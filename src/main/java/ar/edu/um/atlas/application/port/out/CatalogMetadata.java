package ar.edu.um.atlas.application.port.out;

import java.time.Instant;

/**
 * Metadata de versiones que publica la cátedra: la versión vigente y la más antigua con cambios
 * disponibles. El cliente la entrega tal cual; interpretar {@code oldestAvailableVersion} es tarea del
 * planificador. {@code publishedAt} es {@code null} si la cátedra no lo publicó.
 */
public record CatalogMetadata(long currentVersion, long oldestAvailableVersion, Instant publishedAt) {
}
