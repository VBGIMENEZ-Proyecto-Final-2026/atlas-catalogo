package ar.edu.um.atlas.application.search;

/** Un profesional en una página de resultados. */
public record ProfessionalSummary(Long id, String firstName, String lastName, boolean enabled, CategoryView category) {
}
