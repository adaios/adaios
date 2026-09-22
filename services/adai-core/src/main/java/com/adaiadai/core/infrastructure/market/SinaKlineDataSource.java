package com.adaiadai.core.infrastructure.market;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SinaKlineDataSource — 新浪 K 线（RFC 20260923 B 批，2026-09-23：**第三层兜底**）。
 *
 * <p><b>为什么加它</b>：2026-09-22 深夜实测生产的三条 K 线来源**同时失效**——
 * 腾讯 K 线域名被 WAF 拦（501，IP 级）、东财长期被限（连接失败）、tdx 数据包滞后，
 * 于是资金曲线/周期盈亏/买点扫描/案例库整段退化，而用户侧没有任何提示。
 * 「两个网络源同时挂」不是小概率（东财+腾讯本来就同属被风控对象），
 * 所以补一条**独立域名体系**的源（新浪），把「同时挂」的概率压下去。
 *
 * <p><b>定位：最后一层</b>（KlineService 的调用链末位：tdx → 腾讯 → 东财 → **新浪**）。
 * 正常日子里它一次都不会被调用，只在前面几层都失败时顶上。
 *
 * <p><b>两处口径差异，如实标注（不假装与腾讯/东财一致）</b>：
 * <ul>
 *   <li><b>不复权</b>：本接口给的是不复权价，而 tdx/腾讯(qfq)/东财(fqt=1) 都是前复权 →
 *       <b>除权日附近会有跳空</b>。作为兜底可以接受（宁可数据略粗，也不静默缺失），
 *       但因此**绝不把它当主源**；运行期用哪个源可取 {@code GET /trading/market-data/health} 看（RFC 20260923 D 批）。</li>
 *   <li><b>volume 单位是「股」</b>，腾讯/东财给的是「手」→ 这里 /100 统一成手，
 *       免得量比之外的绝对成交量口径漂移（量比本身是比值，不受影响）。</li>
 * </ul>
 *
 * <p>安全约定同其它源：网络异常返回空列表，不抛异常、不阻塞调用方；按日缓存（日 K 一天一变）。
 */
@Component
public class SinaKlineDataSource implements KlineSource {

    private static final Logger log = LoggerFactory.getLogger(SinaKlineDataSource.class);
    private static final String KLINE_URL =
            "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData"
                    + "?symbol=%s%s&scale=240&ma=no&datalen=%d";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    /** K 线缓存：symbol → (日期, 蜡烛列表)，按日失效（与腾讯/东财同口径）。 */
    private final Map<String, KlineCache> cache = new ConcurrentHashMap<>();

    private record KlineCache(LocalDate date, List<Candle> candles) {
        boolean sameDay() {
            return date.equals(LocalDate.now());
        }
    }

    public SinaKlineDataSource() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    SinaKlineDataSource(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public List<Candle> kline(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) return List.of();
        int n = Math.min(Math.max(limit, 10), 320);
        KlineCache cached = cache.get(symbol);
        if (cached != null && cached.sameDay() && cached.candles.size() >= n) {
            List<Candle> sub = cached.candles.subList(cached.candles.size() - n, cached.candles.size());
            return new ArrayList<>(sub);
        }
        String prefix = symbol.startsWith("6") || symbol.startsWith("9") ? "sh" : "sz";
        String url = String.format(KLINE_URL, prefix, symbol, n);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            List<Candle> candles = parse(response.body());
            if (candles.isEmpty()) return List.of();
            cache.put(symbol, new KlineCache(LocalDate.now(), new ArrayList<>(candles)));
            if (candles.size() > n) candles = candles.subList(candles.size() - n, candles.size());
            return candles;
        } catch (Exception e) {
            log.warn("新浪 K线失败 | symbol={} | {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析新浪返回的 JSON 数组：{@code [{"day":"2026-09-21","open":"…","high":"…","low":"…","close":"…","volume":"…"}]}。
     * <p>非数组 / 字段缺失 / 单行坏数据 → 跳过该行（**不因一行坏数据丢掉整段**）；全坏 → 空列表。
     */
    static List<Candle> parse(String body) throws Exception {
        List<Candle> candles = new ArrayList<>();
        if (body == null || body.isBlank()) return candles;
        JsonNode root = new ObjectMapper().readTree(body);
        if (!root.isArray()) return candles;
        for (JsonNode row : root) {
            String day = row.path("day").asText("");
            if (day.isBlank()) continue;
            if (day.length() > 10) day = day.substring(0, 10); // 分钟线会带 " HH:mm:ss"
            try {
                candles.add(new Candle(LocalDate.parse(day),
                        row.path("open").asDouble(), row.path("high").asDouble(),
                        row.path("low").asDouble(), row.path("close").asDouble(),
                        row.path("volume").asDouble() / 100.0)); // 股 → 手（对齐腾讯/东财）
            } catch (Exception ignored) {
                // 单行坏数据跳过，不丢整段
            }
        }
        candles.sort(java.util.Comparator.comparing(Candle::date)); // 兜底：保证旧→新
        return candles;
    }
}
