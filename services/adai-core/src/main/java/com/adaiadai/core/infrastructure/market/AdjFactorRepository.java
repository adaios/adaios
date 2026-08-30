package com.adaiadai.core.infrastructure.market;

import com.adaiadai.core.domain.trading.market.AdjustmentCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AdjFactorRepository — 除权因子仓储（2026-08-30：TDX 数据正确性——前复权）。
 * <p>
 * 数据源：东财分红送转明细（RPT_SHAREBONUS_DET，全 A）。实测 `SEND_RATIO/CASH_RATIO`
 * 字段对纯派息股为 null——**解析方案文本** `IMPL_PLAN_PROFILE`（`10派X元` / `10送Y转Z派W`）。
 * <p>
 * 存储：`data/market/adj/factors/{symbol}.json`（File First，公开行情数据，gitignore）。
 * 懒加载 + 按日 TTL（updatedAt=今天 → 缓存；否则重拉）。失败 → 空（不阻塞主链路）。
 * 换算见 {@link AdjustmentCalculator}。
 */
@Component
public class AdjFactorRepository {

    private static final Logger log = LoggerFactory.getLogger(AdjFactorRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String API_URL =
            "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_SHAREBONUS_DET"
                    + "&columns=ALL&pageSize=200&sortColumns=EX_DIVIDEND_DATE&sortTypes=-1";
    private static final Pattern CASH = Pattern.compile("派([\\d.]+)");
    private static final Pattern SEND = Pattern.compile("送(\\d+)");
    private static final Pattern TRANSFER = Pattern.compile("转(\\d+)");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Path root;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    /** symbol → 事件列表（进程内缓存，文件已是最新才复用）。 */
    private final Map<String, List<AdjustmentCalculator.AdjustmentEvent>> cache = new ConcurrentHashMap<>();

    public AdjFactorRepository(
            @Value("${adai.market.adj-path:../../data/market/adj}") String adjPath) {
        this.root = Paths.get(adjPath);
    }

    /** 某标的除权事件；本地新鲜 → 缓存；否则拉取；失败 → 空（不阻塞主链路）。 */
    public List<AdjustmentCalculator.AdjustmentEvent> factorsFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return List.of();
        List<AdjustmentCalculator.AdjustmentEvent> cached = cache.get(symbol);
        if (cached != null) return cached;
        try {
            Path file = fileFor(symbol);
            if (Files.exists(file)) {
                String content = Files.readString(file);
                FactorFile parsed = MAPPER.readValue(content, FactorFile.class);
                if (parsed.updatedAt() != null && parsed.updatedAt().equals(LocalDate.now())) {
                    List<AdjustmentCalculator.AdjustmentEvent> events =
                            toEvents(parsed.events());
                    cache.put(symbol, events);
                    return events;
                }
            }
            // 拉取（本地无或过期）
            List<AdjustmentCalculator.AdjustmentEvent> events = fetch(symbol);
            if (!events.isEmpty()) {
                save(symbol, events);
                cache.put(symbol, events);
            }
            return events;
        } catch (Exception e) {
            log.warn("除权因子读取失败 | symbol={} | {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /** 东财拉取：解析方案文本（每 10 股比例 → 每股）。 */
    List<AdjustmentCalculator.AdjustmentEvent> fetch(String symbol) {
        try {
            // filter 整体 URL 编码（不能 formatted——URL 含 %22 会被当格式符抛异常）
            String filter = "(SECURITY_CODE=\"" + symbol + "\")";
            String url = API_URL + "&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("除权因子拉取 HTTP {} | symbol={}", resp.statusCode(), symbol);
                return List.of();
            }
            return parseApi(resp.body());
        } catch (Exception e) {
            log.warn("除权因子拉取失败 | symbol={} | {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /** 解析东财响应 → 事件列表（包级可见，单测）。 */
    List<AdjustmentCalculator.AdjustmentEvent> parseApi(String body) {
        List<AdjustmentCalculator.AdjustmentEvent> events = new ArrayList<>();
        if (body == null || body.isBlank()) return events;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.path("result").path("data");
            if (!data.isArray()) return events;
            for (JsonNode n : data) {
                String dateText = n.path("EX_DIVIDEND_DATE").asText("");
                String profile = n.path("IMPL_PLAN_PROFILE").asText("");
                if (dateText.isBlank() || profile.isBlank()) continue;
                LocalDate exDate = parseDate(dateText);
                if (exDate == null) continue;
                AdjustmentCalculator.AdjustmentEvent e = parseProfile(profile, exDate);
                if (e != null) events.add(e);
            }
        } catch (Exception e) {
            log.warn("除权响应解析失败 | {}", e.getMessage());
        }
        return events;
    }

    /** 方案文本解析（包级可见，单测）：`10派X元` / `10送Y转Z派W` → 每股比例。 */
    AdjustmentCalculator.AdjustmentEvent parseProfile(String profile, LocalDate exDate) {
        Matcher cash = CASH.matcher(profile);
        Matcher send = SEND.matcher(profile);
        Matcher transfer = TRANSFER.matcher(profile);
        double cashPerShare = cash.find() ? Double.parseDouble(cash.group(1)) / 10.0 : 0;
        double sendPerShare = send.find() ? Integer.parseInt(send.group(1)) / 10.0 : 0;
        double transferPerShare = transfer.find() ? Integer.parseInt(transfer.group(1)) / 10.0 : 0;
        if (cashPerShare <= 0 && sendPerShare <= 0 && transferPerShare <= 0) return null;
        return new AdjustmentCalculator.AdjustmentEvent(exDate, cashPerShare, sendPerShare, transferPerShare);
    }

    private void save(String symbol, List<AdjustmentCalculator.AdjustmentEvent> events) throws Exception {
        List<EventDto> dtos = events.stream()
                .map(e -> new EventDto(e.exDate(), e.cashPerShare(), e.sendPerShare(), e.transferPerShare()))
                .toList();
        FactorFile file = new FactorFile(symbol, LocalDate.now(), dtos);
        Files.createDirectories(root.resolve("factors"));
        Files.writeString(fileFor(symbol), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(file));
    }

    private Path fileFor(String symbol) {
        return root.resolve("factors").resolve(symbol + ".json");
    }

    private static List<AdjustmentCalculator.AdjustmentEvent> toEvents(List<EventDto> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream()
                .map(d -> new AdjustmentCalculator.AdjustmentEvent(d.exDate(),
                        d.cashPerShare(), d.sendPerShare(), d.transferPerShare()))
                .toList();
    }

    private static LocalDate parseDate(String text) {
        try {
            String date = text.contains(" ") ? text.substring(0, 10) : text;
            return LocalDate.parse(date);
        } catch (Exception e) {
            return null;
        }
    }

    /** 本地文件结构。 */
    public record FactorFile(String symbol, LocalDate updatedAt, List<EventDto> events) {}

    public record EventDto(LocalDate exDate, double cashPerShare, double sendPerShare,
                           double transferPerShare) {}
}
