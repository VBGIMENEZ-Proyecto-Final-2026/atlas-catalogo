package ar.edu.um.atlas.application.port.out;

import java.util.List;

/** IDs afectados por una versión del catálogo, por tipo de entidad. No trae las entidades. */
public record VersionChanges(
        long version,
        List<Long> categoryIds,
        List<Long> professionalIds,
        List<Long> weeklyScheduleIds) {

    public VersionChanges {
        categoryIds = List.copyOf(categoryIds);
        professionalIds = List.copyOf(professionalIds);
        weeklyScheduleIds = List.copyOf(weeklyScheduleIds);
    }
}
