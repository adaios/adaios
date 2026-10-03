---
title: docs/guides 目录索引
description: guides 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-10-03
status: active
lines: 36
depends-on: []
related:
  - ../_index.md
tags: [meta, index, guides]
---

# docs/guides 目录索引

**职责**：使用指南区——各功能/模块的人用指南。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| development.md | 开发指南：全局构建/测试/运行/部署命令 + 换机必跑的三件事（hooks/技能注册/定时任务） | active |
| routine.md | **固定动作清单（每天/每周/到期）**——周期性人肉工作总清单 + 2026-09-14 盘点的三个静默失效缺口 | active |
| skills-usage.md | 技能使用指南：技能何时用、怎么触发、怎么维护（给人看） | active |
| project-os-usage.md | Project OS 使用指南（**⚠️ 已退役**，2026-09-17 RFC 20260917）：project 插件与「项目阿呆」上下文注入已整体撤除；现仅作 `os/project-os/` 知识目录的阅读说明 | superseded |
| qoder-parallel-workflow.md | **Qoder CN 并行工作流实验手册（worktree 多任务）**——跨项目通用：环境配置、IDEA 协同、验证清单、坑与回滚（2026-09-17） | active |
| git-workflow.md | **Git 工作规范（单人 + 多 AI 工具 + GitHub）**——分支模型（GitHub Flow 精简版：只留 main + 短命分支）、合并（线性 + squash 取舍 + 冲突高发文件）、推送（收工即推，本地不再当唯一副本）、发布（**tag ＝ 生产实际部署的那个 commit** + GitHub Release）、GitHub 侧建议（分支保护 + 最小 CI）（2026-10-03）| active |
| branch-development.md | **分支开发规范（AI 上下文工程视角）**——**AI 上下文资产全景**（工具层 / 业务层 / 代码**都是文件、都走 git**；唯一不入库的三样本机状态）· **统一流程**（**加 skill 也开分支** → 本分支注册即用 → 合并 main → 其他分支 `merge main` + 重跑出口）· 建四类资产的硬约束 · 冲突面（`REGISTER` **取并集**）· 六条禁令 · 自检顺序 · **★ 合并后必须重建出口**（2026-10-03）| active |
| worktree-workflow.md | **AdaiOS worktree 并行工作手册**——本项目专属：worktree 是空壳（外挂三件套 data/.env/state 由 `scripts/worktree-prep.sh` 补齐）、DSH 沙箱边界、端口与构建锁、提交纪律、干净构建用法（2026-10-01） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
