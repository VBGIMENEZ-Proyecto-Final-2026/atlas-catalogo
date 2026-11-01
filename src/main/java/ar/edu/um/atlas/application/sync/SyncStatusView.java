package ar.edu.um.atlas.application.sync;

import ar.edu.um.atlas.domain.SnapshotReason;
import ar.edu.um.atlas.domain.SyncResult;
import ar.edu.um.atlas.domain.SyncStatus;

import java.time.Instant;

/**
 * Lo que se informa del estado de la sincronización: una vista de solo lectura de {@code sync_state}.
 * {@code localVersion} es {@code null} con la base vacía; {@code lastError} es {@code null} si no hay un
 * error vigente (un éxito posterior lo limpia).
 */
public record SyncStatusView(
        Long localVersion,
        SyncStatus status,
        SyncResult lastResult,
        SnapshotReason lastSnapshotReason,
        Instant lastSyncAt,
        LastError lastError) {

    /** Error vigente: el código funcional, su mensaje fijo y cuándo ocurrió. Nunca la causa técnica. */
    public record LastError(String code, String message, Instant at) {
    }
}
