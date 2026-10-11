package ar.edu.um.atlas.domain;

/** Estado de la corrida de sincronización (columna {@code sync_state.status}). */
public enum SyncStatus {
    IDLE,
    RUNNING,
    ERROR
}
