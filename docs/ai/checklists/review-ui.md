---
title: 界面审查检查清单
description: ui-reviewer 逐条检查项（人也能用）——触达/层级/间距/三端/深色/空态
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 36
depends-on: []
related: [../roles/ui-reviewer.md]
tags: [review, checklist, ui]
---

# 界面审查检查清单（UI）

| # | 检查项 | 判定 |
|:-:|:-------|:----:|
| V1 | 关键操作区域大小安全（≥44pt）、位置无误触风险（背面主页不靠顶/不误触系统区）| PASS/FAIL |
| V2 | 信息层级清晰：标题/正文/辅助 通过字号/颜色/间距区分 | PASS/FAIL |
| V3 | 间距/圆角/边框 token 统一，无散落硬编码 | PASS/FAIL |
| V4 | 三端视觉语言一致（值复制非适配）| PASS/FAIL |
| V5 | 深色模式对比度足够（暖灰 6 级正确使用）| PASS/FAIL |
| V6 | 空态/加载态/错误态都有设计（非白屏）| PASS/FAIL |
| V7 | 文本截断/溢出安全（emoji 不劈开、超长不横向溢出）| PASS/FAIL |
| V8 | 图标/文案语义正确，无中英混杂 | PASS/FAIL |

| V1-3 | 移动端全屏页（无 AppBar）必须包 SafeArea；顶部交互区不得进入状态栏 | LauncherPage 误触（走查 P1-W10，2026-08-15）|
| V5-2 | 空态/引导文案禁用 darkGrey6（对比度 <2.1:1）；小字号元信息不用 darkGrey5 | 对比度不达标（走查 P1-W11，2026-08-15）|
| V7-3 | 所有文本截断按 grapheme/rune 边界（禁止对含 emoji 文本 substring）| emoji 劈开（走查 P2，2026-08-15）|
| V8-2 | 「真实后端」页面不得残留 MOCK/占位标记 | admin MOCK 徽标（走查 P1，2026-08-15）|

| V2-3 | 色板单一真相：frontend-reference 颜色表与三端 `app_colors.dart` 值对拍（值复制策略下文档易过期）| frontend-reference 旧色值 `#0E0E0E`/`#2BC457`（旧 P13，2026-08-15）|
| V3-2 | 涨跌/盈亏颜色语义单一真相：frontend-reference 颜色表补语义行，三端 app_colors 对拍含 darkRed，杜绝橙/红混用 | 三套涨跌色语义并存（战略 #132，2026-08-15）|
| V8-3 | 三端核心词页面标题对拍：记忆/时间线/交易/任务/档案五页标题中英对照，混用即命中 | 移动端 memory 英文 vs 桌面「记忆」（战略 #131，2026-08-15）|
| V6-2 | 首屏 home 型页面空态可执行性：空态文案提示的动作必须真实存在于页面（有按钮可触发），无可退出层级不得要求用户硬刷新 | 选号页空态「刷新」无按钮（P2 #190）|
| V8-4 | 时段降级文案的 emoji 等装饰符必须与时段分支联动（只改问候词、留死 ☀️ = 修复不完整）| 凌晨「深夜好」仍配 ☀️（P2 #221，2026-08-12）|
