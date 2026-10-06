package com.adaiadai.core.domain.trading;

import java.time.LocalDateTime;
import java.util.List;

/**
 * TradingAuditRepository — 系统侧**不可见修改日志**（设计 §4.3，2026-10-06）。
 *
 * <p>落点 {@code data/{userId}/trading/audit/{yyyy-MM}.jsonl}，每条
 * {@code {时间, 记录 id, 字段, 前值, 后值, 来源}}——**仅供复算审计**：
 * 不进任何用户可见面、不进 AI 上下文（与用户可见的 {@code advice-history/} 严格分家，不许混用）。
 *
 * <p><b>失败策略（fail-visible）</b>：审计写失败 → 抛 {@link com.adaiadai.core.infrastructure.storage.StorageException}，
 * 调用方**不得让业务静默成功**（先在锁内写审计、后改账）。
 */
public interface TradingAuditRepository {

    /** 追加一条修改日志（fail-visible：失败抛异常）。 */
    void append(String userId, AuditEntry entry);

    /**
     * 批量追加（P2-交易96）：整批一次读改写落盘——多字段纠错/导入的留痕要么全有要么全无，
     * <b>不留「部分字段有留痕、账未改」的半截孤儿</b>。空列表 = 不写（no-op）。
     */
    void appendAll(String userId, List<AuditEntry> entries);

    /** 读取全部修改日志（按时间正序；复算审计用）。 */
    List<AuditEntry> findAll(String userId);

    /** 一条修改日志：{时间, 记录 id, 字段, 前值, 后值, 来源}。 */
    record AuditEntry(LocalDateTime time, String recordId, String field,
                      String before, String after, String source) {
        public static AuditEntry of(String recordId, String field, String before, String after, String source) {
            return new AuditEntry(LocalDateTime.now(), recordId, field,
                    before != null ? before : "", after != null ? after : "", source);
        }
    }
}
