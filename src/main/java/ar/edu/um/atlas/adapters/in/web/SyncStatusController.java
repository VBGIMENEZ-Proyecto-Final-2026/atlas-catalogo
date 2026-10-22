package ar.edu.um.atlas.adapters.in.web;

import ar.edu.um.atlas.application.sync.GetSyncStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estado de la sincronización: versión local, último resultado y error vigente. Solo traduce: no tiene
 * lógica ni toca repositorios.
 *
 * <p>Está apagado por defecto ({@code atlas.sync.status-endpoint.enabled=false}): hasta que exista la
 * seguridad base el endpoint no se expone, y apagado la ruta responde {@code 404} como si no existiera.
 */
@RestController
@RequestMapping("/api/sync")
@ConditionalOnProperty(name = "atlas.sync.status-endpoint.enabled", havingValue = "true")
class SyncStatusController {

    private final GetSyncStatus getSyncStatus;

    SyncStatusController(GetSyncStatus getSyncStatus) {
        this.getSyncStatus = getSyncStatus;
    }

    @GetMapping("/status")
    SyncStatusResponse status() {
        return SyncStatusResponse.from(getSyncStatus.get());
    }
}
