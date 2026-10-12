package ar.edu.um.atlas;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Con la propiedad por defecto (encendida) la aplicación pide el snapshot sola al terminar de arrancar
 * si no hay versión local. La fuente de prueba responde desde el primer pedido.
 */
@SpringBootTest(properties = "atlas.sync.on-startup=true")
@Import({TestcontainersConfiguration.class, ArranqueConSincronizacionTest.FuenteProgramada.class})
class ArranqueConSincronizacionTest {

    @Autowired JdbcClient jdbc;

    @Test
    void laAplicacionInicializaLaCopiaAlArrancar() {
        assertThat(jdbc.sql("SELECT local_version FROM sync_state").query(Long.class).single()).isEqualTo(7L);
        assertThat(jdbc.sql("SELECT last_snapshot_reason FROM sync_state").query(String.class).single())
                .isEqualTo("BASE_VACIA");
        assertThat(jdbc.sql("SELECT count(*) FROM professional_category").query(Integer.class).single())
                .isEqualTo(1);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class FuenteProgramada {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        FuenteDeSnapshotDePrueba fuenteProgramada() {
            FuenteDeSnapshotDePrueba fuente = new FuenteDeSnapshotDePrueba();
            Instant t = Instant.parse("2026-03-02T15:30:45Z");
            fuente.devolver(new CatalogSnapshot(7, t,
                    List.of(new ProfessionalCategory(1L, "Cardiología", null, true, t, t)), List.of(), List.of()));
            return fuente;
        }
    }
}
