package ar.edu.um.atlas;

import ar.edu.um.atlas.application.repository.ProfessionalCategoryRepository;
import ar.edu.um.atlas.application.repository.ProfessionalRepository;
import ar.edu.um.atlas.application.repository.WeeklyScheduleRepository;
import ar.edu.um.atlas.application.search.CategoryView;
import ar.edu.um.atlas.application.search.GetProfessionalDetail;
import ar.edu.um.atlas.application.search.ListCategories;
import ar.edu.um.atlas.application.search.PageResult;
import ar.edu.um.atlas.application.search.ProfessionalDetail;
import ar.edu.um.atlas.application.search.ProfessionalNotFoundException;
import ar.edu.um.atlas.application.search.ProfessionalSearchCriteria;
import ar.edu.um.atlas.application.search.ProfessionalSummary;
import ar.edu.um.atlas.application.search.ScheduleView;
import ar.edu.um.atlas.application.search.SearchProfessionals;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Búsqueda y detalle de profesionales contra PostgreSQL real, solo con la copia local (enunciado 4.1 y 6.2).
 * Cada test se revierte al terminar.
 *
 * <p>Datos de base (orden esperado por apellido y nombre: 13, 10, 11, 12, 14):
 * <pre>
 *  13 Diego Alvarez  cat 2  deshabilitado   horario LUNES habilitado
 *  10 Ana   Gomez    cat 1  habilitado      horario LUNES habilitado
 *  11 Beto  Gomez    cat 1  habilitado      sin horarios
 *  12 Carla Perez    cat 2  habilitado      MARTES habilitado y LUNES deshabilitado
 *  14 Elena Ramos    cat 3 (deshabilitada)  habilitada, horario LUNES habilitado
 * </pre>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class BusquedaProfesionalesTest {

    private static final Instant AHORA = Instant.parse("2026-03-01T12:00:00Z");

    @Autowired SearchProfessionals busqueda;
    @Autowired GetProfessionalDetail detalle;
    @Autowired ListCategories listaDeCategorias;
    @Autowired ProfessionalCategoryRepository categorias;
    @Autowired ProfessionalRepository profesionales;
    @Autowired WeeklyScheduleRepository horarios;
    @Autowired EntityManager em;

    private long siguienteHorario = 1000;

    @BeforeEach
    void datos() {
        categorias.upsert(new ProfessionalCategory(1L, "Cardiología", "Corazón", true, AHORA, AHORA));
        categorias.upsert(new ProfessionalCategory(2L, "Pediatría", null, true, AHORA, AHORA));
        categorias.upsert(new ProfessionalCategory(3L, "Antigua", null, false, AHORA, AHORA));
        profesional(13, 2, "Diego", "Alvarez", false);
        profesional(10, 1, "Ana", "Gomez", true);
        profesional(11, 1, "Beto", "Gomez", true);
        profesional(12, 2, "Carla", "Perez", true);
        profesional(14, 3, "Elena", "Ramos", true);
        horario(13, DayOfWeek.MONDAY, true);
        horario(10, DayOfWeek.MONDAY, true);
        horario(12, DayOfWeek.TUESDAY, true);
        horario(12, DayOfWeek.MONDAY, false);
        horario(14, DayOfWeek.MONDAY, true);
    }

    private void profesional(long id, long categoria, String nombre, String apellido, boolean habilitado) {
        profesionales.upsert(new Professional(id, categoria, nombre, apellido, habilitado, AHORA, AHORA));
    }

    private void horario(long profesional, DayOfWeek dia, boolean habilitado) {
        horarios.upsert(new WeeklySchedule(siguienteHorario++, profesional, dia, LocalTime.of(9, 0),
                LocalTime.of(12, 0), 30, habilitado, AHORA, AHORA));
    }

    private static ProfessionalSearchCriteria con(Long categoria, String nombre, Boolean habilitado,
                                                  Boolean disponible, DayOfWeek dia) {
        return new ProfessionalSearchCriteria(categoria, nombre, habilitado, disponible, dia, 0, 100);
    }

    private List<Long> ids(ProfessionalSearchCriteria criterios) {
        em.flush();
        em.clear();
        return busqueda.search(criterios).content().stream().map(ProfessionalSummary::id).toList();
    }

    // --- sin filtros, orden y paginación ---

    @Test
    void sinFiltrosDevuelveTodosOrdenadosPorApellidoNombreEId() {
        assertThat(ids(con(null, null, null, null, null))).containsExactly(13L, 10L, 11L, 12L, 14L);
    }

    @Test
    void elIdDesempataCuandoApellidoYNombreCoinciden() {
        profesional(9, 1, "Ana", "Gomez", true);
        assertThat(ids(con(null, "gomez", null, null, null))).containsExactly(9L, 10L, 11L);
    }

    @Test
    void pagina() {
        em.flush();
        em.clear();
        PageResult<ProfessionalSummary> p0 = busqueda.search(new ProfessionalSearchCriteria(null, null, null, null, null, 0, 2));
        PageResult<ProfessionalSummary> p1 = busqueda.search(new ProfessionalSearchCriteria(null, null, null, null, null, 1, 2));
        PageResult<ProfessionalSummary> p2 = busqueda.search(new ProfessionalSearchCriteria(null, null, null, null, null, 2, 2));
        assertThat(p0.content()).extracting(ProfessionalSummary::id).containsExactly(13L, 10L);
        assertThat(p1.content()).extracting(ProfessionalSummary::id).containsExactly(11L, 12L);
        assertThat(p2.content()).extracting(ProfessionalSummary::id).containsExactly(14L);
        assertThat(p0.totalElements()).isEqualTo(5);
        assertThat(p0.totalPages()).isEqualTo(3);
        assertThat(p0.page()).isZero();
        assertThat(p0.size()).isEqualTo(2);
        assertThat(p2.page()).isEqualTo(2);
    }

    @Test
    void unaPaginaMasAllaDelFinalVieneVaciaPeroConLosTotales() {
        em.flush();
        em.clear();
        var p = busqueda.search(new ProfessionalSearchCriteria(null, null, null, null, null, 9, 2));
        assertThat(p.content()).isEmpty();
        assertThat(p.totalElements()).isEqualTo(5);
    }

    @Test
    void cadaResultadoTraeSuCategoria() {
        em.flush();
        em.clear();
        var resultado = busqueda.search(con(1L, null, null, null, null)).content();
        assertThat(resultado).hasSize(2).allSatisfy(p ->
                assertThat(p.category()).isEqualTo(new CategoryView(1L, "Cardiología", true)));
    }

    // --- categoría ---

    @Test
    void filtraPorCategoria() {
        assertThat(ids(con(1L, null, null, null, null))).containsExactly(10L, 11L);
        assertThat(ids(con(2L, null, null, null, null))).containsExactly(13L, 12L);
    }

    @Test
    void unaCategoriaSinProfesionalesOInexistenteDevuelveVacio() {
        assertThat(ids(con(999L, null, null, null, null))).isEmpty();
    }

    // --- nombre ---

    @Test
    void filtraPorNombreSinDistinguirMayusculas() {
        assertThat(ids(con(null, "ana", null, null, null))).containsExactly(10L);
        assertThat(ids(con(null, "ANA", null, null, null))).containsExactly(10L);
    }

    @Test
    void elNombreBuscaDentroDelNombreOElApellido() {
        assertThat(ids(con(null, "ome", null, null, null))).containsExactly(10L, 11L);
        assertThat(ids(con(null, "ram", null, null, null))).containsExactly(14L);
    }

    @Test
    void elNombreCompletoEncuentraAlProfesional() {
        assertThat(ids(con(null, "ana gomez", null, null, null))).containsExactly(10L);
        assertThat(ids(con(null, "Beto Gomez", null, null, null))).containsExactly(11L);
    }

    @Test
    void elNombreEnOtroOrdenNoCoincide() {
        assertThat(ids(con(null, "gomez ana", null, null, null))).isEmpty();
    }

    @Test
    void losComodinesDeLikeSeTratanComoTexto() {
        assertThat(ids(con(null, "%", null, null, null))).isEmpty();
        assertThat(ids(con(null, "_", null, null, null))).isEmpty();
        assertThat(ids(con(null, "a%z", null, null, null))).isEmpty();
        assertThat(ids(con(null, "\\", null, null, null))).isEmpty();
    }

    @Test
    void noIgnoraTildesPeroSiMayusculasConAcento() {
        // Decisión E: sin unaccent. Si hiciera falta, es una migración aditiva.
        profesional(20, 1, "Úrsula", "Núñez", true);
        assertThat(ids(con(null, "núñez", null, null, null))).containsExactly(20L);
        assertThat(ids(con(null, "NÚÑEZ", null, null, null))).containsExactly(20L);
        assertThat(ids(con(null, "nunez", null, null, null))).isEmpty();
    }

    @Test
    void elNombreEnBlancoNoFiltra() {
        assertThat(ids(con(null, "   ", null, null, null))).hasSize(5);
    }

    // --- habilitado (filtro literal) ---

    @Test
    void filtraPorEstadoHabilitado() {
        assertThat(ids(con(null, null, true, null, null))).containsExactly(10L, 11L, 12L, 14L);
        assertThat(ids(con(null, null, false, null, null))).containsExactly(13L);
    }

    // --- disponibilidad ---

    @Test
    void disponibleEsHabilitadoConCategoriaHabilitadaYAlgunHorarioHabilitado() {
        // 11 sin horarios, 13 deshabilitado, 14 con categoría deshabilitada.
        assertThat(ids(con(null, null, null, true, null))).containsExactly(10L, 12L);
    }

    @Test
    void disponibleEnUnDiaMiraSoloLosHorariosHabilitadosDeEseDia() {
        assertThat(ids(con(null, null, null, true, DayOfWeek.MONDAY))).containsExactly(10L); // 12 tiene el lunes deshabilitado
        assertThat(ids(con(null, null, null, true, DayOfWeek.TUESDAY))).containsExactly(12L);
        assertThat(ids(con(null, null, null, true, DayOfWeek.SUNDAY))).isEmpty();
    }

    @Test
    void noDisponibleDevuelveElComplemento() {
        assertThat(ids(con(null, null, null, false, null))).containsExactly(13L, 11L, 14L);
        assertThat(ids(con(null, null, null, false, DayOfWeek.MONDAY))).containsExactly(13L, 11L, 12L, 14L);
    }

    @Test
    void disponibleYNoDisponibleSumanTodosSinSolaparse() {
        var si = ids(con(null, null, null, true, null));
        var no = ids(con(null, null, null, false, null));
        assertThat(si).doesNotContainAnyElementsOf(no);
        assertThat(si.size() + no.size()).isEqualTo(5);
    }

    @Test
    void laDisponibilidadEsIndependienteDelFiltroHabilitado() {
        assertThat(ids(con(null, null, false, true, null))).isEmpty();          // el único deshabilitado no está disponible
        assertThat(ids(con(null, null, true, false, null))).containsExactly(11L, 14L);
    }

    @Test
    void unProfesionalConVariosHorariosHabilitadosNoSeRepite() {
        horario(10, DayOfWeek.MONDAY, true);
        horario(10, DayOfWeek.TUESDAY, true);
        em.flush();
        em.clear();
        var p = busqueda.search(con(null, null, null, true, null));
        assertThat(p.content()).extracting(ProfessionalSummary::id).containsExactly(10L, 12L);
        assertThat(p.totalElements()).isEqualTo(2);
    }

    // --- combinados ---

    @Test
    void losFiltrosSeCombinanConY() {
        assertThat(ids(con(1L, "gomez", true, true, DayOfWeek.MONDAY))).containsExactly(10L);
        assertThat(ids(con(1L, "gomez", true, true, DayOfWeek.TUESDAY))).isEmpty();
        assertThat(ids(con(2L, "a", null, true, null))).containsExactly(12L);
    }

    // --- detalle ---

    @Test
    void elDetalleTraeLaCategoriaYLosHorariosOrdenadosPorDiaYHora() {
        horarios.upsert(new WeeklySchedule(2000L, 10L, DayOfWeek.FRIDAY, LocalTime.of(15, 0), LocalTime.of(18, 30), 20, true, AHORA, AHORA));
        horarios.upsert(new WeeklySchedule(2001L, 10L, DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0), 15, false, AHORA, AHORA));
        em.flush();
        em.clear();

        ProfessionalDetail d = detalle.get(10);

        assertThat(d.firstName()).isEqualTo("Ana");
        assertThat(d.lastName()).isEqualTo("Gomez");
        assertThat(d.enabled()).isTrue();
        assertThat(d.category()).isEqualTo(new CategoryView(1L, "Cardiología", true));
        assertThat(d.schedules()).extracting(ScheduleView::dayOfWeek, ScheduleView::startTime)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DayOfWeek.MONDAY, LocalTime.of(8, 0)),
                        org.assertj.core.groups.Tuple.tuple(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
                        org.assertj.core.groups.Tuple.tuple(DayOfWeek.FRIDAY, LocalTime.of(15, 0)));
        ScheduleView ultimo = d.schedules().get(2);
        assertThat(ultimo.endTime()).isEqualTo(LocalTime.of(18, 30));
        assertThat(ultimo.slotDurationMinutes()).isEqualTo(20);
        assertThat(d.schedules().get(0).enabled()).isFalse();
        assertThat(d.schedules().get(1).enabled()).isTrue();
    }

    @Test
    void elDetalleIncluyeLosHorariosDeshabilitadosConSuPropioEstado() {
        em.flush();
        em.clear();
        assertThat(detalle.get(12).schedules()).extracting(ScheduleView::dayOfWeek, ScheduleView::enabled)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DayOfWeek.MONDAY, false),
                        org.assertj.core.groups.Tuple.tuple(DayOfWeek.TUESDAY, true));
    }

    @Test
    void unProfesionalSinHorariosTieneListaVacia() {
        em.flush();
        em.clear();
        assertThat(detalle.get(11).schedules()).isEmpty();
    }

    @Test
    void elDetalleDevuelveAlDeshabilitadoConSuEstado() {
        em.flush();
        em.clear();
        ProfessionalDetail d = detalle.get(13);
        assertThat(d.enabled()).isFalse();
        assertThat(d.schedules()).hasSize(1);
    }

    @Test
    void elDetalleDeUnIdInexistenteFalla() {
        assertThatThrownBy(() -> detalle.get(999)).isInstanceOf(ProfessionalNotFoundException.class);
    }

    // --- categorías ---

    @Test
    void listaLasCategoriasPorNombre() {
        em.flush();
        em.clear();
        assertThat(listaDeCategorias.list(null)).extracting(CategoryView::name)
                .containsExactly("Antigua", "Cardiología", "Pediatría");
    }

    @Test
    void listaLasCategoriasSegunSuEstado() {
        em.flush();
        em.clear();
        assertThat(listaDeCategorias.list(true)).extracting(CategoryView::id).containsExactly(1L, 2L);
        assertThat(listaDeCategorias.list(false)).extracting(CategoryView::id).containsExactly(3L);
    }
}
