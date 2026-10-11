package ar.edu.um.atlas.config;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatalogSnapshotSource;
import ar.edu.um.atlas.application.port.out.CatedraCredentialRejectedException;
import ar.edu.um.atlas.application.port.out.CatedraInvalidResponseException;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;
import ar.edu.um.atlas.config.FakeCatedra.Reply;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El cliente REST completo (configuración real, {@code RestClient}, Jackson 3, reintentos y traducción
 * de errores) contra un servidor local que simula a la cátedra. No se llama a la cátedra real.
 */
class CatedraRestSnapshotSourceTest {

    private static final String TOKEN = "token-secreto-de-prueba";

    private static final String SNAPSHOT = """
            {
              "snapshotVersion": 7,
              "generatedAt": "2026-09-10T13:44:59Z",
              "campoNuevoDeLaCatedra": {"x": 1},
              "professionalCategories": [
                {"id": 1, "name": "Clinica medica", "description": "Atencion clinica general", "enabled": true,
                 "createdAt": "2026-07-01T12:00:00Z", "updatedAt": "2026-09-10T13:40:00Z", "otro": "ignorado"}
              ],
              "professionals": [
                {"id": 15, "categoryId": 1, "firstName": "Ana", "lastName": "Gomez", "enabled": true,
                 "createdAt": "2026-07-01T12:00:00Z", "updatedAt": "2026-09-10T13:40:00Z"}
              ],
              "weeklySchedules": [
                {"id": 21, "professionalId": 15, "dayOfWeek": "MONDAY", "startTime": "09:00:00", "endTime": "12:00:00",
                 "slotDurationMinutes": 30, "enabled": true,
                 "createdAt": "2026-07-01T12:00:00Z", "updatedAt": "2026-09-10T13:40:00Z"},
                {"id": 22, "professionalId": 15, "dayOfWeek": "SUNDAY", "startTime": "08:30", "endTime": "10:15",
                 "slotDurationMinutes": 15, "enabled": false, "createdAt": "2026-07-01T12:00:00Z",
                 "updatedAt": "2026-09-10T13:40:00Z"}
              ]
            }
            """;

    private static final String SNAPSHOT_VACIO = """
            {"snapshotVersion": 0, "generatedAt": "2026-09-10T13:44:59Z",
             "professionalCategories": [], "professionals": [], "weeklySchedules": []}
            """;

    private static CatalogSnapshotSource source(FakeCatedra catedra, long maxRetries, Duration readTimeout) {
        CatedraProperties properties = new CatedraProperties(catedra.baseUrl(), TOKEN, Duration.ofSeconds(2),
                readTimeout, new CatedraProperties.Retry(maxRetries, Duration.ofMillis(10), 1.0, Duration.ofMillis(10)));
        return new CatedraRestConfig().catalogSnapshotSource(properties);
    }

    private static CatalogSnapshotSource source(FakeCatedra catedra) {
        return source(catedra, 2, Duration.ofSeconds(5));
    }

    @Test
    void interpretaElSnapshotCompleto() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.json(200, SNAPSHOT))) {
            CatalogSnapshot snapshot = source(catedra).fetchSnapshot();

            assertThat(snapshot.version()).isEqualTo(7);
            assertThat(snapshot.generatedAt()).isEqualTo(Instant.parse("2026-09-10T13:44:59Z"));
            assertThat(snapshot.categories()).hasSize(1);
            assertThat(snapshot.categories().getFirst().getName()).isEqualTo("Clinica medica");
            assertThat(snapshot.categories().getFirst().getUpdatedAt()).isEqualTo(Instant.parse("2026-09-10T13:40:00Z"));
            assertThat(snapshot.professionals()).hasSize(1);
            assertThat(snapshot.professionals().getFirst().getCategoryId()).isEqualTo(1L);
            assertThat(snapshot.professionals().getFirst().getLastName()).isEqualTo("Gomez");
            assertThat(snapshot.weeklySchedules()).hasSize(2);
            var conSegundos = snapshot.weeklySchedules().get(0);
            assertThat(conSegundos.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(conSegundos.getStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(conSegundos.getEndTime()).isEqualTo(LocalTime.of(12, 0));
            var sinSegundos = snapshot.weeklySchedules().get(1);
            assertThat(sinSegundos.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
            assertThat(sinSegundos.getStartTime()).isEqualTo(LocalTime.of(8, 30));
            assertThat(sinSegundos.getEndTime()).isEqualTo(LocalTime.of(10, 15));
            assertThat(sinSegundos.isEnabled()).isFalse();
        }
    }

    @Test
    void aceptaColeccionesVacias() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.json(200, SNAPSHOT_VACIO))) {
            CatalogSnapshot snapshot = source(catedra).fetchSnapshot();

            assertThat(snapshot.version()).isZero();
            assertThat(snapshot.categories()).isEmpty();
            assertThat(snapshot.professionals()).isEmpty();
            assertThat(snapshot.weeklySchedules()).isEmpty();
        }
    }

    @Test
    void enviaElTokenComoBearerAlEndpointDelSnapshot() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.json(200, SNAPSHOT_VACIO))) {
            source(catedra).fetchSnapshot();

            assertThat(catedra.requests()).singleElement().satisfies(r -> {
                assertThat(r.method()).isEqualTo("GET");
                assertThat(r.path()).isEqualTo("/api/synchronization/snapshot");
                assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN);
            });
        }
    }

    @Test
    void unaCredencialRechazadaSinCodeNoSeReintenta() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.json(401, ""))) {
            assertThatThrownBy(() -> source(catedra).fetchSnapshot())
                    .isInstanceOfSatisfying(CatedraCredentialRejectedException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(401);
                        assertThat(e.getCode()).isNull();
                    });
            assertThat(catedra.requests()).hasSize(1);
        }
    }

    @Test
    void un403ConCodeConservaElCodeYNoSeReintenta() throws IOException {
        String problema = """
                {"title": "Forbidden", "status": 403, "code": "STUDENT_INTEGRATION_NOT_FOUND"}""";
        try (FakeCatedra catedra = new FakeCatedra(Reply.problem(403, problema))) {
            assertThatThrownBy(() -> source(catedra).fetchSnapshot())
                    .isInstanceOfSatisfying(CatedraCredentialRejectedException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(403);
                        assertThat(e.getCode()).isEqualTo("STUDENT_INTEGRATION_NOT_FOUND");
                    });
            assertThat(catedra.requests()).hasSize(1);
        }
    }

    @Test
    void un500EsCatedraNoDisponibleYNoSeReintenta() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.problem(500, "{\"status\": 500}"))) {
            assertThatThrownBy(() -> source(catedra).fetchSnapshot())
                    .isInstanceOfSatisfying(CatedraUnavailableException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(500));
            assertThat(catedra.requests()).hasSize(1);
        }
    }

    @Test
    void un503SeRecuperaEnElReintento() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.problem(503, "{}"), Reply.json(200, SNAPSHOT_VACIO))) {
            CatalogSnapshot snapshot = source(catedra).fetchSnapshot();

            assertThat(snapshot.version()).isZero();
            assertThat(catedra.requests()).hasSize(2);
        }
    }

    @Test
    void un503PersistenteSeAcotaAlMaximoDeReintentos() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.problem(503, "{}"))) {
            assertThatThrownBy(() -> source(catedra, 2, Duration.ofSeconds(5)).fetchSnapshot())
                    .isInstanceOfSatisfying(CatedraUnavailableException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(503));
            assertThat(catedra.requests()).hasSize(3); // primer intento + 2 reintentos
        }
    }

    @Test
    void unTimeoutDeLecturaEsCatedraNoDisponibleYSeReintentaDeFormaAcotada() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.slow(1500, SNAPSHOT_VACIO))) {
            assertThatThrownBy(() -> source(catedra, 1, Duration.ofMillis(300)).fetchSnapshot())
                    .isInstanceOf(CatedraUnavailableException.class);
            assertThat(catedra.requests()).hasSize(2);
        }
    }

    @Test
    void unaCatedraCaidaEsCatedraNoDisponible() throws IOException {
        CatalogSnapshotSource source;
        try (FakeCatedra catedra = new FakeCatedra()) {
            source = source(catedra, 1, Duration.ofSeconds(5));
        }

        assertThatThrownBy(source::fetchSnapshot).isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void unJsonInvalidoEsRespuestaInvalidaYNoSeReintenta() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.json(200, "{esto no es json"))) {
            assertThatThrownBy(() -> source(catedra).fetchSnapshot())
                    .isInstanceOf(CatedraInvalidResponseException.class);
            assertThat(catedra.requests()).hasSize(1);
        }
    }

    @Test
    void unaRespuestaSinCamposObligatoriosEsRespuestaInvalida() throws IOException {
        String sinVersion = """
                {"professionalCategories": [], "professionals": [], "weeklySchedules": []}""";
        try (FakeCatedra catedra = new FakeCatedra(Reply.json(200, sinVersion))) {
            assertThatThrownBy(() -> source(catedra).fetchSnapshot())
                    .isInstanceOf(CatedraInvalidResponseException.class)
                    .hasMessageContaining("snapshotVersion");
        }
    }

    @Test
    void unDiaDeLaSemanaDesconocidoEsRespuestaInvalida() throws IOException {
        String json = SNAPSHOT.replace("\"MONDAY\"", "\"FUNDAY\"");
        try (FakeCatedra catedra = new FakeCatedra(Reply.json(200, json))) {
            assertThatThrownBy(() -> source(catedra).fetchSnapshot())
                    .isInstanceOf(CatedraInvalidResponseException.class);
        }
    }

    @Test
    void otroCuatroXxInesperadoEsRespuestaInvalida() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.problem(404, "{\"code\": \"NOT_FOUND\"}"))) {
            assertThatThrownBy(() -> source(catedra).fetchSnapshot())
                    .isInstanceOfSatisfying(CatedraInvalidResponseException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(404);
                        assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                    });
            assertThat(catedra.requests()).hasSize(1);
        }
    }

    @Test
    void noSigueRedireccionesParaNoReenviarElToken() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.redirect("/otro-destino"))) {
            assertThatThrownBy(() -> source(catedra).fetchSnapshot())
                    .isInstanceOf(CatedraInvalidResponseException.class);
            assertThat(catedra.requests()).hasSize(1);
        }
    }

    @Test
    void elTokenNoApareceEnLosMensajesDeLosErrores() throws IOException {
        try (FakeCatedra catedra = new FakeCatedra(Reply.json(401, ""))) {
            assertThatThrownBy(() -> source(catedra).fetchSnapshot())
                    .isInstanceOfSatisfying(CatedraCredentialRejectedException.class, e -> {
                        assertThat(e.getMessage()).doesNotContain(TOKEN);
                        assertThat(e.toString()).doesNotContain(TOKEN);
                        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                            assertThat(String.valueOf(t.getMessage())).doesNotContain(TOKEN);
                        }
                    });
        }
    }

    @Test
    void elTokenNoApareceEnElToStringDeLasPropiedades() {
        CatedraProperties properties = new CatedraProperties("http://catedra.test.invalid", TOKEN,
                Duration.ofSeconds(1), Duration.ofSeconds(1), new CatedraProperties.Retry(1, Duration.ZERO, 1.0, Duration.ZERO));

        assertThat(properties.toString()).doesNotContain(TOKEN).contains("idToken=***");
    }
}
