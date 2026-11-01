package ar.edu.um.atlas.application.search;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** Horario semanal de un profesional, con su propio estado habilitado. */
public record ScheduleView(
        Long id,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        int slotDurationMinutes,
        boolean enabled) {
}
