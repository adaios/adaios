---
title: AI 协作文档元数据规范（Frontmatter Spec）
description: AdaiOS 全项目文档 YAML frontmatter 契约——字段定义、维护职责、图谱与治理机制
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 59
depends-on: []
related:
  - process/audit.md
  - ../../docs/VISION.md
tags: [ai, meta, governance]
---

# AI 协作文档元数据规范

> 目的：让项目内每个文档都是「自描述节点」——AI 打开第一眼懂用途、何时读、读了干嘛；人/工具可据此做图谱、统计、治理、归档。**与工具无关**（YAML 是事实标准：Obsidian/静态站点/AI 工具通用）。

## 一、字段契约

| 字段 | 维护者 | 说明 |
|:-----|:-------|:-----|
| `title` | 人（写文件时）| 文档名称，唯一标识 |
| `description` | 人 | 一句话：解决什么问题、AI 何时读、读了干嘛（对应方法论 Purpose/Trigger）|
| `version` | 人（重大变更 +1）| 语义版本，便于引用 |
| `created` | 人（创建一次）| `YYYY-MM-DD` |
| `updated` | **工具/流程**（/ship 自动，见 `process/ship.md`）| `YYYY-MM-DD`——手写必漂移，机器维护 |
| `status` | 人 | `draft` / `active` / `superseded` / `archived`（生命周期，归档机制基础）|
| `lines` | **工具**（/ship 自动）| 行数——治理：>300 提示拆分、>500 必须拆分 |
| `depends-on` | 人 | 我依赖谁（图谱出边）；反查「谁依赖我」由工具合成 |
| `related` | 人 | 相关但非依赖（图谱弱边）|
| `tags` | 人（可选）| 分类，便于统计/检索 |

## 二、维护职责分离（防元数据腐烂）

- **人**：title/description/version/created/status/depends-on/related/tags
- **工具/流程**：updated/lines（/ship 时自动回写；人不手写这两个字段）

## 三、图谱与治理机制

- **文档图谱**：`depends-on` + `related` = 边；`_index.md` 反查 + 目录清单 = 节点索引
- **孤儿检测**：无任何文件 `depends-on` 它 → 候选归档/删除
- **膨胀预警**：`lines` 超阈值（见上）→ 提示拆分；`updated` 久未动 + `status: superseded` → 归档
- **统计**：按 `tags`/`status` 聚合——哪些领域、哪些过期

## 四、适用范围

- **强制**：`docs/ai/**`（roles/process/checklists）、`AGENTS.md`、`docs/_index.md`、各目录 `_index.md`
- **渐进**：存量 `docs/**` 文档下次编辑时顺手补（不 retroactive 全量，避免一次性迁移成本）
- **异常**：`data/` 个人数据（非协作文档）、`os/*/` 知识资产（有自己工作流，不强加）

## 五、目录治理约定（`_index.md`）

每个关键目录一个 `_index.md`，含：目录职责一句话、文件清单（一行一个 + 一句话）、过期判断（`status != active` 或 `updated` 久未动 → 候选清理）。这是「文件自理」的机制——目录自己知道自己该有什么、哪些该走。

## 六、工具接入

任何 AI 工具读 `docs/ai/README.md`（或 AGENTS.md）即可执行全流程；工具专属入口配置在工具侧，不进项目。
