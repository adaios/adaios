---
title: docs/records 目录索引
description: docs/records/ 的文件清单与过期判断——**历史存档区**（走查存档 / 事故记录 / 发布史 / 归档问题清单）
version: 2
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 51
depends-on: []
related: [./_directory.md]
tags: [meta, index, records]
---

# docs/records 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**历史存档**——「**曾经发生过什么**」的原始记录。与 `.agents/records/`（活账本）的分工：**那边是 AI 正在读写的账；这边是已封存的档**。

## 文件清单

### 走查存档（`audits/`，27 份）

| 范围 | 说明 |
|:--|:--|
| `audits/2026-*.md` | 历次全维度走查 / 深审 / 对抗审查的**原始报告**（2026-08 ~ 2026-10） |
| `audits/_index.md` | 存档清单 |

### 事故与处置

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `testflight-beta-contract-missing.md` | TestFlight Beta 合同缺失事故处置（2026-09-29 ~ 10-03 复核 + 工单材料） | active |
| `issue-log.md` | 项目级问题清单（**⚠️ 已归档 2026-08-23，只读不新增**；未修项真相源 = `.agents/records/REVIEW.md`） | active |
| `app-polish-2026-08-15.md` | 应用打磨报告（一次性） | active |

### 发布史

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `release-v1.0.0.md` | v1.0.0 Release Notes（自 releases 区转入） | active |
| `release-template.md` | Release Notes 模板（自 releases 区转入） | active |

## 过期判断

- 本区文件**原则上不删**（保留历史痕迹）；退役依据见 `.agents/records/change-log.md`
- **不适用"引用必须指向现在"**：本区文件含"当时"的路径属正常（`ai-guard-meta` 的 M4 对本区豁免）

## 来源

**2026-10-04 二批**：自 review/audits（27 份）· review 的 app-polish · records 的 issue-log 与 testflight 记录 · releases（2 份）归集于此——判据＝「AI 上下文运行时**不需要**」（历史存档）。
