package ar.edu.um.atlas.adapters.in.startup;

import ar.edu.um.atlas.application.sync.SyncCatalog;
import ar.edu.um.atlas.application.sync.SyncTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Disparador de arranque: al terminar de arrancar corre la sincronización. Con la base vacía arma la copia
 * con el snapshot; con una versión local la pone al día con los cambios que se hayan perdido mientras la
 * aplicación estaba apagada. Si la cátedra o Redis fallan, la aplicación sigue arrancada y el error queda
 * en {@code sync_state}. Se registra en {@code config} y se apaga con {@code atlas.sync.on-startup=false}.
 */
public class SyncOnStartup {

    private static final Logger log = LoggerFactory.getLogger(SyncOnStartup.class);

    private final SyncCatalog syncCatalog;

    public SyncOnStartup(SyncCatalog syncCatalog) {
        this.syncCatalog = syncCatalog;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            syncCatalog.run(SyncTrigger.ARRANQUE);
        } catch (RuntimeException e) {
            // Nunca tumba el arranque. Si fue una falla de integración, ya quedó registrada en sync_state.
            log.error("La sincronización de arranque falló ({}); la aplicación sigue arrancada",
                    e instanceof ar.edu.um.atlas.application.sync.SyncException s ? s.getErrorCode() : e.getClass()
                            .getSimpleName());
        }
    }
}
