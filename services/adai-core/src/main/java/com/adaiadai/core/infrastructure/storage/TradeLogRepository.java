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

    /** 追加候选（按去重键去重：同 symbol+direction 当日已存在则跳过）。 */
    public List<TradeLogCandidate> append(String userId, LocalDate date, TradeLogCandidate candidate) {
        Object lock = locks.computeIfAbsent(userId + ":" + date, k -> new Object());
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean dup = existing.stream().anyMatch(c -> c.dedupeKey().equals(candidate.dedupeKey()));
            if (!dup) existing.add(candidate);
            save(userId, date, existing);
            return existing;
        }
    }

    /** 覆盖保存当日候选（确认后清空 = 传空列表）。 */
    public void save(String userId, LocalDate date, List<TradeLogCandidate> candidates) {
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
