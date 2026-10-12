package ar.edu.um.atlas.config;

import ar.edu.um.atlas.adapters.in.schedule.PeriodicSyncCheck;
import ar.edu.um.atlas.adapters.in.startup.SyncOnStartup;
import ar.edu.um.atlas.application.sync.SyncCatalog;
import ar.edu.um.atlas.application.sync.SyncLimits;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Clock;

/**
 * Reloj, cotas y disparadores de la sincronización: el arranque ({@code atlas.sync.on-startup}) y la
 * verificación periódica ({@code atlas.sync.periodic-check}), que se apagan por separado. Los tests de
 * contexto apagan los dos para no tocar la cátedra.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SyncProperties.class)
class SyncConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SyncLimits syncLimits(SyncProperties properties) {
        return new SyncLimits(properties.maxVersionsPerRun());
    }

    @Bean
    @ConditionalOnProperty(name = "atlas.sync.on-startup", havingValue = "true", matchIfMissing = true)
    SyncOnStartup syncOnStartup(SyncCatalog syncCatalog) {
        return new SyncOnStartup(syncCatalog);
    }

    /** Registra la verificación periódica con espera fija entre corridas: nunca se superponen. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "atlas.sync.periodic-check", havingValue = "true", matchIfMissing = true)
    static class PeriodicCheckConfig implements SchedulingConfigurer {

        private final PeriodicSyncCheck check;
        private final SyncProperties properties;

        PeriodicCheckConfig(SyncCatalog syncCatalog, SyncProperties properties) {
            this.check = new PeriodicSyncCheck(syncCatalog);
            this.properties = properties;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            // La primera verificación espera un intervalo: el arranque ya corrió la suya.
            registrar.addFixedDelayTask(new FixedDelayTask(check, properties.checkInterval(),
                    properties.checkInterval()));
        }
    }
}
