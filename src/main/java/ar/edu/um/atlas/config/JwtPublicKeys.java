package ar.edu.um.atlas.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;

/**
 * Carga la clave pública con la que se verifican los JWT. Falla al arrancar con un mensaje claro si no se puede
 * leer o no es una clave RSA X.509 de al menos 2048 bits. Los mensajes nunca repiten el contenido de la clave.
 */
final class JwtPublicKeys {

    static final int MIN_BITS = 2048;

    private JwtPublicKeys() {
    }

    static RSAPublicKey load(SecurityProperties.Jwt jwt) {
        String pem = jwt.publicKey() != null ? jwt.publicKey().replace("\\n", "\n") : read(jwt.publicKeyLocation());
        RSAPublicKey key;
        try {
            key = RsaKeyConverters.x509().convert(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("La clave pública JWT no es un PEM X.509 RSA válido "
                    + "(debe empezar con -----BEGIN PUBLIC KEY-----)", e);
        }
        if (key == null || key.getModulus().bitLength() < MIN_BITS) {
            throw new IllegalArgumentException("La clave pública JWT debe ser RSA de al menos " + MIN_BITS + " bits");
        }
        return key;
    }

    private static String read(String location) {
        try {
            Resource resource = resource(location);
            if (!resource.exists()) {
                throw new IllegalArgumentException("No existe el archivo de la clave pública JWT: " + location);
            }
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo leer la clave pública JWT: " + location, e);
        }
    }

    private static Resource resource(String location) throws MalformedURLException {
        if (location.startsWith("classpath:")) {
            return new ClassPathResource(location.substring("classpath:".length()));
        }
        if (location.startsWith("file:")) {
            return new UrlResource(location);
        }
        return new FileSystemResource(location);
    }
}
