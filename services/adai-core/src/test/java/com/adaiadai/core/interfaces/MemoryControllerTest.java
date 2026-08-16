package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MemoryController unit tests（记忆查询；重建/修正维护端点已迁至 AdminController，REVIEW P-be-01）。
 */
class MemoryControllerTest {

    private MemoryController controllerWith() {
        return new MemoryController(mock(MemoryService.class));
    }

    private MockMvc plainMvc(MemoryService memService) {
        return MockMvcBuilders.standaloneSetup(new MemoryController(memService)).build();
    }

    @Test
    void getMemories_returnsOk() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.findByDate(any(), any())).thenReturn(List.of());

        MockMvc mvc = plainMvc(memService);

        mvc.perform(get("/api/v1/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getMemories_withDateFilter() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.findByDate(any(), any())).thenReturn(List.of(
                new Memory("m1", "r1", Memory.KIND_INSIGHT, "summary", null, null, List.of("tag"), "neutral", false, null, LocalDateTime.now(), null, false, null, null, null)
        ));

        MockMvc mvc = plainMvc(memService);

        mvc.perform(get("/api/v1/memory").param("date", "2026-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("m1"))
                .andExpect(jsonPath("$[0].summary").value("summary"));
    }

    @Test
    void getByRecordId_returnsMemory() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.findByRecordId(any(),any())).thenReturn(
                Optional.of(new Memory("m1", "r1", Memory.KIND_INSIGHT, "summary", null, null, List.of("tag"), "positive", true, "buy more", LocalDateTime.now(), null, false, null, null, null))
        );

        MockMvc mvc = plainMvc(memService);

        mvc.perform(get("/api/v1/memory/record/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("m1"))
                .andExpect(jsonPath("$.sentiment").value("positive"))
                .andExpect(jsonPath("$.actionable").value(true));
    }

    @Test
    void getByRecordId_notFound_returns404() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.findByRecordId(any(),any())).thenReturn(Optional.empty());

        MockMvc mvc = plainMvc(memService);

        mvc.perform(get("/api/v1/memory/record/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void markDone_returnsOk() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.markDone(any(),any())).thenReturn(true);
        MockMvc mvc = plainMvc(memService);

        mvc.perform(patch("/api/v1/memory/m1/done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void markDone_notFound_returns404() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.markDone(any(),any())).thenReturn(false);
        MockMvc mvc = plainMvc(memService);

        mvc.perform(patch("/api/v1/memory/nonexistent/done"))
                .andExpect(status().isNotFound());
    }

    // ── dates / count（查询端点保留）──

    private MockMvc jsonMvc(MemoryService memService) {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(new MemoryController(memService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void getDates_returnsDateList() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.findAllDates(any())).thenReturn(List.of(LocalDate.of(2026, 8, 2)));
        MockMvc mvc = jsonMvc(memService);

        mvc.perform(get("/api/v1/memory/dates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("2026-08-02"));
    }

    @Test
    void getCount_returnsNumber() throws Exception {
        var memService = mock(MemoryService.class);
        when(memService.count(any())).thenReturn(5L);
        MockMvc mvc = jsonMvc(memService);

        mvc.perform(get("/api/v1/memory/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5));
    }
}
