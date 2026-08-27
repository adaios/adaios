package com.adaiadai.core.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * NameToSymbolResolver — 股票名称 → 6 位代码（2026-08-27，截图归集补代码）。
 * <p>
 * 背景：截图入账时 VLM OCR 不稳定（thinking 模型「同图两次结果不同」——一次带代码列、
 * 一次漏代码列），表格解析器无代码时只能按名称归集（complete=false，确认会被拒）。
 * 本组件用东财 suggest 搜索接口按名称查代码，查到即补 symbol（complete=true 可确认）。
 * <p>
 * 依赖东财搜索接口（searchapi.eastmoney.com，与 KlineService 东财源同族）；失败/无结果
 * 返回 null——调用方保持「按名称归集待补充」语义，不阻塞主链路。
 */
@Component
public class NameToSymbolResolver {

    private static final Logger log = LoggerFactory.getLogger(NameToSymbolResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEARCH_URL = "https://searchapi.eastmoney.com/api/suggest/get";
    /** type=14 证券检索；count 上限 5（精确匹配优先，无需更多）。 */
    private static final String QUERY_TEMPLATE = SEARCH_URL + "?input=%s&type=14&count=5";

    private final HttpClient httpClient;

    public NameToSymbolResolver() {
        this.httpClient = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))  // 不走系统代理（与 TencentMarketDataSource 同策略）
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 股票名称 → 6 位代码；查不到/失败 → null（调用方按名称归集待补充，不抛异常）。 */
    public String resolve(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            String url = QUERY_TEMPLATE.formatted(URLEncoder.encode(name, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("名称查代码 HTTP {} | name={}", resp.statusCode(), name);
                return null;
            }
            return parseResponse(resp.body(), name);
        } catch (Exception e) {
            log.warn("名称查代码失败 | name={} | {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 解析东财 suggest 响应：优先名称精确匹配，其次第一个 6 位数字代码（A 股）。
     * 包级可见：供单测直接验证解析（不依赖真实网络）。
     */
    String parseResponse(String body, String name) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.path("QuotationCodeTable").path("Data");
            if (!data.isArray() || data.isEmpty()) return null;
            for (JsonNode n : data) {
                String code = n.path("Code").asText("");
                if (name.equals(n.path("Name").asText("")) && code.matches("\\d{6}")) {
                    return code;
                }
            }
            for (JsonNode n : data) {
                String code = n.path("Code").asText("");
                if (code.matches("\\d{6}")) return code;
            }
        } catch (Exception e) {
            log.warn("名称查代码响应解析失败 | name={} | {}", name, e.getMessage());
        }
        return null;
    }
}
