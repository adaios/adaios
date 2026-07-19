package com.adaiadai.core.kernel.record;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CardRecord — 会话卡片。
 * <p>
 * 一个会话一张卡，包含完整对话轮次。
 * 文件位置：data/records/YYYY/MM/DD/card_{id}.md
 *
 * @param id        卡片 ID
 * @param type      log | conversation
 * @param status    idle | active | ended
 * @param tags      标签
 * @param turns     对话轮次（user / ai 交替）
 * @param summary   摘要（ended 后生成）
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record CardRecord(
        String id,
        String type,
        String status,
        List<String> tags,
        List<Turn> turns,
        String summary,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public CardRecord withTurn(boolean isUser, String text, String time) {
        List<Turn> newTurns = new java.util.ArrayList<>(turns);
        newTurns.add(new Turn(isUser, text, time));
        return new CardRecord(id, type, status, tags, newTurns, summary, createdAt, LocalDateTime.now());
    }

    public CardRecord withStatus(String newStatus) {
        return new CardRecord(id, type, newStatus, tags, turns, summary, createdAt, LocalDateTime.now());
    }

    public CardRecord withSummary(String newSummary) {
        return new CardRecord(id, type, status, tags, turns, newSummary, createdAt, LocalDateTime.now());
    }

    public record Turn(boolean isUser, String text, String time) {}
}
