package ar.edu.um.atlas.config;

import ar.edu.um.atlas.adapters.in.startup.SnapshotOnStartup;
import ar.edu.um.atlas.application.repository.SyncStateRepository;
import ar.edu.um.atlas.application.sync.ApplySnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Reloj del sistema y disparador de arranque, que se apaga con {@code atlas.sync.on-startup=false}. */
@Configuration(proxyBeanMethods = false)
class SyncConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "atlas.sync.on-startup", havingValue = "true", matchIfMissing = true)
    SnapshotOnStartup snapshotOnStartup(SyncStateRepository syncState, ApplySnapshot applySnapshot) {
        return new SnapshotOnStartup(syncState, applySnapshot);
    }
}
