package ar.edu.um.atlas.application.sync;

/**
 * La corrida de sincronización falló (completa o incremental); la copia local quedó como estaba y el error
 * quedó registrado en {@code sync_state}.
 */
public class SyncException extends RuntimeException {

    private final SyncErrorCode errorCode;

    public SyncException(SyncErrorCode errorCode, Throwable cause) {
        super(errorCode.name() + ": " + errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    public SyncErrorCode getErrorCode() {
        return errorCode;
    }
}
