package ar.edu.um.atlas.application.port.out;

/** La cátedra no responde: error de red, timeout o {@code 5xx}. Es transitoria. */
public final class CatedraUnavailableException extends CatalogSourceException {

    public CatedraUnavailableException(String message, Integer status, String code, Throwable cause) {
        super(message, status, code, cause);
    }
}
