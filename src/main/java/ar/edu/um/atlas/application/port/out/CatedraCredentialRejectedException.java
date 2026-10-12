package ar.edu.um.atlas.application.port.out;

/**
 * La cátedra rechazó la credencial: REST respondió {@code 401} o {@code 403} (el token es inválido o venció),
 * o Redis respondió {@code WRONGPASS}, {@code NOAUTH} o {@code NOPERM}. Se rota en el {@code .env}
 * (ADR 0001); reintentar no sirve.
 */
public final class CatedraCredentialRejectedException extends CatalogSourceException {

    public CatedraCredentialRejectedException(String message, Integer status, String code, Throwable cause) {
        super(message, status, code, cause);
    }
}
