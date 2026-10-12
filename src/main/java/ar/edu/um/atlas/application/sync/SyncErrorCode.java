package ar.edu.um.atlas.application.sync;

/**
 * Código funcional de una corrida fallida, con el mensaje corto que queda en {@code sync_state}.
 * Los mensajes son fijos: no incluyen secretos ni detalles internos.
 */
public enum SyncErrorCode {
    CATEDRA_NO_DISPONIBLE("La cátedra no respondió"),
    CATEDRA_CREDENCIAL_RECHAZADA("La cátedra rechazó la credencial"),
    CATEDRA_RESPUESTA_INVALIDA("La respuesta de la cátedra no se pudo interpretar"),
    SNAPSHOT_NO_APLICABLE("El snapshot no se pudo aplicar a la copia local");

    private final String message;

    SyncErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
