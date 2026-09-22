package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingSyncState;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * TradingSyncStateRepository — 「账同步 / 复盘已发」状态持久化（RFC `20260922` B 批 B3）。
 *
 * <p>文件 {@code data/{userId}/trading/sync-state.json}：
 * <pre>{"lastSyncDate":"2026-09-22","lastSyncAt":"2026-09-22T15:12:03","dailyReviewDate":"2026-09-22"}</pre>
 *
 * <p><b>读损坏 → 空状态</b>（当作「没同步、没发过」）：最坏后果是复盘多发一条或说一句
 * 「我还没看到你的账」——**比把用户的账当成已同步、进而发一份错的复盘便宜得多**（fail-closed）。
 * <b>写失败 → WARN 不阻断</b>：这是推送辅助状态，不该让用户的导入动作失败。
 */
@Repository
public class TradingSyncStateRepository {

    private static final Logger log = LoggerFactory.getLogger(TradingSyncStateRepository.class);
    private static final String PATH = "trading/sync-state.json";
    private static final ObjectMapper MAPPER = StrictJson.strict(new ObjectMapper());

    private final FileStorage fileStorage;

    public TradingSyncStateRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /** 读取状态；无文件/损坏/字段缺失 → 空状态（各字段独立降级，不因一个坏字段丢掉另一个）。 */
    public TradingSyncState find(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return TradingSyncState.empty();
        try {
            var node = MAPPER.readTree(content);
            return new TradingSyncState(
                    date(node.path("lastSyncDate").asText(null)),
                    dateTime(node.path("lastSyncAt").asText(null)),
                    date(node.path("dailyReviewDate").asText(null)));
        } catch (Exception e) {
            log.warn("读取同步状态失败（按「未同步、未发过」处理）| userId={} | {}", userId, e.getMessage());
            return TradingSyncState.empty();
        }
    }

    /** 记一次成功的账同步（导入持仓/资金/历史成交）——写失败只告警。 */
    public void recordSync(String userId, LocalDate date, LocalDateTime at) {
        synchronized (this) {
            TradingSyncState next = find(userId).withSync(date, at);
            write(userId, next, "账同步");
        }
    }

    /** 记一次复盘已推送——写失败只告警（最坏后果是当天可能重发一条）。 */
    public void markDailyReview(String userId, LocalDate date) {
        synchronized (this) {
            TradingSyncState next = find(userId).withReview(date);
            write(userId, next, "复盘推送");
        }
    }

    private void write(String userId, TradingSyncState state, String what) {
        try {
            var node = MAPPER.createObjectNode();
            put(node, "lastSyncDate", state.lastSyncDate() == null ? null : state.lastSyncDate().toString());
            put(node, "lastSyncAt", state.lastSyncAt() == null ? null : state.lastSyncAt().toString());
            put(node, "dailyReviewDate", state.dailyReviewDate() == null ? null : state.dailyReviewDate().toString());
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(node));
        } catch (Exception e) {
            // 不抛：状态写失败不该让推送/导入主链路失败（复盘可能重发一条，比中断便宜）
            log.warn("写入同步状态失败（{}）| userId={} | {}", what, userId, e.getMessage());
        }
    }

    private static void put(com.fasterxml.jackson.databind.node.ObjectNode node, String key, String value) {
        if (value == null) node.putNull(key); else node.put(key, value);
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) return null;
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime dateTime(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) return null;
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
