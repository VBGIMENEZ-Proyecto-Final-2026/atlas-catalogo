package ar.edu.um.atlas.adapters.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sin {@code atlas.search.enabled=true} la búsqueda no se expone: las rutas responden 404. */
@WebMvcTest
class BusquedaApagadaPorDefectoTest {

    @Autowired MockMvc mockMvc;

    @Test
    void lasRutasDeBusquedaNoExistenSinHabilitarlas() throws Exception {
        mockMvc.perform(get("/api/professionals")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/professionals/1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/categories")).andExpect(status().isNotFound());
    }
}
