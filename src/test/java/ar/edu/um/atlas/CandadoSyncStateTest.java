package ar.edu.um.atlas;

import ar.edu.um.atlas.application.repository.SyncStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El candado de la corrida de sync: {@code SELECT ... FOR UPDATE} sobre la fila única de
 * {@code sync_state}. Sin {@code @Transactional}: necesita dos transacciones reales en paralelo.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CandadoSyncStateTest {

    @Autowired SyncStateRepository syncState;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void unaSegundaCorridaEsperaHastaQueLaPrimeraTermina() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch primeraTomoElCandado = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        try {
            CompletableFuture<Void> primera = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
                syncState.findForUpdate();
                primeraTomoElCandado.countDown();
                await(soltar);
            }), pool);
            assertThat(primeraTomoElCandado.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> segunda = CompletableFuture.runAsync(
                    () -> tx.executeWithoutResult(status -> syncState.findForUpdate()), pool);

            assertThatThrownBy(() -> segunda.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            soltar.countDown();
            primera.get(10, TimeUnit.SECONDS);
            segunda.get(10, TimeUnit.SECONDS);
        } finally {
            soltar.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
