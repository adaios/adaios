package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.search.SearchResult;
import com.adaiadai.core.kernel.search.SearchService;
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
 * SearchController — 全文搜索接口测试。
 */
class SearchControllerTest {

    private MockMvc buildMvc(SearchService searchService) {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(new SearchController(searchService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void search_returnsResultsAndTotal() throws Exception {
        SearchService searchService = mock(SearchService.class);
        when(searchService.search(any(), any())).thenReturn(List.of(
                new SearchResult("rec_1", "note", "标题", "内容片段", List.of("交易"), LocalDateTime.now())));
        MockMvc mvc = buildMvc(searchService);

        mvc.perform(get("/api/v1/search").param("q", "交易"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].id").value("rec_1"))
                .andExpect(jsonPath("$.results[0].tags[0]").value("交易"));
    }

    @Test
    void search_forwardsQueryAndUserId() throws Exception {
        SearchService searchService = mock(SearchService.class);
        when(searchService.search(any(), any())).thenReturn(List.of());
        MockMvc mvc = buildMvc(searchService);

        mvc.perform(get("/api/v1/search").header("X-User-Id", "alice").param("q", "复盘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        verify(searchService).search("alice", "复盘");
    }
}
