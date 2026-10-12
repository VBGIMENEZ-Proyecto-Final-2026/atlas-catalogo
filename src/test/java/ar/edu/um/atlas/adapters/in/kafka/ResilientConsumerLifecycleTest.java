package ar.edu.um.atlas.adapters.in.kafka;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El ciclo de vida reintenta el arranque del contenedor en segundo plano y nunca lanza hacia la aplicación. */
class ResilientConsumerLifecycleTest {

    @Test
    void siElContenedorFallaAlArrancarNoLanzaYReintentaHastaQueArranca() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        doThrow(new KafkaException("Failed to construct kafka consumer"))
                .doThrow(new KafkaException("Failed to construct kafka consumer"))
                .doNothing().when(container).start();
        var lifecycle = new ResilientConsumerLifecycle(container, Duration.ofMillis(50));

        lifecycle.start(); // no lanza aunque la primera vez falle

        assertThat(lifecycle.isRunning()).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(container, times(3)).start());
        lifecycle.stop();
    }

    @Test
    void alDetenerseDejaDeReintentar() throws Exception {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        doThrow(new KafkaException("Failed to construct kafka consumer")).when(container).start();
        var lifecycle = new ResilientConsumerLifecycle(container, Duration.ofMillis(50));

        lifecycle.start();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(container, atLeast(2)).start());
        lifecycle.stop();
        Thread.sleep(200); // deja terminar un intento que estuviera en curso
        int intentosAlDetenerse = (int) mockingDetails(container).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("start")).count();
        Thread.sleep(400);

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(mockingDetails(container).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("start")).count())
                .isEqualTo(intentosAlDetenerse);
    }

    @Test
    void alDetenerseDetieneElContenedorSiEstabaCorriendo() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        doNothing().when(container).start();
        when(container.isRunning()).thenReturn(true);
        var lifecycle = new ResilientConsumerLifecycle(container, Duration.ofMillis(50));

        lifecycle.start();
        lifecycle.stop();

        verify(container).stop();
    }

    @Test
    void siElContenedorNuncaArrancoNoLoDetiene() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        doThrow(new KafkaException("Failed to construct kafka consumer")).when(container).start();
        when(container.isRunning()).thenReturn(false);
        var lifecycle = new ResilientConsumerLifecycle(container, Duration.ofSeconds(60));

        lifecycle.start();
        lifecycle.stop();

        verify(container, never()).stop();
    }
}
