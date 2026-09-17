package com.adaiadai.core.kernel.todo;

import com.adaiadai.core.kernel.IdGenerator;

import java.time.LocalDate;

/**
 * Todo — 待办（Kernel builtin 基础能力，RFC 20260917）。
 * <p>
 * 与 memory 同级：人人都有、默认开、**无插件门控**。形态是纯清单——
 * 一句话 +（可选）到期日 + 两态（OPEN / DONE），不做看板/优先级/标签。
 * <p>
 * 采用 File First：存储为 {@code data/{userId}/todos/YYYY/MM.md} 中的 Markdown 条目。
 * <p>
 * 记忆联动（单向，RFC 20260917 §4.3）：待办是「你会看会点的那份」，记忆是「AI 用的那份」；
 * 状态只由待办流向记忆（完成 → markDone，删除 → 清 actionable），建待办时**不动**记忆。
 *
 * @param id             待办标识 {@code todo_yyyyMMdd_HHmmssSSS}
 * @param title          一句话（多行会被单行化）
 * @param status         状态（OPEN / DONE）
 * @param due            到期日（可空 = 不提醒）
 * @param sourceRecordId 源记录 ID（记录自动转待办时关联的 rec_xxx，可空）
 * @param createdAt      创建日期
 * @param updatedAt      最后更新日期
 */
public record Todo(
        String id,
        String title,
        TodoStatus status,
        LocalDate due,
        String sourceRecordId,
        LocalDate createdAt,
        LocalDate updatedAt
) {

    /** 便捷构造：无源记录（清单页手动添加）。 */
    public Todo(String id, String title, TodoStatus status, LocalDate due,
                LocalDate createdAt, LocalDate updatedAt) {
        this(id, title, status, due, null, createdAt, updatedAt);
    }

    /** 生成待办 ID（P1-2：单调时间戳，同毫秒不碰撞覆盖）。 */
    public static String generateId() {
        return IdGenerator.monotonic("todo_");
    }
}
