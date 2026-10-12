package ar.edu.um.atlas.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Si falta una variable obligatoria de Redis, la aplicación no arranca y el mensaje indica cuál. */
class CatedraRedisPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatedraRedisConfig.class);

    private static final String[] COMPLETAS = {
            "catedra.redis.host=redis.test.invalid", "catedra.redis.port=6379",
            "catedra.redis.username=usuario", "catedra.redis.password=clave-secreta"};

    @Test
    void conTodasLasVariablesArrancaSinConectarse() {
        runner.withPropertyValues(COMPLETAS).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void sinHostNoArrancaYDiceCual() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.redis.host=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_REDIS_HOST"));
    }

    @Test
    void sinPuertoONoValidoNoArrancaYDiceCual() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.redis.port=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_REDIS_PORT"));
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.redis.port=70000")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_REDIS_PORT"));
    }

    @Test
    void sinUsuarioNoArrancaYDiceCual() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.redis.username=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_REDIS_USERNAME"));
    }

    @Test
    void sinContrasenaNoArrancaYDiceCual() {
        runner.withPropertyValues(COMPLETAS).withPropertyValues("catedra.redis.password=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("CATEDRA_REDIS_PASSWORD"));
    }

    @Test
    void laContrasenaNoApareceEnToString() {
        runner.withPropertyValues(COMPLETAS).run(context -> {
            CatedraRedisProperties propiedades = context.getBean(CatedraRedisProperties.class);
            assertThat(propiedades.toString()).doesNotContain("clave-secreta").contains("password=***");
        });
    }
}
