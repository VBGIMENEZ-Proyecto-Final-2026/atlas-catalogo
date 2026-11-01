package ar.edu.um.atlas.application.search;

import ar.edu.um.atlas.application.repository.ProfessionalCategoryRepository;
import ar.edu.um.atlas.application.repository.ProfessionalRepository;
import ar.edu.um.atlas.application.repository.WeeklyScheduleRepository;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

/**
 * Detalle de un profesional con todos sus horarios, habilitados o no (cada uno trae su propio estado). Un
 * profesional deshabilitado también se devuelve, con {@code enabled = false}: la copia local nunca borra.
 */
@Service
public class GetProfessionalDetail {

    private static final Comparator<WeeklySchedule> BY_DAY_AND_START =
            Comparator.comparing(WeeklySchedule::getDayOfWeek).thenComparing(WeeklySchedule::getStartTime)
                    .thenComparing(WeeklySchedule::getId);

    private final ProfessionalRepository professionals;
    private final ProfessionalCategoryRepository categories;
    private final WeeklyScheduleRepository schedules;

    public GetProfessionalDetail(ProfessionalRepository professionals, ProfessionalCategoryRepository categories,
                                 WeeklyScheduleRepository schedules) {
        this.professionals = professionals;
        this.categories = categories;
        this.schedules = schedules;
    }

    @Transactional(readOnly = true)
    public ProfessionalDetail get(long id) {
        Professional professional = professionals.findById(id).orElseThrow(() -> new ProfessionalNotFoundException(id));
        CategoryView category = CategoryViews.of(categories.findById(professional.getCategoryId())
                .orElseThrow(() -> new IllegalStateException("Falta la categoría de un profesional")));
        var horarios = schedules.findByProfessionalId(id).stream().sorted(BY_DAY_AND_START)
                .map(s -> new ScheduleView(s.getId(), s.getDayOfWeek(), s.getStartTime(), s.getEndTime(),
                        s.getSlotDurationMinutes(), s.isEnabled()))
                .toList();
        return new ProfessionalDetail(professional.getId(), professional.getFirstName(),
                professional.getLastName(), professional.isEnabled(), category, horarios);
    }
}
