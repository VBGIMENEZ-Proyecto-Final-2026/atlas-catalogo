package ar.edu.um.atlas.application.port.out;

import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot completo del catálogo: la versión y las tres colecciones, ya como entidades del dominio.
 * {@code version} solo se guarda como versión local cuando las tres colecciones quedaron aplicadas.
 */
public record CatalogSnapshot(
        long version,
        Instant generatedAt,
        List<ProfessionalCategory> categories,
        List<Professional> professionals,
        List<WeeklySchedule> weeklySchedules) {

    public CatalogSnapshot {
        categories = List.copyOf(categories);
        professionals = List.copyOf(professionals);
        weeklySchedules = List.copyOf(weeklySchedules);
    }
}
