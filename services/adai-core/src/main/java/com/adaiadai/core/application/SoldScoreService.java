package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.BuyPointDetector;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.market.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * SoldScoreService — 清仓复盘三维打分（D3，2026-08-16）。
 * <p>
 * RFC 20260816 §3.4：选股 / 买点 / 执行 三维评分，形成可积累的复盘分数。
 * <ul>
 *   <li><b>买点维度</b>：买入日回溯 K 线 → B1/B2 完美图匹配度（BuyPointDetector 复用）</li>
 *   <li><b>执行维度</b>：verdict 纪律对照（盈利了结=守纪律，R66 扛单/R53 短亏=违反）</li>
 *   <li><b>选股维度</b>：关注后表现（需关注历史数据积累，当前返回 null 待积累）</li>
 * </ul>
 * 分数是参考不是指令——复盘用，买不买永远人决定。
 * 并发说明：拉 K 线是网络 IO，逐笔串行 162 笔 ≈ 56s；16 并发线程池 → ~5s（K 线源有按日缓存）。
 */
@Service
public class SoldScoreService {

    private static final Logger log = LoggerFactory.getLogger(SoldScoreService.class);

    private final KlineService klineService;
    private final ExecutorService klinePool = Executors.newFixedThreadPool(16);

    public SoldScoreService(KlineService klineService) {
        this.klineService = klineService;
    }

    /** P2-交易1（2026-08-17）：应用关闭时优雅关闭线程池（B53 检查点）。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        klinePool.shutdown();
        try {
            if (!klinePool.awaitTermination(5, TimeUnit.SECONDS)) {
                klinePool.shutdownNow();
            }
        } catch (InterruptedException e) {
            klinePool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("SoldScoreService K 线线程池已关闭");
    }

    /** 单笔清仓的三维打分结果。 */
    public record SoldScore(String symbol, String name,
                            Integer buyPointScore, String buyPointSignal, String buyPointExplain,
                            Integer executionScore, String executionExplain,
                            Double totalScore, String verdict) {}

    /** 批量打分（按清仓列表顺序返回；K 线拉取 16 并发）。 */
    public List<SoldScore> score(List<SoldTrade> trades) {
        List<SoldScore> result = new ArrayList<>();
        List<Future<SoldScore>> futures = new ArrayList<>();
        for (SoldTrade t : trades) {
            futures.add(klinePool.submit(() -> scoreOne(t)));
        }
        // P2-交易1：超时/失败不产空 symbol 占位行——返回该笔 symbol + 分数 null（前端显示 '—'，不糊弄）
        for (int i = 0; i < futures.size(); i++) {
            SoldTrade t = trades.get(i);
            try {
                result.add(futures.get(i).get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.warn("打分单笔超时/失败 | symbol={} | {}", t.symbol(), e.getMessage());
                result.add(new SoldScore(t.symbol(), t.name(), null, null, "K 线拉取失败",
                        executionScore(t), executionExplain(t), null, t.verdict()));
            }
        }
        return result;
    }

    private SoldScore scoreOne(SoldTrade t) {
        BuyPointDetector.BuyPointResult bp = buyPointAt(t);
        Integer buyScore = bp == null ? null : buyPointScore(bp);
        Integer execScore = executionScore(t);
        Double total = (buyScore != null && execScore != null)
                ? (buyScore * 0.5 + execScore * 0.5) : null;
        return new SoldScore(t.symbol(), t.name(),
                buyScore, bp == null ? null : bp.buyPoint(),
                bp == null ? "买入日 K 线不足，无法回溯" : (bp.hit() ? String.join("、", bp.signals()) : "无买点形态（追高/随意进）"),
                execScore, executionExplain(t),
                total, t.verdict());
    }

    /** 回溯买入日：拉日 K，截取到买入日当天为止 → 判定当时买点信号。 */
    private BuyPointDetector.BuyPointResult buyPointAt(SoldTrade t) {
        if (t.buyDate() == null) return null;
        List<Candle> candles = klineService.kline(t.symbol(), 250);
        if (candles.isEmpty()) return null;
        int idx = -1;
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).date().equals(t.buyDate())) { idx = i; break; }
        }
        if (idx < 0) return null; // 买入日超出 K 线范围（数据源只回溯近 1 年）
        List<Candle> uptoBuy = new ArrayList<>(candles.subList(0, idx + 1));
        // K 线不足 detector 最小长度（25 根）→ 无法判定，返回 null（数据不足不评分，不误判追高）
        if (uptoBuy.size() < 25) return null;
        return new BuyPointDetector(0.5, 0.7, 13, 1.5, 20).detect(uptoBuy);
    }

    /** 买点维度分（完美图匹配度）：B2 突破 / B1 低吸 / B1? 候选 / 无形态。 */
    private int buyPointScore(BuyPointDetector.BuyPointResult r) {
        switch (r.buyPoint()) {
            case "B2": return 85 + (int) Math.min(15, r.score() * 0.15);
            case "B1": return 70 + (int) Math.min(30, r.score() * 0.3);
            case "B1?": return 50;
            default: return 25; // 无买点形态买入（追高/随意进）
        }
    }

    /** 执行维度分（verdict 纪律对照——P3 2026-08-17：R85 实为「分仓 vs 重仓」，纪律决定对错来自复盘五步法，不再误挂 R85 编号）。 */
    private int executionScore(SoldTrade t) {
        String v = t.verdict();
        if (v.contains("R66")) return 15;   // 扛单超 5%——止损纪律违反（阈值已对齐课程 R67/R72 3-5%）
        if (v.contains("R53")) return 45;   // 短持仓亏损——该涨不涨没处理
        if (t.holdPnlPct() >= 0) return 90; // 盈利了结
        return 65;                          // 亏损但按纪律执行
    }

    private String executionExplain(SoldTrade t) {
        if (t.verdict().contains("R66")) return "违反 R66 止损纪律（扛单超 10%）";
        if (t.verdict().contains("R53")) return "违反 R53（该涨不涨未处理）";
        if (t.holdPnlPct() >= 0) return "盈利了结，执行到位";
        return "亏损但按计划执行";
    }
}
