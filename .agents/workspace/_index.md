---
title: workspace/ 目录索引
description: .agents/workspace/ 的清单与过期判断——**按「分支任务」组织**：一个分支一个目录（需求 / 设计 / 审核 / 决策都在它名下，需要才有）；模板在 _templates/、跨任务的在 _meta/
version: 2
created: 2026-10-04
updated: 2026-10-09
status: active
lines: 203
depends-on: []
related: [./_directory.md]
tags: [meta, index, workspace]
---

# workspace/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据）

## 子目录（3）

| 子目录 | 装什么 | 生命周期 |
|:--|:--|:--|
| `_templates/` | 模板（需求 / 设计 / 审核 / 账本） | 常驻 |
| `_meta/` | **跨任务**的：流程问题 / 规范草案 | 交 ① 线后清 |
| `trading-plugin/` | **分支任务目录**（= 分支名 `feat/trading-plugin`）——一个任务的全部产物 | **合并后整目录归档并删除** |

> **规则**：**一个分支 = 一个目录 = 一个任务**（主键唯一 ⇒ **零冲突**）；目录里**需要什么才有什么**（轻活就一个 `LEDGER.md`）。

## 文件清单（164 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `_meta/dispatch-s2-handover-20261006.md` | 派给文档编写者的任务书——产出一份可搬进 rfc/ 的定位扩容 RFC 草稿 + 一份 roadmap 三条改动… | active |
| `_meta/dispatch-s2-revise-20261006.md` | 按人 2026-10-06 的口头修正，就地改 RFC 草稿与 roadmap 提案——四处（措辞升级 / 分享… | active |
| `_meta/dispatch-v3-product-20261006.md` | 单发派给一个独立子代理；材料全部前置。同时验证「fork_turns=none + 文件指针」能否修复任务书投递… | active |
| `_meta/dispatch-v4-backend-review-20261006.md` | 派给 code-backend-reviewer 的任务书——只判上轮 2 战略 + 6 P1 是否闭环 + 是… | active |
| `_meta/dispatch-v4-process-20261006.md` | 派给 process-reviewer 的任务书——只记本轮流程事实（走了哪一档 / 时间 / 空转 / 代笔 … | active |
| `_meta/dispatch-v4-product-review-20261006.md` | 派给 docs-product-reviewer 的任务书——只判上轮 1 战略 + 2 P1 + 3 点是否闭… | active |
| `_meta/dispatch-v4-writer-20261006.md` | 派给设计文档编写者的任务书——产出 design-final 合并正文，收掉 4 类阻塞项（材料全前置）；单号 … | active |
| `_meta/dispatch-v5-writer-fix-20261006.md` | 派给设计文档编写者的任务书——就地修订 design-final，收掉后端 2 条 + 产品 1 条新引 P1，… | active |
| `_meta/inbox-receipt.md` | 子代理在零消息下自行读到 inbox.md 并执行——证明 AGENTS.md 规则 0c 通道可用 | active |
| `_meta/inbox.md` | 消息通道丢载荷时的兜底派单通道；任何 AI 开工先看本文件（AGENTS.md 规则 0c） | active |
| `_meta/process-issues-20261006.md` | 本次会话实跑「需求 → 设计」全流程时实测发现的 12 条流程问题（主链本身 5 · 契约与守卫 3 · 子代理… | active |
| `_meta/process-issues-20261008.md` | 本轮（UI/UX 体验重构 + 7 批实施 + 两轮走查 + 规格补齐）实测的流程问题——事实 → 问题 → 改… | active |
| `_meta/process-log-20261006-v4.md` | 流程官（QA）对本轮（2026-10-06 · trading-plugin 设计 final v4 轮）的事实… | active |
| `_meta/proposal-scope-completeness-20261006.md` | P1-交易101（设计 §6 前端整体未交付且未声明）的机制性修复方案——病因＝「设计→编码」接缝无人站岗（裁剪… | active |
| `_meta/roadmap-diff-trading-20261006.md` | product-roadmap.md 的三条改动提案（现状原文 → 改后原文 + 理由），与 RFC 草稿同口径… | draft |
| `_templates/design.md` | 设计文档模板——本轮回应 · 设计 · 取舍 · 未决 · 自评风险；每轮一份、不覆盖；**一轮只收敛一类**（… | active |
| `_templates/dispatch.md` | 派审核官 / 编写者时的 prompt 骨架——照抄改；含回执 · 材料前置 · 先落文件 · 3 分钟闸门（协… | active |
| `_templates/ledger.md` | 分支账本模板——本分支的目标 · 进度 · 风险 · 待交接；放在 .agents/workspace/<分支名… | active |
| `_templates/requirement.md` | 需求文稿模板——问题动机 · 业务规则 · 范围 · 验收标准 · 未决 · 定稿记录；定稿由人拍板；**分支上… | active |
| `_templates/review-design.md` | 审核文件模板（设计 / 需求共用）：审核对象 · 结论 · 问题清单（P0–P3）· 收敛判定；每轮一份，与稿成… | active |
| `_templates/scope.md` | 编码段「本批范围声明 → 交付声明」的模板——开工从设计 §6 逐条抄、填「计划」列（无表不开工）；收尾填「状态… | active |
| `trading-plugin/LEDGER.md` | ② 交易线的分支账本——本批目标 + 并行作业纪律 + 待交接；一个分支一个目录（feat/trading-pl… | active |
| `trading-plugin/audit-shots-20261007-v3/01-holding.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/02-cleared.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/03-account.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/04-analysis.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/05-rules.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/06-cases.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/07-plan.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/08-analysis-filled.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/09-narrow.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/10-narrow-drawer.png` | — | active |
| `trading-plugin/audit-shots-20261007-v3/14-watchlist.png` | — | active |
| `trading-plugin/audit-shots-20261007/10-holding.png` | — | active |
| `trading-plugin/audit-shots-20261007/11-cleared.png` | — | active |
| `trading-plugin/audit-shots-20261007/12-account.png` | — | active |
| `trading-plugin/audit-shots-20261007/13-analysis.png` | — | active |
| `trading-plugin/audit-shots-20261007/15-cases.png` | — | active |
| `trading-plugin/audit-shots-20261007/16-plan.png` | — | active |
| `trading-plugin/audit-shots-20261007/17-import.png` | — | active |
| `trading-plugin/audit-shots-20261007/20-kline.png` | — | active |
| `trading-plugin/audit-shots-20261008/01-holding.png` | — | active |
| `trading-plugin/audit-shots-20261008/02-selfselect.png` | — | active |
| `trading-plugin/audit-shots-20261008/03-cleared.png` | — | active |
| `trading-plugin/audit-shots-20261008/04-funds.png` | — | active |
| `trading-plugin/audit-shots-20261008/05-rules.png` | — | active |
| `trading-plugin/audit-shots-20261008/06-analysis.png` | — | active |
| `trading-plugin/audit-shots-20261008/07-cases.png` | — | active |
| `trading-plugin/audit-shots-20261008/08-plan.png` | — | active |
| `trading-plugin/audit-shots-20261008/09-kline.png` | — | active |
| `trading-plugin/audit-shots-20261008/10-narrow-holding.png` | — | active |
| `trading-plugin/audit-shots-20261008/12-cases-bottom.png` | — | active |
| `trading-plugin/audit-shots-20261008/p10-holding.png` | — | active |
| `trading-plugin/audit-shots-20261008/p10-kline.png` | — | active |
| `trading-plugin/audit-shots-20261008/p11-cases.png` | — | active |
| `trading-plugin/audit-shots-20261008/p11-holding.png` | — | active |
| `trading-plugin/audit-shots-20261008/p8-analysis.png` | — | active |
| `trading-plugin/audit-shots-20261008/p8-cases.png` | — | active |
| `trading-plugin/audit-shots-20261008/p9-cases-bottom.png` | — | active |
| `trading-plugin/audit-shots-20261008/p9-cases.png` | — | active |
| `trading-plugin/audit-shots-20261008/p9-holding.png` | — | active |
| `trading-plugin/audit-shots-20261008/v9-a-masked.png` | — | active |
| `trading-plugin/audit-shots-20261008/v9-a-revealed.png` | — | active |
| `trading-plugin/audit-shots-20261008/v9-b-narrow-cleared.png` | — | active |
| `trading-plugin/audit-shots-20261008/v9-c-kline-cleared.png` | — | active |
| `trading-plugin/audit-shots-20261008/v9-c-kline-holding.png` | — | active |
| `trading-plugin/audit-shots-20261008/v9-c-kline-watch.png` | — | active |
| `trading-plugin/audit-shots-20261008/v9-d-1-paste.png` | — | active |
| `trading-plugin/audit-shots-20261008/v9-d-2-instant.png` | — | active |
| `trading-plugin/audit-shots-20261008/v9-e-import-precheck.png` | — | active |
| `trading-plugin/audit-shots-20261009/v10-case-kline.png` | — | active |
| `trading-plugin/audit-shots-20261009/v10-tab-analysis.png` | — | active |
| `trading-plugin/audit-shots-20261009/v10-tab-cases.png` | — | active |
| `trading-plugin/audit-shots-20261009/v10-tab-holding.png` | — | active |
| `trading-plugin/blueprint.md` | 交易插件的概念与流程，按我们逐步讨论的顺序记录——定位 · 规则 · 没有规则怎么办 · 清仓股+历史成交 · … | active |
| `trading-plugin/decisions.md` | 历轮 adai 拍板：设计 v1 的 6 项未决（U1–U6）+ 审核 v1 战略级 2 条（S1/S2）+ v… | active |
| `trading-plugin/design-app-20261009.md` | app 端这一轮的**设计规格**——把已圈选的方向（甲·清单优先）与已出可点稿收成可对照的设计稿：首屏四层 I… | active |
| `trading-plugin/design-push-copy-20261009.md` | **推送文案库 v1**（draft）——6 类节奏推送 + 5 类行情告警的标题/正文/落点，含沉默边界（≤8/日 · 同类同票一次 · 22:00–08:00 静默）与待拍 4 条；供 R-07/R-11 提醒落点批次直接用 | draft |
| `trading-plugin/design-final-20261006.md` | 交易线设计 final 稿——以 v2 正文为底合并 v3 增量与两份 v3 审核的整改，收 4 类阻塞项（§9… | draft |
| `trading-plugin/design-kline-r04-20261007.md` | 把「案例专图」泛化成横切四个地方的通用 K 线——四区（主图+量+MACD+KDJ）、我的买卖点标记、止损线/峰… | active |
| `trading-plugin/design-uiux-20261007.md` | UI/UX 这一轮的**设计规格**——把散在方向稿/批注/决算/实施清单里的已拍板决定收成一份可对照的设计稿：… | active |
| `trading-plugin/design-v1-20261006.md` | 交易插件重做 · 设计稿 v1（设计文档编写者）——本轮回应 · 设计（归属分层 / 六模块 / 四条数据流 /… | draft |
| `trading-plugin/design-v2-20261006.md` | 交易插件重做 · 设计稿 v2（设计文档编写者）——本轮回应 13 条（S1/S2 · P1×3 · P2×5 … | draft |
| `trading-plugin/design-v3-20261006.md` | 设计第 3 轮的增量稿——正文仍看 design-v2；本稿只写"本轮改什么"（战略级 3 · P1×8 已改；… | draft |
| `trading-plugin/diff-decisions-20261007.md` | 对 review-web-uiux-20261007（v2）判定的「实现比设计多出来」的项逐条拉单并给四档处置建… | active |
| `trading-plugin/explore-app-trading-page-20261009.md` | apps/adai-app/lib/pages/trading_page.dart 结构地图——供「首屏四层」重… | active |
| `trading-plugin/frontend-gap-20261006.md` | 编码段只交付后端（67/78 文件），设计 §6「三端呈现」的 web/app 范围 0 实现——补做清单 + … | active |
| `trading-plugin/handoff-20261007.md` | 给下一个 AI 工具的交接：目标 · 已完成（含 commit）· 未完成清单 · 必读约束与坑 · 验证与本地… | active |
| `trading-plugin/handoff-20261008.md` | 给下一个 AI 工具的交接：现状（体验重构 7 批 + 两轮走查完成）· 未完成（批 8 R-10 · 待派设计… | active |
| `trading-plugin/handoff-20261009.md` | 给下一个 AI 工具的交接：现状（体验重构 11 批 + 走查 5 轮 + 2 份独立审核全处理完毕）· 唯一遗… | active |
| `trading-plugin/impl-plan-20261007.md` | 把 A1–A5 批注与 D1–D6 决策转成 7 个可执行批次——每批含范围 / 涉及文件 / 验收口径 / 测… | active |
| `trading-plugin/input.md` | 归位之后剩下的"怎么做"——数据现实与处理 / 去重 / 顺序 / 表头识别 / 送股兜底 / 同日行序陷阱 /… | active |
| `trading-plugin/mockups/README.md` | 2026-10-07 夜做的一批 UI/UX 原型——6 个自包含 HTML（可在浏览器直接打开、可点、可切方向… | active |
| `trading-plugin/mockups/png/A-default.png` | — | active |
| `trading-plugin/mockups/png/A-empty.png` | — | active |
| `trading-plugin/mockups/png/A-revealed.png` | — | active |
| `trading-plugin/mockups/png/B-default.png` | — | active |
| `trading-plugin/mockups/png/B-empty.png` | — | active |
| `trading-plugin/mockups/png/C-default.png` | — | active |
| `trading-plugin/mockups/png/C-empty.png` | — | active |
| `trading-plugin/mockups/png/app-1.png` | — | active |
| `trading-plugin/mockups/png/app-2.png` | — | active |
| `trading-plugin/mockups/png/app-3.png` | — | active |
| `trading-plugin/mockups/png/app-4.png` | — | active |
| `trading-plugin/mockups/png/app-5.png` | — | active |
| `trading-plugin/mockups/png/appA-revealed.png` | — | active |
| `trading-plugin/mockups/png/appA.png` | — | active |
| `trading-plugin/mockups/png/appB-empty.png` | — | active |
| `trading-plugin/mockups/png/appB.png` | — | active |
| `trading-plugin/mockups/png/appC.png` | — | active |
| `trading-plugin/mockups/png/flow-1.png` | — | active |
| `trading-plugin/mockups/png/flow-2.png` | — | active |
| `trading-plugin/mockups/png/flow-3.png` | — | active |
| `trading-plugin/mockups/png/flow-4.png` | — | active |
| `trading-plugin/mockups/png/ref-1-revealed.png` | — | active |
| `trading-plugin/mockups/png/ref-1.png` | — | active |
| `trading-plugin/mockups/png/ref-2-revealed.png` | — | active |
| `trading-plugin/mockups/png/ref-2.png` | — | active |
| `trading-plugin/mockups/png/web-1.png` | — | active |
| `trading-plugin/mockups/png/web-2.png` | — | active |
| `trading-plugin/mockups/png/web-3.png` | — | active |
| `trading-plugin/mockups/png/web-4.png` | — | active |
| `trading-plugin/mockups/png/web-5.png` | — | active |
| `trading-plugin/mockups/png/web-6.png` | — | active |
| `trading-plugin/mockups/png/web-7.png` | — | active |
| `trading-plugin/mockups/png/web-8.png` | — | active |
| `trading-plugin/mockups/trading-app-directions.html` | — | active |
| `trading-plugin/mockups/trading-app-entry-flow.html` | — | active |
| `trading-plugin/mockups/trading-app-refined.html` | — | active |
| `trading-plugin/mockups/trading-app-rest.html` | — | active |
| `trading-plugin/mockups/trading-ui-directions.html` | — | active |
| `trading-plugin/mockups/trading-web-full.html` | — | active |
| `trading-plugin/overview.md` | trading 插件的「一页总览」——定位与整体现状 · 功能全景 · 全部相关 RFC · 未修与待办 · 未… | active |
| `trading-plugin/plan-app-20261009.md` | app 端体验重构的一次性执行方案——web 轮已闭环、app 方向已圈选，本件定「交付什么 · 怎么分段 · … | active |
| `trading-plugin/review-app-design-20261009.md` | **app 设计规格的独立交互层审核**（独立子代理 · D-20261009-02）——结论 **通过（无 P0/P1）**；P2×5（入账位置 / 持仓行双热区 / 去重反馈 / 分张中途失败 / 判断句句式）+ P3×11；主链已在 design-app §七之二 统一口径 | active |
| `trading-plugin/review-app-uiux-20261009.md` | **app 端体验重构走查报告**（累积单文件 + 轮次节）——§1 判定：**构建级 + 登录屏实拍通过**，交易页逐屏实拍因**本机锁屏**（UI 自动化不可用）阻塞顺延；含测试级证据清单与待补清单 | active |
| `trading-plugin/audit-shots-20261009/v11-g3-case-kline.png` | web 轮遗留取证 **G3（案例 K 线入口）** 的实拍——闭掉 `handoff-20261009.md` §三.1 那条遗留；同批为行内入口补 `caseKline:<symbol>` 语义锚点（`apps/adai-web/lib/pages/trading_page.dart`） | active |
| `trading-plugin/audit-shots-app-20261009/app-1-first-screen.png` | app 走查实拍①：交易**首屏四层**（判断句「持仓 2 只 · 1 只破了你的线」在小字点名 · 入账区 · 持仓一行一只 · 自选只读）——金额默认打码态 | active |
| `trading-plugin/audit-shots-app-20261009/app-2-position-expanded.png` | app 走查实拍②：持仓行**点开就地展开**（••••股/成本打码 · 现价与止损明文 · 当日三指标 · 批次 · 这只票阿呆怎么说/批次明细） | active |
| `trading-plugin/audit-shots-app-20261009/app-3-watchlist.png` | app 走查实拍③：**自选只读段**（名称 代码 · 行业 · 信号；端点无行情就不给涨跌） | active |
| `trading-plugin/audit-shots-app-20261009/app-4-push-settings.png` | app 走查实拍④（批 4）：**推送设置页**——12 个开关 + 「默认只在该说的时候说一句」；原能力从主页 Feed 右滑搬到交易页可直达 | active |
| `trading-plugin/audit-shots-app-20261009/app-5-review-history.png` | app 走查实拍⑤（批 4）：**复盘历史页**——日期倒序 + 「看这天的复盘 ›」；把此前**零入口**的能力接上（后端端点与 API 早已存在） | active |
| `trading-plugin/audit-shots-app-20261009/app-6-push-landing.png` | app 走查实拍⑥（批 5）：**提醒落点**——模拟真实「点通知」后落在**有研新材**那一行并就地展开（另只保持收起）；与真实点击同一条深链路由 | active |
| `trading-plugin/requirement-review-20261006-r2.md` | 第一轮问题修复后的复审——12 条判据全部通过，结论：收敛（无 P0/P1），可送人签字；仅剩 P3（一笔生命周… | active |
| `trading-plugin/requirement-review-20261006-r3.md` | A 归位（业务规则并回需求、机制留给设计）之后的复审——12 条判据全过 + 归位核对通过；结论：收敛，可送人签… | active |
| `trading-plugin/requirement-review-20261006.md` | 用「需求文稿审核官（草案）」的 12 条判据，对本分支需求文稿的第一轮审核——结论：不收敛（P1×2 / P2×… | active |
| `trading-plugin/requirement.md` | 交易插件重做的需求（要什么 · 现状无关）——问题动机 · 谁在什么时刻要什么 · 目标与不目标 · 用户要付出… | active |
| `trading-plugin/review-annotations-20261007.md` | adai 在本地实跑页面（localhost:8082）上的逐条圈选批注与归属判定——编号 / 批注原文 / 命… | active |
| `trading-plugin/review-r2-backend-20261006.md` | 只核 r1 → r2 三条改动是否真到位 + 有无连带新引 P0/P1，逐条给 文件:行 证据；判定：三条全部真… | draft |
| `trading-plugin/review-uiux-ixd-20261008.md` | 独立（非主链）交互层审核——只判 design-uiux §三 I-1~I-7 的交互决定（流程完整 · 反馈到… | active |
| `trading-plugin/review-uiux-visual-20261008.md` | 对 design-uiux-20261007.md §二（IA 呈现）与 §四（视觉口径）的**独立视觉层**审… | active |
| `trading-plugin/review-v1-20261006.md` | 对设计稿 v1 的产品架构审查——无 P0；战略级 2 条（L6 反哺闭环被砍无 RFC · 定位扩容未进蓝图）… | active |
| `trading-plugin/review-v2-20261006.md` | 对设计稿 v2 的产品架构审查——无 P0；战略级 1 条（S2 的 RFC/roadmap 仍在分支外）· P… | active |
| `trading-plugin/review-v2-backend-20261006.md` | 对设计稿 v2 的后端视角审查（设计 × 现状代码对拍）——无 P0；战略级 2（关插件闸门清单漏 kernel… | active |
| `trading-plugin/review-v3-backend-20261006.md` | 对设计稿 v3 增量稿的后端收敛性审核——不收敛：S1 关插件闸门扩面仍漏「记忆/上下文注入」与 has-act… | active |
| `trading-plugin/review-v3-product-20261006.md` | 只判 3 点——S2 落点是否闭环 / 增量稿与正文是否自相矛盾 / 数量声明是否属实 | draft |
| `trading-plugin/review-v4-20261006.md` | 产品面收敛性审核——只判上轮战略 S2 + 产品 P1-1 / P1-2 + 三点复核（新引 2 条）是否真闭环… | draft |
| `trading-plugin/review-v4-backend-20261006.md` | 后端面收敛性审核——只判上轮 2 条战略（S1 闸门 / S2 §9 基线）+ 6 条 P1 是否真闭环 + 本… | active |
| `trading-plugin/review-web-uiux-20261007.md` | 以「本地真机渲染 + 原型可点稿逐屏比对」审核 web 端重构——8 屏对照结论、与护栏口径的核对（涨红跌绿/打… | active |
| `trading-plugin/scope-app-20261009.md` | app 端这一轮的交付完备性表——从 design-app §二/§三 与 design-final §6「三端… | active |
| `trading-plugin/scope-frontend-20261006.md` | 交付完备性机制首跑——设计 §6「三端呈现」逐条对照：本批= R-12 统一导入 + R-05 web 三粒度分… | active |
| `trading-plugin/scope-uiux-20261008.md` | 本轮（IA 重构 + 交互/视觉收口 + 7 批实施）的交付完备性表——从设计 §6 逐条抄条目，填计划/状态/… | active |
| `trading-plugin/thinking-log.md` | 本分支 AI 的思考过程落盘——每轮写「看到什么 → 怎么判断 → 为什么这么选 → 放弃了什么」；与产物（re… | active |
| `trading-plugin/uiux-discovery-20261007.md` | 双端 UI/UX 重构的当前方向总稿——背景 · 组织原则 · 已定约束（护栏）· 三问（场景/痛点/第一眼）·… | draft |

## 过期判断

- **合并后整个 `<分支名>/` 归档并删除**（`main` 上残留 = 未收尾）
- `_meta/` 的内容交 ① 线后清空
- **清单须与实际一致**（跑 `bash .agents/mechanism/guards/ai-guard-structure.sh --fix` 刷新）
