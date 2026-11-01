package ar.edu.um.atlas.adapters.in.web;

import ar.edu.um.atlas.application.search.InvalidSearchException;
import ar.edu.um.atlas.application.search.ProfessionalNotFoundException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errores de la búsqueda como {@code application/problem+json}, con el formato de la referencia de integración
 * (sección 13): {@code 400 BUSQUEDA_INVALIDA}, {@code 404 PROFESIONAL_NO_ENCONTRADO} y
 * {@code 503 CATALOGO_NO_DISPONIBLE}. Los mensajes son fijos: no repiten el valor recibido ni la causa
 * técnica, que va solo al log y de ella solo la clase.
 */
@RestControllerAdvice(assignableTypes = ProfessionalsController.class)
@ConditionalOnProperty(name = "atlas.search.enabled", havingValue = "true")
class ProfessionalsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProfessionalsExceptionHandler.class);

    @ExceptionHandler(InvalidSearchException.class)
    ResponseEntity<ProblemDetail> criteriosInvalidos(InvalidSearchException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "BUSQUEDA_INVALIDA", e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> parametroConTipoIncorrecto(MethodArgumentTypeMismatchException e,
                                                              HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "BUSQUEDA_INVALIDA",
                "El parámetro " + e.getName() + " tiene un valor inválido", request);
    }

    @ExceptionHandler(ProfessionalNotFoundException.class)
    ResponseEntity<ProblemDetail> noEncontrado(ProfessionalNotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "PROFESIONAL_NO_ENCONTRADO", "No existe el profesional pedido", request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> baseNoDisponible(RuntimeException e, HttpServletRequest request) {
        log.warn("No se pudo leer el catálogo local ({})", e.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "CATALOGO_NO_DISPONIBLE", null, request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                         HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        if (detail != null) {
            problem.setDetail(detail);
        }
        problem.setProperty("message", "error.http." + status.value());
        problem.setProperty("path", request.getRequestURI());
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
