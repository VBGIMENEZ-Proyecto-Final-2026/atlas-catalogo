package ar.edu.um.atlas.config;

import ar.edu.um.atlas.seguridad.TokensDePrueba;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Carga de la clave pública con la que se verifican los JWT, y sus errores de arranque. */
class JwtPublicKeysTest {

    private static SecurityProperties.Jwt jwt(String pem, String ubicacion) {
        return new SecurityProperties.Jwt(pem, ubicacion, "auth", Duration.ofSeconds(60));
    }

    @Test
    void cargaLaClaveDesdeUnArchivoSinEsquema() {
        Path archivo = TokensDePrueba.escribirPem(TokensDePrueba.CRONOS);
        RSAPublicKey clave = JwtPublicKeys.load(jwt(null, archivo.toString()));
        assertThat(clave.getEncoded()).isEqualTo(TokensDePrueba.CRONOS.getPublic().getEncoded());
    }

    @Test
    void cargaLaClaveDesdeUnArchivoConEsquemaFile() {
        Path archivo = TokensDePrueba.escribirPem(TokensDePrueba.CRONOS);
        assertThat(JwtPublicKeys.load(jwt(null, "file:" + archivo)).getEncoded())
                .isEqualTo(TokensDePrueba.CRONOS.getPublic().getEncoded());
    }

    @Test
    void cargaLaClaveDesdeElClasspath() {
        assertThat(JwtPublicKeys.load(jwt(null, "classpath:jwt-test-public.pem")).getModulus().bitLength())
                .isGreaterThanOrEqualTo(2048);
    }

    @Test
    void cargaLaClaveDesdeUnaVariableConLosSaltosDeLineaEscapados() {
        String enUnaLinea = TokensDePrueba.pem(TokensDePrueba.CRONOS).replace("\n", "\\n");
        assertThat(enUnaLinea).doesNotContain("\n");
        assertThat(JwtPublicKeys.load(jwt(enUnaLinea, null)).getEncoded())
                .isEqualTo(TokensDePrueba.CRONOS.getPublic().getEncoded());
    }

    @Test
    void unArchivoInexistenteFallaConSuRuta() {
        assertThatThrownBy(() -> JwtPublicKeys.load(jwt(null, "/no/existe/clave.pem")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/no/existe/clave.pem");
        assertThatThrownBy(() -> JwtPublicKeys.load(jwt(null, "classpath:no-existe.pem")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textoQueNoEsUnPemFallaSinRepetirElContenido() {
        assertThatThrownBy(() -> JwtPublicKeys.load(jwt("esto-no-es-una-clave-secreta-123", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PEM X.509 RSA válido").hasMessageNotContaining("secreta-123");
    }

    @Test
    void unaClavePrivadaNoSeAceptaComoClavePublica() {
        String privada = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(TokensDePrueba.CRONOS.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        assertThatThrownBy(() -> JwtPublicKeys.load(jwt(privada, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(privada.substring(30, 60));
    }

    @Test
    void unaClaveRsaDeMenosDe2048BitsSeRechaza() {
        KeyPair corta = TokensDePrueba.parDeClaves(1024);
        assertThatThrownBy(() -> JwtPublicKeys.load(jwt(TokensDePrueba.pem(corta), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2048");
    }

    @Test
    void unaClaveQueNoEsRsaSeRechaza() throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("EC");
        generador.initialize(256);
        KeyPair ec = generador.generateKeyPair();
        assertThatThrownBy(() -> JwtPublicKeys.load(jwt(TokensDePrueba.pem(ec), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unArchivoVacioFalla() throws IOException {
        Path vacio = Files.createTempFile("atlas-vacio-", ".pem");
        vacio.toFile().deleteOnExit();
        assertThatThrownBy(() -> JwtPublicKeys.load(jwt(null, vacio.toString())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
