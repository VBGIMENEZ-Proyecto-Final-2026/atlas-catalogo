package ar.edu.um.atlas;

import ar.edu.um.atlas.adapters.in.startup.SyncOnStartup;
import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatedraCredentialRejectedException;
import ar.edu.um.atlas.application.port.out.CatedraInvalidResponseException;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;
import ar.edu.um.atlas.application.sync.ApplySnapshot;
import ar.edu.um.atlas.application.sync.SyncCatalog;
import ar.edu.um.atlas.application.sync.SyncException;
import ar.edu.um.atlas.application.sync.SyncErrorCode;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El caso de uso {@code ApplySnapshot} y el disparador de arranque contra PostgreSQL real, con la cátedra
 * simulada. Sin {@code @Transactional}: la corrida abre y cierra sus propias transacciones.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, FuenteDeSnapshotDePrueba.Configuracion.class,
        FuenteDeCambiosDePrueba.Configuracion.class})
class SincronizacionCompletaTest {

    private static final Instant CREADO = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-03-02T15:30:45Z");

    @Autowired ApplySnapshot applySnapshot;
    @Autowired FuenteDeSnapshotDePrueba fuente;
    @Autowired FuenteDeCambiosDePrueba redis;
    @Autowired SyncCatalog syncCatalog;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void baseVacia() {
        fuente.reiniciar();
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
    void inicializaLaCopiaConLaBaseVacia() {
        fuente.devolver(snapshot(5, 2, 3, 4));

        applySnapshot.run(SnapshotReason.BASE_VACIA);

        assertThat(contar("professional_category")).isEqualTo(2);
        assertThat(contar("professional")).isEqualTo(3);
        assertThat(contar("weekly_schedule")).isEqualTo(4);
        assertThat(contarHabilitados("weekly_schedule")).isEqualTo(4);
        Map<String, Object> estado = estado();
        assertThat(estado.get("local_version")).isEqualTo(5L);
        assertThat(estado.get("status")).isEqualTo("IDLE");
        assertThat(estado.get("last_result")).isEqualTo("SNAPSHOT");
        assertThat(estado.get("last_snapshot_reason")).isEqualTo("BASE_VACIA");
        assertThat(estado.get("last_sync_at")).isNotNull();
        assertThat(estado.get("last_error_code")).isNull();
        assertThat(estado.get("last_error_message")).isNull();
        assertThat(estado.get("last_error_at")).isNull();
    }

    @Test
    void reconstruyeConValoresNuevosYDeshabilitaLoQueYaNoViene() {
        fuente.devolver(snapshot(5, 2, 3, 4));
        applySnapshot.run(SnapshotReason.BASE_VACIA);

        // Versión menor que la local: es una reconstrucción y se registra igual. Desaparecen la categoría 2,
        // el profesional 3 y los horarios 3 y 4; cambia el nombre de la categoría 1.
        fuente.devolver(new CatalogSnapshot(3, ACTUALIZADO,
                List.of(new ProfessionalCategory(1L, "Nombre nuevo", null, true, CREADO, ACTUALIZADO)),
                List.of(profesional(1, 1), profesional(2, 1)),
                List.of(horario(1, 1), horario(2, 2))));
        applySnapshot.run(SnapshotReason.LOCAL_ADELANTADA);

        assertThat(contar("professional_category")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT name FROM professional_category WHERE id = 1").query(String.class).single())
                .isEqualTo("Nombre nuevo");
        assertThat(habilitado("professional_category", 1)).isTrue();
        assertThat(habilitado("professional_category", 2)).isFalse();
        assertThat(habilitado("professional", 2)).isTrue();
        assertThat(habilitado("professional", 3)).isFalse();
        assertThat(habilitado("weekly_schedule", 2)).isTrue();
        assertThat(habilitado("weekly_schedule", 3)).isFalse();
        assertThat(habilitado("weekly_schedule", 4)).isFalse();
        // Lo deshabilitado conserva sus marcas de tiempo (decisión J) y nada se borra.
        assertThat(jdbc.sql("SELECT updated_at FROM professional_category WHERE id = 2")
                .query(Instant.class).single()).isEqualTo(ACTUALIZADO);
        assertThat(contar("professional")).isEqualTo(3);
        assertThat(contar("weekly_schedule")).isEqualTo(4);
        assertThat(estado().get("local_version")).isEqualTo(3L);
        assertThat(estado().get("last_snapshot_reason")).isEqualTo("LOCAL_ADELANTADA");
    }

    @Test
    void unaColeccionVaciaDeshabilitaTodasLasLocalesDeEsaTabla() {
        fuente.devolver(snapshot(1, 2, 3, 4));
        applySnapshot.run(SnapshotReason.BASE_VACIA);

        fuente.devolver(new CatalogSnapshot(2, ACTUALIZADO,
                List.of(categoria(1), categoria(2)), List.of(), List.of()));
        applySnapshot.run(SnapshotReason.FUERA_DE_VENTANA);

        assertThat(contarHabilitados("professional_category")).isEqualTo(2);
        assertThat(contarHabilitados("professional")).isZero();
        assertThat(contarHabilitados("weekly_schedule")).isZero();
        assertThat(contar("professional")).isEqualTo(3);
    }

    @Test
    void repetirElSnapshotNoDuplicaNiCambiaLosDatos() {
        fuente.devolver(snapshot(5, 2, 3, 4));
        applySnapshot.run(SnapshotReason.BASE_VACIA);
        List<Map<String, Object>> antes = jdbc.sql("SELECT * FROM weekly_schedule ORDER BY id").query().listOfRows();

        applySnapshot.run(SnapshotReason.BASE_VACIA);

        assertThat(contar("professional_category")).isEqualTo(2);
        assertThat(contar("professional")).isEqualTo(3);
        assertThat(contar("weekly_schedule")).isEqualTo(4);
        assertThat(jdbc.sql("SELECT * FROM weekly_schedule ORDER BY id").query().listOfRows()).isEqualTo(antes);
        assertThat(estado().get("local_version")).isEqualTo(5L);
    }

    @Test
    void siFallaAMitadDeCaminoSeRevierteTodoYQuedaElError() {
        fuente.devolver(snapshot(5, 2, 3, 4));
        applySnapshot.run(SnapshotReason.BASE_VACIA);

        // Cambia una categoría y un profesional, y un horario apunta a un profesional que no existe:
        // la clave foránea falla después de haber aplicado categorías y profesionales.
        fuente.devolver(new CatalogSnapshot(9, ACTUALIZADO,
                List.of(new ProfessionalCategory(1L, "No debe quedar", null, true, CREADO, ACTUALIZADO)),
                List.of(profesional(1, 1)),
                List.of(horario(1, 99))));

        assertThatThrownBy(() -> applySnapshot.run(SnapshotReason.FUERA_DE_VENTANA))
                .isInstanceOfSatisfying(SyncException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SyncErrorCode.SNAPSHOT_NO_APLICABLE));

        assertThat(jdbc.sql("SELECT name FROM professional_category WHERE id = 1").query(String.class).single())
                .isEqualTo("Categoría 1");
        assertThat(contarHabilitados("professional_category")).isEqualTo(2);
        assertThat(contarHabilitados("professional")).isEqualTo(3);
        assertThat(contarHabilitados("weekly_schedule")).isEqualTo(4);
        Map<String, Object> estado = estado();
        assertThat(estado.get("local_version")).isEqualTo(5L);
        assertThat(estado.get("status")).isEqualTo("ERROR");
        assertThat(estado.get("last_result")).isEqualTo("SNAPSHOT");
        assertThat(estado.get("last_error_code")).isEqualTo("SNAPSHOT_NO_APLICABLE");
        assertThat(estado.get("last_error_message")).isEqualTo(SyncErrorCode.SNAPSHOT_NO_APLICABLE.message());
        assertThat(estado.get("last_error_at")).isNotNull();
    }

    @Test
    void laCatedraCaidaDejaLaCopiaIntactaYElEstadoEnError() {
        fuente.devolver(snapshot(5, 2, 3, 4));
        applySnapshot.run(SnapshotReason.BASE_VACIA);

        fuente.fallar(new CatedraUnavailableException("sin respuesta", 503, null, null));

        assertThatThrownBy(() -> applySnapshot.run(SnapshotReason.NO_VERIFICABLE))
                .isInstanceOfSatisfying(SyncException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SyncErrorCode.CATEDRA_NO_DISPONIBLE));

        assertThat(contarHabilitados("weekly_schedule")).isEqualTo(4);
        assertThat(estado().get("local_version")).isEqualTo(5L);
        assertThat(estado().get("status")).isEqualTo("ERROR");
        assertThat(estado().get("last_error_code")).isEqualTo("CATEDRA_NO_DISPONIBLE");
    }

    @Test
    void cadaFallaDelPuertoTieneSuCodigo() {
        fuente.fallar(new CatedraCredentialRejectedException("token", 401, null, null));
        assertThat(codigoDeLaFalla()).isEqualTo(SyncErrorCode.CATEDRA_CREDENCIAL_RECHAZADA);

        fuente.fallar(new CatedraInvalidResponseException("cuerpo", 200, null, null));
        assertThat(codigoDeLaFalla()).isEqualTo(SyncErrorCode.CATEDRA_RESPUESTA_INVALIDA);

        fuente.fallar(new CatedraUnavailableException("red", null, null, null));
        assertThat(codigoDeLaFalla()).isEqualTo(SyncErrorCode.CATEDRA_NO_DISPONIBLE);
        assertThat(estado().get("local_version")).isNull();
    }

    @Test
    void unExitoPosteriorLimpiaElError() {
        fuente.fallar(new CatedraUnavailableException("red", null, null, null));
        codigoDeLaFalla();
        assertThat(estado().get("status")).isEqualTo("ERROR");

        fuente.devolver(snapshot(2, 1, 1, 1));
        applySnapshot.run(SnapshotReason.BASE_VACIA);

        Map<String, Object> estado = estado();
        assertThat(estado.get("status")).isEqualTo("IDLE");
        assertThat(estado.get("last_error_code")).isNull();
        assertThat(estado.get("last_error_message")).isNull();
        assertThat(estado.get("last_error_at")).isNull();
    }

    @Test
    void dosCorridasEnParaleloSeSerializan() throws Exception {
        fuente.devolver(snapshot(5, 2, 3, 4));
        CountDownLatch entro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        fuente.frenar(entro, soltar);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> primera = CompletableFuture.runAsync(
                    () -> applySnapshot.run(SnapshotReason.BASE_VACIA), pool);
            assertThat(entro.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> segunda = CompletableFuture.runAsync(
                    () -> applySnapshot.run(SnapshotReason.NO_VERIFICABLE), pool);
            Thread.sleep(1500);

            // La segunda sigue esperando el candado: todavía no le pidió nada a la cátedra.
            assertThat(segunda.isDone()).isFalse();
            assertThat(fuente.pedidos()).isEqualTo(1);

            soltar.countDown();
            primera.get(30, TimeUnit.SECONDS);
            segunda.get(30, TimeUnit.SECONDS);
            assertThat(fuente.pedidos()).isEqualTo(2);
            assertThat(contar("professional")).isEqualTo(3);
            assertThat(estado().get("last_snapshot_reason")).isEqualTo("NO_VERIFICABLE");
        } finally {
            soltar.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void alArrancarConLaBaseVaciaPideElSnapshotConMotivoBaseVacia() {
        fuente.devolver(snapshot(5, 2, 3, 4));

        arranque().onReady();

        assertThat(fuente.pedidos()).isEqualTo(1);
        assertThat(estado().get("local_version")).isEqualTo(5L);
        assertThat(estado().get("last_snapshot_reason")).isEqualTo("BASE_VACIA");
    }

    @Test
    void alArrancarConVersionLocalAlDiaNoPideElSnapshot() {
        fuente.devolver(snapshot(5, 2, 3, 4));
        applySnapshot.run(SnapshotReason.BASE_VACIA);
        fuente.reiniciar();
        redis.metadata(5, 1);

        arranque().onReady();

        assertThat(fuente.pedidos()).isZero();
        assertThat(estado().get("local_version")).isEqualTo(5L);
        assertThat(estado().get("last_result")).isEqualTo("NOOP");
    }

    @Test
    void siLaCatedraFallaAlArrancarNoSeCaeYQuedaElError() {
        fuente.fallar(new CatedraUnavailableException("sin respuesta", null, null, null));

        arranque().onReady();

        assertThat(estado().get("local_version")).isNull();
        assertThat(estado().get("status")).isEqualTo("ERROR");
        assertThat(estado().get("last_error_code")).isEqualTo("CATEDRA_NO_DISPONIBLE");
    }

    @ParameterizedTest
    @EnumSource(SnapshotReason.class)
    void todosLosMotivosSeGuardan(SnapshotReason motivo) {
        fuente.devolver(snapshot(1, 1, 1, 1));

        applySnapshot.run(motivo);

        assertThat(estado().get("last_snapshot_reason")).isEqualTo(motivo.name());
    }

    private SyncOnStartup arranque() {
        return new SyncOnStartup(syncCatalog);
    }

    private SyncErrorCode codigoDeLaFalla() {
        try {
            applySnapshot.run(SnapshotReason.BASE_VACIA);
        } catch (SyncException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("Se esperaba una falla");
    }

    private static CatalogSnapshot snapshot(long version, int categorias, int profesionales, int horarios) {
        return new CatalogSnapshot(version, ACTUALIZADO,
                java.util.stream.LongStream.rangeClosed(1, categorias).mapToObj(SincronizacionCompletaTest::categoria).toList(),
                java.util.stream.LongStream.rangeClosed(1, profesionales).mapToObj(i -> profesional(i, 1)).toList(),
                java.util.stream.LongStream.rangeClosed(1, horarios).mapToObj(i -> horario(i, 1)).toList());
    }

    private static ProfessionalCategory categoria(long id) {
        return new ProfessionalCategory(id, "Categoría " + id, null, true, CREADO, ACTUALIZADO);
    }

    private static Professional profesional(long id, long categoriaId) {
        return new Professional(id, categoriaId, "Nombre " + id, "Apellido " + id, true, CREADO, ACTUALIZADO);
    }

    private static WeeklySchedule horario(long id, long profesionalId) {
        return new WeeklySchedule(id, profesionalId, DayOfWeek.MONDAY, LocalTime.of(9, 0, 30),
                LocalTime.of(12, 0), 30, true, CREADO, ACTUALIZADO);
    }

    private int contar(String tabla) {
        return jdbc.sql("SELECT count(*) FROM " + tabla).query(Integer.class).single();
    }

    private int contarHabilitados(String tabla) {
        return jdbc.sql("SELECT count(*) FROM " + tabla + " WHERE enabled").query(Integer.class).single();
    }

    private boolean habilitado(String tabla, long id) {
        return jdbc.sql("SELECT enabled FROM " + tabla + " WHERE id = :id").param("id", id)
                .query(Boolean.class).single();
    }

    private Map<String, Object> estado() {
        return jdbc.sql("SELECT * FROM sync_state").query().singleRow();
    }
}
