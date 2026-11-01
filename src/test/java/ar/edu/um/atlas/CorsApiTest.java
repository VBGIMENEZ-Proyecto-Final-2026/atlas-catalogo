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

/** CORS con orígenes configurados: solo esos orígenes, solo lectura y sin credenciales. */
@SpringBootTest(properties = "atlas.security.cors.allowed-origins=https://app.ejemplo.test, http://localhost:3000")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsApiTest {

    private static final String PERMITIDO = "https://app.ejemplo.test";
    private static final String AJENO = "https://atacante.ejemplo.test";

    @DynamicPropertySource
    static void clavePublicaDeCronos(DynamicPropertyRegistry registry) {
        registry.add("atlas.security.jwt.public-key-location",
                () -> "file:" + TokensDePrueba.escribirPem(TokensDePrueba.CRONOS));
    }

    @Autowired MockMvc mockMvc;

    @Test
    void laConsultaPreviaDeUnOrigenPermitidoSeAceptaSinToken() throws Exception {
        mockMvc.perform(options("/api/professionals").header(HttpHeaders.ORIGIN, PERMITIDO)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PERMITIDO))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,OPTIONS"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void tambienSeAceptaElSegundoOrigenConfigurado() throws Exception {
        mockMvc.perform(options("/api/categories").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
    }

    @Test
    void laConsultaPreviaDeUnOrigenAjenoSeRechaza() throws Exception {
        mockMvc.perform(options("/api/professionals").header(HttpHeaders.ORIGIN, AJENO)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void unMetodoDeEscrituraNoSeAceptaNiParaUnOrigenPermitido() throws Exception {
        for (String metodo : new String[]{"POST", "PUT", "DELETE", "PATCH"}) {
            mockMvc.perform(options("/api/professionals").header(HttpHeaders.ORIGIN, PERMITIDO)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, metodo))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void unEncabezadoQueLaApiNoUsaNoSeAcepta() throws Exception {
        mockMvc.perform(options("/api/professionals").header(HttpHeaders.ORIGIN, PERMITIDO)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-user-id"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unaConsultaRealDeUnOrigenPermitidoConTokenTraeElEncabezadoCors() throws Exception {
        mockMvc.perform(get("/api/categories").header(HttpHeaders.ORIGIN, PERMITIDO)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido("ana", "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PERMITIDO));
    }

    @Test
    void unaConsultaRealDeUnOrigenAjenoSeRechazaAunConTokenValido() throws Exception {
        mockMvc.perform(get("/api/categories").header(HttpHeaders.ORIGIN, AJENO)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido("ana", "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unOrigenPermitidoSinTokenSigueSiendo401() throws Exception {
        mockMvc.perform(get("/api/categories").header(HttpHeaders.ORIGIN, PERMITIDO))
                .andExpect(status().isUnauthorized());
    }
}
