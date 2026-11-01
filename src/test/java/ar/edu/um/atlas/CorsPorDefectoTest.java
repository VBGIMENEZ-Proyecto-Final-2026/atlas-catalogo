package ar.edu.um.atlas;

import ar.edu.um.atlas.seguridad.TokensDePrueba;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sin orígenes configurados no se acepta ninguno; los clientes que no son navegadores no se ven afectados. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsPorDefectoTest {

    @DynamicPropertySource
    static void clavePublicaDeCronos(DynamicPropertyRegistry registry) {
        registry.add("atlas.security.jwt.public-key-location",
                () -> "file:" + TokensDePrueba.escribirPem(TokensDePrueba.CRONOS));
    }

    @Autowired MockMvc mockMvc;

    @Test
    void ningunOrigenRecibeLosEncabezadosCors() throws Exception {
        mockMvc.perform(options("/api/professionals").header(HttpHeaders.ORIGIN, "https://app.ejemplo.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                // Sin ninguna regla, el filtro CORS de Spring corta la consulta previa con un 200 vacío: no la
                // reenvía a la cadena. Lo que bloquea al navegador es que no traiga ningún Access-Control-*.
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
    }

    @Test
    void unClienteSinEncabezadoOriginFuncionaConNormalidad() throws Exception {
        mockMvc.perform(get("/api/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido("ana", "ROLE_USER")))
                .andExpect(status().isOk());
    }
}
