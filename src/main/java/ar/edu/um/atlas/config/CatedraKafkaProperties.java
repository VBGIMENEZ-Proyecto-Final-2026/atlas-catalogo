package ar.edu.um.atlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración del consumidor de Kafka de la cátedra. Los servidores, el topic y el consumer group son
 * obligatorios cuando el consumidor está encendido ({@code atlas.sync.kafka.enabled}): si falta alguno, la
 * aplicación no arranca y el mensaje dice cuál.
 */
@ConfigurationProperties("catedra.kafka")
public record CatedraKafkaProperties(
        String bootstrapServers,
        String catalogTopic,
        String consumerGroupId,
        @DefaultValue("10s") Duration startRetryInterval,
        @DefaultValue Retry retry) {

    public CatedraKafkaProperties {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta CATEDRA_KAFKA_BOOTSTRAP_SERVERS (servidores de Kafka de la cátedra)");
        }
        if (catalogTopic == null || catalogTopic.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_KAFKA_CATALOG_TOPIC (topic del catálogo)");
        }
        if (consumerGroupId == null || consumerGroupId.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_KAFKA_CONSUMER_GROUP_ID (consumer group)");
        }
    }

    /**
     * Reintentos de un mensaje cuya sincronización falló: {@code maxAttempts} intentos en total, con espera
     * creciente. Al agotarlos el mensaje se descarta (la verificación periódica reintenta sola).
     */
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration delay,
            @DefaultValue("2.0") double multiplier) {

        public Retry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("CATEDRA_KAFKA_MAX_ATTEMPTS debe ser al menos 1");
            }
        }
    }

    @Override
    public String toString() {
        return "CatedraKafkaProperties[bootstrapServers=" + bootstrapServers + ", catalogTopic=" + catalogTopic
                + ", consumerGroupId=" + consumerGroupId + ", startRetryInterval=" + startRetryInterval
                + ", retry=" + retry + "]";
    }
}
