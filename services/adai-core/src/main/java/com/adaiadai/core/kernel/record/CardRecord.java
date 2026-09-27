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
 *                             conversation」的幂等键——重试同一段对话时据此直接返回既有结果。
 * @param conversationTurnsHash 落该 conversation 时**对话内容的指纹**（`List.hashCode`）。
 *                             **对抗审查 P1-A（2026-09-26）**：只比 `conversationRecordId`
 *                             会把「用户在已结束的卡上继续聊几轮再点结束」也当成重试——于是
 *                             拿回上一次的总结、且新轮次既不落 record 也不进记忆。指纹不一致
 *                             = 内容变了 = 应当**新落一条**（并回写新键与新指纹）。
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
        String conversationRecordId,
        Integer conversationTurnsHash
) {

    /** 兼容旧签名（无 conversationRecordId / 无指纹）——历史调用点零改动。 */
    public CardRecord(String id, String type, String status, List<String> tags, List<Turn> turns,
                      String summary, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, type, status, tags, turns, summary, createdAt, updatedAt, null, null);
    }

    /** 兼容「只有幂等键、没有指纹」的签名（2026-09-26 批一 → 批九 之间的调用点）。 */
    public CardRecord(String id, String type, String status, List<String> tags, List<Turn> turns,
                      String summary, LocalDateTime createdAt, LocalDateTime updatedAt,
                      String conversationRecordId) {
        this(id, type, status, tags, turns, summary, createdAt, updatedAt, conversationRecordId, null);
    }

    public CardRecord withTurn(boolean isUser, String text, String time) {
        List<Turn> newTurns = new java.util.ArrayList<>(turns);
        newTurns.add(new Turn(isUser, text, time));
        // 注意：新 turn 会让「已有指纹」与当前 turns 不一致 → 再次 end 时正确地**新落一条**
        // （这正是 P1-A 要的行为，不是 bug）
        return new CardRecord(id, type, status, tags, newTurns, summary, createdAt, LocalDateTime.now(),
                conversationRecordId, conversationTurnsHash);
    }

    public CardRecord withStatus(String newStatus) {
        return new CardRecord(id, type, newStatus, tags, turns, summary, createdAt, LocalDateTime.now(),
                conversationRecordId, conversationTurnsHash);
    }

    public CardRecord withSummary(String newSummary) {
        return new CardRecord(id, type, status, tags, turns, newSummary, createdAt, LocalDateTime.now(),
                conversationRecordId, conversationTurnsHash);
    }

    /**
     * 记录该卡落盘的 conversation 记录 id **与其内容指纹**（幂等键；写回原卡文件，不新建副本）。
     * 指纹与记录 id 必须**同时**写入——只写 id 会让「继续聊再结束」被误判为重试（P1-A）。
     */
    public CardRecord withConversation(String recordId, Integer turnsHash) {
        return new CardRecord(id, type, status, tags, turns, summary, createdAt, LocalDateTime.now(),
                recordId, turnsHash);
    }

    public record Turn(boolean isUser, String text, String time) {}
}
