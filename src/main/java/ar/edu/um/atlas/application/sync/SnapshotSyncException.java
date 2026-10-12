package ar.edu.um.atlas.application.sync;

/** La corrida de sincronización falló; la copia local quedó como estaba y el error quedó registrado. */
public class SnapshotSyncException extends RuntimeException {

    private final SyncErrorCode errorCode;

    public SnapshotSyncException(SyncErrorCode errorCode, Throwable cause) {
        super(errorCode.name() + ": " + errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    public SyncErrorCode getErrorCode() {
        return errorCode;
    }
}
