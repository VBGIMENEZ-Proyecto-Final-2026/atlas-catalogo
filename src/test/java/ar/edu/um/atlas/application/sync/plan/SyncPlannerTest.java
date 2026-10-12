package ar.edu.um.atlas.application.sync.plan;

import ar.edu.um.atlas.application.port.out.CatalogMetadata;
import ar.edu.um.atlas.domain.SnapshotReason;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El planificador es una función pura: estas pruebas no usan Spring, Redis, REST ni base de datos. Hay una
 * por cada fila de la tabla del ADR 0003, más los bordes.
 */
class SyncPlannerTest {

    private static Optional<CatalogMetadata> metadata(long current, long oldest) {
        return Optional.of(new CatalogMetadata(current, oldest, null));
    }

    private static SyncPlan plan(Long local, Optional<CatalogMetadata> metadata) {
        return SyncPlanner.plan(local, metadata);
    }

    @Test
    void fila1_sinVersionLocalPideSnapshotPorBaseVacia() {
        assertThat(plan(null, metadata(7, 4))).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.BASE_VACIA));
    }

    @Test
    void fila1_laBaseVaciaGanaAunqueNoHayaMetadata() {
        assertThat(plan(null, Optional.empty())).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.BASE_VACIA));
    }

    @Test
    void fila2_sinMetadataNoEsVerificable() {
        assertThat(plan(3L, Optional.empty())).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.NO_VERIFICABLE));
    }

    @Test
    void fila2_metadataIncoherenteNoEsVerificable() {
        assertThat(plan(3L, metadata(2, 5))).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.NO_VERIFICABLE));
    }

    @Test
    void fila3_laLocalAdelantadaPideSnapshot() {
        assertThat(plan(8L, metadata(7, 4))).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.LOCAL_ADELANTADA));
        assertThat(plan(8L, metadata(7, 7))).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.LOCAL_ADELANTADA));
    }

    @Test
    void fila4_conLaVersionVigenteNoHaceNada() {
        assertThat(plan(7L, metadata(7, 4))).isEqualTo(new SyncPlan.Noop());
        assertThat(plan(7L, metadata(7, 7))).isEqualTo(new SyncPlan.Noop());
    }

    @Test
    void fila5_porDebajoDeLaVentanaPideSnapshot() {
        // Ejemplo 18.2 de la referencia: local 3, oldest 4, current 7.
        assertThat(plan(3L, metadata(7, 4))).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.FUERA_DE_VENTANA));
    }

    @Test
    void fila6_dentroDeLaVentanaAplicaIncremental() {
        assertThat(plan(5L, metadata(7, 4))).isEqualTo(new SyncPlan.Incremental(6, 7));
    }

    @Test
    void borde_conLaLocalEnElOldestAplicaIncrementalDesdeLaSiguiente() {
        assertThat(plan(4L, metadata(7, 4))).isEqualTo(new SyncPlan.Incremental(5, 7));
    }

    @Test
    void borde_conLaLocalUnaVersionAtrasAplicaSoloEsaVersion() {
        assertThat(plan(6L, metadata(7, 4))).isEqualTo(new SyncPlan.Incremental(7, 7));
    }

    @Test
    void borde_conLaLocalUnaVersionPorDebajoDelOldestPideSnapshot() {
        assertThat(plan(3L, metadata(7, 4))).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.FUERA_DE_VENTANA));
        assertThat(plan(0L, metadata(1, 1))).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.FUERA_DE_VENTANA));
    }

    @Test
    void borde_laMetadataConCurrentIgualAOldestEsCoherente() {
        assertThat(plan(6L, metadata(7, 7))).isEqualTo(new SyncPlan.Snapshot(SnapshotReason.FUERA_DE_VENTANA));
        assertThat(plan(7L, metadata(7, 7))).isEqualTo(new SyncPlan.Noop());
    }
}
