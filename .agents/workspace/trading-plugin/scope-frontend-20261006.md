---
title: 前端补做批 1 · 范围对照表（R-12 + R-05）
description: 交付完备性机制首跑——设计 §6「三端呈现」逐条对照：本批= R-12 统一导入 + R-05 web 三粒度分析（走通需求验收 1）；其余条目如实标下批去向或已交付证据。开工建表（本文件）→ 收尾核表，收工时 ai-guard-scope.sh 机械核（证据可 grep · 未交付必填去向）
version: 1
created: 2026-10-06
updated: 2026-10-07
status: active
lines: 67
depends-on:
  - design-final-20261006.md
related:
  - frontend-gap-20261006.md
  - LEDGER.md
tags: [workspace, trading, scope]
---

# 前端补做批 1 · 范围对照表

> **用法**：编码段**开工第一动作**即建本表——从设计 §6「三端呈现」逐条抄（不重写、只搬运），填「计划」列；收尾填「状态/证据/去向」列，跑 `bash .agents/mechanism/guards/ai-guard-scope.sh` 核。
> 基线：`.agents/workspace/trading-plugin/design-final-20261006.md`（守卫据此解析 §6 条目）
> **语义**：`状态` = 收尾时刻该条目的**真实交付现状**（不区分是本任务交付还是既有）；`非本任务` = 本条不在本任务范围（既有权或归别的任务）。分批口径 = `frontend-gap-20261006.md` §三。

## 一、条目表

| # | 条目 | 面 | 计划 | 状态 | 证据 | 去向 |
|:--|:--|:--|:--|:--|:--|:--|
| `R-01` | 记录 · web 导入（一次全收） | web | 本批 | 已交付 | `apps/adai-web/lib/pages/trading_page.dart#_openImportDialog,_openBundleImport,_BundleImportDialog`; `apps/adai-web/lib/services/api_service.dart#importTradingBundle`; `apps/adai-web/test/trading_bundle_analysis_test.dart#R-12 统一导入` | — |
| `R-01` | 记录 · web 逐笔编辑 | web | 下批 | — | — | LEDGER 🟥 · 批 3 |
| `R-01` | 记录 · app 手动记一笔 / 一句话 / 截图入账 | app | 下批 | — | — | LEDGER 🟥 · 批 5（app） |
| `R-02` | 对账 · web（差额横幅已上线；断点定位缺） | web | 下批 | — | — | LEDGER 🟥 · 批 3 |
| `R-03` | 一笔 · web 图上 / 列表人工切笔 | web | 下批 | — | — | LEDGER 🟥 · 批 3 |
| `R-04` | 图表 · web（K 线既有；我的买卖点缺） | web | 下批 | — | — | LEDGER 🟥 · 批 3 |
| `R-05` | 分析 · web 三粒度明细 | web | 本批 | 已交付 | `apps/adai-web/lib/pages/trading_page.dart#_AnalysisSection,analysisSymbol`; `apps/adai-web/lib/services/api_service.dart#fetchTradingAnalysis,TradingAnalysisDto`; `apps/adai-web/test/trading_bundle_analysis_test.dart#R-05 三粒度分析` | — |
| `R-05` | 分析 · app 只给结论入口 | app | 下批 | — | — | LEDGER 🟥 · 批 5（app） |
| `R-06` | 规则 · web 自建 / 导入 / 认候选 | web | 下批 | — | — | LEDGER 🟥 · 批 2 |
| `R-07` | 三环 · web 看依据 | web | 下批 | — | — | LEDGER 🟥 · 批 2 |
| `R-07` | 三环 · app 收提醒 | app | 下批 | — | — | LEDGER 🟥 · 批 5（app） |
| `R-08` | 纠错 · web 就地改 | web | 下批 | — | — | LEDGER 🟥 · 批 3 |
| `R-09` `R-10` | 自选留历史 / 案例全功能 · web 补齐 | web | 下批 | — | — | LEDGER 🟥 · 批 4 |
| `R-11` | 提醒 · app 收提醒 | app | 下批 | — | — | LEDGER 🟥 · 批 5（app） |
| `R-12` | 一次交文件 · web（app 不能导入，设计已声明） | web | 本批 | 已交付 | `apps/adai-web/lib/pages/trading_page.dart#选择文件（可多选，通达信导出）,先看计划（预检：只报会做什么，不动数据）`; `apps/adai-web/lib/services/api_service.dart#importTradingBundle,BundleImportReceipt`; `apps/adai-web/test/trading_bundle_analysis_test.dart#先看计划,已导入——可关闭` | — |
| `R-13` | 画像 · web 页面（客观 + 主观） | web | 下批 | — | — | LEDGER 🟥 · 批 4 |
| `资金层` | 资金曲线 + 分周期盈亏 | web | 非本任务 | 已交付 | `apps/adai-web/lib/pages/trading_page.dart#_EquityCurveCard,_pnlPeriods` | — |
| `资金层` | 只读曲线 | app | 非本任务 | 已交付 | `apps/adai-app/lib/pages/profit_calendar_page.dart#EquityCurveDto,getEquityCurve` | — |
| `择时` | 红绿切换条（web 可切 / app 只读） | web/app | 非本任务 | 已交付 | `apps/adai-web/lib/pages/trading_page.dart#_marketStage,_stageButton`; `apps/adai-app/lib/pages/trading_page.dart#_marketStage` | — |
| `行情` | admin 导公共数据 · 行情包（`X-02`；抓取归 L5） | admin | 非本任务 | 已交付 | `apps/adai-admin/lib/pages/system/maintenance_tab.dart#行情数据导入,tdx-import`; `services/adai-core/src/main/java/com/adaiadai/core/interfaces/AdminController.java#importTdxPackage` | — |
| `行情` | admin 导公共数据 · 活跃市值（`X-02`；现状仍是用户手判 · 红线已定 2026-10-05） | admin | 非本任务 | 未交付 | — | LEDGER 🟥 · 本任务遗留 |

- `计划` ∈ {本批, 下批, 不做, 非本任务}；`状态` ∈ {已交付, 未交付, —}（计划=本批时收尾必须填，不许留 —）；
- `证据`＝`` `路径#关键词1,关键词2` ``（多条用 `;` 分隔；守卫核路径存在 + 关键词命中）；走查类写 `case:<说明>`（人工核，守卫豁免）；
- `去向`：未交付/下批必填；`LEDGER`（查 LEDGER.md 有 🟥）/ `REVIEW P?-…`（查编号存在）/ `批 N` / `不做` / `非本任务`。

## 二、验收走查（收尾填 · 人验收〔介入点③〕的前置材料）

| 验收 | 走通？ | 到什么程度 / 证据 | 走不通 → 缺哪条交付物 |
|:--|:--|:--|:--|
| 1 空账号两步导入 → 看见第一笔分析（不看文档） | 走通（web 面 · 测试锁定） | 持仓 Tab「导入持仓」→「选择文件（可多选，通达信导出）」一次选两类导出 → 转统一批量对话框 →「先看计划（预检）」→「导入」→ 逐份回执 → 自动刷新；「分析」Tab 自动出全局。5 例 widget 测试锁链（`trading_bundle_analysis_test.dart`）；真实空账号 + 真实导出端到端留人工验收 | — |
| 2 零规则出带据候选，可认下 / 改 / 自写 | 走不通 | 本批仅「零规则明说判不了」（分析页对照卡）；候选规则面未做 | 缺 `R-06` web（批 2） |
| 3 一笔切分可控 + 改完重算 | 走不通 | 单笔（round）粒度过程可看；人工切笔未做 | 缺 `R-03` web（批 3） |
| 4 图上见我的买卖点 + 数字点得进去 | 半走通 | 「数字点得进去」已交付（出处＝哪几笔 / 哪几天 / note，测试锁定）；「图上买卖点」未做 | 缺 `R-04` web（批 3） |
| 5 缺数据不编（「—」不渲染 0；没规则说判不了） | 走通（web 分析面） | value=null →「—」、hasRules=false →「判不了守没守」，均被测试断言 | — |
| 6 现金自证：余额链连续 + 断点定位 | 半走通 | 资金股份预检回执给出「券商 vs 系统」差额；余额链断点定位未做 | 缺 `R-02` 断点定位（批 3） |
| 7 卖出有评价（我的尺子 + 事实） | 走不通 | 卖出评价面未做 | 缺 `R-07` web（批 2） |
| 8 导错就地改 → 重算 + 重新对账 | 走不通 | 就地纠错未做 | 缺 `R-08` web（批 3） |
| 9 分享 / 导出 / 发模型不含数量金额 | 本批无涉 | 未新增分享 / 导出 / 发模型出口；金额只出现在用户自己的桌面界面 | — |
| 10 全程无「该买该卖建议」 | 走通（本批新增界面） | 新增文案均为陈述式（「判不了」/「命中 N 笔」/「哪几笔」），无「该买 / 该卖 / 建议」字样 | — |
| 11 画像可见（客观 + 主观 + 可能不准声明） | 走不通 | 画像页未做 | 缺 `R-13` web（批 4） |
