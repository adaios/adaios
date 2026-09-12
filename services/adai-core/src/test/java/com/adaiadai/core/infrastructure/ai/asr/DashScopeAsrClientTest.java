package com.adaiadai.core.infrastructure.ai.asr;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DashScopeAsrClientTest — 云端转写客户端（RFC 20260912 §3.8，learn 抓取批 2026-09-12）。
 * <p>
 * 用本地 mock server 锁住四步流程（取凭证 → 上传 → 提交 → 轮询取成品）与两条**实测踩过的坑**：
 * <ol>
 *   <li><b>{@code X-DashScope-OssResourceResolve: enable} 必须带</b>——否则服务端不解析
 *       {@code oss://} 资源，任务秒级 FAILED（pitfall「DashScope 上传文件下载失败」）</li>
 *   <li><b>{@code X-DashScope-Async: enable}</b> + multipart 字段名对齐官方 SDK（缺字段上传 403）</li>
 * </ol>
 * 未配凭证时必须 **fail-visible**（抛人话），不得静默返回空文本。
 * <p>
 * 说明：本测试用 mock server 验证协议行为；**真实链路（真上传真转写）不在单测内**，
 * 需凭证 + 网络，属部署后实测项。
 */
class DashScopeAsrClientTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> submitHeaders = new AtomicReference<>();
    private final AtomicReference<String> submitBody = new AtomicReference<>();
    private final AtomicReference<String> uploadBody = new AtomicReference<>();
    private final AtomicInteger pollCalls = new AtomicInteger();
    private final List<String> polledStatuses = new ArrayList<>();

    private String taskStatus = "SUCCEEDED";
    private String taskMessage = "";
    private int uploadStatus = 200;
    private String transcriptsBody = "{\"transcripts\":[{\"text\":\"转写文本第一段\"},{\"text\":\"第二段\"}]}";
    private int policyStatus = 200;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/api/v1/uploads", ex -> {
            if (policyStatus != 200) {
                respond(ex, policyStatus, "{\"message\":\"bad key\"}");
                return;
            }
            respond(ex, 200, """
                    {"request_id":"r1","data":{"policy":"POL","signature":"SIG","upload_dir":"dir/2026",
                    "upload_host":"HOST/upload","oss_access_key_id":"AK","x_oss_object_acl":"default",
                    "x_oss_forbid_overwrite":"true","expire_in_seconds":300}}"""
                    .replace("HOST", base));
        });
        server.createContext("/upload", ex -> {
            uploadBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, uploadStatus, "");
        });
        server.createContext("/api/v1/services/audio/asr/transcription", ex -> {
            submitBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var h = ex.getRequestHeaders();
            submitHeaders.set("ossresolve=" + h.getFirst("X-DashScope-OssResourceResolve")
                    + "; async=" + h.getFirst("X-DashScope-Async")
                    + "; auth=" + h.getFirst("Authorization")
                    + "; ctype=" + h.getFirst("Content-Type"));
            respond(ex, 200, "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"PENDING\"}}");
        });
        server.createContext("/api/v1/tasks/task-1", ex -> {
            pollCalls.incrementAndGet();
            polledStatuses.add(taskStatus);
            // 第一次允许 RUNNING，之后按配置返回（验证轮询确实在轮）
            String status = pollCalls.get() == 1 && "SUCCEEDED".equals(taskStatus) ? "RUNNING" : taskStatus;
            if (!"SUCCEEDED".equals(status)) {
                respond(ex, 200, "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"" + status
                        + "\",\"message\":\"" + taskMessage + "\"}}");
                return;
            }
            respond(ex, 200, "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"SUCCEEDED\",\"results\":["
                    + "{\"subtask_status\":\"SUCCEEDED\",\"transcription_url\":\"" + base + "/result.json\"}]}}");
        });
        server.createContext("/result.json", ex -> respond(ex, 200, transcriptsBody));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            ex.close();
        }
    }

    private DashScopeAsrClient client(String apiKey) {
        return new DashScopeAsrClient(base, apiKey, "paraformer-v2", 10L, 5000L);
    }

    // ── 凭证与入参 ──

    @Test
    void available_onlyWithApiKey() {
        assertTrue(client("sk-test").available());
        assertFalse(client("").available());
        assertFalse(client("  ").available());
    }

    @Test
    void transcribe_withoutKey_throwsHumanMessage_notSilentEmpty() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client("").transcribe(new byte[]{1}, "a.mp3"));

        assertTrue(e.getMessage().contains("凭证"), "缺凭证必须人话说清楚（fail-visible）");
        assertEquals(0, pollCalls.get());
    }

    @Test
    void transcribe_emptyAudio_throws() {
        assertThrows(IllegalStateException.class, () -> client("sk-test").transcribe(new byte[0], "a.mp3"));
    }

    // ── 全链路 ──

    @Test
    void transcribe_fullFlow_returnsJoinedTranscripts() {
        String text = client("sk-test").transcribe(new byte[]{1, 2, 3}, "learn-BV1.mp3");

        assertEquals("转写文本第一段\n第二段", text);
        assertEquals(2, pollCalls.get(), "应轮询直到 SUCCEEDED（第一次 RUNNING → 第二次成功）");
    }

    @Test
    void submit_carriesOssResourceResolveAndAsyncHeaders() {
        client("sk-test").transcribe(new byte[]{1}, "learn-BV1.mp3");

        String headers = submitHeaders.get().toLowerCase();
        assertTrue(headers.contains("ossresolve=enable"),
                "缺此头 → 任务秒级 FAILED（pitfall「DashScope 上传文件下载失败」）");
        assertTrue(headers.contains("async=enable"), "异步任务必须带 Async 头");
        assertTrue(headers.contains("auth=bearer sk-test"), "凭证走 Authorization Bearer");
        assertTrue(headers.contains("ctype=application/json"));
    }

    @Test
    void submit_payloadUsesOssUrlAndModel() {
        client("sk-test").transcribe(new byte[]{1}, "learn-BV1.mp3");

        String body = submitBody.get();
        assertTrue(body.contains("\"model\":\"paraformer-v2\""));
        assertTrue(body.contains("oss://dir/2026/learn-BV1.mp3"), "file_urls 必须是 oss:// 临时地址");
        assertTrue(body.contains("language_hints"));
    }

    @Test
    void upload_multipartCarriesSdkFieldsAndFileLast() {
        client("sk-test").transcribe(new byte[]{1, 2}, "learn-BV1.mp3");

        String body = uploadBody.get();
        for (String field : List.of("OSSAccessKeyId", "Signature", "policy", "key",
                "x-oss-object-acl", "x-oss-forbid-overwrite", "success_action_status")) {
            assertTrue(body.contains("name=\"" + field + "\""), "multipart 缺字段会让上传被拒：" + field);
        }
        assertTrue(body.contains("filename=\"learn-BV1.mp3\""));
        assertTrue(body.indexOf("filename=") > body.indexOf("name=\"key\""), "文件字段应在最后");
    }

    // ── 失败路径 ──

    @Test
    void transcribe_taskFailed_throwsHumanMessage() {
        taskStatus = "FAILED";
        taskMessage = "FILE_DOWNLOAD_FAILED";

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client("sk-test").transcribe(new byte[]{1}, "a.mp3"));

        assertTrue(e.getMessage().contains("云端转写失败"));
        assertTrue(e.getMessage().contains("FILE_DOWNLOAD_FAILED"), "要把平台错误码带出来，便于排查");
    }

    @Test
    void transcribe_policyRequestFails_throwsHumanMessage() {
        policyStatus = 401;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client("sk-test").transcribe(new byte[]{1}, "a.mp3"));

        assertTrue(e.getMessage().contains("获取上传凭证失败"));
    }

    @Test
    void transcribe_uploadRejected_throwsHumanMessage() {
        uploadStatus = 403;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client("sk-test").transcribe(new byte[]{1}, "a.mp3"));

        assertTrue(e.getMessage().contains("上传失败"));
    }

    @Test
    void transcribe_emptyTranscriptResult_throwsInsteadOfEmptyCard() {
        transcriptsBody = "{\"transcripts\":[]}";

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client("sk-test").transcribe(new byte[]{1}, "a.mp3"));

        assertTrue(e.getMessage().contains("空的"), "转写回来是空的 → 明确失败，不产空卡片");
    }

    @Test
    void transcribe_timeout_throwsHumanMessage() {
        taskStatus = "RUNNING";
        DashScopeAsrClient slow = new DashScopeAsrClient(base, "sk-test", "paraformer-v2", 5L, 60L);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> slow.transcribe(new byte[]{1}, "a.mp3"));

        assertTrue(e.getMessage().contains("超时"));
    }

    @Test
    void buildMultipart_edgeCases_wellFormedBoundaries() {
        byte[] body = DashScopeAsrClient.buildMultipart("BOUND", List.of(
                new DashScopeAsrClient.FormField("k", "v")), "file", "a.mp3", new byte[]{9});

        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("--BOUND\r\n"));
        assertTrue(text.endsWith("--BOUND--\r\n"));
        assertTrue(text.contains("name=\"file\"; filename=\"a.mp3\""));
    }
}
