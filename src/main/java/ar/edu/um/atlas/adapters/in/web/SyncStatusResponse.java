package ar.edu.um.atlas.adapters.in.web;

import ar.edu.um.atlas.application.sync.SyncStatusView;

import java.time.Instant;

/**
 * Cuerpo de {@code GET /api/sync/status}. Los campos sin valor van como {@code null} (no se omiten) para que
 * quien lo consume vea siempre la misma forma. Las fechas salen en ISO-8601 UTC.
 */
public record SyncStatusResponse(
        Long localVersion,
        String status,
        String lastResult,
        String lastSnapshotReason,
        Instant lastSyncAt,
        LastError lastError) {

    public record LastError(String code, String message, Instant at) {
    }

    static SyncStatusResponse from(SyncStatusView view) {
        var error = view.lastError() == null ? null
                : new LastError(view.lastError().code(), view.lastError().message(), view.lastError().at());
        return new SyncStatusResponse(view.localVersion(), view.status().name(),
                view.lastResult() == null ? null : view.lastResult().name(),
                view.lastSnapshotReason() == null ? null : view.lastSnapshotReason().name(),
                view.lastSyncAt(), error);
    }
}
