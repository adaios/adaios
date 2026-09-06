package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * TradePsychologyService — 清仓情绪采集（RFC 20260905 P2 主观层，2026-09-05）。
 * <p>
 * 试点记忆卡的提问式采集产品化：每笔清仓按交易结构（盈亏/持仓天数/行为签名）**确定性生成**
 * 3~5 个「当时为什么」提问（不耗 LLM——提问是模板，答案才是语义），用户在清仓卡上回答后
 * 经 {@code PUT /trading/sold/{symbol}/psychology} 回填，并沉淀进 profile.md 主观层。
 * <p>
 * 回答不是事实真相源——行为标注由系统从流水判（红线②），情绪是用户补的语境，AI 交叉验证。
 */
@Service
public class TradePsychologyService {

    private static final Logger log = LoggerFactory.getLogger(TradePsychologyService.class);
    private static final String PROFILE_PATH = "trading/profile.md";

    private final SoldTradeRepository soldTradeRepository;
    private final FileStorage fileStorage;

    public TradePsychologyService(SoldTradeRepository soldTradeRepository, FileStorage fileStorage) {
        this.soldTradeRepository = soldTradeRepository;
        this.fileStorage = fileStorage;
    }

    /** 生成的提问条目。 */
    public record PsychologyQuestion(String key, String question) {}

    /** 按清仓记录生成补情绪提问（确定性模板，按交易结构选择，3~5 问）。 */
    public List<PsychologyQuestion> questionsFor(SoldTrade t) {
        List<PsychologyQuestion> qs = new ArrayList<>();
        String verdict = t.verdict() != null ? t.verdict() : "";
        boolean deepLoss = t.holdPnlPct() <= -10;
        boolean moderateLoss = t.holdPnlPct() < 0;
        boolean profit = t.holdPnlPct() > 0;
        boolean shortHold = t.holdDays() <= 5;
        boolean longHold = t.holdDays() > 90;
        int buyCount = parseBuyCount(t.tradeCount());

        if (moderateLoss && shortHold) {
            qs.add(new PsychologyQuestion("exit_why_short", "这笔拿 " + t.holdDays() + " 天就割了（" + t.holdPnlPct() + "%），当时买入的预期是什么？为什么这么快就放弃？"));
            qs.add(new PsychologyQuestion("panic_or_plan", "卖出是计划内的止损，还是跌到受不了的恐慌离场？"));
        } else if (moderateLoss && longHold) {
            qs.add(new PsychologyQuestion("hold_long_loss", "这笔拿了 " + t.holdDays() + " 天才走（" + t.holdPnlPct() + "%），中途一直亏为什么没早止损？当时在想什么？"));
            qs.add(new PsychologyQuestion("avg_down", "持有期间有想过补仓摊低成本吗？为什么做了/没做？"));
        } else if (deepLoss) {
            qs.add(new PsychologyQuestion("deep_loss_trigger", "亏到 " + t.holdPnlPct() + "% 才走——是什么最终触发了离场？（认输/要用钱/彻底失望/其他）"));
            qs.add(new PsychologyQuestion("stop_missing", "如果入场时设了 -5% 止损，这笔本可少亏多少？为什么当时没设？"));
        }
        if (profit && longHold) {
            qs.add(new PsychologyQuestion("profit_hold", "这笔赚了 " + t.holdPnlPct() + "%、拿了 " + t.holdDays() + " 天——中间回撤时靠什么拿住的？"));
            qs.add(new PsychologyQuestion("profit_exit", "为什么选在最后那天卖？是计划好的目标位，还是感觉到了？"));
        }
        if (profit && shortHold) {
            qs.add(new PsychologyQuestion("quick_profit", "这笔 " + t.holdDays() + " 天赚 " + t.holdPnlPct() + "% 就走——卖飞过吗？事后看会后悔吗？"));
        }
        if (buyCount >= 5) {
            qs.add(new PsychologyQuestion("multi_buy", "你在这笔上买了 " + buyCount + " 次——是计划分批，还是越跌越买停不下来？"));
        }
        if (verdict.contains("扛单")) {
            qs.add(new PsychologyQuestion("carry_psych", "扛单时每天看着浮亏扩大是什么感觉？有没有想过「再等等就回来了」？"));
        }
        if (verdict.contains("短持仓亏损")) {
            qs.add(new PsychologyQuestion("short_life", "这笔买完没涨就割——是本来就打算快进快出，还是买完发现不对赶紧跑？"));
        }
        if (qs.isEmpty()) {
            qs.add(new PsychologyQuestion("general", "这笔 " + t.name() + " 买卖的整个过程，现在回头看，最想对自己说的一句是什么？"));
        }
        // 稳定 3~5 问
        if (qs.size() > 5) qs = qs.subList(0, 5);
        return qs;
    }

    /** 用户回答（自由文本）回填 sold.psychology + 追加到 profile.md 主观层。 */
    public boolean submitAnswer(String userId, String symbol, String psychology) {
        if (psychology == null || psychology.isBlank()) return false;
        List<SoldTrade> sold = soldTradeRepository.findAll(userId);
        // 💥6（2026-09-05 对抗审 + backend P2-5）：同 symbol 多次清仓——按 sellDate 定位**最近一笔**
        // （用户在当前清仓卡看到的即最近），只改那一笔。原实现把情绪写进该 symbol 所有行（张冠李戴）。
        SoldTrade target = sold.stream()
                .filter(t -> symbol.equals(t.symbol()) && t.sellDate() != null)
                .max(java.util.Comparator.comparing(SoldTrade::sellDate))
                .orElse(null);
        if (target == null) return false;
        // 1. 回填 sold.psychology（追加式，保留已有）——只改目标一笔，其它同 symbol 行不动。
        //    P2（2026-09-05 三官审）：重复 POST 去重——已含相同内容则不重复追加（与 profile 层 appendSubjective 同逻辑）
        String existing = target.psychology() == null ? "" : target.psychology();
        String merged;
        if (existing.isBlank()) {
            merged = psychology;
        } else if (existing.contains(psychology)) {
            return true; // 幂等：内容已存在，不重复追加（sold 层去重）
        } else {
            merged = existing + "\n" + psychology;
        }
        String finalMerged = merged;
        List<SoldTrade> updated = sold.stream()
                .map(t -> t == target
                        ? new SoldTrade(t.symbol(), t.name(), t.buyDate(), t.sellDate(),
                        t.holdDays(), t.tradeCount(), t.holdPnlPct(), t.verdict(), finalMerged)
                        : t)
                .toList();
        soldTradeRepository.saveAll(userId, updated);
        // 2. 沉淀主观层到 profile.md（若存在；不存在则不建——画像由试点/确认流程建）
        appendSubjective(userId, symbol, target.name(), psychology);
        return true;
    }

    private void appendSubjective(String userId, String symbol, String name, String psychology) {
        try {
            String existing = fileStorage.read(userId, PROFILE_PATH);
            if (existing == null) return; // 无画像文件 → 不自动建（尊重用户是否启用画像）
            String line = "- " + name + "（" + symbol + "）补情绪：" + psychology.replace("\n", " ");
            if (!existing.contains(line)) {
                fileStorage.write(userId, PROFILE_PATH, existing + "\n" + line + "\n");
            }
        } catch (Exception e) {
            log.warn("情绪沉淀到画像失败（不影响回填）| userId={} | {}", userId, e.getMessage());
        }
    }

    /** 从 "9+1" 解析买入次数；非法 → 0。 */
    private int parseBuyCount(String tradeCount) {
        if (tradeCount == null || !tradeCount.contains("+")) return 0;
        try {
            return Integer.parseInt(tradeCount.split("\\+")[0].strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
