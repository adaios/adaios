package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingPlan;
import com.adaiadai.core.domain.trading.TradingPlanRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * TradingPlanFileRepository — 次日操作计划的文件存储
 * （{@code data/{userId}/trading/plans/YYYY-MM-DD.json}，RFC 20261003-trading-plan-and-review-loop §二）。
 *
 * <p>与 {@code transfers.json} / {@code sold.json} 同惯例：JSON 落盘、StrictJson 读路径严格化
 * （读坏文件不静默吞、不编造空计划）。
 */
@Repository
public class TradingPlanFileRepository implements TradingPlanRepository {

    private static final Logger log = LoggerFactory.getLogger(TradingPlanFileRepository.class);
    private static final String DIR = "trading/plans";
    private static final ObjectMapper MAPPER = StrictJson.strict(new ObjectMapper());

    private final FileStorage fileStorage;

    public TradingPlanFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    private static String path(LocalDate date) {
        return DIR + "/" + date + ".json";
    }

    @Override
    public Optional<TradingPlan> find(String userId, LocalDate date) {
        String content = fileStorage.read(userId, path(date));
        if (content == null || content.isBlank()) return Optional.empty();
        try {
            var n = MAPPER.readTree(content);
            List<TradingPlan.PlanItem> items = new ArrayList<>();
            n.path("items").forEach(it -> items.add(new TradingPlan.PlanItem(
                    it.path("id").asText(),
                    it.path("action").asText("HOLD"),
                    it.path("symbol").asText(""),
                    it.path("name").asText(""),
                    it.path("condition").asText(""),
                    it.path("condOp").asText(""),
                    it.path("condPrice").isMissingNode() || it.path("condPrice").isNull()
                            ? null : it.path("condPrice").decimalValue(),
                    it.path("quantity").isMissingNode() || it.path("quantity").isNull()
                            ? null : it.path("quantity").asInt(),
                    it.path("text").asText(""),
                    it.path("done").asBoolean(false))));
            return Optional.of(new TradingPlan(
                    parseDate(n.path("date").asText(), date),
                    items,
                    n.path("note").asText(""),
                    parseTime(n.path("createdAt").asText())));
        } catch (Exception e) {
            // ⚠️ P3-16（2026-10-03 增量深审）：读坏**不能当作「没写」**——那会让早盘不念计划、20:30 反复催，
            // 而计划其实好好躺在盘上。现在**抛出去**：推送侧已 catch 并跳过（宁可不说，不可说错）。
            throw new com.adaiadai.core.infrastructure.storage.StorageException(
                    "操作计划文件解析失败（" + date + "）：" + e.getMessage(), e);
        }
    }

    private static final int LOCK_STRIPES = 16;

    private final Object[] locks = new Object[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    @Override
    public void save(String userId, TradingPlan plan) {
        // ⚠️ P3-18（2026-10-03 增量深审）：同一天并发保存（app 与 web 同时写）会后写覆盖前者——
        // read-modify-write 必须串行（与 CashAdjustmentFileRepository 同模式）。
        Object lock = locks[(userId != null ? userId : "default").hashCode() & (LOCK_STRIPES - 1)];
        synchronized (lock) {
            saveLocked(userId, plan);
        }
    }

    private void saveLocked(String userId, TradingPlan plan) {
        try {
            var n = MAPPER.createObjectNode();
            n.put("date", plan.date().toString());
            n.put("note", plan.note() != null ? plan.note() : "");
            n.put("createdAt", (plan.createdAt() != null ? plan.createdAt() : LocalDateTime.now()).toString());
            var arr = n.putArray("items");
            for (TradingPlan.PlanItem it : plan.items()) {
                var o = arr.addObject();
                o.put("id", it.id());
                o.put("action", it.action());
                o.put("symbol", it.symbol());
                o.put("name", it.name());
                o.put("condition", it.condition());
                o.put("condOp", it.condOp());
                if (it.condPrice() != null) o.put("condPrice", it.condPrice());
                if (it.quantity() != null) o.put("quantity", it.quantity());
                o.put("text", it.text());
                o.put("done", it.done());
            }
            fileStorage.write(userId, path(plan.date()), MAPPER.writeValueAsString(n));
        } catch (Exception e) {
            throw new StorageException("保存操作计划失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<LocalDate> dates(String userId) {
        List<LocalDate> out = new ArrayList<>();
        try {
            for (String f : fileStorage.listFiles(userId, DIR)) {
                String name = f.substring(f.lastIndexOf('/') + 1);
                if (!name.endsWith(".json")) continue;
                try {
                    out.add(LocalDate.parse(name.substring(0, name.length() - 5)));
                } catch (Exception ignored) {
                    // 非日期命名的文件跳过（不猜）
                }
            }
        } catch (RuntimeException e) {
            log.warn("列操作计划目录失败 | userId={} | {}", userId, e.getMessage());
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    private static LocalDate parseDate(String s, LocalDate fallback) {
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static LocalDateTime parseTime(String s) {
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
