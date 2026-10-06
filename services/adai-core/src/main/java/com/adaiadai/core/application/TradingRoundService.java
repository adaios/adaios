package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.RoundBoundary;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.RoundBoundaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TradingRoundService — 「一轮完整交易」的识别与交易规则检查
 * （RFC 20261003-trading-plan-and-review-loop §五，2026-10-03；用户授权「全做」）。
 *
 * <p><b>为什么需要它</b>：通达信「清仓股」表是**标的级总账**（首次介入 → 最后一次清仓，累计全部买卖），
 * **没有轮次概念**——生产实测 160/172 只标的的「买卖次数」等于该标的全部流水笔数（000776 = 33+16，
 * 而流水显示它实际做了 7 轮）。文件给 172 条，流水能还原出 232 轮 → 60 轮原本不可见。
 *
 * <p><b>口径（用户 2026-10-03 拍板 C）</b>：持仓归零 = 一轮边界；**同一交易日内「卖光→买回」合并为同一轮**
 * （实测 6 处，最快 82 秒后买回）。B 档「N 日内买回算同轮」不做（需定 N，易主观）。
 *
 * <p><b>建仓完毕的定义（用户给出）</b>：卖出之前的**最后一次买入** = 建仓完毕，其后 3 个交易日为观察窗
 * （R53「没涨=错」判定用）。
 *
 * <p><b>边界（用户明确要求）</b>：**只陈述事实，不作建议**——输出的是「峰值 +9.4%、最终 −25.9%、
 * 扛了 9 个交易日、命中 R55」这类事实，不是「你应该在 +9.4% 卖出」。
 *
 * <p><b>2026-10-06 rounds 批（design-final §2.3/§3②/§7）</b>：① <b>人工边界一等对象</b>——
 * {@code {symbol, 锚定日, 锚定那笔买入, source: auto|manual, 备注}}（{@link RoundBoundary}，
 * 落 {@code trading/round-boundaries.json}），人工 &gt; 自动、可撤销（auto 让位回自动切分）；
 * ② <b>不做底仓特例、如实说判不了</b>——只有卖出、没有建仓流水的段**不再静默丢弃**，
 * 如实呈现（{@code unresolved=true} + reason，成本/盈亏/过程指标一律 null，绝不编造）。
 */
@Service
public class TradingRoundService {

    private static final Logger log = LoggerFactory.getLogger(TradingRoundService.class);

    /** R72 拍板基准（2026-10-03）：止损幅度取 3-5% 中值 = −4%。
     *  为什么不用真实止损位：生产实测 1721 笔流水里带 `stopLossPrice` 的 **0 笔**——
     *  通达信「历史成交查询」与「持仓股」都没有止损位列，只能做「应然 vs 实然」对照。 */
    static final BigDecimal REVIEW_STOP_LOSS_RATIO = new BigDecimal("0.96");

    /** 无规则包时的复盘阈值兜底（真实值走 rules.yaml：reviewPeakMinPct / reviewTrapMinPct）。 */
    private static final double DEFAULT_PEAK_MIN_PCT = 3.0;
    private static final double DEFAULT_TRAP_MIN_PCT = 1.0;

    /** 未识别段的如实说明（不做底仓特例：识别不出就如实说判不了，design-final §7）。 */
    static final String UNRESOLVED_REASON =
            "只有卖出记录、没有对应的建仓买入（流水未覆盖），无法判断成本与盈亏——如实：判不了";

    private final TradingHistoryRepository tradingHistoryRepository;
    private final KlineService klineService;
    private final TradingRuleSettingsPort ruleSettingsRepository;
    private final RoundBoundaryRepository roundBoundaryRepository;

    /** 主构造（Spring）：带人工边界仓储——cut / merge / 撤销 / 备注持久化（人工 &gt; 自动）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TradingRoundService(TradingHistoryRepository tradingHistoryRepository,
                               KlineService klineService,
                               TradingRuleSettingsPort ruleSettingsRepository,
                               RoundBoundaryRepository roundBoundaryRepository) {
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.klineService = klineService;
        this.ruleSettingsRepository = ruleSettingsRepository;
        this.roundBoundaryRepository = roundBoundaryRepository;
    }

    /** 兼容构造（既有调用点/测试：无人工边界 → 纯自动口径，行为与 2026-10-03 批一致）。 */
    public TradingRoundService(TradingHistoryRepository tradingHistoryRepository,
                               KlineService klineService,
                               TradingRuleSettingsPort ruleSettingsRepository) {
        this(tradingHistoryRepository, klineService, ruleSettingsRepository, null);
    }

    // ── 对外 ──

    /** 轮次总览（按清仓日倒序）。{@code symbolFilter} 非空时只算该标的；{@code limit} 限制返回条数。 */
    public RoundsView rounds(String userId, String symbolFilter, int limit) {
        TradingRuleSettings rules = safeRules(userId);
        double peakMin = rules.reviewPeakMinPct();
        double trapMin = rules.reviewTrapMinPct();

        Map<String, List<RoundBoundary>> manualBySymbol = loadBoundaries(userId);
        Map<String, List<TradeRecord>> bySymbol = flowBySymbol(userId, symbolFilter);

        List<TradeRound> rounds = new ArrayList<>();
        for (Map.Entry<String, List<TradeRecord>> e : bySymbol.entrySet()) {
            String symbol = e.getKey();
            List<StrictRound> identified = identify(symbol, e.getValue(),
                    manualBySymbol.getOrDefault(symbol, List.of()));
            if (identified.isEmpty()) continue;
            // P1（2026-10-03 自查修复）：日线**按 symbol 一次取全**——原实现「按 symbol 缓存、范围却按轮次算」，
            // 同一只票从第二轮起拿到的是**第一轮的范围** → 峰值浮盈/3 日表现等过程指标静默为 null（规则检查失效）。
            // 影响面：生产实测 37 只标的做过 ≥2 轮（000776 做过 7 轮）。
            LocalDate minStart = null;
            LocalDate maxEnd = null;
            for (StrictRound r : identified) {
                if (minStart == null || r.start().isBefore(minStart)) minStart = r.start();
                LocalDate e2 = r.end() != null ? r.end() : LocalDate.now();
                if (maxEnd == null || e2.isAfter(maxEnd)) maxEnd = e2;
            }
            Map<LocalDate, Candle> days = loadDays(symbol, minStart.minusDays(10), maxEnd.plusDays(10));
            for (StrictRound r : identified) {
                TradeRound tr = analyze(userId, symbol, r, days, peakMin, trapMin);
                if (tr != null) rounds.add(tr);   // unresolved 段如实返回；null 仅防御（数据异常不编造）
            }
        }
        rounds.sort(Comparator.comparing(TradeRound::end, Comparator.nullsLast(Comparator.reverseOrder())));
        int total = rounds.size();
        int capped = limit > 0 && total > limit ? limit : total;
        return new RoundsView(total, rounds.subList(0, capped));
    }

    /** 流水按标的归组 + 时间序（volume=0 的分红/税行不参与轮次；symbolFilter 可选）。 */
    private Map<String, List<TradeRecord>> flowBySymbol(String userId, String symbolFilter) {
        Map<String, List<TradeRecord>> bySymbol = new LinkedHashMap<>();
        for (TradeRecord t : tradingHistoryRepository.findAll(userId)) {
            if (t.volume() <= 0 || t.entryDate() == null) continue;      // 股息/红利税行（volume=0）不参与轮次
            if (symbolFilter != null && !symbolFilter.isBlank() && !symbolFilter.equals(t.symbol())) continue;
            bySymbol.computeIfAbsent(t.symbol(), k -> new ArrayList<>()).add(t);
        }
        for (List<TradeRecord> ts : bySymbol.values()) {
            ts.sort(Comparator.comparing(TradeRecord::entryDate)
                    .thenComparing(t -> t.tradeTime() != null ? t.tradeTime() : LocalTime.MIN));
        }
        return bySymbol;
    }

    /** 该用户全部人工边界按标的归组（无仓储 → 空 map = 纯自动口径）。 */
    private Map<String, List<RoundBoundary>> loadBoundaries(String userId) {
        if (roundBoundaryRepository == null) return Map.of();
        Map<String, List<RoundBoundary>> bySymbol = new LinkedHashMap<>();
        for (RoundBoundary b : roundBoundaryRepository.findByUser(userId)) {
            bySymbol.computeIfAbsent(b.symbol(), k -> new ArrayList<>()).add(b);
        }
        return bySymbol;
    }

    // ── 对外：人工边界（POST /rounds/boundaries · PUT /rounds/{id}）──

    /**
     * 设置/更新/撤销一条人工边界：cut=从锚定买入算新的一笔 · merge=与上一笔合并 ·
     * auto=撤销人工切分（自动让位回自动口径）。锚点必须是该标的真实买入流水（否则 400 人话）；
     * note 传 null 保留旧备注、空串清除。
     */
    public BoundaryAck setBoundary(String userId, String symbol, String anchorBuyId, String mode, String note) {
        requireBoundaryRepo();
        if (symbol == null || symbol.isBlank() || anchorBuyId == null || anchorBuyId.isBlank()) {
            throw new IllegalArgumentException("symbol 与 anchorBuyId 必填（锚定哪只票的哪笔买入）");
        }
        TradeRecord anchor = findBuy(userId, symbol, anchorBuyId);
        if (anchor == null) {
            throw new IllegalArgumentException(
                    "锚定的买入不存在：anchorBuyId 必须是 " + symbol + " 的真实买入流水 id");
        }
        String newMode;
        switch (mode == null ? "" : mode) {
            case RoundBoundary.MODE_CUT -> newMode = RoundBoundary.MODE_CUT;
            case RoundBoundary.MODE_MERGE -> newMode = RoundBoundary.MODE_MERGE;
            case "auto" -> newMode = null;   // 撤销人工切分 → 自动让位
            default -> throw new IllegalArgumentException(
                    "mode 只支持 cut（从这笔算新的一笔）/ merge（与上一笔合并）/ auto（撤销）");
        }
        RoundBoundary existing = findByAnchor(userId, symbol, anchorBuyId);
        String newNote = note != null
                ? (note.isBlank() ? null : note)
                : (existing != null ? existing.note() : null);
        if (newMode == null && newNote == null) {
            roundBoundaryRepository.remove(userId, symbol, anchorBuyId);
            return new BoundaryAck(symbol, anchorBuyId, anchor.entryDate().toString(), null, null);
        }
        RoundBoundary saved = new RoundBoundary(symbol, anchorBuyId, anchor.entryDate().toString(),
                newMode, newNote, LocalDateTime.now().toString());
        roundBoundaryRepository.upsert(userId, saved);
        return new BoundaryAck(symbol, anchorBuyId, saved.anchorDate(), saved.mode(), saved.note());
    }

    /** 给某笔加/清备注（id = {symbol}_{start}，备注锚定该笔首笔买入，切分动作保留）。 */
    public void setRoundNote(String userId, String roundId, String note) {
        requireBoundaryRepo();
        int sep = roundId == null ? -1 : roundId.lastIndexOf('_');
        if (sep <= 0 || sep == roundId.length() - 1) {
            throw new IllegalArgumentException("笔 id 形状应为 {代码}_{开始日}，如 600206_2026-08-05");
        }
        String symbol = roundId.substring(0, sep);
        LocalDate start;
        try {
            start = LocalDate.parse(roundId.substring(sep + 1));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("笔 id 里的日期应为 yyyy-MM-dd：" + roundId);
        }
        List<TradeRecord> ts = flowBySymbol(userId, symbol).getOrDefault(symbol, List.of());
        List<StrictRound> identified = identify(symbol, ts,
                loadBoundaries(userId).getOrDefault(symbol, List.of()));
        StrictRound target = null;
        for (StrictRound r : identified) {
            if (roundId.equals(roundId(r.symbol(), r.start()))) { target = r; break; }
        }
        if (target == null) {
            throw new IllegalArgumentException("找不到该笔：" + roundId + "（可能流水已变、边界已重切）");
        }
        List<TradeRecord> buys = target.buys();
        if (target.initial() || buys.isEmpty() || buys.get(0).id() == null || buys.get(0).id().isBlank()) {
            throw new IllegalArgumentException("该笔只有卖出记录、没有可锚定的建仓买入——加不了备注（如实：判不了）");
        }
        String anchorBuyId = buys.get(0).id();
        RoundBoundary existing = findByAnchor(userId, symbol, anchorBuyId);
        String mode = existing != null ? existing.mode() : null;
        String newNote = note == null || note.isBlank() ? null : note;
        if (mode == null && newNote == null) {
            roundBoundaryRepository.remove(userId, symbol, anchorBuyId);
            return;
        }
        roundBoundaryRepository.upsert(userId, new RoundBoundary(symbol, anchorBuyId,
                start.toString(), mode, newNote, LocalDateTime.now().toString()));
    }

    private void requireBoundaryRepo() {
        if (roundBoundaryRepository == null) {
            throw new IllegalStateException("人工边界存储未接入");
        }
    }

    /** 在流水里找锚定买入（必须是 BUY）。 */
    private TradeRecord findBuy(String userId, String symbol, String anchorBuyId) {
        for (TradeRecord t : flowBySymbol(userId, symbol).getOrDefault(symbol, List.of())) {
            if (t.direction() == TradeDirection.BUY && anchorBuyId.equals(t.id())) return t;
        }
        return null;
    }

    private RoundBoundary findByAnchor(String userId, String symbol, String anchorBuyId) {
        for (RoundBoundary b : roundBoundaryRepository.findByUser(userId)) {
            if (b.symbol().equals(symbol) && b.anchorBuyId().equals(anchorBuyId)) return b;
        }
        return null;
    }

    // ── ① 轮次识别（口径 C + 人工边界）──

    /** 一笔的记录集（时间序）+ 切分来源（初始/人工标记）。buys/sells 按方向派生。 */
    private record StrictRound(String symbol, LocalDate start, LocalDate end,
                               List<TradeRecord> records, boolean initial,
                               boolean manual, String manualMode, String note) {

        List<TradeRecord> buys() {
            List<TradeRecord> out = new ArrayList<>();
            for (TradeRecord t : records) if (t.direction() == TradeDirection.BUY) out.add(t);
            return out;
        }

        List<TradeRecord> sells() {
            List<TradeRecord> out = new ArrayList<>();
            for (TradeRecord t : records) if (t.direction() == TradeDirection.SELL) out.add(t);
            return out;
        }

        /** 人工标记（切分被人工覆盖或带备注）。 */
        StrictRound marked(String mode, String note) {
            return new StrictRound(symbol, start, end, records, initial, true, mode, note);
        }
    }

    /** 识别一笔的完整口径：自动切分 → 应用人工边界（人工 &gt; 自动）。 */
    private List<StrictRound> identify(String symbol, List<TradeRecord> ts, List<RoundBoundary> boundaries) {
        List<StrictRound> auto = identifyAuto(symbol, ts);
        if (boundaries.isEmpty()) return auto;
        return applyManual(auto, boundaries);
    }

    /**
     * 自动切分（口径 C，用户 2026-10-03 拍板）：净额归零即一笔结束 · 同日「卖光 → 买回」合回同一笔 ·
     * 底仓卖出（无建仓流水）如实单列、相邻归并（不做底仓特例，design-final §7）。
     */
    private List<StrictRound> identifyAuto(String symbol, List<TradeRecord> ts) {
        List<StrictRound> strict = new ArrayList<>();
        LocalDate start = null;
        List<TradeRecord> records = new ArrayList<>();
        int run = 0;
        for (TradeRecord t : ts) {
            if (t.direction() == TradeDirection.BUY) {
                if (run == 0) {
                    start = t.entryDate();
                    records = new ArrayList<>();
                }
                run += t.volume();
                records.add(t);
            } else {
                if (run == 0) {
                    // 卖出但无持仓 = 流水未覆盖的底仓（不做底仓特例：如实单列，analyze 呈现「判不了」）
                    strict.add(new StrictRound(symbol, t.entryDate(), t.entryDate(),
                            List.of(t), true, false, null, null));
                    continue;
                }
                run -= t.volume();
                records.add(t);
                if (run <= 0) {
                    run = 0;
                    strict.add(new StrictRound(symbol, start, t.entryDate(),
                            List.copyOf(records), false, false, null, null));
                    records = new ArrayList<>();
                }
            }
        }
        if (!records.isEmpty()) {
            strict.add(new StrictRound(symbol, start, null, List.copyOf(records), false, false, null, null));
        }
        // 口径 C：同一交易日内「卖光 → 买回」合并为同一轮（含「底仓卖出 → 同日买回」）
        List<StrictRound> merged = new ArrayList<>();
        for (StrictRound r : strict) {
            StrictRound last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.end() != null && last.end().equals(r.start()) && hasBuy(r.records())) {
                List<TradeRecord> recs = new ArrayList<>(last.records());
                recs.addAll(r.records());
                // P1（独立审查 2026-10-03 修复）：合并后只要已有建仓流水，就不再是「初始批次」——
                // 原实现继承 last.initial()，导致同日「底仓卖出 → 买回」整轮被判初始而**直接丢弃**（连 total 都不计）。
                merged.set(merged.size() - 1, new StrictRound(symbol, last.start(), r.end(),
                        List.copyOf(recs), !hasBuy(recs), false, null, null));
            } else {
                merged.add(r);
            }
        }
        // 相邻「未识别」段归并一条（start=首卖、end=末卖）——如实呈现，不逐笔刷屏
        List<StrictRound> compacted = new ArrayList<>();
        for (StrictRound r : merged) {
            StrictRound last = compacted.isEmpty() ? null : compacted.get(compacted.size() - 1);
            if (last != null && last.initial() && r.initial()) {
                List<TradeRecord> recs = new ArrayList<>(last.records());
                recs.addAll(r.records());
                compacted.set(compacted.size() - 1, new StrictRound(symbol, last.start(), r.end(),
                        List.copyOf(recs), true, false, null, null));
            } else {
                compacted.add(r);
            }
        }
        return compacted;
    }

    // ── ①b 人工边界应用（人工 > 自动；可追溯 / 可撤销 / 自动让位）──

    /** 按操作顺序逐条应用人工边界（boundaries 按标的分组，存入顺序 = 操作顺序，后操作生效）。 */
    private List<StrictRound> applyManual(List<StrictRound> auto, List<RoundBoundary> boundaries) {
        List<StrictRound> result = auto;
        for (RoundBoundary b : boundaries) {
            if (RoundBoundary.MODE_CUT.equals(b.mode())) {
                result = applyCut(result, b);
            } else if (RoundBoundary.MODE_MERGE.equals(b.mode())) {
                result = applyMerge(result, b);
            } else {
                result = applyNote(result, b);   // mode=null：只加备注，不动切分
            }
        }
        return result;
    }

    /** 「从这笔买入开始算新的一笔」：锚定买入所在的笔切开，两段各自重走自动口径（净额/合并）。 */
    private List<StrictRound> applyCut(List<StrictRound> rounds, RoundBoundary b) {
        for (int i = 0; i < rounds.size(); i++) {
            StrictRound r = rounds.get(i);
            int idx = recordIndex(r, b.anchorBuyId());
            if (idx < 0) continue;
            List<StrictRound> out = new ArrayList<>(rounds);
            if (idx == 0) {
                // 锚定买入已是这笔的起点 = 边界显式化（人工确认；自动让位为人工）
                out.set(i, r.marked(RoundBoundary.MODE_CUT, b.note()));
                return out;
            }
            List<TradeRecord> records = r.records();
            List<StrictRound> head = identifyAuto(r.symbol(), records.subList(0, idx));
            List<StrictRound> tail = identifyAuto(r.symbol(), records.subList(idx, records.size()));
            out.remove(i);
            int pos = i;
            for (StrictRound h : head) out.add(pos++, h.marked(RoundBoundary.MODE_CUT, b.note()));
            for (StrictRound t : tail) out.add(pos++, t.marked(RoundBoundary.MODE_CUT, b.note()));
            return out;
        }
        return rounds;
    }

    /** 「与上一笔合并」：锚定买入所在的笔与上一笔并成一笔；没有上一笔 → 如实 no-op。 */
    private List<StrictRound> applyMerge(List<StrictRound> rounds, RoundBoundary b) {
        for (int i = 0; i < rounds.size(); i++) {
            StrictRound r = rounds.get(i);
            if (recordIndex(r, b.anchorBuyId()) < 0) continue;
            if (i == 0) return rounds;
            StrictRound prev = rounds.get(i - 1);
            List<TradeRecord> recs = new ArrayList<>(prev.records());
            recs.addAll(r.records());
            List<StrictRound> out = new ArrayList<>(rounds);
            out.set(i - 1, new StrictRound(r.symbol(), prev.start(), r.end(),
                    List.copyOf(recs), !hasBuy(recs), true, RoundBoundary.MODE_MERGE, b.note()));
            out.remove(i);
            return out;
        }
        return rounds;
    }

    /** 仅备注（不动切分）。 */
    private List<StrictRound> applyNote(List<StrictRound> rounds, RoundBoundary b) {
        for (int i = 0; i < rounds.size(); i++) {
            StrictRound r = rounds.get(i);
            if (recordIndex(r, b.anchorBuyId()) < 0) continue;
            List<StrictRound> out = new ArrayList<>(rounds);
            out.set(i, new StrictRound(r.symbol(), r.start(), r.end(), r.records(), r.initial(),
                    r.manual(), r.manualMode(), b.note()));
            return out;
        }
        return rounds;
    }

    /** 锚定买入在该笔记录里的下标（限定 BUY）；不在 → -1。 */
    private static int recordIndex(StrictRound r, String buyId) {
        if (buyId == null || buyId.isBlank()) return -1;
        List<TradeRecord> records = r.records();
        for (int i = 0; i < records.size(); i++) {
            TradeRecord t = records.get(i);
            if (buyId.equals(t.id()) && t.direction() == TradeDirection.BUY) return i;
        }
        return -1;
    }

    private static boolean hasBuy(List<TradeRecord> records) {
        for (TradeRecord t : records) if (t.direction() == TradeDirection.BUY) return true;
        return false;
    }

    // ── ② 过程指标 + ③ 规则检查 ──

    /** 某交易日的成本 = 该日或之前**最后一个有买入日**的加权成本；早于首笔买入则用首笔成本。 */
    private static BigDecimal dayCostAt(java.util.TreeMap<LocalDate, BigDecimal> costByDay,
                                        LocalDate d, BigDecimal fallback) {
        Map.Entry<LocalDate, BigDecimal> e = costByDay.floorEntry(d);
        return e != null ? e.getValue() : fallback;
    }

    /** 取某标的在 [from, to] 的日线；失败降级为空表（过程指标如实为 null，**不编造**）。 */
    private Map<LocalDate, Candle> loadDays(String symbol, LocalDate from, LocalDate to) {        Map<LocalDate, Candle> m = new LinkedHashMap<>();
        try {
            for (Candle c : klineService.klineRange(symbol, from, to)) m.put(c.date(), c);
        } catch (RuntimeException ex) {
            log.warn("轮次复盘取日线失败（该标本的过程指标降级为 null）| symbol={} | {}", symbol, ex.getMessage());
        }
        return m;
    }

    private TradeRound analyze(String userId, String symbol, StrictRound r,
                               Map<LocalDate, Candle> days,
                               double peakMin, double trapMin) {
        // 不做底仓特例（design-final §7）：只有卖出、没有建仓流水的段如实呈现「判不了」，不再静默丢弃
        if (r.initial() || r.buys().isEmpty()) return unresolvedRound(symbol, r);

        List<TradeRecord> buys = r.buys();
        List<TradeRecord> sells = r.sells();
        BigDecimal buyAmount = BigDecimal.ZERO;
        int buyVolume = 0;
        for (TradeRecord b : buys) {
            BigDecimal fee = b.fee() != null ? b.fee() : BigDecimal.ZERO;
            buyAmount = buyAmount.add(b.price().multiply(BigDecimal.valueOf(b.volume()))).add(fee);
            buyVolume += b.volume();
        }
        if (buyVolume == 0) return null;
        BigDecimal avgCost = buyAmount.divide(BigDecimal.valueOf(buyVolume), 4, RoundingMode.HALF_UP);

        BigDecimal sellAmount = BigDecimal.ZERO;
        for (TradeRecord s : sells) {
            BigDecimal fee = s.fee() != null ? s.fee() : BigDecimal.ZERO;
            sellAmount = sellAmount.add(s.price().multiply(BigDecimal.valueOf(s.volume()))).subtract(fee);
        }
        BigDecimal pnl = sellAmount.subtract(buyAmount);
        BigDecimal pnlPct = pct(pnl, buyAmount);

        String name = buys.get(0).name() != null && !buys.get(0).name().isBlank()
                ? buys.get(0).name() : symbol;
        LocalDate lastBuy = buys.get(buys.size() - 1).entryDate();

        LocalDate end = r.end();
        List<LocalDate> inRound = new ArrayList<>();
        for (LocalDate d : days.keySet()) {
            if (!d.isBefore(r.start()) && (end == null || !d.isAfter(end))) inRound.add(d);
        }
        inRound.sort(Comparator.naturalOrder());

        BigDecimal peakPct = null;
        LocalDate peakDate = null;
        BigDecimal troughPct = null;
        // P1-5（独立审查 2026-10-03 修复）：**当日成本**逐日推进，不再拿全轮摊薄后的平均成本去比每日 high/low——
        // 摊薄成本会把加仓前的价格算成浮盈 → R55（盈转亏）系统性多报。
        // 口径：某日成本 = 截至该日（含）全部买入的加权均价（含费）；卖出不改成本价（只改数量）。
        java.util.TreeMap<LocalDate, BigDecimal> costByDay = new java.util.TreeMap<>();
        BigDecimal cumAmt0 = BigDecimal.ZERO;
        int cumVol0 = 0;
        for (TradeRecord b : buys) {
            BigDecimal fee = b.fee() != null ? b.fee() : BigDecimal.ZERO;
            cumAmt0 = cumAmt0.add(b.price().multiply(BigDecimal.valueOf(b.volume()))).add(fee);
            cumVol0 += b.volume();
            costByDay.put(b.entryDate(), cumAmt0.divide(BigDecimal.valueOf(cumVol0), 4, RoundingMode.HALF_UP));
        }
        BigDecimal firstCost = costByDay.isEmpty() ? avgCost : costByDay.firstEntry().getValue();
        for (LocalDate d : inRound) {
            Candle c = days.get(d);
            BigDecimal dayCost = dayCostAt(costByDay, d, firstCost);
            BigDecimal up = pct(BigDecimal.valueOf(c.high()).subtract(dayCost), dayCost);
            BigDecimal dn = pct(BigDecimal.valueOf(c.low()).subtract(dayCost), dayCost);
            // P2-交易98：pct 的 base 缺/为 0 时出 null（不编造 0）——null 时不参与峰/谷取值
            if (up != null && (peakPct == null || up.compareTo(peakPct) > 0)) { peakPct = up; peakDate = d; }
            if (dn != null && (troughPct == null || dn.compareTo(troughPct) < 0)) troughPct = dn;
        }

        // 加仓（第 2 笔起的每次买入）时是否被套：该日最低价 < 当时加权成本 ×(1 − trapMin%)
        int addOn = 0;
        int addOnTrapped = 0;
        BigDecimal cumAmt = BigDecimal.ZERO;
        int cumVol = 0;
        for (int i = 0; i < buys.size(); i++) {
            TradeRecord b = buys.get(i);
            if (i > 0) {
                addOn++;
                Candle c = days.get(b.entryDate());
                if (c != null && cumVol > 0) {
                    BigDecimal costThen = cumAmt.divide(BigDecimal.valueOf(cumVol), 4, RoundingMode.HALF_UP);
                    BigDecimal trapLine = costThen.multiply(BigDecimal.valueOf(1 - trapMin / 100.0));
                    if (BigDecimal.valueOf(c.low()).compareTo(trapLine) < 0) addOnTrapped++;
                }
            }
            BigDecimal fee = b.fee() != null ? b.fee() : BigDecimal.ZERO;
            cumAmt = cumAmt.add(b.price().multiply(BigDecimal.valueOf(b.volume()))).add(fee);
            cumVol += b.volume();
        }

        // R53：建仓完毕（最后一次买入）后第 3 个交易日相对成本
        // P3（独立审查 2026-10-03 修复）：限定在**轮内**（越过清仓日就是别的轮次的地盘）。
        List<LocalDate> after = new ArrayList<>();
        for (LocalDate d : days.keySet()) {
            if (d.isAfter(lastBuy) && (end == null || !d.isAfter(end))) after.add(d);
        }
        after.sort(Comparator.naturalOrder());
        BigDecimal d3Pct = null;
        if (after.size() >= 3) {
            Candle c3 = days.get(after.get(2));
            BigDecimal cost3 = dayCostAt(costByDay, after.get(2), firstCost);
            d3Pct = pct(BigDecimal.valueOf(c3.close()).subtract(cost3), cost3);
        }

        // R66：收盘跌破 R72 基准止损位（−4%）后是否当日未走
        // P2（独立审查 2026-10-03 修复）：止损线也按**当日成本**算（原来用全轮摊薄成本 → 首次跌破日失真，
        // 与 R69 的「当时成本」口径不一致）。
        LocalDate breachDate = null;
        for (LocalDate d : inRound) {
            Candle c = days.get(d);
            BigDecimal dayStop = dayCostAt(costByDay, d, firstCost)
                    .multiply(REVIEW_STOP_LOSS_RATIO).setScale(4, RoundingMode.HALF_UP);
            if (BigDecimal.valueOf(c.close()).compareTo(dayStop) < 0) { breachDate = d; break; }
        }
        Integer breachHoldDays = null;
        if (breachDate != null && end != null) {
            int n = 0;
            for (LocalDate d : inRound) if (!d.isBefore(breachDate)) n++;
            breachHoldDays = n;
        }

        List<RuleHit> hits = new ArrayList<>();
        if (end != null) {
            if (peakPct != null && peakPct.doubleValue() >= peakMin && pnl.signum() < 0) {
                hits.add(new RuleHit("R55", "盈转亏没走（曾浮盈 " + trim(peakPct) + "% 却亏损收场）",
                        "峰值 " + trim(peakPct) + "%@" + peakDate + " → 最终 "
                                + (pnlPct != null ? trim(pnlPct) + "%" : "—")));   // P2-交易98：pnlPct 可空（base 缺/0 不出 0）
            }
            if (addOnTrapped > 0) {
                hits.add(new RuleHit("R69", "被套时加仓（R90 短线不加仓同源）",
                        addOnTrapped + " 次加仓发生在浮亏 >" + trim(BigDecimal.valueOf(trapMin)) + "% 时（共加仓 " + addOn + " 次）"));
            }
            if (breachHoldDays != null && breachHoldDays > 1) {
                hits.add(new RuleHit("R66", "收盘跌破止损位后未当日走（按当日成本 −4%，R72 取中值）",
                        "首次跌破 " + breachDate + " → 清仓 " + end + "，多扛 " + breachHoldDays + " 个交易日"));
            }
            if (d3Pct != null && d3Pct.signum() <= 0) {
                hits.add(new RuleHit("R53", "建仓完毕后 3 个交易日仍未脱离成本区",
                        "第 3 日 " + trim(d3Pct) + "%（建仓完毕 " + lastBuy + "）"));
            }
        }

        int holdDays = end != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(r.start(), end) : 0;
        return new TradeRound(roundId(symbol, r.start()), symbol, name, r.start(), lastBuy, end,
                holdDays, inRound.size(),
                buys.size(), sells.size(), buyAmount.setScale(2, RoundingMode.HALF_UP),
                pnl.setScale(2, RoundingMode.HALF_UP), trim(pnlPct), trim(peakPct), peakDate,
                trim(troughPct), addOn, addOnTrapped, trim(d3Pct), hits,
                false, null, boundaryView(symbol, r));
    }

    /**
     * 未识别段（只有卖出、没有建仓流水）：如实呈现、明说判不了——
     * 成本/盈亏/过程指标一律 null（绝不编造），命中规则为空，total 照计。
     */
    private static TradeRound unresolvedRound(String symbol, StrictRound r) {
        List<TradeRecord> sells = r.sells();
        TradeRecord first = sells.isEmpty() ? null : sells.get(0);
        String name = first != null && first.name() != null && !first.name().isBlank()
                ? first.name() : symbol;
        int holdDays = r.start() != null && r.end() != null
                ? (int) java.time.temporal.ChronoUnit.DAYS.between(r.start(), r.end()) : 0;
        int tradeDays = (int) sells.stream().map(TradeRecord::entryDate).distinct().count();
        return new TradeRound(roundId(symbol, r.start()), symbol, name, r.start(), null, r.end(),
                holdDays, tradeDays, 0, sells.size(),
                null, null, null, null, null, null, 0, 0, null,
                List.of(), true, UNRESOLVED_REASON, boundaryView(symbol, r));
    }

    /** 边界对象视图：source=manual 表示该笔切分被人工覆盖（cut/merge）；note 独立于切分。 */
    private static RoundBoundaryView boundaryView(String symbol, StrictRound r) {
        List<TradeRecord> buys = r.buys();
        String anchorBuyId = buys.isEmpty() ? null : buys.get(0).id();
        return new RoundBoundaryView(symbol,
                r.start() != null ? r.start().toString() : null,
                anchorBuyId,
                r.manual() ? "manual" : "auto",
                r.manualMode(),
                r.note());
    }

    /** 笔 id：{symbol}_{start}（如 600206_2026-08-05）——PUT /rounds/{id} 与前端列表的稳定引用。 */
    static String roundId(String symbol, LocalDate start) {
        return start == null ? symbol : symbol + "_" + start;
    }

    private TradingRuleSettings safeRules(String userId) {
        try {
            TradingRuleSettings s = ruleSettingsRepository.findByUser(userId);
            if (s != null) return s;
        } catch (RuntimeException e) {
            log.warn("轮次复盘读规则包失败，用默认阈值 | userId={} | {}", userId, e.getMessage());
        }
        return TradingRuleSettings.defaults();
    }

    /**
     * 百分比（delta/base×100，2 位）。base 缺 / 为 0 → <b>null</b>（缺数据一律出 null、不编造 0——
     * 设计契约①/验收 5，P2-交易98；原实现返回 {@code ZERO}，把「算不了」伪装成「0%」）。
     * 包内可见：契约由 {@code TradingRoundServiceTest} 直接锁死。
     */
    static BigDecimal pct(BigDecimal delta, BigDecimal base) {
        if (base == null || base.signum() == 0) return null;
        return delta.multiply(BigDecimal.valueOf(100))
                .divide(base, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal trim(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().scale() <= 0
                ? v.setScale(0, RoundingMode.HALF_UP) : v;
    }

    // ── 视图对象 ──

    /** 轮次总览：总数（未截断）+ 明细。 */
    public record RoundsView(int total, List<TradeRound> rounds) {}

    /**
     * 一轮完整交易（口径 C）。{@code end} 为 null = 尚未平仓（当前持仓）。
     * 所有百分比字段为百分数（+9.4 = 浮盈 9.4%），null = 数据不足（**不编造 0**）。
     *
     * <p>2026-10-06 rounds 批：{@code id}（{symbol}_{start}）稳定引用该笔；
     * {@code unresolved=true} = 只有卖出、没有建仓流水的未识别段（成本/盈亏/过程指标全 null，
     * 看 {@code reason} 如实说明）；{@code boundary} = 边界一等对象（source=auto|manual）。
     */
    public record TradeRound(
            String id,
            String symbol, String name, LocalDate start, LocalDate lastBuy, LocalDate end,
            int holdDays, int tradeDays, int buyCount, int sellCount,
            BigDecimal buyAmount, BigDecimal pnl, BigDecimal pnlPct,
            BigDecimal peakPct, LocalDate peakDate, BigDecimal troughPct,
            int addOnCount, int addOnTrappedCount, BigDecimal d3Pct,
            List<RuleHit> hits,
            boolean unresolved, String reason,
            RoundBoundaryView boundary) {}

    /**
     * 边界对象视图（设计稿 §7：{symbol, 锚定日, 锚定那笔买入, source: auto|manual, 备注}）。
     * 锚定日 / 锚定那笔买入 = 该笔的**开始点**（首笔买入）；source=manual = 切分被人工覆盖（cut/merge）。
     */
    public record RoundBoundaryView(String symbol, String anchorDate, String anchorBuyId,
                                    String source, String mode, String note) {}

    /** 人工边界操作回执（POST /rounds/boundaries 对外形状）。 */
    public record BoundaryAck(String symbol, String anchorBuyId, String anchorDate,
                              String mode, String note) {}

    /** 规则命中（只陈述事实：命中了哪条 + 过程数据）。 */
    public record RuleHit(String rule, String text, String data) {}
}
