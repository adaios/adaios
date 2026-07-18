package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MemoryController unit tests.
 */
class MemoryControllerTest {

    @Test
    void getMemories_returnsOk() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.findByDate(any())).thenReturn(List.of());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryController(memService)).build();

        mvc.perform(get("/api/v1/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getMemories_withDateFilter() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.findByDate(any())).thenReturn(List.of(
                new Memory("m1", "r1", "summary", List.of("tag"), "neutral", false, null, LocalDateTime.now())
        ));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryController(memService)).build();

        mvc.perform(get("/api/v1/memory").param("date", "2026-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("m1"))
                .andExpect(jsonPath("$[0].summary").value("summary"));
    }

    @Test
    void getByRecordId_returnsMemory() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.findByRecordId("r1")).thenReturn(
                Optional.of(new Memory("m1", "r1", "summary", List.of("tag"), "positive", true, "buy more", LocalDateTime.now()))
        );

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryController(memService)).build();

        mvc.perform(get("/api/v1/memory/record/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("m1"))
                .andExpect(jsonPath("$.sentiment").value("positive"))
                .andExpect(jsonPath("$.actionable").value(true));
    }

    @Test
    void getByRecordId_notFound_returns404() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.findByRecordId("nonexistent")).thenReturn(Optional.empty());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryController(memService)).build();

        mvc.perform(get("/api/v1/memory/record/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMemories_wrongMethod_returns405() throws Exception {
        var memService = mock(MemoryService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryController(memService)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/memory"))
                .andExpect(status().isMethodNotAllowed());
    }
}
