package ar.edu.um.atlas.application.sync.plan;

import ar.edu.um.atlas.application.port.out.CatalogMetadata;
import ar.edu.um.atlas.domain.SnapshotReason;

import java.util.Optional;

/**
 * Decide entre {@code Noop}, {@code Incremental} y {@code Snapshot} comparando la versión local con la
 * metadata de la cátedra (tabla del ADR 0003). Es una función pura: no usa Spring, Redis, REST ni base de
 * datos, y el aviso de Kafka nunca interviene (R1 y R3).
 *
 * <p>La fila 5 ({@code local < oldest}) sigue la lectura literal de la referencia de integración (14.2 y
 * 18.2) hasta que el docente responda la consulta 10.
 */
public final class SyncPlanner {

    private SyncPlanner() {
    }

    /**
     * @param localVersion versión local, o {@code null} si la base está vacía
     * @param metadata     metadata remota, o vacía si la cátedra no la publicó o no se pudo interpretar
     */
    public static SyncPlan plan(Long localVersion, Optional<CatalogMetadata> metadata) {
        if (localVersion == null) {
            return new SyncPlan.Snapshot(SnapshotReason.BASE_VACIA);
        }
        if (metadata.isEmpty() || metadata.get().currentVersion() < metadata.get().oldestAvailableVersion()) {
            return new SyncPlan.Snapshot(SnapshotReason.NO_VERIFICABLE);
        }
        long local = localVersion;
        long current = metadata.get().currentVersion();
        long oldest = metadata.get().oldestAvailableVersion();
        if (local > current) {
            return new SyncPlan.Snapshot(SnapshotReason.LOCAL_ADELANTADA);
        }
        if (local == current) {
            return new SyncPlan.Noop();
        }
        if (local < oldest) {
            return new SyncPlan.Snapshot(SnapshotReason.FUERA_DE_VENTANA);
        }
        return new SyncPlan.Incremental(local + 1, current);
    }
}
