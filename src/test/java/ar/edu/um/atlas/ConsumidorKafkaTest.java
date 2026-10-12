package ar.edu.um.atlas;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;
import ar.edu.um.atlas.application.sync.ApplySnapshot;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * El consumidor de {@code CatalogUpdated} contra un Kafka real (Testcontainers) y PostgreSQL real, con REST y
 * Redis simulados. Cada test envía mensajes y espera a que el <i>offset</i> del grupo avance: eso solo ocurre
 * después de que la corrida terminó (o de que el mensaje se descartó), así que comprueba la confirmación
 * manual además del efecto sobre la copia.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, FuenteDeSnapshotDePrueba.Configuracion.class,
        FuenteDeCambiosDePrueba.Configuracion.class})
class ConsumidorKafkaTest {

    private static final String TOPIC = "catedra.catalog.test";
    private static final String GRUPO = "alumnos-test-" + UUID.randomUUID();
    private static final TopicPartition PARTICION = new TopicPartition(TOPIC, 0);
    private static final Instant CREADO = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-03-02T15:30:45Z");
    private static final Instant NUEVO = Instant.parse("2026-09-10T13:40:00Z");

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        kafka.start();
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers",
                kafka.getBootstrapServers()))) {
            // Una sola partición, como el topic real de la cátedra.
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void propiedadesDeKafka(DynamicPropertyRegistry registry) {
        registry.add("atlas.sync.kafka.enabled", () -> "true");
        registry.add("catedra.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("catedra.kafka.catalog-topic", () -> TOPIC);
        registry.add("catedra.kafka.consumer-group-id", () -> GRUPO);
        // Esperas cortas para que los reintentos no alarguen los tests.
        registry.add("catedra.kafka.retry.delay", () -> "50ms");
    }

    @AfterAll
    static void apagarKafka() {
        kafka.stop();
    }

    @Autowired KafkaMessageListenerContainer<String, String> consumidor;
    @Autowired ApplySnapshot applySnapshot;
    @Autowired FuenteDeSnapshotDePrueba snapshots;
    @Autowired FuenteDeCambiosDePrueba redis;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void copiaEnLaVersion1ConElConsumidorAsignado() {
        // Con auto.offset.reset=latest, un mensaje enviado antes de la asignación se perdería.
        await().atMost(Duration.ofSeconds(60)).until(() -> !consumidor.getAssignedPartitions().isEmpty());

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

    @Test
    void unEventoNuevoAplicaLaVersionYConfirmaElOffset() throws Exception {
        long base = offsetConfirmado();
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, "Ana")), List.of());

        enviar(evento(2));

        esperarOffset(base + 1);
        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(estado().get("last_result")).isEqualTo("INCREMENTAL");
        assertThat(jdbc.sql("SELECT first_name FROM professional WHERE id = 1").query(String.class).single())
                .isEqualTo("Ana");
    }

    @Test
    void duplicadosAtrasadosYAdelantadosTerminanEnNoopSinAlterarLaCopia() throws Exception {
        long base = offsetConfirmado();
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, "Ana")), List.of());
        enviar(evento(2));
        esperarOffset(base + 1);

        // El mismo evento otra vez, uno atrasado (versión menor que la local) y uno adelantado (versión que
        // Redis todavía no publicó): el aviso no decide nada.
        enviar(evento(2), evento(1), evento(99));

        esperarOffset(base + 4);
        assertThat(redis.versionesPedidas()).containsExactly(2L);
        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(estado().get("last_result")).isEqualTo("NOOP");
        assertThat(estado().get("status")).isEqualTo("IDLE");
    }

    @Test
    void losMensajesCorruptosSeDescartanSinBloquearYElSiguienteSeProcesa() throws Exception {
        long base = offsetConfirmado();
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, "Ana")), List.of());

        enviar(null, "esto no es json", """
                {"eventType":"AppointmentConfirmed","newVersion":2}""", """
                {"eventType":"CatalogUpdated"}""", evento(2));

        esperarOffset(base + 5);
        // Los cuatro primeros no dispararon nada; el quinto sí.
        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(redis.versionesPedidas()).containsExactly(2L);
    }

    @Test
    void unMensajeCorruptoSoloTambienConfirmaSuOffset() throws Exception {
        long base = offsetConfirmado();

        enviar("esto no es json");

        // Los offsets son acumulativos: si el corrupto fuera el último y no se confirmara, quedaría atrás.
        esperarOffset(base + 1);
        assertThat(redis.lecturasDeMetadata()).isZero();
        assertThat(estado().get("local_version")).isEqualTo(1L);
    }

    @Test
    void losCamposDesconocidosYUnaVersionDeEsquemaNuevaSeIgnoran() throws Exception {
        long base = offsetConfirmado();
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, "Ana")), List.of());

        enviar("""
                {"eventId":"catalog-event-2-x","eventType":"CatalogUpdated","newVersion":2,
                 "occurredAt":"2026-09-10T13:44:59Z","source":"SCHEDULED","schemaVersion":2,"nuevo":{"a":[1]}}""");

        esperarOffset(base + 1);
        assertThat(estado().get("local_version")).isEqualTo(2L);
    }

    @Test
    void siLaSincronizacionFallaReintentaDeFormaAcotadaDescartaYElConsumidorSigue() throws Exception {
        long base = offsetConfirmado();
        redis.fallarMetadata(new CatedraUnavailableException("sin respuesta", null, null, null));

        enviar(evento(2));

        esperarOffset(base + 1);
        // Tres intentos en total (el primero y dos reintentos), y después se descarta con el offset confirmado.
        assertThat(redis.lecturasDeMetadata()).isEqualTo(3);
        assertThat(estado().get("status")).isEqualTo("ERROR");
        assertThat(estado().get("last_error_code")).isEqualTo("CATEDRA_NO_DISPONIBLE");
        assertThat(estado().get("local_version")).isEqualTo(1L);

        // Redis vuelve: el consumidor no quedó trabado y el siguiente aviso se procesa.
        redis.fallarMetadata(null);
        redis.metadata(2, 1);
        redis.publicar(2, List.of(), List.of(profesional(1, "Ana")), List.of());
        enviar(evento(2));

        esperarOffset(base + 2);
        assertThat(estado().get("local_version")).isEqualTo(2L);
        assertThat(estado().get("status")).isEqualTo("IDLE");
        assertThat(estado().get("last_error_code")).isNull();
    }

    // --- soporte ---

    private static String evento(long version) {
        return """
                {"eventId":"catalog-event-%d","eventType":"CatalogUpdated","newVersion":%d,
                 "occurredAt":"2026-09-10T13:44:59Z","source":"MANUAL","schemaVersion":1}""".formatted(version, version);
    }

    /** Publica los valores en el topic, en orden y esperando la confirmación del broker. */
    private static void enviar(String... valores) throws Exception {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> productor = new KafkaProducer<>(config)) {
            for (String valor : valores) {
                productor.send(new ProducerRecord<>(TOPIC, 0, null, valor)).get();
            }
        }
    }

    /** El offset que el grupo confirmó en la única partición (0 si todavía no confirmó ninguno). */
    private static long offsetConfirmado() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers",
                kafka.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(GRUPO)
                    .partitionsToOffsetAndMetadata().get();
            OffsetAndMetadata confirmado = offsets.get(PARTICION);
            return confirmado == null ? 0 : confirmado.offset();
        }
    }

    private static void esperarOffset(long esperado) {
        await().ignoreExceptions().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(offsetConfirmado()).isEqualTo(esperado));
    }

    private static CatalogSnapshot copiaBase() {
        return new CatalogSnapshot(1, ACTUALIZADO,
                List.of(new ProfessionalCategory(1L, "Categoría 1", null, true, CREADO, ACTUALIZADO)),
                List.of(new Professional(1L, 1L, "Nombre 1", "Apellido 1", true, CREADO, ACTUALIZADO)),
                List.of(new WeeklySchedule(1L, 1L, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30,
                        true, CREADO, ACTUALIZADO)));
    }

    private static Professional profesional(long id, String nombre) {
        return new Professional(id, 1L, nombre, "Apellido " + id, true, CREADO, NUEVO);
    }

    private Map<String, Object> estado() {
        return jdbc.sql("SELECT * FROM sync_state").query().singleRow();
    }
}
