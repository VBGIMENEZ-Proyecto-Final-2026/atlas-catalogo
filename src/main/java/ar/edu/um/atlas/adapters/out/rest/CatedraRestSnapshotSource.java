package ar.edu.um.atlas.adapters.out.rest;

import ar.edu.um.atlas.application.port.out.CatalogSnapshot;
import ar.edu.um.atlas.application.port.out.CatalogSnapshotSource;
import ar.edu.um.atlas.application.port.out.CatalogSourceException;
import ar.edu.um.atlas.application.port.out.CatedraCredentialRejectedException;
import ar.edu.um.atlas.application.port.out.CatedraInvalidResponseException;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Adaptador de salida del snapshot: llama a la cátedra con el {@code RestClient} recibido (que ya lleva
 * la URL base, el token y los timeouts), reintenta solo ante fallas transitorias y traduce los errores
 * por {@code status} y {@code code}. Nunca registra el token.
 */
public class CatedraRestSnapshotSource implements CatalogSnapshotSource {

    private static final Logger log = LoggerFactory.getLogger(CatedraRestSnapshotSource.class);

    private final CatedraSnapshotApi api;
    private final RetryTemplate retryTemplate;

    public CatedraRestSnapshotSource(RestClient restClient, RetryTemplate retryTemplate) {
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
                .createClient(CatedraSnapshotApi.class);
        this.retryTemplate = retryTemplate;
    }

    @Override
    public CatalogSnapshot fetchSnapshot() {
        SnapshotResponse response;
        try {
            response = retryTemplate.execute(api::snapshot);
        } catch (RetryException e) {
            throw translate(e.getLastException());
        }
        if (response == null) {
            throw new CatedraInvalidResponseException("La cátedra respondió sin cuerpo", null, null, null);
        }
        return SnapshotMapper.toSnapshot(response);
    }

    /** Solo se reintentan las fallas transitorias: error de red, timeout y {@code 503}. */
    public static boolean isTransient(Throwable t) {
        return t instanceof ResourceAccessException
                || (t instanceof HttpServerErrorException e && e.getStatusCode().value() == 503);
    }

    private CatalogSourceException translate(Throwable t) {
        if (t instanceof CatalogSourceException e) {
            return e;
        }
        if (t instanceof RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = codeOf(e);
            log.warn("La cátedra respondió HTTP {} (code={}) al pedir el snapshot", status, code);
            return translateStatus(e.getStatusCode(), code, e);
        }
        if (t instanceof ResourceAccessException e) {
            log.warn("No se pudo conectar con la cátedra al pedir el snapshot: {}", e.getClass().getSimpleName());
            return new CatedraUnavailableException(
                    "La cátedra no está disponible (error de red o timeout)", null, null, e);
        }
        if (t instanceof RestClientException e) {
            return new CatedraInvalidResponseException(
                    "La respuesta de la cátedra no se pudo interpretar", null, null, e);
        }
        throw new IllegalStateException("Falla inesperada al pedir el snapshot", t);
    }

    private static CatalogSourceException translateStatus(HttpStatusCode statusCode, String code, Throwable cause) {
        int status = statusCode.value();
        if (status == 401 || status == 403) {
            return new CatedraCredentialRejectedException(
                    "La cátedra rechazó la credencial (HTTP " + status + "): el token es inválido o venció; "
                            + "se rota en el .env", status, code, cause);
        }
        if (statusCode.is5xxServerError()) {
            return new CatedraUnavailableException(
                    "La cátedra no está disponible (HTTP " + status + ")", status, code, cause);
        }
        return new CatedraInvalidResponseException(
                "Respuesta inesperada de la cátedra (HTTP " + status + ")", status, code, cause);
    }

    private static String codeOf(RestClientResponseException e) {
        try {
            ProblemJson problem = e.getResponseBodyAs(ProblemJson.class);
            return problem == null ? null : problem.code();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
