package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TagIndexController — 标签统计接口测试。
 */
class TagIndexControllerTest {

    private MockMvc buildMvc(TagIndexService tagIndexService) {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(new TagIndexController(tagIndexService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void getTags_returnsListAndTotal() throws Exception {
        TagIndexService tagIndexService = mock(TagIndexService.class);
        when(tagIndexService.getAllTags(any())).thenReturn(List.of(
                new TagIndexService.TagSummary("交易", 3, LocalDateTime.now()),
                new TagIndexService.TagSummary("复盘", 1, LocalDateTime.now())));
        MockMvc mvc = buildMvc(tagIndexService);

        mvc.perform(get("/api/v1/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.tags[0].name").value("交易"))
                .andExpect(jsonPath("$.tags[0].count").value(3));
    }

    @Test
    void getTags_forwardsUserId() throws Exception {
        TagIndexService tagIndexService = mock(TagIndexService.class);
        when(tagIndexService.getAllTags(any())).thenReturn(List.of());
        MockMvc mvc = buildMvc(tagIndexService);

        mvc.perform(get("/api/v1/tags").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        verify(tagIndexService).getAllTags("alice");
    }
}
