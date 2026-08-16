package com.adaiadai.core.domain.trading.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TencentMarketDataSource — 腾讯行情 API 实现。
 * <p>
 * 调用 {@code qt.gtimg.cn} 获取 A 股实时行情。
 * 免费、无需 API Key、支持批量查询。
 * <p>
 * 缓存策略：60 秒内存缓存。
 * 网络异常时返回空 Map，不阻塞调用方。
 * <p>
 * API 格式：{@code v_code="1~name~code~price~yesterdayClose~open~volume~...~changePercent~"}
 */
@Component
public class TencentMarketDataSource implements MarketDataSource {

    private static final Logger log = LoggerFactory.getLogger(TencentMarketDataSource.class);

    private static final String API_URL = "https://qt.gtimg.cn/q=%s";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long CACHE_TTL_MS = 60_000; // 60 秒

    private final HttpClient httpClient;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 指数代码映射：内部标识 → 腾讯 API 代码
     */
    private static final Map<String, String> INDEX_CODES = Map.of(
            "sh000001", "sh000001", // 上证指数
            "sz399001", "sz399001", // 深证成指
            "sz399006", "sz399006"  // 创业板指
    );

    public TencentMarketDataSource() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    TencentMarketDataSource(HttpClient httpClient) {
        this.httpClient = httpClient;
        log.info("TencentMarketDataSource 初始化 | API={}", String.format(API_URL, "sh000001"));
    }

    @Override
    public Map<String, MarketData> quote(List<String> codes) {
        if (codes == null || codes.isEmpty()) return Map.of();

        // 缓存键统一用规范化 API 代码（toApiCode），避免带前缀/6位混用导致永久 miss
        Map<String, MarketData> result = new LinkedHashMap<>();
        List<String> uncached = new ArrayList<>();
        Map<String, String> requestKeyByApiCode = new HashMap<>();
        for (String code : codes) {
            String apiCode = toApiCode(code);
            requestKeyByApiCode.put(apiCode, code);
            CacheEntry entry = cache.get(apiCode);
            if (entry != null && !entry.isExpired()) {
                result.put(code, entry.data);
            } else {
                uncached.add(code);
            }
        }

        if (uncached.isEmpty()) return result;

        // 批量查询未缓存的数据
        try {
            String query = String.join(",", uncached.stream().map(TencentMarketDataSource::toApiCode).toList());
            String url = String.format(API_URL, query);
            log.debug("Tencent行情请求: {}", url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(Charset.forName("GBK")));
            String body = response.body();
            if (body == null || body.isBlank()) {
                log.warn("Tencent行情返回空 body");
                return result;
            }

            Map<String, MarketData> fetched = parseResponse(body);
            for (var entry : fetched.entrySet()) {
                // 缓存用规范化 API 键存储（响应键是 6 位代码）
                String apiCode = toApiCode(entry.getKey());
                cache.put(apiCode, new CacheEntry(entry.getValue()));
                // 返回键与调用方请求一致
                String requestKey = requestKeyByApiCode.get(apiCode);
                result.put(requestKey != null ? requestKey : entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            log.warn("Tencent行情请求失败: {}", e.getMessage());
            // 返回已有缓存数据（哪怕已过期也比没有好）
            for (String code : uncached) {
                CacheEntry entry = cache.get(toApiCode(code));
                if (entry != null) {
                    result.put(code, entry.data);
                }
            }
        }

        return result;
    }

    @Override
    public Map<String, MarketData> indices() {
        List<String> indexCodes = new ArrayList<>(INDEX_CODES.keySet());
        return quote(indexCodes);
    }

    // ── 解析 ──

    /**
     * 解析腾讯 API 返回的文本。
     * <p>
     * 返回格式示例：
     * <pre>
     * v_sh600519="1~贵州茅台~600519~1361.76~1321.00~1323.00~...~40.76~3.09~";
     * </pre>
     * 字段以 ~ 分隔，关键字段位置（0-indexed）：
     * <ul>
     *   <li>[1] 名称</li>
     *   <li>[2] 代码</li>
     *   <li>[3] 最新价</li>
     *   <li>[4] 昨收</li>
     *   <li>[5] 今开</li>
     *   <li>[6] 成交量（手）</li>
     *   <li>[31] 日期时间</li>
     *   <li>[32] 涨跌幅</li>
     *   <li>[33] 涨跌额</li>
     *   <li>[44] 最低</li>
     *   <li>[45] 最高</li>
     * </ul>
     */
    Map<String, MarketData> parseResponse(String responseBody) {
        Map<String, MarketData> result = new LinkedHashMap<>();

        for (String line : responseBody.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("=")) continue;

            // 从行前缀 v_sh000001 提取带交易所前缀的 API 代码
            int eqIdx = line.indexOf('=');
            String varName = line.substring(0, eqIdx).trim();
            String apiCode = varName.startsWith("v_") ? varName.substring(2) : varName;
            String valuePart = line.substring(eqIdx + 1);
            // 去掉首尾引号和分号
            valuePart = valuePart.replaceAll("^\"|\";?$", "");

            String[] fields = valuePart.split("~");
            if (fields.length < 33) continue;

            try {
                String name = fields[1].trim();
                BigDecimal price = parseBigDecimal(fields[3]);
                BigDecimal yesterdayClose = parseBigDecimal(fields[4]);
                BigDecimal open = parseBigDecimal(fields[5]);
                BigDecimal changePercent = parseBigDecimal(fields[32]);

                // 最高最低在较后面的位置（44/45），可能不存在
                BigDecimal high = fields.length > 45 ? parseBigDecimal(fields[45]) : BigDecimal.ZERO;
                BigDecimal low = fields.length > 44 ? parseBigDecimal(fields[44]) : BigDecimal.ZERO;
                long volume = parseLong(fields[6]);

                MarketData md = new MarketData(apiCode, name, price, yesterdayClose, open, high, low, changePercent, volume);
                result.put(apiCode, md);
            } catch (Exception e) {
                log.warn("解析行情行失败: {}", e.getMessage());
            }
        }

        return result;
    }

    // ── 工具 ──

    /**
     * 6位股票代码 → 腾讯 API 代码（带交易所前缀）。
     */
    static String toApiCode(String code) {
        if (code == null || code.isBlank()) return code;
        code = code.trim();
        if (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj")) {
            return code; // 已经是完整格式
        }
        if (code.startsWith("6") || code.startsWith("688") || code.startsWith("689")) {
            return "sh" + code;
        }
        if (code.startsWith("0") || code.startsWith("3") || code.startsWith("2")) {
            return "sz" + code;
        }
        if (code.startsWith("4") || code.startsWith("8")) {
            return "bj" + code;
        }
        return "sh" + code; // 默认上海
    }

    private BigDecimal parseBigDecimal(String str) {
        if (str == null || str.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(str.strip());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private long parseLong(String str) {
        if (str == null || str.isBlank()) return 0;
        try {
            return Long.parseLong(str.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── 缓存 ──

    private static class CacheEntry {
        private final MarketData data;
        private final long createdAt;

        CacheEntry(MarketData data) {
            this.data = data;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }
}
