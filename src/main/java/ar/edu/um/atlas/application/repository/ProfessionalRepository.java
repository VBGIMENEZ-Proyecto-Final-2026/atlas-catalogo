package ar.edu.um.atlas.application.repository;

import ar.edu.um.atlas.domain.Professional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProfessionalRepository extends JpaRepository<Professional, Long> {

    /** Inserta o actualiza por ID; repetirlo no duplica ni falla (decisión B). */
    default void upsert(Professional p) {
        upsert(p.getId(), p.getCategoryId(), p.getFirstName(), p.getLastName(), p.isEnabled(),
                p.getCreatedAt(), p.getUpdatedAt());
    }

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO professional (id, category_id, first_name, last_name, enabled, created_at, updated_at)
            VALUES (:id, :categoryId, :firstName, :lastName, :enabled, :createdAt, :updatedAt)
            ON CONFLICT (id) DO UPDATE SET
                category_id = EXCLUDED.category_id,
                first_name = EXCLUDED.first_name,
                last_name = EXCLUDED.last_name,
                enabled = EXCLUDED.enabled,
                created_at = EXCLUDED.created_at,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(@Param("id") Long id, @Param("categoryId") Long categoryId, @Param("firstName") String firstName,
                @Param("lastName") String lastName, @Param("enabled") boolean enabled,
                @Param("createdAt") Instant createdAt, @Param("updatedAt") Instant updatedAt);

    /**
     * Deshabilita las filas habilitadas cuyo ID no está en {@code ids} (nunca borra, decisión C). Con un
     * arreglo vacío deshabilita todas. No toca {@code updated_at}: son valores de la cátedra (decisión J).
     */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE professional SET enabled = false WHERE enabled AND id <> ALL (:ids)")
    int disableAllExcept(@Param("ids") Long[] ids);

    /**
     * Deshabilita las filas habilitadas cuyo ID está en {@code ids} (baja por ID ausente en Redis, decisión
     * C). No toca {@code updated_at}: son valores de la cátedra (decisión J).
     */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE professional SET enabled = false WHERE enabled AND id = ANY (:ids)")
    int disableAllIn(@Param("ids") Long[] ids);
}
