package ar.edu.um.atlas.adapters.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Si la base de datos no responde, el estado se devuelve como {@code application/problem+json} con
 * {@code 503} y el código {@code STATUS_NO_DISPONIBLE} (formato de la referencia de integración, sección 13).
 * Sin {@code detail}: la causa técnica va solo al log, y de ella solo la clase.
 */
@RestControllerAdvice(assignableTypes = SyncStatusController.class)
@ConditionalOnProperty(name = "atlas.sync.status-endpoint.enabled", havingValue = "true")
class SyncStatusExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SyncStatusExceptionHandler.class);

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> baseNoDisponible(RuntimeException e, HttpServletRequest request) {
        log.warn("No se pudo leer el estado de la sincronización ({})", e.getClass().getSimpleName());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Service Unavailable");
        problem.setProperty("message", "error.http.503");
        problem.setProperty("path", request.getRequestURI());
        problem.setProperty("code", "STATUS_NO_DISPONIBLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
