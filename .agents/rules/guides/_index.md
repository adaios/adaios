---
title: guides/ 目录索引
description: .agents/rules/guides/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 40
depends-on: []
related: [./_directory.md]
tags: [meta, index, guides]
---

# guides/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**工程与协作规则**——回答「怎么做」（构建 / 测试 / 运行 · 分支 / 合并 / 推送 / 发布 · worktree 并行 · 固定动作 · 技能使用）。**人读与 AI 读共用同一份**。

## 文件清单（7 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `branch-development.md` | 分支开发规范（AI 上下文工程视角）——资产全景 · 统一流程 · **合并后重建出口** | active |
| `development.md` | 开发指南：全局构建/测试/运行/部署命令 + 换机必跑的三件事 | active |
| `git-workflow.md` | Git 工作规范（单人 + 多 AI 工具 + GitHub）——分支模型 · 合并 · 推送 · 发布（**tag ＝ 生产实际部署的 commit**） | active |
| `qoder-parallel-workflow.md` | Qoder CN 并行工作流实验手册（worktree 多任务，**跨项目通用**） | active |
| `routine.md` | ★ **固定动作清单（每天/每周/到期）**——周期性人肉工作总清单 + 到期红线 | active |
| `skills-usage.md` | 技能使用指南：技能何时用、怎么触发、怎么维护 | active |
| `worktree-workflow.md` | AdaiOS worktree 并行工作手册——**本项目专属**（外挂三件套 / 沙箱边界 / 端口与构建锁） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`ai-guard-structure` S2 双向校验；新增文件后跑 `bash .agents/mechanism/guards/ai-guard-structure.sh --fix`）

## 来源

2026-10-04 目录归纳批：自 `docs/guides` 移入 7 份**工程规则**（同目录的 `project-os-usage.md` 因 `status: superseded` 转入 `../../../docs/archive/`）。判据＝**按性质**：本区回答「怎么做」，故归 `.agents/`。
