package ar.edu.um.atlas.application.search;

import java.util.List;

/** Una página de resultados. Es propio para no exponer el formato interno de {@code Page} de Spring Data. */
public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
