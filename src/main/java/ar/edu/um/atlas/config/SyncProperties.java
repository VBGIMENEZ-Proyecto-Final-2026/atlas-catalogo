package ar.edu.um.atlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración de la sincronización. {@code onStartup} y {@code periodicCheck} encienden los dos
 * disparadores; {@code checkInterval} es la espera entre verificaciones y {@code maxVersionsPerRun} acota
 * cuántas versiones se aplican en una corrida.
 */
@ConfigurationProperties("atlas.sync")
public record SyncProperties(
        @DefaultValue("true") boolean onStartup,
        @DefaultValue("true") boolean periodicCheck,
        @DefaultValue("60s") Duration checkInterval,
        @DefaultValue("1000") int maxVersionsPerRun) {

    public SyncProperties {
        if (checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("ATLAS_SYNC_CHECK_INTERVAL debe ser mayor que cero");
        }
        if (maxVersionsPerRun < 1) {
            throw new IllegalArgumentException("ATLAS_SYNC_MAX_VERSIONS_PER_RUN debe ser al menos 1");
        }
    }
}
