package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TradingKlineAppService —— R-04 通用 K 线（2026-10-07 晚，设计见
 * {@code .agents/workspace/trading-plugin/design-kline-r04-20261007.md}）。
 *
 * <p><b>一张图，四处共用</b>：持仓 / 自选 / 清仓 / 案例 —— 同样的 K 线，不同的标记。
 * 本服务只负责「把图要的东西凑齐」：蜡烛 + 我的买卖点 + 你定的止损线 + 峰值浮盈线 + 上下文。
 * 副图（成交量 / MACD / KDJ）不在这里算 —— 与生产案例图同口径，**前端从 OHLCV 重算**
 * （对齐后端 {@code KdjIndicator} / {@code MacdIndicator}）。
 *
 * <p><b>诚实口径</b>（承接需求「缺数据不编」）：行情取不到 → 返回空 candles + 一句人话 note，
 * 绝不沿用旧价凑一张图；没有买卖点就不给标记，不硬编。
 */
@Service
public class TradingKlineAppService {

    /**
     * 峰值浮盈线 = 持有期内最高收盘 × (1 − 回吐阈值)。
     * <p><b>口径待收紧</b>：这里取固定 5%；`rules.yaml` 的 {@code givebackPeakPct} 接入后改为读用户设置
     * （接入前不静默改口径 —— 所以返回值里带 {@code basis} 说明用的是哪一档）。
     */
    private static final BigDecimal PEAK_GIVEBACK = new BigDecimal("0.05");

    private static final int DEFAULT_WINDOW = 90;
    private static final int MIN_WINDOW = 30;
    private static final int MAX_WINDOW = 400;

    private final KlineService klineService;
    private final TradingHistoryRepository historyRepository;
    private final PositionRepository positionRepository;
    private final SoldTradeRepository soldTradeRepository;
    /** 批次止损来源（2026-10-08 补：原实现只读成交记录，见 {@link #stopLine}）。 */
    private final TradingLotService lotService;

    public TradingKlineAppService(KlineService klineService,
                                  TradingHistoryRepository historyRepository,
                                  PositionRepository positionRepository,
                                  SoldTradeRepository soldTradeRepository,
                                  TradingLotService lotService) {
        this.klineService = klineService;
        this.historyRepository = historyRepository;
        this.positionRepository = positionRepository;
        this.soldTradeRepository = soldTradeRepository;
        this.lotService = lotService;
    }

    /** 组装一张图的全部原料。window 为交易日根数（30~400，默认 90）。 */
    public Map<String, Object> kline(String userId, String symbol, Integer window) {
        Map<String, Object> out = new LinkedHashMap<>();
        String sym = normalizeSymbol(symbol);
        out.put("symbol", sym == null ? "" : sym);
        int limit = clampWindow(window);
        out.put("window", limit);

        if (sym == null) {
            out.put("candles", List.of());
            out.put("marks", List.of());
            out.put("stopLine", null);
            out.put("peakLine", null);
            out.put("context", Map.of("held", false));
            out.put("note", "代码要 6 位数字");
            return out;
        }

        List<Candle> candles = klineService.kline(sym, limit);
        if (candles == null || candles.isEmpty()) {
            out.put("candles", List.of());
            out.put("marks", List.of());
            out.put("stopLine", null);
            out.put("peakLine", null);
            out.put("context", Map.of("held", false));
            out.put("note", "暂时取不到这只票的行情");
            return out;
        }

        List<Map<String, Object>> candleJson = new ArrayList<>(candles.size());
        for (Candle c : candles) candleJson.add(candleJson(c));
        out.put("candles", candleJson);

        LocalDate from = candles.get(0).date();
        LocalDate to = candles.get(candles.size() - 1).date();
        List<TradeRecord> trades = tradesIn(userId, sym, from, to);

        out.put("marks", marks(trades));
        out.put("stopLine", stopLine(userId, sym, trades));
        out.put("peakLine", peakLine(trades, candles));
        out.put("context", context(userId, sym, trades));
        return out;
    }

    // ── 我的买卖点：B 建仓 / T 加仓 / S 卖出（同日同向合并，按成交先后推导） ──

    private List<Map<String, Object>> marks(List<TradeRecord> trades) {
        List<Map<String, Object>> marks = new ArrayList<>();
        int holding = 0;
        int i = 0;
        while (i < trades.size()) {
            TradeRecord head = trades.get(i);
            if (head.direction() == null || head.entryDate() == null) {
                i++;
                continue;
            }
            // 2026-10-08 用户拍板：**同一天、同一方向的成交合成一条** ——
            // 同一天买三笔在图上只该有一个点（否则图上一堆重影标记，看不出「哪一天动过」）。
            final LocalDate day = head.entryDate();
            final boolean buy = head.direction() == TradeDirection.BUY;
            int qty = 0;
            BigDecimal amount = BigDecimal.ZERO;
            int j = i;
            while (j < trades.size()) {
                TradeRecord t = trades.get(j);
                if (t.direction() == null || t.entryDate() == null) break;
                if (!day.equals(t.entryDate())) break;
                if ((t.direction() == TradeDirection.BUY) != buy) break;
                qty += t.volume();
                if (t.price() != null) {
                    amount = amount.add(t.price().multiply(BigDecimal.valueOf(t.volume())));
                }
                j++;
            }
            String type = buy ? (holding <= 0 ? "B" : "T") : "S";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("type", type);
            // 合并后的价格 = 加权均价（不是随便取一笔）
            m.put("price", qty > 0 && amount.signum() > 0
                    ? amount.divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP).doubleValue()
                    : null);
            m.put("quantity", qty);
            m.put("tradeId", head.id());
            m.put("note", buy ? ("B".equals(type) ? "买入" : "加仓") : "卖出");
            marks.add(m);
            holding = Math.max(0, holding + (buy ? qty : -qty));
            i = j;
        }
        return marks;
    }

    // ── 你定的止损：先看成交记录，再看**批次止损**（有就画，没有就不画） ──

    private Map<String, Object> stopLine(String userId, String symbol, List<TradeRecord> trades) {
        // ① 成交记录里最近一次买入定下的那条
        for (int i = trades.size() - 1; i >= 0; i--) {
            TradeRecord t = trades.get(i);
            if (t.direction() == TradeDirection.BUY && t.stopLossPrice() != null) {
                return line(t.stopLossPrice(), t.entryDate(), "你定的止损");
            }
        }
        // ② 批次止损 —— 2026-10-08 补。原实现只看了 ①，于是「在 app 里给某一批单独设过止损」
        //    的票在图上画不出线（本地实测：云南锗业 stopLine=null，而它设过批次止损）。
        try {
            TradingLotService.TradingLotView hit = null;
            for (TradingLotService.TradingLotView v : lotService.lots(userId, "open")) {
                if (!symbol.equals(v.symbol()) || v.closed() || v.stopLossPrice() == null) continue;
                if (hit == null
                        || (v.buyDate() != null && hit.buyDate() != null && v.buyDate().isAfter(hit.buyDate()))) {
                    hit = v;
                }
            }
            if (hit != null) return line(hit.stopLossPrice(), hit.buyDate(), "你给这一批定的止损");
        } catch (Exception e) {
            // 批次推导失败 → 如实当作「没定过」，不凭空给一条线（承接「缺数据不编」）
        }
        return null;
    }

    private Map<String, Object> line(BigDecimal price, LocalDate from, String note) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("price", price.doubleValue());
        line.put("from", from == null ? null : from.toString());
        line.put("note", note);
        return line;
    }

    // ── 峰值浮盈线：持有期内最高收盘 × (1 − 回吐阈值)；没有持有期就没有这条线 ──

    private Map<String, Object> peakLine(List<TradeRecord> trades, List<Candle> candles) {
        LocalDate holdFrom = null;
        LocalDate holdTo = null;
        int holding = 0;
        for (TradeRecord t : trades) {
            if (t.direction() == null || t.entryDate() == null) continue;
            boolean buy = t.direction() == TradeDirection.BUY;
            holding = Math.max(0, holding + (buy ? t.volume() : -t.volume()));
            if (buy && holdFrom == null) holdFrom = t.entryDate();
            if (!buy && holding == 0) holdTo = t.entryDate();
        }
        if (holdFrom == null) return null;

        LocalDate end = holdTo != null ? holdTo : candles.get(candles.size() - 1).date();
        Candle peak = null;
        for (Candle c : candles) {
            if (c.date().isBefore(holdFrom) || c.date().isAfter(end)) continue;
            if (peak == null || c.close() > peak.close()) peak = c;
        }
        if (peak == null) return null;

        BigDecimal peakPrice = BigDecimal.valueOf(peak.close());
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("price", peakPrice.multiply(BigDecimal.ONE.subtract(PEAK_GIVEBACK))
                .setScale(2, RoundingMode.HALF_UP).doubleValue());
        line.put("peakPrice", peakPrice.setScale(2, RoundingMode.HALF_UP).doubleValue());
        line.put("peakDate", peak.date().toString());
        line.put("basis", "-5% 浮盈回吐（当前口径）");
        line.put("note", "峰值浮盈线");
        return line;
    }

    // ── 上下文：现在还拿着吗 / 什么时候清的 ──

    private Map<String, Object> context(String userId, String symbol, List<TradeRecord> trades) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        Optional<Position> pos = positionRepository.findBySymbol(userId, symbol);
        boolean held = pos.isPresent() && pos.get().quantity() > 0;
        ctx.put("held", held);
        ctx.put("tradeCount", trades.size());
        if (!held) {
            SoldTrade sold = null;
            List<SoldTrade> all = soldTradeRepository.findAll(userId);
            if (all != null) {
                for (SoldTrade s : all) {
                    if (s == null || !symbol.equals(s.symbol())) continue;
                    if (sold == null || (s.sellDate() != null && sold.sellDate() != null
                            && s.sellDate().isAfter(sold.sellDate()))) {
                        sold = s;
                    }
                }
            }
            if (sold != null) {
                ctx.put("closedAt", sold.sellDate() == null ? null : sold.sellDate().toString());
                ctx.put("holdDays", sold.holdDays());
                ctx.put("holdPnlPct", sold.holdPnlPct());
                ctx.put("verdict", sold.verdict());
            }
        }
        return ctx;
    }

    // ── 取数 ──

    private List<TradeRecord> tradesIn(String userId, String symbol, LocalDate from, LocalDate to) {
        List<TradeRecord> all = historyRepository.findAll(userId);
        if (all == null || all.isEmpty()) return List.of();
        List<TradeRecord> hit = new ArrayList<>();
        for (TradeRecord t : all) {
            if (t == null || !symbol.equals(t.symbol()) || t.entryDate() == null) continue;
            if (t.entryDate().isBefore(from) || t.entryDate().isAfter(to)) continue;
            hit.add(t);
        }
        hit.sort(Comparator.comparing(TradeRecord::entryDate));
        return hit;
    }

    private Map<String, Object> candleJson(Candle c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", c.date() == null ? null : c.date().toString());
        m.put("open", c.open());
        m.put("high", c.high());
        m.put("low", c.low());
        m.put("close", c.close());
        m.put("volume", c.volume());
        return m;
    }

    /** 只认 6 位数字（东财/腾讯/tdx 都是这个口径）；认不出返回 null，由调用方如实说明。 */
    private String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim();
        return s.matches("\\d{6}") ? s : null;
    }

    private int clampWindow(Integer window) {
        int w = window == null ? DEFAULT_WINDOW : window;
        return Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, w));
    }
}
