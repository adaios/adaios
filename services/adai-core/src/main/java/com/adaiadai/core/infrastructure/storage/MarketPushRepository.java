package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * MarketPushRepository — 行情异动推送事件存储（Layer 5 主动推送）。
 * <p>
 * 按日持久化推送事件，文件 {@code data/{userId}/trading/pushes/{yyyy-MM-dd}.json}：
 * <pre>
 * [{"id":"push_...","symbol":"600519","name":"立昂微",
 *   "message":"📉 立昂微(600519) 今日跌 -3.2%，现价 24.5，触发止损预警",
 *   "type":"loss","time":"14:05"}]
 * </pre>
 * FeedAppService 按日读取注入 {@code type=push} 条目（前端已支持推送卡片）。
 */
@Repository
public class MarketPushRepository {

    // P2-8（2026-08-17 走查）：append 是读→改→写 RMW，4 线程调度下并发丢事件——per-user+date 锁。
    // P2-交易28（2026-08-29）：原 ConcurrentHashMap 锁池按 userId:date 无限增长（#179 任意 userId 可撑爆）——
    // 改固定 16 条带锁（个人系统并发度低，条带串行可接受；从根上消除 map 增长）。
    private static final int LOCK_STRIPES = 16;
    private final Object[] appendLocks = new Object[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) appendLocks[i] = new Object();
    }

    private static Object lockFor(Object[] stripes, String key) {
        int h = (key != null ? key : "default").hashCode();
        return stripes[(h ^ (h >>> 16)) & (stripes.length - 1)];
    }

    private static final Logger log = LoggerFactory.getLogger(MarketPushRepository.class);
    private static final String PUSHES_DIR = "trading/pushes/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 行情类推送类型（RFC 20260825 §7 TTL 分组）：时效性强，次日 09:30 自动消失。 */
    public static final java.util.Set<String> SESSION_TYPES = java.util.Set.of(
            "stop-loss", "near-stop-loss", "loss", "gain", "break-cost", "market", "session", "buy-point");

    private final FileStorage fileStorage;

    public MarketPushRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /** 读取指定日期的推送事件；无文件/空返回空列表，损坏返回空列表（读失败由 append 拒绝写回）。 */
    public List<MarketPushEvent> findByDate(String userId, LocalDate date) {
        String content = fileStorage.read(userId, PUSHES_DIR + date + ".json");
        if (content == null || content.isBlank()) return List.of();
        try {
            List<MarketPushEvent> list = new ArrayList<>();
            MAPPER.readTree(content).forEach(n -> list.add(new MarketPushEvent(
                    n.path("id").asText(),
                    n.path("symbol").asText(),
                    n.path("name").asText(),
                    n.path("message").asText(),
                    n.path("type").asText(),
                    n.path("time").asText(),
                    // B9-1（2026-08-23，P1-推送1）：读回原标题；旧文件（2026-08-23 前）无 title → null
                    n.has("title") ? n.path("title").asText() : null,
                    // RFC 20260825 §7：读回过期时间；旧文件无 expiresAt → null（按类型默认保留期）
                    n.has("expiresAt") ? n.path("expiresAt").asText() : null)));
            return list;
        } catch (Exception e) {
            log.warn("读取推送事件失败 | userId={} | date={} | {}", userId, date, e.getMessage());
            return List.of();
        }
    }

    /** 追加一条推送事件（读现有 → 追加 → 全量写回）。
     *  B5-5（2026-08-23）：读取失败（文件损坏/半写）→ 拒绝写回——
     *  原实现损坏时读到空列表再全量写回，当日历史推送被覆盖丢失。
     *  B6-1（2026-08-23）：损坏防护从「验 JSON 语法」升级为「验结构」——
     *  `[123]`/`{"a":1}` 是合法 JSON 但结构损坏，readTree 通过仍会读空列表覆盖历史；
     *  须解析为对象数组且元素含 id 字段才放行，否则保留原文件。 */
    public void append(String userId, LocalDate date, MarketPushEvent event) {
        Object lock = lockFor(appendLocks, userId + ":" + date);
        synchronized (lock) {
            String content = fileStorage.read(userId, PUSHES_DIR + date + ".json");
            if (content != null && !content.isBlank()) {
                // 文件存在但结构损坏（非对象数组 / 元素缺 id）→ 不写回，保留原文件等人工处理
                if (!isValidPushArray(content)) {
                    log.error("推送事件文件结构损坏，拒绝写回（保留原文件防覆盖历史）| userId={} | date={} | content 头 80 字符: {}",
                            userId, date, content.length() > 80 ? content.substring(0, 80) : content);
                    return;
                }
            }
            List<MarketPushEvent> events = new ArrayList<>(findByDate(userId, date));
            // RFC 20260825 §7：写入时顺带剔除已过期条目（推送定时消失，文件不无限涨）
            events.removeIf(e -> isExpired(e, date));
            events.add(event);
            writeAll(userId, date, events);
        }
    }

    /** 按 id 移除一条推送事件（B10-1，2026-08-23，P1-推送2）：app 左滑删/web 忽略按钮持久化——
     *  原前端仅本地 removeWhere，30 分钟自动刷新/下拉后同卡复活。返回是否命中。 */
    public boolean dismiss(String userId, LocalDate date, String eventId) {
        Object lock = lockFor(appendLocks, userId + ":" + date);
        synchronized (lock) {
            String content = fileStorage.read(userId, PUSHES_DIR + date + ".json");
            if (content != null && !content.isBlank() && !isValidPushArray(content)) {
                log.error("推送事件文件结构损坏，拒绝删除（保留原文件）| userId={} | date={}", userId, date);
                return false;
            }
            List<MarketPushEvent> events = new ArrayList<>(findByDate(userId, date));
            boolean removed = events.removeIf(e -> eventId.equals(e.id()));
            if (removed) writeAll(userId, date, events);
            return removed;
        }
    }

    /** 全量写回（append/dismiss 共用；结构已由调用方校验）。 */
    private void writeAll(String userId, LocalDate date, List<MarketPushEvent> events) {
        try {
            var arr = MAPPER.createArrayNode();
            for (MarketPushEvent e : events) {
                var n = arr.addObject();
                n.put("id", e.id());
                n.put("symbol", e.symbol());
                n.put("name", e.name());
                n.put("message", e.message());
                n.put("type", e.type());
                n.put("time", e.time());
                if (e.title() != null) n.put("title", e.title()); // B9-1：透传原标题（旧数据无则不写）
                if (e.expiresAt() != null) n.put("expiresAt", e.expiresAt()); // RFC 20260825：过期时间
            }
            fileStorage.write(userId, PUSHES_DIR + date + ".json", MAPPER.writeValueAsString(arr));
        } catch (Exception e) {
            // P2-交易27（2026-08-29）：推送落盘失败与账目落盘同级别——持久化数据写失败必须 error
            log.error("写入推送事件失败——当日推送可能丢失 | userId={} | date={} | {}", userId, date, e.getMessage());
        }
    }

    /** 推送是否已过期（RFC 20260825 §7 定时消失）：expiresAt 缺失/解析失败 → 按类型默认保留期
     *  （行情类 = 落盘日次日 09:30，汇总类 = 次日 23:59——兑现 RFC 承诺，旧数据也会过期清理，P2-4）。 */
    public static boolean isExpired(MarketPushEvent e, LocalDate date) {
        if (e.expiresAt() != null && !e.expiresAt().isBlank()) {
            try {
                return java.time.LocalDateTime.parse(e.expiresAt()).isBefore(java.time.LocalDateTime.now());
            } catch (Exception ex) {
                return false; // 格式异常按不过期处理，不误删
            }
        }
        java.time.LocalDateTime fallback = e.type() != null && SESSION_TYPES.contains(e.type())
                ? date.plusDays(1).atTime(9, 30)
                : date.plusDays(1).atTime(23, 59);
        return fallback.isBefore(java.time.LocalDateTime.now());
    }

    /** 推送文件结构校验（B6-1）：必须是对象数组且每元素含非空 id 字段；`[123]`/`{"a":1}` 判损坏。 */
    private boolean isValidPushArray(String content) {
        try {
            var node = MAPPER.readTree(content);
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
