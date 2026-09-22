package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.MediaRecordAppService;
import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private IntentRecognizer intentRecognizer;

    @BeforeEach
    void setUp() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "持仓截图", "trading", "浦发银行", List.of("交易")));
        when(glm.ask(any(), any())).thenReturn("这是浦发银行，持仓约 1000 股。");
        when(glm.askMulti(any(), any())).thenReturn("左图是持仓截图，右图是分时走势。");
        when(glm.understandMulti(any(), any())).thenReturn(new ImageUnderstanding(
                "两张图：群里在聊篮球夺冠", "photo", "【第1张】小粉拿到了总冠军", List.of("群聊")));
        MediaRecordAppService service = new MediaRecordAppService(
                glm, new RecordFileRepository(fs), new MemoryService(fs), fs,
                new CardFileRepository(fs), mock(PluginService.class),
                mock(com.adaiadai.core.application.TradeLogCollectService.class));
        intentRecognizer = mock(IntentRecognizer.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new MediaController(service, fs, intentRecognizer)).build();
    }

    /** 先上传 N 张图，返回 recordId 列表（供 ask-batch 引用）。 */
    private List<String> uploadN(int n) throws Exception {
        String resp = mvc.perform(multipart("/api/v1/records/media")
                        .file(png())
                        .header("X-User-Id", "default"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = ((ObjectNode) new ObjectMapper().readTree(resp)).get("recordId").asText();
        java.util.ArrayList<String> ids = new java.util.ArrayList<>(List.of(id));
        for (int i = 1; i < n; i++) {
            resp = mvc.perform(multipart("/api/v1/records/media")
                            .file(png())
                            .header("X-User-Id", "default"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            ids.add(((ObjectNode) new ObjectMapper().readTree(resp)).get("recordId").asText());
        }
        return ids;
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

    // ── ask-batch（Phase 1 带图 ask：多图一次问答 + intent 分流）──

    @Test
    void askBatch_question_returnsAnswerAndPersists() throws Exception {
        when(intentRecognizer.recognizeWithAi(any()))
                .thenReturn(IntentRecognizer.Intent.QUESTION);
        List<String> ids = uploadN(2);

        String body = new ObjectMapper().writeValueAsString(Map.of(
                "imageRecordIds", ids, "question", "这两张图分别是什么？"));
        mvc.perform(post("/api/v1/records/media/ask-batch")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("question"))
                .andExpect(jsonPath("$.answer").value("左图是持仓截图，右图是分时走势。"))
                .andExpect(jsonPath("$.recordId").isString())
                .andExpect(jsonPath("$.imageRecordIds[0]").value(ids.get(0)))
                .andExpect(jsonPath("$.imageRecordIds[1]").value(ids.get(1)));
    }

    @Test
    void askBatch_statement_returnsLogWithoutVlm() throws Exception {
        // 陈述文本（非问句）→ 图片已逐张记录，直接返回 log，不调 VLM 多图问答
        when(intentRecognizer.recognizeWithAi(any()))
                .thenReturn(IntentRecognizer.Intent.STATEMENT);
        List<String> ids = uploadN(1);

        String body = new ObjectMapper().writeValueAsString(Map.of(
                "imageRecordIds", ids, "question", "这是今天的持仓截图"));
        mvc.perform(post("/api/v1/records/media/ask-batch")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("log"))
                .andExpect(jsonPath("$.imageRecordIds[0]").value(ids.get(0)));
    }

    @Test
    void askBatch_intentAiFailure_degradesToQuestionMark() throws Exception {
        // intent 判定 AI 失败 → 降级问号启发式：文本以 ？结尾 → question 分支
        when(intentRecognizer.recognizeWithAi(any()))
                .thenThrow(new RuntimeException("DeepSeek down"));
        List<String> ids = uploadN(1);

        String body = new ObjectMapper().writeValueAsString(Map.of(
                "imageRecordIds", ids, "question", "这是什么股票？"));
        mvc.perform(post("/api/v1/records/media/ask-batch")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("question"));
    }

    @Test
    void askBatch_overMaxImages_400() throws Exception {
        when(intentRecognizer.recognizeWithAi(any()))
                .thenReturn(IntentRecognizer.Intent.QUESTION);
        List<String> ids = uploadN(3);

        // 4 张超上限 → 400
        String body = new ObjectMapper().writeValueAsString(Map.of(
                "imageRecordIds", List.of(ids.get(0), ids.get(0), ids.get(0), ids.get(0)),
                "question", "这是什么？"));
        mvc.perform(post("/api/v1/records/media/ask-batch")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askBatch_blankQuestion_400() throws Exception {
        List<String> ids = uploadN(1);
        String body = new ObjectMapper().writeValueAsString(Map.of(
                "imageRecordIds", ids, "question", "  "));
        mvc.perform(post("/api/v1/records/media/ask-batch")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askBatch_emptyImages_400() throws Exception {
        String body = "{\"imageRecordIds\": [], \"question\": \"这是什么？\"}";
        mvc.perform(post("/api/v1/records/media/ask-batch")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── S-2 聚合卡身份断裂修复：image_qa id 的 GET 预览 / 追问（原 404 / 400）──

    /** 上传 2 图 + ask-batch（question）→ 返回 image_qa 记录 id（S-2 聚合事件）。 */
    private String askBatchToImageQa(List<String> ids) throws Exception {
        when(intentRecognizer.recognizeWithAi(any()))
                .thenReturn(IntentRecognizer.Intent.QUESTION);
        String body = new ObjectMapper().writeValueAsString(Map.of(
                "imageRecordIds", ids, "question", "这两张图分别是什么？"));
        String resp = mvc.perform(post("/api/v1/records/media/ask-batch")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((ObjectNode) new ObjectMapper().readTree(resp)).get("recordId").asText();
    }

    @Test
    void getMedia_imageQaId_returnsFirstImageBytes() throws Exception {
        // 修复缩略图 404：GET /records/media/{image_qa_id} → 回退解析引用首图返回原图字节
        List<String> ids = uploadN(2);
        String qaId = askBatchToImageQa(ids);

        mvc.perform(get("/api/v1/records/media/" + qaId)
                        .header("X-User-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
    }

    @Test
    void askImage_imageQaId_routesToMultiImageAsk() throws Exception {
        // 修复聚合卡追问 400：POST /media/{image_qa_id}/ask → 解析引用图 → 转多图问答（askImages）
        List<String> ids = uploadN(2);
        String qaId = askBatchToImageQa(ids);

        mvc.perform(post("/api/v1/records/media/" + qaId + "/ask")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"再帮我看看这两张图\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("左图是持仓截图，右图是分时走势。"))
                .andExpect(jsonPath("$.recordId").isString())
                .andExpect(jsonPath("$.imageRecordIds[0]").value(ids.get(0)))
                .andExpect(jsonPath("$.imageRecordIds[1]").value(ids.get(1)));
    }

    @Test
    void askImage_imageQaId_blankQuestion_400() throws Exception {
        // image_qa 追问路由仍校验问题非空
        List<String> ids = uploadN(1);
        String qaId = askBatchToImageQa(ids);

        mvc.perform(post("/api/v1/records/media/" + qaId + "/ask")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askImage_normalImageId_keepsSingleImageAsk() throws Exception {
        // 普通 image 记录追问语义不变（单图 AskResult 响应：imageRecordId 回指原图）
        when(intentRecognizer.recognizeWithAi(any()))
                .thenReturn(IntentRecognizer.Intent.STATEMENT);
        List<String> ids = uploadN(1);

        mvc.perform(post("/api/v1/records/media/" + ids.get(0) + "/ask")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"这是什么股票？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("这是浦发银行，持仓约 1000 股。"))
                .andExpect(jsonPath("$.imageRecordId").value(ids.get(0)));
    }

    // ── 图文一体：一次投递多图（POST /media/batch，REVIEW P1-多图1、P1-多图2） ──

    /** batch 端点的 multipart 字段名是 {@code files}（单图端点才是 {@code file}）。 */
    private static MockMultipartFile batchImage(String name) {
        return new MockMultipartFile("files", name, "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void uploadImages_batch_oneRecordWithAllMediaIds() throws Exception {
        String resp = mvc.perform(multipart("/api/v1/records/media/batch")
                        .file(batchImage("a.png")).file(batchImage("b.png"))
                        .param("text", "今天的球局")
                        .header("X-User-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("image"))
                .andExpect(jsonPath("$.intent").value("log"))
                .andExpect(jsonPath("$.mediaIds.length()").value(2))
                .andExpect(jsonPath("$.duplicated").value(false))
                .andExpect(jsonPath("$.summary").value("两张图：群里在聊篮球夺冠"))
                .andReturn().getResponse().getContentAsString();

        // 一次投递 = 1 条主记录 + 2 条薄附件；Feed 侧由 mediaIds 收敛为**一张卡**
        long mdCount = fs.listFiles("default", "records").stream()
                .filter(p -> p.endsWith(".md")).count();
        org.junit.jupiter.api.Assertions.assertEquals(3, mdCount, "2 条附件 + 1 条主记录");
        String mediaId = new ObjectMapper().readTree(resp).get("mediaIds").get(0).asText();
        // 附件原图按 id 可访问（预览/追问链路零改动）
        mvc.perform(get("/api/v1/records/media/" + mediaId).header("X-User-Id", "default"))
                .andExpect(status().isOk());
    }

    @Test
    void uploadImages_sameIdempotencyKey_returnsFirstResultWithoutDuplicate() throws Exception {
        String first = mvc.perform(multipart("/api/v1/records/media/batch")
                        .file(batchImage("a.png")).file(batchImage("b.png"))
                        .header("X-User-Id", "default")
                        .header("Idempotency-Key", "delivery-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(false))
                .andReturn().getResponse().getContentAsString();
        String firstId = new ObjectMapper().readTree(first).get("recordId").asText();
        long afterFirst = fs.listFiles("default", "records").stream()
                .filter(p -> p.endsWith(".md")).count();

        // 客户端超时后重试（同 key）→ 命中幂等：返回首次结果、**不重复落盘**（P1-多图2）
        String second = mvc.perform(multipart("/api/v1/records/media/batch")
                        .file(batchImage("a.png")).file(batchImage("b.png"))
                        .header("X-User-Id", "default")
                        .header("Idempotency-Key", "delivery-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(true))
                .andReturn().getResponse().getContentAsString();
        String secondId = new ObjectMapper().readTree(second).get("recordId").asText();

        org.junit.jupiter.api.Assertions.assertEquals(firstId, secondId, "同 key 返回首次 recordId");
        org.junit.jupiter.api.Assertions.assertEquals(afterFirst,
                fs.listFiles("default", "records").stream().filter(p -> p.endsWith(".md")).count(),
                "重试不新增任何记录/文件（md5 相同重复文件的旧现场不再出现）");
    }

    @Test
    void uploadImages_question_returnsImageQaAnswer() throws Exception {
        when(intentRecognizer.recognizeWithAi("这是什么菜？")).thenReturn(IntentRecognizer.Intent.QUESTION);

        mvc.perform(multipart("/api/v1/records/media/batch")
                        .file(batchImage("a.png"))
                        .param("text", "这是什么菜？")
                        .header("X-User-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("image_qa"))
                .andExpect(jsonPath("$.intent").value("question"))
                .andExpect(jsonPath("$.answer").value("左图是持仓截图，右图是分时走势。"));
    }

    @Test
    void uploadImages_emptyFiles_rejected() throws Exception {
        mvc.perform(multipart("/api/v1/records/media/batch")
                        .header("X-User-Id", "default"))
                .andExpect(status().isBadRequest());
    }
}
