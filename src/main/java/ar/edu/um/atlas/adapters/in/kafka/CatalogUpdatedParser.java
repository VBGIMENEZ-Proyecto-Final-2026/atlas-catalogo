package ar.edu.um.atlas.adapters.in.kafka;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Interpreta el valor de un mensaje del topic del catálogo (referencia de integración, 15.3). Solo exige lo
 * mínimo para saber que es un aviso de versión nueva: {@code eventType = CatalogUpdated} y {@code newVersion}.
 * Los campos desconocidos se ignoran (15.1). Nunca lanza excepciones: lo que no se puede interpretar vuelve
 * como {@link Result.Discard}, con un motivo que no incluye el cuerpo del mensaje.
 */
final class CatalogUpdatedParser {

    static final String EVENT_TYPE = "CatalogUpdated";

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private CatalogUpdatedParser() {
    }

    /** Forma mínima del evento. {@code eventId} puede faltar: solo sirve para el log. */
    private record Raw(String eventId, String eventType, Long newVersion) {
    }

    sealed interface Result {

        /** Un aviso válido. {@code newVersion} y {@code eventId} son solo informativos: no deciden nada. */
        record Valid(long newVersion, String eventId) implements Result {
        }

        /** Un mensaje que se registra y se descarta sin bloquear el consumo. */
        record Discard(String reason) implements Result {
        }
    }

    static Result parse(String value) {
        if (value == null || value.isBlank()) {
            return new Result.Discard("el mensaje no tiene valor");
        }
        Raw raw;
        try {
            raw = JSON.readValue(value, Raw.class);
        } catch (JacksonException e) {
            return new Result.Discard("el valor no es un JSON interpretable");
        }
        if (raw == null) {
            return new Result.Discard("el valor no es un objeto JSON");
        }
        if (!EVENT_TYPE.equals(raw.eventType())) {
            return new Result.Discard("eventType distinto de " + EVENT_TYPE);
        }
        if (raw.newVersion() == null) {
            return new Result.Discard("falta newVersion");
        }
        return new Result.Valid(raw.newVersion(), raw.eventId());
    }
}
