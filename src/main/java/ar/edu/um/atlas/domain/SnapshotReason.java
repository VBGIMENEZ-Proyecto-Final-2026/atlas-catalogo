package ar.edu.um.atlas.domain;

/** Motivo por el que se pidió un snapshot (columna {@code sync_state.last_snapshot_reason}). */
public enum SnapshotReason {
    BASE_VACIA,
    NO_VERIFICABLE,
    LOCAL_ADELANTADA,
    FUERA_DE_VENTANA,
    FALTA_VERSION
}
