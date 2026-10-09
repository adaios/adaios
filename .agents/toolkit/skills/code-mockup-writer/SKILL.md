---
title: 建设技能：可点原型（UI/UX 稿）
description: 当用户说「画原型 / 出图 / 出可点稿 / 概念图 / 画几张看看」，或设计讨论需要「看与试」时加载——用自包含 HTML 出可点稿（含自检与入库），不是用生成式位图
name: code-mockup-writer
version: 1
created: 2026-10-07
updated: 2026-10-07
status: active
lines: 55
depends-on:
  - ../../../rules/assets/skills-spec.md
related:
  - ../../../workspace/trading-plugin/mockups/README.md
  - ../../../workspace/trading-plugin/uiux-discovery-20261007.md
tags: [skill, build, uiux, mockup]
---

# 可点原型（UI/UX 稿）

你是 AdaiOS 的**原型作者**——把设计方向变成**能点、能对比、字是真的**的稿子，让用户**圈方向**，而不是靠文字空对空。

## 触发条件

用户说「画原型 / 出图 / 出可点稿 / 概念图 / 画几张我看看」，或设计讨论卡在「说不清、得看」（`AGENTS.md` 原则 5：UI/UX 靠看与试收敛）。

## 执行步骤

1. **先问「这张图要帮用户判断什么」**——原型是**决策工具**，不是美术稿。三方向就画三张、能对比；要圈方向就别画成一堆细节。
2. **读真实产品资产再动笔**：主题色/字号/间距从**现有代码**取（如 `apps/adai-web/lib/theme/app_colors.dart`），页面结构从**现有页面**取。不凭想象配色、不编不存在的区块。
3. **用 HTML/CSS 写，不用生成式位图**。理由很硬：UI 评审看的是**字**，而生成式图里中文与数字一律糊。写 HTML 的副产品是**能点**（打码 ↔ 显形、切方案、空态开关）。
4. **一屏一屏自检**（必须做，别跳）：写完 → 用本机 Chrome 无头渲染 → 逐屏截图 + 量高度 + 扫横向溢出 → 改 → 再渲染。典型要修：几屏高度不齐（carousel 要求相等）、某行文字挤到换行/溢出、缺数据被渲染成 0。
5. **把约束画进图里**（否则用户看不出差别）：打码态/显形态、空账号态、缺数据「—」、涨红跌绿、无「该买/该卖/建议」字样。
6. **入库**：稿子进 `.agents/workspace/<任务>/mockups/`（HTML 是原件，截图放 `png/`），加 README 说明每张是什么、圈了哪个方向、怎么看。**不要只留在会话目录或 /tmp**——那里会丢。

## 约束与规则

- **只出稿，不改代码/数据**（`AGENTS.md` 规则 7）：原型属于讨论；用户说「开工」才动代码。
- **不画假的**：缺数据就画缺数据的样子（「—」），不要为了好看补一个数。
- **不留悬空**：稿子必须入库 + 在 README/设计稿里被引用（否则下个工具找不到）。
- 一屏里**同一套事实只出现一次**；标签文案不要与已有区块重复（否则测试的唯一性断言会红，也会让用户读重）。

## 输出要求

1. 每张稿的**绝对路径** + **怎么看**（是否要宽屏、哪些能点）；
2. 一次交付**成群**（三方向 / 一批屏），别一张一张来回；
3. 交付时说清**这批图要用户做的那个决定**（圈哪个方向 / 哪些摆法不对）。

## 参考资料

- 现存原型与看法：`../../../workspace/trading-plugin/mockups/README.md`
- 设计总稿写法（护栏 + 三问 + 三看 + 圈选记录）：`../../../workspace/trading-plugin/uiux-discovery-20261007.md`
- 自检渲染（本机无头 Chrome + Playwright，**需沙箱外执行**）：
  `NODE_PATH=<codex-runtimes>/dependencies/node/node_modules <...>/node/bin/node <shot.cjs>`
  `shot.cjs` 三件事：读 HTML 片段 → 包成整页 → `chromium.launch({channel:'chrome'})` 逐屏 `screenshot()` + 扫 `scrollWidth > clientWidth`
- 注意：沙箱内**连不上** `localhost` 回环（curl 返回 000），验证本机服务要放沙箱外跑。
