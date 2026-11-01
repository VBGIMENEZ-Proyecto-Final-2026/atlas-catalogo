package ar.edu.um.atlas.application.search;

/** Los parámetros de la búsqueda no son válidos. El mensaje es fijo y no incluye el valor recibido. */
public class InvalidSearchException extends RuntimeException {

    public InvalidSearchException(String message) {
        super(message);
    }
}
