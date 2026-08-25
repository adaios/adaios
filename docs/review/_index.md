---
title: docs/review 目录索引
description: review 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-25
status: active
lines: 37
depends-on: []
related:
  - ../_index.md
tags: [meta, index, review]
---

# docs/review 目录索引

**职责**：审核结果区——未修项滚动区 + 走查存档。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| REVIEW.md | 审核全量状态报告（未修项滚动区） | active |
| audits/2026-08-23-ai-engineering-meta-audit.md | AI 上下文建设工程体系元审核存档（主审核 + 对抗官复核 + 实证实验，修复批 072dcee） | active |
| audits/2026-08-23-reviewer-isolation-demo.md | 隔离审查演示存档（对抗官首战 + 上下文隔离，交易归集批残留） | active |
| audits/2026-08-20-app-health-check.md | app 全面体检走查存档（用户体感导向，4 官） | active |
| audits/2026-08-15.md | 首轮全维度走查存档（7 官） | active |
| audits/2026-08-15-ai-engineering-self.md | AI 工程层自伤自查存档（8 官） | active |
| audits/2026-08-16-ai-engineering-workflow.md | AI 工程工作流自伤自查存档（第二轮，三视角） | active |
| audits/2026-08-18-production-log.md | 生产日志审查存档（2026-08-18 journalctl 当日问题分级） | active |
| audits/2026-08-24-ai-calling-governance-doc-review.md | AI 调用治理方案文档深审存档（docs/backend/frontend/adversarial 四官隔离，S-9/S-10 登记） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
