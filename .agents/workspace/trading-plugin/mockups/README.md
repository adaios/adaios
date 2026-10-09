---
title: 交易插件 UI/UX 原型（可点稿）
description: 2026-10-07 夜做的一批 UI/UX 原型——6 个自包含 HTML（可在浏览器直接打开、可点、可切方向）+ 33 张验证截图；配合 uiux-discovery-20261007.md 使用
version: 1
created: 2026-10-07
updated: 2026-10-07
status: active
lines: 30
depends-on: []
related:
  - ../uiux-discovery-20261007.md
  - ../handoff-20261007.md
tags: [workspace, trading, mockup]
---

# 交易插件 UI/UX 原型

> 自包含 HTML（无外部依赖）——**浏览器直接打开**即可，可点、可切方向。设计口径见 `../uiux-discovery-20261007.md`。

| 文件 | 是什么 |
|:--|:--|
| `trading-ui-directions.html` | **web 主屏三方向**（专业终端 / 叙事卡片 / 驾驶舱）← 已圈选「专业终端」 |
| `trading-app-directions.html` | app 首屏三方向（清单优先 / 结论先行 / 图形优先）← 已圈选「清单优先」 |
| `trading-app-refined.html` | app 第一版：**首屏（清单优先）+ 一只票的批次** |
| `trading-app-entry-flow.html` | app **截图入账四步**（传 / 认 / 记 / 失败） |
| `trading-web-full.html` | **web 全量 8 屏**（持仓·账·分析·清仓·案例·K 线·导入·全量地图） |
| `trading-app-rest.html` | app 剩下五屏（今天 / 收益日历 / 资金 / 推送设置 / 复盘历史） |
| `png/` | 上述页面的**验证截图**（1440/2x，我逐屏自检用的）——可以删，HTML 才是原件 |

**看图要点**：HTML 里每张都有「👁 看金额」可点（打码态 ↔ 显形），部分有「新用户·空账号」开关。web 那份用 wide 宽度看（≥1024）才对得上设计。
