package ar.edu.um.atlas.application.port.out;

/** La cátedra no responde: error de red o de conexión, timeout, {@code 5xx} (REST) o Redis caído. Es transitoria. */
public final class CatedraUnavailableException extends CatalogSourceException {

    public CatedraUnavailableException(String message, Integer status, String code, Throwable cause) {
        super(message, status, code, cause);
    }
}
