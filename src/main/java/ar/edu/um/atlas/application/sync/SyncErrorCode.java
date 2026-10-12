package ar.edu.um.atlas.application.sync;

import ar.edu.um.atlas.application.port.out.CatalogSourceException;
import ar.edu.um.atlas.application.port.out.CatedraCredentialRejectedException;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;

/**
 * Código funcional de una corrida fallida, con el mensaje corto que queda en {@code sync_state}.
 * Los mensajes son fijos: no incluyen secretos ni detalles internos.
 */
public enum SyncErrorCode {
    CATEDRA_NO_DISPONIBLE("La cátedra no respondió"),
    CATEDRA_CREDENCIAL_RECHAZADA("La cátedra rechazó la credencial"),
    CATEDRA_RESPUESTA_INVALIDA("La respuesta de la cátedra no se pudo interpretar"),
    SNAPSHOT_NO_APLICABLE("El snapshot no se pudo aplicar a la copia local"),
    INCREMENTAL_NO_APLICABLE("Los cambios incrementales no se pudieron aplicar a la copia local");

    private final String message;

    SyncErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    /**
     * Traduce una falla al código funcional: las del puerto por su tipo y cualquier otra con {@code fallback}
     * (el código de "no aplicable" de la operación que estaba corriendo).
     */
    public static SyncErrorCode of(RuntimeException e, SyncErrorCode fallback) {
        return switch (e) {
            case CatedraUnavailableException ignored -> CATEDRA_NO_DISPONIBLE;
            case CatedraCredentialRejectedException ignored -> CATEDRA_CREDENCIAL_RECHAZADA;
            case CatalogSourceException ignored -> CATEDRA_RESPUESTA_INVALIDA;
            default -> fallback;
        };
    }
}
