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
 * EastMoneyKlineDataSource — 东方财富 K 线数据源（2026-08-16 主源；腾讯为兜底）。
 * <p>
 * 接口：{@code push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.600519&klt=101&fqt=1&lmt=320}
 * secid：沪 1.xxx / 深 0.xxx（用户只玩沪深主板，无创业板特殊逻辑）。
 * klines 每行：日期,开,收,高,低,量,额,振幅,涨跌幅,涨跌额,换手。
 * <p>
 * 日 K 一天一变 → 缓存到收盘（比现价长得多），失败返回空列表（安全约定）。
 */
@Component
public class EastMoneyKlineDataSource implements KlineSource {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyKlineDataSource.class);
    private static final String KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
            + "?secid=%s&klt=101&fqt=1&lmt=%d&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    /** K 线缓存：symbol → (日期, 蜡烛列表)，按日期失效（收盘后自然更新）。 */
    private final Map<String, KlineCache> cache = new ConcurrentHashMap<>();

    public EastMoneyKlineDataSource() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    EastMoneyKlineDataSource(HttpClient httpClient) {
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
        String secid = (symbol.startsWith("6") || symbol.startsWith("9")) ? "1." + symbol : "0." + symbol;
        try {
            String url = String.format(KLINE_URL, secid, n);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0").GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            JsonNode klines = root.path("data").path("klines");
            if (!klines.isArray() || klines.isEmpty()) {
                log.warn("东财 K线空 | symbol={}", symbol);
                return List.of();
            }
            List<Candle> candles = new ArrayList<>();
            for (JsonNode row : klines) {
                String[] parts = row.asText().split(",");
                if (parts.length < 6) continue;
                try {
                    candles.add(new Candle(
                            LocalDate.parse(parts[0]),
                            Double.parseDouble(parts[1]), Double.parseDouble(parts[3]),
                            Double.parseDouble(parts[4]), Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[5])));
                } catch (Exception ignored) {}
            }
            if (candles.isEmpty()) return List.of();
            cache.put(symbol, new KlineCache(LocalDate.now(), candles));
            return candles;
        } catch (Exception e) {
            log.warn("东财 K线失败 | symbol={} | {}", symbol, e.getMessage());
            return List.of();
        }
    }

    private record KlineCache(LocalDate date, List<Candle> candles) {
        boolean sameDay() {
            return date.equals(LocalDate.now());
        }
    }
}
