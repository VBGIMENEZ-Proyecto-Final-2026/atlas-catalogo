package ar.edu.um.atlas;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.sync.ApplySnapshot;
import ar.edu.um.atlas.application.sync.SyncCatalog;
import ar.edu.um.atlas.application.sync.SyncTrigger;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La sincronización incremental de punta a punta con un Redis real (Testcontainers) cargado con el formato de
 * la referencia de integración (14), PostgreSQL real y el snapshot REST simulado. Es el único test que une el
 * cliente Redis real con el caso de uso.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, FuenteDeSnapshotDePrueba.Configuracion.class})
class SincronizacionConRedisRealTest {

    private static final String USUARIO = "catedra";
    private static final String CLAVE = "clave-de-prueba-redis";
    private static final Instant CREADO = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-03-02T15:30:45Z");

    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        redis.start();
        try {
            // Como la cátedra: el usuario de la integración solo puede leer catedra:sync:*.
            cli("ACL", "SETUSER", USUARIO, "on", ">" + CLAVE, "~catedra:sync:*", "+@read");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void propiedadesDeRedis(DynamicPropertyRegistry registry) {
        registry.add("catedra.redis.host", redis::getHost);
        registry.add("catedra.redis.port", () -> redis.getMappedPort(6379));
        registry.add("catedra.redis.username", () -> USUARIO);
        registry.add("catedra.redis.password", () -> CLAVE);
    }

    @AfterAll
    static void apagarRedis() {
        redis.stop();
    }

    @Autowired SyncCatalog syncCatalog;
    @Autowired ApplySnapshot applySnapshot;
    @Autowired FuenteDeSnapshotDePrueba snapshots;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void copiaEnLaVersion1() throws Exception {
        cli("FLUSHALL");
        snapshots.reiniciar();
        jdbc.sql("DELETE FROM weekly_schedule").update();
        jdbc.sql("DELETE FROM professional").update();
        jdbc.sql("DELETE FROM professional_category").update();
        jdbc.sql("""
                UPDATE sync_state SET local_version = NULL, status = 'IDLE', last_result = NULL,
                    last_snapshot_reason = NULL, last_sync_at = NULL, last_error_code = NULL,
                    last_error_message = NULL, last_error_at = NULL
                """).update();
        snapshots.devolver(copiaBase(1));
        applySnapshot.run(SnapshotReason.BASE_VACIA);
        snapshots.reiniciar();
    }

    @Test
    void aplicaUnaVersionLeidaDeRedisYLaSiguienteVerificacionNoHaceNada() throws Exception {
        publicarMetadata(2, 1);
        cli("SET", "catedra:sync:changes:2", """
                {"version":2,"changes":{"professionalCategories":[],"professionals":[1],"weeklySchedules":[2]}}""");
        cli("HSET", "catedra:sync:professionals", "1", """
                {"id":1,"categoryId":1,"firstName":"Ana","lastName":"Gómez","enabled":true,
                 "createdAt":"2026-03-01T12:00:00Z","updatedAt":"2026-09-10T13:40:00Z"}""");
        cli("HSET", "catedra:sync:weekly-schedules", "2", """
                {"id":2,"professionalId":1,"dayOfWeek":"TUESDAY","startTime":"08:30","endTime":"11:00:00",
                 "slotDurationMinutes":20,"enabled":true,
                 "createdAt":"2026-03-01T12:00:00Z","updatedAt":"2026-09-10T13:40:00Z"}""");

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(jdbc.sql("SELECT first_name FROM professional WHERE id = 1").query(String.class).single())
                .isEqualTo("Ana");
        assertThat(jdbc.sql("SELECT day_of_week FROM weekly_schedule WHERE id = 2").query(String.class).single())
                .isEqualTo("TUESDAY");
        assertThat(jdbc.sql("SELECT start_time FROM weekly_schedule WHERE id = 2").query(LocalTime.class).single())
                .isEqualTo(LocalTime.of(8, 30));
        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(estado().get("last_result")).isEqualTo("INCREMENTAL");
        assertThat(snapshots.pedidos()).isZero();

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(estado().get("last_result")).isEqualTo("NOOP");
    }

    @Test
    void unIdQueYaNoEstaEnElHashSeDeshabilitaLocalmente() throws Exception {
        publicarMetadata(2, 1);
        cli("SET", "catedra:sync:changes:2", """
                {"version":2,"changes":{"professionalCategories":[],"professionals":[3],"weeklySchedules":[]}}""");
        // El Hash de profesionales no tiene al 3.

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(jdbc.sql("SELECT enabled FROM professional WHERE id = 3").query(Boolean.class).single())
                .isFalse();
        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(snapshots.pedidos()).isZero();
    }

    @Test
    void sinMetadataEnRedisReconstruyeConElSnapshot() {
        snapshots.devolver(copiaBase(4));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(snapshots.pedidos()).isEqualTo(1);
        assertThat(estado().get("local_version")).isEqualTo(4L);
        assertThat(estado().get("last_snapshot_reason")).isEqualTo("NO_VERIFICABLE");
    }

    @Test
    void siFaltaElChangesDeUnaVersionReconstruyeConElSnapshot() throws Exception {
        publicarMetadata(3, 1);
        cli("SET", "catedra:sync:changes:2", """
                {"version":2,"changes":{"professionalCategories":[],"professionals":[],"weeklySchedules":[]}}""");
        snapshots.devolver(copiaBase(3));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(estado().get("last_snapshot_reason")).isEqualTo("FALTA_VERSION");
        assertThat(estado().get("local_version")).isEqualTo(3L);
    }

    private static void publicarMetadata(long current, long oldest) throws Exception {
        cli("HSET", "catedra:sync:metadata", "currentVersion", String.valueOf(current),
                "oldestAvailableVersion", String.valueOf(oldest), "publishedAt", "2026-09-10T13:44:59Z");
    }

    private static CatalogSnapshot copiaBase(long version) {
        return new CatalogSnapshot(version, ACTUALIZADO,
                List.of(new ProfessionalCategory(1L, "Categoría 1", null, true, CREADO, ACTUALIZADO),
                        new ProfessionalCategory(2L, "Categoría 2", null, true, CREADO, ACTUALIZADO)),
                List.of(new Professional(1L, 1L, "Nombre 1", "Apellido 1", true, CREADO, ACTUALIZADO),
                        new Professional(2L, 1L, "Nombre 2", "Apellido 2", true, CREADO, ACTUALIZADO),
                        new Professional(3L, 2L, "Nombre 3", "Apellido 3", true, CREADO, ACTUALIZADO)),
                List.of(new WeeklySchedule(1L, 1L, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30,
                                true, CREADO, ACTUALIZADO),
                        new WeeklySchedule(2L, 1L, DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(17, 0), 30,
                                true, CREADO, ACTUALIZADO)));
    }

    private Map<String, Object> estado() {
        return jdbc.sql("SELECT * FROM sync_state").query().singleRow();
    }

    /** Ejecuta {@code redis-cli} dentro del contenedor, como el usuario por defecto (administrador de la prueba). */
    private static void cli(String... args) throws Exception {
        String[] comando = new String[args.length + 1];
        comando[0] = "redis-cli";
        System.arraycopy(args, 0, comando, 1, args.length);
        var resultado = redis.execInContainer(comando);
        assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();
        assertThat(resultado.getStdout()).doesNotContain("ERR");
    }
}
