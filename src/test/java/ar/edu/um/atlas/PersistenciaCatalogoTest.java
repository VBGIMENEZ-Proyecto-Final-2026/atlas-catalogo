package ar.edu.um.atlas;

import ar.edu.um.atlas.application.repository.ProfessionalCategoryRepository;
import ar.edu.um.atlas.application.repository.ProfessionalRepository;
import ar.edu.um.atlas.application.repository.SyncStateRepository;
import ar.edu.um.atlas.application.repository.WeeklyScheduleRepository;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.SyncResult;
import ar.edu.um.atlas.domain.SyncState;
import ar.edu.um.atlas.domain.SyncStatus;
import ar.edu.um.atlas.domain.WeeklySchedule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistencia contra PostgreSQL real: Flyway crea el esquema y Hibernate lo valida
 * ({@code ddl-auto=validate}). Cada test se revierte al terminar.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PersistenciaCatalogoTest {

    private static final Instant CREADO = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-03-02T15:30:45Z");

    @Autowired ProfessionalCategoryRepository categorias;
    @Autowired ProfessionalRepository profesionales;
    @Autowired WeeklyScheduleRepository horarios;
    @Autowired SyncStateRepository syncState;
    @Autowired JdbcClient jdbc;
    @Autowired EntityManager em;

    @Test
    void flywayCreaLasCuatroTablasDesdeUnaBaseVacia() {
        List<String> tablas = jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """).query(String.class).list();

        assertThat(tablas).containsExactly("professional", "professional_category", "sync_state", "weekly_schedule");
    }

    @Test
    void existeElIndiceParcialDeHorarios() {
        String definicion = jdbc.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'weekly_schedule' AND indexname = 'weekly_schedule_day_professional_enabled_idx'
                """).query(String.class).single();

        assertThat(definicion).contains("(day_of_week, professional_id)").contains("WHERE enabled");
    }

    @Test
    void categoriaIdaYVuelta() {
        categorias.upsert(new ProfessionalCategory(10L, "Cardiología", "Corazón", true, CREADO, ACTUALIZADO));
        em.clear();

        ProfessionalCategory leida = categorias.findById(10L).orElseThrow();

        assertThat(leida.getName()).isEqualTo("Cardiología");
        assertThat(leida.getDescription()).isEqualTo("Corazón");
        assertThat(leida.isEnabled()).isTrue();
        assertThat(leida.getCreatedAt()).isEqualTo(CREADO);
        assertThat(leida.getUpdatedAt()).isEqualTo(ACTUALIZADO);
    }

    @Test
    void categoriaConDescripcionNulaSePersiste() {
        categorias.upsert(new ProfessionalCategory(11L, "Pediatría", null, false, CREADO, ACTUALIZADO));
        em.clear();

        ProfessionalCategory leida = categorias.findById(11L).orElseThrow();

        assertThat(leida.getDescription()).isNull();
        assertThat(leida.isEnabled()).isFalse();
    }

    @Test
    void profesionalIdaYVuelta() {
        categoria(10L);
        profesionales.upsert(new Professional(100L, 10L, "Ana", "Pérez", true, CREADO, ACTUALIZADO));
        em.clear();

        Professional leido = profesionales.findById(100L).orElseThrow();

        assertThat(leido.getCategoryId()).isEqualTo(10L);
        assertThat(leido.getFirstName()).isEqualTo("Ana");
        assertThat(leido.getLastName()).isEqualTo("Pérez");
        assertThat(leido.isEnabled()).isTrue();
        assertThat(leido.getCreatedAt()).isEqualTo(CREADO);
        assertThat(leido.getUpdatedAt()).isEqualTo(ACTUALIZADO);
    }

    @Test
    void horarioIdaYVueltaConservaLosSegundos() {
        categoria(10L);
        profesional(100L, 10L);
        horarios.upsert(new WeeklySchedule(1000L, 100L, DayOfWeek.WEDNESDAY,
                LocalTime.of(8, 30), LocalTime.of(12, 15, 45), 20, true, CREADO, ACTUALIZADO));
        em.clear();

        WeeklySchedule leido = horarios.findById(1000L).orElseThrow();

        assertThat(leido.getProfessionalId()).isEqualTo(100L);
        assertThat(leido.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(leido.getStartTime()).isEqualTo(LocalTime.of(8, 30));
        assertThat(leido.getEndTime()).isEqualTo(LocalTime.of(12, 15, 45));
        assertThat(leido.getSlotDurationMinutes()).isEqualTo(20);
        assertThat(leido.isEnabled()).isTrue();
        assertThat(leido.getUpdatedAt()).isEqualTo(ACTUALIZADO);
    }

    @Test
    void upsertRepetidoNoDuplicaYActualizaLosDatos() {
        categoria(10L);
        profesional(100L, 10L);
        WeeklySchedule horario = new WeeklySchedule(1000L, 100L, DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(13, 0), 30, true, CREADO, CREADO);

        horarios.upsert(horario);
        horarios.upsert(horario);
        profesionales.upsert(new Professional(100L, 10L, "Ana", "Pérez", false, CREADO, ACTUALIZADO));
        categorias.upsert(new ProfessionalCategory(10L, "Cardiología y más", null, false, CREADO, ACTUALIZADO));
        horarios.upsert(new WeeklySchedule(1000L, 100L, DayOfWeek.FRIDAY,
                LocalTime.of(10, 0), LocalTime.of(14, 0), 15, false, CREADO, ACTUALIZADO));
        em.clear();

        assertThat(categorias.count()).isEqualTo(1);
        assertThat(profesionales.count()).isEqualTo(1);
        assertThat(horarios.count()).isEqualTo(1);
        assertThat(categorias.findById(10L).orElseThrow().getName()).isEqualTo("Cardiología y más");
        assertThat(profesionales.findById(100L).orElseThrow().isEnabled()).isFalse();
        WeeklySchedule actualizado = horarios.findById(1000L).orElseThrow();
        assertThat(actualizado.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(actualizado.getSlotDurationMinutes()).isEqualTo(15);
        assertThat(actualizado.isEnabled()).isFalse();
    }

    @Test
    void profesionalConCategoriaInexistenteSeRechaza() {
        assertThatThrownBy(() -> profesionales.upsert(
                new Professional(100L, 999L, "Ana", "Pérez", true, CREADO, ACTUALIZADO)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void horarioConProfesionalInexistenteSeRechaza() {
        assertThatThrownBy(() -> horarios.upsert(new WeeklySchedule(1000L, 999L, DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(13, 0), 30, true, CREADO, ACTUALIZADO)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void diaDeLaSemanaInvalidoSeRechaza() {
        categoria(10L);
        profesional(100L, 10L);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO weekly_schedule (id, professional_id, day_of_week, start_time, end_time,
                                             slot_duration_minutes, enabled, created_at, updated_at)
                VALUES (1, 100, 'FUNDAY', '09:00', '13:00', 30, true, now(), now())
                """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void syncStateArrancaConLaFilaInicial() {
        SyncState estado = syncState.findById(SyncState.ID).orElseThrow();

        assertThat(syncState.count()).isEqualTo(1);
        assertThat(estado.getLocalVersion()).isNull();
        assertThat(estado.getStatus()).isEqualTo(SyncStatus.IDLE);
        assertThat(estado.getLastResult()).isNull();
        assertThat(estado.getUpdatedAt()).isNotNull();
    }

    @Test
    void syncStateIdaYVuelta() {
        SyncState estado = syncState.findForUpdate();
        estado.setLocalVersion(7L);
        estado.setStatus(SyncStatus.ERROR);
        estado.setLastResult(SyncResult.SNAPSHOT);
        estado.setLastSnapshotReason(SnapshotReason.FUERA_DE_VENTANA);
        estado.setLastSyncAt(CREADO);
        estado.setLastErrorCode("CATEDRA_NO_DISPONIBLE");
        estado.setLastErrorMessage("La cátedra no respondió");
        estado.setLastErrorAt(ACTUALIZADO);
        estado.setUpdatedAt(ACTUALIZADO);
        em.flush();
        em.clear();

        SyncState leido = syncState.findById(SyncState.ID).orElseThrow();

        assertThat(leido.getLocalVersion()).isEqualTo(7L);
        assertThat(leido.getStatus()).isEqualTo(SyncStatus.ERROR);
        assertThat(leido.getLastResult()).isEqualTo(SyncResult.SNAPSHOT);
        assertThat(leido.getLastSnapshotReason()).isEqualTo(SnapshotReason.FUERA_DE_VENTANA);
        assertThat(leido.getLastSyncAt()).isEqualTo(CREADO);
        assertThat(leido.getLastErrorCode()).isEqualTo("CATEDRA_NO_DISPONIBLE");
        assertThat(leido.getLastErrorMessage()).isEqualTo("La cátedra no respondió");
        assertThat(leido.getLastErrorAt()).isEqualTo(ACTUALIZADO);
    }

    @Test
    void syncStateNoAdmiteUnaSegundaFila() {
        assertThatThrownBy(() -> jdbc.sql(
                "INSERT INTO sync_state (id, status, updated_at) VALUES (2, 'IDLE', now())").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void categoria(long id) {
        categorias.upsert(new ProfessionalCategory(id, "Categoría " + id, null, true, CREADO, ACTUALIZADO));
    }

    private void profesional(long id, long categoriaId) {
        profesionales.upsert(new Professional(id, categoriaId, "Nombre", "Apellido", true, CREADO, ACTUALIZADO));
    }
}
