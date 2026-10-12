package ar.edu.um.atlas.application.repository;

import ar.edu.um.atlas.domain.ProfessionalCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProfessionalCategoryRepository extends JpaRepository<ProfessionalCategory, Long> {

    /** Inserta o actualiza por ID; repetirlo no duplica ni falla (decisión B). */
    default void upsert(ProfessionalCategory c) {
        upsert(c.getId(), c.getName(), c.getDescription(), c.isEnabled(), c.getCreatedAt(), c.getUpdatedAt());
    }

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO professional_category (id, name, description, enabled, created_at, updated_at)
            VALUES (:id, :name, CAST(:description AS text), :enabled, :createdAt, :updatedAt)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                enabled = EXCLUDED.enabled,
                created_at = EXCLUDED.created_at,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(@Param("id") Long id, @Param("name") String name, @Param("description") String description,
                @Param("enabled") boolean enabled, @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt);

    /**
     * Deshabilita las filas habilitadas cuyo ID no está en {@code ids} (nunca borra, decisión C). Con un
     * arreglo vacío deshabilita todas. No toca {@code updated_at}: son valores de la cátedra (decisión J).
     */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE professional_category SET enabled = false WHERE enabled AND id <> ALL (:ids)")
    int disableAllExcept(@Param("ids") Long[] ids);

    /**
     * Deshabilita las filas habilitadas cuyo ID está en {@code ids} (baja por ID ausente en Redis, decisión
     * C). No toca {@code updated_at}: son valores de la cátedra (decisión J).
     */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE professional_category SET enabled = false WHERE enabled AND id = ANY (:ids)")
    int disableAllIn(@Param("ids") Long[] ids);
}
