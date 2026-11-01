package ar.edu.um.atlas.application.search;

import ar.edu.um.atlas.domain.Professional;
import org.springframework.data.jpa.domain.Specification;

import java.time.DayOfWeek;

/**
 * Filtro "disponibilidad" de la búsqueda de profesionales. Es una interfaz para poder reemplazar la regla (por
 * ejemplo por una que considere turnos libres reales) sin tocar a quien la usa.
 *
 * <p>La versión básica (decisión A del modelo de datos) dice que el profesional atiende, no que le quedan
 * turnos: los turnos libres dependen de las ocupaciones, que viven en la cátedra y se combinan en
 * {@code cronos-turnos}.
 */
public interface AvailabilityFilter {

    /**
     * Profesionales disponibles, para combinar con los demás filtros de la búsqueda.
     *
     * @param dayOfWeek si no es {@code null}, el horario habilitado debe ser de ese día
     */
    Specification<Professional> available(DayOfWeek dayOfWeek);
}
