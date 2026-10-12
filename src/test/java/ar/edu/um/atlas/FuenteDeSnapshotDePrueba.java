package ar.edu.um.atlas;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatalogSnapshotSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Reemplaza al cliente REST en los tests de sync: devuelve lo programado, falla o se frena a pedido. */
class FuenteDeSnapshotDePrueba implements CatalogSnapshotSource {

    private volatile CatalogSnapshot snapshot;
    private volatile RuntimeException falla;
    private volatile CountDownLatch freno;
    private volatile CountDownLatch entro;
    private final AtomicInteger pedidos = new AtomicInteger();

    void devolver(CatalogSnapshot snapshot) {
        this.snapshot = snapshot;
        this.falla = null;
    }

    void fallar(RuntimeException falla) {
        this.falla = falla;
    }

    /** El próximo pedido avisa por {@code entro} y espera hasta que se libere {@code freno}. */
    void frenar(CountDownLatch entro, CountDownLatch freno) {
        this.entro = entro;
        this.freno = freno;
    }

    int pedidos() {
        return pedidos.get();
    }

    void reiniciar() {
        snapshot = null;
        falla = null;
        freno = null;
        entro = null;
        pedidos.set(0);
    }

    @Override
    public CatalogSnapshot fetchSnapshot() {
        pedidos.incrementAndGet();
        CountDownLatch entro = this.entro;
        CountDownLatch freno = this.freno;
        if (entro != null) {
            entro.countDown();
        }
        if (freno != null) {
            try {
                freno.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (falla != null) {
            throw falla;
        }
        return snapshot;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracion {

        @Bean
        @Primary
        FuenteDeSnapshotDePrueba fuenteDeSnapshotDePrueba() {
            return new FuenteDeSnapshotDePrueba();
        }
    }
}
