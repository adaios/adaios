package com.adaiadai.core.kernel.context.policy;

/**
 * ContextAssemblyPolicy — 上下文装配策略（RFC 20260929 批 1）。
 * <p>
 * 把「每次对话注入多少上下文」从散落的硬编码常量收敛成一个**可配置、可灰度、可回滚**的策略对象。
 * 本包（{@code kernel/context/policy}）是项目早期就预留的能力位，此前只有 {@code package-info}——
 * 批 1 只落「分档与配额」这一小块，时效与去重（批 2）另议。
 *
 * <h3>为什么要有它</h3>
 * 生产实测（2026-09-29，30 条对话模式样本）：一次对话的 prompt 均长 **10699 字符**，其中
 * <b>L4 知识源 46.0%</b>（P90 87%）、<b>L2 记忆 34.9%</b>、相关历史 12.6%，
 * 而<b>用户当前那句话只占 4.3%</b>——前文与主问句被背景淹没，表现为「像没看前文」。
 *
 * <h3>分档口径</h3>
 * <ul>
 *   <li><b>SHORT</b>（≤ {@code shortMaxTurns} 轮，生产约 45%）：只注入 身份/契约 + 对话前文 + 极少核心记忆</li>
 *   <li><b>MID</b>（≤ {@code midMaxTurns} 轮）：加 L2 完整近期记忆；<b>仍不</b>检索</li>
 *   <li><b>LONG</b>：全层（含 L3 检索）</li>
 * </ul>
 *
 * <h3>口径依据</h3>
 * 「最小的高信噪比 token 集合」（Anthropic context engineering）；上下文越长性能非均匀下降、
 * 且<b>主题相关的干扰项比无关内容伤害更大</b>（Chroma context rot）；对话历史优先且保真、
 * 检索 ≤25~30%、记忆 ≤15% 属需自测的工程惯例。
 *
 * @param tieringEnabled      是否启用分档（false = legacy 现状行为，用于灰度与回滚）
 * @param shortMaxTurns       短档轮数上限
 * @param midMaxTurns         中档轮数上限（之上为长档）
 * @param coreMemoryMax       短档核心记忆条数（0 = 短档不注入记忆）
 * @param coreMemoryMaxTokens 短档核心记忆的 token 上限
 * @param fallbackRecentMax   无标签回退时注入的相关记录条数（legacy=20，v1 收到 2）
 */
public record ContextAssemblyPolicy(
        boolean tieringEnabled,
        int shortMaxTurns,
        int midMaxTurns,
        int coreMemoryMax,
        int coreMemoryMaxTokens,
        int fallbackRecentMax
) {

    /** 上下文分档。 */
    public enum Tier { SHORT, MID, LONG }

    /**
     * 现状口径（legacy）：不分档、回退仍取 20 条、短档不额外注入核心记忆。
     * <p>
     * 与批 1 之前的行为**逐字一致**——灰度期默认值，也是回滚目标。
     */
    public static ContextAssemblyPolicy legacy() {
        return new ContextAssemblyPolicy(false, 3, 9, 0, 0, 20);
    }

    /** v1 默认口径（D1 拍板：短对话保留极少核心）。 */
    public static ContextAssemblyPolicy v1() {
        return new ContextAssemblyPolicy(true, 3, 9, 2, 200, 2);
    }

    /**
     * 生效档位：关闭分档时**恒为 LONG**（= 现状全量注入），这是 legacy 与 v1 的唯一行为分叉点。
     *
     * @param turns 当前卡片的对话轮数（0 = 新卡首问）
     */
    public Tier tierFor(int turns) {
        if (!tieringEnabled) return Tier.LONG;
        if (turns <= shortMaxTurns) return Tier.SHORT;
        if (turns <= midMaxTurns) return Tier.MID;
        return Tier.LONG;
    }
}
