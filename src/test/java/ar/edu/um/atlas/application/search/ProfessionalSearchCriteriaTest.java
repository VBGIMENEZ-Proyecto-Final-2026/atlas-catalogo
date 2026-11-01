package ar.edu.um.atlas.application.search;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validación de los parámetros de la búsqueda, sin Spring ni base de datos. */
class ProfessionalSearchCriteriaTest {

    private static ProfessionalSearchCriteria criterios(String nombre, Boolean disponible, DayOfWeek dia, int page, int size) {
        return new ProfessionalSearchCriteria(null, nombre, null, disponible, dia, page, size);
    }

    @Test
    void sinFiltrosYConTamanoPorDefectoEsValido() {
        var c = criterios(null, null, null, 0, ProfessionalSearchCriteria.DEFAULT_SIZE);
        assertThat(c.name()).isNull();
        assertThat(c.size()).isEqualTo(20);
    }

    @Test
    void elTamanoDePaginaDebeEstarEntre1YElMaximo() {
        assertThatThrownBy(() -> criterios(null, null, null, 0, 0)).isInstanceOf(InvalidSearchException.class);
        assertThatThrownBy(() -> criterios(null, null, null, 0, ProfessionalSearchCriteria.MAX_SIZE + 1))
                .isInstanceOf(InvalidSearchException.class);
        assertThat(criterios(null, null, null, 0, 1).size()).isEqualTo(1);
        assertThat(criterios(null, null, null, 0, ProfessionalSearchCriteria.MAX_SIZE).size()).isEqualTo(100);
    }

    @Test
    void laPaginaNoPuedeSerNegativa() {
        assertThatThrownBy(() -> criterios(null, null, null, -1, 20)).isInstanceOf(InvalidSearchException.class);
    }

    @Test
    void elNombreSeRecortaYEnBlancoEquivaleANoFiltrar() {
        assertThat(criterios("  ana  ", null, null, 0, 20).name()).isEqualTo("ana");
        assertThat(criterios("   ", null, null, 0, 20).name()).isNull();
        assertThat(criterios("", null, null, 0, 20).name()).isNull();
    }

    @Test
    void elNombreTieneUnLargoMaximo() {
        assertThat(criterios("a".repeat(100), null, null, 0, 20).name()).hasSize(100);
        assertThatThrownBy(() -> criterios("a".repeat(101), null, null, 0, 20))
                .isInstanceOf(InvalidSearchException.class);
    }

    @Test
    void elDiaSoloTieneSentidoJuntoAlFiltroDeDisponibilidad() {
        assertThatThrownBy(() -> criterios(null, null, DayOfWeek.MONDAY, 0, 20))
                .isInstanceOf(InvalidSearchException.class).hasMessage("dayOfWeek requiere available");
        assertThat(criterios(null, true, DayOfWeek.MONDAY, 0, 20).dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(criterios(null, false, DayOfWeek.MONDAY, 0, 20).available()).isFalse();
    }

    @Test
    void elMensajeDeErrorNoRepiteElValorRecibido() {
        assertThatThrownBy(() -> criterios("x".repeat(500), null, null, 0, 20))
                .hasMessageNotContaining("xxxxx");
    }
}
