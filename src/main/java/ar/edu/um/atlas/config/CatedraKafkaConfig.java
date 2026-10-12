package ar.edu.um.atlas.config;

import ar.edu.um.atlas.adapters.in.kafka.CatalogUpdatedListener;
import ar.edu.um.atlas.adapters.in.kafka.ResilientConsumerLifecycle;
import ar.edu.um.atlas.application.sync.SyncCatalog;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Arma el consumidor del aviso {@code CatalogUpdated}: un contenedor de un solo hilo (el topic tiene una
 * partición) con confirmación manual, un mensaje por vez y reintentos acotados. No usa {@code spring.kafka.*}:
 * las propiedades son las propias de {@link CatedraKafkaProperties}. Solo existe si
 * {@code atlas.sync.kafka.enabled} es verdadero (por defecto sí); los tests de contexto lo apagan.
 *
 * <p>La aplicación arranca aunque Kafka no responda: si los servidores existen pero no contestan, el cliente
 * se conecta en segundo plano; si ni siquiera se resuelven, {@link ResilientConsumerLifecycle} reintenta
 * arrancar el contenedor. El consumo se reanuda solo cuando Kafka vuelve.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "atlas.sync.kafka.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CatedraKafkaProperties.class)
class CatedraKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(CatedraKafkaConfig.class);

    /** Una corrida puede durar hasta un snapshot completo (peor caso, unos 91 s): margen amplio. */
    private static final Duration MAX_POLL_INTERVAL = Duration.ofMinutes(10);

    @Bean
    ConsumerFactory<String, String> catalogConsumerFactory(CatedraKafkaProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers().strip());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupId().strip());
        // El valor se lee como texto y se interpreta en el adaptador; ErrorHandlingDeserializer evita que un
        // mensaje que no se pueda deserializar trabe el consumo.
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Un grupo sin offset confirmado empieza por lo nuevo: el arranque ya sincroniza la copia.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, (int) MAX_POLL_INTERVAL.toMillis());
        // Nunca crear el topic de la cátedra desde un consumidor.
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    KafkaMessageListenerContainer<String, String> catalogUpdatedContainer(
            ConsumerFactory<String, String> catalogConsumerFactory, CatedraKafkaProperties properties,
            SyncCatalog syncCatalog) {
        ContainerProperties container = new ContainerProperties(properties.catalogTopic().strip());
        // MANUAL_IMMEDIATE: el offset se confirma en el momento del acknowledge, después de persistir.
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        container.setMessageListener(new CatalogUpdatedListener(syncCatalog));

        var listener = new KafkaMessageListenerContainer<>(catalogConsumerFactory, container);
        listener.setCommonErrorHandler(errorHandler(properties.retry()));
        // Lo arranca ResilientConsumerLifecycle, para que una falla al construir el consumidor no tumbe la app.
        listener.setAutoStartup(false);
        return listener;
    }

    @Bean
    ResilientConsumerLifecycle catalogUpdatedConsumerLifecycle(
            KafkaMessageListenerContainer<String, String> catalogUpdatedContainer,
            CatedraKafkaProperties properties) {
        return new ResilientConsumerLifecycle(catalogUpdatedContainer, properties.startRetryInterval());
    }

    /**
     * Si la corrida falla, reintenta con espera creciente y, al agotar los intentos, descarta el mensaje y
     * confirma su offset: el error ya quedó en {@code sync_state} y la verificación periódica lo reintenta,
     * así que no se pierde nada y el consumidor no se queda trabado.
     */
    private static DefaultErrorHandler errorHandler(CatedraKafkaProperties.Retry retry) {
        var backOff = new ExponentialBackOffWithMaxRetries(retry.maxAttempts() - 1);
        backOff.setInitialInterval(retry.delay().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(Math.max(retry.delay().toMillis(), 30_000));
        var handler = new DefaultErrorHandler((record, exception) ->
                // Sin el valor del mensaje: solo de dónde vino y la clase de la falla.
                log.error("Se agotaron los intentos con el mensaje {}-{}@{} ({}): se descarta y la verificación "
                                + "periódica reintenta", record.topic(), record.partition(), record.offset(),
                        exception.getClass().getSimpleName()), backOff);
        handler.setCommitRecovered(true);
        return handler;
    }
}
