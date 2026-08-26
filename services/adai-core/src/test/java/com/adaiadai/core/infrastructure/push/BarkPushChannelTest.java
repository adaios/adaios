package com.adaiadai.core.infrastructure.push;

import com.adaiadai.core.kernel.push.PushChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BarkPushChannel — iOS 原生推送渠道测试（Bark，2026-08-25 新增，替代微信 Server酱）。
 * <p>
 * 覆盖：未配置 key → 渠道不可用（静默跳过，Feed 不受影响）；配置后可用；
 * push 不抛错（外部 HTTP 不在单测范围）；自定义 base-url 可用。
 * <p>
 * 2026-08-26 生产事故回归：LLM 多行正文（含 \n）必须被转义成合法 JSON——
 * 否则 Bark 服务端 Go 解析 400 丢弃时段推送（当天 4 次全失败）。
 */
class BarkPushChannelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missingKey_disabled() {
        BarkPushChannel channel = new BarkPushChannel("https://api.day.app", "");
        assertFalse(channel.enabled(), "未配置 key 应不可用");
        // 不可用时 push 静默跳过，不抛错
        channel.push("adai", new PushChannel.PushMessage(
                "t", "c", "session", null, null, LocalTime.now()));
        assertTrue(true);
    }

    @Test
    void withKey_enabled() {
        BarkPushChannel channel = new BarkPushChannel("https://api.day.app", "test-device-key");
        assertTrue(channel.enabled(), "配置 key 应可用");
    }

    @Test
    void nullKey_disabled() {
        BarkPushChannel channel = new BarkPushChannel("https://api.day.app", null);
        assertFalse(channel.enabled());
    }

    @Test
    void blankBaseUrl_fallsBackToDefault() {
        BarkPushChannel channel = new BarkPushChannel("  ", "test-device-key");
        assertTrue(channel.enabled(), "base-url 为空应回退默认且不影响可用性");
    }

    @Test
    void multilineContent_escapedIntoValidJson() throws Exception {
        // 本地捕获服务器：记录请求 body，回 200
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            BarkPushChannel channel = new BarkPushChannel(baseUrl, "test-key");
            // LLM 生成的多行时段推送正文：含换行/引号/反斜杠/制表符
            String multiLine = "早盘计划：\n1. 看白线\n2. 回踩不破找 B1 \"买点\"\n路径 C:\\tmp";
            channel.push("adai", new PushChannel.PushMessage(
                    "早盘计划", multiLine, "session", null, null, LocalTime.now()));

            String body = capturedBody.get();
            assertTrue(body != null && !body.isEmpty(), "应发出请求 body");

            // 关键断言 1：body 中不得出现裸换行/裸制表符（Bark 400 根因）
            assertFalse(body.contains("\n") || body.contains("\r") || body.contains("\t"),
                    "JSON body 不得含裸换行/制表符（Bark 服务端 Go 严格解析）: " + body);

            // 关键断言 2：转义后是合法 JSON，且字段还原为原文（含换行语义）
            JsonNode node = MAPPER.readTree(body);
            assertTrue(node.has("title") && node.has("body"), "应含 title/body 字段");
            assertTrue(node.get("body").asText().contains("早盘计划"),
                    "body 应保留原文内容: " + body);
        } finally {
            server.stop(0);
        }
    }
}
