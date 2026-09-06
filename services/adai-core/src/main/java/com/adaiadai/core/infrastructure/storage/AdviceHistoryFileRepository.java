package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.AdviceEntry;
import com.adaiadai.core.domain.trading.AdviceHistoryRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * AdviceHistoryFileRepository — 建议留痕文件存储（RFC 20260905 B①）。
 * <p>
 * 文件 {@code data/{userId}/trading/advice-history/{yyyy-MM}.json}——每月一个 JSON 数组，
 * append 为读-改-写（对齐 TradingHistoryFileRepository / MarketPushRepository 模式）：
 * per-user 条带锁（P2-交易28 固定 16 条带）防并发丢条目；读损坏拒写回防覆盖历史（B5-5/B6-1）。
 */
@Repository
public class AdviceHistoryFileRepository implements AdviceHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(AdviceHistoryFileRepository.class);
    private static final String DIR = "trading/advice-history";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int LOCK_STRIPES = 16;
    private final Object[] locks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final FileStorage fileStorage;
    private final ObjectMapper mapper;

    public AdviceHistoryFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private Object lockFor(String userId, String month) {
        int h = (userId != null ? userId : "default").hashCode() ^ (month != null ? month : "").hashCode();
        return locks[(h ^ (h >>> 16)) & (locks.length - 1)];
    }

    private String filePath(LocalDate date) {
        return DIR + "/" + date.format(MONTH_FMT) + ".json";
    }

    @Override
    public void append(String userId, AdviceEntry entry) {
        // G2（2026-09-05 守护）：date 由 AdviceEntry 构造器保证非空（默认 createdAt 的日期）——
        // 存储层路径推导不出现 now()（pitfalls「now() 推导路径」复发信号）
        LocalDate month = entry.date();
        String path = filePath(month);
        Object lock = lockFor(userId, month.toString());
        synchronized (lock) {
            // 损坏文件拒写回（B5-5/B6-1：防覆盖历史）
            String content = fileStorage.read(userId, path);
            if (content != null && !content.isBlank() && !isValidArray(content)) {
                log.error("建议留痕文件结构损坏，拒绝写回 | userId={} | path={}", userId, path);
                return;
            }
            List<AdviceEntry> entries = new ArrayList<>(findByMonth(userId, month));
            entries.add(entry);
            writeAll(userId, month, entries);
        }
    }

    @Override
    public List<AdviceEntry> findByMonth(String userId, LocalDate month) {
        String path = filePath(month);
        String content = fileStorage.read(userId, path);
        if (content == null || content.isBlank()) return List.of();
        try {
            List<AdviceEntry> list = new ArrayList<>();
            mapper.readTree(content).forEach(n -> list.add(toEntry(n)));
            return list;
        } catch (Exception e) {
            log.warn("读取建议留痕失败 | userId={} | path={} | {}", userId, path, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<AdviceEntry> findBySymbolRecent(String userId, String symbol, int days, LocalDate today) {
        List<AdviceEntry> result = new ArrayList<>();
        // 回查窗口横跨最近 3 个月足够（建议 TTL 无需更长史）
        for (int i = 0; i < 3; i++) {
            LocalDate month = today.minusMonths(i);
            for (AdviceEntry e : findByMonth(userId, month)) {
                if (e.symbol() == null || !e.symbol().equals(symbol)) continue;
                if (e.date() != null && e.date().isBefore(today.minusDays(days))) continue;
                result.add(e);
            }
        }
        // P2（2026-09-05 三官审）：createdAt 可 null（旧数据）——nullsLast 防 NPE，与 adviceHistoryByMonth 同口径
        result.sort(java.util.Comparator.comparing(AdviceEntry::createdAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return result;
    }

    private void writeAll(String userId, LocalDate month, List<AdviceEntry> entries) {
        try {
            var arr = mapper.createArrayNode();
            for (AdviceEntry e : entries) {
                var n = arr.addObject();
                n.put("id", e.id());
                if (e.date() != null) n.put("date", e.date().toString());
                n.put("symbol", e.symbol());
                n.put("name", e.name());
                if (e.suggestion() != null) n.put("suggestion", e.suggestion());
                if (e.reason() != null) n.put("reason", e.reason());
                var rules = n.putArray("rules");
                for (String r : e.rules()) rules.add(r);
                n.put("hardVerdict", e.hardVerdict());
                if (e.positionPercent() != null) n.put("positionPercent", e.positionPercent());
                n.put("source", e.source());
                if (e.createdAt() != null) n.put("createdAt", e.createdAt().toString());
            }
            fileStorage.write(userId, filePath(month), mapper.writeValueAsString(arr));
        } catch (Exception e) {
            log.error("写入建议留痕失败——建议历史可能丢失 | userId={} | {}", userId, e.getMessage());
        }
    }

    private AdviceEntry toEntry(com.fasterxml.jackson.databind.JsonNode n) {
        return new AdviceEntry(
                n.path("id").asText(""),
                parseDate(n.path("date").asText("")),
                n.path("symbol").asText(""),
                n.path("name").asText(""),
                n.has("suggestion") && !n.path("suggestion").isNull() ? n.path("suggestion").asText() : null,
                n.has("reason") && !n.path("reason").isNull() ? n.path("reason").asText() : null,
                parseRules(n.get("rules")),
                n.path("hardVerdict").asBoolean(false),
                n.has("positionPercent") && n.path("positionPercent").isNumber()
                        ? n.path("positionPercent").decimalValue() : null,
                n.path("source").asText(""),
                parseDateTime(n.path("createdAt").asText("")));
    }

    private List<String> parseRules(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> rules = new ArrayList<>();
        for (var r : node) if (r.isTextual()) rules.add(r.asText());
        return rules;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    private LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s); } catch (Exception e) { return null; }
    }

    /** 文件结构校验：对象数组且每元素含非空 id（对齐 B6-1）。 */
    private boolean isValidArray(String content) {
        try {
            var node = mapper.readTree(content);
            if (!node.isArray()) return false;
            for (var item : node) {
                if (!item.isObject()) return false;
                if (item.path("id").asText("").isBlank()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
