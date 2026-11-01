package ar.edu.um.atlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Seguridad de la API: cómo se validan los JWT que emite {@code cronos-turnos} y qué orígenes CORS se aceptan.
 * atlas solo tiene la clave pública: no puede firmar tokens ni guarda usuarios (ADR de autenticación entre
 * servicios, opción A).
 */
@ConfigurationProperties("atlas.security")
public record SecurityProperties(@DefaultValue Jwt jwt, @DefaultValue Cors cors) {

    /**
     * @param publicKey         clave pública RSA en PEM X.509 ({@code BEGIN PUBLIC KEY}), por ejemplo desde un secreto
     *                          de Docker; las secuencias {@code \n} literales se toman como saltos de línea
     * @param publicKeyLocation ruta o URL ({@code file:}, {@code classpath:}) de un archivo con esa clave; sin esquema
     *                          se toma como ruta del sistema de archivos
     * @param authoritiesClaim  claim con las autoridades; {@code auth} es el de JHipster
     * @param clockSkew         tolerancia de reloj al validar {@code exp} y {@code nbf}
     */
    public record Jwt(
            String publicKey,
            String publicKeyLocation,
            @DefaultValue("auth") String authoritiesClaim,
            @DefaultValue("60s") Duration clockSkew) {

        public Jwt {
            publicKey = blankToNull(publicKey);
            publicKeyLocation = blankToNull(publicKeyLocation);
            if (publicKey == null && publicKeyLocation == null) {
                throw new IllegalArgumentException("Falta ATLAS_JWT_PUBLIC_KEY_LOCATION (archivo) o ATLAS_JWT_PUBLIC_KEY "
                        + "(PEM) con la clave pública con la que cronos-turnos firma los JWT");
            }
            if (publicKey != null && publicKeyLocation != null) {
                throw new IllegalArgumentException(
                        "Definí solo una: ATLAS_JWT_PUBLIC_KEY_LOCATION o ATLAS_JWT_PUBLIC_KEY, no las dos");
            }
            if (authoritiesClaim == null || authoritiesClaim.isBlank()) {
                throw new IllegalArgumentException("atlas.security.jwt.authorities-claim no puede estar vacío");
            }
            if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("ATLAS_JWT_CLOCK_SKEW debe estar entre 0 y 5 minutos");
            }
        }

        @Override
        public String toString() {
            return "Jwt[publicKey=" + (publicKey == null ? "-" : "(definida)") + ", publicKeyLocation="
                    + publicKeyLocation + ", authoritiesClaim=" + authoritiesClaim + ", clockSkew=" + clockSkew + "]";
        }
    }

    /**
     * @param allowedOrigins orígenes permitidos, cada uno con esquema y sin comodines (por ejemplo
     *                       {@code https://app.ejemplo.test}); vacío significa que no se acepta ningún origen
     * @param maxAge         cuánto puede recordar el navegador la respuesta a una consulta previa
     */
    public record Cors(@DefaultValue List<String> allowedOrigins, @DefaultValue("1h") Duration maxAge) {

        private static final Pattern ORIGIN = Pattern.compile("^https?://[^\\s*/?#@]+$");

        public Cors {
            allowedOrigins = allowedOrigins.stream().filter(o -> o != null && !o.isBlank()).map(String::strip).toList();
            for (String origin : allowedOrigins) {
                if (!ORIGIN.matcher(origin).matches()) {
                    throw new IllegalArgumentException("ATLAS_CORS_ALLOWED_ORIGINS: cada origen debe ser "
                            + "esquema://host[:puerto], sin comodines ni rutas");
                }
            }
            if (maxAge.isNegative()) {
                throw new IllegalArgumentException("atlas.security.cors.max-age no puede ser negativo");
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
