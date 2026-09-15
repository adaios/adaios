package com.adaiadai.core.kernel.record;

import java.util.List;

/**
 * ConversationText — 对话原文的规范化拼接（E-A 写侧保真的数据契约）。
 * <p>
 * 记录层 {@code type=conversation} 记录的正文 = <b>对话原文</b>（而非 AI 转述），
 * 格式为 {@code 我：…} / {@code 你：…} 交替——遵循第一原则「我和阿呆的自然对话」。
 * AI 转述降入记录的 {@code summary} 字段（见 {@code docs/architecture/memory-fidelity.md} E-A）。
 * <p>
 * 收口理由：{@code ConversationController} 与 {@code RecordRetryService} 原各有一份
 * 等价的 {@code buildTurnText}（两份实现易漂移，而正文格式是数据契约）——本类是唯一实现。
 */
public final class ConversationText {

    /** 第一人称（用户）。 */
    private static final String USER_PREFIX = "我：";
    /** 第二人称（阿呆）。 */
    private static final String AI_PREFIX = "你：";

    private ConversationText() {
    }

    /**
     * 由「我 / 你」交替的轮次文本拼接（偶数索引 = 我，奇数 = 你）。
     * <p>
     * 用于 {@code POST /api/v1/conversations/end} 的请求轮次（该端点只收交替纯文本，
     * 无角色字段可依）。空轮次 → 空串（不产出 {@code "\n"}）。
     */
    public static String fromAlternating(List<String> turns) {
        if (turns == null || turns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            sb.append(i % 2 == 0 ? USER_PREFIX : AI_PREFIX).append(turns.get(i)).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * 由卡片轮次拼接——以 {@link CardRecord.Turn#isUser()} 判定角色。
     * <p>
     * 比索引奇偶可靠：卡片轮次自带角色标记（{@code CardFileRepository.parseTurns} 按
     * {@code 用户：/AI：} 行前缀解析），轮次缺失/错位时不会把阿呆的话当我说的。
     */
    public static String fromTurns(List<CardRecord.Turn> turns) {
        if (turns == null || turns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CardRecord.Turn turn : turns) {
            if (turn == null) continue;
            sb.append(turn.isUser() ? USER_PREFIX : AI_PREFIX).append(turn.text()).append("\n");
        }
        return sb.toString().strip();
    }
}
