package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.MediaRecordAppService;
import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MediaController — 图片上传 + 预览端点测试。
 * 真实存储 + mock VisualAiClient，走完整 HTTP 流。
 */
class MediaControllerTest {

    private final InMemoryFileStorage fs = new InMemoryFileStorage();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "持仓截图", "trading", "浦发银行", List.of("交易")));
        when(glm.ask(any(), any())).thenReturn("这是浦发银行，持仓约 1000 股。");
        MediaRecordAppService service = new MediaRecordAppService(
                glm, new RecordFileRepository(fs), new MemoryService(fs), fs,
                new CardFileRepository(fs));
        mvc = MockMvcBuilders.standaloneSetup(new MediaController(service, fs)).build();
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("file", "shot.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void uploadImage_returnsRecord() throws Exception {
        mvc.perform(multipart("/api/v1/records/media")
                        .file(png())
                        .param("caption", "加仓截图")
                        .header("X-User-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordId").isString())
                .andExpect(jsonPath("$.intent").value("log"))
                .andExpect(jsonPath("$.summary").value("持仓截图"))
                .andExpect(jsonPath("$.tags[0]").value("交易"))
                .andExpect(jsonPath("$.mediaPath").isString());
    }

    @Test
    void uploadImage_notImage_400() throws Exception {
        MockMultipartFile text = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());

        mvc.perform(multipart("/api/v1/records/media").file(text))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("仅支持图片文件"));
    }

    @Test
    void askImage_returnsAnswerAndPersists() throws Exception {
        // 先上传一张图
        String resp = mvc.perform(multipart("/api/v1/records/media")
                        .file(png())
                        .header("X-User-Id", "default"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = ((ObjectNode) new ObjectMapper().readTree(resp)).get("recordId").asText();

        // 追问
        String body = "{\"question\": \"这是什么股票？\"}";
        mvc.perform(post("/api/v1/records/media/" + id + "/ask")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("这是浦发银行，持仓约 1000 股。"))
                .andExpect(jsonPath("$.imageRecordId").value(id))
                .andExpect(jsonPath("$.recordId").isString());
    }

    @Test
    void askImage_blankQuestion_400() throws Exception {
        mvc.perform(post("/api/v1/records/media/rec_x/ask")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askImage_unknownImage_400() throws Exception {
        mvc.perform(post("/api/v1/records/media/rec_unknown/ask")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"这是什么？\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askImage_overlongQuestion_400() throws Exception {
        // #214：question 无上界会原样进记录 content + ai-log prompt → 超长拒绝
        String overlong = "问".repeat(501);
        mvc.perform(post("/api/v1/records/media/rec_unknown/ask")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"" + overlong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askImage_boundaryQuestion_500ok() throws Exception {
        // #214：恰好 500 字符边界应放行（真实图片记录 + 边界长度 → 正常回答）
        String resp = mvc.perform(multipart("/api/v1/records/media")
                        .file(png())
                        .header("X-User-Id", "default"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = ((ObjectNode) new ObjectMapper().readTree(resp)).get("recordId").asText();

        String boundary = "问".repeat(500);
        mvc.perform(post("/api/v1/records/media/" + id + "/ask")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"" + boundary + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("这是浦发银行，持仓约 1000 股。"));
    }

    @Test
    void getMedia_returnsImageBytes() throws Exception {
        String resp = mvc.perform(multipart("/api/v1/records/media")
                        .file(png()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectNode json = (ObjectNode) new ObjectMapper().readTree(resp);
        String id = json.get("recordId").asText();

        mvc.perform(get("/api/v1/records/media/" + id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
    }

    @Test
    void getMedia_notFound_404() throws Exception {
        mvc.perform(get("/api/v1/records/media/rec_unknown"))
                .andExpect(status().isNotFound());
    }
}
