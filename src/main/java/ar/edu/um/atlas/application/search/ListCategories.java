package ar.edu.um.atlas.application.search;

import ar.edu.um.atlas.application.repository.ProfessionalCategoryRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Categorías de la copia local, para que quien busca pueda elegir una al filtrar. */
@Service
public class ListCategories {

    private final ProfessionalCategoryRepository categories;

    public ListCategories(ProfessionalCategoryRepository categories) {
        this.categories = categories;
    }

    /** @param enabled si no es {@code null}, solo las categorías con ese estado */
    @Transactional(readOnly = true)
    public List<CategoryView> list(Boolean enabled) {
        return categories.findAll(Sort.by("name", "id")).stream()
                .filter(c -> enabled == null || c.isEnabled() == enabled)
                .map(CategoryViews::of)
                .toList();
    }
}
