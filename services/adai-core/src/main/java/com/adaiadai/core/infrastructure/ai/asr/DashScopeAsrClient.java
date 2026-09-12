package com.adaiadai.core.infrastructure.ai.asr;

import com.adaiadai.core.kernel.ai.AsrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DashScopeAsrClient — 阿里云百炼 fun-asr（Paraformer）云端转写（RFC 20260912 §3.8）。
 * <p>
 * 四步流程（2026-09-12 生产实测跑通，28min 视频 → 10297 字）：
 * <ol>
 *   <li>{@code GET /api/v1/uploads?action=getPolicy} → 上传凭证（policy/signature/upload_dir/upload_host）</li>
 *   <li>POST 到 {@code upload_host}（multipart，字段对齐官方 SDK）→ 得到 {@code oss://} 临时 URL</li>
 *   <li>{@code POST /api/v1/services/audio/asr/transcription}（异步）→ task_id</li>
 *   <li>{@code GET /api/v1/tasks/{id}} 轮询 → {@code results[].transcription_url} → 取正文</li>
 * </ol>
 * <p>
 * <b>必带的坑修复</b>（pitfall「DashScope 上传文件下载失败」）：提交任务时必须带
 * {@code X-DashScope-OssResourceResolve: enable}，否则服务端不解析 OSS 资源，任务**秒级**
 * FAILED（paraformer-v1 报 FILE_DOWNLOAD_FAILED，v2 只报笼统 SERVER_ERROR）。
 * <p>
 * <b>入参音频必须是 ASR 可解容器</b>：B站 dash 的 fMP4 分片直传必失败，调用方须先经
 * {@code AudioTranscoder} 转 16k 单声道 mp3（见 {@link FfmpegAudioTranscoder}）。
 * <p>
 * 凭证走 {@code adai.learn.asr.api-key}（env {@code DASHSCOPE_API_KEY}，不入库）；
 * 未配置 → {@link #available()} false，调用方 fail-visible 提示（**绝不**静默降级产废卡）。
 */
@Component
public class DashScopeAsrClient implements AsrClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeAsrClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long pollIntervalMs;
    private final long maxWaitMs;

    public DashScopeAsrClient(
            @Value("${adai.learn.asr.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${adai.learn.asr.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${adai.learn.asr.model:paraformer-v2}") String model,
            @Value("${adai.learn.asr.poll-interval-ms:5000}") long pollIntervalMs,
            @Value("${adai.learn.asr.max-wait-ms:1800000}") long maxWaitMs) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = (model == null || model.isBlank()) ? "paraformer-v2" : model.strip();
        this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : 5000L;
        this.maxWaitMs = maxWaitMs > 0 ? maxWaitMs : 1_800_000L;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public boolean available() {
        return !apiKey.isBlank();
    }

    @Override
    public String transcribe(byte[] audio, String fileName) {
        if (!available()) {
            throw new IllegalStateException("云端转写还没配置（缺 DashScope 凭证），暂时没法转写这个视频");
        }
        if (audio == null || audio.length == 0) {
            throw new IllegalStateException("音频内容为空，没法转写");
        }
        String name = (fileName == null || fileName.isBlank()) ? "learn-audio.mp3" : fileName;

        UploadTicket ticket = requestUploadTicket();
        uploadToOss(ticket, name, audio);
        String fileUrl = "oss://" + ticket.uploadDir() + "/" + name;
        String taskId = submitTask(fileUrl);
        return awaitResult(taskId);
    }

    // ── 步骤 1：上传凭证 ──

    private UploadTicket requestUploadTicket() {
        String url = baseUrl + "/api/v1/uploads?action=getPolicy&model=" + model;
        HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode root = parseJson(resp, "获取上传凭证失败");
        JsonNode data = root.path("data");
        String uploadHost = data.path("upload_host").asText("");
        String uploadDir = data.path("upload_dir").asText("");
        if (uploadHost.isBlank() || uploadDir.isBlank()) {
            throw new IllegalStateException("云端转写上传凭证不完整，转写中止");
        }
        return new UploadTicket(uploadHost, uploadDir,
                data.path("policy").asText(""),
                data.path("signature").asText(""),
                data.path("oss_access_key_id").asText(""),
                data.path("x_oss_object_acl").asText(""),
                data.path("x_oss_forbid_overwrite").asText(""));
    }

    private record UploadTicket(String uploadHost, String uploadDir, String policy, String signature,
                                String accessKeyId, String objectAcl, String forbidOverwrite) {}

    // ── 步骤 2：上传到 OSS（multipart，字段与官方 SDK 对齐）──

    private void uploadToOss(UploadTicket ticket, String fileName, byte[] audio) {
        String boundary = "----adaios" + UUID.randomUUID().toString().replace("-", "");
        List<FormField> fields = new ArrayList<>();
        fields.add(new FormField("OSSAccessKeyId", ticket.accessKeyId()));
        fields.add(new FormField("Signature", ticket.signature()));
        fields.add(new FormField("policy", ticket.policy()));
        fields.add(new FormField("key", ticket.uploadDir() + "/" + fileName));
        fields.add(new FormField("x-oss-object-acl", ticket.objectAcl()));
        fields.add(new FormField("x-oss-forbid-overwrite", ticket.forbidOverwrite()));
        fields.add(new FormField("x-oss-content-type", "application/octet-stream"));
        fields.add(new FormField("success_action_status", "200"));

        byte[] body = buildMultipart(boundary, fields, "file", fileName, audio);
        HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(ticket.uploadHost()))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.warn("转写音频上传失败 | status={} | body={}", resp.statusCode(), brief(resp.body()));
            throw new IllegalStateException("转写音频上传失败（" + resp.statusCode() + "），稍后重试");
        }
    }

    /** multipart 表单字段（包可见：供单测直接验证报文结构）。 */
    record FormField(String name, String value) {}

    static byte[] buildMultipart(String boundary, List<FormField> fields, String fileField,
                                 String fileName, byte[] fileBytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (FormField f : fields) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + f.name() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(f.value().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                    + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("转写音频打包失败：" + e.getMessage());
        }
    }

    // ── 步骤 3：提交异步任务（带 OssResourceResolve 头，pitfall 修复点）──

    private String submitTask(String fileUrl) {
        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode urls = input.putArray("file_urls");
        urls.add(fileUrl);

        ObjectNode parameters = MAPPER.createObjectNode();
        ArrayNode hints = parameters.putArray("language_hints");
        hints.add("zh");
        hints.add("en");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.set("input", input);
        payload.set("parameters", parameters);

        HttpResponse<String> resp;
        try {
            resp = send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/api/v1/services/audio/asr/transcription"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")
                    // 关键：不加此头，服务端不解析 oss:// 资源 → 任务秒级 FAILED（pitfall 已记）
                    .header("X-DashScope-OssResourceResolve", "enable")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (RuntimeException e) {
            throw new IllegalStateException("提交转写任务失败：" + e.getMessage());
        }
        JsonNode root = parseJson(resp, "提交转写任务失败");
        String taskId = root.path("output").path("task_id").asText("");
        if (taskId.isBlank()) {
            throw new IllegalStateException("转写任务没拿到任务号，转写中止");
        }
        log.info("转写任务已提交 | taskId={} | model={}", taskId, model);
        return taskId;
    }

    // ── 步骤 4：轮询 + 取正文 ──

    private String awaitResult(String taskId) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/api/v1/tasks/" + taskId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode output = parseJson(resp, "查询转写任务失败").path("output");
            String status = output.path("task_status").asText("");
            switch (status) {
                case "SUCCEEDED" -> {
                    return fetchTranscript(output);
                }
                case "FAILED", "CANCELED", "UNKNOWN" -> {
                    String msg = output.path("message").asText("");
                    log.warn("转写任务失败 | taskId={} | status={} | {}", taskId, status, msg);
                    throw new IllegalStateException("云端转写失败"
                            + (msg.isBlank() ? "" : "（" + msg + "）") + "，稍后可重试");
                }
                default -> {
                    // PENDING / RUNNING：继续轮询
                }
            }
            sleep(pollIntervalMs);
        }
        throw new IllegalStateException("云端转写超时，稍后可重试（音频已留存，重试不再重复上传）");
    }

    private String fetchTranscript(JsonNode output) {
        JsonNode results = output.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("云端转写没有返回结果");
        }
        String transcriptionUrl = results.get(0).path("transcription_url").asText("");
        if (transcriptionUrl.isBlank()) {
            throw new IllegalStateException("云端转写结果地址缺失");
        }
        HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(transcriptionUrl))
                .timeout(Duration.ofSeconds(60))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode root = parseJson(resp, "取转写成品失败");
        StringBuilder sb = new StringBuilder();
        JsonNode transcripts = root.path("transcripts");
        if (transcripts.isArray()) {
            for (JsonNode t : transcripts) {
                String text = t.path("text").asText("");
                if (!text.isBlank()) sb.append(text.strip()).append('\n');
            }
        }
        String text = sb.toString().strip();
        if (text.isBlank()) {
            throw new IllegalStateException("云端转写回来是空的（可能是纯音乐/静音），这张卡先不做");
        }
        return text;
    }

    // ── 基础设施 ──

    private JsonNode parseJson(HttpResponse<String> resp, String action) {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.warn("{} | status={} | body={}", action, resp.statusCode(), brief(resp.body()));
            throw new IllegalStateException(action + "（HTTP " + resp.statusCode() + "）");
        }
        try {
            return MAPPER.readTree(resp.body());
        } catch (Exception e) {
            throw new IllegalStateException(action + "：返回内容无法解析");
        }
    }

    private HttpResponse<String> send(HttpRequest request, HttpResponse.BodyHandler<String> handler) {
        try {
            return httpClient.send(request, handler);
        } catch (IOException e) {
            throw new IllegalStateException("连不上云端转写服务：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("转写被中断，请重试");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("转写被中断，请重试");
        }
    }

    private static String brief(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        String u = url.strip();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
