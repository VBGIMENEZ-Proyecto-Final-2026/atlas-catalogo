package ar.edu.um.atlas.application.search;

import ar.edu.um.atlas.domain.Professional;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/** Filtros simples de la búsqueda como {@link Specification}, para combinarlos sin una consulta por cada uno. */
final class ProfessionalSpecifications {

    private static final char ESCAPE = '\\';

    private ProfessionalSpecifications() {
    }

    static Specification<Professional> inCategory(long categoryId) {
        return (professional, query, cb) -> cb.equal(professional.get("categoryId"), categoryId);
    }

    static Specification<Professional> enabled(boolean enabled) {
        return (professional, query, cb) -> cb.equal(professional.get("enabled"), enabled);
    }

    /**
     * El texto está contenido en "nombre apellido", sin distinguir mayúsculas (decisión E: {@code ILIKE}
     * sin índice). Se busca en el nombre completo para que "Ana Gómez" encuentre al profesional, y no solo
     * "Ana" o "Gómez". Los comodines de {@code LIKE} que escriba el cliente se tratan como texto común.
     */
    static Specification<Professional> nameContains(String text) {
        String pattern = "%" + escape(text.toLowerCase(Locale.ROOT)) + "%";
        return (professional, query, cb) -> cb.like(
                cb.lower(cb.concat(cb.concat(professional.<String>get("firstName"), " "),
                        professional.<String>get("lastName"))),
                pattern, ESCAPE);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
