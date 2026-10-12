package ar.edu.um.atlas.adapters.in.kafka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** El interpretador del mensaje no usa Kafka ni Spring: solo decide si es un aviso válido o se descarta. */
class CatalogUpdatedParserTest {

    @Test
    void unEventoDeLaReferenciaEsValido() {
        var result = CatalogUpdatedParser.parse("""
                {"eventId":"catalog-event-7-4f3b","eventType":"CatalogUpdated","newVersion":7,
                 "occurredAt":"2026-09-10T13:44:59Z","source":"MANUAL","schemaVersion":1}""");

        assertThat(result).isEqualTo(new CatalogUpdatedParser.Result.Valid(7, "catalog-event-7-4f3b"));
    }

    @Test
    void ignoraLosCamposDesconocidos() {
        var result = CatalogUpdatedParser.parse("""
                {"eventType":"CatalogUpdated","newVersion":8,"campoNuevo":{"x":[1,2]},"schemaVersion":2}""");

        assertThat(result).isEqualTo(new CatalogUpdatedParser.Result.Valid(8, null));
    }

    @Test
    void laVersionComoTextoSeAcepta() {
        assertThat(CatalogUpdatedParser.parse("""
                {"eventType":"CatalogUpdated","newVersion":"9"}"""))
                .isEqualTo(new CatalogUpdatedParser.Result.Valid(9, null));
    }

    @Test
    void sinValorSeDescarta() {
        assertThat(CatalogUpdatedParser.parse(null)).isInstanceOf(CatalogUpdatedParser.Result.Discard.class);
        assertThat(CatalogUpdatedParser.parse("")).isInstanceOf(CatalogUpdatedParser.Result.Discard.class);
        assertThat(CatalogUpdatedParser.parse("   ")).isInstanceOf(CatalogUpdatedParser.Result.Discard.class);
    }

    @Test
    void unJsonInvalidoSeDescarta() {
        for (String valor : new String[] {"esto no es json", "{\"eventType\":", "[1,2,3]", "7", "\"texto\""}) {
            assertThat(CatalogUpdatedParser.parse(valor)).as(valor)
                    .isInstanceOf(CatalogUpdatedParser.Result.Discard.class);
        }
    }

    @Test
    void unTipoDeEventoDistintoSeDescarta() {
        var result = CatalogUpdatedParser.parse("""
                {"eventType":"AppointmentConfirmed","newVersion":3}""");

        assertThat(result).isInstanceOfSatisfying(CatalogUpdatedParser.Result.Discard.class,
                d -> assertThat(d.reason()).contains("eventType"));
    }

    @Test
    void sinTipoDeEventoOSinVersionSeDescarta() {
        assertThat(CatalogUpdatedParser.parse("""
                {"newVersion":3}""")).isInstanceOf(CatalogUpdatedParser.Result.Discard.class);
        assertThat(CatalogUpdatedParser.parse("""
                {"eventType":"CatalogUpdated"}""")).isInstanceOfSatisfying(CatalogUpdatedParser.Result.Discard.class,
                d -> assertThat(d.reason()).contains("newVersion"));
    }

    @Test
    void unaVersionQueNoEsUnNumeroSeDescarta() {
        assertThat(CatalogUpdatedParser.parse("""
                {"eventType":"CatalogUpdated","newVersion":"siete"}"""))
                .isInstanceOf(CatalogUpdatedParser.Result.Discard.class);
    }

    @Test
    void elMotivoDelDescarteNoIncluyeElCuerpoDelMensaje() {
        var result = CatalogUpdatedParser.parse("""
                {"eventType":"Otro","newVersion":3,"secreto":"no-debe-aparecer"}""");

        assertThat(result).isInstanceOfSatisfying(CatalogUpdatedParser.Result.Discard.class,
                d -> assertThat(d.reason()).doesNotContain("no-debe-aparecer"));
    }
}
