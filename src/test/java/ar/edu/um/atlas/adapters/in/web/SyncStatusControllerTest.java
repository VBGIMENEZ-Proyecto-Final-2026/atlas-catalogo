package ar.edu.um.atlas.adapters.in.web;

import ar.edu.um.atlas.application.sync.GetSyncStatus;
import ar.edu.um.atlas.application.sync.SyncStatusView;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.SyncResult;
import ar.edu.um.atlas.domain.SyncStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El controlador solo traduce el estado a JSON y la falla de la base a {@code problem+json}. El estado real
 * contra PostgreSQL está en {@code EstadoSincronizacionTest}.
 */
@AutoConfigureMockMvc(addFilters = false) // la seguridad se prueba aparte, en SeguridadApiTest
@WebMvcTest(controllers = SyncStatusController.class, properties = "atlas.sync.status-endpoint.enabled=true")
class SyncStatusControllerTest {

    private static final Instant T = Instant.parse("2026-10-21T15:30:45Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean GetSyncStatus getSyncStatus;

    @Test
    void traduceElEstadoCompletoConLasFechasEnIso8601() throws Exception {
        when(getSyncStatus.get()).thenReturn(new SyncStatusView(7L, SyncStatus.ERROR, SyncResult.INCREMENTAL,
                SnapshotReason.BASE_VACIA, T, new SyncStatusView.LastError("CATEDRA_NO_DISPONIBLE",
                "La cátedra no respondió", T.plusSeconds(60))));

        mockMvc.perform(get("/api/sync/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.localVersion").value(7))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.lastResult").value("INCREMENTAL"))
                .andExpect(jsonPath("$.lastSnapshotReason").value("BASE_VACIA"))
                .andExpect(jsonPath("$.lastSyncAt").value("2026-10-21T15:30:45Z"))
                .andExpect(jsonPath("$.lastError.code").value("CATEDRA_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.lastError.message").value("La cátedra no respondió"))
                .andExpect(jsonPath("$.lastError.at").value("2026-10-21T15:31:45Z"));
    }

    @Test
    void conLaBaseVaciaLosCamposSinValorVanComoNullYNoSeOmiten() throws Exception {
        when(getSyncStatus.get()).thenReturn(
                new SyncStatusView(null, SyncStatus.IDLE, null, null, null, null));

        String cuerpo = mockMvc.perform(get("/api/sync/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE")).andReturn().getResponse().getContentAsString();

        assertThat(cuerpo).contains("\"localVersion\":null", "\"lastResult\":null", "\"lastSnapshotReason\":null",
                "\"lastSyncAt\":null", "\"lastError\":null");
    }

    @Test
    void siLaBaseNoRespondeDevuelve503ConProblemJsonYSinDetalleInterno() throws Exception {
        when(getSyncStatus.get()).thenThrow(
                new DataAccessResourceFailureException("conexión rechazada, password=clave-secreta-de-la-base"));

        String cuerpo = mockMvc.perform(get("/api/sync/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.code").value("STATUS_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.path").value("/api/sync/status"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(cuerpo).doesNotContain("clave-secreta-de-la-base").doesNotContain("DataAccess");
    }

    @Test
    void siNoSePuedeAbrirLaTransaccionTambienEs503() throws Exception {
        when(getSyncStatus.get()).thenThrow(new CannotCreateTransactionException("sin conexión"));

        mockMvc.perform(get("/api/sync/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STATUS_NO_DISPONIBLE"));
    }

    @Test
    void soloSeConsultaNoSeModifica() throws Exception {
        mockMvc.perform(post("/api/sync/status")).andExpect(status().isMethodNotAllowed());
    }
}
