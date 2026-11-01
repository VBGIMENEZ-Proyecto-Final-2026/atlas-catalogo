package ar.edu.um.atlas.adapters.in.web;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El controlador solo traduce parámetros a criterios y resultados a JSON, y los errores a
 * {@code problem+json}. Los filtros reales contra PostgreSQL están en {@code BusquedaProfesionalesTest}.
 */
@WebMvcTest(controllers = ProfessionalsController.class, properties = "atlas.search.enabled=true")
class ProfessionalsControllerTest {

    private static final CategoryView CARDIOLOGIA = new CategoryView(1L, "Cardiología", true);

    @Autowired MockMvc mockMvc;
    @MockitoBean SearchProfessionals search;
    @MockitoBean GetProfessionalDetail detail;
    @MockitoBean ListCategories categories;

    private static PageResult<ProfessionalSummary> pagina(ProfessionalSummary... items) {
        return new PageResult<>(List.of(items), 0, 20, items.length, items.length == 0 ? 0 : 1);
    }

    @Test
    void traduceLosParametrosAlosCriterios() throws Exception {
        when(search.search(any())).thenReturn(pagina());

        mockMvc.perform(get("/api/professionals").param("categoryId", "2").param("name", "ana")
                        .param("enabled", "true").param("available", "true").param("dayOfWeek", "MONDAY")
                        .param("page", "1").param("size", "5"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(ProfessionalSearchCriteria.class);
        verify(search).search(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProfessionalSearchCriteria(2L, "ana", true, true,
                DayOfWeek.MONDAY, 1, 5));
    }

    @Test
    void sinParametrosUsaLosValoresPorDefecto() throws Exception {
        when(search.search(any())).thenReturn(pagina());

        mockMvc.perform(get("/api/professionals")).andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(ProfessionalSearchCriteria.class);
        verify(search).search(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProfessionalSearchCriteria(null, null, null, null, null, 0, 20));
    }

    @Test
    void devuelveLaPaginaConSuCategoria() throws Exception {
        when(search.search(any())).thenReturn(new PageResult<>(
                List.of(new ProfessionalSummary(10L, "Ana", "Gomez", true, CARDIOLOGIA)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/professionals"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(10))
                .andExpect(jsonPath("$.content[0].firstName").value("Ana"))
                .andExpect(jsonPath("$.content[0].lastName").value("Gomez"))
                .andExpect(jsonPath("$.content[0].enabled").value(true))
                .andExpect(jsonPath("$.content[0].category.id").value(1))
                .andExpect(jsonPath("$.content[0].category.name").value("Cardiología"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void devuelveElDetalleConSusHorarios() throws Exception {
        when(detail.get(10L)).thenReturn(new ProfessionalDetail(10L, "Ana", "Gomez", true, CARDIOLOGIA,
                List.of(new ScheduleView(5L, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 30), 30, true))));

        mockMvc.perform(get("/api/professionals/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.category.name").value("Cardiología"))
                .andExpect(jsonPath("$.schedules[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.schedules[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.schedules[0].endTime").value("12:30:00"))
                .andExpect(jsonPath("$.schedules[0].slotDurationMinutes").value(30))
                .andExpect(jsonPath("$.schedules[0].enabled").value(true));
    }

    @Test
    void listaLasCategoriasYTraduceElFiltro() throws Exception {
        when(categories.list(true)).thenReturn(List.of(CARDIOLOGIA));

        mockMvc.perform(get("/api/categories").param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Cardiología"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void unParametroConTipoIncorrectoEs400SinRepetirElValor() throws Exception {
        for (String[] parametro : new String[][]{{"dayOfWeek", "LUNES-secreto"}, {"size", "abc-secreto"},
                {"enabled", "quizas-secreto"}, {"categoryId", "x-secreto"}}) {
            String cuerpo = mockMvc.perform(get("/api/professionals").param(parametro[0], parametro[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("BUSQUEDA_INVALIDA"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(cuerpo).doesNotContain("secreto");
        }
        verifyNoInteractions(search);
    }

    @Test
    void criteriosInvalidosSonError400ConCodigo() throws Exception {
        mockMvc.perform(get("/api/professionals").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BUSQUEDA_INVALIDA"))
                .andExpect(jsonPath("$.path").value("/api/professionals"))
                .andExpect(jsonPath("$.detail").value("size debe estar entre 1 y 100"));
        mockMvc.perform(get("/api/professionals").param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/professionals").param("dayOfWeek", "MONDAY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("dayOfWeek requiere available"));
        verifyNoInteractions(search);
    }

    @Test
    void unIdNoNumericoEs400() throws Exception {
        mockMvc.perform(get("/api/professionals/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSQUEDA_INVALIDA"));
        verify(detail, never()).get(anyLong());
    }

    @Test
    void unProfesionalInexistenteEs404() throws Exception {
        when(detail.get(999L)).thenThrow(new ProfessionalNotFoundException(999));

        mockMvc.perform(get("/api/professionals/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PROFESIONAL_NO_ENCONTRADO"))
                .andExpect(jsonPath("$.path").value("/api/professionals/999"));
    }

    @Test
    void siLaBaseNoRespondeEs503SinDetalleInterno() throws Exception {
        when(search.search(any())).thenThrow(
                new DataAccessResourceFailureException("conexión rechazada, password=clave-secreta-de-la-base"));

        String cuerpo = mockMvc.perform(get("/api/professionals"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("CATALOGO_NO_DISPONIBLE"))
                .andReturn().getResponse().getContentAsString();

        assertThat(cuerpo).doesNotContain("clave-secreta-de-la-base").doesNotContain("conexión rechazada")
                .doesNotContain("DataAccess");
    }

    @Test
    void soloAceptaLecturas() throws Exception {
        mockMvc.perform(post("/api/professionals")).andExpect(status().isMethodNotAllowed());
    }
}
