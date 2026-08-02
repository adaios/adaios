package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.MediaRecordAppService;
import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
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
        MediaRecordAppService service = new MediaRecordAppService(
                glm, new RecordFileRepository(fs), new MemoryService(fs));
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
