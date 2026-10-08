---
title: app 端交易插件 · 体验重构走查报告（2026-10-09）
description: app 端这一轮的真机走查报告（累积单文件 + 轮次节）——§1 第 1 轮：iOS 模拟器真机逐屏实拍（首屏四层 / 持仓就地展开 / 自选只读），含打码纪律与未覆盖声明
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

## §1 · 2026-10-09（第 1 轮）

**本轮判定：✅ 通过（无 P0 / 无 P1）**——交易页形态在 **iOS 26.5 真机渲染**下成立：判断句在最上面一行、持仓是一行一只且点开就地展开、自选只读段与「缺数据不编」的口径都对得上。

### 1.1 取证方式（可复跑，供下一轮照抄）

本机 **macOS 无 Computer Use 授权**（`cua` 报 `permissions are not granted`）、且 **Xcode 27 没有可点的 `Simulator.app`**（只有 headless 设备 + Device Hub）——所以走查没走 GUI 自动化，改成：

1. **设备内驱动**：`apps/adai-app/integration_test/walkthrough_test.dart`（integration_test）在**模拟器里真实点击**真实 App（真后端 `:8080` + 本地 `data/` 快照）；
2. **外部取证**：`xcrun simctl io booted screenshot` 每 2 秒拍一张，事后按阶段挑帧；
3. **两个系统级拦路石已在测试侧绕开**（**未改任何产品代码**）：① 通知授权弹窗 → mock 原生推送通道（`adai/push` 返回「已授权、无 token」）；② 「输入 iPhone 密码」本地解锁闸 → 用壳层自带的测试注入点 `RootApp(biometric: _NoGate())`（`main.dart:84`）。
   命令：`WL_PW=$(grep '^ADAI_SMOKE_PASSWORD=' services/adai-core/.env | cut -d= -f2-) flutter test integration_test/walkthrough_test.dart -d <sim-udid> --dart-define=API_BASE_URL=http://localhost:8080 --dart-define=WL_PW="$WL_PW"`

### 1.2 证据（`audit-shots-app-20261009/`，**金额默认打码**状态下拍摄）

| 图 | 内容 | 对判据 |
|:--|:--|:--|
| `app-1-first-screen.png` | 首屏：`10-09 · 今天` + 👁 → **判断句「持仓 2 只 · 1 只破了你的线」**（橙）+ 小字「破了你的线：有研新材」 → 入账（截图入账 / 精确填写 / 一句话） → 「今天没动 · 想动，没动」 → 持仓明细 2 只（云南锗业 `+61.4% −1.3%` 收起、**第二行「破了你的 49.00」橙字**） → 自选 23 只 | I-1 判断句在首屏第一行 · I-2 一行一只 · I-8「与你的线」在场 · 无事不出横幅 |
| `app-2-position-expanded.png` | 点持仓行 → **就地展开**：`••••股 · 成本 •••• · 现价 85.71 · 未设止损` + 当日 `•••• / −1.34% / 47.75%` + 批次「3 个批次 · 最近买入 9/30」+ **「这只票阿呆怎么说」「批次明细」** | I-2 三条展开内容齐 · I-4 数量与成本打码、现价/止损明文 · 点开不跳页 |
| `app-3-watchlist.png` | 下滚：自选段（名称 代码 · 行业 · 信号，如「KDJ死叉」「多头排列」）——**右侧给的是行业/信号，不是涨跌** | I-3 自选只读 · 「端点无行情就不编涨跌」的口径落地 |

> **打码纪律**：「看金额」的显形态也拍到了（本地核过：数量/成本显形、金额千分位），**刻意不入库**——避免明文金额进仓。

### 1.3 与测试的关系

`flutter test` 465 全绿（判据级）+ 本轮真机实拍（形态级）**互为补集**：测试证明「行为对」，实拍证明「页面上真的长这样」（web 轮批 6「测试全绿但页面没出现」的教训在这端不重演）。

### 1.4 未覆盖（如实声明）

- **截图入账的真图链路**（传 → 认 → 记 → 失败四步）未在真机跑（需真实成交截图；逻辑层已由 3 条测试覆盖）；
- 手势 / 连续滚动手感（程序化点击不覆盖）；
- 推送**真到达**（需生产 APNs；本轮只验到落点形态未做）；
- `ux-visual` 视觉层审核与外部视角三官（陌生人 / 社会性 / 支持台）**未派**。

## §2 · 与上轮差异

无上轮（本轮为第 1 轮）。本轮之前的一次尝试因**本机锁屏**（UI 自动化不可用）未能取证，那次不计轮次。
