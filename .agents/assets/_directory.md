---
title: assets/ 目录契约
description: assets/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 51
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# assets/ 目录契约

**职责**：AI 工程静态知识——规范（怎么做）· 边界（不做什么）· ADR（为什么这么定）· 已知坑（别踩什么）

## 职责边界
- **放**：规范 / 边界 / 决策记录 / 已知坑；每条都带 frontmatter 与图谱边
- **不放**：一次性讨论 → `docs/inbox/`；流程定义 → `process/`；工具脚本 → `guards/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `./adr/` | 边界与规范多由 ADR 定调 |
| 被依赖 | `../roles/` `../checklists/` | 审查官与清单引用这里的规范作判据 |
| 工具 | `../guards/ai-guard-meta.sh` | 校验 frontmatter 图谱 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 写代码前 | `ai-guard-context.sh`（开工自举） | `conventions.md` · `boundaries.md` |
| 踩坑后 | AI 主动提示 → **人确认** | `pitfalls.md`（禁止自动写入） |
| 做技术决策时 | `workflow/design.md` | `adr/`（先查有没有定过） |

## 约束

- 每个 md 必带 frontmatter **10 字段**
- **ADR 是 append-only**：改动写新记录 + `supersededBy` 链，不回头改已接受的
- **禁止**把一次性讨论 / 未验证假设写进来（须人确认）

## 守卫（谁保证这里不腐烂）

- `ai-guard-meta`：M1 断链 / M2 lines / M3 孤儿 / M4 正文路径

## 维护动作

1. 新增规范 → 写文件 → 补 `_index.md` 清单 → 确认被引用（否则 M3 孤儿）
2. ADR 变更 → 新增编号文件 + 旧文件标 `superseded` + `supersededBy` 指向新的
