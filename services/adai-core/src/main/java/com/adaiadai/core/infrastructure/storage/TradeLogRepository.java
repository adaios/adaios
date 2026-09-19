package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
    private static final ObjectMapper MAPPER = StrictJson.strict(new ObjectMapper());

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
                String fee = n.path("fee").asText("");
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
                        // 2026-09-18（P0-交易59）：tradeTime 可空（文字归集/旧数据无此字段）→ 退回原判定
                        parseTradeTime(n.path("tradeTime").asText("")),
                        n.path("source").asText("text"),
                        n.path("complete").asBoolean(false),
                        // P2-交易36 治本（2026-09-09）：orderId/fee 可空字段——缺字段 → null
                        // （Jackson NullNode.asText()="null" 由 normalizeNull 归一）；fee 数字文本解析失败 → null
                        normalizeNull(n.path("orderId").asText()),
                        parseFee(fee),
                        // P1-交易54（2026-09-17）：行标识——旧数据没有该字段 → 按内容派生**确定性** id
                        // （同一文件反复读出的 id 必须一致，否则前端拿着 id 删不掉）
                        candidateId(n)));
            });
            return list;
        } catch (Exception e) {
            log.warn("读取交易日志候选失败 | userId={} | date={} | {}", userId, date, e.getMessage());
            return List.of();
        }
    }

    /** 反序列化 fee：空串/缺字段 → null；非数字文本（脏数据）解析失败 → null（不阻断整文件读取）。 */
    private static java.math.BigDecimal parseFee(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return new java.math.BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 读取候选的**行标识**（P1-交易54，2026-09-17）：文件里有 {@code id} 就用它；
     * 旧数据没有该字段 → 按内容派生一个**确定性** id（同一文件反复读出的结果一致，
     * 否则前端拿到 id 却删不掉）。代价：同代码+同方向+同价+同量+同日的两条旧候选会得到同一个
     * 派生 id——但那两条本来就无法区分，且会在下一次落盘时各自获得单调 id。
     */
    private static String candidateId(com.fasterxml.jackson.databind.JsonNode n) {
        String raw = normalizeNull(n.path("id").asText());
        if (raw != null) return raw;
        String seed = n.path("symbol").asText("") + "|" + n.path("direction").asText("")
                + "|" + n.path("price").asText("") + "|" + n.path("volume").asText("")
                + "|" + n.path("tradeDate").asText("");
        return "cand_l" + Integer.toHexString(seed.hashCode());
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

    /** 反序列化 tradeTime：空串/缺字段/非法格式 → null（旧数据/文字归集无成交时间）。 */
    private static java.time.LocalTime parseTradeTime(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return java.time.LocalTime.parse(v);
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
            // P1-交易54（2026-09-17）：落盘前**发号**——候选必须带行标识，
            // 删除/补日期/补元信息才能行级定位（此前按「代码+方向」粗粒度，多笔一删全删）。
            TradeLogCandidate incoming = candidate.id() == null
                    ? candidate.withId(com.adaiadai.core.kernel.IdGenerator.monotonic("cand_"))
                    : candidate;
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean dup = existing.stream().anyMatch(c -> c.sameTrade(incoming));
            if (!dup) existing.add(incoming);
            save(userId, date, existing);
            return existing;
        }
    }

    /**
     * 批量追加候选（2026-09-18，P0-交易59）：**同一批解析结果内部互不判重**，只与「本批开始前
     * 已存在的候选」去重。
     *
     * <p>生产实据：用户卖出 000831 两笔各 200 股、成交价同为 53.300，同一张截图里 OCR 出了两行；
     * 逐笔 {@link #append} 会让第二笔与**本批第一笔**判成同笔并静默丢弃（实际卖 400 股只记 200 股，
     * 少记 10660 元）。一张截图是同一时刻的同一张凭证，批次内出现两行 = 券商确实有两笔成交（分单），
     * 不该由解析层替用户合并——宁可多留一笔让用户手动丢（与 {@code sameTrade} 同一取舍）。
     *
     * <p>跨批次去重语义不变：同一张图重复上传时，第二批只与第一批已落盘的候选比对 → 仍能判重。
     */
    public List<TradeLogCandidate> appendBatch(String userId, LocalDate date,
                                               List<TradeLogCandidate> incoming) {
        if (incoming == null || incoming.isEmpty()) return findByDate(userId, date);
        Object lock = lockFor(userId);
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            // 基准 = 本批开始前的候选快照（批内新加入的不参与比对）
            List<TradeLogCandidate> baseline = List.copyOf(existing);
            for (TradeLogCandidate c : incoming) {
                if (c == null) continue;
                TradeLogCandidate candidate = c.id() == null
                        ? c.withId(com.adaiadai.core.kernel.IdGenerator.monotonic("cand_"))
                        : c;
                boolean dup = baseline.stream().anyMatch(x -> x.sameTrade(candidate));
                if (!dup) existing.add(candidate);
            }
            saveUnlocked(userId, date, existing);
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
    /**
     * 按**行标识**丢弃一条候选（P1-交易54，2026-09-17 新增）：这是前端的默认路径——
     * 同标的同方向的多笔候选（生产实据：当日三笔亨通光电买入各 100 股）各不相同，
     * 只有 id 能精确删到其中一条。
     */
    public boolean discardById(String userId, LocalDate date, String id) {
        if (id == null || id.isBlank()) return false;
        Object lock = lockFor(userId);
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean removed = existing.removeIf(c -> id.equals(c.id()));
            if (removed) saveUnlocked(userId, date, existing);
            return removed;
        }
    }

    /**
     * 按 symbol+direction 丢弃（B6-5，2026-08-23，P1-交易18；旧口径，保留兼容）。
     * <p>⚠️ **粗粒度**：同标的同方向的多笔会被**一起删掉**；{@code symbol} 传 null 更是删光该方向全部候选。
     * 新代码请用 {@link #discardById}（2026-09-17 起前端已改走 id）。
     */
    @Deprecated
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
     *  按 symbol+direction 定位（与 discard 同口径），锁内读-改-写。
     *  P2-交易36（2026-09-09）：重建候选保留 c.orderId()/c.fee()（补日期不丢已填成交元信息）。 */
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
                            // 2026-09-18：补日期**不得抹掉成交时间**（tradeTime 纳入候选后，这里漏传会静默清空）
                            tradeDate, c.tradeTime(), c.source(), c.complete(), c.orderId(), c.fee(), c.id()));
                    updated = true;
                }
            }
            if (updated) saveUnlocked(userId, date, existing);
            return updated;
        }
    }

    /** 补写候选成交元信息（P2-交易36 治本，2026-09-09）：确认前用户补成交编号/手续费。
     *  <p>按 symbol+direction 定位（与 updateTradeDate 同口径），锁内读-改-写；
     *  只覆盖非空新值——orderId 仅非 null 且非 blank 才替换、fee 仅非 null 才替换，其余保持；
     *  orderId 与 fee 都无可写值 → 直接返回 false（无操作）。</p>
     *  @return true=至少更新了一笔候选；false=当日无此候选/无新值可写 */
    public boolean updateMeta(String userId, LocalDate date, String symbol, String direction,
                              String orderId, BigDecimal fee) {
        if (symbol == null || direction == null) return false;
        boolean hasOrder = orderId != null && !orderId.isBlank();
        boolean hasFee = fee != null;
        if (!hasOrder && !hasFee) return false;
        Object lock = lockFor(userId); // C5+P2-交易28：锁收敛 userId + 固定条带（无 map 增长）
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean updated = false;
            for (int i = 0; i < existing.size(); i++) {
                TradeLogCandidate c = existing.get(i);
                if (symbol.equals(c.symbol()) && direction.equals(c.direction())) {
                    existing.set(i, new TradeLogCandidate(
                            c.symbol(), c.name(), c.direction(), c.price(), c.volume(),
                            c.tradeDate(), c.tradeTime(), c.source(), c.complete(),
                            hasOrder ? orderId : c.orderId(),
                            hasFee ? fee : c.fee(), c.id()));
                    updated = true;
                }
            }
            if (updated) saveUnlocked(userId, date, existing);
            return updated;
        }
    }

    /**
     * 按**行标识**补写候选成交日期（P1-交易54 收尾，2026-09-17）：同标的同方向的多笔候选
     * （生产实据：当日三笔亨通光电买入各 100 股）只有 id 能定位到其中一条——旧口径
     * {@link #updateTradeDate} 会把它们**一起补上**同一个日期（用户对其中一条改日期，
     * 另外两条也被改，而它们可能来自不同的成交日）。
     */
    public boolean updateTradeDateById(String userId, LocalDate date, String id, LocalDate tradeDate) {
        if (id == null || id.isBlank() || tradeDate == null) return false;
        Object lock = lockFor(userId);
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean updated = false;
            for (int i = 0; i < existing.size(); i++) {
                TradeLogCandidate c = existing.get(i);
                if (id.equals(c.id())) {
                    existing.set(i, new TradeLogCandidate(
                            c.symbol(), c.name(), c.direction(), c.price(), c.volume(),
                            tradeDate, c.tradeTime(), c.source(), c.complete(), c.orderId(), c.fee(), c.id()));
                    updated = true;
                }
            }
            if (updated) saveUnlocked(userId, date, existing);
            return updated;
        }
    }

    /** 按**行标识**补写候选成交元信息（P1-交易54 收尾）：语义同 {@link #updateMeta}，定位改用 id。 */
    public boolean updateMetaById(String userId, LocalDate date, String id, String orderId, BigDecimal fee) {
        if (id == null || id.isBlank()) return false;
        boolean hasOrder = orderId != null && !orderId.isBlank();
        boolean hasFee = fee != null;
        if (!hasOrder && !hasFee) return false;
        Object lock = lockFor(userId);
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean updated = false;
            for (int i = 0; i < existing.size(); i++) {
                TradeLogCandidate c = existing.get(i);
                if (id.equals(c.id())) {
                    existing.set(i, new TradeLogCandidate(
                            c.symbol(), c.name(), c.direction(), c.price(), c.volume(),
                            c.tradeDate(), c.tradeTime(), c.source(), c.complete(),
                            hasOrder ? orderId : c.orderId(),
                            hasFee ? fee : c.fee(), c.id()));
                    updated = true;
                }
            }
            if (updated) saveUnlocked(userId, date, existing);
            return updated;
        }
    }

    /**
     * 按**行标识**就地改候选核心字段（2026-09-18，RFC 20260918 A1-4）：
     * 价格 / 数量 / 方向 / 成交日期 / 手续费（传 null = 该字段保持不变）。
     *
     * <p>为什么需要：截图入账的全部价值是省手输，而 VLM 对价格、数量、买卖方向都可能出错；
     * 原实现只允许「全对」或「丢弃重录」（丢掉后还得重发截图、重新补日期），
     * 等于把 AI 的不确定性全部转嫁给用户。
     *
     * <p>{@code complete} 随改动**重算**——否则把不完整候选改完整后仍判不完整、确认时会被跳过。
     * 成交时间（tradeTime）原样保留。
     *
     * @return true=已更新；false=无此候选 / 没有任何可改字段
     */
    public boolean updateFieldsById(String userId, LocalDate date, String id,
                                    BigDecimal price, Integer volume, String direction,
                                    LocalDate tradeDate, BigDecimal fee) {
        if (id == null || id.isBlank()) return false;
        boolean hasPrice = price != null && price.signum() > 0;
        boolean hasVolume = volume != null && volume > 0;
        boolean hasDir = direction != null && !direction.isBlank();
        boolean hasDate = tradeDate != null;
        boolean hasFee = fee != null;
        if (!hasPrice && !hasVolume && !hasDir && !hasDate && !hasFee) return false;
        Object lock = lockFor(userId);
        synchronized (lock) {
            List<TradeLogCandidate> existing = new ArrayList<>(findByDate(userId, date));
            boolean updated = false;
            for (int i = 0; i < existing.size(); i++) {
                TradeLogCandidate c = existing.get(i);
                if (!id.equals(c.id())) continue;
                BigDecimal newPrice = hasPrice ? price : c.price();
                Integer newVolume = hasVolume ? volume : c.volume();
                String newDir = hasDir ? direction : c.direction();
                LocalDate newDate = hasDate ? tradeDate : c.tradeDate();
                BigDecimal newFee = hasFee ? fee : c.fee();
                boolean complete = c.symbol() != null && !c.symbol().isBlank()
                        && newDir != null && newPrice != null && newVolume != null && newVolume > 0;
                existing.set(i, new TradeLogCandidate(
                        c.symbol(), c.name(), newDir, newPrice, newVolume,
                        newDate, c.tradeTime(), c.source(), complete,
                        c.orderId(), newFee, c.id()));
                updated = true;
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
                // 2026-09-18（P0-交易59）：成交时间——空写 ""，读侧归 null（旧数据无此字段亦兼容）
                n.put("tradeTime", c.tradeTime() != null ? c.tradeTime().toString() : "");
                n.put("complete", c.complete());
                // P2-交易36（2026-09-09）：orderId/fee 可空——空写 ""，读侧空串/缺字段归 null
                n.put("orderId", c.orderId() != null ? c.orderId() : "");
                if (c.fee() != null) {
                    n.put("fee", c.fee());
                } else {
                    n.put("fee", "");
                }
                // P1-交易54（2026-09-17）：候选**行标识**（前端删除/补日期按它定位；旧数据无此字段）
                n.put("id", c.id() != null ? c.id() : "");
            }
            fileStorage.write(userId, DIR + date + ".json", MAPPER.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("保存交易日志候选失败 | userId={} | date={} | {}", userId, date, e.getMessage());
        }
    }
}
