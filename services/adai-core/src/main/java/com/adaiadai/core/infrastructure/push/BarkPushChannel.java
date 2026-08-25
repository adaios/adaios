package com.adaiadai.core.infrastructure.push;

import com.adaiadai.core.kernel.push.PushChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * BarkPushChannel — iOS 原生推送渠道（Bark，RFC 20260816 渠道插件化的第二个外部实现）。
 * <p>
 * Bark（github.com/Finb/Bark）：iPhone 装 Bark App 拿设备 key → HTTP POST → 直达系统通知，
 * 免费、无限条数；不经微信中转，推送更快更稳。公共服务器 api.day.app 个人自用足够，
 * 也可自托管（base-url 可配，2026-08-25 用户拍板：微信 Server酱 免费 5 条/天不够用，
 * 交易推送改走 iOS 原生推送）。
 * <p>
 * 配置：{@code adai.push.bark.key}（env {@code ADAI_PUSH_BARK_KEY}）；
 * 可选 {@code adai.push.bark.base-url}（env {@code ADAI_PUSH_BARK_BASE_URL}，默认公共服务器）。
 * 未配置 key → 渠道不可用，静默跳过（Feed 不受影响）。
 */
@Component
public class BarkPushChannel implements PushChannel {

    private static final Logger log = LoggerFactory.getLogger(BarkPushChannel.class);
    private static final String DEFAULT_BASE_URL = "https://api.day.app";

    private final String baseUrl;
    private final String deviceKey;
    private final HttpClient httpClient;

    public BarkPushChannel(
            @Value("${adai.push.bark.base-url:https://api.day.app}") String baseUrl,
            @Value("${adai.push.bark.key:}") String deviceKey) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.deviceKey = deviceKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "bark";
    }

    @Override
    public boolean enabled() {
        return deviceKey != null && !deviceKey.isBlank();
    }

    @Override
    public void push(String userId, PushMessage message) {
        if (!enabled()) return;
        try {
            // POST JSON：title/body 不受 URL 长度限制，比 GET 路径拼接稳（Server酱 GET 曾因内容超长/特殊字符受限）
            String json = "{\"title\":\"%s\",\"body\":\"%s\",\"group\":\"AdaiOS\",\"level\":\"active\"}"
                    .formatted(escape(message.title()), escape(message.content()));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + deviceKey))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("Bark 推送失败 | status={} | body={}", resp.statusCode(), truncate(resp.body(), 200));
            } else {
                log.info("Bark 推送成功 | type={} | symbol={}", message.type(), message.symbol());
            }
        } catch (InterruptedException e) {
            // 同 WeChatPushChannel（P3，2026-08-17）：不置 interrupt 标志污染共享调度线程
            log.info("Bark 推送被中断（服务关闭中）| type={}", message.type());
        } catch (IOException e) {
            log.warn("Bark 推送异常 | type={} | {}", message.type(), e.getMessage());
        } catch (Exception e) {
            log.warn("Bark 推送异常 | type={} | {}", message.type(), e.getMessage());
        }
    }

    /** JSON 字符串转义（防 title/body 含引号/反斜杠破坏 JSON）。 */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
