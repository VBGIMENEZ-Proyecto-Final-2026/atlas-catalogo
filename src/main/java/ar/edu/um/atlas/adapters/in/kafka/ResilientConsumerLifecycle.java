package ar.edu.um.atlas.adapters.in.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Arranca el contenedor del consumidor sin poder tumbar la aplicación. El cliente de Kafka falla al
 * construirse si los servidores no se pueden resolver (por ejemplo, con la red de la cátedra caída al
 * arrancar), y eso haría fallar el arranque entero. Acá esa falla se registra y se reintenta en segundo
 * plano hasta que el contenedor arranca; si los servidores existen pero no responden, el propio cliente
 * reintenta conectarse. Hasta entonces la verificación periódica mantiene la copia al día.
 *
 * <p>El contenedor se crea con {@code autoStartup = false}: lo arranca y lo detiene este ciclo de vida.
 */
public class ResilientConsumerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ResilientConsumerLifecycle.class);

    private final MessageListenerContainer container;
    private final Duration retryInterval;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;

    public ResilientConsumerLifecycle(MessageListenerContainer container, Duration retryInterval) {
        this.container = container;
        this.retryInterval = retryInterval;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kafka-consumer-start");
            thread.setDaemon(true);
            return thread;
        });
        tryStart();
    }

    private synchronized void tryStart() {
        if (!running) {
            return;
        }
        try {
            container.start();
            log.info("Consumidor de CatalogUpdated iniciado");
        } catch (RuntimeException e) {
            // Solo la causa raíz, sin servidores ni credenciales.
            log.warn("No se pudo iniciar el consumidor de Kafka ({}); se reintenta en {}",
                    rootMessage(e), retryInterval);
            scheduler.schedule(this::tryStart, retryInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (container.isRunning()) {
            container.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
