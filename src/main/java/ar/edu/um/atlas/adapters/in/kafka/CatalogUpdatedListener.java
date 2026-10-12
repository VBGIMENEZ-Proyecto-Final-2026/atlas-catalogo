package ar.edu.um.atlas.adapters.in.kafka;

import ar.edu.um.atlas.application.sync.SyncCatalog;
import ar.edu.um.atlas.application.sync.SyncTrigger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Disparador por aviso: cada {@code CatalogUpdated} ejecuta {@link SyncCatalog}, el mismo caso de uso que el
 * arranque y la verificación periódica. El aviso solo despierta la sincronización: el plan sale de comparar
 * la versión local con la metadata de Redis, nunca de {@code newVersion}, por eso un evento duplicado,
 * atrasado o fuera de orden termina en {@code Noop} sin guardar ningún {@code eventId} (ADR 0003).
 *
 * <p>El <i>offset</i> se confirma solo después de que la corrida terminó sin excepción, o al descartar un
 * mensaje que no se puede interpretar (que no debe bloquear el consumo; la verificación periódica cubre el
 * hueco). Si la corrida falla, la excepción sube al manejador de errores del contenedor, que reintenta de
 * forma acotada y, al agotarla, descarta el mensaje con el <i>offset</i> confirmado.
 */
public class CatalogUpdatedListener implements AcknowledgingMessageListener<String, String> {

    private static final Logger log = LoggerFactory.getLogger(CatalogUpdatedListener.class);

    private final SyncCatalog syncCatalog;

    public CatalogUpdatedListener(SyncCatalog syncCatalog) {
        this.syncCatalog = syncCatalog;
    }

    @Override
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        switch (CatalogUpdatedParser.parse(record.value())) {
            case CatalogUpdatedParser.Result.Discard discard -> {
                // Sin el cuerpo del mensaje en el log: solo de dónde vino y por qué se descarta.
                log.warn("Mensaje descartado del topic del catálogo ({}-{}@{}): {}", record.topic(),
                        record.partition(), record.offset(), discard.reason());
                acknowledgment.acknowledge();
            }
            case CatalogUpdatedParser.Result.Valid event -> {
                log.info("CatalogUpdated recibido (newVersion={}, eventId={}): se verifica la copia local",
                        event.newVersion(), event.eventId());
                syncCatalog.run(SyncTrigger.AVISO_DE_VERSION);
                acknowledgment.acknowledge();
            }
        }
    }
}
