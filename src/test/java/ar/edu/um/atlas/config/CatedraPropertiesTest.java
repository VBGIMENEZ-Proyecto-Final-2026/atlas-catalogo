package ar.edu.um.atlas.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Si falta una variable obligatoria, la aplicación no arranca y el mensaje indica cuál. */
class CatedraPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatedraRestConfig.class);

    @Test
    void conAmbasVariablesArranca() {
        runner.withPropertyValues("catedra.rest.base-url=http://catedra.test.invalid", "catedra.rest.id-token=t")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void sinUrlBaseNoArrancaYDiceCual() {
        runner.withPropertyValues("catedra.rest.base-url=", "catedra.rest.id-token=t")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_REST_BASE_URL"));
    }

    @Test
    void sinTokenNoArrancaYDiceCual() {
        runner.withPropertyValues("catedra.rest.base-url=http://catedra.test.invalid", "catedra.rest.id-token=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_ID_TOKEN"));
    }

    @Test
    void unaUrlBaseInvalidaNoArranca() {
        runner.withPropertyValues("catedra.rest.base-url=no-es-una-url", "catedra.rest.id-token=t")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_REST_BASE_URL"));
    }
}
