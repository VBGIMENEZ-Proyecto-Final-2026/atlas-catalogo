package ar.edu.um.atlas.application.port.out;

/**
 * Falla al obtener el catálogo de la cátedra. Se decide por {@code status} y {@code code}, nunca por
 * el texto de {@code detail} (referencia de integración, 13). Los mensajes no incluyen secretos.
 */
public abstract sealed class CatalogSourceException extends RuntimeException
        permits CatedraUnavailableException, CatedraCredentialRejectedException, CatedraInvalidResponseException {

    private final Integer status;
    private final String code;

    protected CatalogSourceException(String message, Integer status, String code, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /** Estado HTTP de la respuesta, o {@code null} si no hubo respuesta. */
    public Integer getStatus() {
        return status;
    }

    /** {@code code} funcional del problema, o {@code null} si la respuesta no lo traía. */
    public String getCode() {
        return code;
    }
}
