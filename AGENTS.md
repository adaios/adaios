---
title: AdaiOS AI 协作入口
description: 任何 AI 工具打开本项目的统一入口——项目定位、协作规则、审查体系导航（工具无关）
version: 1
created: 2026-08-15
updated: 2026-10-06
status: active
lines: 91
depends-on:
  - .agents/README.md
related:
  - ARCHITECTURE.md
  - .agents/direction/VISION.md
  - .agents/direction/product-roadmap.md
tags: [ai, entry]
---

# AdaiOS — AI 协作入口

> 本文件是**任何 AI 工具**进入 AdaiOS 项目的统一入口（与工具无关；Claude/Qoder/DSH 等均读取同一份）。人类侧完整说明见 `docs/README.md`（文档索引），本文件只承载 AI 协作必需的最小导航。

## 项目一句话

AdaiOS 是一套 **Personal AI Operating System**：以 Kernel（Context + Memory + Knowledge）为核心、个人文件（`data/`）为资产、Domain OS（`os/`）为能力边界。不是 CRUD 应用。

## AI 协作规则（必读）

> **语言纪律（先于以下全部规则）**：**用户可见的答复、以及你的思考过程（reasoning / thinking / 内部推理），一律使用中文** —— 不只是结论用中文、推理却用英文。**代码 / 命令 / 文件名 / API / 专有名词保持原文**，正文说明用中文。
> （2026-10-06 用户确立：**跨工具统一**——此前只写在 DSH 的全局指令（`~/.dsh/AGENTS.md`）里，**其它工具读不到**；本文件是所有工具的统一入口，故落在此处。）

0. **开工自举（必做，零人工）**：任何 AI 开始工作前**自动执行** `bash .agents/mechanism/guards/ai-guard-context.sh`，以其输出（状态/未修项/边界/坑/规范/待办/成本提醒）为上下文基线——用户不需要手动跑脚本、不需要回忆任何事（2026-08-18 用户确立）
0b. **跨会话记忆（自动）**：DSH/Claude 等工具会话开始时**自动注入**项目根 `AGENTS.local.md`——上次收尾的状态快照（机器生成勿手改；真相源是 `docs/` 源文件；体积预算见 .agents/toolkit/checklists/ai-cost-checklist.md C7）。**收尾时强制两步，缺一不可**：① `bash .agents/mechanism/guards/ai-guard-context.sh --write-local`（刷 AGENTS.local.md 快照）② `bash .agents/mechanism/guards/ai-guard-cost.sh --record`（今日成本入账）——下次开工自动带上，用户零操作（2026-08-20 确立，2026-08-22 补 cost 强制）
0c. **派单收件箱（消息通道丢载荷时的兜底）**：开工先看 `.agents/workspace/_meta/inbox.md`——**有分配给你的任务就执行，没有就忽略**（任务做完把结果落盘到你自己的交付文件）。**背景**：2026-10-06 实测本环境 `spawn_agent(message)` 与 `followup_task` **均会丢载荷**（子代理只收到 AGENTS.md／技能／环境／协作规则），**而子代理必读本文件**——所以**收件箱是唯一可靠的派单通道**（2026-10-06 确立，详见 `.agents/workspace/_meta/process-issues-20261006.md` 甲层）
1. **必读文档**：先读 `.agents/direction/VISION.md`（理念）→ `ARCHITECTURE.md`（架构红线）→ `.agents/direction/product-roadmap.md`（唯一蓝图）→ `.agents/README.md`（本会话协作标准）
2. **工作焦点分离**：子项目有独立 AGENTS.md（分层应用、就近原则——`services/adai-core`、`apps/*`、`os/*`）；在哪个目录工作只看哪个领域
3. **入口统一，后台分流**：`POST /api/v1/records` 是唯一输入入口
4. **第一原则「无第三视角」**：所有用户可见展示必须是「我和阿呆」的自然对话，不得出现系统视角标签（问：/答：/图片记录：/【备注】）
5. **File First**：`os/` 与 `data/` 知识以文件为准，数据库为查询存在；`data/` 隐私受 gitignore 保护，不提交
6. **审查只报告不直接修**（除 P0 数据丢失可与用户确认后修）
7. **讨论与实施分离**（2026-08-16 确立，2026-08-18 明确范围）：讨论方向/方案/数据口径时**只聊不动手**——用户明确说「开工 / 做 / 改」后才改**代码与 `data/` 数据资产**；未指示前不写代码。**本规则只约束代码/数据修改**；AI 工程建设层面文档（`.agents/` 协作规范与流程、AGENTS.md 等）属工程自身持续维护，可直接修订。违背此条即越界（已发生一次：账户总盈亏口径讨论中擅自改代码）
8. **触发词「每日巡检」（2026-09-16 用户确立：「以后我说每日巡检，你触发就好」）**：用户说出「**每日巡检**」四个字，AI **立即执行** `bash .agents/mechanism/scripts/task-cadence.sh daily`——它从**上次巡检覆盖到的日期**自动补看到今天（游标 `.agents/records/state/cadence.json`，机制见 `.agents/rules/process/cadence.md`），不再永远重复同一个窗口；然后把结果**用人话讲给他听**——只讲三条：**① 用户之声**（他最近真问了什么、在骂什么）· **② 有没有新异常**（新类目的告警 / ERROR / 服务非 active）· **③ 心跳趋势**（他还在不在用）。**不堆原始日志**（扫描器噪音与老朋友的东财 Kline 已由脚本自动折叠）；巡检中发现的**产品反馈 / 新缺陷 → 提议登记，不擅自改代码**（受规则 7 约束）。用户**不必自己敲命令**——「每日巡检」就是他触发这条每日流程的唯一入口
9. **触发词「收工」（2026-09-26 用户确立：「收尾/收工，提交 diff」；**2026-10-03 更新：收工默认含提交**）**：用户说「**收工**」（或「收尾」），AI 执行 `bash .agents/mechanism/scripts/task-cadence.sh ship`——一条命令出**本批 diff**（自上次收工基线以来已提交的 commit + 工作区未提交的清单与统计）、刷开工快照、成本入账，并把收工基线推到当前 commit；**随后按本批显式路径提交**（2026-10-03 用户拍板「提交，且以后收工默认连提交」——提交是收工的最后一步，不再需要用户额外说一次）；**提交后把收工基线补推到新 commit**（`bash .agents/mechanism/scripts/task-cadence.sh mark ship`）——否则下次收工会把本批再算一遍。**提交纪律不变**：仓库可能有并发会话（`.agents/rules/process/ship.md §7` 的真实事故），**严禁 `git add -A`**，一律**显式路径** + `ADAI_BATCH_PATHS` 范围守卫；**审查判定有 P0/P1 时先修再提交**。**收工止步于提交——不自动 push、不自动部署**（外向动作仍须用户点头，部署走 `code-deploy-gate.sh`，原则 B8）
10. **触发词「每周」「待办」（2026-09-26 确立）**：「**每周**」→ `bash .agents/mechanism/scripts/task-cadence.sh weekly`（每周审查 W1–W6 + 本周人肉清单 + 到期红线）；「**待办**」→ `bash .agents/mechanism/scripts/task-cadence.sh todo`（REVIEW 未修项一眼看全）。**开工第一眼**（或想确认当前节奏）跑 `bash .agents/mechanism/scripts/task-cadence.sh`（无参数 = 状态总览：上次巡检/上次收工/上次周审 + 欠账提醒）。五条默契的总表与游标机制见 `.agents/rules/process/cadence.md`
11. **触发词「发布」「发版」（2026-09-26 用户确立：「不主动部署，通过部署动作一键触发，确定是否更新发布」）**：用户说「**发布**」「**发版**」，或问「**要不要发**」「**该发什么**」，AI 执行 `bash .agents/mechanism/scripts/task-cadence.sh release`——**只判定**：现在欠着什么没发、要发哪几端（后端 / Web 桌面端 / 管理后台 / iOS App），附生产↔本地 commit 对照与未推送数。**AI 绝不主动部署、绝不主动 push**（原则 B8）：判定结果讲给用户后，**只有用户点头**才走 `code-deploy-gate.sh`（门禁 + 部署后 smoke）或对应端的构建 / 发布命令；未获指示时只报告、不动作
12. **触发词「体检」（2026-10-06 用户确立：「我需要一个体检角色/skill」）**：用户说「**体检**」，AI 走两步——① **机械层**：`bash .agents/mechanism/guards/ai-guard-health.sh --full`（六维总检 + 健康分；机械结论**照抄不解读**）；② **叙事层**：派 **`ai-context-health-reviewer`**（体系体检官）按 `toolkit/checklists/ai-context-health-reviewer.md` 的 **H1–H8** 逐条查——**结构断裂 · 跨目录双源 · 滞后 · 文档与行为不一致 · 缩写路径死胡同 · 总纲可执行性**（**全是守卫覆盖不到的**）。报告落 `docs/records/audits/<日期>-ai-context-health.md`，**只讲结论与待修，不堆原始输出**。**只报告不改**（B7）：修复方案写清落点，用户点头再动。**何时该体检**：结构类改动之后（顶层级重构 / 加删角色 / 批量改名）· 里程碑前 · 「每周」时按需

## 审查体系（.agents/）

> **本表是操作导航**（什么时候跑哪个）；**体系里逐条资产的完整清单与规模**见 `rules/assets/ai-context-engineering.md` **§二**（2026-10-06 体检 **H4-2** 收口）。

| 命令/操作 | 文件 | 说明 |
|:---------|:-----|:-----|
| **审核驱动主链** | `.agents/rules/process/review-driven.md` | **需求 → 设计 → 编码**：文档先行 + **双角色真对打**（编写者 / 审核者各为独立 subagent）多轮交叉；定了需求文稿后人只在 **4 个点**介入（需求定稿 · 设计取舍 · 验收 · 发布）；**≤3 轮不收敛强制升级给人**；在制品在 `.agents/workspace/` |
| 全维度走查 | `.agents/rules/process/audit.md` | 8 客观官 + 1 对抗官独立并行全量走查 + 交叉印证 |
| 增量深审 | `.agents/rules/process/review.md` | 按改动派对应审查官 |
| 收尾闭环 | `.agents/rules/process/ship.md` | /ship：测试→契约→登记→ai-guard-meta 门禁→提交 |
| **审查角色（16 个）** | `.agents/toolkit/roles/` | **分三类**（全景与关系见 `process/review-driven.md` **§0**）：**产作者 1**（`docs-design-writer`）· **审核者 14**（需求评审 `docs-requirement-reviewer` ＋ 域客观官 8：`docs-product` / `code-backend` / `code-frontend` / `ux-interaction` / `ux-visual` / `docs-contract` / `data-knowledge` / `ai-context` ＋ 对抗官 `ai-adversarial-reviewer`〔deep 默认附加〕＋ 外部视角 3 ＋ **体系体检 1**）· **流程官 1**（`process-reviewer`，管过程不管产物）。**角色为扁平 `<name>.md`**（subagent 真相源，五段结构写在文件内；刻意不目录化），只有技能用官方目录布局 `<name>/SKILL.md`|
| **外部视角审查** | `.agents/toolkit/roles/`（`ux-stranger-reviewer` / `ux-social-reviewer` / `ux-support-reviewer`）| **面向身边人 / 新用户前必跑**：**陌生人官**（首次使用，**禁读源码**）+ **社会性官**（递出去那一刻）+ **支持台官**（他一定会问的问题）——补内部 8 官「读代码 → 结构上永远知道按钮在哪」的盲区 |
| 建设技能 | `.agents/toolkit/skills/<name>/SKILL.md` | code-api-writer / code-domain-writer / task-ship / data-learn-writer 四技能：建设与收尾流程封装为 SKILL.md，加载即执行（官方目录布局；工具侧靠 `.agents/mechanism/scripts/ai-link-skills.sh` 软链出口） |
| 技能包规范 | `.agents/rules/assets/skills-spec.md` | SKILL.md 技能包标准：name + frontmatter 10 字段融合、五段结构、新增流程 |
| 架构红线 | `ARCHITECTURE.md` | 技术栈/五层架构/分层依赖/数据流/红线清单，AI 进项目直读 |
| 检查清单 | `.agents/toolkit/checklists/` | 逐条可执行（人也能用）：**部分一一对应**：16 个角色中 14 个有同名清单；另 3 份为专项清单（`ai-guard-checklist` · `ai-cost-checklist` · `code-perf-reviewer`） |
| 元数据规范 | `.agents/frontmatter-spec.md` | 文档 frontmatter 契约（图谱/治理/归档）|
| **健康总检（一条命令）** | `.agents/mechanism/guards/ai-guard-health.sh` | **「整个 `.agents/` 健不健康」的总入口**：六维体检（① 结构 · ② 元数据 · ③ 命名 · ④ 体积 · ⑤ 契约 · ⑥ 新鲜度）+ 健康分 + 子目录概览；① ② 直接调 structure/meta（**不重写已有判据**），③④⑤⑥ 是它们没有的内容规范层。`--json` 喂 AI · `--full` 看提示级明细 · `--fix` 先修再检（2026-10-04 用户「我需要一个脚本维持整个 `.agents/` 的健康」）|
| 元治理自检 | `.agents/mechanism/guards/ai-guard-meta.sh` | 一条命令：frontmatter 图谱断链/lines 漂移/孤儿（`--fix` 回写）|
| **技能包质量** | `.agents/mechanism/guards/ai-guard-skills.sh` | 官方 Agent Skills 规范硬约束：S3 `name` 字符集/长度/与目录名一致 · S4 `description` 长度 · S5 五段结构 · S7 偏离在案（git pre-commit 自动触发）|
| 文档自动对齐 | `.agents/mechanism/guards/ai-guard-align.sh` | 代码↔文档内容对齐：端点↔api-spec / 测试数↔status.md（git pre-commit 自动触发）|
| **功能索引自检** | `.agents/mechanism/guards/ai-guard-feature.sh` | **功能主轴**（`.agents/knowledge/features/`）是否真实：索引行字段/链接/状态枚举 · 欠着编号存在 · 卡内无实现细节 · 卡 ≤12 行 · 卡文件非孤儿（git pre-commit 自动触发）；规格见 RFC 20261001 |
| 任务上下文 | `.agents/mechanism/guards/ai-guard-context.sh` | 开工前生成上下文清单（状态/未修项/边界/坑/规范/待办）；`--write-local` 收尾刷 AGENTS.local.md 快照（DSH 自动注入）。⚠️ **该脚本没有 `--topic` 参数**（曾误标「可按主题过滤」，2026-10-06 实测修正）；**输出很大**，会话开头别跑全量——读 `AGENTS.local.md` 等价 |
| 沉淀检查 | `.agents/mechanism/guards/ai-guard-sediment.sh` | ship 时检查沉淀/出表/登记（S1 坑/ADR、S2 REVIEW 出表、S3 change-log）|
| 部署门禁 | `.agents/mechanism/scripts/code-deploy-gate.sh` | 部署前强制 review+guard，部署后自动 smoke（最硬闸门）；同时把「本次应更新哪几端」写进生产 `DEPLOYED` |
| **发版体检（发布前随时问）** | `.agents/mechanism/guards/ai-guard-release.sh` | **发布前**一条命令答「现在欠着什么没发」：生产当前 commit/上批清单/待 push 数 + **逐端判定**（后端 · Web 桌面端 · 管理后台 · **iOS App**）要发还是不用发 + 下一步命令（jar+code-deploy-gate / flutter build web+tar / TestFlight 构建号 N→N+1）；`--json` 可喂 AI。路径映射唯一真相源 `.agents/mechanism/scripts/lib/release-units.sh`（code-deploy-gate 共用）|
| **域视图 / 项目全景** | `.agents/mechanism/scripts/ai-domain-view.py` | **按领域取数**（补「按性质存」之外的一层）：`domain trading` 一条命令答「该域现状 / 未修项（带编号）/ RFC 与设计 / 关键文档」；`overview` 答「功能分布 / 规模 / 里程碑 / 未修 / 在制品」；无参数 = 全景 + 域总览。**纯聚合视图，现算不落盘**（落盘＝第 7 个数据源）。`--json` 可喂 AI（2026-10-04 用户压力测试暴露「按领域取」缺口）|
| **协作默契（节奏总入口）** | `.agents/mechanism/scripts/task-cadence.sh` + `process/cadence.md` | **五条默契的唯一入口**（规则 8–11）：每日巡检（从上次覆盖日补看到今天）/ 收工（本批 diff + 刷快照 + 成本入账）/ **发布（只判定、不部署）** / 每周 / 待办；游标 `.agents/records/state/cadence.json`。无参数 = 状态总览（巡检·收工·发版·周审 + 欠账 + 到期红线 + 定时任务健康）；另 `check`（交付门禁一键）/ `cost`（成本）|
| **生产日报（每日）** | `.agents/mechanism/guards/ai-guard-prod.sh` | 用户说「**每日巡检**」即触发（规则 8）。**生产日志 + 真实对话卡片**一条命令看全：服务/ERROR/告警人话/公网用量（4xx·5xx 自动分「扫描器/探针/设计语义/★待关注」）/用户之声/心跳；C0 心跳发现今日有新记录也会提示跑它 |
| 每周审查 | `.agents/mechanism/scripts/task-weekly-audit.sh` | cron 每周自动审查（守护/结构/对齐/失真/未修项，防休眠）|
| 成本监控 | `.agents/mechanism/guards/ai-guard-cost.sh` | 读 DSH 会话按天/会话算钱；收工前 `--record`，开工看 `ai-guard-context.sh` C6.5 |
| 成本纪律 | `.agents/toolkit/checklists/ai-cost-checklist.md` | 烧钱动作清单 + 省钱原则（错峰/断会话/控输出/降频/用对模型/盯账）|

## 状态真相源

- 测试数/端点数/运行环境：`.agents/knowledge/reference/status.md`
- 未修项：`.agents/records/REVIEW.md`
- 批次历史：`.agents/records/change-log.md`
- 发布：`docs/records/`（Release Notes 存档）、`.agents/direction/product-roadmap.md`（唯一蓝图）

## 工具接入

本文件与 `.agents/` 是**项目内唯一标准**；工具侧入口（如某 IDE 的 AI 插件指向本文件）配置在工具自己的设置里，不在本项目。换工具零迁移。

**AI 资产的布局、多工具出口与维护规则**见 [`.agents/rules/assets/ai-context-layer-spec.md`](.agents/rules/assets/ai-context-layer-spec.md)（项目级 AI 上下文中间层规范）——加工具、加技能、换机重建都按它办。
