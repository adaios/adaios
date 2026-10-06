---
title: 项目级 AI 上下文中间层规范（AI Context Layer Spec）
description: AdaiOS 项目级 AI 上下文的中间层规范——定义 AI 资产**放在哪**（真相源）、**怎么被各工具发现**（出口）、**怎么新增工具与维护一致性**。行业标准管「长什么样」（Agent Skills 管技能格式、Agent Plugins 管包结构、AGENTS.md 管背景契约），没有管「项目层一份、多工具都看见」这一段——本规范补的正是这一段。
version: 1
created: 2026-10-03
updated: 2026-10-05
status: active
lines: 211
depends-on:
  - skills-spec.md
  - ../../frontmatter-spec.md
related:
  - ../../../AGENTS.md
  - ../../mechanism/scripts/ai-link-skills.sh
  - ../../mechanism/guards/ai-guard-tools.sh
  - ../../direction/rfc/20261003-project-level-ai-context-layer.md
tags: [ai, meta, governance, context-layer]
---

# 项目级 AI 上下文中间层规范

> **定位**：定义 AdaiOS 的 AI 资产**放在哪、怎么被各工具发现、怎么维护**。格式规则不在这里（技能格式见 `skills-spec.md`，文档元数据见 `frontmatter-spec.md`）。

## 一、为什么需要中间层

本项目由**一个人 + 多个 AI 工具**（DSH / Qoder / Codex / 未来更多）开发。若 AI 资产跟着工具走，换一次工具就要重写一次；若不跟着工具走，工具又**看不见**它（各工具只扫自己的目录）。

**弱发现 vs 原生发现**：靠"AGENTS.md 里写一句『去 `ai-engineering/` 找技能』"是弱发现——AI 要先读到那句话、再主动去翻文件，费上下文且不稳定；**原生发现**是工具把它当作可用技能列出。

中间层要解决的就是这件事：**真相源一份、每个工具原生看得见**。

## 二、三层模型（不变量）

> **本节的层用名字、不用编号**——「加载层」的 `L0–L3` 见 [`.agents/rules/assets/ai-context-engineering.md`](ai-context-engineering.md)；两者是**正交维度**（存放 vs 加载），共用编号会造成跨文档歧义（2026-10-03 审查修正）。

```
真相源      项目层，进 git，唯一可写         ← 本仓库的 .agents/ 等
出口        各工具目录，软链，不进 git        ← 由 .agents/mechanism/scripts/ai-link-skills.sh 重建
工具私有    各工具自己的配置，项目不代管      ← .idea/ 等
```

1. **真相源只有一份，永远不在工具目录里。**
2. **出口必须是指回本仓库的软链**；禁止副本（副本＝第二真相源＝必然漂移）。
3. **工具私有配置属于工具**，项目不生成、不代管。
4. **出口是本机状态**，不进 git（换机用一条命令重建）。
5. **一个仓库一套真相源**——但「一套」指的是**真相源**，不是「所有文件都在根目录」。判据是：**进 git 的、定义「AI 怎么工作」的文件**才算真相源。以下三类**不算重复，不要收走**：
   - **分层 `AGENTS.md`**（`services/*`、`apps/*`、`os/*` 各一份）：这是 AGENTS.md 的**标准特性**（就近原则，子目录覆盖上层），收走反而破坏它；
   - **Domain 知识**（`os/**/rules.md`、`os/*/11-context/` 等）：那是**产品知识资产**，归 `os/`，不在 AI 工程层；
   - **工具私有产物**（子项目里的 `.claude/` `.qoder/` 等，**不进 git**）：本机状态，无第二真相源之虞；可清理，但不必「收到根」。

## 三、真相源清单（什么属于中间层）

| 类别 | 位置 | 布局 | 怎么被工具看见 |
|:--|:--|:--|:--|
| 背景契约 | `AGENTS.md` + 6 个子项目 `AGENTS.md` + `ARCHITECTURE.md` | 单文件 | **工具原生读**（无需出口）|
| **开工快照** | `.agents/records/state/`（游标/成本账）+ `ai-guard-context.sh` 的输出 | 脚本生成 | **跨工具靠「跑脚本」**：`AGENTS.md` 规则 0 要求任何 AI 开工先跑 `ai-guard-context.sh`；DSH 另把它写进 `AGENTS.local.md` **自动注入**（＝DSH 专属缓存，**Qoder / Codex / Claude Code 都不读这个文件名**）|
| 技能 | `.agents/toolkit/skills/<name>/SKILL.md` | 官方目录布局 | 软链到出口（§四）|
| 审查官 | `.agents/toolkit/roles/<name>.md`（**扁平**） | — | **不进技能出口**（流程内触发，见 §六）；其「出口」是下行生成的 subagent 定义 |
| 子代理 | 同上（审查官 `.md` 即 subagent 的真相源） | — | **生成**到 `.qoder/agents/<name>.md`（md+YAML）· `.codex/agents/<name>.toml`（**TOML**）——**格式不同故不能软链**；生成时**重写相对路径**（`./x`→`.agents/rules/assets/x`）+ **只读强制**（Qoder `tools: Read, Grep, Glob` · Codex `sandbox_mode="read-only"`）|
| 脚本与门禁 | `ai-engineering/*.sh` · `scripts/` · `.githooks/` | — | 工具无关；靠契约文档导航 |
| 知识库（被读写） | `docs/`（rfc / features / review / reference / guides…） | — | 同上 |

## 四、出口契约（实测基线）

| 工具 | 技能出口（项目级） | 子代理出口 | 跟随软链 | 实测日期 |
|:--|:--|:--|:--:|:--|
| **DSH** | `.dsh/skills` · `.agents/skills` | ⚠️ 无项目级契约 | ✅ | 2026-10-03 |
| **Qoder**（含 JetBrains 插件） | `.qoder/skills` | `.qoder/agents/<name>.md`（md+YAML） | ✅ | 2026-10-03 |
| **Codex** | `.agents/skills` | `.codex/agents/<name>.toml`（**TOML**） | ✅ | 2026-10-03 |
| Claude Code | `.claude/skills` | `.claude/agents/<name>.md` | ✅（官方明确支持） | 官方文档 |
| 预留 | `skills/`（仅 OpenClaw 等用根目录的工具） | — | — | — |

**⛔ 出口清单的机器可读版是唯一真相源**：`.agents/mechanism/scripts/lib/ai-export-targets.sh`
（`SKILL_TARGETS` / `AGENT_TARGETS`，2026-10-05 起）。**本表是人读的镜像**——改清单必须同步改本表；
`ai-guard-tools.sh` 的 **T8** 会提示两者口径不一致。

**两条硬约束**（实测得出）：

1. **没有任何单一目录能被所有工具读到**——Claude Code 官方明确不读 `.agents/`；DSH 不读根 `skills/`；Qoder 只认 `.qoder/skills/`。**押注"中立目录"这条路不存在。**
2. **主流收敛到官方目录布局 `<name>/SKILL.md`**（Claude Code / Qoder 只认它，DSH 两种都认）⇒ 技能真相源必须用目录布局。
3. **`.agents/` 是出口位，不能当真相源**——它是**被多家工具扫描**的目录（Codex / Cursor / Gemini CLI / Copilot / OpenCode 等）。把 `ai-engineering/` 改名搬进去会同时踩四个雷：① **语义不符**（`.agents/` 在行业语义里是「技能/子代理容器」，而我们那 12 个子目录是整个工程体系）；② **真相源会被工具当出口直接扫**——`skills/`、`process/`、`checklists/` 全被当技能读，**真相源/出口分层当场崩掉**；③ **gitignore 冲突**（`.agents/` 被忽略、真相源必须进 git）；④ **`ai-guard-tools` T4 判据失效**（它检查的是「`.agents/skills` 里的软链是否指向本仓库 `ai-engineering/`」）。

**当前出口 4 个**（= `SKILL_TARGETS`，2026-10-05 复核）：`.dsh/skills` · `.claude/skills` · `.qoder/skills` · `.agents/skills`（末项喂 Codex / Cursor / Gemini CLI / Copilot / OpenCode 等公约数阵营）。

> ⚠️ **2026-10-05 修掉一处「静默失效」**：`.agents/skills` 出口位在 2026-10-04 六顶层重构后**实际消失了**
> ——真相源从 `.agents/skills/` 搬到 `.agents/toolkit/skills/`，而 `ai-link-skills.sh` 的旧注释仍写着
> 「`.agents/skills` 是真相源本身，无需软链」（重构后这句已错），出口位就此丢失；T4 又还在扫这个
> 空路径 ⇒ **一直静默 PASS**。根因是「同一个事实散在 4 处各写一份」（link-skills / sync-agents /
> guard-tools / 本表），现已收敛到 `lib/ai-export-targets.sh` 一处（见 §五）。

## 五、新增工具接入流程（三步，缺一不可 · 2026-10-05 收敛）

> **改动点收敛为 1 处**：出口清单的唯一真相源是 `.agents/mechanism/scripts/lib/ai-export-targets.sh`
> （`SKILL_TARGETS` / `AGENT_TARGETS`）。此前「加一个工具要改 4 处」（`ai-link-skills.sh` 的 TARGETS、
> `ai-sync-agents.sh` 的 TARGETS、`ai-guard-tools.sh` 的 T4 扫描清单、本规范 §四 表），**漏一处静默**
> ——`.agents/skills` 出口位就是这么消失的。现在四处**全部读同一份清单**（T4 直接按清单扫描）
> ⇒ 「新加的出口没人检查」在结构上不可能再发生。

1. **查官方文档确认项目级路径**——**不猜**。文档滞后于实现是常态（Qoder 插件文档未提 skills，实测支持），所以第 3 步必做。
2. **只改 `lib/ai-export-targets.sh`**：技能出口加进 `SKILL_TARGETS`；子代理出口加进 `AGENT_TARGETS`
   （并按该工具的 subagent 格式在 `ai-sync-agents.sh` 加一种生成分支）→ 跑 `bash .agents/mechanism/scripts/ai-sync-all.sh`。
   - 别忘 `.gitignore`：出口是**本机状态，必须忽略**，且规则要精确（写成 `skills/` 会连真相源一起忽略——pitfalls 二十三）。
3. **放探针实测**：技能 + 子代理各一个最小探针 → 目标工具里验证 → **结果写回 §四（含日期）并同步 §四 表** → 清理探针。

> 探针一律**本地忽略**（`.git/info/exclude` 或 `.gitignore`），验证完即删。

## 六、准入与布局

- **技能格式**：按 `skills-spec.md`（五段结构 + frontmatter 十字段）。
- **布局**：`<name>/SKILL.md`，**目录名 == frontmatter `name`**（官方硬约束，也是命令名来源）。
- **技能出口**：只注册"用户直触发"的技能——其余技能常驻 catalog 要花钱，还可能被误触发（成本纪律，见 `checklists/ai-cost-checklist.md`）。
  **判据**：用户能不能**一句话直呼它**？能 → 进 `REGISTER`；只有流程走到某一步才需要 → 不进（由流程文档导航）。当前 4 个技能包逐一对号：

  | 技能 | 注册 | 判据 |
  |:--|:--:|:--|
  | `data-learn-writer` | ✅ | 用户说「整理 <链接> / 把这篇文章存下来」＝直触发 |
  | `code-api-writer` | ❌ | 只在"新增端点"这一步需要 → 流程内触发（`review.md` / `ship.md` 导航） |
  | `code-domain-writer` | ❌ | 同上（新增领域模块时） |
  | `task-ship` | ❌ | 「收工」触发的是 `task-cadence.sh ship`（脚本）；技能包是给 AI 读的流程说明 |
- **subagent 出口**：审查官**全量注册**（12/12，2026-10-03 起）——实测 12 个 `description` 合计 **≈1.3k token**，远低于 Claude Code 官方 **15k token** 告警线。这条线是硬约束：接近时**优先缩短 description**，不要砍审查官。
- **产出物不进真相源**（生成的卡片/报告/缓存归 `data/` 或 `state/`）。

## 七、维护规则

| 场景 | 动作 |
|:--|:--|
| 改技能内容 | **只改真相源**；出口是软链，自动生效 |
| 改审查官内容 | 跑 `bash .agents/mechanism/scripts/ai-sync-agents.sh` **重新生成** subagent 定义；自检 `--check`（生成物不进 git）|
| **换机 / 新 clone** | **一条命令**：`bash .agents/mechanism/scripts/ai-sync-all.sh`（git hooks + 技能 + 子代理，自带自检；2026-10-05 起）|
| 自检一致性 | `bash .agents/mechanism/scripts/ai-sync-all.sh --check` · `bash .agents/mechanism/guards/ai-guard-tools.sh`（T4 技能真身 + **T8** 出口清单口径）|
| 新增/删除出口 | 改 `lib/ai-export-targets.sh` → 跑 `ai-sync-all.sh` → 同步 §四 表（T8 会提示不一致）|
| 有意的偏离 | **必须留痕**（写进本规范 + `pitfalls.md`）|

### 多 worktree / 多分支下的行为（2026-10-03 补）

**真相源随分支走；出口是本机状态、每个 worktree 各自一套。**

| 资产 | 在 worktree 里怎么来 | 随分支？ |
|:--|:--|:--:|
| `AGENTS.md` ×7 · `ARCHITECTURE.md` · `.agents/**`（技能 / 审查官 / 规范） | git 检出 | ✅ |
| **技能出口 ×4** | `bash .agents/mechanism/scripts/ai-link-skills.sh`（**相对软链** → 指向本 worktree） | ✅ |
| **子代理出口 ×2** | `bash .agents/mechanism/scripts/ai-sync-agents.sh`（**生成**） | ✅ |
| `data/` · `services/adai-core/.env` | `.agents/mechanism/scripts/ai-worktree-prep.sh`（link 或 `--copy`） | — |
| **`.agents/records/state/` · `AGENTS.local.md`** | **恒 link 主仓库**——账本与开工快照**必须唯一** | ❌（有意）|

**一条命令补齐**：`bash .agents/mechanism/scripts/ai-worktree-prep.sh`（自动含出口注册与 `--check`）。

**分支开发下的全流程**（资产全景 · **加 skill 也开分支** · 改完怎么传给其他分支 · **合并后重建出口**）见 `.agents/rules/guides/branch-development.md`。

**铁律**：**绝不 link 主仓库的出口**——那会让 `feat/a` 的技能漏进 `feat/b`，分支隔离在 AI 上下文层失效。

## 八、反模式（禁止）

1. **把技能复制到工具目录**——第二真相源，必然漂移（T4 能发现"指向别处"，但发现不了"副本"，靠纪律）。
2. **押注一个"中立目录"**——不存在（§四 硬约束 1）。
3. **在守卫脚本里硬编码工具路径**——T4 按软链真身判定，新增工具无需改判据。
4. **改出口不改真相源**——出口是软链则不可能；一旦出现副本，立即消除。
5. **为"形式一致"付全量迁移**——如 16 个角色不必要地全部目录化（按需迁；角色**刻意保持扁平**）。
6. **为空场景预造机制**——没有消费者就先不建（如第三方技能准入、子代理出口）。
7. **在守卫里加"字段"却没确认它是列表还是标量**（见 `pitfalls.md` 二十三）。
8. **把真相源放进 `.agents/`**（或任何被工具扫描的目录）——那是**出口位**，不是本体位；见 §四 硬约束 3。

## 九、硬规则 vs 软约定（诚实分层）

| 规则 | 强度 | 谁在保障 |
|:--|:--:|:--|
| 出口是指回本仓库的软链 | **硬** | `ai-guard-tools` T4（按真身判定）|
| 技能目录名 == `name`；两种布局都覆盖 | **硬** | `ai-guard-tools` T3 |
| 真相源 frontmatter 契约 / lines / 图谱 | **硬** | `ai-guard-meta` |
| 技能包符合官方规范（name / description / 五段 / 偏离在案） | **硬** | `ai-guard-skills`（S3/S4/S5/S7）|
| 提交前门禁（隐私 / 密钥 / 对齐 / 结构 / 功能索引 / 技能 / 防复发 / shell） | **硬** | `.githooks/pre-commit`（多层，数字不写死）|
| 只注册"用户直触发"技能 | 软 | 纪律（写在本文与脚本注释）|
| 不复制到工具目录 | 软 | 纪律（T4 覆盖不到副本）|
| 新工具走四步流程 | 软 | 纪律 |

> "软"的三条是**待加固**方向，本规范显式标出，不在本批实现。

## 十、实测记录与变更方式

| 日期 | 事项 | 结论 |
|:--|:--|:--|
| 2026-10-03 | DSH 三探针 | `.dsh/skills` ✅（两种布局）· `.agents/skills` ✅ · 根 `skills/` ❌；清单**热更新** |
| 2026-10-03 | Qoder 两探针（JetBrains 插件） | `.qoder/skills` ✅ · **跟随软链** ✅ · `.qoder/agents/` ✅ |
| 2026-10-03 | Codex 两探针 | `.agents/skills` ✅（与 DSH 共用，**零新增出口**）· 跟随软链 ✅ · `.codex/agents/*.toml` ✅ |
| 2026-10-03 | `ai/context/` 退役 | 早期空壳设计（占位符从未填充），内容已被本清单的资产承担 |

**变更方式**：新增工具 / 新出口 / 新资产类别 → 改本文对应表 + 走 §五 四步 + `ai-guard-meta` PASS；本文件状态 `active`，重大调整升 `version`。

## 十一、证据（2026-10-03 补 · 行业实证）

**这一节约束「要不要再加上下文」的所有决定**——因为直觉与实证方向相反。

| 结论 | 证据 | 强度 |
|:--|:--|:--|
| **「写更多上下文文件」不提升正确率** | ETH Zurich《Evaluating AGENTS.md》（[arXiv 2602.11988](https://ar5iv.labs.arxiv.org/html/2602.11988)，AGENTbench：5,694 PR → 138 实例 / 12 仓库 / 4 组 agent×模型）：**LLM 生成的上下文 −0.5% ~ −2%**；开发者手写 **+4%**；但**成本 +19% ~ +23%** | 实证 |
| **独立复现同样中性** | [arXiv 2607.27250](https://ar5iv.labs.arxiv.org/html/2607.27250v1)（288 次有效运行 / 2 agent / 17 任务 / 3 仓库 / gold test）：正确率**无显著差异**（界定在 <10pp / <15pp）；**失败原因是 implementation skill（功能设计与接线），不是缺仓库知识** | 实证 |
| **上下文文件可能是「冗余文档」** | 同上：把仓库里所有 `.md` / 示例 / `docs/` **删掉**后，LLM 生成的上下文文件**反而平均 +2.7%**，并超过开发者手写版 | 实证（机制发现）|
| **有证据的是「过程」，不是「正确率」** | 同上：`selective` 策略显著降低 cache-creation token（p=0.012）；写明「全量测试要跑 >20 分钟」的仓库，盲目跑全量的次数 **3.67 → 1.67**、墙钟 −24%（探索性）| 实证（探索性）|
| **正面：AI 直连内部数据是放大器** | DORA（[AI-accessible internal data](https://dora.dev/capabilities/ai-accessible-internal-data/)）：是**个人效能与代码质量的统计显著乘数** | 实证（权威）|
| **但代价是实的** | DORA 2025（[Balancing AI tensions](https://dora.dev/insights/balancing-ai-tensions/)）：90% 用 AI · >80% 自认提效 · **30% 几乎不信任 AI 生成的代码**；更高采纳同时关联**吞吐↑ + 不稳定性↑**；负面主题＝**验证税 / 幻觉 / 技能退化 / 制造债务** | 实证 |
| **「提升 X%」的宣称基本不可信** | METR（[2026-02 更新](https://metr.org/blog/2026-02-24-uplift-update/)）**自我宣布 RCT 设计已被选择偏差破坏**（30–50% 开发者不提交「没有 AI 就不想做」的任务）| 实证（负面）|
| **skill 有硬规模上限** | Agent Skills 官方（[spec](https://agentskills.io/specification)）：skill listing **每轮进上下文**，预算 **≈ context window 的 1%**，溢出时**丢弃部分 description** ⇒ **skill 越多，触发越不准** | 官方文档 |

**⇒ 三条操作判据**：

1. **默认不加**——新增上下文资产（技能 / 规范 / 文档 / 审查官）之前先问：「这算 **过程改善** 还是 **知识补齐**？」只有前者有实证支持。
2. **按路径 / 按需加载**——四家工具（AGENTS.md 嵌套 · Claude Code `.claude/rules` + `paths:` · Copilot `*.instructions.md` + `applyTo` · Cursor `globs`）**独立收敛到「作用域化 + 按需」**；**全量注入是负收益**（+20% 成本、正确率不升）。
3. **可度量才保留**——每个技能 / 审查官都应能回答「**context 成本多少 · 被调用几次**」（Claude Code `/skill-doctor` 的做法：报告成本与调用频率，标出「从未被调用」）；**从未被调用过的应撤出 catalog**，而不是继续加。

**本项目实测对照（2026-10-03）**：一次 AI 资产台账体检的结果是「**11/12 审查官零读取 · skill 调用 0 次**」——与上述证据方向一致。**所以后续对上下文资产的动作，默认应是「裁」或「按需加载」，不是「再加一层」。**

