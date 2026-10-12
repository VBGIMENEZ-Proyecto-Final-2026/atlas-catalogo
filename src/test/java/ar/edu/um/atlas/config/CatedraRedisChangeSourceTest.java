package ar.edu.um.atlas.config;

import ar.edu.um.atlas.adapters.out.redis.CatedraRedisChangeSource;
import ar.edu.um.atlas.application.port.out.CatalogChangeSource;
import ar.edu.um.atlas.application.port.out.CatalogMetadata;
import ar.edu.um.atlas.application.port.out.CatedraCredentialRejectedException;
import ar.edu.um.atlas.application.port.out.CatedraInvalidResponseException;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;
import ar.edu.um.atlas.application.port.out.VersionChanges;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El cliente Redis contra un Redis real (Testcontainers). Los datos se cargan con el formato de la
 * referencia de integración (14) y el cliente se conecta con un usuario ACL que solo puede leer
 * {@code catedra:sync:*}, como el de la cátedra: si el adaptador escribiera o leyera otra clave, Redis
 * respondería {@code NOPERM} y los tests fallarían.
 */
class CatedraRedisChangeSourceTest {

    private static final String USUARIO = "catedra";
    private static final String CLAVE = "clave-de-prueba-redis";
    /** Igual a {@code CatedraRedisChangeSource.BATCH_SIZE}: el test cruza el límite del lote. */
    private static final int TAMANO_DE_LOTE = 500;

    static GenericContainer<?> redis;

    private final CatedraRedisConfig config = new CatedraRedisConfig();
    private final List<LettuceConnectionFactory> fabricas = new ArrayList<>();

    @BeforeAll
    static void levantarRedis() throws Exception {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        cli("ACL", "SETUSER", USUARIO, "on", ">" + CLAVE, "~catedra:sync:*", "+@read");
        cli("ACL", "SETUSER", "sinpermiso", "on", ">" + CLAVE, "~otra:*", "+@read");
    }

    @AfterAll
    static void apagarRedis() {
        redis.stop();
    }

    @AfterEach
    void cerrarConexiones() {
        fabricas.forEach(LettuceConnectionFactory::destroy);
        fabricas.clear();
    }

    @BeforeEach
    void limpiar() throws Exception {
        cli("FLUSHALL");
    }

    // --- metadata ---

    @Test
    void leeLaMetadataCompleta() throws Exception {
        cli("HSET", "catedra:sync:metadata", "currentVersion", "7", "oldestAvailableVersion", "4",
                "publishedAt", "2026-09-10T13:44:59Z");

        Optional<CatalogMetadata> metadata = fuente().fetchMetadata();

        assertThat(metadata).contains(new CatalogMetadata(7, 4, Instant.parse("2026-09-10T13:44:59Z")));
    }

    @Test
    void sinMetadataDevuelveVacio() {
        assertThat(fuente().fetchMetadata()).isEmpty();
    }

    @Test
    void laMetadataSinFechaDePublicacionLaDejaNula() throws Exception {
        cli("HSET", "catedra:sync:metadata", "currentVersion", "3", "oldestAvailableVersion", "3");

        assertThat(fuente().fetchMetadata()).contains(new CatalogMetadata(3, 3, null));
    }

    @Test
    void laMetadataNoNumericaEsInvalida() throws Exception {
        cli("HSET", "catedra:sync:metadata", "currentVersion", "siete", "oldestAvailableVersion", "4");

        assertThatThrownBy(() -> fuente().fetchMetadata()).isInstanceOf(CatedraInvalidResponseException.class)
                .hasMessageContaining("currentVersion");
    }

    @Test
    void laMetadataSinUnCampoEsInvalida() throws Exception {
        cli("HSET", "catedra:sync:metadata", "currentVersion", "7");

        assertThatThrownBy(() -> fuente().fetchMetadata()).isInstanceOf(CatedraInvalidResponseException.class)
                .hasMessageContaining("oldestAvailableVersion");
    }

    // --- cambios ---

    @Test
    void leeLosCambiosDeUnaVersion() throws Exception {
        cli("SET", "catedra:sync:changes:7", """
                {"version":7,"changes":{"professionalCategories":[],"professionals":[15,16],"weeklySchedules":[21]}}""");

        assertThat(fuente().fetchChanges(7)).contains(new VersionChanges(7, List.of(), List.of(15L, 16L), List.of(21L)));
    }

    @Test
    void unaVersionSinCambiosTraeLosTresArreglosVacios() throws Exception {
        cli("SET", "catedra:sync:changes:8", """
                {"version":8,"changes":{"professionalCategories":[],"professionals":[],"weeklySchedules":[]}}""");

        assertThat(fuente().fetchChanges(8)).contains(new VersionChanges(8, List.of(), List.of(), List.of()));
    }

    @Test
    void siFaltaLaVersionDevuelveVacio() {
        assertThat(fuente().fetchChanges(9)).isEmpty();
    }

    @Test
    void unaVersionQueNoCoincideEsInvalida() throws Exception {
        cli("SET", "catedra:sync:changes:7", """
                {"version":6,"changes":{"professionalCategories":[],"professionals":[],"weeklySchedules":[]}}""");

        assertThatThrownBy(() -> fuente().fetchChanges(7)).isInstanceOf(CatedraInvalidResponseException.class);
    }

    @Test
    void cambiosConJsonInvalidoOSinCamposSonInvalidos() throws Exception {
        cli("SET", "catedra:sync:changes:1", "esto no es json");
        cli("SET", "catedra:sync:changes:2", """
                {"version":2}""");
        cli("SET", "catedra:sync:changes:3", """
                {"version":3,"changes":{"professionalCategories":[],"professionals":[null],"weeklySchedules":[]}}""");

        for (long version : List.of(1L, 2L, 3L)) {
            assertThatThrownBy(() -> fuente().fetchChanges(version))
                    .isInstanceOf(CatedraInvalidResponseException.class);
        }
    }

    // --- entidades ---

    @Test
    void leeLasCategoriasPresentesYOmiteLasAusentes() throws Exception {
        cli("HSET", "catedra:sync:professional-categories",
                "1", categoria(1, "Clínica médica"), "2", categoria(2, "Pediatría"));

        List<ProfessionalCategory> leidas = fuente().fetchCategories(List.of(2L, 99L, 1L));

        assertThat(leidas).extracting(ProfessionalCategory::getId).containsExactly(2L, 1L);
        assertThat(leidas.get(1).getName()).isEqualTo("Clínica médica");
        assertThat(leidas.get(1).getDescription()).isEqualTo("Atención clínica general");
        assertThat(leidas.get(1).isEnabled()).isTrue();
        assertThat(leidas.get(1).getCreatedAt()).isEqualTo(Instant.parse("2026-07-01T12:00:00Z"));
        assertThat(leidas.get(1).getUpdatedAt()).isEqualTo(Instant.parse("2026-09-10T13:40:00Z"));
    }

    @Test
    void leeProfesionalesIgnorandoCamposDesconocidos() throws Exception {
        cli("HSET", "catedra:sync:professionals", "15", """
                {"id":15,"categoryId":1,"firstName":"Ana","lastName":"Gómez","enabled":false,
                 "createdAt":"2026-07-01T12:00:00Z","updatedAt":"2026-09-10T13:40:00Z","campoNuevo":{"x":1}}""");

        List<Professional> leidos = fuente().fetchProfessionals(List.of(15L));

        assertThat(leidos).singleElement().satisfies(p -> {
            assertThat(p.getCategoryId()).isEqualTo(1L);
            assertThat(p.getFirstName()).isEqualTo("Ana");
            assertThat(p.getLastName()).isEqualTo("Gómez");
            assertThat(p.isEnabled()).isFalse();
        });
    }

    @Test
    void leeHorariosConHorasConYSinSegundos() throws Exception {
        cli("HSET", "catedra:sync:weekly-schedules",
                "21", horario(21, "09:00:00", "12:00:00"), "22", horario(22, "09:00", "12:30"));

        List<WeeklySchedule> leidos = fuente().fetchWeeklySchedules(List.of(21L, 22L));

        assertThat(leidos).hasSize(2);
        assertThat(leidos.get(0).getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(leidos.get(0).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(leidos.get(0).getSlotDurationMinutes()).isEqualTo(30);
        assertThat(leidos.get(1).getEndTime()).isEqualTo(LocalTime.of(12, 30));
    }

    @Test
    void sinIdsNoHaceFalta() {
        assertThat(fuente().fetchCategories(List.of())).isEmpty();
    }

    @Test
    void conMasIdsQueElLoteLosLeeTodos() throws Exception {
        int total = TAMANO_DE_LOTE + 100;
        List<String> comando = new ArrayList<>(List.of("HSET", "catedra:sync:professional-categories"));
        for (long id = 1; id <= total; id++) {
            comando.add(String.valueOf(id));
            comando.add(categoria(id, "Categoría " + id));
        }
        cli(comando.toArray(String[]::new));

        List<Long> ids = LongStream.rangeClosed(1, total + 5).boxed().toList();

        assertThat(fuente().fetchCategories(ids)).hasSize(total);
    }

    @Test
    void unaEntidadConJsonInvalidoOSinCampoObligatorioEsInvalida() throws Exception {
        cli("HSET", "catedra:sync:professional-categories", "1", "no es json", "2", """
                {"id":2,"description":"sin nombre","enabled":true,
                 "createdAt":"2026-07-01T12:00:00Z","updatedAt":"2026-09-10T13:40:00Z"}""");

        assertThatThrownBy(() -> fuente().fetchCategories(List.of(1L)))
                .isInstanceOf(CatedraInvalidResponseException.class);
        assertThatThrownBy(() -> fuente().fetchCategories(List.of(2L)))
                .isInstanceOf(CatedraInvalidResponseException.class).hasMessageContaining("name");
    }

    @Test
    void unaEntidadConIdDistintoDelFieldEsInvalida() throws Exception {
        cli("HSET", "catedra:sync:professional-categories", "1", categoria(5, "Otra"));

        assertThatThrownBy(() -> fuente().fetchCategories(List.of(1L)))
                .isInstanceOf(CatedraInvalidResponseException.class);
    }

    // --- fallas de conexión y de credencial ---

    @Test
    void conRedisCaidoFallaComoNoDisponibleDespuesDeLosReintentos() throws Exception {
        int puertoSinServicio = puertoLibre();
        CatalogChangeSource fuente = fuente(propiedades("localhost", puertoSinServicio, USUARIO, CLAVE,
                new CatedraRedisProperties.Retry(2, Duration.ofMillis(150), 1.0, Duration.ofMillis(150))));

        long inicio = System.nanoTime();
        assertThatThrownBy(fuente::fetchMetadata).isInstanceOf(CatedraUnavailableException.class);
        long milis = Duration.ofNanos(System.nanoTime() - inicio).toMillis();

        // Tres intentos con dos esperas de 150 ms: reintenta, pero acotado.
        assertThat(milis).isBetween(300L, 15_000L);
    }

    @Test
    void conUnaContrasenaIncorrectaSeRechazaLaCredencialYNoSeReintenta() {
        CatalogChangeSource fuente = fuente(propiedades(redis.getHost(), redis.getMappedPort(6379), USUARIO,
                "contraseña-equivocada", new CatedraRedisProperties.Retry(2, Duration.ofSeconds(5), 1.0,
                        Duration.ofSeconds(5))));

        long inicio = System.nanoTime();
        assertThatThrownBy(fuente::fetchMetadata).isInstanceOf(CatedraCredentialRejectedException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("contraseña-equivocada"));
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void unUsuarioSinPermisosSobreLasClavesDeSyncSeRechaza() {
        CatalogChangeSource fuente = fuente(propiedades(redis.getHost(), redis.getMappedPort(6379), "sinpermiso",
                CLAVE, SIN_ESPERA_PROPS));

        assertThatThrownBy(fuente::fetchMetadata).isInstanceOf(CatedraCredentialRejectedException.class);
    }

    // --- soporte ---

    private CatalogChangeSource fuente() {
        return fuente(propiedades(redis.getHost(), redis.getMappedPort(6379), USUARIO, CLAVE, SIN_ESPERA_PROPS));
    }

    private CatalogChangeSource fuente(CatedraRedisProperties propiedades) {
        var fabrica = (LettuceConnectionFactory) config.catedraRedisConnectionFactory(propiedades);
        fabrica.afterPropertiesSet();
        fabricas.add(fabrica);
        return config.catalogChangeSource(fabrica, propiedades);
    }

    private static final CatedraRedisProperties.Retry SIN_ESPERA_PROPS =
            new CatedraRedisProperties.Retry(2, Duration.ofMillis(10), 1.0, Duration.ofMillis(10));

    private static CatedraRedisProperties propiedades(String host, int puerto, String usuario, String clave,
                                                      CatedraRedisProperties.Retry retry) {
        return new CatedraRedisProperties(host, puerto, usuario, clave, Duration.ofSeconds(1), Duration.ofSeconds(2),
                retry);
    }

    private static int puertoLibre() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String categoria(long id, String nombre) {
        return """
                {"id":%d,"name":"%s","description":"Atención clínica general","enabled":true,
                 "createdAt":"2026-07-01T12:00:00Z","updatedAt":"2026-09-10T13:40:00Z"}""".formatted(id, nombre);
    }

    private static String horario(long id, String inicio, String fin) {
        return """
                {"id":%d,"professionalId":15,"dayOfWeek":"MONDAY","startTime":"%s","endTime":"%s",
                 "slotDurationMinutes":30,"enabled":true,
                 "createdAt":"2026-07-01T12:00:00Z","updatedAt":"2026-09-10T13:40:00Z"}""".formatted(id, inicio, fin);
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
