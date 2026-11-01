package ar.edu.um.atlas.seguridad;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Claves y tokens para probar la seguridad con firmas de verdad, incluidos los tokens que un atacante armaría.
 * El par {@link #CRONOS} hace de {@code cronos-turnos}: atlas solo conoce su clave pública. {@link #OTRO} es un par
 * ajeno que atlas no debe aceptar.
 */
public final class TokensDePrueba {

    public static final KeyPair CRONOS = parDeClaves(2048);
    public static final KeyPair OTRO = parDeClaves(2048);

    private TokensDePrueba() {
    }

    public static KeyPair parDeClaves(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** La clave pública en PEM X.509, como la entregaría {@code cronos-turnos}. */
    public static String pem(KeyPair par) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(par.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    public static Path escribirPem(KeyPair par) {
        try {
            Path archivo = Files.createTempFile("atlas-jwt-publica-", ".pem");
            archivo.toFile().deleteOnExit();
            return Files.writeString(archivo, pem(par));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Claims válidos: usuario, vigencia de una hora y las autoridades en el claim {@code auth} (texto con espacios, como JHipster). */
    public static JWTClaimsSet.Builder claims(String usuario, String... autoridades) {
        Instant ahora = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(usuario)
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(3600)));
        if (autoridades.length > 0) {
            claims.claim("auth", String.join(" ", autoridades));
        }
        return claims;
    }

    /**
     * Claims de un token que venció hace {@code segundos}. Emitido una hora antes de vencer: Spring rechaza un token
     * cuyo {@code exp} sea anterior a su {@code iat}, y eso taparía el motivo que se quiere probar.
     */
    public static JWTClaimsSet.Builder vencidoHace(long segundos, String usuario, String... autoridades) {
        Instant vencimiento = Instant.now().minusSeconds(segundos);
        return claims(usuario, autoridades)
                .issueTime(Date.from(vencimiento.minusSeconds(3600)))
                .expirationTime(Date.from(vencimiento));
    }

    /** Un token válido de {@code cronos-turnos}. */
    public static String valido(String usuario, String... autoridades) {
        return firmar(CRONOS, claims(usuario, autoridades).build());
    }

    /** Un token con las autoridades como lista JSON en lugar de texto. */
    public static String conAutoridadesEnLista(String usuario, String... autoridades) {
        return firmar(CRONOS, claims(usuario).claim("auth", List.of(autoridades)).build());
    }

    /** Firma con RS256 usando la clave privada del par dado. */
    public static String firmar(KeyPair par, JWTClaimsSet claims) {
        return firmar(par, JWSAlgorithm.RS256, claims);
    }

    public static String firmar(KeyPair par, JWSAlgorithm algoritmo, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(algoritmo), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) par.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Ataque de confusión de algoritmo: un HS256 firmado usando como secreto la clave pública de atlas (que no es
     * secreta). Si el decodificador aceptara HS256, cualquiera podría forjar tokens.
     */
    public static String hs256ConLaClavePublicaComoSecreto(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(CRONOS.getPublic().getEncoded()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Un token sin firma ({@code alg: none}). */
    public static String sinFirma(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    /** Cambia el payload de un token ya firmado sin volver a firmarlo. */
    public static String conPayloadAdulterado(String token, JWTClaimsSet nuevoPayload) {
        String[] partes = token.split("\\.");
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(nuevoPayload.toString().getBytes(StandardCharsets.UTF_8));
        return partes[0] + "." + payload + "." + partes[2];
    }
}
