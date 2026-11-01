package ar.edu.um.atlas.adapters.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con {@code atlas.search.enabled=false} la búsqueda no se expone: las rutas responden 404. */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(properties = {"atlas.search.enabled=false", "atlas.sync.status-endpoint.enabled=false"})
class BusquedaApagadaTest {

    @Autowired MockMvc mockMvc;

    @Test
    void lasRutasDeBusquedaNoExistenSiSeApaga() throws Exception {
        mockMvc.perform(get("/api/professionals")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/professionals/1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/categories")).andExpect(status().isNotFound());
    }
}
