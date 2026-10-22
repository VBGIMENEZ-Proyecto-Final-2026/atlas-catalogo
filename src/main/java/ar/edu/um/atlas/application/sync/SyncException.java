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

    /**
     * Describe una falla para el log sin exponer secretos: el código funcional y la clase de la causa (nunca
     * su mensaje, que puede traer detalles de la integración). Para cualquier otra excepción, su clase.
     */
    public static String describe(Throwable failure) {
        if (failure instanceof SyncException s) {
            Throwable cause = s.getCause();
            return s.errorCode + (cause == null ? "" : ", causa " + cause.getClass().getSimpleName());
        }
        return failure.getClass().getSimpleName();
    }
}
