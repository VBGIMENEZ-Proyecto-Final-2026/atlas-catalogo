package ar.edu.um.atlas.adapters.out.rest;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatedraInvalidResponseException;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;

import java.util.List;

/** Convierte la respuesta de la cátedra al modelo propio, exigiendo los campos que el esquema necesita. */
final class SnapshotMapper {

    private SnapshotMapper() {
    }

    static CatalogSnapshot toSnapshot(SnapshotResponse r) {
        return new CatalogSnapshot(
                required(r.snapshotVersion(), "snapshotVersion"),
                r.generatedAt(),
                requiredList(r.professionalCategories(), "professionalCategories").stream()
                        .map(SnapshotMapper::toCategory).toList(),
                requiredList(r.professionals(), "professionals").stream()
                        .map(SnapshotMapper::toProfessional).toList(),
                requiredList(r.weeklySchedules(), "weeklySchedules").stream()
                        .map(SnapshotMapper::toSchedule).toList());
    }

    private static ProfessionalCategory toCategory(SnapshotResponse.CategoryJson c) {
        return new ProfessionalCategory(
                required(c.id(), "professionalCategories.id"),
                required(c.name(), "professionalCategories.name"),
                c.description(),
                required(c.enabled(), "professionalCategories.enabled"),
                required(c.createdAt(), "professionalCategories.createdAt"),
                required(c.updatedAt(), "professionalCategories.updatedAt"));
    }

    private static Professional toProfessional(SnapshotResponse.ProfessionalJson p) {
        return new Professional(
                required(p.id(), "professionals.id"),
                required(p.categoryId(), "professionals.categoryId"),
                required(p.firstName(), "professionals.firstName"),
                required(p.lastName(), "professionals.lastName"),
                required(p.enabled(), "professionals.enabled"),
                required(p.createdAt(), "professionals.createdAt"),
                required(p.updatedAt(), "professionals.updatedAt"));
    }

    private static WeeklySchedule toSchedule(SnapshotResponse.ScheduleJson s) {
        return new WeeklySchedule(
                required(s.id(), "weeklySchedules.id"),
                required(s.professionalId(), "weeklySchedules.professionalId"),
                required(s.dayOfWeek(), "weeklySchedules.dayOfWeek"),
                required(s.startTime(), "weeklySchedules.startTime"),
                required(s.endTime(), "weeklySchedules.endTime"),
                required(s.slotDurationMinutes(), "weeklySchedules.slotDurationMinutes"),
                required(s.enabled(), "weeklySchedules.enabled"),
                required(s.createdAt(), "weeklySchedules.createdAt"),
                required(s.updatedAt(), "weeklySchedules.updatedAt"));
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new CatedraInvalidResponseException(
                    "El snapshot de la cátedra no trae el campo obligatorio " + field, null, null, null);
        }
        return value;
    }

    private static <T> List<T> requiredList(List<T> value, String field) {
        return required(value, field);
    }
}
