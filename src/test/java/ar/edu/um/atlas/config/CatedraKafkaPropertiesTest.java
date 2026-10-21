package ar.edu.um.atlas.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Si falta una variable obligatoria de Kafka, la aplicación no arranca y el mensaje indica cuál. Con el
 * consumidor apagado no se exige ninguna y no se crea nada.
 */
class CatedraKafkaPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatedraKafkaConfig.class, SyncCatalogDeRelleno.class);

    private static final String JAAS = "org.apache.kafka.common.security.plain.PlainLoginModule required "
            + "username=\"usuario-jaas\" password=\"secreto-del-jaas\";";

    private static final String[] COMPLETAS = {
            "catedra.kafka.bootstrap-servers=kafka.test.invalid:9092",
            "catedra.kafka.catalog-topic=catedra.catalog.test", "catedra.kafka.consumer-group-id=alumnos-test"};

    @Test
    void conTodasLasVariablesCreaElContenedorDelConsumidor() {
        runner.withPropertyValues(COMPLETAS).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KafkaMessageListenerContainer.class);
        });
    }

    @Test
    void siLosServidoresNoSeResuelvenLaAplicacionArrancaIgual() {
        // El cliente de Kafka falla al construirse con servidores que no resuelven: no debe tumbar el arranque.
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.start-retry-interval=200ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(KafkaMessageListenerContainer.class).isRunning()).isFalse();
                });
    }

    @Test
    void sinServidoresNoArrancaYDiceCual() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.bootstrap-servers=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_KAFKA_BOOTSTRAP_SERVERS"));
    }

    @Test
    void sinTopicNoArrancaYDiceCual() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.catalog-topic=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_KAFKA_CATALOG_TOPIC"));
    }

    @Test
    void sinConsumerGroupNoArrancaYDiceCual() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.consumer-group-id=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_KAFKA_CONSUMER_GROUP_ID"));
    }

    @Test
    void conElConsumidorApagadoNoSeExigeNadaYNoSeCreaNada() {
        runner.withPropertyValues("atlas.sync.kafka.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(KafkaMessageListenerContainer.class);
            assertThat(context).doesNotHaveBean(CatedraKafkaProperties.class);
        });
    }

    @Test
    void unMaximoDeIntentosInvalidoNoArranca() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.retry.max-attempts=0")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_KAFKA_MAX_ATTEMPTS"));
    }

    @Test
    void sinNingunaVariableDeSeguridadLaConexionEsPlaintextYSinSasl() {
        runner.withPropertyValues(COMPLETAS).run(context -> {
            assertThat(context.getBean(CatedraKafkaProperties.class).securityProtocol()).isEqualTo("PLAINTEXT");
            Map<String, Object> config = context.getBean(ConsumerFactory.class).getConfigurationProperties();
            assertThat(config).containsEntry("security.protocol", "PLAINTEXT")
                    .doesNotContainKeys("sasl.mechanism", "sasl.jaas.config");
        });
    }

    @Test
    void conSaslSslElClienteRecibeElProtocoloElMecanismoYElJaas() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.security-protocol=SASL_SSL",
                "catedra.kafka.sasl-jaas-config=" + JAAS).run(context -> {
            assertThat(context).hasNotFailed();
            Map<String, Object> config = context.getBean(ConsumerFactory.class).getConfigurationProperties();
            assertThat(config).containsEntry("security.protocol", "SASL_SSL")
                    .containsEntry("sasl.mechanism", "PLAIN").containsEntry("sasl.jaas.config", JAAS);
        });
    }

    @Test
    void elProtocoloSeNormalizaAMayusculas() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.security-protocol= sasl_ssl ",
                "catedra.kafka.sasl-jaas-config=" + JAAS).run(context ->
                assertThat(context.getBean(CatedraKafkaProperties.class).securityProtocol()).isEqualTo("SASL_SSL"));
    }

    @Test
    void conSaslSslSinJaasNoArrancaYDiceCual() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.security-protocol=SASL_SSL")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_KAFKA_SASL_JAAS_CONFIG"));
    }

    @Test
    void unJaasConPlaintextNoArrancaParaNoDejarCreerQueEstaAutenticado() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.sasl-jaas-config=" + JAAS)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("solo se usa con SASL_SSL"));
    }

    @Test
    void otrosProtocolosNoSeAdmiten() {
        for (String protocolo : new String[]{"SSL", "SASL_PLAINTEXT", "cualquiera"}) {
            runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.security-protocol=" + protocolo)
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .hasStackTraceContaining("CATEDRA_KAFKA_SECURITY_PROTOCOL solo admite"));
        }
    }

    @Test
    void elJaasNoApareceEnElToStringNiEnElMensajeDeUnaFalla() {
        var propiedades = new CatedraKafkaProperties("kafka.test.invalid:9092", "topic", "grupo", "SASL_SSL", JAAS,
                null, null);
        assertThat(propiedades.toString()).doesNotContain("secreto-del-jaas").doesNotContain("usuario-jaas")
                .contains("saslJaasConfig=***");
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.kafka.sasl-jaas-config=" + JAAS)
                .run(context -> assertThat(context.getStartupFailure().toString())
                        .doesNotContain("secreto-del-jaas").doesNotContain("usuario-jaas"));
    }

    /** El contenedor necesita un {@code SyncCatalog}; acá no se ejecuta, así que alcanza un doble sin dependencias. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class SyncCatalogDeRelleno {

        @org.springframework.context.annotation.Bean
        ar.edu.um.atlas.application.sync.SyncCatalog syncCatalog() {
            return org.mockito.Mockito.mock(ar.edu.um.atlas.application.sync.SyncCatalog.class);
        }
    }
}
