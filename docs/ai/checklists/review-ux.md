---
title: 交互走查检查清单
description: ux-reviewer 逐条检查项（人也能用）——操作路径/状态机/异常流/反馈/跨端/误触
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 30
depends-on: []
related: [../roles/ux-reviewer.md]
tags: [review, checklist, ux]
---

# 交互走查检查清单（UX）

| # | 检查项 | 判定 |
|:-:|:-------|:----:|
| U1 | 输入→发送→AI 处理→展示 全链路每步有状态反馈（无静默等待）| PASS/FAIL |
| U2 | 卡片状态机四态转换无卡死态；waiting 超时有复位 | PASS/FAIL |
| U3 | 失败/降级/重试/删除 都有反馈；无静默吞错 | PASS/FAIL |
| U4 | 异步操作有进度/占位（上传进度、生成中）| PASS/FAIL |
| U5 | 双端（app/web）同一流程行为对拍（降级策略一致）| PASS/FAIL |
| U6 | 键盘收起、手势切换无误触、无丢输入（草稿保留）| PASS/FAIL |
| U7 | 时间线/Feed 聚合：一次输入=一个事件；跨天/多轮边界无重复无丢失 | PASS/FAIL |
| U8 | 空态有引导；错误态人话可理解 | PASS/FAIL |
| U9 | 删除操作有确认；删除后关联（记忆/卡片）一致清理 | PASS/FAIL |
