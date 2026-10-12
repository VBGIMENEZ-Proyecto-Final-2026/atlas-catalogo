package ar.edu.um.atlas.application.sync;

import ar.edu.um.atlas.application.port.out.CatalogChangeSource;
import ar.edu.um.atlas.application.port.out.CatalogMetadata;
import ar.edu.um.atlas.application.port.out.CatedraInvalidResponseException;
import ar.edu.um.atlas.application.port.out.VersionChanges;
import ar.edu.um.atlas.application.repository.ProfessionalCategoryRepository;
import ar.edu.um.atlas.application.repository.ProfessionalRepository;
import ar.edu.um.atlas.application.repository.SyncStateRepository;
import ar.edu.um.atlas.application.repository.WeeklyScheduleRepository;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.SyncState;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Aplica los cambios incrementales de la cátedra, una versión por vez (ADR 0003).
 *
 * <p>Cada versión es su propia transacción: toma {@code sync_state} con {@code FOR UPDATE}, comprueba que la
 * versión local siga siendo la anterior, lee {@code changes:{v}} y el estado vigente de cada ID afectado,
 * los aplica con <i>upserts</i> y avanza la versión local. Si algo falla, esa versión se revierte y la
 * versión no avanza; las versiones anteriores ya aplicadas se conservan. Los datos de Redis se leen dentro
 * de la transacción por la misma razón que el pedido del snapshot: ninguna otra corrida puede avanzar la
 * versión en el medio.
 *
 * <p>Qué es una falla de datos (se escala a snapshot) y qué una falla de infraestructura (queda el error y
 * se reintenta en la próxima verificación) se decide acá; el snapshot lo pide quien llama.
 */
@Service
public class ApplyIncremental {

    private static final Logger log = LoggerFactory.getLogger(ApplyIncremental.class);

    /** Resultado de una corrida incremental. */
    public sealed interface Result {

        /** Se aplicaron las versiones posibles; la copia quedó en la última aplicada. */
        record Done(int applied) implements Result {
        }

        /** Hay que reconstruir con un snapshot por este motivo; la copia quedó consistente. */
        record Escalate(SnapshotReason reason) implements Result {
        }

        /** Otra corrida avanzó la versión mientras tanto: hay que volver a planificar. */
        record Superseded() implements Result {
        }
    }

    private enum Step {APPLIED, SUPERSEDED}

    private record VersionResult(Step step, int absent) {
    }

    /** Aborta y revierte la versión en curso porque la copia local debe reconstruirse. */
    private static final class Escalation extends RuntimeException {
        private final SnapshotReason reason;

        Escalation(SnapshotReason reason, String message) {
            super(message, null, false, false);
            this.reason = reason;
        }
    }

    private final CatalogChangeSource source;
    private final ProfessionalCategoryRepository categories;
    private final ProfessionalRepository professionals;
    private final WeeklyScheduleRepository schedules;
    private final SyncStateRepository syncState;
    private final SyncStateRecorder recorder;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SyncLimits limits;

    public ApplyIncremental(CatalogChangeSource source, ProfessionalCategoryRepository categories,
                            ProfessionalRepository professionals, WeeklyScheduleRepository schedules,
                            SyncStateRepository syncState, SyncStateRecorder recorder,
                            PlatformTransactionManager transactionManager, Clock clock, SyncLimits limits) {
        this.source = source;
        this.categories = categories;
        this.professionals = professionals;
        this.schedules = schedules;
        this.syncState = syncState;
        this.recorder = recorder;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.limits = limits;
    }

    /**
     * Aplica las versiones de {@code from} a {@code to} y, al terminar, vuelve a leer la metadata: si la
     * versión vigente subió, sigue. Se detiene al llegar a {@code maxVersionsPerRun}.
     *
     * @throws SyncException si Redis o la cátedra fallan o no se puede aplicar por un motivo que un snapshot
     *                       no arreglaría; el error queda en {@code sync_state}
     */
    public Result run(long from, long to) {
        long next = from;
        long target = to;
        int applied = 0;
        int absent = 0;
        while (true) {
            while (next <= target && applied < limits.maxVersionsPerRun()) {
                VersionResult result;
                try {
                    result = applyVersion(next, absent);
                } catch (Escalation e) {
                    log.warn("La versión {} no se puede aplicar ({}): se reconstruye con un snapshot por {}",
                            next, e.getMessage(), e.reason);
                    return new Result.Escalate(e.reason);
                } catch (DataIntegrityViolationException e) {
                    log.warn("La versión {} rompe una referencia de la copia local: se reconstruye con un "
                            + "snapshot", next);
                    return new Result.Escalate(SnapshotReason.NO_VERIFICABLE);
                } catch (CatedraInvalidResponseException e) {
                    log.warn("Redis trae datos inválidos en la versión {} ({}): se reconstruye con un snapshot",
                            next, e.getMessage());
                    return new Result.Escalate(SnapshotReason.NO_VERIFICABLE);
                } catch (RuntimeException e) {
                    throw fail(e);
                }
                if (result.step() == Step.SUPERSEDED) {
                    return new Result.Superseded();
                }
                absent += result.absent();
                applied++;
                next++;
            }
            if (applied >= limits.maxVersionsPerRun()) {
                break;
            }
            long current = currentVersion(target);
            if (current <= target) {
                break;
            }
            target = current;
        }
        if (applied >= limits.maxVersionsPerRun() && next <= target) {
            log.info("Se alcanzó el máximo de {} versiones por corrida: la próxima verificación continúa",
                    limits.maxVersionsPerRun());
        }
        return new Result.Done(applied);
    }

    /** Relee la metadata y devuelve la versión vigente, o {@code fallback} si la cátedra no la publicó. */
    private long currentVersion(long fallback) {
        try {
            return source.fetchMetadata().map(CatalogMetadata::currentVersion).orElse(fallback);
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    private SyncException fail(RuntimeException e) {
        SyncErrorCode code = SyncErrorCode.of(e, SyncErrorCode.INCREMENTAL_NO_APLICABLE);
        recorder.recordError(code);
        return new SyncException(code, e);
    }

    private VersionResult applyVersion(long version, int absentSoFar) {
        return transaction.execute(status -> {
            SyncState state = syncState.findForUpdate();
            if (state.getLocalVersion() == null || state.getLocalVersion() != version - 1) {
                return new VersionResult(Step.SUPERSEDED, 0);
            }
            VersionChanges changes = source.fetchChanges(version)
                    .orElseThrow(() -> new Escalation(SnapshotReason.FALTA_VERSION,
                            "falta changes:" + version));

            List<ProfessionalCategory> cats = source.fetchCategories(changes.categoryIds());
            List<Professional> pros = source.fetchProfessionals(changes.professionalIds());
            List<WeeklySchedule> scheds = source.fetchWeeklySchedules(changes.weeklyScheduleIds());

            Set<Long> missingCats = missing(changes.categoryIds(), cats, ProfessionalCategory::getId);
            Set<Long> missingPros = missing(changes.professionalIds(), pros, Professional::getId);
            Set<Long> missingScheds = missing(changes.weeklyScheduleIds(), scheds, WeeklySchedule::getId);
            int absent = missingCats.size() + missingPros.size() + missingScheds.size();
            if (absentSoFar + absent >= 2) {
                throw new Escalation(SnapshotReason.NO_VERIFICABLE,
                        "más de un ID de los cambios no está en Redis");
            }
            if (absent == 1) {
                log.warn("Un ID de changes:{} ya no está en Redis (categorías {}, profesionales {}, horarios {}): "
                        + "se deshabilita localmente", version, missingCats, missingPros, missingScheds);
            }
            categories.disableAllIn(array(missingCats));
            professionals.disableAllIn(array(missingPros));
            schedules.disableAllIn(array(missingScheds));

            cats.forEach(categories::upsert);
            pros.forEach(professionals::upsert);
            scheds.forEach(schedules::upsert);

            state.registrarIncremental(version, clock.instant());
            log.info("Versión {} aplicada: {} categorías, {} profesionales y {} horarios",
                    version, cats.size(), pros.size(), scheds.size());
            return new VersionResult(Step.APPLIED, absent);
        });
    }

    private static <T> Set<Long> missing(Collection<Long> requested, List<T> found, Function<T, Long> id) {
        Set<Long> missing = new HashSet<>(requested);
        found.forEach(item -> missing.remove(id.apply(item)));
        return missing;
    }

    private static Long[] array(Set<Long> ids) {
        return ids.toArray(Long[]::new);
    }
}
