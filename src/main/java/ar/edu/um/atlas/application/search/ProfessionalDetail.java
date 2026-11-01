package ar.edu.um.atlas.application.search;

import java.util.List;

/** Un profesional con todos sus horarios semanales, ordenados por día y hora de inicio. */
public record ProfessionalDetail(
        Long id,
        String firstName,
        String lastName,
        boolean enabled,
        CategoryView category,
        List<ScheduleView> schedules) {
}
