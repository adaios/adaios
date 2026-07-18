package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TimelineAppService;
import com.adaiadai.core.kernel.timeline.TimelineEntry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TimelineController unit tests.
 */
class TimelineControllerTest {

    @Test
    void getTimeline_returnsOk() throws Exception {
        var timelineService = mock(TimelineAppService.class);
        when(timelineService.getRecent(anyInt())).thenReturn(List.of());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TimelineController(timelineService)).build();

        mvc.perform(get("/api/v1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getTimeline_withTypeFilter() throws Exception {
        var timelineService = mock(TimelineAppService.class);
        when(timelineService.getTimelineByType("note")).thenReturn(List.of());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TimelineController(timelineService)).build();

        mvc.perform(get("/api/v1/timeline").param("type", "note"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getTimeline_returnsEntries() throws Exception {
        var timelineService = mock(TimelineAppService.class);
        when(timelineService.getRecent(anyInt())).thenReturn(List.of(
                new TimelineEntry("r1", "note", "title", List.of("tag"), LocalDateTime.of(2026, 7, 18, 14, 30))
        ));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TimelineController(timelineService)).build();

        mvc.perform(get("/api/v1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("r1"))
                .andExpect(jsonPath("$[0].type").value("note"))
                .andExpect(jsonPath("$[0].tags[0]").value("tag"));
    }

    @Test
    void getTimeline_wrongMethod_returns405() throws Exception {
        var timelineService = mock(TimelineAppService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TimelineController(timelineService)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/timeline"))
                .andExpect(status().isMethodNotAllowed());
    }
}
