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

    // P2-8（2026-08-17 走查）：append 是读→改→写 RMW，4 线程调度下并发丢事件——per-user+date 锁
    private final java.util.concurrent.ConcurrentHashMap<String, Object> appendLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(MarketPushRepository.class);
    private static final String PUSHES_DIR = "trading/pushes/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileStorage fileStorage;

    public MarketPushRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /** 读取指定日期的推送事件；无文件/损坏返回空列表。 */
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
                    n.path("time").asText())));
            return list;
        } catch (Exception e) {
            log.warn("读取推送事件失败 | userId={} | date={} | {}", userId, date, e.getMessage());
            return List.of();
        }
    }

    /** 追加一条推送事件（读现有 → 追加 → 全量写回）。 */
    public void append(String userId, LocalDate date, MarketPushEvent event) {
        Object lock = appendLocks.computeIfAbsent(userId + ":" + date, k -> new Object());
        synchronized (lock) {
        List<MarketPushEvent> events = new ArrayList<>(findByDate(userId, date));
        events.add(event);
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
            }
            fileStorage.write(userId, PUSHES_DIR + date + ".json", MAPPER.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("追加推送事件失败 | userId={} | date={} | {}", userId, date, e.getMessage());
        }
        }
    }
}
