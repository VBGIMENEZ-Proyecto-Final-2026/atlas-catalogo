package ar.edu.um.atlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Configuración del cliente REST de la cátedra. {@code CATEDRA_REST_BASE_URL} y {@code CATEDRA_ID_TOKEN}
 * son obligatorias: si falta alguna, la aplicación no arranca y el mensaje dice cuál. El token nunca
 * aparece en {@code toString}.
 */
@ConfigurationProperties("catedra.rest")
public record CatedraProperties(
        String baseUrl,
        String idToken,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue Retry retry) {

    public CatedraProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_REST_BASE_URL (URL base de la API de la cátedra)");
        }
        URI uri = URI.create(baseUrl.strip());
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("CATEDRA_REST_BASE_URL debe ser una URL http o https absoluta");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_ID_TOKEN (JWT técnico de la cátedra)");
        }
    }

    /** Reintentos ante fallas transitorias: {@code maxRetries} reintentos tras el primer intento. */
    public record Retry(
            @DefaultValue("2") long maxRetries,
            @DefaultValue("500ms") Duration delay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("5s") Duration maxDelay) {
    }

    @Override
    public String toString() {
        return "CatedraProperties[baseUrl=" + baseUrl + ", idToken=***, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", retry=" + retry + "]";
    }
}
