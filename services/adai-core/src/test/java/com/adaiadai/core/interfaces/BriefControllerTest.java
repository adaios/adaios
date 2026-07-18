package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.BriefAppService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * BriefController unit tests.
 */
class BriefControllerTest {

    @Test
    void getBrief_returnsContent() throws Exception {
        var briefService = mock(BriefAppService.class);
        when(briefService.generateBrief()).thenReturn("Hello, today is a good day");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BriefController(briefService)).build();

        mvc.perform(get("/api/v1/brief"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Hello, today is a good day"));
    }

    @Test
    void getBrief_returnsValidJson() throws Exception {
        var briefService = mock(BriefAppService.class);
        when(briefService.generateBrief()).thenReturn("Morning brief");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BriefController(briefService)).build();

        mvc.perform(get("/api/v1/brief"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void getBrief_wrongMethod_returns405() throws Exception {
        var briefService = mock(BriefAppService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BriefController(briefService)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/brief"))
                .andExpect(status().isMethodNotAllowed());
    }
}
