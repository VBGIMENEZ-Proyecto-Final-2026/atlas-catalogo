package ar.edu.um.atlas;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatedraCredentialRejectedException;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * Falla y recuperación de punta a punta, con la verificación periódica corriendo de verdad y el estado leído
 * por {@code GET /api/sync/status}: funciona, falla la dependencia (la cátedra por REST, Redis o la
 * aplicación a la base), queda {@code ERROR} con el código correcto y la copia intacta, la dependencia vuelve
 * y la verificación siguiente deja {@code IDLE} y el error limpio, sin que nadie intervenga.
 */
@SpringBootTest(properties = {"atlas.sync.periodic-check=true", "atlas.sync.check-interval=300ms",
        "atlas.sync.status-endpoint.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecuperacionSincronizacionTest.FuentesProgramadas.class})
// El planificador de tareas sigue corriendo mientras el contexto vive: se cierra al terminar la clase.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecuperacionSincronizacionTest {

    private static final Instant T = Instant.parse("2026-03-02T15:30:45Z");
    private static final String SECRETO = "clave-secreta-de-la-integracion";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired FuenteDeSnapshotDePrueba snapshots;
    @Autowired FuenteDeCambiosDePrueba redis;
    @Autowired JdbcClient jdbc;

    /** Un administrador por petición (no por hilo): la seguridad en sí se prueba en SeguridadApiTest. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return user("root").roles("ADMIN");
    }

    @Test
    void laAplicacionSeRecuperaSolaDeLaCaidaDeRedisDeLaCatedraYDeUnaFallaAlAplicar() throws Exception {
        // 1. Base vacía: la primera verificación arma la copia con el snapshot.
        esperarEstado(e -> e.get("localVersion").asLong() == 1 && "IDLE".equals(e.get("status").asString()));
        Object copiaEnLaVersion1 = copia();

        // 2. Redis cae: queda el error, sin escalar a snapshot, y la copia no cambia.
        redis.fallarMetadata(new CatedraUnavailableException("sin respuesta de " + SECRETO, null, null, null));
        JsonNode error = esperarEstado(e -> "ERROR".equals(e.get("status").asString()));
        assertThat(error.get("lastError").get("code").asString()).isEqualTo("CATEDRA_NO_DISPONIBLE");
        assertThat(error.get("lastError").get("message").asString()).doesNotContain(SECRETO);
        assertThat(error.get("localVersion").asLong()).isEqualTo(1);
        assertThat(copia()).isEqualTo(copiaEnLaVersion1);

        // 3. Redis vuelve: la verificación siguiente limpia el error y retoma sola.
        redis.fallarMetadata(null);
        redis.metadata(1, 1);
        JsonNode recuperado = esperarEstado(e -> "IDLE".equals(e.get("status").asString()));
        assertThat(recuperado.get("lastError").isNull()).isTrue();
        assertThat(recuperado.get("lastResult").asString()).isEqualTo("NOOP");

        // 4. Se publica la versión 2 y la copia la recoge.
        redis.publicar(2, List.of(), List.of(profesional(1, 1, "Ana")), List.of());
        redis.metadata(2, 1);
        esperarEstado(e -> e.get("localVersion").asLong() == 2);
        assertThat(jdbc.sql("SELECT first_name FROM professional WHERE id = 1").query(String.class).single())
                .isEqualTo("Ana");
        Object copiaEnLaVersion2 = copia();

        // 5. La cátedra rechaza la credencial cuando hay que reconstruir con un snapshot (la local 2 queda fuera
        // de la ventana). Primero se programa la falla y después la metadata, para que ninguna verificación
        // alcance a aplicar el snapshot anterior.
        snapshots.fallar(new CatedraCredentialRejectedException("rechazada " + SECRETO, null, null, null));
        redis.metadata(5, 4);
        error = esperarEstado(e -> "ERROR".equals(e.get("status").asString()));
        assertThat(error.get("lastError").get("code").asString()).isEqualTo("CATEDRA_CREDENCIAL_RECHAZADA");
        assertThat(error.get("localVersion").asLong()).isEqualTo(2);
        assertThat(copia()).isEqualTo(copiaEnLaVersion2);

        // 6. La credencial se corrige: el snapshot se aplica solo.
        snapshots.devolver(snapshot(5, 1));
        recuperado = esperarEstado(e -> e.get("localVersion").asLong() == 5 && "IDLE".equals(e.get("status").asString()));
        assertThat(recuperado.get("lastError").isNull()).isTrue();
        assertThat(recuperado.get("lastSnapshotReason").asString()).isEqualTo("FUERA_DE_VENTANA");
        Object copiaEnLaVersion5 = copia();

        // 7. Un snapshot que no se puede aplicar (un profesional de una categoría que no existe) se revierte
        // entero: la copia queda en la versión 5 y el error tiene su propio código.
        snapshots.devolver(snapshot(9, 999));
        redis.metadata(9, 8);
        error = esperarEstado(e -> "ERROR".equals(e.get("status").asString()));
        assertThat(error.get("lastError").get("code").asString()).isEqualTo("SNAPSHOT_NO_APLICABLE");
        assertThat(error.get("localVersion").asLong()).isEqualTo(5);
        assertThat(copia()).isEqualTo(copiaEnLaVersion5);

        // 8. El snapshot vuelve a ser válido: se aplica solo y el error desaparece.
        snapshots.devolver(snapshot(9, 1));
        recuperado = esperarEstado(e -> e.get("localVersion").asLong() == 9 && "IDLE".equals(e.get("status").asString()));
        assertThat(recuperado.get("lastError").isNull()).isTrue();
    }

    /** Espera, consultando el endpoint, a que el estado cumpla la condición; devuelve ese estado. */
    private JsonNode esperarEstado(Predicate<JsonNode> condicion) {
        JsonNode[] ultimo = new JsonNode[1];
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            String cuerpo = mockMvc.perform(get("/api/sync/status").with(admin())).andReturn().getResponse().getContentAsString();
            ultimo[0] = JSON.readTree(cuerpo);
            return condicion.test(ultimo[0]);
        });
        return ultimo[0];
    }

    private Object copia() {
        return Map.of(
                "categorias", jdbc.sql("SELECT id, name, enabled FROM professional_category ORDER BY id")
                        .query().listOfRows(),
                "profesionales", jdbc.sql("SELECT id, category_id, first_name, enabled FROM professional ORDER BY id")
                        .query().listOfRows());
    }

    private static CatalogSnapshot snapshot(long version, long categoriaDelProfesional) {
        return new CatalogSnapshot(version, T,
                List.of(new ProfessionalCategory(1L, "Cardiología", null, true, T, T)),
                List.of(profesional(1, categoriaDelProfesional, "Nombre")), List.of());
    }

    private static Professional profesional(long id, long categoriaId, String nombre) {
        return new Professional(id, categoriaId, nombre, "Apellido " + id, true, T, T);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FuentesProgramadas {

        @Bean
        @Primary
        FuenteDeSnapshotDePrueba snapshotProgramado() {
            FuenteDeSnapshotDePrueba fuente = new FuenteDeSnapshotDePrueba();
            fuente.devolver(snapshot(1, 1));
            return fuente;
        }

        @Bean
        @Primary
        FuenteDeCambiosDePrueba cambiosProgramados() {
            FuenteDeCambiosDePrueba fuente = new FuenteDeCambiosDePrueba();
            fuente.metadata(1, 1);
            return fuente;
        }
    }
}
