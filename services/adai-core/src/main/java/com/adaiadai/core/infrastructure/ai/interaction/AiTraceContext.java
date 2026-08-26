package com.adaiadai.core.infrastructure.ai.interaction;

/**
 * AiTraceContext — AI 调用追踪上下文（线程绑定）。
 * <p>
 * 调用点在触发 AI 调用前 {@link #set(userId, recordId, cardId, source)} 挂上"这次调用属于谁、与哪条记录/哪张卡相关"，
 * 由 {@link LoggingAiClient} / {@link LoggingVisualAiClient} 装饰器在打日志时读取。
 * 装饰器每次调用结束后 {@link #restore} 恢复进入前的快照——调用点无需 try/finally，
 * 且同一调用点连续多次 AI 调用（如复盘 compose→generate）共享同一 trace；无残留污染下一条记录。
 * <p>
 * 快照-恢复而非 clear 的原因：调用点 set 是"这次业务要关联的锚点"，装饰器只消费不销毁。
 *
 * @see LoggingAiClient
 * @see AiInteractionLogger
 */
public final class AiTraceContext {

    private static final ThreadLocal<Trace> HOLDER = new ThreadLocal<>();

    private AiTraceContext() {}

    /** 一次 AI 调用的业务锚点：所属用户 + 关联的记录/卡片/来源。 */
    public record Trace(String userId, String recordId, String cardId, String source) {}

    /**
     * 设置当前线程的 trace（覆盖旧值）。
     *
     * @param userId   用户 ID（日志按用户分目录）
     * @param recordId 关联记录 ID（无则 null）
     * @param cardId   关联卡片 ID（无则 null）
     * @param source   调用来源标识（question / log / retry / brief / trading_review / conversation / media / intent 等）
     */
    public static void set(String userId, String recordId, String cardId, String source) {
        HOLDER.set(new Trace(userId, recordId, cardId, source));
    }

    /** 读取当前线程 trace；未设置返回 null。 */
    public static Trace get() {
        return HOLDER.get();
    }

    /** 读取当前线程 trace 的来源标识（如 trading_review / question / brief）；未设置返回 null。 */
    public static String source() {
        Trace trace = HOLDER.get();
        return trace != null ? trace.source() : null;
    }

    /** 恢复快照（装饰器 finally 使用）；null 表示清空。 */
    public static void restore(Trace snapshot) {
        if (snapshot == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(snapshot);
        }
    }
}
