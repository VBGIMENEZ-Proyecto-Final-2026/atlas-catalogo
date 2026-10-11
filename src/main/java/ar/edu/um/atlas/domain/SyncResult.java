package ar.edu.um.atlas.domain;

/** Resultado de la última corrida terminada (columna {@code sync_state.last_result}). */
public enum SyncResult {
    NOOP,
    INCREMENTAL,
    SNAPSHOT
}
