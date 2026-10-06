package com.adaiadai.core.domain.trading;

import java.util.List;
import java.util.Map;

/**
 * UserRule — 用户规则集条目（三态：候选 / 已认 / 自定义；R-06 · blueprint §三，rules 批 2026-10-06）。
 *
 * <p><b>三个来源</b>（R-06）：从数据长出来（{@link RuleSource#DATA}——描述性统计是候选的原料）·
 * 自己写（{@link RuleSource#USER}）· 导入（未实现——无规格，不预置空壳）。<b>只存文本 + 参数</b>
 * （设计 §2；规则不是可执行代码——判定仍由引擎 / 分析层完成）。
 *
 * <p><b>三态流转</b>（blueprint §三）：{@link UserRuleState#CANDIDATE 候选}（系统提、每条带据）→
 * {@link UserRuleState#ACCEPTED 已认}（你勾选 / 改）→ {@link UserRuleState#CUSTOM 自定义}（你写）。
 * <b>永不覆盖</b>：已认 / 自定义的文本是用户的——系统刷新只动「仍是候选」的条目。
 *
 * <p><b>弃掉 = 墓碑</b>（P1-交易93，2026-10-06）：{@link UserRuleState#DISMISSED 弃掉}的条目
 * <b>留痕不删</b>——不再出现在任何视图分组，重新生成候选时也<b>不复活</b>
 * （否则「用户划掉的候选下次刷新又出现」= 用户操作被系统无视）。
 *
 * <p><b>红线</b>（blueprint §二/§三）：候选是<b>描述</b>（你实际在做什么）不是<b>建议</b>（你该怎么做）
 * ——把「你实际怎么做的」照成规则，由你认；认不认、改不改，你定。没有规则时不套默认值：候选
 * <b>只从你自己的数据照出来</b>，样本不足宁可不出（不硬凑）。
 */
public record UserRule(
        String id,
        UserRuleState state,
        String text,
        RuleSource source,
        Map<String, Object> params,
        Evidence evidence,
        String createdAt,
        String updatedAt) {

    /** 状态：候选（系统提）→ 已认（你勾选 / 改）→ 自定义（你写）；DISMISSED = 弃掉（墓碑，不再展示、生成不复活）。 */
    public enum UserRuleState { CANDIDATE, ACCEPTED, CUSTOM, DISMISSED }

    /** 来源：DATA = 从数据里长出来；USER = 自己写（导入未实现）。 */
    public enum RuleSource { DATA, USER }

    public UserRule {
        if (params == null) params = Map.of();
    }

    public boolean isCandidate() {
        return state == UserRuleState.CANDIDATE;
    }

    /** 认下（text 传非空 = 认下时改——blueprint「你勾选 / 改」都算认下）；evidence 保留（这条当时为什么长出来）。 */
    public UserRule accepted(String newText, String now) {
        String t = newText != null && !newText.isBlank() ? newText : text;
        return new UserRule(id, UserRuleState.ACCEPTED, t, source, params, evidence, createdAt, now);
    }

    /** 改文本（仅对已认 / 自定义；候选要先认下）。 */
    public UserRule edited(String newText, String now) {
        return new UserRule(id, state, newText, source, params, evidence, createdAt, now);
    }

    /** 弃掉（墓碑——不删条目：不再出现在任何分组，生成候选时也不复活）。
     *  <p>P1-交易93（2026-10-06）：原实现是直接删条目，而候选按固定 id（cand-*）全量重生成
     *  ⇒ 用户划掉的候选下次刷新又出现。改为留痕状态：条目保留、永不再回到用户面前。 */
    public UserRule dismissed(String now) {
        return new UserRule(id, UserRuleState.DISMISSED, text, source, params, evidence, createdAt, now);
    }

    /** 候选刷新（数据变了，候选跟着变；createdAt 保留=首次照出来的时刻；已认 / 自定义永远不经过这里）。 */
    public UserRule refreshed(UserRule fresh, String now) {
        return new UserRule(id, UserRuleState.CANDIDATE, fresh.text(), fresh.source(),
                fresh.params(), fresh.evidence(), createdAt, now);
    }

    /** 从数据里长出来的候选（必带据）。 */
    public static UserRule candidate(String id, String text, Map<String, Object> params,
                                     Evidence evidence, String now) {
        return new UserRule(id, UserRuleState.CANDIDATE, text, RuleSource.DATA, params, evidence, now, now);
    }

    /** 自己写（三态之自定义）。 */
    public static UserRule custom(String id, String text, Map<String, Object> params, String now) {
        return new UserRule(id, UserRuleState.CUSTOM, text, RuleSource.USER, params, null, now, now);
    }

    /**
     * 依据（验收 2「每条带据」）：怎么算的（人话）+ 关键数字 + 哪几笔 / 哪几天
     * （可回溯——验收 4「每个数字点得进去」的精神）。
     */
    public record Evidence(String how, List<String> facts, List<String> roundIds, List<String> dates) {
        public Evidence {
            if (facts == null) facts = List.of();
            if (roundIds == null) roundIds = List.of();
            if (dates == null) dates = List.of();
        }
    }
}
