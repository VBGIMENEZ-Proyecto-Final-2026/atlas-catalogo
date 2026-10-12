package ar.edu.um.atlas.application.port.out;

/** La respuesta no se puede interpretar: cuerpo o JSON inválido, campos obligatorios ausentes, datos incoherentes o un {@code 4xx} inesperado. */
public final class CatedraInvalidResponseException extends CatalogSourceException {

    public CatedraInvalidResponseException(String message, Integer status, String code, Throwable cause) {
        super(message, status, code, cause);
    }
}
