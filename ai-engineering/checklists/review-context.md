---
title: AI Context 审查检查清单
description: context-reviewer 逐条检查项（人也能用）——Purpose/Trigger/Action/Consistency 四问
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 30
depends-on: []
related:
  - ../roles/context-reviewer.md
tags: [review, checklist, context]
---

# AI Context 审查检查清单（Context）

> 对应 context-reviewer 四问：判断 `ai/context/`、`os/*/11-context/`、CLAUDE.md 加载结构能否帮助 AI **正确理解项目并执行任务**。

| # | 检查项 | 判定 |
|:-:|:-------|:----:|
| C1 | **Purpose**：文件存在的目的？AI 为什么需要读？删除会损失什么能力？| PASS/FAIL |
| C2 | **Trigger**：AI 何时该读？触发条件明确（写在文件里或由加载逻辑保证）？| PASS/FAIL |
| C3 | **Action**：读完 AI 该做什么？约束可执行？有无「禁止做什么」硬卡点？| PASS/FAIL |
| C4 | **Consistency**：同一知识多处重复会漂移？文件声称的状态与代码/实际目录一致？| PASS/FAIL |
| C5 | 决策/事实分离（L1/L2/L3）：模板区分「决策」（人定）与「事实」（代码/文件扫描得出），不混写 | PASS/FAIL |
| C6 | 加载结构自检：`ai/context/` 与 `os/*/11-context/` 的 README 引用的目录/文件真实存在，无复制模板漂移 | PASS/FAIL |
| C7 | 引用外部方法论（`ai-context-research/`）时路径有效且同步（仓库外同级目录）| PASS/FAIL |

---
**追加方式**：新发现 Context 类问题 → 追加一行，注明日期。
