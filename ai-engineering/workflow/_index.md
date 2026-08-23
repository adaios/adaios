---
title: workflow 工作流层索引
description: AI 工程工作流层目录治理——讨论→方案→开发→审核→验收 六段闭环
version: 2
created: 2026-08-15
updated: 2026-08-23
status: active
lines: 42
depends-on: []
related:
  - ../README.md
tags: [ai, meta, index, workflow]
---

# workflow 工作流层索引

**职责**：AI 工程过程定义——从想法到验收的完整闭环。开发（develop）沿用现有直接改代码流程，未单独成文（见 design.md 量级匹配表）。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| overview.md | 工作流总览（主心骨 + 五段闭环 + guard 挂点，一张图导航）| active |
| discuss.md | 讨论登记 + 沉淀过滤器（AI 主动发现，人确认）| active |
| design.md | RFC 骨架标准化 + 决策沉淀规则 | active |
| develop.md | 开发执行（动工前查边界/坑/规范 + 沉淀触发）| active |

## 完整闭环（五段 + 双审核 = 六节点）

```
discuss（讨论）→ design（方案/RFC）→ develop（开发，直接改代码）
     → ship（收尾 + guard-meta 门禁）→ review（增量深审）/ audit（全维度走查）
        ↓ ship 时                                        ↓ 审查后
    决策入 assets/adr/  + 坑入 checklists+pitfalls    未修项入 state/ 指针
```

物理路径：discuss/design/develop 在 `workflow/`；ship/review/audit 在 `process/`（workflow 前置段 → process 后置段）。

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
