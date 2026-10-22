package ar.edu.um.atlas;

import ar.edu.um.atlas.adapters.in.schedule.PeriodicSyncCheck;
import ar.edu.um.atlas.adapters.in.startup.SyncOnStartup;
import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatedraCredentialRejectedException;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;
import ar.edu.um.atlas.application.sync.ApplySnapshot;
import ar.edu.um.atlas.application.sync.SyncCatalog;
import ar.edu.um.atlas.application.sync.SyncException;
import ar.edu.um.atlas.application.sync.SyncTrigger;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.SnapshotReason;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El estado de la sincronización informado por {@code GET /api/sync/status} contra PostgreSQL real, con la
 * cátedra simulada: después de cada tipo de corrida, con un error vigente, sin bloquear a una corrida en curso
 * y sin dejar secretos en la respuesta ni en el log. Sin {@code @Transactional}: cada corrida abre las suyas.
 */
@SpringBootTest(properties = "atlas.sync.status-endpoint.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FuenteDeSnapshotDePrueba.Configuracion.class,
        FuenteDeCambiosDePrueba.Configuracion.class})
class EstadoSincronizacionTest {

    private static final Instant T = Instant.parse("2026-03-02T15:30:45Z");
    private static final String SECRETO = "token-secreto-de-la-catedra-9f3a";

    @Autowired MockMvc mockMvc;
    @Autowired SyncCatalog syncCatalog;
    @Autowired ApplySnapshot applySnapshot;
    @Autowired FuenteDeSnapshotDePrueba snapshots;
    @Autowired FuenteDeCambiosDePrueba redis;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void baseVacia() {
        snapshots.reiniciar();
        redis.reiniciar();
        jdbc.sql("DELETE FROM weekly_schedule").update();
        jdbc.sql("DELETE FROM professional").update();
        jdbc.sql("DELETE FROM professional_category").update();
        jdbc.sql("""
                UPDATE sync_state SET local_version = NULL, status = 'IDLE', last_result = NULL,
                    last_snapshot_reason = NULL, last_sync_at = NULL, last_error_code = NULL,
                    last_error_message = NULL, last_error_at = NULL
                """).update();
    }

    @Test
    void conLaBaseVaciaInformaSinVersionNiResultado() throws Exception {
        mockMvc.perform(get("/api/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localVersion").value((Object) null))
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.lastResult").value((Object) null))
                .andExpect(jsonPath("$.lastSyncAt").value((Object) null))
                .andExpect(jsonPath("$.lastError").value((Object) null));
    }

    @Test
    void despuesDeUnSnapshotInformaLaVersionElResultadoYElMotivo() throws Exception {
        snapshots.devolver(snapshot(1));

        syncCatalog.run(SyncTrigger.ARRANQUE);

        mockMvc.perform(get("/api/sync/status"))
                .andExpect(jsonPath("$.localVersion").value(1))
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.lastResult").value("SNAPSHOT"))
                .andExpect(jsonPath("$.lastSnapshotReason").value("BASE_VACIA"))
                .andExpect(jsonPath("$.lastSyncAt").isNotEmpty())
                .andExpect(jsonPath("$.lastError").value((Object) null));
    }

    @Test
    void despuesDeUnaVersionNuevaYDeUnaVerificacionSinCambiosInformaCadaResultado() throws Exception {
        snapshots.devolver(snapshot(1));
        syncCatalog.run(SyncTrigger.ARRANQUE);

        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, "Ana")), List.of());
        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);
        mockMvc.perform(get("/api/sync/status"))
                .andExpect(jsonPath("$.localVersion").value(2))
                .andExpect(jsonPath("$.lastResult").value("INCREMENTAL"))
                // El motivo del último snapshot se conserva: describe cómo se armó la copia de base.
                .andExpect(jsonPath("$.lastSnapshotReason").value("BASE_VACIA"));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);
        mockMvc.perform(get("/api/sync/status"))
                .andExpect(jsonPath("$.localVersion").value(2))
                .andExpect(jsonPath("$.lastResult").value("NOOP"));
    }

    @Test
    void conUnErrorInformaSuCodigoYSuMensajeFijoSinTocarLaVersionNiElUltimoResultado() throws Exception {
        snapshots.devolver(snapshot(1));
        syncCatalog.run(SyncTrigger.ARRANQUE);
        redis.fallarMetadata(new CatedraUnavailableException("fallo con " + SECRETO, null, null,
                new RuntimeException("causa con " + SECRETO)));

        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA))
                .isInstanceOf(SyncException.class);

        String cuerpo = mockMvc.perform(get("/api/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localVersion").value(1))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.lastResult").value("SNAPSHOT"))
                .andExpect(jsonPath("$.lastError.code").value("CATEDRA_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.lastError.message").value("La cátedra no respondió"))
                .andExpect(jsonPath("$.lastError.at").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain(SECRETO).doesNotContain("RuntimeException");
    }

    @Test
    void unExitoPosteriorLimpiaElErrorInformado() throws Exception {
        snapshots.devolver(snapshot(1));
        syncCatalog.run(SyncTrigger.ARRANQUE);
        redis.fallarMetadata(new CatedraUnavailableException("sin respuesta", null, null, null));
        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA));

        redis.fallarMetadata(null);
        redis.metadata(1, 1);
        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        mockMvc.perform(get("/api/sync/status"))
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.lastResult").value("NOOP"))
                .andExpect(jsonPath("$.lastError").value((Object) null));
    }

    @Test
    void consultarElEstadoNoEsperaAUnaCorridaEnCursoYVeLoUltimoConfirmado() throws Exception {
        snapshots.devolver(snapshot(1));
        syncCatalog.run(SyncTrigger.ARRANQUE);
        // La versión local (1) queda fuera de la ventana: la corrida pide un snapshot y se frena con el candado.
        redis.metadata(7, 4);
        snapshots.devolver(snapshot(7));
        CountDownLatch entro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        snapshots.frenar(entro, soltar);
        CompletableFuture<Void> corrida = CompletableFuture.runAsync(
                () -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA));
        try {
            assertThat(entro.await(10, TimeUnit.SECONDS)).isTrue();

            // Si la consulta tomara el candado quedaría esperando a la corrida: el tope la hace fallar y no colgarse.
            CompletableFuture<String> consulta = CompletableFuture.supplyAsync(() -> {
                try {
                    return mockMvc.perform(get("/api/sync/status")).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(consulta.get(5, TimeUnit.SECONDS)).contains("\"localVersion\":1");
            assertThat(corrida.isDone()).isFalse();
        } finally {
            soltar.countDown();
        }
        corrida.get(30, TimeUnit.SECONDS);

        mockMvc.perform(get("/api/sync/status")).andExpect(jsonPath("$.localVersion").value(7));
    }

    @Test
    void cadaFallaDejaUnaLineaConElCodigoYLaClaseDeLaCausaYNingunSecretoEnElLog() {
        var raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Level anterior = raiz.getLevel();
        raiz.addAppender(appender);
        raiz.setLevel(Level.INFO);
        try {
            // Base vacía: el snapshot falla por la cátedra caída (arranque).
            snapshots.fallar(new CatedraUnavailableException("fallo con " + SECRETO, null, null,
                    new RuntimeException("causa con " + SECRETO)));
            new SyncOnStartup(syncCatalog).onReady();
            // Credencial rechazada en la verificación periódica.
            snapshots.fallar(new CatedraCredentialRejectedException("rechazada " + SECRETO, null, null, null));
            new PeriodicSyncCheck(syncCatalog).run();
            // Falla inesperada de aplicación (no es del puerto).
            snapshots.fallar(new IllegalStateException("detalle interno " + SECRETO));
            new PeriodicSyncCheck(syncCatalog).run();
        } finally {
            raiz.detachAppender(appender);
            raiz.setLevel(anterior);
        }

        List<String> lineas = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lineas).anyMatch(l -> l.contains("La sincronización de arranque falló")
                && l.contains("CATEDRA_NO_DISPONIBLE") && l.contains("causa CatedraUnavailableException"));
        assertThat(lineas).anyMatch(l -> l.contains("La verificación periódica falló")
                && l.contains("CATEDRA_CREDENCIAL_RECHAZADA"));
        assertThat(lineas).anyMatch(l -> l.contains("La verificación periódica falló")
                && l.contains("SNAPSHOT_NO_APLICABLE") && l.contains("causa IllegalStateException"));
        assertThat(lineas).noneMatch(l -> l.contains(SECRETO));
        // Tampoco en las trazas que acompañan a las líneas (excepciones adjuntas).
        assertThat(appender.list).noneMatch(e -> e.getThrowableProxy() != null);
    }

    @Test
    void conLaCopiaIntactaElErrorNoCambiaLosDatos() throws Exception {
        snapshots.devolver(snapshot(1));
        syncCatalog.run(SyncTrigger.ARRANQUE);
        var antes = jdbc.sql("SELECT id, first_name, enabled FROM professional ORDER BY id").query().listOfRows();
        snapshots.fallar(new CatedraUnavailableException("sin respuesta", null, null, null));
        redis.metadata(7, 4);

        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA));

        assertThat(jdbc.sql("SELECT id, first_name, enabled FROM professional ORDER BY id").query().listOfRows())
                .isEqualTo(antes);
        assertThat(jdbc.sql("SELECT local_version FROM sync_state").query(Long.class).single()).isEqualTo(1L);
    }

    private static CatalogSnapshot snapshot(long version) {
        return new CatalogSnapshot(version, T,
                List.of(new ProfessionalCategory(1L, "Cardiología", null, true, T, T)),
                List.of(profesional(1, "Nombre"), profesional(2, "Otro")), List.of());
    }

    private static Professional profesional(long id, String nombre) {
        return new Professional(id, 1L, nombre, "Apellido " + id, true, T, T);
    }
}
