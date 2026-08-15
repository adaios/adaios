---
title: AdaiOS AI 协作入口
description: 任何 AI 工具打开本项目的统一入口——项目定位、协作规则、审查体系导航（工具无关）
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 56
depends-on:
  - docs/ai/README.md
related:
  - CLAUDE.md
  - docs/VISION.md
  - docs/architecture/product-roadmap.md
tags: [ai, entry]
---

# AdaiOS — AI 协作入口

> 本文件是**任何 AI 工具**进入 AdaiOS 项目的统一入口（与工具无关；Claude/Qoder/DSH 等均读取同一份）。人类完整项目说明见 `CLAUDE.md`，本文件只承载 AI 协作必需的最小导航。

## 项目一句话

AdaiOS 是一套 **Personal AI Operating System**：以 Kernel（Context + Memory + Knowledge）为核心、个人文件（`data/`）为资产、Domain OS（`os/`）为能力边界。不是 CRUD 应用。

## AI 协作规则（必读）

1. **必读文档**：先读 `docs/VISION.md`（理念）→ `docs/architecture/product-roadmap.md`（唯一蓝图）→ `docs/ai/README.md`（本会话协作标准）
2. **工作焦点分离**：子项目有独立 CLAUDE.md（`services/adai-core`、`apps/*`、`os/*`）；在哪个目录工作只看哪个领域
3. **入口统一，后台分流**：`POST /api/v1/records` 是唯一输入入口
4. **第一原则「无第三视角」**：所有用户可见展示必须是「我和阿呆」的自然对话，不得出现系统视角标签（问：/答：/图片记录：/【备注】）
5. **File First**：`os/` 与 `data/` 知识以文件为准，数据库为查询存在；`data/` 隐私受 gitignore 保护，不提交
6. **审查只报告不直接修**（除 P0 数据丢失可与用户确认后修）

## 审查体系（docs/ai/）

| 命令/操作 | 文件 | 说明 |
|:---------|:-----|:-----|
| 全维度走查 | `docs/ai/process/audit.md` | 8 审查官独立并行全量走查 + 交叉印证 |
| 增量深审 | `docs/ai/process/review.md` | 按改动派对应审查官 |
| 收尾闭环 | `docs/ai/process/ship.md` | /ship：测试→契约→登记→guard-meta 门禁→提交 |
| 审查角色 | `docs/ai/roles/` | 产品架构/交互/界面/后端/前端/文档/知识数据 8 官 |
| 检查清单 | `docs/ai/checklists/` | 逐条可执行（人也能用）：8 官清单 + guard 守护 |
| 元数据规范 | `docs/ai/frontmatter-spec.md` | 文档 frontmatter 契约（图谱/治理/归档）|
| 元治理自检 | `docs/ai/guard-meta.sh` | 一条命令：frontmatter 图谱断链/lines 漂移/孤儿（`--fix` 回写）|

## 状态真相源

- 测试数/端点数/运行环境：`docs/reference/status.md`
- 未修项：`docs/review/REVIEW.md`
- 批次历史：`docs/reference/change-log.md`
- 发布：`docs/releases/`、`docs/architecture/product-roadmap.md`

## 工具接入

本文件与 `docs/ai/` 是**项目内唯一标准**；工具侧入口（如某 IDE 的 AI 插件指向本文件）配置在工具自己的设置里，不在本项目。换工具零迁移。
