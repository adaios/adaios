package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.FeedAppService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FeedController unit tests.
 */
class FeedControllerTest {

    @Test
    void getFeed_returnsOk() throws Exception {
        var feedService = mock(FeedAppService.class);
        when(feedService.getFeed(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FeedAppService.FeedResponse(List.of(), 0, false));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();

        mvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.totalToday").value(0));
    }

    @Test
    void getFeed_withDateParam() throws Exception {
        var feedService = mock(FeedAppService.class);
        when(feedService.getFeed(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FeedAppService.FeedResponse(List.of(), 2, false));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();

        mvc.perform(get("/api/v1/feed").param("date", "2026-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalToday").value(2));
    }

    @Test
    void getFeed_withPaginationParams() throws Exception {
        var feedService = mock(FeedAppService.class);
        when(feedService.getFeed(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FeedAppService.FeedResponse(List.of(), 10, false));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();

        mvc.perform(get("/api/v1/feed")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalToday").value(10));
    }

    @Test
    void getFeed_returnsEntries() throws Exception {
        var feedService = mock(FeedAppService.class);
        when(feedService.getFeed(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FeedAppService.FeedResponse(
                        List.of(new FeedAppService.FeedEntry("record", "r1", null, "title", "content",
                                List.of("tag"), "14:30", null, null, null, "life", "08-03", null, null)),
                        1, false
                ));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();

        mvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].type").value("record"))
                .andExpect(jsonPath("$.entries[0].content").value("content"));
    }

    /**
     * 2026-09-18（REVIEW P1-UI14 复发修复）：空 Feed 的分流判据必须出现在契约里——
     * 前端据此决定空态播「能力引导三问」（新账号）还是「接着上次的聊」（老用户）。
     */
    @Test
    void getFeed_returnsHasHistory() throws Exception {
        var feedService = mock(FeedAppService.class);
        when(feedService.getFeed(any(), any(), anyInt(), anyInt()))
                .thenReturn(new FeedAppService.FeedResponse(List.of(), 0, true));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();

        mvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalToday").value(0))
                .andExpect(jsonPath("$.hasHistory").value(true));
    }

    @Test
    void getFeed_wrongMethod_returns405() throws Exception {
        var feedService = mock(FeedAppService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/feed"))
                .andExpect(status().isMethodNotAllowed());
    }
}
