---
title: app 端交易插件 · 体验重构走查报告（2026-10-09）
description: app 端这一轮的真机走查报告（累积单文件 + 轮次节）——§1 轮判定：构建级 + 登录屏实拍通过，进入交易页后的逐屏实拍因**本机锁屏**阻塞顺延；含测试级证据清单与待补清单
version: 1
created: 2026-10-09
updated: 2026-10-09
status: active
lines: 59
depends-on:
  - ./design-app-20261009.md
related:
  - ./scope-app-20261009.md
  - ./review-app-design-20261009.md
  - ../../rules/process/review-driven.md
tags: [workspace, trading, app, walkthrough]
---

# app 端交易插件 · 体验重构走查报告

> 规范：`.agents/rules/process/review-driven.md` **§10 体验走查段**——走查是**回验型**活动，允许「累积单文件 + 轮次节」；
> 每轮一个独立小节（本轮判定 + 与上轮差异 + 证据），历史小节不得覆盖删除；连续 3 轮不收敛则升级给人。

## §1 · 2026-10-09（第 1 轮 · 部分完成）

**本轮判定：⏳ 部分**——**构建级与登录屏取证通过；交易页逐屏实拍未取得**（如实声明，不当成「已走查」）。

### 1.1 已取得的证据（真机渲染）

| 证据 | 内容 |
|:--|:--|
| iOS 模拟器真机构建 | `flutter run -d 35217F5A…（iPhone 17 · iOS 26.5）--dart-define=API_BASE_URL=http://localhost:8080` → **Xcode build 成功**、App 启动、Impeller(Metal) 渲染 |
| 登录屏实拍 | `simctl io booted screenshot`（**本机取证、未入库**）——中文正常、暗色主题正常、账号预填正常 |
| 本地后端 | `services/adai-core` 在 `:8080` 运行（`/api/v1/accounts` 返回 401 人话 = 服务活着）；`data/` 为本地快照 |

### 1.2 阻塞（本轮未取得的部分）

1. **macOS 处于锁屏**：Computer Use（`cua`）报告 `Native apps: The Mac is locked and automatic unlock could not unlock it` ⇒ 无法用真实点击驱动模拟器界面（登录 → 进交易插件 → 逐屏）；
2. **Xcode 27 下 `Simulator.app` 不在预期路径**（`/Applications/Xcode.app/Contents/Develop` 无该 App；仅有 headless 设备 + DeviceHub），`open -a Simulator` 失败 ⇒ 没有可点的窗口，`simctl` 只能截图、不能点击。
   ⇒ 处置：**解锁后补一轮**（可复用本报告 §1 的方法；预期 10 分钟内可完成「登录 → 交易首页 → 持仓展开 → 今天区」四张实拍）。

### 1.3 测试级证据（替代不了走查，但可追溯）

| 项 | 证据 |
|:--|:--|
| 首屏四层 | `apps/adai-app/test/pages_widget_test.dart`：判断句念结论 · 破线换句 + 小字点名 · 持仓一行点开/收起 · 自选只读一段 · 今天区入口 |
| 入账主线三卡点 | 同文件：分张提交（3 张 → 3 次请求）+ 跨图指纹去重 · 异常优先（卖超持仓单独醒目 + 其余折行）· 回执「这次过后：持仓 0 → 1 只」 |
| 判断句 / 与你的线 纯逻辑 | `apps/adai-app/test/trading_verdict_test.dart`（12 条：常态 · 破线 · 到线 · 账实取不到不编 · 组合小字顺序 · 破线/在线/没设线/没取到现价） |
| 全量回归 | `flutter test` **465 全绿** · `flutter analyze` **0 issue** |

### 1.4 未覆盖（如实声明）

- 真机**逐屏实拍**（首屏四层、持仓展开、今天区、入账候选卡、回执）；
- **真图截图入账**（VLM 归集）与**失败步**（`flow-4`）的真机形态；
- 手势 / 连续滚动手感（程序化点击本就不覆盖）；
- 独立审核：`review-app-design-20261009.md`（设计段交互层）**已交付**；`ux-visual` 视觉层与外部视角（陌生人 / 社会性 / 支持台）**待派**。

## §2 · 与上轮差异

无上轮（本轮为第 1 轮）。
