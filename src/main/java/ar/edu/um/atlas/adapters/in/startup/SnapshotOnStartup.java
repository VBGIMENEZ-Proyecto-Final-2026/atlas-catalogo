package ar.edu.um.atlas.adapters.in.startup;

import ar.edu.um.atlas.application.repository.SyncStateRepository;
import ar.edu.um.atlas.application.sync.ApplySnapshot;
import ar.edu.um.atlas.application.sync.SnapshotSyncException;
import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.SyncState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Disparador de arranque: con la base vacía (sin versión local) corre el snapshot completo con el motivo
 * {@code BASE_VACIA}. Si la cátedra falla, la aplicación sigue arrancada y el error queda en
 * {@code sync_state}. Se registra en {@code config} y se apaga con {@code atlas.sync.on-startup=false}.
 */
public class SnapshotOnStartup {

    private static final Logger log = LoggerFactory.getLogger(SnapshotOnStartup.class);

    private final SyncStateRepository syncState;
    private final ApplySnapshot applySnapshot;

    public SnapshotOnStartup(SyncStateRepository syncState, ApplySnapshot applySnapshot) {
        this.syncState = syncState;
        this.applySnapshot = applySnapshot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        boolean baseVacia = syncState.findById(SyncState.ID)
                .map(state -> state.getLocalVersion() == null)
                .orElse(true);
        if (!baseVacia) {
            log.info("Hay versión local: no se pide snapshot al arrancar");
            return;
        }
        log.info("Base vacía: se pide el snapshot completo de la cátedra");
        try {
            applySnapshot.run(SnapshotReason.BASE_VACIA);
        } catch (SnapshotSyncException e) {
            log.error("El snapshot de arranque falló ({}); la aplicación sigue arrancada", e.getErrorCode());
        }
    }
}
