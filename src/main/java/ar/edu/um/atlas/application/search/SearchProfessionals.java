package ar.edu.um.atlas.application.search;

import ar.edu.um.atlas.application.repository.ProfessionalCategoryRepository;
import ar.edu.um.atlas.application.repository.ProfessionalRepository;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Busca profesionales por categoría, nombre, estado habilitado y disponibilidad, siempre sobre la copia local:
 * no depende de ningún puerto hacia la cátedra (lo comprueba ArchUnit), así que una búsqueda nunca la consulta
 * (enunciado 6.2).
 */
@Service
public class SearchProfessionals {

    private static final Sort ORDER = Sort.by("lastName", "firstName", "id");

    private final ProfessionalRepository professionals;
    private final ProfessionalCategoryRepository categories;
    private final AvailabilityFilter availability;

    public SearchProfessionals(ProfessionalRepository professionals, ProfessionalCategoryRepository categories,
                               AvailabilityFilter availability) {
        this.professionals = professionals;
        this.categories = categories;
        this.availability = availability;
    }

    @Transactional(readOnly = true)
    public PageResult<ProfessionalSummary> search(ProfessionalSearchCriteria criteria) {
        Specification<Professional> filter = Specification.unrestricted();
        if (criteria.categoryId() != null) {
            filter = filter.and(ProfessionalSpecifications.inCategory(criteria.categoryId()));
        }
        if (criteria.name() != null) {
            filter = filter.and(ProfessionalSpecifications.nameContains(criteria.name()));
        }
        if (criteria.enabled() != null) {
            filter = filter.and(ProfessionalSpecifications.enabled(criteria.enabled()));
        }
        if (criteria.available() != null) {
            Specification<Professional> available = availability.available(criteria.dayOfWeek());
            filter = filter.and(criteria.available() ? available : Specification.not(available));
        }

        Page<Professional> page = professionals.findAll(filter,
                PageRequest.of(criteria.page(), criteria.size(), ORDER));

        Map<Long, ProfessionalCategory> categoryById = categories
                .findAllById(page.getContent().stream().map(Professional::getCategoryId).distinct().toList())
                .stream().collect(Collectors.toMap(ProfessionalCategory::getId, Function.identity()));
        List<ProfessionalSummary> content = page.getContent().stream()
                .map(p -> new ProfessionalSummary(p.getId(), p.getFirstName(), p.getLastName(), p.isEnabled(),
                        CategoryViews.of(categoryById.get(p.getCategoryId()))))
                .toList();
        return new PageResult<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
