package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * TradeLogRepository — 当日交易日志候选存储（RFC 20260817 交易日志自动归集）。
 * <p>
 * 文件 {@code data/{userId}/trading/trade-log/{yyyy-MM-dd}.json}：
 * <pre>[{"symbol":"000725","name":"京东方A","direction":"SELL","price":6.1,"volume":5300,
 *   "source":"text","complete":true}]</pre>
 * 候选未落库（待用户确认）；确认后由 TradeLogCollectService 走 recordTrade 链路并清空当日候选。
 */
@Repository
public class TradeLogRepository {

    private static final Logger log = LoggerFactory.getLogger(TradeLogRepository.class);
    private static final String DIR = "trading/trade-log/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileStorage fileStorage;
    /** per-user 写锁（C5，2026-08-23）：锁 key 收敛为 userId——date 维度会随日期无限增长；
     *  同用户全日期共享一把锁（append/discard/save 均极快，串行度可接受）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

    public TradeLogRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /** 读取当日候选；无文件/损坏返回空列表。 */
    public List<TradeLogCandidate> findByDate(String userId, LocalDate date) {
        String content = fileStorage.read(userId, DIR + date + ".json");
        if (content == null || content.isBlank()) return List.of();
        try {
            List<TradeLogCandidate> list = new ArrayList<>();
            MAPPER.readTree(content).forEach(n -> {
                String price = n.path("price").asText("");
                String volume = n.path("volume").asText("");
                list.add(new TradeLogCandidate(
                        n.path("symbol").asText(),
                        n.path("name").asText(),
                        n.path("direction").asText(),
                        price == null || price.isBlank() ? null : new java.math.BigDecimal(price),
                        volume == null || volume.isBlank() ? null : Integer.valueOf(volume),
                        n.path("source").asText("text"),
                        n.path("complete").asBoolean(false)));
            });
            return list;
        } catch (Exception e) {
            log.warn("读取交易日志候选失败 | userId={} | date={} | {}", userId, date, e.getMessage());
            return List.of();
        }
    }

    /** 追加候选（去重：同 symbol+direction 且 volume ±10% 内视为同笔）。
     *  B6-2（2026-08-23，P1-交易12）：去重从 dedupeKey 字符串桶改为 sameTrade 区间判定——
     *  固定 10 股桶过宽吞笔（10 vs 19）/过窄漏去重（100 vs 110 → confirm 双落库）双缺陷。 */
    public List<TradeLogCandidate> append(String userId, LocalDate date, TradeLogCandidate candidate) {
        Object lock = locks.computeIfAbsent(userId != null ? userId : "default", k -> new Object()); // C5：锁收敛为 userId（date 维度无限增长）
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean dup = existing.stream().anyMatch(c -> c.sameTrade(candidate));
            if (!dup) existing.add(candidate);
            save(userId, date, existing);
            return existing;
        }
    }

    /** 覆盖保存当日候选（确认后清空 = 传空列表）。
     *  B5-4（2026-08-23）：与 append 同一把 per-user 锁——confirm 的 save 与 collect 的 append
     *  并发时不再清掉确认期间新归集的候选（原 save 无锁；synchronized 可重入，append 锁内调用安全）。 */
    public void save(String userId, LocalDate date, List<TradeLogCandidate> candidates) {
        Object lock = locks.computeIfAbsent(userId != null ? userId : "default", k -> new Object()); // C5：锁收敛为 userId（date 维度无限增长）
        synchronized (lock) {
            saveUnlocked(userId, date, candidates);
        }
    }

    /** 丢弃一条候选（B6-5，2026-08-23，P1-交易18）：按 symbol+direction 移除，锁内读-过滤-写回。 */
    public boolean discard(String userId, LocalDate date, String symbol, String direction) {
        Object lock = locks.computeIfAbsent(userId != null ? userId : "default", k -> new Object()); // C5：锁收敛为 userId（date 维度无限增长）
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean removed = existing.removeIf(c ->
                    (symbol == null || symbol.equals(c.symbol()))
                            && (direction == null || direction.equals(c.direction())));
            if (removed) saveUnlocked(userId, date, existing);
            return removed;
        }
    }

    private void saveUnlocked(String userId, LocalDate date, List<TradeLogCandidate> candidates) {
        try {
            var arr = MAPPER.createArrayNode();
            for (TradeLogCandidate c : candidates) {
                var n = arr.addObject();
                n.put("symbol", c.symbol());
                n.put("name", c.name() != null ? c.name() : "");
                n.put("direction", c.direction());
                if (c.price() != null) {
                    n.put("price", c.price());
                } else {
                    n.put("price", "");
                }
                if (c.volume() != null) {
                    n.put("volume", c.volume());
                } else {
                    n.put("volume", "");
                }
                n.put("source", c.source());
                n.put("complete", c.complete());
            }
            fileStorage.write(userId, DIR + date + ".json", MAPPER.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("保存交易日志候选失败 | userId={} | date={} | {}", userId, date, e.getMessage());
        }
    }
}
