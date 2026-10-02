---
title: 项目级 AI 上下文中间层规范（AI Context Layer Spec）
description: AdaiOS 项目级 AI 上下文的中间层规范——定义 AI 资产**放在哪**（真相源）、**怎么被各工具发现**（出口）、**怎么新增工具与维护一致性**。行业标准管「长什么样」（Agent Skills 管技能格式、Agent Plugins 管包结构、AGENTS.md 管背景契约），没有管「项目层一份、多工具都看见」这一段——本规范补的正是这一段。
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 141
depends-on:
  - skills-spec.md
  - ../frontmatter-spec.md
related:
  - ../../AGENTS.md
  - ../../scripts/link-skills.sh
  - ../guard-tools.sh
  - ../../docs/rfc/20261003-project-level-ai-context-layer.md
tags: [ai, meta, governance, context-layer]
---

# 项目级 AI 上下文中间层规范

> **定位**：定义 AdaiOS 的 AI 资产**放在哪、怎么被各工具发现、怎么维护**。格式规则不在这里（技能格式见 `skills-spec.md`，文档元数据见 `frontmatter-spec.md`）。

## 一、为什么需要中间层

本项目由**一个人 + 多个 AI 工具**（DSH / Qoder / Codex / 未来更多）开发。若 AI 资产跟着工具走，换一次工具就要重写一次；若不跟着工具走，工具又**看不见**它（各工具只扫自己的目录）。

**弱发现 vs 原生发现**：靠"AGENTS.md 里写一句『去 `ai-engineering/` 找技能』"是弱发现——AI 要先读到那句话、再主动去翻文件，费上下文且不稳定；**原生发现**是工具把它当作可用技能列出。

中间层要解决的就是这件事：**真相源一份、每个工具原生看得见**。

## 二、三层模型（不变量）

```
L1 真相源   项目层，进 git，唯一可写         ← 本仓库的 ai-engineering/ 等
L2 出口     各工具目录，软链，不进 git        ← 由 scripts/link-skills.sh 重建
L3 工具私有 各工具自己的配置，项目不代管      ← .idea/ 等
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
| **开工快照** | `ai-engineering/state/`（游标/成本账）+ `guard-context.sh` 的输出 | 脚本生成 | **跨工具靠「跑脚本」**：`AGENTS.md` 规则 0 要求任何 AI 开工先跑 `guard-context.sh`；DSH 另把它写进 `AGENTS.local.md` **自动注入**（＝DSH 专属缓存，**Qoder / Codex / Claude Code 都不读这个文件名**）|
| 技能 | `ai-engineering/skills/<name>/SKILL.md` | 官方目录布局 | 软链到出口（§四）|
| 审查官 | `ai-engineering/roles/<name>.md`（**扁平**） | — | **不进技能出口**（流程内触发，见 §六）；其「出口」是下行生成的 subagent 定义 |
| 子代理 | 同上（审查官 `.md` 即 subagent 的真相源） | — | **生成**到 `.qoder/agents/<name>.md`（md+YAML）· `.codex/agents/<name>.toml`（**TOML**）——**格式不同故不能软链**；生成时**重写相对路径**（`../assets/x`→`ai-engineering/assets/x`）+ **只读强制**（Qoder `tools: Read, Grep, Glob` · Codex `sandbox_mode="read-only"`）|
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

**两条硬约束**（实测得出）：

1. **没有任何单一目录能被所有工具读到**——Claude Code 官方明确不读 `.agents/`；DSH 不读根 `skills/`；Qoder 只认 `.qoder/skills/`。**押注"中立目录"这条路不存在。**
2. **主流收敛到官方目录布局 `<name>/SKILL.md`**（Claude Code / Qoder 只认它，DSH 两种都认）⇒ 技能真相源必须用目录布局。
3. **`.agents/` 是出口位，不能当真相源**——它是**被多家工具扫描**的目录（Codex / Cursor / Gemini CLI / Copilot / OpenCode 等）。把 `ai-engineering/` 改名搬进去会同时踩四个雷：① **语义不符**（`.agents/` 在行业语义里是「技能/子代理容器」，而我们那 12 个子目录是整个工程体系）；② **真相源会被工具当出口直接扫**——`skills/`、`process/`、`checklists/` 全被当技能读，**真相源/出口分层当场崩掉**；③ **gitignore 冲突**（`.agents/` 被忽略、真相源必须进 git）；④ **`guard-tools` T4 判据失效**（它检查的是「`.agents/skills` 里的软链是否指向本仓库 `ai-engineering/`」）。

**当前净出口 3 个**：`.dsh/skills` · `.agents/skills`（喂 DSH + Codex + Cursor/Gemini CLI/Copilot/OpenCode 等公约数阵营）· `.qoder/skills`。

## 五、新增工具接入流程（四步，缺一不可）

1. **查官方文档确认项目级路径**——**不猜**。文档滞后于实现是常态（Qoder 插件文档未提 skills，实测支持），所以第 4 步必做。
2. **加进 `scripts/link-skills.sh` 的 `TARGETS`**（技能出口）。
3. **加进 `ai-engineering/guard-tools.sh` T4 的扫描清单**——否则新出口**无人检查**（T4 按软链真身判定，不认名字）。
   - **子代理出口同理**：加进 `scripts/sync-agents.sh` 的 `TARGETS`（并按该工具的 subagent 格式加一种生成分支）。
4. **放探针实测**：技能 + 子代理各一个最小探针 → 目标工具里验证 → **结果写回 §四（含日期）** → 清理探针。

> 探针一律**本地忽略**（`.git/info/exclude` 或 `.gitignore`），验证完即删。

## 六、准入与布局

- **技能格式**：按 `skills-spec.md`（五段结构 + frontmatter 十字段）。
- **布局**：`<name>/SKILL.md`，**目录名 == frontmatter `name`**（官方硬约束，也是命令名来源）。
- **只注册"用户直触发"的技能**到出口——流程内触发的资产（如 12 个审查官）**不进 catalog**：它们常驻上下文要花钱，还可能被误触发（成本纪律，见 `checklists/cost.md`）。
- **产出物不进真相源**（生成的卡片/报告/缓存归 `data/` 或 `state/`）。

## 七、维护规则

| 场景 | 动作 |
|:--|:--|
| 改技能内容 | **只改真相源**；出口是软链，自动生效 |
| 改审查官内容 | 跑 `bash scripts/sync-agents.sh` **重新生成** subagent 定义；自检 `--check`（生成物不进 git）|
| 换机 / 新 clone | `bash scripts/link-skills.sh`（+ `setup-hooks.sh`）；自检 `--check` |
| 自检一致性 | `bash scripts/link-skills.sh --check` · `bash ai-engineering/guard-tools.sh`（T4）|
| 新增/删除出口 | 改 `TARGETS` → 跑脚本 → 更新 §四 表 |
| 有意的偏离 | **必须留痕**（写进本规范 + `pitfalls.md`）|

## 八、反模式（禁止）

1. **把技能复制到工具目录**——第二真相源，必然漂移（T4 能发现"指向别处"，但发现不了"副本"，靠纪律）。
2. **押注一个"中立目录"**——不存在（§四 硬约束 1）。
3. **在守卫脚本里硬编码工具路径**——T4 按软链真身判定，新增工具无需改判据。
4. **改出口不改真相源**——出口是软链则不可能；一旦出现副本，立即消除。
5. **为"形式一致"付全量迁移**——如 12 个审查官不必要地全部目录化（按需迁）。
6. **为空场景预造机制**——没有消费者就先不建（如第三方技能准入、子代理出口）。
7. **在守卫里加"字段"却没确认它是列表还是标量**（见 `pitfalls.md` 二十三）。
8. **把真相源放进 `.agents/`**（或任何被工具扫描的目录）——那是**出口位**，不是本体位；见 §四 硬约束 3。

## 九、硬规则 vs 软约定（诚实分层）

| 规则 | 强度 | 谁在保障 |
|:--|:--:|:--|
| 出口是指回本仓库的软链 | **硬** | `guard-tools` T4（按真身判定）|
| 技能目录名 == `name`；两种布局都覆盖 | **硬** | `guard-tools` T3 |
| 真相源 frontmatter 契约 / lines / 图谱 | **硬** | `guard-meta` |
| 提交前门禁（隐私/密钥/对齐/shell） | **硬** | `.githooks/pre-commit`（六层）|
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

**变更方式**：新增工具 / 新出口 / 新资产类别 → 改本文对应表 + 走 §五 四步 + `guard-meta` PASS；本文件状态 `active`，重大调整升 `version`。
