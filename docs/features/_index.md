---
title: 功能索引（Feature Index）
description: AdaiOS 全项目功能主轴——一个功能一行（ID/插件/状态/需求出处/实现出处/欠着），状态由脚本对拍；意图卡按需生长在同目录插件文件里，只写意图不写实现
version: 1
created: 2026-10-01
updated: 2026-10-01
status: active
lines: 129
depends-on:
  - ../_index.md
  - ../reference/feature-reference.md
related:
  - kernel.md
  - ../reference/status.md
  - ../reference/trading-features.md
  - ../reference/admin-features.md
  - ../review/REVIEW.md
  - ../review/audits/2026-10-01-feature-index-adversarial.md
  - ../rfc/20261001-feature-index-and-authoring-gate.md
  - ../../.agents/guards/ai-guard-meta.sh
  - ../../.agents/guards/ai-guard-feature.sh
  - ../../.agents/tests/_index.md
tags: [meta, index, feature, 功能主轴]
---

# 功能索引（Feature Index）

> **职责**：AdaiOS 唯一的功能主轴——回答「一共有哪些功能、各自到哪一步、需求出自哪、实现在哪、还欠着什么」。
> **设计**：方案见 [RFC 20261001](../rfc/20261001-feature-index-and-authoring-gate.md)。本层**只放一行 + 链接，不做内容副本**：需求和设计在 `rfc/`、架构在 `architecture/`、实现明细在 `feature-reference.md`、缺陷在 `REVIEW.md`。
> **维护（如实降格，2026-10-01 对抗审查 A2）**：**状态列仍然由人填**——`ai-guard-feature.sh` 只做「枚举合法 + 一条关键词粗对拍（shipped 不得配『待建/未做』）」，**不是**「状态与证据一致」的真对拍（那需要每行带可机器验证的证据字段，尚未做）。**需求出处只做存在性校验，不做相关性校验**（「链接可达但内容无关」机器抓不到，靠审查官抽检——本表已有 2 行被抽检出填错并修正）。意图卡按需生长（见下）。

## 一、状态枚举（唯一写法）

`idea`（只有想法）→ `rfc`（方案 draft/approved）→ `designed`（设计已定）→ `building`（开发中）→ `shipped`（已上线）→ `retired`（已退役，保留行不删除）。

> 历史 RFC 的 `status` 实测有 9 种以上写法（implemented/approved/draft/completed/active/accepted…）。本表统一用上面 6 个值；RFC 侧新文件强制、存量渐进（见 RFC §3.2 F5）。

## 二、功能清单

**列说明**：`需求出处` = RFC 文件链接；无 RFC 的直接改动批次写明批次（记 `change-log.md`）；标 ⚠️ 的表示出处本身有问题。`实现出处` = `feature-reference.md` 章节或专项功能手册。`欠着` = `REVIEW.md` 编号（跑 `bash .agents/guards/ai-guard-unfixed.sh` 可复算当前未修项）。

### Kernel（内核，无插件门控）

| ID | 功能 | 状态 | 需求出处 | 实现出处 | 欠着 |
|:---|:-----|:----:|:---------|:---------|:-----|
| `feed` | 主页 Feed 流（双世界）| shipped | [20260722-dual-world](../rfc/20260722-dual-world.md) | feature-ref §1 | — |
| `record` | 记录提交与意图分流（log/ask）| shipped | 2026-09-30 D4 路由口径批（change-log，无 RFC）| feature-ref §2 | — |
| `ask` | 问答会话（对话模式）| shipped | [20260929-conversation-context-engineering](../rfc/20260929-conversation-context-engineering.md) | feature-ref §3 | P2-对话1 |
| `card` | FeedCard 卡片组件 | shipped | — | feature-ref §4 | — |
| `brief` | 简报 | shipped | [20260923-rhythm-and-memory-temporality](../rfc/20260923-rhythm-and-memory-temporality.md) | feature-ref §5 | — |
| `timeline` | 时间线 | shipped | [20260722-features-memory-timeline-search](../rfc/20260722-features-memory-timeline-search.md) | feature-ref §6 | — |
| `memory` | 记忆 | shipped | [20260801-memory-system-evolution](../rfc/20260801-memory-system-evolution.md) | feature-ref §7 | — |
| `search` | 搜索 | shipped | [20260722-features-memory-timeline-search](../rfc/20260722-features-memory-timeline-search.md) | feature-ref §11 | — |
| `identity` | 身份资料 | shipped | [20260722-identity-page](../rfc/20260722-identity-page.md) | feature-ref §12 | — |
| `tag` | 标签 | shipped | [20260723-tagcloud-gesture](../rfc/20260723-tagcloud-gesture.md) | feature-ref §13 | — |
| `todo` | 待办 | shipped | [20260917-todo-kernel-retire-project-plugin](../rfc/20260917-todo-kernel-retire-project-plugin.md) | feature-ref §10 | — |
| `rhythm` | 节律（周期习惯）| shipped | [20260923-rhythm-and-memory-temporality](../rfc/20260923-rhythm-and-memory-temporality.md) | feature-ref §10b | — |
| `launcher` | Launcher 导航 | shipped | [20260723-launcher-polish](../rfc/20260723-launcher-polish.md) | feature-ref §8 | — |
| `retry` | 定时补完服务 | shipped | — | feature-ref §14 | — |

> **注**：`rhythm` 那条「概览卡天天提醒」在 REVIEW 里以「2026-09-23 用户原话」登记（**无编号**），故本表欠着列写 `—`；要查它直接在 REVIEW.md 搜该日期短语。

### trading（交易插件）

| ID | 功能 | 状态 | 需求出处 | 实现出处 | 欠着 |
|:---|:-----|:----:|:---------|:---------|:-----|
| `trade.ledger` | 账本：导入 / 流水 / 对账 | shipped | [20260912-trading-ledger-integrity](../rfc/20260912-trading-ledger-integrity.md) | trading-features §一 | P2-交易66、P2-交易73 |
| `trade.holdings` | 持仓与账户卡 | shipped | — | trading-features §一 | P2-交易59 |
| `trade.decision` | 决策时点提醒 + 四要素铁证 | shipped | [20260922-trading-decision-copilot](../rfc/20260922-trading-decision-copilot.md) | trading-features §二 | — |
| `trade.alert` | 行情异动推送 | shipped | [20260923-market-data-resilience](../rfc/20260923-market-data-resilience.md) · [20260928-market-source-consolidation](../rfc/20260928-market-source-consolidation.md) | trading-features §二 | — |
| `trade.equity` | 资金曲线与周期盈亏 | shipped | —（2026-09-04 晚间自主批 IV 直接实施，无 RFC）| trading-features §一 | — |
| `trade.case` | 案例库与三维打分 | shipped | [20260830-trading-perfect-case-library](../rfc/20260830-trading-perfect-case-library.md) | trading-features §十 | S7 |
| `trade.cognition` | 认知 / 画像（第五层）| building | [20260905-trading-cognition-layer](../rfc/20260905-trading-cognition-layer.md) | trading-features §一（端点）· §三（**认知层整体待建**）| P2-认知2、P2-认知3 |
| `trade.app-form` | App 端交易形态（重做中）| rfc | [20260924-trading-app-form](../rfc/20260924-trading-app-form.md)（draft）| trading-features §五 | — |

### learn（学习插件）

| ID | 功能 | 状态 | 需求出处 | 实现出处 | 欠着 |
|:---|:-----|:----:|:---------|:---------|:-----|
| `learn.digest` | 学习沉淀 / 整理 | shipped | [20260912-learn-product-digest](../rfc/20260912-learn-product-digest.md) | feature-ref §17 | P2-learn23、P2-learn33、P2-learn34 |
| `learn.review` | 复习流转 | shipped | [20260917-learn-representation](../rfc/20260917-learn-representation.md) | feature-ref §17 | — |
| `learn.share` | 分享追踪 | shipped | [20260914-ios-share-extension](../rfc/20260914-ios-share-extension.md) | feature-ref §17（进度区）· §19（扩展侧）| P2-分享6 |

### platform（平台能力）

| ID | 功能 | 状态 | 需求出处 | 实现出处 | 欠着 |
|:---|:-----|:----:|:---------|:---------|:-----|
| `push` | 推送渠道与设备登记 | shipped | ⚠️ **无 RFC 文件**——2026-09-13 批直接实施；全仓库 **6 个文件 / 12 处**引用编号「20260913」，但 `docs/rfc/` 里没有它（2026-10-01 核实）| feature-ref §18 | P2-APNs3、P2-APNs4 |
| `entry` | 外部入口（Siri / 快捷指令 / `adai://`）| shipped | ⚠️ **无 RFC 文件**（同上，同批 2026-09-13）| feature-ref §19 | — |
| `share-ext` | iOS 分享扩展 | shipped | [20260914-ios-share-extension](../rfc/20260914-ios-share-extension.md) · [20260915-share-extension-credentials](../rfc/20260915-share-extension-credentials.md) | feature-ref §19（`ios/ShareExtension/` 在 §19 内，无独立章节）| P2-分享6 |
| `media` | 多模态与截图入账 | shipped | [20260802-multimodal-image-glm](../rfc/20260802-multimodal-image-glm.md) · [20260815-media-event-unification](../rfc/20260815-media-event-unification.md) | feature-ref §15 | — |
| `account` | 多账号与治理 | shipped | [20260802-multi-account-prep](../rfc/20260802-multi-account-prep.md) | feature-ref §15 | — |
| `plugin-model` | Domain = 插件模型 | shipped | [20260814-domain-plugin-model](../rfc/20260814-domain-plugin-model.md) · [20260816-framework-plus-plugin-model](../rfc/20260816-framework-plus-plugin-model.md) | feature-ref §16 | — |

### admin（管理后台）

| ID | 功能 | 状态 | 需求出处 | 实现出处 | 欠着 |
|:---|:-----|:----:|:---------|:---------|:-----|
| `admin.auth` | 登录与会话 | shipped | [20260802-adai-admin](../rfc/20260802-adai-admin.md) · [20260914-login-credential-experience](../rfc/20260914-login-credential-experience.md) | admin-features §一 | — |
| `admin.accounts` | 账号管理与插件开关 | shipped | [20260802-adai-admin](../rfc/20260802-adai-admin.md) | admin-features §三 | — |
| `admin.data` | 数据治理（只读）| shipped | [20260802-adai-admin](../rfc/20260802-adai-admin.md) | admin-features §四 | — |
| `admin.knowledge` | 知识治理 | shipped | [20260802-adai-admin](../rfc/20260802-adai-admin.md) | admin-features §六 | — |

### 产品口径（跨功能，待拍板）

| ID | 功能 | 状态 | 需求出处 | 实现出处 | 欠着 |
|:---|:-----|:----:|:---------|:---------|:-----|
| `product.positioning` | 产品三口径（显式喜欢 / 工具还是伙伴 / 开源形态）| idea | — | — | P2-产品1 |
| `product.proactivity` | 连续性 / 主动性（阿呆自己敲门）| idea | — | — | S-12 |

## 三、意图卡（按需生长）

**规则**：只有被用户提出修改、或本批次要动的功能才补卡；每张 ≤12 行（**口径：含 `##` 标题行、不含空行**）、只 4 项（干什么用 / 不做什么 / 选型理由 / 验收标准）。**卡内禁写实现细节**（字段名 / 方法名 / 端点 / 路径 / 请求响应）——实现一律链接出去。

**卡标题格式（守卫靠它认卡，写歪会被 F10 拦下）**：`` ## `ID` · 名称 ``——**标题里必须含反引号包裹的 ID**，否则该段落不被当卡、F7/F8 对它静默失效。

| 插件 | 卡文件 | 已生长的卡 |
|:-----|:-------|:-----------|
| kernel | [kernel.md](kernel.md) | `record` |
| trading | 待生长 | — |
| learn | 待生长 | — |
| platform | 待生长 | — |
| admin | 待生长 | — |

## 四、待办（本层自身）

- [x] **批 2**（2026-10-01 完成）：新增守卫脚本 `ai-guard-feature.sh`（F0 表非空防假绿 / F1 字段齐 / F2 链接可达（剥锚点）/ F3 ⚠️需写缺因 / F4 状态枚举 + 关键词粗对拍 / F5 新 RFC status 枚举（缺 date 也强制）/ F6 欠着编号存在（词边界）/ F7 卡内无实现细节 / F8 卡 ≤12 行 / F9 卡文件以链接登记 / F10 防「格式写歪 → 检查静默失效」）+ 已挂 pre-commit「2b」（带未就绪防御）；**反例回归 `tests/guard-feature-fixture.py` 18 条 FAIL 全触发**。⚠️ **与方案的偏差见 RFC §十**（F3/F6 改写、F4 降格、A3 防御等 9 条）。
- [x] **批 3**（2026-10-01 完成）：编写侧判据前置（`workflow/develop.md` 入口条：清单编写/审查双用 + 对抗闭环限三处 ≤2 轮 + 无外部信号不派官）+ **对抗官独立审查 1 轮**（P1×3 / P2×6 / P3×8，逐条处置见 `docs/review/audits/2026-10-01-feature-index-adversarial.md`）。
- [x] **幽灵引用已处置（2026-10-01）**：`push` / `entry` 的需求出处在本表如实标「无 RFC 文件」；**现行文档已加注**——`docs/architecture/api-spec.md` §19 与 `docs/reference/feature-reference.md` §18/§19 的标题注明「该编号无实体文件，2026-10-01 核实」；**历史记录（change-log / status / REVIEW / deployment）按原样保留**（历史如实，不篡改）。**遗留**：若要补一份真正的决策记录（凭 change-log + 实现倒推），仍需用户拍板——**AI 不编造历史**。
