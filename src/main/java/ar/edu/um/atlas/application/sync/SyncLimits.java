package ar.edu.um.atlas.application.sync;

/**
 * Cotas de una corrida incremental. {@code maxVersionsPerRun} es el máximo de versiones que se aplican en
 * una corrida: si faltan más, la siguiente verificación continúa. Evita corridas sin fin.
 */
public record SyncLimits(int maxVersionsPerRun) {

    public SyncLimits {
        if (maxVersionsPerRun < 1) {
            throw new IllegalArgumentException("El máximo de versiones por corrida debe ser al menos 1");
        }
    }
}
