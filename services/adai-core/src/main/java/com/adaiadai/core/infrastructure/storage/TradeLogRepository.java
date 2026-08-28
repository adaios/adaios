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
     *  同用户全日期共享一把锁（append/discard/save 均极快，串行度可接受）。
     *  P2-交易28（2026-08-29）：原 ConcurrentHashMap 锁池按 userId 无界增长（#179 任意 userId 可撑爆）——
     *  改固定 16 条带锁（个人系统并发度低，条带串行可接受；从根上消除 map 增长）。 */
    private static final Object[] LOCK_STRIPES = new Object[16];

    static {
        for (int i = 0; i < LOCK_STRIPES.length; i++) LOCK_STRIPES[i] = new Object();
    }

    private static Object lockFor(String userId) {
        int h = (userId != null ? userId : "default").hashCode();
        return LOCK_STRIPES[(h ^ (h >>> 16)) & (LOCK_STRIPES.length - 1)];
    }

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
                        // 2026-08-27：symbol/name 为 null 时 Jackson NullNode.asText() 返回 "null" 字符串
                        // （round-trip 后污染 dedupeKey/complete 判定）——归一化为 null；兼容历史脏数据。
                        normalizeNull(n.path("symbol").asText()),
                        normalizeNull(n.path("name").asText()),
                        n.path("direction").asText(),
                        price == null || price.isBlank() ? null : new java.math.BigDecimal(price),
                        volume == null || volume.isBlank() ? null : Integer.valueOf(volume),
                        // 2026-08-27：tradeDate 可空（文字归集/旧候选无日期）——确认时回退确认当天
                        parseTradeDate(n.path("tradeDate").asText("")),
                        n.path("source").asText("text"),
                        n.path("complete").asBoolean(false)));
            });
            return list;
        } catch (Exception e) {
            log.warn("读取交易日志候选失败 | userId={} | date={} | {}", userId, date, e.getMessage());
            return List.of();
        }
    }

    /** Jackson NullNode.asText() 返回 "null" 字符串——统一归 null（空串/字面 "null" 均视为无值）。 */
    private static String normalizeNull(String v) {
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }

    /** 反序列化 tradeDate：空串/非法格式 → null（旧候选/文字归集无日期，确认时回退确认当天）。 */
    private static java.time.LocalDate parseTradeDate(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** 追加候选（去重：同 symbol+direction 且 volume ±10% 内视为同笔）。
     *  B6-2（2026-08-23，P1-交易12）：去重从 dedupeKey 字符串桶改为 sameTrade 区间判定——
     *  固定 10 股桶过宽吞笔（10 vs 19）/过窄漏去重（100 vs 110 → confirm 双落库）双缺陷。 */
    public List<TradeLogCandidate> append(String userId, LocalDate date, TradeLogCandidate candidate) {
        Object lock = lockFor(userId); // C5+P2-交易28：锁收敛 userId + 固定条带（无 map 增长）
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
        Object lock = lockFor(userId); // C5+P2-交易28：锁收敛 userId + 固定条带（无 map 增长）
        synchronized (lock) {
            saveUnlocked(userId, date, candidates);
        }
    }

    /**
     * 确认落库的锁内原子「读最新 → 合并保留集 → 写回」（P2-交易25，2026-08-29）：
     * <p>
     * confirm 原实现先锁外读 latest 再锁内 save——读→写之间新 append 的候选仍会被覆盖
     * （C1 只堵了「处理前读候选 → save 前读 latest」主窗口，残余窗口在 latest 读后、save 前）。
     * 本方法把「读最新候选 + 合并 + 写」整体纳入 per-user 锁：
     * 并发 append 要么在本锁前完成（latest 可见）、要么在本锁后执行（写后追加），串行化后无覆盖。
     *
     * @param handled 本次确认已处理（落库成功或保留）的候选——不重复并入
     * @param keep    确认后保留集（失败/不完整候选）
     * @return 合并写回后的当日候选全量
     */
    public List<TradeLogCandidate> saveMerging(String userId, LocalDate date,
                                               List<TradeLogCandidate> handled,
                                               List<TradeLogCandidate> keep) {
        Object lock = lockFor(userId);
        synchronized (lock) {
            List<TradeLogCandidate> latest = new ArrayList<>(findByDate(userId, date));
            List<TradeLogCandidate> merged = new ArrayList<>(keep);
            for (TradeLogCandidate n : latest) {
                boolean wasHandled = handled.stream().anyMatch(c -> c.sameTrade(n));
                boolean alreadyKept = merged.stream().anyMatch(c -> c.sameTrade(n));
                if (!wasHandled && !alreadyKept) merged.add(n);
            }
            saveUnlocked(userId, date, merged);
            return merged;
        }
    }

    /** 丢弃一条候选（B6-5，2026-08-23，P1-交易18）：按 symbol+direction 移除，锁内读-过滤-写回。 */
    public boolean discard(String userId, LocalDate date, String symbol, String direction) {
        Object lock = lockFor(userId); // C5+P2-交易28：锁收敛 userId + 固定条带（无 map 增长）
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean removed = existing.removeIf(c ->
                    (symbol == null || symbol.equals(c.symbol()))
                            && (direction == null || direction.equals(c.direction())));
            if (removed) saveUnlocked(userId, date, existing);
            return removed;
        }
    }

    /** 补写候选成交日期（2026-08-27 二修，用户拍板「截图缺日期禁止落库，补充日期后再确认」）：
     *  截图归集候选无日期列被 confirm 拒后，用户补日期 → 更新候选 tradeDate → 可再次确认。
     *  按 symbol+direction 定位（与 discard 同口径），锁内读-改-写。 */
    public boolean updateTradeDate(String userId, LocalDate date, String symbol, String direction,
                                   LocalDate tradeDate) {
        if (symbol == null || direction == null || tradeDate == null) return false;
        Object lock = lockFor(userId); // C5+P2-交易28：锁收敛 userId + 固定条带（无 map 增长）
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean updated = false;
            for (int i = 0; i < existing.size(); i++) {
                TradeLogCandidate c = existing.get(i);
                if (symbol.equals(c.symbol()) && direction.equals(c.direction())) {
                    existing.set(i, new TradeLogCandidate(
                            c.symbol(), c.name(), c.direction(), c.price(), c.volume(),
                            tradeDate, c.source(), c.complete()));
                    updated = true;
                }
            }
            if (updated) saveUnlocked(userId, date, existing);
            return updated;
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
                n.put("tradeDate", c.tradeDate() != null ? c.tradeDate().toString() : "");
                n.put("complete", c.complete());
            }
            fileStorage.write(userId, DIR + date + ".json", MAPPER.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("保存交易日志候选失败 | userId={} | date={} | {}", userId, date, e.getMessage());
        }
    }
}
