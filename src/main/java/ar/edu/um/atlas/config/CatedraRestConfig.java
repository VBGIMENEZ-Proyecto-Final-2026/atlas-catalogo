package ar.edu.um.atlas.config;

import ar.edu.um.atlas.adapters.out.rest.CatedraRestSnapshotSource;
import ar.edu.um.atlas.application.port.out.CatalogSnapshotSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/** Arma el cliente REST de la cátedra: URL base, token, timeouts y reintentos acotados. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatedraProperties.class)
class CatedraRestConfig {

    @Bean
    CatalogSnapshotSource catalogSnapshotSource(CatedraProperties properties) {
        // Sin redirecciones: el token nunca se reenvía a otro destino.
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withTimeouts(properties.connectTimeout(), properties.readTimeout());
        RestClient restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .baseUrl(properties.baseUrl().strip())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.idToken().strip())
                .build();

        CatedraProperties.Retry retry = properties.retry();
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(retry.maxRetries())
                .delay(retry.delay())
                .multiplier(retry.multiplier())
                .maxDelay(retry.maxDelay())
                .predicate(CatedraRestSnapshotSource::isTransient)
                .build();
        return new CatedraRestSnapshotSource(restClient, new RetryTemplate(policy));
    }
}
