package ar.edu.um.atlas;

import ar.edu.um.atlas.application.repository.ProfessionalCategoryRepository;
import ar.edu.um.atlas.application.repository.ProfessionalRepository;
import ar.edu.um.atlas.application.repository.WeeklyScheduleRepository;
import ar.edu.um.atlas.application.search.AvailabilityFilter;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filtro de disponibilidad (versión básica, decisiones A y F del modelo de datos) contra PostgreSQL real, solo
 * con datos locales. Cada test se revierte al terminar.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DisponibilidadTest {

    private static final Instant AHORA = Instant.parse("2026-03-01T12:00:00Z");
    private static final long CATEGORIA = 1L;
    private static final long CATEGORIA_DESHABILITADA = 2L;

    @Autowired AvailabilityFilter disponibilidad;
    @Autowired ProfessionalCategoryRepository categorias;
    @Autowired ProfessionalRepository profesionales;
    @Autowired WeeklyScheduleRepository horarios;
    @Autowired EntityManager em;

    private long siguienteHorario = 1000;

    @BeforeEach
    void categorias() {
        categorias.upsert(new ProfessionalCategory(CATEGORIA, "Cardiología", null, true, AHORA, AHORA));
        categorias.upsert(new ProfessionalCategory(CATEGORIA_DESHABILITADA, "Antigua", null, false, AHORA, AHORA));
    }

    private void profesional(long id, long categoria, boolean habilitado) {
        profesionales.upsert(new Professional(id, categoria, "Nombre" + id, "Apellido" + id, habilitado, AHORA, AHORA));
    }

    private void horario(long profesional, DayOfWeek dia, boolean habilitado) {
        horarios.upsert(new WeeklySchedule(siguienteHorario++, profesional, dia, LocalTime.of(9, 0),
                LocalTime.of(12, 0), 30, habilitado, AHORA, AHORA));
    }

    private List<Long> ids(Specification<Professional> filtro) {
        em.flush();
        em.clear();
        return profesionales.findAll(filtro).stream().map(Professional::getId).sorted().toList();
    }

    @Test
    void unProfesionalSinHorariosNoEstaDisponible() {
        profesional(10, CATEGORIA, true);

        assertThat(ids(disponibilidad.available(null))).isEmpty();
        assertThat(ids(disponibilidad.available(DayOfWeek.MONDAY))).isEmpty();
    }

    @Test
    void unProfesionalConTodosLosHorariosDeshabilitadosNoEstaDisponible() {
        profesional(10, CATEGORIA, true);
        horario(10, DayOfWeek.MONDAY, false);
        horario(10, DayOfWeek.TUESDAY, false);

        assertThat(ids(disponibilidad.available(null))).isEmpty();
        assertThat(ids(disponibilidad.available(DayOfWeek.MONDAY))).isEmpty();
    }

    @Test
    void conUnHorarioHabilitadoEstaDisponibleSinImportarElDia() {
        profesional(10, CATEGORIA, true);
        horario(10, DayOfWeek.WEDNESDAY, true);

        assertThat(ids(disponibilidad.available(null))).containsExactly(10L);
    }

    @Test
    void conUnDiaSoloEstaDisponibleSiElHorarioHabilitadoEsDeEseDia() {
        profesional(10, CATEGORIA, true);
        horario(10, DayOfWeek.WEDNESDAY, true);

        assertThat(ids(disponibilidad.available(DayOfWeek.WEDNESDAY))).containsExactly(10L);
        assertThat(ids(disponibilidad.available(DayOfWeek.THURSDAY))).isEmpty();
    }

    @Test
    void unHorarioDeshabilitadoEseDiaNoCuentaAunqueHayaOtroHabilitadoOtroDia() {
        profesional(10, CATEGORIA, true);
        horario(10, DayOfWeek.MONDAY, false);
        horario(10, DayOfWeek.FRIDAY, true);

        assertThat(ids(disponibilidad.available(DayOfWeek.MONDAY))).isEmpty();
        assertThat(ids(disponibilidad.available(DayOfWeek.FRIDAY))).containsExactly(10L);
        assertThat(ids(disponibilidad.available(null))).containsExactly(10L);
    }

    @Test
    void unProfesionalDeshabilitadoNoEstaDisponibleAunqueConserveHorariosHabilitados() {
        profesional(10, CATEGORIA, false);
        horario(10, DayOfWeek.MONDAY, true);

        assertThat(ids(disponibilidad.available(null))).isEmpty();
        assertThat(ids(disponibilidad.available(DayOfWeek.MONDAY))).isEmpty();
    }

    @Test
    void unaCategoriaDeshabilitadaQuitaALosProfesionalesHabilitadosDeLaDisponibilidad() {
        profesional(10, CATEGORIA_DESHABILITADA, true);
        horario(10, DayOfWeek.MONDAY, true);

        assertThat(ids(disponibilidad.available(null))).isEmpty();
    }

    @Test
    void conVariosHorariosElProfesionalNoSeRepite() {
        profesional(10, CATEGORIA, true);
        horario(10, DayOfWeek.MONDAY, true);
        horario(10, DayOfWeek.MONDAY, true);
        horario(10, DayOfWeek.TUESDAY, true);

        assertThat(profesionales.findAll(disponibilidad.available(null))).hasSize(1);
    }

    @Test
    void devuelveSoloALosDisponiblesEntreVarios() {
        profesional(10, CATEGORIA, true);
        horario(10, DayOfWeek.MONDAY, true);
        profesional(11, CATEGORIA, true);
        profesional(12, CATEGORIA, true);
        horario(12, DayOfWeek.TUESDAY, true);
        profesional(13, CATEGORIA, true);
        horario(13, DayOfWeek.MONDAY, false);

        assertThat(ids(disponibilidad.available(null))).containsExactly(10L, 12L);
        assertThat(ids(disponibilidad.available(DayOfWeek.MONDAY))).containsExactly(10L);
    }

    @Test
    void sePuedeCombinarConElFiltroLiteralDeHabilitadoYSonIndependientes() {
        profesional(10, CATEGORIA, true);
        horario(10, DayOfWeek.MONDAY, true);
        profesional(11, CATEGORIA, false);
        horario(11, DayOfWeek.MONDAY, true);
        Specification<Professional> habilitado = (p, q, cb) -> cb.isTrue(p.get("enabled"));
        Specification<Professional> deshabilitado = (p, q, cb) -> cb.isFalse(p.get("enabled"));

        // Literal: el deshabilitado se encuentra pidiéndolo; efectivo: nunca figura como disponible.
        assertThat(ids(deshabilitado)).containsExactly(11L);
        assertThat(ids(habilitado.and(disponibilidad.available(null)))).containsExactly(10L);
        assertThat(ids(deshabilitado.and(disponibilidad.available(null)))).isEmpty();
    }
}
