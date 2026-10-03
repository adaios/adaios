---
title: AdaiOS AI 上下文工程体系（方案稿）
description: 项目级 AI 上下文工程的总览——分层模型（L0 入口 / L1 任务 / L2 约束 / L3 事实 + 横跨的工具层）、完整的目录与文件定位表、层间关系与单一权威来源原则、五张流程图（开工 / 改动 / 合并传播 / 发布 / 知识回流）、触发词工作流表、个人与团队两种配置、保障体系不腐的机制与守卫、判断依据（企业实践 + 实证数据）、以及现状与目标的落地缺口。本文是方案稿，待拍板。
version: 1
created: 2026-10-03
updated: 2026-10-03
status: draft
lines: 300
depends-on:
  - ../reference/status.md
related:
  - ./product-roadmap.md
  - ../guides/branch-development.md
  - ../guides/git-workflow.md
  - ../guides/worktree-workflow.md
  - ../../ai-engineering/assets/ai-context-layer-spec.md
tags: [architecture, ai, context, ai-tooling, plan]
---

# AdaiOS AI 上下文工程体系（方案稿）

> **状态**：方案稿（`status: draft`），待拍板。
> **与另三份的分工**：**本文是总览**（体系全貌、目录定位、流程与工作流）· `ai-engineering/assets/ai-context-layer-spec.md` 是**机制细节**（出口、契约、反模式）· `docs/guides/git-workflow.md` 是**版本控制操作**（分支/提交/合并/推送/发布）· `docs/guides/branch-development.md` 是**分支上的操作流程**（怎么改、怎么传给别人、合并后做什么）。

## 一、体系全景

```
┌────────────────────────────────────────────────────────────────┐
│ L0 入口层                                                       │
│   AGENTS.md ×7（目录分层）＋ AGENTS.local.md（本机快照）          │
│   职责：导航 · 环境事实 · 行为契约      【必须轻量，禁写详细规则】 │
└────────────────────────────────────────────────────────────────┘
                              ↓ 按需下沉（禁止全量灌入）
┌────────────────────────────────────────────────────────────────┐
│ L1 任务层（当前分支 / 当前任务）                                 │
│   docs/inbox/branch-notes/<分支>.md                             │
│   职责：本次做什么 · 进度 · 风险        【分支内必读，合并时归档】 │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│ L2 约束层（应该怎么做 / 不能做什么）—— 【决策类】                │
│   约定 conventions · 边界 boundaries · 坑 pitfalls               │
│   决策 adr/ · rfc/ · 方向 VISION / product-roadmap               │
│   职责：随开发持续增长                  【人确认才写入】          │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│ L3 事实层（事实是什么）—— 【事实类】                            │
│   代码 · ARCHITECTURE.md · api-spec · status.md                 │
│   领域知识 wiki os/*/11-context/                                │
│   职责：随代码同步                      【机器可验一致性】        │
└────────────────────────────────────────────────────────────────┘

════════════════════ 横 跨 层 ════════════════════
工具层（机制）：skills/ · roles/ · guard-*.sh · scripts/ · 契约规范
  —— 它同样是文件、同样走 git；不经 L0–L3 的"沉降水路"，而是按工具出口暴露
═══════════════════════════════════════════════════

工具侧出口（本机状态，**不入 git**，脚本重建）：
  .dsh/skills · .agents/skills · .claude/skills · .qoder/skills
  .qoder/agents/* · .codex/agents/*
```

**为什么这么切**（来自那份方法论的核心洞察）：

- **决策**会变、有立场、记「为什么」；**事实**相对稳定、可验证、随代码同步。混在一起是**文档腐烂的根源**（AI 分不清"这是约束还是现状"，要么盲从过时事实，要么无视有效约束）。
- 二者**不是静态二分**，而是**同一知识的两态**：决策被接受后成为约束/事实；事实会因新决策失效。所以每份文档都带 `status`（`draft` → `active` → `superseded`），由生命周期管理，而非靠目录分区。

## 二、目录与文件定位（全量）

| 路径 | 层 | 职责 | 随分支 | 谁写 / 怎么保真 |
|:--|:--:|:--|:--:|:--|
| `AGENTS.md` + 6 个子目录版本 | **L0** | 任何工具的统一入口：项目一句话 · 协作规则 0–11 · 审查体系导航 | ✅ | 人/AI；`guard-meta`（lines/断链）|
| `AGENTS.local.md` | **L0** | 本机开工快照（状态/未修项/边界/坑/规范/待办/成本）| ❌ | `guard-context.sh --write-local`；**恒 link 主仓库** |
| `docs/inbox/branch-notes/<分支>.md` | **L1** | 分支本地账本：做什么 · 进度 · 风险 · 待归档的账本条目 | ✅ | 分支内 AI/人；**合并时归档后删除**（待建）|
| `ai-engineering/assets/conventions.md` | **L2** | 代码与工程约定（C1–C8…）| ✅ | `guard-meta` |
| `ai-engineering/assets/boundaries.md` | **L2** | 原则级边界 B1–B9 | ✅ | 同上 |
| `ai-engineering/assets/pitfalls.md` | **L2** | 已知坑 + **复发信号**（现 23 章）| ✅ | 同上 |
| `ai-engineering/assets/adr/*.md` | **L2** | 架构决策记录（**append-only**，改动写新记录 + superseded 链）| ✅ | `guard-meta`（图谱）|
| `docs/rfc/*.md` | **L2** | 方案决策（含未采纳的备选与理由）| ✅ | `guard-feature`（status 枚举）|
| `docs/VISION.md` · `docs/architecture/product-roadmap.md` | **L2** | 业务方向（**唯一蓝图**）| ✅ | `guard-roadmap` |
| `ARCHITECTURE.md` | **L3** | 技术栈 · 五层架构 · 分层依赖 · 红线 | ✅ | `guard-meta` |
| `docs/reference/api-spec.md` | **L3** | 接口事实（端点表）| ✅ | **`guard-align` 与代码对拍** |
| `docs/reference/status.md` | **L3** | 测试数 · 端点数 · 运行环境 · 发布态 | ✅ | **`guard-align` 与实测对拍** |
| `os/*/11-context/*.md` | **L3** | 领域知识 wiki（交易规则/学习素材等）| ✅ | 人/AI |
| `services/` · `apps/` · `os/` | **L3** | 代码本体 | ✅ | 编译器 + 测试 |
| **`ai-engineering/skills/<name>/SKILL.md`** | 工具 | 技能（把流程封装成"加载即执行"）| ✅ | `guard-skills`（S3/S4/S5/S7）|
| **`ai-engineering/roles/<name>.md`** | 工具 | 审查官（**扁平**，即 subagent 的真相源）| ✅ | `guard-skills` + `sync-agents` |
| `ai-engineering/guard-*.sh` · `cadence.sh` · `weekly-audit.sh` 等 15 个 | 工具 | 守卫与执行器 | ✅ | shell-lint + 自检 |
| `ai-engineering/process/*.md`（audit/review/ship/cadence）| 工具 | 流程定义 | ✅ | `guard-meta` |
| `ai-engineering/checklists/*.md`（14 份）| 工具 | 逐条可执行清单（人也用）| ✅ | `guard-meta` |
| `ai-engineering/assets/{frontmatter,skills,ai-context-layer}-spec.md` | 工具 | **契约**（元数据 / 技能包 / 中间层）| ✅ | `guard-meta` |
| `ai-engineering/lib/*.sh` · `scripts/*.sh` | 工具 | 注册与环境（`link-skills` · `sync-agents` · `worktree-prep`…）| ✅ | shell-lint |
| `.githooks/pre-commit` | 工具 | **多层门禁**（范围守卫 → 隐私 → 密钥 → 对齐 → 结构 → 功能索引 → 技能包 → 防复发 → shell）| ✅ | 自身即守卫 |
| `docs/reference/change-log.md` · `docs/review/REVIEW.md` · 各 `_index.md` | L2/L3 账本 | 批次历史 · 未修项 · 目录索引 | ✅ | `guard-sediment` · `guard-unfixed` · `guard-meta` |
| `ai-engineering/state/*`（游标/成本账/日志）| **本机** | 协作节奏的账本 | ❌ | **恒 link 主仓库**（唯一一本）|
| 6 个出口目录 | **出口** | 让各家工具"看得见"技能与子代理 | ❌ | `worktree-prep.sh` = `link-skills` + `sync-agents` |

## 三、关系：谁依赖谁

```
                    ┌──────────────┐
                    │  L0 入口层    │  AGENTS.md（+ 本机快照）
                    └──────┬───────┘
             ┌─────────────┼──────────────┬────────────────┐
             ▼             ▼              ▼                ▼
        ┌────────┐   ┌──────────┐   ┌──────────┐   ┌────────────┐
        │ L1 任务 │   │ L2 约束   │   │ L3 事实   │   │ 工具层      │
        │ notes  │   │ 约定/边界 │   │ 代码/接口 │   │ 技能/审查官 │
        │        │   │ 坑/决策   │   │ 状态/wiki │   │ 守卫/契约   │
        └───┬────┘   └────┬─────┘   └────┬─────┘   └──────┬─────┘
            │             │              │                │
            │  引用（单向，禁止环）        │                │ 生成
            └─────────────┴──────────────┘                ▼
                                                  6 个工具出口（本机）
```

**三条关系铁律**：

1. **单一权威来源**——同一知识**只在一处详述**，别处**只引用**（那份方法论的原话）。重复即腐烂起点，靠**机器检测**（`guard-meta` 断链/孤儿；`guard-align` 事实对拍）而非纪律维持。
2. **引用单向、不设环**——`depends-on` / `related` 构成有向图；交叉引用用"软引用"（提名字、不建强依赖），避免锁死。
3. **下沉单向**——L0 → L1 → L2 → L3 只能按需下沉，**不许反向把细节塞进入口**（入口一膨胀，每次会话都付税）。

## 四、流程图

### 4.1 一次开工（会话启动，零人工）

```
用户打开工具并开会话
   │
   ├─① 工具自动注入 AGENTS.md（L0，目录树就近的那份）
   ├─② 工具自动注入 AGENTS.local.md（L0 快照：上次状态/未修项/成本）
   │
   └─③ AI 按规则 0 执行 `bash ai-engineering/guard-context.sh`
            └─ 产出：状态 · 未修项 · 边界 · 坑 · 规范 · 待办 · 成本提醒
   ↓
（若在分支上）读 L1：docs/inbox/branch-notes/<分支>.md
   ↓
按任务需要**按需加载** L2 / L3（不是全量读一遍）
   ↓
开工
```

### 4.2 一次改动（含"加一个 skill"）

```
① 开分支        git worktree add ../adaios-<任务> -b feat/<任务> main
                  cd ../adaios-<任务> && bash scripts/worktree-prep.sh
   ↓
② 改文件        • 代码（L3）· 规范/坑/决策（L2）· 技能/审查官（工具层）
   ↓
③ 本分支注册    bash scripts/link-skills.sh   # 新技能进出口
                bash scripts/sync-agents.sh   # 新审查官进 subagent
                  ⇒ 本分支内**立即可用**（出口指向本分支的真相源）
   ↓
④ 自测          bash ai-engineering/cadence.sh check   # 一键门禁
                （AI 资产另跑 guard-skills / link-skills --check / sync-agents --check）
   ↓
⑤ 提交          **显式路径** + ADAI_BATCH_PATHS 范围守卫（pre-commit 多层门禁）
                  ⇒ 账本类改动写进 L1 分支本地账本，**不碰全局账本**
```

### 4.3 一次合并与传播（关键）

```
             main（唯一汇合点）
                ▲
   ④ 合并       │   git merge --squash feat/<任务>   （见 git-workflow.md §五）
                │   冲突高发文件：change-log / REVIEW / status / 各 _index / REGISTER
                │
   ┌────────────┴─────────────┐
   │ feat/plugin-a            │ feat/plugin-b
   │ ⑤ git merge main         │ ⑤ git merge main
   │ ⑥ 重跑 worktree-prep.sh  │ ⑥ 重跑 worktree-prep.sh
   │   ⇒ 拿到别人新加的 skill  │   ⇒ 拿到别人新加的技能
   └──────────────────────────┘

★ ⑥ 是全流程最容易漏的一步：**出口不在 git 里**——真相源合过来了，
  但工具里还看不见（"我明明加了技能，怎么没有？"）。
★ 不存在"实时共享"：传播＝对方 `merge main`，一条命令。
```

### 4.4 一次发布

```
① bash ai-engineering/cadence.sh release      # 只判定：欠什么 / 发哪几端
② 用户点头（B8：外向动作须人确认）
③ bash ai-engineering/deploy-gate.sh          # 门禁 + 部署 + smoke
④ 部署成功后打 tag：git tag -a v3.x <部署的那个 commit> && git push origin v3.x
⑤ GitHub Release（用 docs/releases/ 的内容）
   ⇒ **tag ＝ 生产实际部署的 commit**（唯一对齐点）
```

### 4.5 一次知识回流（对话 → L2）

```
对话中出现信号
   │  坑点（又踩坑/规避/workaround）· 规范（统一/约定/禁止）
   │  决策（决定用 A 不用 B/选型）· 边界（不能调/职责）· 依赖（对接 XX）
   ↓
AI 标记为「待沉淀」并在会话结束输出提醒清单
   ↓
★ **禁止自动写入**——必须用户确认后才落进 L2 对应文档
   ↓
落盘（走 4.2 的提交路径）
```

> 这条是**防腐**的关键：直接防住「AI 把未验证的假设写进项目知识」。目前本项目**缺这条机制**。

## 五、工作流：触发词 → 动作

| 触发 | 动作 | 现状 |
|:--|:--|:--:|
| **开工**（自动，无需交代）| 注入 L0 + `guard-context.sh` + 读 L1 | ✅ |
| **每日巡检** | `cadence.sh daily`（从上次覆盖日补看到今天）→ 只讲「用户之声 / 新异常 / 心跳趋势」| ✅ |
| **收工** | `cadence.sh ship`（diff + 快照 + 成本入账）→ **显式路径提交** → `cadence.sh mark ship` | ✅ |
| **发布 / 发版** | `cadence.sh release`（**只判定，不部署**）| ✅ |
| **每周** | `cadence.sh weekly`（W1–W6 + 到期红线）| ✅ |
| **待办** | `cadence.sh todo`（REVIEW 未修项）| ✅ |
| **待办**（前瞻）| `cadence.sh`（无参数 = 状态总览）| ✅ |
| **沉淀**（前瞻）| AI 主动提示「待沉淀清单」→ 人确认 → 写 L2 | ❌ **待建** |
| **加一个 skill / 审查官** | 走 §4.2 分支流程 | ✅ 机制已备 |
| **接一个新工具** | `ai-context-layer-spec.md` §五 四步 | ✅ |

## 六、个人 vs 团队：同一结构，两种配置

**结构完全一样**（L0–L3 + 工具层 + 单一权威来源）。差别只在**强制程度**与**责任归属**：

| 维度 | **个人**（当前）| **团队**（扩展配置）|
|:--|:--|:--|
| 分支 | `main` + 短命分支（worktree 并行）| + **保护分支** + PR + **required review** |
| 共享 | 各自注册出口 + `merge main` | 同 + **CODEOWNERS**（哪块归谁）|
| 知识回流 | AI 提示 + 你确认 | 同 + **PR review** 再一道 |
| **强制层** | `pre-commit` 门禁（本机，**可绕过**）| + CI + **managed settings / 组织级指令**（**管理员不可绕过**）|
| 责任 | 你自己 | **owner 制**（每块有负责团队）|
| 加载 | L0–L3 按需 | 同 + **路由表按模块分发** |
| 防膨胀 | 行数红线 + 定期裁 | 同 + 定期审计出报告 |

> 那份方法论里被标「过时」的分支纪律（feature/develop/master + 硬卡点），**就是"团队"那一栏**——单人过时，团队仍成立。

## 七、保障体系不腐的机制

| # | 机制 | 现状 | 覆盖 |
|:--:|:--|:--:|:--|
| 1 | **范围守卫**（显式路径 + `ADAI_BATCH_PATHS`）| ✅ | 防并发会话互卷 |
| 2 | **多层 pre-commit 门禁**（隐私/密钥/对齐/结构/功能索引/技能包/防复发/shell）| ✅ | 提交即拦 |
| 3 | **元数据图谱自检**（`guard-meta`：断链/lines/孤儿/正文路径）| ✅ | L2/L3 + 工具层 |
| 4 | **事实对拍**（`guard-align`：端点↔api-spec · 测试数↔status）| ✅ | L3 |
| 5 | **技能包合规**（`guard-skills` S3/S4/S5/S7）| ✅ | 工具层 |
| 6 | **工具接入自检**（`guard-tools` T1–T7，含出口真身）| ✅ | 出口 |
| 7 | **反例回归**（`ai-engineering/tests/`：守卫必须能被反例触发）| ✅ | 守卫自身 |
| 8 | **知识回流需人确认**（AI 提示，禁自动写入 L2）| ❌ **待建** | L2 |
| 9 | **防膨胀红线**（单文件 >300 行提示拆 / >500 必拆；同知识点 ≥2 次合并；「已废弃」超 3 月归档）| ❌ **待建** | 全体 |
| 10 | **按需加载路由**（模块↔关键词表 + 惰性加载）| ❌ **待建** | L2/L3 |
| 11 | **上下文成本度量**（每个 skill 的 context 成本 × 调用频率；从未调用的撤出）| ❌ **待建** | 工具层 |

## 八、判断依据（为什么这么定）

| 依据 | 来源 | 影响了体系的哪一条 |
|:--|:--|:--|
| **决策/事实分离**；L1 每次必读；单一权威来源；知识回流需人确认；防膨胀四维检查 | 本项目早期方法论（公司项目实践，2026-08 稿）| §一 分层 · §三 铁律 · §七 8/9 |
| **一切是文件、都走 git**（含工具层）| 用户 2026-10-03 的主张 | §4.2/4.3 流程 · 工具层定位 |
| **上下文文件不提升正确率，但 +20% 成本**；「删掉 .md 后反而 +2.7%」；失败在 implementation skill | [ETH Zurich arXiv 2602.11988](https://ar5iv.labs.arxiv.org/html/2602.11988) · [arXiv 2607.27250](https://ar5iv.labs.arxiv.org/html/2607.27250v1) | **§七 9/10/11（防膨胀、按需、度量）** |
| **skill listing 预算 ≈ context window 的 1%**，溢出丢弃 description ⇒ 越多越不准 | [Agent Skills 规范](https://agentskills.io/specification) | §七 11 · 出口只注册直触发 |
| **按路径/目录作用域化**（四家工具独立收敛）| AGENTS.md / Claude Code / Copilot / Cursor 官方文档 | §一（入口分层）· §七 10 |
| **上下文不是硬约束**——强制要走 hook / managed settings / sandbox | Anthropic · Cursor · DORA | §六（团队态）· §七 2 |
| **AI 直连内部数据是放大器**；更高采纳＝吞吐↑ + 不稳定↑ | [DORA 2025](https://dora.dev/insights/balancing-ai-tensions/) | §三（单一来源便于被 AI 直读）|
| **知识资产像代码管理**（版本 + 评审 + owner + 追加式变更）| DDC 论文 §5.4 · docs-as-code · ADR · Changesets | §4.2/4.3 · §六 |
| **「提升 X%」类宣称要看测量设计** | METR（自我宣布 RCT 失效）| 本文不写"提升多少"的承诺 |

## 九、落地缺口（现状 → 目标）

| 缺口 | 目标 | 代价 |
|:--|:--|:--|
| **L1 任务层缺失** | 建 `docs/inbox/branch-notes/` 模板 + 合并时归档 | 小（模板 + 规范条款 + `guard-sediment` 检查残留）|
| **知识回流无确认机制** | AI 输出「待沉淀清单」→ 人确认才写 | 小（写进 AGENTS.md 规则）|
| **防膨胀无红线** | `guard-meta` 加"行数/重复/过时"提示 | 小（守卫加一维）|
| **无按需加载路由** | 模块↔关键词表（从 `docs/features/_index.md` 与 `AGENTS.md` 分层自然长出）| 中 |
| **上下文成本无度量** | 统计每个 skill/审查官的调用频率与 context 开销 | 中（需要埋点或日志分析）|
| **出口与真相源的一致性靠"记得重跑"** | `guard-tools` T4 已能查真身；再加一条"出口过期"提示 | 小 |
| **AI 上下文资产已偏重**（179 docs / 64 ai-eng md / 规范 ~190 行）| 按 §七 9 的红线做一轮**裁**（不是再加）| 中 |

---

## 一句话总览

> **分层按需加载（L0→L3）+ 工具层横跨；一切是文件、都走 git；单一权威来源靠机器检测；知识回流必须人确认；默认不加、按需加载、量不出来就撤。**
