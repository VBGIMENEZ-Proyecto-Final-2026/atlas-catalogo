package ar.edu.um.atlas.config;

import ar.edu.um.atlas.adapters.out.redis.CatedraRedisChangeSource;
import ar.edu.um.atlas.application.port.out.CatalogChangeSource;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Arma el cliente Redis de la cátedra con usuario y contraseña (ACL), timeouts y reintentos acotados. No usa
 * {@code spring.data.redis.*}: las propiedades son las propias de {@link CatedraRedisProperties}. La conexión
 * es perezosa, así que la aplicación arranca aunque Redis no responda.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatedraRedisProperties.class)
class CatedraRedisConfig {

    @Bean
    RedisConnectionFactory catedraRedisConnectionFactory(CatedraRedisProperties properties) {
        RedisStandaloneConfiguration server =
                new RedisStandaloneConfiguration(properties.host().strip(), properties.port());
        server.setUsername(properties.username().strip());
        server.setPassword(RedisPassword.of(properties.password()));

        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(properties.commandTimeout())
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(properties.connectTimeout()).build())
                        .build())
                .build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean
    CatalogChangeSource catalogChangeSource(RedisConnectionFactory catedraRedisConnectionFactory,
                                            CatedraRedisProperties properties) {
        CatedraRedisProperties.Retry retry = properties.retry();
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(retry.maxRetries())
                .delay(retry.delay())
                .multiplier(retry.multiplier())
                .maxDelay(retry.maxDelay())
                .predicate(CatedraRedisChangeSource::isTransient)
                .build();
        return new CatedraRedisChangeSource(new StringRedisTemplate(catedraRedisConnectionFactory),
                new RetryTemplate(policy));
    }
}
