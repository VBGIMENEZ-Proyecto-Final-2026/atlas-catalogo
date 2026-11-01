package ar.edu.um.atlas.adapters.in.web;

import ar.edu.um.atlas.application.search.CategoryView;
import ar.edu.um.atlas.application.search.GetProfessionalDetail;
import ar.edu.um.atlas.application.search.ListCategories;
import ar.edu.um.atlas.application.search.PageResult;
import ar.edu.um.atlas.application.search.ProfessionalDetail;
import ar.edu.um.atlas.application.search.ProfessionalSearchCriteria;
import ar.edu.um.atlas.application.search.ProfessionalSummary;
import ar.edu.um.atlas.application.search.SearchProfessionals;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.util.List;

/**
 * Búsqueda de profesionales sobre la copia local. Solo traduce parámetros a criterios y resultados a JSON: la
 * lógica está en los casos de uso de {@code application.search}, que nunca consultan a la cátedra.
 *
 * <p>Está apagado por defecto ({@code atlas.search.enabled=false}): hasta que exista la seguridad base no se
 * expone, y apagado las rutas responden {@code 404} como si no existieran. Los cuerpos son las vistas de
 * {@code application.search}.
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "atlas.search.enabled", havingValue = "true")
class ProfessionalsController {

    private final SearchProfessionals searchProfessionals;
    private final GetProfessionalDetail getProfessionalDetail;
    private final ListCategories listCategories;

    ProfessionalsController(SearchProfessionals searchProfessionals, GetProfessionalDetail getProfessionalDetail,
                            ListCategories listCategories) {
        this.searchProfessionals = searchProfessionals;
        this.getProfessionalDetail = getProfessionalDetail;
        this.listCategories = listCategories;
    }

    @GetMapping("/professionals")
    PageResult<ProfessionalSummary> search(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean available,
            @RequestParam(required = false) DayOfWeek dayOfWeek,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + ProfessionalSearchCriteria.DEFAULT_SIZE) int size) {
        return searchProfessionals.search(
                new ProfessionalSearchCriteria(categoryId, name, enabled, available, dayOfWeek, page, size));
    }

    @GetMapping("/professionals/{id}")
    ProfessionalDetail detail(@PathVariable long id) {
        return getProfessionalDetail.get(id);
    }

    @GetMapping("/categories")
    List<CategoryView> categories(@RequestParam(required = false) Boolean enabled) {
        return listCategories.list(enabled);
    }
}
