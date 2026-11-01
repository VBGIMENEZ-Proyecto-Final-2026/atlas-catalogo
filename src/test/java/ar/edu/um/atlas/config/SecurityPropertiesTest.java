package ar.edu.um.atlas.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validación de las propiedades de seguridad al arrancar: sin clave la aplicación no debe levantar. */
class SecurityPropertiesTest {

    private static SecurityProperties.Jwt jwt(String pem, String ubicacion) {
        return new SecurityProperties.Jwt(pem, ubicacion, "auth", Duration.ofSeconds(60));
    }

    private static SecurityProperties.Cors cors(String... origenes) {
        return new SecurityProperties.Cors(List.of(origenes), Duration.ofHours(1));
    }

    @Test
    void sinClavePublicaNoArranca() {
        assertThatThrownBy(() -> jwt(null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ATLAS_JWT_PUBLIC_KEY_LOCATION").hasMessageContaining("ATLAS_JWT_PUBLIC_KEY");
        assertThatThrownBy(() -> jwt("  ", "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Falta");
    }

    @Test
    void conLasDosFormasDeDarLaClaveNoArranca() {
        assertThatThrownBy(() -> jwt("-----BEGIN PUBLIC KEY-----", "/ruta/clave.pem"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("solo una");
    }

    @Test
    void conUnaSolaFormaEsValido() {
        assertThat(jwt("-----BEGIN PUBLIC KEY-----", null).publicKey()).isNotNull();
        assertThat(jwt(null, "/ruta/clave.pem").publicKeyLocation()).isEqualTo("/ruta/clave.pem");
    }

    @Test
    void elClaimDeAutoridadesNoPuedeEstarVacio() {
        assertThatThrownBy(() -> new SecurityProperties.Jwt(null, "/a", " ", Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laToleranciaDeRelojTieneUnTope() {
        assertThatThrownBy(() -> new SecurityProperties.Jwt(null, "/a", "auth", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityProperties.Jwt(null, "/a", "auth", Duration.ofMinutes(6)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SecurityProperties.Jwt(null, "/a", "auth", Duration.ofMinutes(5)).clockSkew())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(new SecurityProperties.Jwt(null, "/a", "auth", Duration.ZERO).clockSkew()).isZero();
    }

    @Test
    void sinOrigenesNoSeAceptaNinguno() {
        assertThat(cors().allowedOrigins()).isEmpty();
        assertThat(cors("", "  ").allowedOrigins()).isEmpty();
    }

    @Test
    void losOrigenesValidosSeConservanYSeRecortan() {
        assertThat(cors(" https://app.ejemplo.test ", "http://localhost:3000").allowedOrigins())
                .containsExactly("https://app.ejemplo.test", "http://localhost:3000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.ejemplo.test", "https://*", "app.ejemplo.test", "https://app.ejemplo.test/ruta",
            "https://app.ejemplo.test/", "ftp://app.ejemplo.test", "https://usuario@app.ejemplo.test", "null",
            "https://app .ejemplo.test"})
    void losOrigenesConComodinesSinEsquemaOConRutaSeRechazan(String origen) {
        assertThatThrownBy(() -> cors(origen)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ATLAS_CORS_ALLOWED_ORIGINS");
    }

    @Test
    void laEdadMaximaDeCorsNoPuedeSerNegativa() {
        assertThatThrownBy(() -> new SecurityProperties.Cors(List.of(), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elToStringNoMuestraLaClave() {
        String pem = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA-contenido-largo\n-----END PUBLIC KEY-----";
        assertThat(jwt(pem, null).toString()).doesNotContain("MIIBIjAN").doesNotContain("contenido-largo");
    }
}
