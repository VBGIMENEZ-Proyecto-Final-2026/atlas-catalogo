package ar.edu.um.atlas;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Reglas de dependencia del ADR 0003. Todos los paquetes de las reglas ya tienen clases, así que ninguna
 * usa {@code allowEmptyShould(true)}.
 */
@AnalyzeClasses(packages = "ar.edu.um.atlas", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule dominioSinFrameworks = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..adapters..", "..config..", "org.springframework..");

    @ArchTest
    static final ArchRule aplicacionSinAdaptadores = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapters..", "..config..", "org.springframework.web..",
                    "org.springframework.kafka..", "org.springframework.data.redis..",
                    "tools.jackson..", "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule adaptadoresAislados = slices()
            .matching("..adapters.(*)..")
            .should().notDependOnEachOther();

    @ArchTest
    static final ArchRule redisNoDependeDeRestNiDeEntrada = noClasses()
            .that().resideInAPackage("..adapters.out.redis..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapters.out.rest..", "..adapters.in..");

    @ArchTest
    static final ArchRule restNoDependeDeRedis = noClasses()
            .that().resideInAPackage("..adapters.out.rest..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapters.out.redis..", "..adapters.in..");

    @ArchTest
    static final ArchRule planificadorPuro = noClasses()
            .that().resideInAPackage("..application.sync.plan..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "..adapters..", "..config..", "..application.repository..",
                    "jakarta.persistence..");

    @ArchTest
    static final ArchRule kafkaNoDependeDeOtrosAdaptadoresNiDeConfig = noClasses()
            .that().resideInAPackage("..adapters.in.kafka..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapters.out..", "..config..",
                    "..adapters.in.startup..", "..adapters.in.schedule..");
}
