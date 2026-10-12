package ar.edu.um.atlas.application.sync.plan;

import ar.edu.um.atlas.domain.SnapshotReason;

/** Qué hacer para que la copia local alcance a la cátedra. Lo decide {@link SyncPlanner}. */
public sealed interface SyncPlan {

    /** La copia ya está en la versión vigente: no hay nada que aplicar. */
    record Noop() implements SyncPlan {
    }

    /** Aplicar en orden las versiones de {@code from} a {@code to}, ambas incluidas. */
    record Incremental(long from, long to) implements SyncPlan {
    }

    /** Reconstruir la copia desde el snapshot completo, por el motivo indicado. */
    record Snapshot(SnapshotReason reason) implements SyncPlan {
    }
}
