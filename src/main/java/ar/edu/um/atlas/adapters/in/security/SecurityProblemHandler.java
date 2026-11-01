package ar.edu.um.atlas.adapters.in.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Respuestas de seguridad como {@code application/problem+json} con el formato de la referencia de integración
 * (sección 13): {@code 401 NO_AUTENTICADO} sin token o con un token inválido, vencido o mal firmado, y
 * {@code 403 SIN_PERMISO} con un token válido que no alcanza para el recurso.
 *
 * <p>No dice por qué se rechazó el token (firma, vencimiento, algoritmo): esa pista sirve a quien ataca, y quien
 * lo usa legítimamente la recibe por el {@code 401} y por el log. Tampoco repite el rol que faltaba. Cada
 * rechazo queda en el log con el método y la ruta, y en el {@code 403} con el usuario, nunca con el token.
 */
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemHandler.class);

    private final JsonMapper mapper;

    public SecurityProblemHandler(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        log.warn("Acceso rechazado: 401 {} {} ({})", request.getMethod(), request.getRequestURI(),
                exception.getClass().getSimpleName());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, request, HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        log.warn("Acceso rechazado: 403 {} {} usuario={}", request.getMethod(), request.getRequestURI(),
                authentication == null ? "-" : authentication.getName());
        write(response, request, HttpStatus.FORBIDDEN, "SIN_PERMISO");
    }

    private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String code)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("message", "error.http." + status.value());
        problem.put("path", request.getRequestURI());
        problem.put("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
