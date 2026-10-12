package ar.edu.um.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Fila única de control del sync: versión local, último resultado y error vigente. La tabla
 * impone {@code id = 1}; la corrida de sync la toma con {@code SELECT ... FOR UPDATE} como candado.
 */
@Entity
@Table(name = "sync_state")
public class SyncState {

    public static final short ID = 1;

    @Id
    private Short id;

    @Column(name = "local_version")
    private Long localVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_result")
    private SyncResult lastResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_snapshot_reason")
    private SnapshotReason lastSnapshotReason;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SyncState() {
    }

    /**
     * Registra un snapshot aplicado: la versión local pasa a ser exactamente {@code version} (aunque sea
     * menor que la anterior, en una reconstrucción) y se limpia el error vigente.
     */
    public void registrarSnapshotAplicado(long version, SnapshotReason reason, Instant now) {
        this.localVersion = version;
        this.status = SyncStatus.IDLE;
        this.lastResult = SyncResult.SNAPSHOT;
        this.lastSnapshotReason = reason;
        this.lastSyncAt = now;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
        this.lastErrorAt = null;
        this.updatedAt = now;
    }

    /** Registra una corrida fallida. No toca la versión local ni el último resultado exitoso. */
    public void registrarError(String code, String message, Instant now) {
        this.status = SyncStatus.ERROR;
        this.lastErrorCode = code;
        this.lastErrorMessage = message;
        this.lastErrorAt = now;
        this.updatedAt = now;
    }

    public Short getId() {
        return id;
    }

    public Long getLocalVersion() {
        return localVersion;
    }

    public void setLocalVersion(Long localVersion) {
        this.localVersion = localVersion;
    }

    public SyncStatus getStatus() {
        return status;
    }

    public void setStatus(SyncStatus status) {
        this.status = status;
    }

    public SyncResult getLastResult() {
        return lastResult;
    }

    public void setLastResult(SyncResult lastResult) {
        this.lastResult = lastResult;
    }

    public SnapshotReason getLastSnapshotReason() {
        return lastSnapshotReason;
    }

    public void setLastSnapshotReason(SnapshotReason lastSnapshotReason) {
        this.lastSnapshotReason = lastSnapshotReason;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(Instant lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public void setLastErrorAt(Instant lastErrorAt) {
        this.lastErrorAt = lastErrorAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
