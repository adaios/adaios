package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.BriefAppService;
import com.adaiadai.core.application.FeedAppService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
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
        when(feedService.getFeed(any(), any()))
                .thenReturn(new FeedAppService.Feed("brief", List.of(), 0));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();

        mvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brief").value("brief"))
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.earlierCount").value(0));
    }

    @Test
    void getFeed_withDateParam() throws Exception {
        var feedService = mock(FeedAppService.class);
        when(feedService.getFeed(any(), any()))
                .thenReturn(new FeedAppService.Feed("brief", List.of(), 2));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();

        mvc.perform(get("/api/v1/feed").param("date", "2026-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earlierCount").value(2));
    }

    @Test
    void getFeed_withSinceParam() throws Exception {
        var feedService = mock(FeedAppService.class);
        when(feedService.getFeed(any(), any()))
                .thenReturn(new FeedAppService.Feed("brief", List.of(), 0));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();

        mvc.perform(get("/api/v1/feed")
                        .param("date", "2026-07-18")
                        .param("since", "2026-07-18T10:00:00"))
                .andExpect(status().isOk());
    }

    @Test
    void getFeed_returnsEntries() throws Exception {
        var feedService = mock(FeedAppService.class);
        when(feedService.getFeed(any(), any()))
                .thenReturn(new FeedAppService.Feed(
                        "brief",
                        List.of(new FeedAppService.FeedEntry("record", "r1", null, "title", "content", List.of("tag"), java.time.LocalTime.of(14, 30), null, null)),
                        0
                ));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();

        mvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].type").value("record"))
                .andExpect(jsonPath("$.entries[0].content").value("content"));
    }

    @Test
    void getFeed_wrongMethod_returns405() throws Exception {
        var feedService = mock(FeedAppService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedController(feedService)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/feed"))
                .andExpect(status().isMethodNotAllowed());
    }
}
