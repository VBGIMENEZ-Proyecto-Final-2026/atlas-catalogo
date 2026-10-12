package ar.edu.um.atlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración del cliente Redis de la cátedra. Host, puerto, usuario y contraseña son obligatorios: si
 * falta alguno, la aplicación no arranca y el mensaje dice cuál. La contraseña nunca aparece en
 * {@code toString}.
 */
@ConfigurationProperties("catedra.redis")
public record CatedraRedisProperties(
        String host,
        Integer port,
        String username,
        String password,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration commandTimeout,
        @DefaultValue Retry retry) {

    public CatedraRedisProperties {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_REDIS_HOST (host de Redis de la cátedra)");
        }
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Falta CATEDRA_REDIS_PORT o no es un puerto válido (1 a 65535)");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_REDIS_USERNAME (usuario de Redis de la cátedra)");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_REDIS_PASSWORD (contraseña de Redis de la cátedra)");
        }
    }

    /** Reintentos ante fallas transitorias: {@code maxRetries} reintentos tras el primer intento. */
    public record Retry(
            @DefaultValue("2") long maxRetries,
            @DefaultValue("200ms") Duration delay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("2s") Duration maxDelay) {
    }

    @Override
    public String toString() {
        return "CatedraRedisProperties[host=" + host + ", port=" + port + ", username=" + username
                + ", password=***, connectTimeout=" + connectTimeout + ", commandTimeout=" + commandTimeout
                + ", retry=" + retry + "]";
    }
}
