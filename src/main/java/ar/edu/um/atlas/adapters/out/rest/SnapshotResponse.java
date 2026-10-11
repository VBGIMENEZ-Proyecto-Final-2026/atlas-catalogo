package ar.edu.um.atlas.adapters.out.rest;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Forma del JSON de {@code GET /api/synchronization/snapshot} (referencia de integración, 7).
 * Los campos desconocidos se ignoran; los obligatorios se validan al convertir al modelo propio.
 */
record SnapshotResponse(
        Long snapshotVersion,
        Instant generatedAt,
        List<CategoryJson> professionalCategories,
        List<ProfessionalJson> professionals,
        List<ScheduleJson> weeklySchedules) {

    record CategoryJson(Long id, String name, String description, Boolean enabled,
                        Instant createdAt, Instant updatedAt) {
    }

    record ProfessionalJson(Long id, Long categoryId, String firstName, String lastName, Boolean enabled,
                            Instant createdAt, Instant updatedAt) {
    }

    record ScheduleJson(Long id, Long professionalId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime,
                        Integer slotDurationMinutes, Boolean enabled, Instant createdAt, Instant updatedAt) {
    }
}
