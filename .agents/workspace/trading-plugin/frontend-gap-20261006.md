---
title: 前端未交付清单（交易插件重做 · web / app）
description: 编码段只交付后端（67/78 文件），设计 §6「三端呈现」的 web/app 范围 0 实现——补做清单 + 验收口径，派给 feat/trading-plugin 分支
version: 1
created: 2026-10-06
updated: 2026-10-07
status: active
lines: 70
depends-on:
  - design-final-20261006.md
  - requirement.md
related:
  - LEDGER.md
tags: [workspace, trading, frontend]
---

# 前端未交付清单（交易插件重做）

## 一、结论（先看这段）

**编码段（`45a1558d`）交付的是「后端 + 契约」，不是「三端」。**

| 项 | 事实 |
|:--|:--|
| 文件分布 | `services/adai-core` **67** · `adai-admin` 5 · `adai-web` 3 · `adai-app` 3——后三者**全是**推送卡徽章 / B1 文案那类顺手修复 |
| 后端 | R-05～R-12 的 **13 个端点全部实现**，2026-10-06 已上线生产（第二十二次部署，线上逐条真链验过） |
| **web 端** | 设计 §6 要求的功能 **一个都没做**（见 §二）——新端点 `advisory` / `rules/*` / `analysis/*` / `trading/import` 在 `apps/adai-web/` **0 引用** |
| app 端 | 同样只有 B1 文案（`trading_page.dart` 7 行）；§6 里 app 那几行（结论入口 / 收提醒 / 只读曲线）未做 |
| 性质 | **不是分批**——§13 的 U1「分批」讲的是后端模块（ingest→ledger→rounds→analytics→rules→advisory）；§6 末尾明写「**不许出现『某功能只在一端、且未声明』**」，现状是「只在后端、三端都没有」**且未声明** |

前端要接的**全是已上线的生产契约**（`https://api.adaiadai.com`，adai 账号可登录），**不需要等后端**。

## 二、逐条欠账（对照 §6 三端呈现表）

| 需求 | §6 要求 | 端点（已上线，可直接用） | 现状 |
|:--|:--|:--|:--|
| `R-12` | web：**一次把导出的文件交给它就行** | `POST /trading/import`（multipart `files` 可多选，`dryRun` 可选） | ❌ 仍是 5 个分散入口（各 Tab 专属按钮） |
| `R-05` | web：**三粒度明细**；app：只给结论入口 | `GET /trading/analysis/{scope}`（`global` / `symbol` / `round`） | ❌ 零界面 |
| `R-06` | web：自建 / 导入 / **认候选** | `GET /trading/rules/user` · `POST /trading/rules/candidates` · `POST /trading/rules/{id}/accept` · `PUT`/`DELETE /trading/rules/{id}` · `POST /trading/rules/custom` | ❌ 零界面（`GET/PUT /trading/rules` 是**另一套**：参数化阈值 `rules.yaml`，两者并存不冲突） |
| `R-07` | web：**看依据**；app：收提醒 | `GET /trading/advisory/{ring}`（`buy` / `hold` / `sell`；错值返 400 人话） | ❌ 零界面 |
| `R-08` | web：**就地改** | `PUT` / `DELETE /trading/trades/{tradeId}` | ❌ 零界面 |
| `R-03` | web：图上 / 列表人工切笔 | `POST /trading/rounds/boundaries` · `PUT /trading/rounds/{id}` | ❌ 零界面（`GET /trading/rounds` 已上线） |
| `R-13` | web：**画像页**（客观 + 主观），如实标「可能不准」 | `GET/PUT /trading/profile` + 认知 5 端点 | ❌ **前端零入口**（设计 §9 表点名「本轮补」，未补） |
| `R-02` | web：差额 + **断点定位** | 既有（`GET /trading/integrity` 已在用） | ⚠️ 对账横幅已有（drift/gaps），**断点定位**未做 |
| `R-04` | web：K 线 + 指标 + **我的买卖点** | 既有 K 线（`case_kline_chart.dart`） | ⚠️ 案例 K 线有，**加减持点/止损线标注**未做 |
| `R-09` / `R-10` | web：自选留历史 / 案例库全功能 | 既有 | ⚠️ 部分（自选 Tab / 案例打分已有） |
| 资金层 | web：资金曲线 + 分周期盈亏；app：只读 | 既有 | ✅ 已在线上 |
| 择时 | web：红绿切换条；app：只读 | 既有 | ✅ 已在线上 |

## 三、建议分批（每批都能独立验收）

**批 1 —— 走通「验收 1」（最高优先）**：`R-12` + `R-05`
→ 需求验收 1：「空账号的新用户：只导两类导出、**不填任何规则**，能走到『看见第一笔分析』——**全程不需要看文档**」。这一条是整份需求里判定「这插件成立不成立」的那条。

**批 2**：`R-06` 规则三态 + `R-07` 三环（验收 2 / 7：候选每条带据可认下 / 卖出有评价）

**批 3**：`R-02` 断点定位 + `R-03` 人工切笔 + `R-04` 图上买卖点 + `R-08` 就地纠错

**批 4**：`R-09`/`R-10` 自选与案例补齐 + `R-13` 画像页

## 四、纪律（本批交付时必须满足）

1. **交付即交「设计范围对照表」**：§6 逐条标「已交付 / 未交付」+ 证据（文件 / 用例）；**未交付项必须进 `LEDGER.md` 交接清单**——本次缺口之所以一路绿灯，就是因为交接清单里没有这一栏。
2. **不得让功能只在一端且未声明**（§6 原话）——包括「只在后端」。
3. 缺数据不编：取不到的显「—」，**绝不渲染成 0**；没有规则时明说「判不了」（验收 5 原文）。
4. 三端兼容（web / iOS / Android），`flutter analyze` + 对应 widget 测试过；web 构建**必须**走 `sh apps/adai-web/scripts/serve_web.sh <API_BASE_URL> --build-only`（漏传 URL 会静默回落 localhost，2026-10-05 真实 P0），admin 同 `sh apps/adai-admin/scripts/serve_web.sh <API_BASE_URL> --build-only`。

## 五、口径裁决已拍板（2026-10-06 · 已按此落地）

2026-08-23 用户拍板「**移除页头批量导入**」（`apps/adai-web/lib/pages/trading_page.dart:738` 注释）；补 `R-12` 时曾一度按「新需求覆盖旧决定」在页头加回统一入口——**2026-10-06 用户裁决：「移除页头批量导入，归于 tab 专属」**。落法：页头不设导入入口；「一次交文件」并入各 Tab 专属导入对话框——「选择文件（可多选，通达信导出）」选 ≥2 份转统一批量对话框（逐份识别 + 先看计划），选 1 份仍走原文本框路径（预览/编辑 + 基准日）；历史成交多选路径原本即此形态（不动）。
