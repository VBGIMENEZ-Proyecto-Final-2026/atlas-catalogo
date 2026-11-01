package ar.edu.um.atlas.application.search;

import java.time.DayOfWeek;

/**
 * Parámetros de la búsqueda de profesionales. Todos los filtros son opcionales y se combinan con "y".
 *
 * <ul>
 *   <li>{@code categoryId}: profesionales de esa categoría.</li>
 *   <li>{@code name}: texto contenido en el nombre y apellido, sin distinguir mayúsculas (decisión E).</li>
 *   <li>{@code enabled}: filtro literal por el estado habilitado del profesional (decisión F).</li>
 *   <li>{@code available}: {@code true} deja a los disponibles y {@code false} a los que no lo están
 *       ({@link AvailabilityFilter}), independiente de {@code enabled}.</li>
 *   <li>{@code dayOfWeek}: refina {@code available} a un día; sin {@code available} no tiene sentido y se
 *       rechaza.</li>
 * </ul>
 *
 * El orden es fijo (apellido, nombre, ID): el cliente no elige columnas.
 */
public record ProfessionalSearchCriteria(
        Long categoryId,
        String name,
        Boolean enabled,
        Boolean available,
        DayOfWeek dayOfWeek,
        int page,
        int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_NAME_LENGTH = 100;

    public ProfessionalSearchCriteria {
        if (page < 0) {
            throw new InvalidSearchException("page debe ser 0 o mayor");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidSearchException("size debe estar entre 1 y " + MAX_SIZE);
        }
        if (name != null) {
            name = name.strip();
            if (name.isEmpty()) {
                name = null;
            } else if (name.length() > MAX_NAME_LENGTH) {
                throw new InvalidSearchException("name no puede superar " + MAX_NAME_LENGTH + " caracteres");
            }
        }
        if (dayOfWeek != null && available == null) {
            throw new InvalidSearchException("dayOfWeek requiere available");
        }
    }
}
