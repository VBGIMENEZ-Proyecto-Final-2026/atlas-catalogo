package ar.edu.um.atlas.config;

import ar.edu.um.atlas.adapters.in.security.SecurityProblemHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * atlas es un servidor de recursos: valida los JWT que emite {@code cronos-turnos} con su clave pública (RS256) y
 * no guarda usuarios ni puede firmar tokens (ADR de autenticación entre servicios, opción A). Toda la validación
 * vive acá y en {@code adapters.in.security}; el dominio y los casos de uso no saben que hay seguridad.
 *
 * <ul>
 *   <li>Solo {@code GET /actuator/health} es público.</li>
 *   <li>La búsqueda ({@code /api/professionals}, {@code /api/categories}) pide {@code ROLE_USER} o
 *       {@code ROLE_ADMIN}.</li>
 *   <li>El estado de la sincronización ({@code /api/sync/status}) pide {@code ROLE_ADMIN}: muestra errores internos.</li>
 *   <li>Todo lo demás se deniega, exista o no la ruta.</li>
 * </ul>
 *
 * <p>La API no usa cookies ni sesión: el token viaja en {@code Authorization: Bearer}, por lo que CSRF no aplica y
 * se desactiva. Nada de lo que decide una autorización viene de un identificador enviado por el cliente: sale del
 * token firmado.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

    static final String ROLE_USER = "ROLE_USER";
    static final String ROLE_ADMIN = "ROLE_ADMIN";

    @Bean
    SecurityProblemHandler securityProblemHandler(JsonMapper mapper) {
        return new SecurityProblemHandler(mapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties,
                                            SecurityProblemHandler problems) {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/professionals", "/api/professionals/*",
                                "/api/categories").hasAnyAuthority(ROLE_USER, ROLE_ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/sync/status").hasAuthority(ROLE_ADMIN)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter(properties))));
        return http.build();
    }

    /**
     * Firma RS256 con la clave pública (un token firmado con otro algoritmo, como HS256 o {@code none}, se
     * rechaza), vigencia con la tolerancia configurada y {@code sub} y {@code exp} obligatorios: un token sin
     * vencimiento sería válido para siempre.
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        RSAPublicKey key = JwtPublicKeys.load(properties.jwt());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(properties.jwt().clockSkew()), requiredClaims()));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> requiredClaims() {
        return jwt -> {
            if (jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getExpiresAt() == null) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Faltan los claims sub o exp", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    /** Las autoridades salen del claim configurado (por defecto {@code auth}) tal cual, sin prefijo: ya traen {@code ROLE_}. */
    private static JwtAuthenticationConverter authenticationConverter(SecurityProperties properties) {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(properties.jwt().authoritiesClaim());
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Sin orígenes configurados no se acepta ninguno (el navegador bloquea las consultas de otros orígenes). Con
     * orígenes, solo {@code GET} y {@code OPTIONS} y los encabezados que la API usa, sin credenciales.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        SecurityProperties.Cors cors = properties.cors();
        if (!cors.allowedOrigins().isEmpty()) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(cors.allowedOrigins());
            configuration.setAllowedMethods(List.of("GET", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
            configuration.setAllowCredentials(false);
            configuration.setMaxAge(cors.maxAge());
            source.registerCorsConfiguration("/**", configuration);
        }
        return source;
    }
}
