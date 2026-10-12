package ar.edu.um.atlas.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Si falta una variable obligatoria de Kafka, la aplicación no arranca y el mensaje indica cuál. Con el
 * consumidor apagado no se exige ninguna y no se crea nada.
 */
class CatedraKafkaPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatedraKafkaConfig.class, SyncCatalogDeRelleno.class);

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

    /** El contenedor necesita un {@code SyncCatalog}; acá no se ejecuta, así que alcanza un doble sin dependencias. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class SyncCatalogDeRelleno {

        @org.springframework.context.annotation.Bean
        ar.edu.um.atlas.application.sync.SyncCatalog syncCatalog() {
            return org.mockito.Mockito.mock(ar.edu.um.atlas.application.sync.SyncCatalog.class);
        }
    }
}
