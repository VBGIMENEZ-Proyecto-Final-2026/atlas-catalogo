package ar.edu.um.atlas.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El perfil {@code kafka-sasl} del {@code application-kafka-sasl.yaml} real activa {@code SASL_SSL} con el JAAS
 * que viene de la variable de entorno, y el cliente de Kafka no lo vuelca al log. No se conecta a ningún
 * servidor: la cátedra hoy no exige autenticación, así que no hay un Kafka con SASL contra el cual probarlo.
 */
class PerfilKafkaSaslTest {

    private static final String JAAS = "org.apache.kafka.common.security.plain.PlainLoginModule required "
            + "username=\"usuario-jaas\" password=\"secreto-del-jaas\";";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(CatedraKafkaConfig.class, CatedraKafkaPropertiesTest.SyncCatalogDeRelleno.class)
            .withPropertyValues("catedra.kafka.bootstrap-servers=localhost:1",
                    "catedra.kafka.catalog-topic=catedra.catalog.test",
                    "catedra.kafka.consumer-group-id=alumnos-test", "atlas.sync.kafka.enabled=true");

    @Test
    void sinElPerfilLaConexionEsPlaintext() {
        runner.run(context -> assertThat(context.getBean(CatedraKafkaProperties.class).securityProtocol())
                .isEqualTo("PLAINTEXT"));
    }

    @Test
    void conElPerfilLaConexionEsSaslSslConElJaasDeLaVariable() {
        runner.withPropertyValues("spring.profiles.active=kafka-sasl", "CATEDRA_KAFKA_SASL_JAAS_CONFIG=" + JAAS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CatedraKafkaProperties.class).securityProtocol())
                            .isEqualTo("SASL_SSL");
                    assertThat(context.getBean(ConsumerFactory.class).getConfigurationProperties())
                            .containsEntry("sasl.jaas.config", JAAS);
                });
    }

    @Test
    void conElPerfilSinJaasNoArrancaYDiceCual() {
        runner.withPropertyValues("spring.profiles.active=kafka-sasl")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_KAFKA_SASL_JAAS_CONFIG"));
    }

    @Test
    void elClienteNoVuelcaElJaasAlLog() {
        var propiedades = new CatedraKafkaProperties("localhost:1", "catedra.catalog.test", "alumnos-test",
                "SASL_SSL", JAAS, null, null);
        Map<String, Object> config = CatedraKafkaConfig.consumerConfig(propiedades);

        var logger = (Logger) LoggerFactory.getLogger("org.apache.kafka");
        Level anterior = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            // Construir el consumidor no abre ninguna conexión; sí vuelca su configuración al log.
            new KafkaConsumer<String, String>(config).close();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(anterior);
        }

        String log = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(log).contains("sasl.jaas.config = [hidden]").contains("security.protocol = SASL_SSL")
                .doesNotContain("secreto-del-jaas").doesNotContain("usuario-jaas");
    }
}
