package ar.edu.um.atlas.adapters.out.redis;

import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;

/** Convierte los JSON de Redis al modelo propio, exigiendo los campos que el esquema necesita. */
final class RedisEntityMapper {

    private RedisEntityMapper() {
    }

    static ProfessionalCategory toCategory(RedisJson.Category c) {
        return new ProfessionalCategory(
                required(c.id(), "professional-categories.id"),
                required(c.name(), "professional-categories.name"),
                c.description(),
                required(c.enabled(), "professional-categories.enabled"),
                required(c.createdAt(), "professional-categories.createdAt"),
                required(c.updatedAt(), "professional-categories.updatedAt"));
    }

    static Professional toProfessional(RedisJson.Professional p) {
        return new Professional(
                required(p.id(), "professionals.id"),
                required(p.categoryId(), "professionals.categoryId"),
                required(p.firstName(), "professionals.firstName"),
                required(p.lastName(), "professionals.lastName"),
                required(p.enabled(), "professionals.enabled"),
                required(p.createdAt(), "professionals.createdAt"),
                required(p.updatedAt(), "professionals.updatedAt"));
    }

    static WeeklySchedule toSchedule(RedisJson.Schedule s) {
        return new WeeklySchedule(
                required(s.id(), "weekly-schedules.id"),
                required(s.professionalId(), "weekly-schedules.professionalId"),
                required(s.dayOfWeek(), "weekly-schedules.dayOfWeek"),
                required(s.startTime(), "weekly-schedules.startTime"),
                required(s.endTime(), "weekly-schedules.endTime"),
                required(s.slotDurationMinutes(), "weekly-schedules.slotDurationMinutes"),
                required(s.enabled(), "weekly-schedules.enabled"),
                required(s.createdAt(), "weekly-schedules.createdAt"),
                required(s.updatedAt(), "weekly-schedules.updatedAt"));
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw CatedraRedisChangeSource.invalid("Redis trae una entidad sin el campo obligatorio " + field);
        }
        return value;
    }
}
