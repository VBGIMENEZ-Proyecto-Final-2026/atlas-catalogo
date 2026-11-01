package ar.edu.um.atlas.application.search;

/** No hay un profesional con ese ID en la copia local. */
public class ProfessionalNotFoundException extends RuntimeException {

    public ProfessionalNotFoundException(long id) {
        super("No existe el profesional " + id);
    }
}
