package ar.edu.um.atlas.application.port.out;

import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Origen de los cambios incrementales del catálogo (hoy, Redis de la cátedra). Solo lee. Que falte una
 * clave o un ID no es un error: se devuelve vacío o se omite, y decide quien llama (ADR 0003).
 */
public interface CatalogChangeSource {

    /** Metadata de versiones, o vacío si la cátedra no la publicó. */
    Optional<CatalogMetadata> fetchMetadata();

    /** IDs afectados por {@code version}, o vacío si falta {@code changes:{version}}. */
    Optional<VersionChanges> fetchChanges(long version);

    /** Estado vigente de las categorías pedidas; las que no están se omiten. */
    List<ProfessionalCategory> fetchCategories(Collection<Long> ids);

    /** Estado vigente de los profesionales pedidos; los que no están se omiten. */
    List<Professional> fetchProfessionals(Collection<Long> ids);

    /** Estado vigente de los horarios pedidos; los que no están se omiten. */
    List<WeeklySchedule> fetchWeeklySchedules(Collection<Long> ids);
}
