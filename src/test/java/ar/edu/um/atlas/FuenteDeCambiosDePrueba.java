package ar.edu.um.atlas;

import ar.edu.um.atlas.application.port.out.CatalogChangeSource;
import ar.edu.um.atlas.application.port.out.CatalogMetadata;
import ar.edu.um.atlas.application.port.out.VersionChanges;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reemplaza al cliente Redis en los tests de sync: se programa la metadata, los cambios de cada versión y el
 * estado vigente de las entidades (los Hashes de la cátedra), y se pueden inyectar fallas y frenos.
 */
class FuenteDeCambiosDePrueba implements CatalogChangeSource {

    private volatile List<Optional<CatalogMetadata>> metadatas = List.of(Optional.empty());
    private final AtomicInteger lecturasDeMetadata = new AtomicInteger();
    private volatile RuntimeException fallaDeMetadata;
    private volatile RuntimeException fallaDeCambios;
    private volatile CountDownLatch entro;
    private volatile CountDownLatch freno;

    private final Map<Long, VersionChanges> cambios = new ConcurrentHashMap<>();
    private final Map<Long, ProfessionalCategory> categorias = new ConcurrentHashMap<>();
    private final Map<Long, Professional> profesionales = new ConcurrentHashMap<>();
    private final Map<Long, WeeklySchedule> horarios = new ConcurrentHashMap<>();
    private final List<Long> versionesPedidas = new CopyOnWriteArrayList<>();

    /** La metadata de cada lectura, en orden; la última se repite. */
    @SafeVarargs
    final void metadata(Optional<CatalogMetadata>... secuencia) {
        this.metadatas = List.of(secuencia);
        this.lecturasDeMetadata.set(0);
    }

    void metadata(long current, long oldest) {
        metadata(Optional.of(new CatalogMetadata(current, oldest, null)));
    }

    void sinMetadata() {
        metadata(Optional.empty());
    }

    void fallarMetadata(RuntimeException falla) {
        this.fallaDeMetadata = falla;
    }

    void fallarCambios(RuntimeException falla) {
        this.fallaDeCambios = falla;
    }

    /** El próximo pedido de cambios avisa por {@code entro} y espera hasta que se libere {@code freno}. */
    void frenarCambios(CountDownLatch entro, CountDownLatch freno) {
        this.entro = entro;
        this.freno = freno;
    }

    /** Publica la versión: sus IDs afectados y el estado vigente de las entidades. */
    void publicar(long version, List<ProfessionalCategory> cats, List<Professional> pros,
                  List<WeeklySchedule> scheds) {
        cats.forEach(c -> categorias.put(c.getId(), c));
        pros.forEach(p -> profesionales.put(p.getId(), p));
        scheds.forEach(s -> horarios.put(s.getId(), s));
        cambios.put(version, new VersionChanges(version,
                cats.stream().map(ProfessionalCategory::getId).toList(),
                pros.stream().map(Professional::getId).toList(),
                scheds.stream().map(WeeklySchedule::getId).toList()));
    }

    /** Publica una versión que menciona IDs que no están en los Hashes (el contrato no lo prevé). */
    void publicarSoloIds(long version, List<Long> cats, List<Long> pros, List<Long> scheds) {
        cambios.put(version, new VersionChanges(version, cats, pros, scheds));
    }

    List<Long> versionesPedidas() {
        return List.copyOf(versionesPedidas);
    }

    int lecturasDeMetadata() {
        return lecturasDeMetadata.get();
    }

    void reiniciar() {
        metadatas = List.of(Optional.empty());
        lecturasDeMetadata.set(0);
        fallaDeMetadata = null;
        fallaDeCambios = null;
        entro = null;
        freno = null;
        cambios.clear();
        categorias.clear();
        profesionales.clear();
        horarios.clear();
        versionesPedidas.clear();
    }

    @Override
    public Optional<CatalogMetadata> fetchMetadata() {
        // Cuenta también las lecturas que fallan: sirve para contar reintentos.
        int lectura = lecturasDeMetadata.getAndIncrement();
        if (fallaDeMetadata != null) {
            throw fallaDeMetadata;
        }
        List<Optional<CatalogMetadata>> secuencia = metadatas;
        return secuencia.get(Math.min(lectura, secuencia.size() - 1));
    }

    @Override
    public Optional<VersionChanges> fetchChanges(long version) {
        versionesPedidas.add(version);
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
        if (fallaDeCambios != null) {
            throw fallaDeCambios;
        }
        return Optional.ofNullable(cambios.get(version));
    }

    @Override
    public List<ProfessionalCategory> fetchCategories(Collection<Long> ids) {
        return presentes(ids, categorias);
    }

    @Override
    public List<Professional> fetchProfessionals(Collection<Long> ids) {
        return presentes(ids, profesionales);
    }

    @Override
    public List<WeeklySchedule> fetchWeeklySchedules(Collection<Long> ids) {
        return presentes(ids, horarios);
    }

    private static <T> List<T> presentes(Collection<Long> ids, Map<Long, T> hash) {
        List<T> result = new ArrayList<>();
        for (Long id : ids) {
            T value = hash.get(id);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracion {

        @Bean
        @Primary
        FuenteDeCambiosDePrueba fuenteDeCambiosDePrueba() {
            return new FuenteDeCambiosDePrueba();
        }
    }
}
