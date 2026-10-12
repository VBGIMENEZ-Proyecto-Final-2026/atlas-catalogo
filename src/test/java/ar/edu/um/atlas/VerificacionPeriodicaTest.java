package ar.edu.um.atlas;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * La verificación periódica corriendo de verdad con el planificador de tareas de Spring (intervalo corto):
 * arma la copia cuando la base está vacía, no hace nada si ya está al día y recoge una versión nueva que
 * aparece después, sin que nadie la dispare.
 */
@SpringBootTest(properties = {"atlas.sync.periodic-check=true", "atlas.sync.check-interval=300ms"})
@Import({TestcontainersConfiguration.class, VerificacionPeriodicaTest.FuentesProgramadas.class})
// El planificador de tareas sigue corriendo mientras el contexto vive: se cierra al terminar la clase.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VerificacionPeriodicaTest {

    private static final Instant T = Instant.parse("2026-03-02T15:30:45Z");

    @Autowired FuenteDeCambiosDePrueba redis;
    @Autowired JdbcClient jdbc;

    @Test
    void laVerificacionPeriodicaArmaLaCopiaLaMantieneAlDiaYRecogeLasVersionesNuevas() {
        // Base vacía: el primer disparo arma la copia con el snapshot.
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT local_version FROM sync_state").query(Long.class).single()).isEqualTo(1L));
        assertThat(jdbc.sql("SELECT last_snapshot_reason FROM sync_state").query(String.class).single())
                .isEqualTo("BASE_VACIA");

        // Con la copia al día, las verificaciones siguientes quedan registradas como Noop.
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT last_result FROM sync_state").query(String.class).single())
                        .isEqualTo("NOOP"));

        // La cátedra publica la versión 2 y no llega ningún aviso: la verificación la encuentra sola.
        redis.publicar(2, List.of(), List.of(new Professional(1L, 1L, "Ana", "Gómez", true, T, T.plusSeconds(60))),
                List.of());
        redis.metadata(2, 1);

        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT local_version FROM sync_state").query(Long.class).single()).isEqualTo(2L));
        assertThat(jdbc.sql("SELECT first_name FROM professional WHERE id = 1").query(String.class).single())
                .isEqualTo("Ana");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FuentesProgramadas {

        @Bean
        @Primary
        FuenteDeSnapshotDePrueba snapshotProgramado() {
            FuenteDeSnapshotDePrueba fuente = new FuenteDeSnapshotDePrueba();
            fuente.devolver(new CatalogSnapshot(1, T,
                    List.of(new ProfessionalCategory(1L, "Cardiología", null, true, T, T)),
                    List.of(new Professional(1L, 1L, "Nombre", "Apellido", true, T, T)), List.of()));
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
