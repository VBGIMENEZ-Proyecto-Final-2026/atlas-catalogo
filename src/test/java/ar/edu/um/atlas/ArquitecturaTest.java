package ar.edu.um.atlas;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Reglas de dependencia del ADR 0003. {@code allowEmptyShould(true)} se mantiene solo donde
 * todavía no hay clases (adaptadores); se quita cuando las haya.
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
                    "org.springframework.kafka..", "org.springframework.data.redis..");

    @ArchTest
    static final ArchRule adaptadoresAislados = slices()
            .matching("..adapters.(*)..")
            .should().notDependOnEachOther()
            .allowEmptyShould(true);
}
