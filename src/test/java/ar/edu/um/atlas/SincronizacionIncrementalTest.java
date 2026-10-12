package ar.edu.um.atlas;

import ar.edu.um.atlas.adapters.in.schedule.PeriodicSyncCheck;
import ar.edu.um.atlas.adapters.in.startup.SyncOnStartup;
import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatedraCredentialRejectedException;
import ar.edu.um.atlas.application.port.out.CatedraInvalidResponseException;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;
import ar.edu.um.atlas.application.repository.ProfessionalCategoryRepository;
import ar.edu.um.atlas.application.repository.ProfessionalRepository;
import ar.edu.um.atlas.application.repository.SyncStateRepository;
import ar.edu.um.atlas.application.repository.WeeklyScheduleRepository;
import ar.edu.um.atlas.application.sync.ApplyIncremental;
import ar.edu.um.atlas.application.sync.ApplySnapshot;
import ar.edu.um.atlas.application.sync.SyncCatalog;
import ar.edu.um.atlas.application.sync.SyncErrorCode;
import ar.edu.um.atlas.application.sync.SyncException;
import ar.edu.um.atlas.application.sync.SyncLimits;
import ar.edu.um.atlas.application.sync.SyncStateRecorder;
import ar.edu.um.atlas.application.sync.SyncTrigger;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La sincronización incremental contra PostgreSQL real, con la cátedra simulada (REST y Redis). Sin
 * {@code @Transactional}: cada versión abre y cierra su propia transacción. Parte siempre de una copia en la
 * versión 1 (2 categorías, 3 profesionales y 4 horarios).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, FuenteDeSnapshotDePrueba.Configuracion.class,
        FuenteDeCambiosDePrueba.Configuracion.class})
class SincronizacionIncrementalTest {

    private static final Instant CREADO = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-03-02T15:30:45Z");
    private static final Instant NUEVO = Instant.parse("2026-09-10T13:40:00Z");

    @Autowired SyncCatalog syncCatalog;
    @Autowired ApplySnapshot applySnapshot;
    @Autowired FuenteDeSnapshotDePrueba snapshots;
    @Autowired FuenteDeCambiosDePrueba redis;
    @Autowired JdbcClient jdbc;
    @Autowired SyncStateRepository syncState;
    @Autowired ProfessionalCategoryRepository categorias;
    @Autowired ProfessionalRepository profesionales;
    @Autowired WeeklyScheduleRepository horarios;
    @Autowired SyncStateRecorder recorder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;

    @BeforeEach
    void copiaEnLaVersion1() {
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
        snapshots.devolver(copiaBase());
        applySnapshot.run(SnapshotReason.BASE_VACIA);
        snapshots.reiniciar();
    }

    // --- aplicación de versiones ---

    @Test
    void unaVersionNuevaCambiaElHorarioYAvanzaLaVersion() {
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(), List.of(horario(1, 1, LocalTime.of(10, 0), NUEVO)));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(jdbc.sql("SELECT start_time FROM weekly_schedule WHERE id = 1").query(LocalTime.class).single())
                .isEqualTo(LocalTime.of(10, 0));
        assertThat(jdbc.sql("SELECT updated_at FROM weekly_schedule WHERE id = 1").query(Instant.class).single())
                .isEqualTo(NUEVO);
        assertThat(contar("weekly_schedule")).isEqualTo(4);
        Map<String, Object> estado = estado();
        assertThat(estado.get("local_version")).isEqualTo(2L);
        assertThat(estado.get("status")).isEqualTo("IDLE");
        assertThat(estado.get("last_result")).isEqualTo("INCREMENTAL");
        assertThat(estado.get("last_snapshot_reason")).isEqualTo("BASE_VACIA");
        assertThat(estado.get("last_sync_at")).isNotNull();
        assertThat(estado.get("last_error_code")).isNull();
        assertThat(snapshots.pedidos()).isZero();
    }

    @Test
    void variasVersionesSeAplicanEnOrdenYAvanzanUnaPorUna() {
        redis.metadata(4, 1);
        redis.publicar(2, List.of(categoria(1, "Nombre nuevo", NUEVO)), List.of(), List.of());
        redis.publicar(3, List.of(), List.of(profesional(1, 1, "Ana", NUEVO)), List.of());
        redis.publicar(4, List.of(), List.of(), List.of(horario(2, 1, LocalTime.of(8, 0), NUEVO)));

        syncCatalog.run(SyncTrigger.ARRANQUE);

        assertThat(redis.versionesPedidas()).containsExactly(2L, 3L, 4L);
        assertThat(estado().get("local_version")).isEqualTo(4L);
        assertThat(jdbc.sql("SELECT name FROM professional_category WHERE id = 1").query(String.class).single())
                .isEqualTo("Nombre nuevo");
        assertThat(jdbc.sql("SELECT first_name FROM professional WHERE id = 1").query(String.class).single())
                .isEqualTo("Ana");
        assertThat(jdbc.sql("SELECT start_time FROM weekly_schedule WHERE id = 2").query(LocalTime.class).single())
                .isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void unaEntidadNuevaSeInsertaYUnaBajaLogicaSeGuardaTalCual() {
        redis.metadata(2, 1);
        redis.publicar(2, List.of(categoria(3, "Cardiología", NUEVO)),
                List.of(new Professional(4L, 3L, "Luis", "Paz", true, CREADO, NUEVO),
                        new Professional(2L, 1L, "Nombre 2", "Apellido 2", false, CREADO, NUEVO)),
                List.of());

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(contar("professional_category")).isEqualTo(3);
        assertThat(contar("professional")).isEqualTo(4);
        assertThat(habilitado("professional", 4)).isTrue();
        assertThat(habilitado("professional", 2)).isFalse();
    }

    @Test
    void unaSegundaCorridaConLaCopiaAlDiaNoHaceNadaYRegistraNoop() {
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, 1, "Ana", NUEVO)), List.of());
        syncCatalog.run(SyncTrigger.ARRANQUE);
        List<Map<String, Object>> antes = jdbc.sql("SELECT * FROM professional ORDER BY id").query().listOfRows();

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(redis.versionesPedidas()).containsExactly(2L);
        assertThat(jdbc.sql("SELECT * FROM professional ORDER BY id").query().listOfRows()).isEqualTo(antes);
        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(estado().get("last_result")).isEqualTo("NOOP");
        assertThat(estado().get("status")).isEqualTo("IDLE");
    }

    @Test
    void reaplicarUnaVersionYaAplicadaDejaLaCopiaIgual() {
        redis.metadata(2, 1);
        redis.publicar(2, List.of(categoria(1, "Nombre nuevo", NUEVO)),
                List.of(profesional(1, 1, "Ana", NUEVO)), List.of(horario(1, 1, LocalTime.of(10, 0), NUEVO)));
        syncCatalog.run(SyncTrigger.ARRANQUE);
        List<Map<String, Object>> despuesDeLaPrimera = copiaCompleta();

        jdbc.sql("UPDATE sync_state SET local_version = 1").update();
        syncCatalog.run(SyncTrigger.ARRANQUE);

        assertThat(redis.versionesPedidas()).containsExactly(2L, 2L);
        assertThat(copiaCompleta()).isEqualTo(despuesDeLaPrimera);
        assertThat(estado().get("local_version")).isEqualTo(2L);
    }

    @Test
    void siCurrentSubeDuranteLaCorridaSigueConLasVersionesNuevas() {
        // Primera lectura: current 2. Al terminar la versión 2 se relee y ya hay una 3.
        redis.metadata(Optional.of(new ar.edu.um.atlas.application.port.out.CatalogMetadata(2, 1, null)),
                Optional.of(new ar.edu.um.atlas.application.port.out.CatalogMetadata(3, 1, null)));
        redis.publicar(2, List.of(), List.of(profesional(1, 1, "Ana", NUEVO)), List.of());
        redis.publicar(3, List.of(), List.of(profesional(2, 1, "Luis", NUEVO)), List.of());

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(redis.versionesPedidas()).containsExactly(2L, 3L);
        assertThat(estado().get("local_version")).isEqualTo(3L);
    }

    @Test
    void elTopeDeVersionesPorCorridaCortaLaCorridaYLaSiguienteContinua() {
        redis.metadata(5, 1);
        for (long v = 2; v <= 5; v++) {
            redis.publicar(v, List.of(), List.of(profesional(1, 1, "Versión " + v, NUEVO)), List.of());
        }
        ApplyIncremental conTope = new ApplyIncremental(redis, categorias, profesionales, horarios, syncState,
                recorder, transactionManager, clock, new SyncLimits(2));

        ApplyIncremental.Result resultado = conTope.run(2, 5);

        assertThat(resultado).isEqualTo(new ApplyIncremental.Result.Done(2));
        assertThat(estado().get("local_version")).isEqualTo(3L);

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(estado().get("local_version")).isEqualTo(5L);
        assertThat(jdbc.sql("SELECT first_name FROM professional WHERE id = 1").query(String.class).single())
                .isEqualTo("Versión 5");
    }

    // --- planes que terminan en snapshot ---

    @Test
    void siFaltaUnaVersionPideSnapshotPorFaltaVersion() {
        redis.metadata(3, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, 1, "Ana", NUEVO)), List.of());
        // La versión 3 no está en Redis.
        snapshots.devolver(snapshotDeVersion(3));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(snapshots.pedidos()).isEqualTo(1);
        assertThat(estado().get("local_version")).isEqualTo(3L);
        assertThat(estado().get("last_result")).isEqualTo("SNAPSHOT");
        assertThat(estado().get("last_snapshot_reason")).isEqualTo("FALTA_VERSION");
    }

    @Test
    void lasVersionesYaAplicadasSeConservanSiElSnapshotDeEscaladaFalla() {
        redis.metadata(3, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, 1, "Ana", NUEVO)), List.of());
        snapshots.fallar(new CatedraUnavailableException("sin respuesta", null, null, null));

        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA))
                .isInstanceOfSatisfying(SyncException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SyncErrorCode.CATEDRA_NO_DISPONIBLE));

        // La versión 2 quedó aplicada: la copia es consistente y avanza hasta donde se pudo.
        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT first_name FROM professional WHERE id = 1").query(String.class).single())
                .isEqualTo("Ana");
        assertThat(estado().get("status")).isEqualTo("ERROR");
        assertThat(snapshots.pedidos()).isEqualTo(1);
    }

    @Test
    void unIdAusenteDeRedisSeDeshabilitaLocalmenteSinEscalar() {
        redis.metadata(2, 1);
        redis.publicarSoloIds(2, List.of(), List.of(3L), List.of());

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(habilitado("professional", 3)).isFalse();
        assertThat(habilitado("professional", 1)).isTrue();
        assertThat(jdbc.sql("SELECT updated_at FROM professional WHERE id = 3").query(Instant.class).single())
                .isEqualTo(ACTUALIZADO);
        assertThat(contar("professional")).isEqualTo(3);
        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(estado().get("last_result")).isEqualTo("INCREMENTAL");
        assertThat(snapshots.pedidos()).isZero();
    }

    @Test
    void dosIdsAusentesEnLaMismaVersionPidenSnapshotPorNoVerificable() {
        redis.metadata(2, 1);
        redis.publicarSoloIds(2, List.of(), List.of(2L, 3L), List.of());
        snapshots.devolver(snapshotDeVersion(2));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(snapshots.pedidos()).isEqualTo(1);
        assertThat(estado().get("last_snapshot_reason")).isEqualTo("NO_VERIFICABLE");
        assertThat(estado().get("last_result")).isEqualTo("SNAPSHOT");
    }

    @Test
    void dosIdsAusentesRevierteLaVersionEnCurso() {
        redis.metadata(2, 1);
        redis.publicarSoloIds(2, List.of(), List.of(2L, 3L), List.of());
        snapshots.fallar(new CatedraUnavailableException("sin respuesta", null, null, null));

        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA))
                .isInstanceOf(SyncException.class);

        // Ni siquiera el primer ID ausente quedó deshabilitado: la versión entera se revirtió.
        assertThat(habilitado("professional", 2)).isTrue();
        assertThat(habilitado("professional", 3)).isTrue();
        assertThat(estado().get("local_version")).isEqualTo(1L);
    }

    @Test
    void unSegundoIdAusenteEnUnaVersionPosteriorDeLaCorridaPideSnapshot() {
        redis.metadata(3, 1);
        redis.publicarSoloIds(2, List.of(), List.of(3L), List.of());
        redis.publicarSoloIds(3, List.of(), List.of(2L), List.of());
        snapshots.fallar(new CatedraUnavailableException("sin respuesta", null, null, null));

        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA))
                .isInstanceOf(SyncException.class);

        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(habilitado("professional", 3)).isFalse();
        assertThat(habilitado("professional", 2)).isTrue();
        assertThat(snapshots.pedidos()).isEqualTo(1);
    }

    @Test
    void unaReferenciaInexistenteRevierteLaVersionYPideSnapshot() {
        redis.metadata(2, 1);
        // La categoría cambia y un horario apunta a un profesional que no existe: la clave foránea falla
        // después de haber aplicado la categoría.
        redis.publicar(2, List.of(categoria(1, "No debe quedar", NUEVO)), List.of(),
                List.of(horario(9, 99, LocalTime.of(9, 0), NUEVO)));
        snapshots.fallar(new CatedraUnavailableException("sin respuesta", null, null, null));

        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA))
                .isInstanceOf(SyncException.class);

        assertThat(jdbc.sql("SELECT name FROM professional_category WHERE id = 1").query(String.class).single())
                .isEqualTo("Categoría 1");
        assertThat(contar("weekly_schedule")).isEqualTo(4);
        assertThat(estado().get("local_version")).isEqualTo(1L);
        assertThat(snapshots.pedidos()).isEqualTo(1);
    }

    @Test
    void unaReferenciaInexistenteEscalaConElMotivoNoVerificable() {
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(), List.of(horario(9, 99, LocalTime.of(9, 0), NUEVO)));
        snapshots.devolver(snapshotDeVersion(2));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(estado().get("last_snapshot_reason")).isEqualTo("NO_VERIFICABLE");
        assertThat(estado().get("local_version")).isEqualTo(2L);
    }

    @Test
    void datosInvalidosEnLosCambiosPidenSnapshotPorNoVerificable() {
        redis.metadata(2, 1);
        redis.fallarCambios(new CatedraInvalidResponseException("changes:2 no es un JSON válido", null, null, null));
        snapshots.devolver(snapshotDeVersion(2));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(estado().get("last_snapshot_reason")).isEqualTo("NO_VERIFICABLE");
        assertThat(estado().get("local_version")).isEqualTo(2L);
    }

    @Test
    void sinMetadataPideSnapshotPorNoVerificable() {
        redis.sinMetadata();
        snapshots.devolver(snapshotDeVersion(1));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(estado().get("last_snapshot_reason")).isEqualTo("NO_VERIFICABLE");
        assertThat(snapshots.pedidos()).isEqualTo(1);
    }

    @Test
    void unaMetadataQueNoSePuedeInterpretarPideSnapshotPorNoVerificable() {
        redis.fallarMetadata(new CatedraInvalidResponseException("valor no numérico", null, null, null));
        snapshots.devolver(snapshotDeVersion(1));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(estado().get("last_snapshot_reason")).isEqualTo("NO_VERIFICABLE");
    }

    @Test
    void conLaLocalAdelantadaReconstruyeConElSnapshot() {
        jdbc.sql("UPDATE sync_state SET local_version = 5").update();
        redis.metadata(3, 1);
        snapshots.devolver(snapshotDeVersion(3));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(estado().get("last_snapshot_reason")).isEqualTo("LOCAL_ADELANTADA");
        assertThat(estado().get("local_version")).isEqualTo(3L);
    }

    @Test
    void conLaLocalPorDebajoDeLaVentanaReconstruyeConElSnapshot() {
        redis.metadata(7, 4);
        snapshots.devolver(snapshotDeVersion(7));

        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(redis.versionesPedidas()).isEmpty();
        assertThat(estado().get("last_snapshot_reason")).isEqualTo("FUERA_DE_VENTANA");
        assertThat(estado().get("local_version")).isEqualTo(7L);
    }

    // --- fallas de integración: no escalan a snapshot ---

    @Test
    void conRedisCaidoQuedaElErrorSinSnapshotYLaSiguienteVerificacionSeRecupera() {
        redis.fallarMetadata(new CatedraUnavailableException("sin respuesta", null, null, null));

        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA))
                .isInstanceOfSatisfying(SyncException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SyncErrorCode.CATEDRA_NO_DISPONIBLE));

        assertThat(snapshots.pedidos()).isZero();
        assertThat(contarHabilitados("weekly_schedule")).isEqualTo(4);
        assertThat(estado().get("local_version")).isEqualTo(1L);
        assertThat(estado().get("status")).isEqualTo("ERROR");
        assertThat(estado().get("last_error_code")).isEqualTo("CATEDRA_NO_DISPONIBLE");

        // Redis vuelve: la siguiente verificación se recupera sola y limpia el error.
        redis.fallarMetadata(null);
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, 1, "Ana", NUEVO)), List.of());
        syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);

        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(estado().get("status")).isEqualTo("IDLE");
        assertThat(estado().get("last_error_code")).isNull();
        assertThat(estado().get("last_error_message")).isNull();
    }

    @Test
    void unaCredencialRechazadaAlLeerLosCambiosDejaElErrorSinAvanzarNiEscalar() {
        redis.metadata(2, 1);
        redis.fallarCambios(new CatedraCredentialRejectedException("rechazada", null, null, null));

        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA))
                .isInstanceOfSatisfying(SyncException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SyncErrorCode.CATEDRA_CREDENCIAL_RECHAZADA));

        assertThat(estado().get("local_version")).isEqualTo(1L);
        assertThat(estado().get("last_error_code")).isEqualTo("CATEDRA_CREDENCIAL_RECHAZADA");
        assertThat(snapshots.pedidos()).isZero();
    }

    @Test
    void unaFallaInesperadaDeAplicacionQuedaConSuPropioCodigo() {
        redis.metadata(2, 1);
        redis.fallarCambios(new IllegalStateException("falla inesperada con detalle interno"));

        assertThatThrownBy(() -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA))
                .isInstanceOfSatisfying(SyncException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SyncErrorCode.INCREMENTAL_NO_APLICABLE));

        assertThat(estado().get("last_error_code")).isEqualTo("INCREMENTAL_NO_APLICABLE");
        assertThat((String) estado().get("last_error_message")).doesNotContain("detalle interno");
        assertThat(snapshots.pedidos()).isZero();
    }

    @Test
    void conLaBaseVaciaLaPrimeraCargaNoLeeRedis() {
        jdbc.sql("UPDATE sync_state SET local_version = NULL").update();
        redis.fallarMetadata(new CatedraUnavailableException("sin respuesta", null, null, null));
        snapshots.devolver(snapshotDeVersion(1));

        syncCatalog.run(SyncTrigger.ARRANQUE);

        assertThat(redis.lecturasDeMetadata()).isZero();
        assertThat(estado().get("local_version")).isEqualTo(1L);
        assertThat(estado().get("last_snapshot_reason")).isEqualTo("BASE_VACIA");
    }

    // --- concurrencia ---

    @Test
    void dosCorridasEnParaleloSeSerializanYLaSegundaVuelveAPlanificar() throws Exception {
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, 1, "Ana", NUEVO)), List.of());
        CountDownLatch entro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        redis.frenarCambios(entro, soltar);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> primera = CompletableFuture.runAsync(
                    () -> syncCatalog.run(SyncTrigger.ARRANQUE), pool);
            assertThat(entro.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> segunda = CompletableFuture.runAsync(
                    () -> syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA), pool);
            Thread.sleep(1500);

            // La segunda ya planificó la versión 2, pero espera el candado: todavía no la pidió a Redis.
            assertThat(segunda.isDone()).isFalse();
            assertThat(redis.versionesPedidas()).containsExactly(2L);

            soltar.countDown();
            primera.get(30, TimeUnit.SECONDS);
            segunda.get(30, TimeUnit.SECONDS);

            // La segunda encontró la versión 2 aplicada, volvió a planificar y terminó en Noop.
            assertThat(redis.versionesPedidas()).containsExactly(2L);
            assertThat(estado().get("local_version")).isEqualTo(2L);
            assertThat(estado().get("last_result")).isEqualTo("NOOP");
        } finally {
            soltar.countDown();
            pool.shutdownNow();
        }
    }

    // --- disparadores ---

    @Test
    void alArrancarConUnaVersionLocalAtrasadaAplicaLosCambiosPerdidos() {
        redis.metadata(3, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, 1, "Ana", NUEVO)), List.of());
        redis.publicar(3, List.of(), List.of(profesional(2, 1, "Luis", NUEVO)), List.of());

        new SyncOnStartup(syncCatalog).onReady();

        assertThat(estado().get("local_version")).isEqualTo(3L);
        assertThat(snapshots.pedidos()).isZero();
    }

    @Test
    void alArrancarConLaCopiaAlDiaSoloVerifica() {
        redis.metadata(1, 1);

        new SyncOnStartup(syncCatalog).onReady();

        assertThat(redis.versionesPedidas()).isEmpty();
        assertThat(snapshots.pedidos()).isZero();
        assertThat(estado().get("last_result")).isEqualTo("NOOP");
    }

    @Test
    void siRedisFallaAlArrancarLaAplicacionSigueYQuedaElError() {
        redis.fallarMetadata(new CatedraUnavailableException("sin respuesta", null, null, null));

        new SyncOnStartup(syncCatalog).onReady();

        assertThat(estado().get("status")).isEqualTo("ERROR");
        assertThat(estado().get("last_error_code")).isEqualTo("CATEDRA_NO_DISPONIBLE");
        assertThat(estado().get("local_version")).isEqualTo(1L);
    }

    @Test
    void laVerificacionPeriodicaAplicaLosCambiosYNuncaLanzaExcepciones() {
        PeriodicSyncCheck verificacion = new PeriodicSyncCheck(syncCatalog);
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, 1, "Ana", NUEVO)), List.of());

        verificacion.run();

        assertThat(estado().get("local_version")).isEqualTo(2L);

        redis.fallarMetadata(new CatedraUnavailableException("sin respuesta", null, null, null));
        verificacion.run(); // no lanza: el error queda en sync_state
        assertThat(estado().get("last_error_code")).isEqualTo("CATEDRA_NO_DISPONIBLE");

        redis.fallarMetadata(new IllegalStateException("falla inesperada"));
        verificacion.run(); // tampoco lanza
    }

    // --- soporte ---

    private static CatalogSnapshot copiaBase() {
        return new CatalogSnapshot(1, ACTUALIZADO,
                List.of(categoria(1, "Categoría 1", ACTUALIZADO), categoria(2, "Categoría 2", ACTUALIZADO)),
                List.of(profesional(1, 1, "Nombre 1", ACTUALIZADO), profesional(2, 1, "Nombre 2", ACTUALIZADO),
                        profesional(3, 2, "Nombre 3", ACTUALIZADO)),
                List.of(horario(1, 1, LocalTime.of(9, 0, 30), ACTUALIZADO),
                        horario(2, 1, LocalTime.of(9, 0), ACTUALIZADO),
                        horario(3, 2, LocalTime.of(9, 0), ACTUALIZADO),
                        horario(4, 3, LocalTime.of(9, 0), ACTUALIZADO)));
    }

    private static CatalogSnapshot snapshotDeVersion(long version) {
        CatalogSnapshot base = copiaBase();
        return new CatalogSnapshot(version, ACTUALIZADO, base.categories(), base.professionals(),
                base.weeklySchedules());
    }

    private static ProfessionalCategory categoria(long id, String nombre, Instant actualizado) {
        return new ProfessionalCategory(id, nombre, null, true, CREADO, actualizado);
    }

    private static Professional profesional(long id, long categoriaId, String nombre, Instant actualizado) {
        return new Professional(id, categoriaId, nombre, "Apellido " + id, true, CREADO, actualizado);
    }

    private static WeeklySchedule horario(long id, long profesionalId, LocalTime inicio, Instant actualizado) {
        return new WeeklySchedule(id, profesionalId, DayOfWeek.MONDAY, inicio, LocalTime.of(12, 0), 30, true,
                CREADO, actualizado);
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

    private List<Map<String, Object>> copiaCompleta() {
        List<Map<String, Object>> filas = new java.util.ArrayList<>();
        for (String tabla : List.of("professional_category", "professional", "weekly_schedule")) {
            filas.addAll(jdbc.sql("SELECT * FROM " + tabla + " ORDER BY id").query().listOfRows());
        }
        return filas;
    }

    private Map<String, Object> estado() {
        return jdbc.sql("SELECT * FROM sync_state").query().singleRow();
    }
}
