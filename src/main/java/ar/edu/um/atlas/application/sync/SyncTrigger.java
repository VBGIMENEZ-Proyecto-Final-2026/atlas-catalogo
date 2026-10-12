package ar.edu.um.atlas.application.sync;

/** Qué disparó una corrida de sincronización. Solo sirve para el log: la decisión no depende de esto. */
public enum SyncTrigger {
    ARRANQUE,
    VERIFICACION_PERIODICA
}
