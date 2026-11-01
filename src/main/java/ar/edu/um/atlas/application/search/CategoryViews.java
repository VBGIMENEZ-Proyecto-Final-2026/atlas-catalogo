package ar.edu.um.atlas.application.search;

import ar.edu.um.atlas.domain.ProfessionalCategory;

/** Traduce la entidad de categoría a su vista. */
final class CategoryViews {

    private CategoryViews() {
    }

    static CategoryView of(ProfessionalCategory category) {
        return new CategoryView(category.getId(), category.getName(), category.isEnabled());
    }
}
