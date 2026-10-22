package ar.edu.um.atlas.application.search;

import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;

/**
 * Disponibilidad efectiva (decisión F del modelo de datos): el profesional y su categoría están habilitados
 * y tiene al menos un horario semanal habilitado (del día pedido, si se informa). Un profesional
 * deshabilitado, o en una categoría deshabilitada, no se ofrece aunque conserve horarios habilitados: la
 * cátedra rechazaría reservarle un turno ({@code PROFESSIONAL_DISABLED}).
 *
 * <p>No depende del filtro literal {@code habilitado}: se combina con él como cualquier otra
 * {@link Specification}. Usa {@code EXISTS}, así que un profesional con varios horarios no se repite.
 */
@Component
public class ScheduleAvailabilityFilter implements AvailabilityFilter {

    @Override
    public Specification<Professional> available(DayOfWeek dayOfWeek) {
        return (professional, query, cb) -> {
            Subquery<Long> category = query.subquery(Long.class);
            var c = category.from(ProfessionalCategory.class);
            category.select(c.get("id")).where(
                    cb.equal(c.get("id"), professional.get("categoryId")),
                    cb.isTrue(c.get("enabled")));

            Subquery<Long> schedule = query.subquery(Long.class);
            var s = schedule.from(WeeklySchedule.class);
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(cb.equal(s.get("professionalId"), professional.get("id")));
            conditions.add(cb.isTrue(s.get("enabled")));
            if (dayOfWeek != null) {
                conditions.add(cb.equal(s.get("dayOfWeek"), dayOfWeek));
            }
            schedule.select(s.get("id")).where(conditions.toArray(Predicate[]::new));

            return cb.and(cb.isTrue(professional.get("enabled")), cb.exists(category), cb.exists(schedule));
        };
    }
}
