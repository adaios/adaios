---
title: 产品架构审查检查清单
description: product-arch 逐条检查项（人也能用）——五层架构/数据流/Roadmap/原则/功能归属/叙事
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 42
depends-on: []
related:
  - ../roles/product-arch.md
  - review-ui.md
  - review-ux.md
tags: [review, checklist, product]
---

# 产品架构审查检查清单（Product）

> 承接旧 review-product 清单的产品定位项（P8/P9/P11/P22），UI/UX 项已拆分至 `review-ui.md` / `review-ux.md`。

## 主检查表

| # | 检查项 | 判定 |
|:-:|:-------|:----:|
| P1 | 五层架构符合度：新功能/改动明确归属 L1-L6 之一，无「不属于任何层」的游离功能 | PASS/FAIL |
| P2 | 数据流完整性：Record → Timeline → Context → Memory → Knowledge 闭环；新输入不绕过（手拼 prompt、跳过 ContextEngine）| PASS/FAIL |
| P3 | Roadmap 对齐：`docs/architecture/product-roadmap.md` 是唯一蓝图；偏离有 RFC 记录 | PASS/FAIL |
| P4 | 原则符合度（★）：第一原则「无第三视角」等产品原则不被违反；抽查 Feed/时间线/记忆页无系统标签（问：/答：/图片记录：/【备注】）| PASS/FAIL |
| P5 | 功能归属：新能力回答「Kernel 还是 Domain OS」；跨域协作经 application 编排（Domain 间禁直接依赖）| PASS/FAIL |
| P6 | 产品叙事一致性：文案/术语/称呼全局一致（阿呆/复盘/记忆/时间线不混用）| PASS/FAIL |

## 沉淀检查点（带上次发现）

| # | 检查项 | 上次发现 |
|:-:|:-------|:---------|
| P8 | 新页面/功能与五层产品架构（`product-architecture.md`）定位一致，不偏离 | —（旧 review-product P8）|
| P9 | 项目状态页/任务看板等数据展示是「个人 OS」风格（状态/进展叙事）而非表格堆砌 | 方向进展图硬编码（P3，已修）|
| P11 | 主入口功能对位：桌面重绘不丢移动端核心交互（分页/删除确认/复盘/反哺入口）| adai-web Feed 无分页 + 交易无复盘 + 删除无确认（战略/P1，待修）|
| P22 | 「图即上下文」类功能进入对话态后原图持续可见；追问/追加类交互必须有后端持久化载体（card 文件或记录），「刷新即失」与个人 OS 资产理念冲突 | 图片追问仅前端内存、刷新丢失（P1 #209，2026-08-12）|

---
**追加方式**：新发现产品架构类问题 → 追加一行，注明日期。
