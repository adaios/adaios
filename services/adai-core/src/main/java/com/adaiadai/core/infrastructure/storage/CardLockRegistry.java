package com.adaiadai.core.infrastructure.storage;

import org.springframework.stereotype.Component;

/**
 * CardLockRegistry — 会话卡片文件「读-改-写」（RMW）的**共享**条带锁池。
 *
 * <p><b>为什么需要（REVIEW P2-工程13）</b>：卡片文件是整文件重写语义，任何
 * {@code findById → withXxx → save} 都是 RMW，并发交错会静默丢更新（pitfalls「整文件重写并发」）。
 * 此前三条 append 路径（{@code QuestionAppService.ensureCardWithUserTurn}、
 * {@code QuestionAppService.finishAnswer} 的 AI turn、{@code MediaRecordAppService.appendQaToImageCard}）
 * 各自裸跑，而结束对话的 {@code ConversationController.endConversation} 用的是**只有它自己**拿得到的
 * 条带锁：append 一旦落在 end「写回前 re-read → save」之间，就被旧快照覆盖——丢轮次 → 幂等键
 * 对不上（turnsAligned=false）→ 下次 end 重落一条 + 重复 AI 调用（花钱）。
 * 治法即本类：**同一把锁**（同一 Spring 单例）被 append 侧与 end 侧共用。
 *
 * <p><b>锁顺序（全仓库唯一顺序，违反即可能死锁）</b>：
 * <pre>
 *   endLock(userId, cardId)  →  cardLock(userId, cardId)
 * </pre>
 * <ul>
 *   <li>{@link #cardLock}：卡片文件 RMW 互斥——三处 append、end 的卡片写回、
 *       {@code RecordRetryService.processCard} 的重补写回、{@code CardMigrationService} 的
 *       迁移写盘共用。锁内**只允许一次**卡片文件的读-改-写；**禁止**在持锁期间做 AI 调用 /
 *       HTTP / 大文件 IO / 记忆落盘。
 *       <p><b>注意「一次 RMW」不等于「毫秒以下」</b>（REVIEW P2-工程13 发现2，2026-10-05 实测）：
 *       当前 {@link CardFileRepository#findById} 是**全目录扫描 + 逐文件解析**，耗时随卡量线性增长
 *       （9 张≈亚毫秒 · 300 张中位 9.8ms · 1000 张 25.8ms；未命中要扫两遍 ⇒ ≈51ms）。
 *       全量遍历与建索引是已知待办 REVIEW #19（独立一批），本注释只如实记录量级，不改变实现。
 *       因此 append 侧等待 end 写回的最坏延迟是「一次扫描 + 一次整文件写」，不是恒定微秒。</p>
 *       持 cardLock 时**不得**再取第二把 cardLock（跨卡）也**不得**取 endLock（反序 = 死锁环）。</li>
 *   <li>{@link #endLock}：同卡「结束闸」——end 的「幂等判定 → AI 总结 → 落记录」整段持有，
 *       保证并发 end / 超时重发只有第一个真的调 AI（REVIEW P1-对话1 检查-再动作竞态，防重复烧钱）。
 *       它**跨 AI 调用**持有，因此不能与 cardLock 合并成一把——否则每次 append 都要等 AI 跑完。</li>
 *   <li>append 侧只取 cardLock，**绝不**取 endLock → 不存在反向获取 → 无死锁环。</li>
 * </ul>
 *
 * <p><b>上限</b>：固定 {@value #STRIPES} 条 × 2 个命名空间 = 常量个 monitor（{@link #lockCount()}），
 * **不按** (userId, cardId) 建 map → 无界增长风险为零（同 {@code TradingAppService.tradeLock} /
 * P2-交易28 锁池模式；个人系统并发度低，不同卡共条带互相排队可接受）。
 */
@Component
public class CardLockRegistry {

    /** 条带数（2 的幂：用位掩码取条带，无取模开销）。 */
    static final int STRIPES = 32;

    /** 卡片文件 RMW 锁池（append ×3 + end 写回共用）。 */
    private final Object[] cardLocks = new Object[STRIPES];

    /** end 结束闸锁池（跨 AI 调用持有；与 cardLocks 分属不同命名空间，不可合并）。 */
    private final Object[] endLocks = new Object[STRIPES];

    public CardLockRegistry() {
        for (int i = 0; i < STRIPES; i++) {
            cardLocks[i] = new Object();
            endLocks[i] = new Object();
        }
    }

    /** 卡片文件读-改-写锁：同一 (userId, cardId) 恒落同一把（加锁粒度 = 单卡）。 */
    public Object cardLock(String userId, String cardId) {
        return cardLocks[stripe(userId, cardId)];
    }

    /** end 结束闸：同一 (userId, cardId) 恒落同一把；与 {@link #cardLock} 是**不同**对象。 */
    public Object endLock(String userId, String cardId) {
        return endLocks[stripe(userId, cardId)];
    }

    /**
     * 条带下标：高位异或扩散 + 位掩码（同 {@code TradingAppService.tradeLock} 口径），
     * 键 = {@code userId|canonicalCardId}（多用户隔离：不同用户的同号卡片不共锁）。
     */
    private static int stripe(String userId, String cardId) {
        String key = (userId != null && !userId.isBlank() ? userId : "default")
                + "|" + canonicalCardId(cardId);
        int h = key.hashCode();
        return (h ^ (h >>> 16)) & (STRIPES - 1);
    }

    /**
     * 卡 id 归一化：对齐 {@link CardFileRepository#findById} 的旧版数字 id 兼容（"1784872873886"
     * 会匹配到 "card_1784872873886"）。不做这一步的话，同一个卡用两种写法传入就会落进**两把**
     * 不同的锁——锁形同虚设，而 findById 却能读到同一个文件。
     */
    private static String canonicalCardId(String cardId) {
        if (cardId == null || cardId.isBlank()) return "";
        return cardId.startsWith("card_") ? cardId : "card_" + cardId;
    }

    /** 条带数（测试断言「上限是常量」用）。 */
    public int stripeCount() {
        return STRIPES;
    }

    /** 当前持有的锁对象总数 = 2 × 条带数（**常量**：不随 (userId, cardId) 数量增长）。 */
    public int lockCount() {
        return cardLocks.length + endLocks.length;
    }
}
