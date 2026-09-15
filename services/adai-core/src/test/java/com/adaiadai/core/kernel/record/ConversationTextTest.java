package com.adaiadai.core.kernel.record;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ConversationText — E-A 写侧保真的正文格式契约（memory-fidelity.md）。
 * <p>
 * 正文 = 「我：/你：」交替的对话原文，是本批的数据契约本身，故单独立测。
 */
class ConversationTextTest {

    @Test
    void fromAlternating_prefixesAlternately() {
        assertEquals("我：你好\n你：在的",
                ConversationText.fromAlternating(List.of("你好", "在的")));
    }

    @Test
    void fromAlternating_emptyOrNull_returnsEmptyWithoutTrailingNewline() {
        assertEquals("", ConversationText.fromAlternating(List.of()));
        assertEquals("", ConversationText.fromAlternating(null));
    }

    @Test
    void fromTurns_rolesComeFromIsUserNotIndexParity() {
        // 反向锁：角色必须读 isUser 字段。两条都是用户轮时，不得把第二条当成阿呆的
        // （若按索引奇偶实现，此处会得到「你：第二句」——真实对话中轮次缺失/错位即会踩到）。
        String text = ConversationText.fromTurns(List.of(
                new CardRecord.Turn(true, "第一句", "10:00"),
                new CardRecord.Turn(true, "第二句", "10:01")));
        assertEquals("我：第一句\n我：第二句", text);
    }

    @Test
    void fromTurns_skipsNullTurnAndStrips() {
        String text = ConversationText.fromTurns(Arrays.asList(
                new CardRecord.Turn(true, "只有我", "10:00"), null));
        assertEquals("我：只有我", text);
    }

    @Test
    void fromTurns_emptyOrNull_returnsEmpty() {
        assertEquals("", ConversationText.fromTurns(List.of()));
        assertEquals("", ConversationText.fromTurns(null));
    }
}
