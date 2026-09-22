package com.adaiadai.core.domain.trading.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
public class TencentMarketDataSource implements MarketDataSource, KlineSource {

    private static final Logger log = LoggerFactory.getLogger(TencentMarketDataSource.class);

    private static final String API_URL = "https://qt.gtimg.cn/q=%s";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long CACHE_TTL_MS = 60_000; // 60 秒

    /**
     * K 线域名（RFC 20260923 A 批，2026-09-23）：**逗号分隔、按序尝试**。
     *
     * <p>为什么要有这一项：2026-09-22 深夜实测，老域名 {@code web.ifzq.gtimg.cn/appstock/app/fqkline/get}
     * 从生产服务器返回 **501 + 腾讯 WAF 拦截页**（加 iPhone UA 与 {@code Referer: https://gu.qq.com/}
     * 仍是 501 → **IP 级拦截**，不是请求头问题），而同机的**实时行情** {@code qt.gtimg.cn} 仍 200、
     * **备用域名** {@code proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get} 返回**有效前复权 K 线**。
     *
     * <p>做成列表而不是「换一行 URL」：这类风控会周期性变化（东财被限就是同类），写死一处就得改代码重新部署。
     * 现在运维只需在 {@code .env} 里改 {@code ADAI_MARKET_TENCENT_KLINE_BASES} 就能切。
     * 默认**只配新域名**——不自动重试老域名：同一家的两个域名受同一套风控影响，
     * 常态下多试一次只换来一次完整超时的延迟（失败路径本来还有新浪/东财兜底）。
     */
    private static final String DEFAULT_KLINE_BASES =
            "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get";

    private final HttpClient httpClient;
    /** K 线域名（按序尝试；解析自 {@code adai.market.tencent-kline-bases}）。 */
    private final List<String> klineBases;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    // P2-交易3（2026-08-17）：K 线按日缓存（东财被限时兜底不每请求都打腾讯）
    private final Map<String, KlineCache> klineCache = new ConcurrentHashMap<>();

    private record KlineCache(java.time.LocalDate date, List<Candle> candles) {
        boolean sameDay() {
            return date.equals(java.time.LocalDate.now());
        }
    }

    /**
     * 指数代码映射：内部标识 → 腾讯 API 代码
     */
    private static final Map<String, String> INDEX_CODES = Map.of(
            "sh000001", "sh000001", // 上证指数
            "sz399001", "sz399001", // 深证成指
            "sz399006", "sz399006"  // 创业板指
    );

    @Autowired
    public TencentMarketDataSource(
            @Value("${adai.market.tencent-kline-bases:" + DEFAULT_KLINE_BASES + "}") String klineBasesCsv) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(), klineBasesCsv);
    }

    /** 测试/降级用：不传域名 → 用默认（新域名）。 */
    TencentMarketDataSource(HttpClient httpClient) {
        this(httpClient, DEFAULT_KLINE_BASES);
    }

    TencentMarketDataSource(HttpClient httpClient, String klineBasesCsv) {
        this.httpClient = httpClient;
        this.klineBases = parseBases(klineBasesCsv);
        log.info("TencentMarketDataSource 初始化 | API={} | K线域名={}",
                String.format(API_URL, "sh000001"), klineBases);
    }

    /** 解析域名列表（去空、去重、保序）；全空 → 回默认域名（**不放任成「一个域名都没有」**）。 */
    static List<String> parseBases(String csv) {
        if (csv == null || csv.isBlank()) return List.of(DEFAULT_KLINE_BASES);
        List<String> bases = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !bases.contains(trimmed)) bases.add(trimmed);
        }
        return bases.isEmpty() ? List.of(DEFAULT_KLINE_BASES) : List.copyOf(bases);
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

    /** K 线（腾讯）：param=sh600519,day,,,N,qfq → data.sh600519.qfqday；域名可配多个、按序尝试。 */
    @Override
    public List<Candle> kline(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) return List.of();
        // P2-交易3：按日缓存——同日已拉过且数据足够，直接复用（东财被限时避免每请求打腾讯）
        KlineCache cached = klineCache.get(symbol);
        if (cached != null && cached.sameDay() && cached.candles.size() >= Math.min(Math.max(limit, 10), 320)) {
            return new ArrayList<>(cached.candles);
        }
        String prefix = symbol.startsWith("6") || symbol.startsWith("9") ? "sh" : "sz";
        int n = Math.min(Math.max(limit, 10), 320);
        List<Candle> candles = List.of();
        for (String base : klineBases) {
            candles = fetchKline(base, prefix, symbol, n);
            if (!candles.isEmpty()) break;
        }
        if (candles.size() > limit) candles = candles.subList(candles.size() - limit, candles.size());
        if (!candles.isEmpty()) {
            klineCache.put(symbol, new KlineCache(java.time.LocalDate.now(), new ArrayList<>(candles)));
        }
        return candles;
    }

    /** 单个域名的最近 N 根（失败/异常 → 空列表，由调用方决定是否试下一个域名）。 */
    private List<Candle> fetchKline(String base, String prefix, String symbol, int n) {
        String url = String.format("%s?param=%s%s,day,,,%d,qfq", base, prefix, symbol, n);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            com.fasterxml.jackson.databind.JsonNode data = root.path("data").path(prefix + symbol);
            com.fasterxml.jackson.databind.JsonNode day = data.path("qfqday");
            if (day.isMissingNode() || !day.isArray()) day = data.path("day");
            List<Candle> candles = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode row : day) {
                if (!row.isArray() || row.size() < 6) continue;
                try {
                    candles.add(new Candle(
                            java.time.LocalDate.parse(row.get(0).asText()),
                            row.get(1).asDouble(), row.get(3).asDouble(), row.get(4).asDouble(),
                            row.get(2).asDouble(), row.get(5).asDouble()));
                } catch (Exception ignored) {}
            }
            return candles;
        } catch (Exception e) {
            log.warn("腾讯 K线失败 | base={} | symbol={} | {}", base, symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按日期范围直查（案例库历史窗口）。
     *
     * <p><b>区间一律本地裁剪</b>（2026-09-23 A 批）：实测备用域名 {@code newfqkline} **忽略** start/end 参数
     * （传 2026-09-01~09-22 却返回 2025-06-05 起的 320 根）——所以不赌第三方的参数语义：
     * 拿到什么都在本地按 [from, to] 过滤，哪个域名在服务都得到同一语义（tdx 缺口补齐/案例窗口都依赖它）。
     */
    @Override
    public List<Candle> klineRange(String symbol, java.time.LocalDate from, java.time.LocalDate to) {
        if (symbol == null || symbol.isBlank() || from == null || to == null || from.isAfter(to)) {
            return List.of();
        }
        String prefix = symbol.startsWith("6") || symbol.startsWith("9") ? "sh" : "sz";
        List<Candle> candles = List.of();
        for (String base : klineBases) {
            candles = fetchKlineRange(base, prefix, symbol, from, to);
            if (!candles.isEmpty()) break;
        }
        if (!candles.isEmpty()) {
            klineCache.put(symbol, new KlineCache(java.time.LocalDate.now(), new ArrayList<>(candles)));
        }
        return candles;
    }

    /** 单个域名的区间查询（本地按 [from,to] 裁剪；失败 → 空列表）。 */
    private List<Candle> fetchKlineRange(String base, String prefix, String symbol,
                                        java.time.LocalDate from, java.time.LocalDate to) {
        String url = String.format("%s?param=%s%s,day,%s,%s,320,qfq", base, prefix, symbol, from, to);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            com.fasterxml.jackson.databind.JsonNode data = root.path("data").path(prefix + symbol);
            com.fasterxml.jackson.databind.JsonNode day = data.path("qfqday");
            if (day.isMissingNode() || !day.isArray()) day = data.path("day");
            List<Candle> candles = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode row : day) {
                if (!row.isArray() || row.size() < 6) continue;
                try {
                    Candle c = new Candle(
                            java.time.LocalDate.parse(row.get(0).asText()),
                            row.get(1).asDouble(), row.get(3).asDouble(), row.get(4).asDouble(),
                            row.get(2).asDouble(), row.get(5).asDouble());
                    if (!c.date().isBefore(from) && !c.date().isAfter(to)) candles.add(c);
                } catch (Exception ignored) {}
            }
            return candles;
        } catch (Exception e) {
            log.warn("腾讯 K线范围查询失败 | base={} | symbol={} | {}", base, symbol, e.getMessage());
            return List.of();
        }
    }

}
