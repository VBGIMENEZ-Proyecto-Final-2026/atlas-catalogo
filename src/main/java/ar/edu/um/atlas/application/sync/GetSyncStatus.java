package ar.edu.um.atlas.application.sync;

import ar.edu.um.atlas.application.repository.SyncStateRepository;
import ar.edu.um.atlas.domain.SyncState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Informa el estado de la sincronización. Lee {@code sync_state} sin tomar el candado ({@code FOR UPDATE}):
 * consultar el estado nunca espera ni bloquea a una corrida en curso, y ve lo último confirmado. No depende
 * de la cátedra, así que responde aunque ella esté caída.
 *
 * <p>No informa {@code RUNNING}: ninguna corrida lo escribe, porque mientras una corrida está abierta nadie
 * ve su cambio y el candado ya excluye a las demás.
 */
@Service
public class GetSyncStatus {

    private final SyncStateRepository syncState;

    public GetSyncStatus(SyncStateRepository syncState) {
        this.syncState = syncState;
    }

    @Transactional(readOnly = true)
    public SyncStatusView get() {
        SyncState state = syncState.findById(SyncState.ID)
                .orElseThrow(() -> new IllegalStateException("Falta la fila única de sync_state"));
        SyncStatusView.LastError error = state.getLastErrorCode() == null ? null
                : new SyncStatusView.LastError(state.getLastErrorCode(), state.getLastErrorMessage(),
                        state.getLastErrorAt());
        return new SyncStatusView(state.getLocalVersion(), state.getStatus(), state.getLastResult(),
                state.getLastSnapshotReason(), state.getLastSyncAt(), error);
    }
}
