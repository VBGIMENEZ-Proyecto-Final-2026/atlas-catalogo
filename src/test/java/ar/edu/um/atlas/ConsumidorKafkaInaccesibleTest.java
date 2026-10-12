package ar.edu.um.atlas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La aplicación completa arranca con el consumidor encendido aunque Kafka no responda: el servidor existe
 * en el nombre pero nadie escucha en ese puerto. El consumidor queda intentando conectarse en segundo plano
 * y el servicio sigue sano.
 */
@SpringBootTest(properties = {
        "atlas.sync.kafka.enabled=true",
        "catedra.kafka.bootstrap-servers=localhost:1",
        "catedra.kafka.catalog-topic=catedra.catalog.test",
        "catedra.kafka.consumer-group-id=alumnos-test-inaccesible"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// El consumidor sigue intentando conectarse mientras el contexto vive: se cierra al terminar la clase.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConsumidorKafkaInaccesibleTest {

    @Autowired KafkaMessageListenerContainer<String, String> consumidor;
    @Autowired MockMvc mockMvc;

    @Test
    void laAplicacionArrancaYElConsumidorQuedaReintentandoLaConexion() throws Exception {
        await().atMost(Duration.ofSeconds(30)).until(consumidor::isRunning);

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        assertThat(consumidor.getAssignedPartitions()).isNullOrEmpty();
    }
}
