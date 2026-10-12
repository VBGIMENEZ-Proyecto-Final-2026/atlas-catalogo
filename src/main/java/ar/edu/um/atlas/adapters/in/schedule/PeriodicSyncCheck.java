package ar.edu.um.atlas.adapters.in.schedule;

import ar.edu.um.atlas.application.sync.SyncCatalog;
import ar.edu.um.atlas.application.sync.SyncException;
import ar.edu.um.atlas.application.sync.SyncTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disparador periódico: compara la versión local con la de la cátedra para recuperar avisos perdidos y para
 * mantener la copia al día aunque no llegue ningún aviso. Se registra en {@code config}, que fija el
 * intervalo (con espera fija entre corridas, para que no se superpongan) y puede apagarlo con
 * {@code atlas.sync.periodic-check=false}.
 */
public class PeriodicSyncCheck implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PeriodicSyncCheck.class);

    private final SyncCatalog syncCatalog;

    public PeriodicSyncCheck(SyncCatalog syncCatalog) {
        this.syncCatalog = syncCatalog;
    }

    @Override
    public void run() {
        try {
            syncCatalog.run(SyncTrigger.VERIFICACION_PERIODICA);
        } catch (SyncException e) {
            // Ya quedó registrada en sync_state; la próxima verificación lo reintenta.
            log.warn("La verificación periódica falló ({})", e.getErrorCode());
        } catch (RuntimeException e) {
            log.error("La verificación periódica falló de forma inesperada: {}", e.getClass().getSimpleName());
        }
    }
}
