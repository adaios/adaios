package com.adaiadai.core.kernel.record;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CardRecord — 会话卡片。
 * <p>
 * 一个会话一张卡，包含完整对话轮次。
 * 文件位置：data/records/YYYY/MM/DD/card_{id}.md
 *
 * @param id                   卡片 ID
 * @param type                 log | conversation
 * @param status               idle | active | ended
 * @param tags                 标签
 * @param turns                对话轮次（user / ai 交替）
 * @param summary              摘要（ended 后生成）
 * @param createdAt            创建时间
 * @param updatedAt            最后更新时间
 * @param conversationRecordId 该卡结束（end）时落盘的那条 conversation 记录 id；
 *                             null = 尚未结束过。REVIEW P1-对话1：它是「同卡只允许一条
 *                             conversation」的幂等键——超时重发 / 双端并发再次 end 时
 *                             据此直接返回既有结果，不再重复落盘、不再重复调用 AI。
 */
public record CardRecord(
        String id,
        String type,
        String status,
        List<String> tags,
        List<Turn> turns,
        String summary,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String conversationRecordId
) {

    /** 兼容旧签名（无 conversationRecordId）——P1-对话1 之前的所有调用点无需改动。 */
    public CardRecord(String id, String type, String status, List<String> tags, List<Turn> turns,
                      String summary, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, type, status, tags, turns, summary, createdAt, updatedAt, null);
    }

    public CardRecord withTurn(boolean isUser, String text, String time) {
        List<Turn> newTurns = new java.util.ArrayList<>(turns);
        newTurns.add(new Turn(isUser, text, time));
        return new CardRecord(id, type, status, tags, newTurns, summary, createdAt, LocalDateTime.now(),
                conversationRecordId);
    }

    public CardRecord withStatus(String newStatus) {
        return new CardRecord(id, type, newStatus, tags, turns, summary, createdAt, LocalDateTime.now(),
                conversationRecordId);
    }

    public CardRecord withSummary(String newSummary) {
        return new CardRecord(id, type, status, tags, turns, newSummary, createdAt, LocalDateTime.now(),
                conversationRecordId);
    }

    /** 记录该卡落盘的 conversation 记录 id（幂等键；写回原卡文件，不新建副本）。 */
    public CardRecord withConversationRecordId(String recordId) {
        return new CardRecord(id, type, status, tags, turns, summary, createdAt, LocalDateTime.now(), recordId);
    }

    public record Turn(boolean isUser, String text, String time) {}
}
