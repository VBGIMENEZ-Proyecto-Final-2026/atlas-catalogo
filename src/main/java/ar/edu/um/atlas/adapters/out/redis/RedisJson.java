package ar.edu.um.atlas.adapters.out.redis;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Forma de los JSON de Redis (referencia de integración, 14.4 y 14.5). Los campos desconocidos se
 * ignoran; los obligatorios se validan al convertir al modelo propio.
 */
final class RedisJson {

    private RedisJson() {
    }

    record Changes(Long version, ChangeIds changes) {
    }

    record ChangeIds(List<Long> professionalCategories, List<Long> professionals, List<Long> weeklySchedules) {
    }

    record Category(Long id, String name, String description, Boolean enabled,
                    Instant createdAt, Instant updatedAt) {
    }

    record Professional(Long id, Long categoryId, String firstName, String lastName, Boolean enabled,
                        Instant createdAt, Instant updatedAt) {
    }

    record Schedule(Long id, Long professionalId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime,
                    Integer slotDurationMinutes, Boolean enabled, Instant createdAt, Instant updatedAt) {
    }
}
