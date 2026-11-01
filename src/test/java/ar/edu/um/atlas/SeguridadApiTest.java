package ar.edu.um.atlas;

import ar.edu.um.atlas.seguridad.TokensDePrueba;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La seguridad de la API con la aplicación completa y firmas RS256 reales: qué se rechaza sin credenciales o con
 * un token inválido (401), qué se rechaza por falta de permisos (403) y qué se acepta. Cubre el enunciado 9
 * ("rechazo de accesos no autenticados o no autorizados").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SeguridadApiTest {

    private static final String[] RUTAS_PROTEGIDAS = {
            "/api/professionals", "/api/professionals/1", "/api/categories", "/api/sync/status"};

    @DynamicPropertySource
    static void clavePublicaDeCronos(DynamicPropertyRegistry registry) {
        registry.add("atlas.security.jwt.public-key-location",
                () -> "file:" + TokensDePrueba.escribirPem(TokensDePrueba.CRONOS));
    }

    @Autowired MockMvc mockMvc;

    private MvcResult pedir(String ruta, String token) throws Exception {
        return mockMvc.perform(conToken(get(ruta), token)).andReturn();
    }

    private static MockHttpServletRequestBuilder conToken(MockHttpServletRequestBuilder pedido, String token) {
        return token == null ? pedido : pedido.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private int estado(String ruta, String token) throws Exception {
        return pedir(ruta, token).getResponse().getStatus();
    }

    private static String vencidoHace(long segundos) {
        return TokensDePrueba.firmar(TokensDePrueba.CRONOS, TokensDePrueba.vencidoHace(segundos, "ana", "ROLE_USER").build());
    }

    // --- sin credenciales: 401 ---

    @ParameterizedTest
    @ValueSource(strings = {"/api/professionals", "/api/professionals/1", "/api/categories", "/api/sync/status"})
    void sinTokenTodaRutaProtegidaDevuelve401ConProblemJson(String ruta) throws Exception {
        mockMvc.perform(get(ruta))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"))
                .andExpect(jsonPath("$.path").value(ruta));
    }

    @Test
    void soloElHealthEsPublico() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        assertThat(estado("/actuator/info", null)).isEqualTo(401);
        assertThat(estado("/actuator/env", null)).isEqualTo(401);
        assertThat(estado("/api/lo-que-sea", null)).isEqualTo(401);
        assertThat(estado("/", null)).isEqualTo(401);
    }

    @Test
    void sinTokenUnaEscrituraTambienEs401() throws Exception {
        mockMvc.perform(post("/api/professionals")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/professionals/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/professionals/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void unIdentificadorEnviadoPorElClienteNoSustituyeAlToken() throws Exception {
        for (String ruta : RUTAS_PROTEGIDAS) {
            mockMvc.perform(get(ruta).header("X-User", "admin").header("X-User-Id", "1")
                            .header("X-Forwarded-User", "admin").header("X-On-Behalf-Of", "admin")
                            .param("userId", "1").param("user", "admin"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // --- tokens inválidos: 401 ---

    @Test
    void unTokenValidoDeUnUsuarioPasa() throws Exception {
        for (String ruta : new String[]{"/api/professionals", "/api/categories"}) {
            assertThat(estado(ruta, TokensDePrueba.valido("ana", "ROLE_USER"))).as(ruta).isEqualTo(200);
        }
        assertThat(estado("/api/professionals/99999", TokensDePrueba.valido("ana", "ROLE_USER"))).isEqualTo(404);
    }

    @Test
    void unTokenVencidoSeRechazaPeroConLaToleranciaDeRelojDeUnMinuto() throws Exception {
        assertThat(estado("/api/categories", vencidoHace(3600))).as("vencido hace una hora").isEqualTo(401);
        assertThat(estado("/api/categories", vencidoHace(120))).as("vencido hace dos minutos").isEqualTo(401);
        assertThat(estado("/api/categories", vencidoHace(10))).as("vencido hace 10 s, dentro de la tolerancia").isEqualTo(200);
    }

    @Test
    void unTokenQueTodaviaNoEsValidoSeRechaza() throws Exception {
        JWTClaimsSet futuro = TokensDePrueba.claims("ana", "ROLE_USER").notBeforeTime(Date.from(Instant.now().plusSeconds(3600)))
                .expirationTime(Date.from(Instant.now().plusSeconds(7200))).build();
        assertThat(estado("/api/categories", TokensDePrueba.firmar(TokensDePrueba.CRONOS, futuro))).isEqualTo(401);
    }

    @Test
    void unTokenFirmadoPorOtraClaveSeRechaza() throws Exception {
        String ajeno = TokensDePrueba.firmar(TokensDePrueba.OTRO, TokensDePrueba.claims("ana", "ROLE_USER", "ROLE_ADMIN").build());
        for (String ruta : RUTAS_PROTEGIDAS) {
            assertThat(estado(ruta, ajeno)).as(ruta).isEqualTo(401);
        }
    }

    @Test
    void unTokenConElPayloadAdulteradoSeRechaza() throws Exception {
        String original = TokensDePrueba.valido("ana", "ROLE_USER");
        String adulterado = TokensDePrueba.conPayloadAdulterado(original,
                TokensDePrueba.claims("ana", "ROLE_USER", "ROLE_ADMIN").build());
        assertThat(estado("/api/sync/status", adulterado)).isEqualTo(401);
    }

    @Test
    void elAtaqueDeConfusionDeAlgoritmoHs256SeRechaza() throws Exception {
        String forjado = TokensDePrueba.hs256ConLaClavePublicaComoSecreto(
                TokensDePrueba.claims("atacante", "ROLE_USER", "ROLE_ADMIN").build());
        assertThat(estado("/api/professionals", forjado)).isEqualTo(401);
        assertThat(estado("/api/sync/status", forjado)).isEqualTo(401);
    }

    @Test
    void soloSeAceptaRs256() throws Exception {
        String rs512 = TokensDePrueba.firmar(TokensDePrueba.CRONOS, JWSAlgorithm.RS512,
                TokensDePrueba.claims("ana", "ROLE_USER").build());
        assertThat(estado("/api/categories", rs512)).isEqualTo(401);
    }

    @Test
    void unTokenSinFirmaAlgNoneSeRechaza() throws Exception {
        String sinFirma = TokensDePrueba.sinFirma(TokensDePrueba.claims("atacante", "ROLE_USER", "ROLE_ADMIN").build());
        assertThat(estado("/api/professionals", sinFirma)).isEqualTo(401);
    }

    @Test
    void unTokenSinVencimientoOSinUsuarioSeRechaza() throws Exception {
        JWTClaimsSet sinExp = new JWTClaimsSet.Builder().subject("ana").claim("auth", "ROLE_USER").build();
        JWTClaimsSet sinSub = new JWTClaimsSet.Builder().claim("auth", "ROLE_USER")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
        assertThat(estado("/api/categories", TokensDePrueba.firmar(TokensDePrueba.CRONOS, sinExp))).isEqualTo(401);
        assertThat(estado("/api/categories", TokensDePrueba.firmar(TokensDePrueba.CRONOS, sinSub))).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no.es.un.jwt", "basura", "a.b.c", "eyJhbGciOiJSUzI1NiJ9.e30."})
    void textoQueNoEsUnJwtSeRechaza(String texto) throws Exception {
        assertThat(estado("/api/categories", texto)).isEqualTo(401);
    }

    @Test
    void elTokenSoloSeAceptaEnElEncabezadoAuthorizationBearer() throws Exception {
        String token = TokensDePrueba.valido("ana", "ROLE_USER");
        mockMvc.perform(get("/api/categories").param("access_token", token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/categories").header(HttpHeaders.AUTHORIZATION, "Basic " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/categories").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/categories").header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void elRechazoNoDicePorQueSeRechazoElToken() throws Exception {
        String vencido = vencidoHace(3600);
        String cuerpo = pedir("/api/categories", vencido).getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContainIgnoringCase("expired").doesNotContainIgnoringCase("jwt")
                .doesNotContainIgnoringCase("signature").doesNotContain(vencido);
    }

    // --- autorización: 403 ---

    @Test
    void unTokenValidoSinElRolNecesarioEs403ConProblemJson() throws Exception {
        mockMvc.perform(get("/api/professionals").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TokensDePrueba.valido("ana", "ROLE_GUEST")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"))
                .andExpect(jsonPath("$.path").value("/api/professionals"));
    }

    @Test
    void unTokenSinAutoridadesEs403() throws Exception {
        String sinRoles = TokensDePrueba.valido("ana");
        for (String ruta : RUTAS_PROTEGIDAS) {
            assertThat(estado(ruta, sinRoles)).as(ruta).isEqualTo(403);
        }
    }

    @Test
    void elEstadoDeLaSincronizacionExigeAdministrador() throws Exception {
        assertThat(estado("/api/sync/status", TokensDePrueba.valido("ana", "ROLE_USER"))).isEqualTo(403);
        mockMvc.perform(get("/api/sync/status").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TokensDePrueba.valido("root", "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"));
    }

    @Test
    void unAdministradorTambienPuedeBuscar() throws Exception {
        assertThat(estado("/api/professionals", TokensDePrueba.valido("root", "ROLE_ADMIN"))).isEqualTo(200);
        assertThat(estado("/api/professionals", TokensDePrueba.valido("root", "ROLE_USER", "ROLE_ADMIN"))).isEqualTo(200);
    }

    @Test
    void lasAutoridadesPuedenVenirComoTextoOComoLista() throws Exception {
        assertThat(estado("/api/categories", TokensDePrueba.valido("ana", "ROLE_USER"))).isEqualTo(200);
        assertThat(estado("/api/categories", TokensDePrueba.conAutoridadesEnLista("ana", "ROLE_USER"))).isEqualTo(200);
        assertThat(estado("/api/sync/status", TokensDePrueba.conAutoridadesEnLista("ana", "ROLE_USER"))).isEqualTo(403);
        assertThat(estado("/api/sync/status", TokensDePrueba.conAutoridadesEnLista("root", "ROLE_USER", "ROLE_ADMIN"))).isEqualTo(200);
    }

    @Test
    void unRolEnOtroClaimNoCuenta() throws Exception {
        String enOtroClaim = TokensDePrueba.firmar(TokensDePrueba.CRONOS,
                TokensDePrueba.claims("ana").claim("roles", "ROLE_ADMIN").claim("scope", "ROLE_ADMIN").build());
        assertThat(estado("/api/sync/status", enOtroClaim)).isEqualTo(403);
    }

    @Test
    void ningunaEscrituraEstaPermitidaNiParaElAdministrador() throws Exception {
        String admin = TokensDePrueba.valido("root", "ROLE_USER", "ROLE_ADMIN");
        mockMvc.perform(conToken(post("/api/professionals"), admin)).andExpect(status().isForbidden());
        mockMvc.perform(conToken(put("/api/professionals/1"), admin)).andExpect(status().isForbidden());
        mockMvc.perform(conToken(delete("/api/professionals/1"), admin)).andExpect(status().isForbidden());
    }

    @Test
    void unaRutaDesconocidaSeDeniegaEnVezDeRevelarSiExiste() throws Exception {
        assertThat(estado("/api/lo-que-sea", TokensDePrueba.valido("root", "ROLE_USER", "ROLE_ADMIN"))).isEqualTo(403);
        assertThat(estado("/actuator/env", TokensDePrueba.valido("root", "ROLE_ADMIN"))).isEqualTo(403);
    }

    @Test
    void elRechazoPorPermisosNoRevelaQueRolFaltaba() throws Exception {
        String cuerpo = pedir("/api/sync/status", TokensDePrueba.valido("ana", "ROLE_USER")).getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain("ROLE_");
    }

    // --- trazabilidad ---

    @Test
    void cadaRechazoQuedaEnElLogSinElToken(CapturedOutput salida) throws Exception {
        String token = TokensDePrueba.valido("ana", "ROLE_GUEST");
        pedir("/api/sync/status", token);
        pedir("/api/categories", null);

        assertThat(salida.getAll())
                .contains("Acceso rechazado: 403 GET /api/sync/status usuario=ana")
                .contains("Acceso rechazado: 401 GET /api/categories")
                .doesNotContain(token);
    }
}
