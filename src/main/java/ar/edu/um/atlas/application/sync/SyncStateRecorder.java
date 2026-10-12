package ar.edu.um.atlas.application.sync;

import ar.edu.um.atlas.application.repository.SyncStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Escribe en {@code sync_state} lo que no forma parte de la unidad de trabajo de una corrida: un error
 * (la transacción principal ya se revirtió y liberó el candado) o una verificación sin cambios. Cada
 * escritura es una transacción corta que toma el candado.
 */
@Component
public class SyncStateRecorder {

    private static final Logger log = LoggerFactory.getLogger(SyncStateRecorder.class);

    private final SyncStateRepository syncState;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public SyncStateRecorder(SyncStateRepository syncState, PlatformTransactionManager transactionManager,
                             Clock clock) {
        this.syncState = syncState;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Deja la corrida en {@code ERROR}. Si ni siquiera esto se puede escribir, lo deja en el log. */
    public void recordError(SyncErrorCode code) {
        try {
            transaction.executeWithoutResult(status ->
                    syncState.findForUpdate().registrarError(code.name(), code.message(), clock.instant()));
        } catch (RuntimeException e) {
            log.error("No se pudo registrar el error {} en sync_state", code, e);
        }
    }

    /** Registra una verificación sin cambios y limpia el error vigente. */
    public void recordNoop() {
        transaction.executeWithoutResult(status -> syncState.findForUpdate().registrarNoop(clock.instant()));
    }
}
