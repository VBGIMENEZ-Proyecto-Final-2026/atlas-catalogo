package ar.edu.um.atlas.application.sync;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatalogSnapshotSource;
import ar.edu.um.atlas.application.repository.ProfessionalCategoryRepository;
import ar.edu.um.atlas.application.repository.ProfessionalRepository;
import ar.edu.um.atlas.application.repository.SyncStateRepository;
import ar.edu.um.atlas.application.repository.WeeklyScheduleRepository;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.function.Function;

/**
 * Inicializa o reconstruye la copia local desde el snapshot completo de la cátedra (ADR 0003).
 *
 * <p>Toda la corrida es una sola transacción que empieza tomando {@code sync_state} con
 * {@code FOR UPDATE}: dos corridas se serializan y, si algo falla, no queda nada a medias. El pedido a
 * la cátedra va dentro de esa transacción para que una corrida lenta no pueda aplicar su snapshot después
 * de otra más nueva y hacer retroceder la copia. El error se registra en una transacción aparte, ya con
 * la principal revertida y el candado liberado.
 */
@Service
public class ApplySnapshot {

    private static final Logger log = LoggerFactory.getLogger(ApplySnapshot.class);

    private final CatalogSnapshotSource source;
    private final ProfessionalCategoryRepository categories;
    private final ProfessionalRepository professionals;
    private final WeeklyScheduleRepository schedules;
    private final SyncStateRepository syncState;
    private final SyncStateRecorder recorder;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ApplySnapshot(CatalogSnapshotSource source, ProfessionalCategoryRepository categories,
                         ProfessionalRepository professionals, WeeklyScheduleRepository schedules,
                         SyncStateRepository syncState, SyncStateRecorder recorder,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.source = source;
        this.categories = categories;
        this.professionals = professionals;
        this.schedules = schedules;
        this.syncState = syncState;
        this.recorder = recorder;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Pide el snapshot y lo aplica. Si falla, la copia local queda intacta, {@code sync_state} queda en
     * {@code ERROR} y se lanza {@link SyncException}.
     */
    public void run(SnapshotReason reason) {
        try {
            long version = transaction.execute(status -> apply(reason));
            log.info("Snapshot aplicado: versión local {} (motivo {})", version, reason);
        } catch (RuntimeException e) {
            SyncErrorCode code = SyncErrorCode.of(e, SyncErrorCode.SNAPSHOT_NO_APLICABLE);
            recorder.recordError(code);
            throw new SyncException(code, e);
        }
    }

    private long apply(SnapshotReason reason) {
        var state = syncState.findForUpdate();
        CatalogSnapshot snapshot = source.fetchSnapshot();

        List<ProfessionalCategory> cats = snapshot.categories();
        List<Professional> pros = snapshot.professionals();
        List<WeeklySchedule> scheds = snapshot.weeklySchedules();

        cats.forEach(categories::upsert);
        pros.forEach(professionals::upsert);
        scheds.forEach(schedules::upsert);

        categories.disableAllExcept(ids(cats, ProfessionalCategory::getId));
        professionals.disableAllExcept(ids(pros, Professional::getId));
        schedules.disableAllExcept(ids(scheds, WeeklySchedule::getId));

        state.registrarSnapshotAplicado(snapshot.version(), reason, clock.instant());
        return snapshot.version();
    }

    private static <T> Long[] ids(List<T> items, Function<T, Long> id) {
        return items.stream().map(id).toArray(Long[]::new);
    }
}
