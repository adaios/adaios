package com.adaiadai.core.infrastructure.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * HttpBodies — 抓取响应读取的**上限保护**（2026-09-12 对抗审查 P0-1 附带项）。
 * <p>
 * 原实现用 {@code BodyHandlers.ofString()/ofByteArray()}：**全量入内存**。抓取目标来自用户提交的
 * 链接与第三方响应，一个超大文件（视频/镜像/日志）就能把 2核4G 的生产实例打满。
 * 这里改成「流式读，读满上限即停」，超限**明确失败**（调用方转人话），不静默截断半页喂给 LLM。
 * <p>
 * 编码按 {@code Content-Type} 的 charset 走（中文站点常见 GB2312/GBK），缺省 UTF-8——与原先
 * {@code ofString()} 口径一致，避免中文站点整页乱码。
 */
final class HttpBodies {

    private HttpBodies() {
    }

    /** 正文页上限（HTML/JSON 文本）。 */
    static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;
    /** 音频上限（B站音频分片；28min 约 6.7MB，留足余量）。 */
    static final int MAX_AUDIO_BYTES = 64 * 1024 * 1024;

    /** 已读入内存的响应（状态码 + 正文/字节 + 跳转地址）。 */
    record Fetched(int status, String text, byte[] bytes, String location) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        boolean redirect() {
            return status >= 300 && status < 400;
        }
    }

    /** 流式读文本（超上限抛 {@link TooLargeException}）。 */
    static Fetched getText(HttpClient client, HttpRequest request, int maxBytes)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new TooLargeException(maxBytes);
            }
            return new Fetched(response.statusCode(), new String(bytes, charsetOf(response)), bytes,
                    response.headers().firstValue("Location").orElse(null));
        }
    }

    /** 流式读字节（超上限抛 {@link TooLargeException}）。 */
    static Fetched getBytes(HttpClient client, HttpRequest request, int maxBytes)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new TooLargeException(maxBytes);
            }
            return new Fetched(response.statusCode(), null, bytes,
                    response.headers().firstValue("Location").orElse(null));
        }
    }

    private static Charset charsetOf(HttpResponse<?> response) {
        Optional<String> contentType = response.headers().firstValue("Content-Type");
        if (contentType.isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        for (String part : contentType.get().split(";")) {
            String p = part.strip();
            if (p.toLowerCase().startsWith("charset=")) {
                String name = p.substring("charset=".length()).strip().replace("\"", "");
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 响应体超过上限（调用方转成人话）。 */
    static final class TooLargeException extends IOException {
        TooLargeException(int maxBytes) {
            super("响应体超过上限 " + (maxBytes / 1024 / 1024) + "MB");
        }
    }
}
