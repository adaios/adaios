package com.adaiadai.core.domain.trading.cases;

import java.util.List;

/**
 * CaseCandidate — 案例候选（2026-10-08，交易插件 UI/UX 重做批 ③「案例候选」）。
 *
 * <p><b>回答什么问题</b>：案例区的上半区——「<b>从你的记录里长出来的</b> —— 我不替你定，
 * 你认了才算」（设计稿 uiux-discovery §十一「案例」；原型 mockups/trading-web-full.html）。
 * 候选收下后成为案例（已收下列表），承接 `R-10`「案例库：成功 / 失败 · 买点 / 卖点 · 也是规则出口」。
 *
 * <p><b>候选只从数据里照（诚实口径，与 R-06 候选规则同一条线）</b>：
 * <ul>
 *   <li><b>卖点类</b>（{@link Kind#SELL}）：清仓笔「卖掉之后到现在」——涨 = {@link Outcome#EARLY 走早了}
 *       / 跌 = {@link Outcome#RIGHT 走对了}（口径复用 {@code SoldAfterCloseService}，本类不重算）。</li>
 *   <li><b>买点类</b>（{@link Kind#BUY}）：轮「买入之后到现在」——涨 = {@link Outcome#SUCCESS 成功}
 *       / 跌 = {@link Outcome#FAILED 失败}。基准 = 买点日收盘价（同「卖掉之后到现在」的收盘对收盘口径，
 *       不用流水成交价——那是 import/flow 双轨数据，收盘是每行都拿得到的统一基准）。</li>
 *   <li><b>显著性</b>：|变化| ≥ 阈值的才是候选（不硬凑）；每类取幅度最大者若干条。</li>
 * </ul>
 *
 * <p><b>规则对照（第二层——能机械对照才贴，贴不上就只摆事实）</b>：
 * <ul>
 *   <li>来源①<b>你写的规则</b>（{@code user-rules.json} 的已认 / 自定义，参数形状可机械对照者）——
 *       止损线（{@code pct}）/ 超期（{@code days}）/ 盈转亏（{@code peakPct}）/ 被套不补仓。</li>
 *   <li>来源②<b>轮自带的引擎命中</b>（R55 / R66 / R69 / R53——{@code TradeRoundService} 已算好的
 *       「没做到」型事实）。</li>
 *   <li>对照不上 → {@code ruleRel=null}：卡片只摆走势事实，<b>不硬编一条规则出来</b>。</li>
 * </ul>
 *
 * <p><b>三态</b>（同 R-06 规则集）：{@link State#CANDIDATE 等你认} → {@link State#ACCEPTED 已收下}
 * （收下时可改标题——「改一改」）/ {@link State#DISMISSED 不要}。<b>弃掉 = 墓碑</b>：留痕不删，
 * 下次生成不复活（P1-交易93 教训：划掉的候选下次刷新又出现 = 用户操作被系统无视）。
 * <b>只有「有状态的」（已收下 / 已弃）才落盘</b>——候选每次现算，文件只记你的决定。
 *
 * @param id         稳定 id：{@code buy|sell-{symbol}-{date}}（数据不变则 id 不变——墓碑才认得住）
 * @param state      三态（见 {@link State}）
 * @param kind       买点 / 卖点
 * @param outcome    成功 / 失败 / 走早了 / 走对了
 * @param symbol     代码 · @param name 名称 · @param date 买点日（买点类 = 轮的首买日）/ 清仓日（卖点类）
 * @param title      标题（人话，收下时可改）：如「洛阳钼业 08-19 卖了之后又涨 18.4%」
 * @param changePct  走势 %（买后 / 卖后到现在；保留 2 位）；null = 没算出来（不该出候选）
 * @param ruleRel    对照方向：{@link RuleRel#SUPPORT 支持} / {@link RuleRel#AGAINST 反对}；null = 无对照
 * @param ruleId     对照的规则 id（usr-xxx / cand-xxx / R66）· @param ruleText 规则主句（截断「（」尾巴）
 * @param notes      卡体文案（1-2 行：第一行 = 对照句（若有），末行 = 走势事实句）
 */
public record CaseCandidate(
        String id,
        State state,
        Kind kind,
        Outcome outcome,
        String symbol,
        String name,
        String date,
        String title,
        Double changePct,
        RuleRel ruleRel,
        String ruleId,
        String ruleText,
        List<String> notes,
        String createdAt,
        String updatedAt) {

    /** 状态：CANDIDATE 等你认 → ACCEPTED 已收下 / DISMISSED 不要（墓碑，生成不复活）。 */
    public enum State { CANDIDATE, ACCEPTED, DISMISSED }

    /** 类别：BUY 买点类（从轮长）· SELL 卖点类（从清仓长）。 */
    public enum Kind { BUY, SELL }

    /** 结果：SUCCESS 买后涨 / FAILED 买后跌（买点类）；EARLY 卖后涨 = 走早了 / RIGHT 卖后跌 = 走对了（卖点类）。 */
    public enum Outcome { SUCCESS, FAILED, EARLY, RIGHT }

    /** 对照方向：SUPPORT = 这笔做到了（在规则内）；AGAINST = 这笔与规则相反（没做到）。 */
    public enum RuleRel { SUPPORT, AGAINST }

    public CaseCandidate {
        if (notes == null) notes = List.of();
    }

    /** 稳定 id：{@code buy|sell-{symbol}-{date}}（#211 风格：日期+标的，天然幂等键）。 */
    public static String idOf(Kind kind, String symbol, String date) {
        return (kind == Kind.BUY ? "buy-" : "sell-") + symbol + "-" + date;
    }

    /** 从数据里照出来的候选（未落盘——只有你认了 / 不要了才落盘）。 */
    public static CaseCandidate candidate(String id, Kind kind, Outcome outcome,
                                          String symbol, String name, String date,
                                          String title, Double changePct,
                                          RuleRel ruleRel, String ruleId, String ruleText,
                                          List<String> notes, String now) {
        return new CaseCandidate(id, State.CANDIDATE, kind, outcome, symbol, name, date, title,
                changePct, ruleRel, ruleId, ruleText, notes, now, now);
    }

    /** 收下（title 传非空 = 收下时改——原型「改一改」按钮；快照式：走势 / 对照都定格在收下那一刻）。 */
    public CaseCandidate accepted(String newTitle, String now) {
        String t = newTitle != null && !newTitle.isBlank() ? newTitle.trim() : title;
        return new CaseCandidate(id, State.ACCEPTED, kind, outcome, symbol, name, date, t,
                changePct, ruleRel, ruleId, ruleText, notes, createdAt, now);
    }

    /** 不要（墓碑——不删条目：不再出现在任何视图，生成候选时也不复活）。 */
    public CaseCandidate dismissed(String now) {
        return new CaseCandidate(id, State.DISMISSED, kind, outcome, symbol, name, date, title,
                changePct, ruleRel, ruleId, ruleText, notes, createdAt, now);
    }
}
