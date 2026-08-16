package com.adaiadai.core.infrastructure.push;

import com.adaiadai.core.kernel.push.PushChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * WeChatPushChannel — 微信推送渠道（Server酱，RFC 20260816）。
 * <p>
 * Server酱（sct.ftqq.com）：扫码绑定微信拿 SendKey → HTTP POST → 微信服务号收到。
 * 比系统通知（FCM/APNs 需改 App + 注册推送服务）简单一个量级；个人自用免费额度够。
 * <p>
 * 配置：{@code adai.push.wechat.sendkey}（env {@code ADAI_PUSH_WECHAT_SENDKEY}，Spring relaxed binding）；
 * 未配置 → 渠道不可用，静默跳过（Feed 不受影响）。
 */
@Component
public class WeChatPushChannel implements PushChannel {

    private static final Logger log = LoggerFactory.getLogger(WeChatPushChannel.class);
    private static final String SCT_URL = "https://sctapi.ftqq.com/%s.send";

    private final String sendKey;
    private final HttpClient httpClient;

    public WeChatPushChannel(@Value("${adai.push.wechat.sendkey:}") String sendKey) {
        this.sendKey = sendKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "wechat";
    }

    @Override
    public boolean enabled() {
        return sendKey != null && !sendKey.isBlank();
    }

    @Override
    public void push(String userId, PushMessage message) {
        if (!enabled()) return;
        try {
            String url = String.format(SCT_URL, sendKey)
                    + "?title=" + encode(message.title())
                    + "&desp=" + encode(message.content());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("微信推送失败 | status={} | body={}", resp.statusCode(), truncate(resp.body(), 200));
            } else {
                log.info("微信推送成功 | type={} | symbol={}", message.type(), message.symbol());
            }
        } catch (IOException | InterruptedException e) {
            log.warn("微信推送异常 | type={} | {}", message.type(), e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("微信推送异常 | type={} | {}", message.type(), e.getMessage());
        }
    }

    private String encode(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
