package ar.edu.um.atlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Locale;

/**
 * Configuración del consumidor de Kafka de la cátedra. Los servidores, el topic y el consumer group son
 * obligatorios cuando el consumidor está encendido ({@code atlas.sync.kafka.enabled}): si falta alguno, la
 * aplicación no arranca y el mensaje dice cuál.
 *
 * <p>La seguridad es {@code PLAINTEXT} salvo que se active el perfil {@code kafka-sasl} (o se defina
 * {@code CATEDRA_KAFKA_SECURITY_PROTOCOL}), que pasa a {@code SASL_SSL} con mecanismo {@code PLAIN} y exige
 * {@code CATEDRA_KAFKA_SASL_JAAS_CONFIG}. No hay otros protocolos ni mecanismos hasta que la cátedra los pida.
 * El JAAS lleva usuario y contraseña: no aparece en {@code toString}.
 */
@ConfigurationProperties("catedra.kafka")
public record CatedraKafkaProperties(
        String bootstrapServers,
        String catalogTopic,
        String consumerGroupId,
        @DefaultValue(PLAINTEXT) String securityProtocol,
        String saslJaasConfig,
        @DefaultValue("10s") Duration startRetryInterval,
        @DefaultValue Retry retry) {

    public static final String PLAINTEXT = "PLAINTEXT";
    public static final String SASL_SSL = "SASL_SSL";

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
        securityProtocol = securityProtocol == null ? PLAINTEXT : securityProtocol.strip().toUpperCase(Locale.ROOT);
        if (!securityProtocol.equals(PLAINTEXT) && !securityProtocol.equals(SASL_SSL)) {
            throw new IllegalArgumentException("CATEDRA_KAFKA_SECURITY_PROTOCOL solo admite " + PLAINTEXT + " o "
                    + SASL_SSL);
        }
        boolean hayJaas = saslJaasConfig != null && !saslJaasConfig.isBlank();
        if (securityProtocol.equals(SASL_SSL) && !hayJaas) {
            throw new IllegalArgumentException(
                    "Falta CATEDRA_KAFKA_SASL_JAAS_CONFIG (obligatoria con " + SASL_SSL + ")");
        }
        if (securityProtocol.equals(PLAINTEXT) && hayJaas) {
            // Credenciales que nadie usaría: mejor avisar que dejar creer que la conexión está autenticada.
            throw new IllegalArgumentException(
                    "CATEDRA_KAFKA_SASL_JAAS_CONFIG solo se usa con " + SASL_SSL + " (perfil kafka-sasl)");
        }
        saslJaasConfig = hayJaas ? saslJaasConfig.strip() : null;
    }

    public boolean usaSasl() {
        return securityProtocol.equals(SASL_SSL);
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
                + ", consumerGroupId=" + consumerGroupId + ", securityProtocol=" + securityProtocol
                + ", saslJaasConfig=" + (saslJaasConfig == null ? "null" : "***") + ", startRetryInterval=" + startRetryInterval
                + ", retry=" + retry + "]";
    }
}
