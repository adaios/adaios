---
title: AdaiOS AI 协作入口
description: 任何 AI 工具打开本项目的统一入口——项目定位、协作规则、审查体系导航（工具无关）
version: 1
created: 2026-08-15
updated: 2026-08-18
status: active
lines: 65
depends-on:
  - ai-engineering/README.md
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

0. **开工自举（必做，零人工）**：任何 AI 开始工作前**自动执行** `bash ai-engineering/guard-context.sh`，以其输出（状态/未修项/边界/坑/规范/待办/成本提醒）为上下文基线——用户不需要手动跑脚本、不需要回忆任何事（2026-08-18 用户确立）
1. **必读文档**：先读 `docs/VISION.md`（理念）→ `docs/architecture/product-roadmap.md`（唯一蓝图）→ `ai-engineering/README.md`（本会话协作标准）
2. **工作焦点分离**：子项目有独立 CLAUDE.md（`services/adai-core`、`apps/*`、`os/*`）；在哪个目录工作只看哪个领域
3. **入口统一，后台分流**：`POST /api/v1/records` 是唯一输入入口
4. **第一原则「无第三视角」**：所有用户可见展示必须是「我和阿呆」的自然对话，不得出现系统视角标签（问：/答：/图片记录：/【备注】）
5. **File First**：`os/` 与 `data/` 知识以文件为准，数据库为查询存在；`data/` 隐私受 gitignore 保护，不提交
6. **审查只报告不直接修**（除 P0 数据丢失可与用户确认后修）
7. **讨论与实施分离**（2026-08-16 确立，2026-08-18 明确范围）：讨论方向/方案/数据口径时**只聊不动手**——用户明确说「开工 / 做 / 改」后才改**代码与 `data/` 数据资产**；未指示前不写代码。**本规则只约束代码/数据修改**；AI 工程建设层面文档（`ai-engineering/` 协作规范与流程、AGENTS.md 等）属工程自身持续维护，可直接修订。违背此条即越界（已发生一次：账户总盈亏口径讨论中擅自改代码）

## 审查体系（ai-engineering/）

| 命令/操作 | 文件 | 说明 |
|:---------|:-----|:-----|
| 全维度走查 | `ai-engineering/process/audit.md` | 8 审查官独立并行全量走查 + 交叉印证 |
| 增量深审 | `ai-engineering/process/review.md` | 按改动派对应审查官 |
| 收尾闭环 | `ai-engineering/process/ship.md` | /ship：测试→契约→登记→guard-meta 门禁→提交 |
| 审查角色 | `ai-engineering/roles/` | 产品架构/交互/界面/后端/前端/文档/知识数据/Context 8 官 |
| 检查清单 | `ai-engineering/checklists/` | 逐条可执行（人也能用）：8 官清单 + guard 守护 |
| 元数据规范 | `ai-engineering/frontmatter-spec.md` | 文档 frontmatter 契约（图谱/治理/归档）|
| 元治理自检 | `ai-engineering/guard-meta.sh` | 一条命令：frontmatter 图谱断链/lines 漂移/孤儿（`--fix` 回写）|
| 文档自动对齐 | `ai-engineering/guard-align.sh` | 代码↔文档内容对齐：端点↔api-spec / 测试数↔status.md（git pre-commit 自动触发）|
| 任务上下文 | `ai-engineering/guard-context.sh` | 开工前生成上下文清单（状态/未修项/边界/坑/规范/待办，可按主题过滤）|
| 沉淀检查 | `ai-engineering/guard-sediment.sh` | ship 时检查沉淀/出表/登记（S1 坑/ADR、S2 REVIEW 出表、S3 change-log）|
| 部署门禁 | `ai-engineering/deploy-gate.sh` | 部署前强制 review+guard，部署后自动 smoke（最硬闸门）|
| 每周审查 | `ai-engineering/weekly-audit.sh` | cron 每周自动审查（守护/结构/对齐/失真/未修项，防休眠）|
| 成本监控 | `ai-engineering/guard-cost.sh` | 读 DSH 会话按天/会话算钱；收工前 `--record`，开工看 `guard-context.sh` C6.5 |
| 成本纪律 | `ai-engineering/checklists/cost.md` | 烧钱动作清单 + 省钱原则（错峰/断会话/控输出/降频/用对模型/盯账）|

## 状态真相源

- 测试数/端点数/运行环境：`docs/reference/status.md`
- 未修项：`docs/review/REVIEW.md`
- 批次历史：`docs/reference/change-log.md`
- 发布：`docs/releases/`、`docs/architecture/product-roadmap.md`

## 工具接入

本文件与 `ai-engineering/` 是**项目内唯一标准**；工具侧入口（如某 IDE 的 AI 插件指向本文件）配置在工具自己的设置里，不在本项目。换工具零迁移。
