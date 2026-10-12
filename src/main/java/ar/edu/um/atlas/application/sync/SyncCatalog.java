package ar.edu.um.atlas.application.sync;

import ar.edu.um.atlas.application.port.out.CatalogChangeSource;
import ar.edu.um.atlas.application.port.out.CatalogMetadata;
import ar.edu.um.atlas.application.port.out.CatedraInvalidResponseException;
import ar.edu.um.atlas.application.repository.SyncStateRepository;
import ar.edu.um.atlas.application.sync.plan.SyncPlan;
import ar.edu.um.atlas.application.sync.plan.SyncPlanner;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.SyncState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Pone la copia local al día con la cátedra: compara la versión local con la metadata de Redis, decide con
 * {@link SyncPlanner} y ejecuta el plan (nada, cambios incrementales o snapshot). Es el único caso de uso
 * que usan los disparadores (arranque, verificación periódica y, más adelante, el aviso de Kafka), así que
 * un disparo duplicado, atrasado o perdido produce el mismo resultado (ADR 0003, R3).
 *
 * <p>No es transaccional: cada paso abre sus propias transacciones. Un disparo nunca encadena más de un
 * snapshot. Una falla transitoria de Redis o de la cátedra no se escala a snapshot: queda el error y la
 * siguiente verificación lo reintenta.
 */
@Service
public class SyncCatalog {

    private static final Logger log = LoggerFactory.getLogger(SyncCatalog.class);

    /** Veces que se vuelve a planificar si otra corrida avanzó la versión mientras esta aplicaba cambios. */
    private static final int MAX_ROUNDS = 3;

    private final SyncStateRepository syncState;
    private final CatalogChangeSource changeSource;
    private final ApplyIncremental applyIncremental;
    private final ApplySnapshot applySnapshot;
    private final SyncStateRecorder recorder;

    public SyncCatalog(SyncStateRepository syncState, CatalogChangeSource changeSource,
                       ApplyIncremental applyIncremental, ApplySnapshot applySnapshot,
                       SyncStateRecorder recorder) {
        this.syncState = syncState;
        this.changeSource = changeSource;
        this.applyIncremental = applyIncremental;
        this.applySnapshot = applySnapshot;
        this.recorder = recorder;
    }

    /**
     * @throws SyncException si la corrida falló; la copia quedó como estaba y el error quedó registrado
     */
    public void run(SyncTrigger trigger) {
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            Long local = syncState.findById(SyncState.ID).map(SyncState::getLocalVersion).orElse(null);
            if (local == null) {
                // La primera carga no depende de Redis.
                log.info("[{}] Base vacía: se pide el snapshot completo de la cátedra", trigger);
                applySnapshot.run(SnapshotReason.BASE_VACIA);
                return;
            }
            SyncPlan plan = SyncPlanner.plan(local, readMetadata());
            log.debug("[{}] Versión local {}: {}", trigger, local, plan);
            switch (plan) {
                case SyncPlan.Noop ignored -> {
                    recorder.recordNoop();
                    return;
                }
                case SyncPlan.Snapshot snapshot -> {
                    log.info("[{}] Se reconstruye la copia con un snapshot (motivo {})", trigger, snapshot.reason());
                    applySnapshot.run(snapshot.reason());
                    return;
                }
                case SyncPlan.Incremental incremental -> {
                    switch (applyIncremental.run(incremental.from(), incremental.to())) {
                        case ApplyIncremental.Result.Done ignored -> {
                            return;
                        }
                        case ApplyIncremental.Result.Escalate escalate -> {
                            applySnapshot.run(escalate.reason());
                            return;
                        }
                        case ApplyIncremental.Result.Superseded ignored ->
                                log.info("[{}] Otra corrida avanzó la versión: se vuelve a planificar", trigger);
                    }
                }
            }
        }
        log.warn("[{}] La versión local siguió cambiando durante {} planificaciones: la próxima verificación "
                + "continúa", trigger, MAX_ROUNDS);
    }

    /**
     * Lee la metadata. Si Redis no la tiene o no se puede interpretar, el estado no es verificable (vacío).
     * Si Redis o la cátedra fallan, queda el error y no se escala a snapshot.
     */
    private Optional<CatalogMetadata> readMetadata() {
        try {
            return changeSource.fetchMetadata();
        } catch (CatedraInvalidResponseException e) {
            log.warn("La metadata de Redis no se pudo interpretar: el estado no es verificable");
            return Optional.empty();
        } catch (RuntimeException e) {
            SyncErrorCode code = SyncErrorCode.of(e, SyncErrorCode.INCREMENTAL_NO_APLICABLE);
            recorder.recordError(code);
            throw new SyncException(code, e);
        }
    }
}
