/**
 * Todo — 待办（Kernel builtin 基础能力，RFC 20260917）。
 * <p>
 * 人人都有、默认开、无插件门控：记录里可执行的事自动进这里（RecordToTodoLinker），
 * 也可以自己在清单页加。形态是纯清单两态（OPEN / DONE）+ 可选到期日，
 * 到期当天由 TodoReminderService 推送提醒（类型 {@code todo-due}）。
 * <p>
 * 与 memory 关系：待办是用户会看会点的那份，记忆是 AI 用的那份；
 * 状态单向由待办流向记忆（完成 → markDone，删除 → 清 actionable）。
 */
package com.adaiadai.core.kernel.todo;
