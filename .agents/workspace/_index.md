---
title: workspace/ 目录索引
description: .agents/workspace/ 的文件清单与过期判断；**这是全工程最活跃的目录**（在制品容器）；契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 39
depends-on: []
related: [./_directory.md, ../rules/process/review-driven.md]
tags: [meta, index, workspace]
---

# workspace/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据）

## 子目录（3）

| 子目录 | 装什么 | 生命周期 | 索引 | 契约 |
|:--|:--|:--|:--:|:--:|
| `requirements/` | 需求文稿（编写者 / 审核者双角色对打的在制品） | 定稿后**归档**并清空 | [→](./requirements/_index.md) | [→](./requirements/_directory.md) |
| `designs/` | 设计稿（含对家评审稿） | 设计定案后**归档**并清空 | [→](./designs/_index.md) | [→](./designs/_directory.md) |
| `tasks/` | 任务卡（当批要做的事） | 完成后**清空** | [→](./tasks/_index.md) | [→](./tasks/_directory.md) |

## 文件清单（4 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `designs/_template-design.md` | 模板 A——设计文档（设计文档编写者产出）：本轮回应 · 设计 · 取舍 · 未决 · 自评风险；每轮一份，不覆… | active |
| `designs/_template-review.md` | 模板 B——设计审核文件（设计文档审核者产出）：审核对象 · 结论 · 问题清单（P0–P3）· 收敛判定；每轮… | active |
| `requirements/_template.md` | 需求文稿模板——问题动机 · 范围 · 验收标准 · 未决问题 · 定稿记录；定稿由人拍板，随后归档 docs/ | active |
| `tasks/_template.md` | 分支任务账本模板——本分支的目标 · 进度 · 风险 · 待归档条目；合并时按「待归档」搬进全局账本后删除本文件 | active |

## 过期判断

- **本目录允许"空"**——模板与两件套常在，**在制品应随任务结束而清零**（长期堆积 = 未收敛，见 `_directory.md`）
