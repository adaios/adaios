package com.adaiadai.core.domain.trading;

import com.adaiadai.core.kernel.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TradingProfileService — 个人交易画像（RFC 20260905 A 层，2026-09-05）。
 * <p>
 * 双层画像：
 * <ul>
 *   <li><b>客观层</b>：全部数字由系统从清仓史（sold）实时推导——纪律遵守率、胜率、持仓节奏、
 *       行为签名频次。不落盘（实时算最真），红线①：数字只能系统给。</li>
 *   <li><b>主观层</b>：用户补全的情绪/签名确认，存 {@code data/{userId}/trading/profile.md}
 *       （试点记忆卡提问式采集后由 AI 回填），本服务只读并随客观层一起注入。</li>
 * </ul>
 * 用途：注入建议/复盘/问答 prompt，让阿呆说「你上次…」而不是背规则书（主语是你，合规）。
 */
@Service
public class TradingProfileService {

    private static final Logger log = LoggerFactory.getLogger(TradingProfileService.class);
    private static final String PROFILE_PATH = "trading/profile.md";

    private final SoldTradeRepository soldTradeRepository;
    private final FileStorage fileStorage;
    /** RFC 20260905 P2：建议遵守率（B 反哺 A）——查建议留痕对照实际清仓。 */
    private final com.adaiadai.core.domain.trading.AdviceHistoryRepository adviceHistoryRepository;

    public TradingProfileService(SoldTradeRepository soldTradeRepository,
                                 FileStorage fileStorage,
                                 com.adaiadai.core.domain.trading.AdviceHistoryRepository adviceHistoryRepository) {
        this.soldTradeRepository = soldTradeRepository;
        this.fileStorage = fileStorage;
        this.adviceHistoryRepository = adviceHistoryRepository;
    }

    /** 结构化画像统计（客观层；供端点返回 / 测试断言）。 */
    public record TradingProfileStats(
            int soldCount,
            double winRatePct,
            double medianPnlPct,
            int disciplineViolationCount,
            double disciplineViolationRatePct,
            int avgHoldDays,
            Map<String, Integer> verdictBreakdown
    ) {}

    /** 计算客观画像统计（空/损坏清仓史 → 全零，不抛错）。 */
    public TradingProfileStats computeStats(String userId) {
        List<SoldTrade> sold = safeSold(userId);
        if (sold.isEmpty()) {
            return new TradingProfileStats(0, 0, 0, 0, 0, 0, Map.of());
        }
        int wins = 0;
        double sum = 0;
        List<Double> pnls = new ArrayList<>();
        int violations = 0;
        int holdSum = 0;
        Map<String, Integer> breakdown = new java.util.LinkedHashMap<>();
        for (SoldTrade t : sold) {
            sum += t.holdPnlPct();
            pnls.add(t.holdPnlPct());
            holdSum += t.holdDays();
            if (t.holdPnlPct() > 0) wins++;
            String key = t.verdict() != null && !t.verdict().isBlank()
                    ? t.verdict().split("——")[0].strip() : "未知";
            breakdown.merge(key, 1, Integer::sum);
            // 纪律违反（P2-认知1，2026-09-05 用户拍板 A：只算真破纪律两类）：
            //   扛单超5%（R66 不止损）+ 短打亏损（R53 没涨不拍）——「亏损持仓」类（小亏拿很久）
            //   属普通亏损非破纪律，从违纪剔除（原实现三分类全算 → 违纪率≈亏损率，标签误导）
            if (t.verdict() != null && (t.verdict().contains("扛单")
                    || t.verdict().contains("短持仓亏损"))) {
                violations++;
            }
        }
        pnls.sort(Double::compareTo);
        double median = pnls.get(pnls.size() / 2);
        double avgHold = (double) holdSum / sold.size();
        return new TradingProfileStats(
                sold.size(),
                round1(wins * 100.0 / sold.size()),
                round1(median),
                violations,
                round1(violations * 100.0 / sold.size()),
                (int) Math.round(avgHold),
                breakdown);
    }

    /** 客观画像注入文本（AI 可读，主语是你，全数字系统算）。
     *  🤔18（2026-09-05 对抗审）：样本 <10 笔不注入胜率/纪律率/平均持仓（小样本统计易误读成画像事实），
     *  只给笔数与提示积累中——1 笔就下「纪律违反率 0%/100%」判断会误导 AI 与用户。 */
    public String objectiveProfileText(String userId) {
        TradingProfileStats s = computeStats(userId);
        if (s.soldCount() == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## 你的交易画像（客观统计，系统从你的清仓史推导）\n\n");
        if (s.soldCount() < 10) {
            sb.append("- 已清仓 ").append(s.soldCount()).append(" 笔（样本不足，暂不统计胜率/纪律率——"
                    + "积累到 10 笔以上再看规律）\n");
            sb.append("> 参考：清仓史会随导入逐步积累，画像随之变准。\n");
            return sb.toString();
        }
        sb.append("- 已清仓 ").append(s.soldCount()).append(" 笔，胜率 ").append(s.winRatePct()).append("%");
        if (s.soldCount() >= 5) {
            sb.append("，盈亏中位数 ").append(s.medianPnlPct()).append("%");
        }
        sb.append("\n");
        sb.append("- 纪律违反率 ").append(s.disciplineViolationRatePct())
                .append("%（").append(s.disciplineViolationCount()).append(" 笔违反止损/短持纪律）\n");
        sb.append("- 平均持仓 ").append(s.avgHoldDays()).append(" 天\n");
        if (!s.verdictBreakdown().isEmpty()) {
            sb.append("- 清仓结果分布：");
            List<String> parts = new ArrayList<>();
            s.verdictBreakdown().forEach((k, v) -> parts.add(k + " " + v + " 笔"));
            sb.append(String.join("，", parts)).append("\n");
        }
        // P2-认知2（2026-09-05 用户拍板 B）：标注口径——清仓表是「首买→末卖」纸上区间收益，
        // 非实际回合收益（如航天发展 +224% 实为两轮 +24%）；画像 v2 应切逐笔流水回合。
        sb.append("> 参考：这是你的历史统计（**清仓表口径：首买→末卖纸上区间收益**，非实际逐笔回合），"
                + "帮你对照「这次操作像不像过去的你」。\n");
        return sb.toString();
    }

    /** 主观画像层（用户补全，存 profile.md）；无文件返回空（不影响注入）。
     *  ⚠️10（2026-09-05 对抗审）：整文件注入——profile.md 只应存主观内容（写入端约束：
     *  PUT /trading/profile 与情绪回填都写用户/AI 确认的主观文本，不写系统统计；
     *  客观统计实时推导不落盘，故无「跳过客观节」可做。若未来文件混入统计，由写入端负责隔离）。 */
    public String subjectiveProfileText(String userId) {
        try {
            String content = fileStorage.read(userId, PROFILE_PATH);
            if (content == null || content.isBlank()) return "";
            return "## 你的画像（主观层，你确认过的行为签名）\n\n" + content;
        } catch (Exception e) {
            log.warn("读取个人画像失败（不影响注入）| userId={} | {}", userId, e.getMessage());
            return "";
        }
    }

    /** 建议遵守率统计（RFC 20260905 P2，B 反哺 A）。 */
    public record AdviceAdherence(
            int withAdviceCount,   // 清仓前 30 天内有建议留痕的笔数
            int followedCount,     // 遵守（建议 clear/reduce 且实际清仓在建议后 10 天内）笔数
            double followRatePct   // 遵守率
    ) {}

    /**
     * 计算建议遵守率：对每笔已清仓，回查卖前是否有「clear/reduce」建议——
     * 有则判「建议后是否尽快（10 天内）清仓」，是 → 遵守。数字系统算，红线①。
     * <p>
     * P1-1（2026-09-05 三官深审）：回查窗口以该笔 <b>sellDate</b> 为锚（不用 now）——
     * 扫 sellDate 所在月 + 前 2 月（建议留痕 3 个月窗口），过滤 date ≤ sellDate 且 ≥ sellDate-30 天，
     * 旧清仓的卖前建议不再被「近 30 天 now 窗口」漏掉（原实现只统计近期清仓 → 遵守率虚高）。
     */
    public AdviceAdherence computeAdviceAdherence(String userId) {
        List<SoldTrade> sold = safeSold(userId);
        if (sold.isEmpty()) return new AdviceAdherence(0, 0, 0);
        int withAdvice = 0, followed = 0;
        for (SoldTrade t : sold) {
            if (t.sellDate() == null) continue;
            // 卖前 30 天窗口：date ∈ [sellDate-30, sellDate]，只统计真正的卖前建议
            java.time.LocalDate windowStart = t.sellDate().minusDays(30);
            com.adaiadai.core.domain.trading.AdviceEntry preSell = null;
            java.time.YearMonth m = java.time.YearMonth.from(t.sellDate());
            java.time.YearMonth m0 = java.time.YearMonth.from(windowStart);
            try {
                // 扫 sellDate 所在月 → windowStart 所在月（跨月最多 2 个月文件）
                for (java.time.YearMonth ym = m; !ym.isBefore(m0); ym = ym.minusMonths(1)) {
                    for (com.adaiadai.core.domain.trading.AdviceEntry h : adviceHistoryRepository.findByMonth(userId, ym.atDay(1))) {
                        if (!t.symbol().equals(h.symbol())) continue;
                        if (h.date() == null || h.date().isAfter(t.sellDate()) || h.date().isBefore(windowStart)) continue;
                        if (h.suggestion() != null
                                && (h.suggestion().equals("clear") || h.suggestion().equals("reduce"))) {
                            // 取卖前最近一条 clear/reduce
                            if (preSell == null || h.date().isAfter(preSell.date())) preSell = h;
                        }
                    }
                }
            } catch (Exception e) {
                continue;
            }
            if (preSell == null) continue;
            withAdvice++;
            // 遵守 = 建议后 10 天内清仓（sellDate 距建议日 ≤ 10 天且不早于建议日）
            long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(preSell.date(), t.sellDate());
            if (daysBetween >= 0 && daysBetween <= 10) {
                followed++;
            }
        }
        double rate = withAdvice > 0 ? round1(followed * 100.0 / withAdvice) : 0;
        return new AdviceAdherence(withAdvice, followed, rate);
    }

    /** 建议遵守率注入文本（画像 A 层引用；无建议对照史 → 空）。 */
    public String adviceAdherenceText(String userId) {
        AdviceAdherence a = computeAdviceAdherence(userId);
        if (a.withAdviceCount() == 0) return "";
        return "- 建议遵守率 " + a.followRatePct() + "%（阿呆给过 clear/reduce 建议的 "
                + a.withAdviceCount() + " 笔里，" + a.followedCount() + " 笔在建议后 10 天内执行）\n";
    }

    /**
     * RFC 20260905 远期：主动拦截预留——当前关注的 symbol 是否命中用户历史「老毛病」。
     * <p>
     * 查该 symbol 的清仓史：深亏过（≤-10%）→ 提示「上次在这只票上亏过」；曾有 clear 建议但
     * 拖很久才走 → 提示「上次不听 clear 建议」。建议引擎逐票注入（拦截入口预留，供后续升级）。
     */
    public String symbolHistoryNote(String userId, String symbol) {
        List<SoldTrade> sold = safeSold(userId);
        if (sold.isEmpty() || symbol == null) return "";
        SoldTrade past = null;
        for (SoldTrade t : sold) {
            if (symbol.equals(t.symbol())) { past = t; break; }
        }
        if (past == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("- 你上次做这只票：").append(past.sellDate() != null ? past.sellDate() : "？")
                .append(" 清仓，持仓期 ").append(past.holdPnlPct()).append("%");
        if (past.verdict() != null && !past.verdict().isBlank()) {
            sb.append("，判定：").append(past.verdict().split("——")[0].strip());
        }
        sb.append("。若本次操作与你上次的模式相似，请在心里先过一遍：上次哪里做得不对？");
        return sb.toString();
    }

    /** 组合注入文本：客观 + 建议遵守 + 主观（无任何画像数据 → 空字符串）。 */
    public String profileText(String userId) {
        String objective = objectiveProfileText(userId);
        String adherence = adviceAdherenceText(userId);
        String subjective = subjectiveProfileText(userId);
        if (objective.isBlank() && adherence.isBlank() && subjective.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        if (!objective.isBlank()) sb.append(objective).append("\n\n");
        if (!adherence.isBlank()) sb.append("## 建议遵守（B 反哺 A）\n\n").append(adherence).append("\n");
        if (!subjective.isBlank()) sb.append(subjective);
        return sb.toString().strip();
    }

    /** 读取原始 profile.md（端点透出，供前端展示/编辑）。 */
    public String rawProfile(String userId) {
        try {
            return fileStorage.read(userId, PROFILE_PATH);
        } catch (Exception e) {
            return null;
        }
    }

    /** 保存 profile.md 主观层（用户/AI 回填）。 */
    public void saveProfile(String userId, String content) {
        try {
            fileStorage.write(userId, PROFILE_PATH, content != null ? content : "");
        } catch (Exception e) {
            throw new com.adaiadai.core.infrastructure.storage.StorageException(
                    "保存个人画像失败 | userId=" + userId + " | " + e.getMessage(), e);
        }
    }

    private List<SoldTrade> safeSold(String userId) {
        try {
            List<SoldTrade> list = soldTradeRepository.findAll(userId);
            return list != null ? list : List.of();
        } catch (Exception e) {
            log.warn("读取清仓史失败（画像降级为空）| userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    private double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
