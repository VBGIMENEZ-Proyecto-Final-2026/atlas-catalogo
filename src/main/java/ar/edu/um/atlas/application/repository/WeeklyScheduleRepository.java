package ar.edu.um.atlas.application.repository;

import ar.edu.um.atlas.domain.WeeklySchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalTime;

public interface WeeklyScheduleRepository extends JpaRepository<WeeklySchedule, Long> {

    /** Inserta o actualiza por ID; repetirlo no duplica ni falla (decisión B). */
    default void upsert(WeeklySchedule s) {
        upsert(s.getId(), s.getProfessionalId(), s.getDayOfWeek().name(), s.getStartTime(), s.getEndTime(),
                s.getSlotDurationMinutes(), s.isEnabled(), s.getCreatedAt(), s.getUpdatedAt());
    }

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO weekly_schedule (id, professional_id, day_of_week, start_time, end_time,
                                         slot_duration_minutes, enabled, created_at, updated_at)
            VALUES (:id, :professionalId, :dayOfWeek, :startTime, :endTime,
                    :slotDurationMinutes, :enabled, :createdAt, :updatedAt)
            ON CONFLICT (id) DO UPDATE SET
                professional_id = EXCLUDED.professional_id,
                day_of_week = EXCLUDED.day_of_week,
                start_time = EXCLUDED.start_time,
                end_time = EXCLUDED.end_time,
                slot_duration_minutes = EXCLUDED.slot_duration_minutes,
                enabled = EXCLUDED.enabled,
                created_at = EXCLUDED.created_at,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(@Param("id") Long id, @Param("professionalId") Long professionalId,
                @Param("dayOfWeek") String dayOfWeek, @Param("startTime") LocalTime startTime,
                @Param("endTime") LocalTime endTime, @Param("slotDurationMinutes") int slotDurationMinutes,
                @Param("enabled") boolean enabled, @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt);

    /**
     * Deshabilita las filas habilitadas cuyo ID no está en {@code ids} (nunca borra, decisión C). Con un
     * arreglo vacío deshabilita todas. No toca {@code updated_at}: son valores de la cátedra (decisión J).
     */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE weekly_schedule SET enabled = false WHERE enabled AND id <> ALL (:ids)")
    int disableAllExcept(@Param("ids") Long[] ids);
}
