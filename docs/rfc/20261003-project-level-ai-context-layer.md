---
title: 项目级 AI 上下文中间层——扩展现有机制（实测校准版）
description: 目标（用户 2026-10-03）＝一个中间层：项目级 AI 上下文（AGENTS.md / skill / subagent / 脚本）在项目层，不与工具深度绑定，而 Qoder / Codex / Claude Code / DSH 都能读。★本版两处重大修正：① **机制项目里早已存在**——`scripts/link-skills.sh` + `.gitignore` 三目录忽略 + `guard-tools.sh` T4 检查已在运行，只是只注册 1 个技能（learn-digest）、只覆盖 1 个工具（DSH），故本 RFC 从「新建中间层」改为「扩展既有机制」；② **DSH 技能发现当场实测**（三探针，2026-10-03）：`.dsh/skills/` ✅（扁平与目录两种布局都认）、`.agents/skills/` ✅、**工作区根 `skills/` ❌ 不生效**（原 F7 推测被证伪，且技能清单是**热更新**的，无需重开会话）；③ **Qoder 实测**（JetBrains 插件）：项目级技能目录是 **`.qoder/skills/`**（**非**项目根 `skills/`）、**跟随软链**、`.qoder/agents/` 子代理亦识别；④ **Codex 实测**：技能目录＝`.agents/skills/`（**与 DSH 共用 ⇒ 零新增出口**）、**跟随软链**、`.codex/agents/<name>.toml` 子代理可用。
date: 2026-10-03
status: draft
decided-by: —（待拍板：用户 2026-10-03「按照下一步走」→「放」＝授权放探针实测；探针已清理，方案落地须另行点头）
tags: [ai-engineering, 中间层, skills, subagent, 多工具, 门禁, RFC]
related:
  - 20261003-skill-conformance-and-supply-chain.md
  - ../../AGENTS.md
  - ../../scripts/link-skills.sh
  - ../../ai-engineering/guard-tools.sh
  - ../../ai-engineering/assets/skills-spec.md
  - ../reference/status.md
---

# 项目级 AI 上下文中间层（实测校准版）

> 一句话：**技能只写一份，放在项目层；每个工具通过自己的目录"看见"它。** —— 这件事项目**已经在做**，本方案是把它从"1 工具 1 技能"扩到"多工具、按需注册"。

## 一、目标（用户原话拆解）

> 「项目是个体，但是我可以用 qoder / codex / claude code，包括现在 DSH 打开，开发；所以我需要一个中间层……我希望是项目级别的 AI 上下文在项目层，不和所用的工具有太多的深度绑定，但是每个工具也可以读取项目 AI 上下文的内容，包括 skill/subagent、脚本等工具」

四条硬要求：① 真相源在项目层；② 不与工具深度绑定；③ 每个工具都能读到；④ 覆盖 背景契约 + skill + subagent + 脚本。

## 二、现状：机制已存在，覆盖不足 ★本版最大修正

上一版 RFC 的判断（"技能是断点、工具看不见"）**是错的**。项目里已有一套中间层机制在运行：

| 已有资产 | 作用 | 现状参数 |
|:--|:--|:--|
| `scripts/link-skills.sh` | 把 `ai-engineering/skills/<name>.md` **相对软链**到工具目录；`--check` 只检不写 | `TARGETS=(".dsh/skills")`、`REGISTER=(learn-digest)` ⇒ **1 工具 / 1 技能** |
| `.gitignore`（第 14–21 行） | 整目录忽略 `.dsh/` `.claude/` `.agents/` | 注释已写明"技能注册是本机状态而非仓库资产，真相源在 `ai-engineering/`" |
| `guard-tools.sh` **T4** | 扫描 `$ROOT` 与 `$HOME` 下的 `.dsh/skills`、`.claude/skills`、`.agents/skills`，**按软链真身是否指向本仓库 `ai-engineering/` 判定** | 6 个位置已覆盖，不写死工具名 |
| `guard-tools.sh` T5 | 工具侧上下文注入：若 `.claude/settings.json` 存在，检查是否显式引用 `AGENTS.md` | 已有 |

**现有设计里有一条重要的成本纪律**（`link-skills.sh` 注释原文）：16 个技能包中**只有"用户一句话就能直触发"的才进工具 catalog**；12 个审查官是**流程内触发**（`process/review.md` 按下表派发），全量注册会常驻会话上下文，并可能"在你随口改一行代码时自动派 8+1 官全量走查"——那是全项目最贵的 AI 流程。

> **本方案必须继承这条纪律。** 上一版"16 个技能全部重排 + 全部适配"的写法违背了它，是本版要修掉的第二处。

## 三、DSH 实测结论（2026-10-03 三探针，当场生效/失效）

| 探针 | 位置 | 结果 |
|:--|:--|:--|
| A `dsh-probe-root` | `<工作区根>/skills/<name>/SKILL.md` | ❌ **未出现** |
| B `dsh-probe-agents` | `.agents/skills/<name>/SKILL.md` | ✅ **出现** |
| C `dsh-probe-dir` | `.dsh/skills/<name>/SKILL.md`（**目录布局**） | ✅ **出现** |
| 对照 `learn-digest` | `.dsh/skills/learn-digest.md`（**扁平软链**） | ✅ 一直在 |

三条结论（一手，实测）：

1. **`.dsh/skills/` 认两种布局**——扁平 `x.md`（软链）与目录 `x/SKILL.md` 都进清单。
2. **`.agents/skills/` 生效**（行业事实约定目录，DSH 认）。
3. **工作区根 `skills/` 对 DSH 不生效** —— 原 F7 里"`skillsRoot = resolve(workdir,'skills')`"的推测**被证伪**（至少在当前版本/工作区形态下）。**Qoder 的项目级技能目录也不是根 `skills/`，而是 `.qoder/skills/`**（官方文档 + 本机实测双重确认）——根 `skills/` 只对 OpenClaw 那类工具有效，本仓库已把它列为**预留出口**。
4. **附带发现：技能清单是热更新**——文件增删后**当前会话立即反映**，无需重启。这修正了上一版"必须新开会话才能验证"的说法，也让验收变得当场可做。

> 探针已按承诺清理（`skills/`、`.agents/`、`.dsh/skills/dsh-probe-dir/` 全删，`.git/info/exclude` 复原，`learn-digest` 正式注册完好，技能清单实时回到原状）。

### Qoder 实测（2026-10-03，**JetBrains 插件形态**）

用户确认为 **IntelliJ IDEA + Qoder 插件**（不是 Qoder IDE 独立应用、不是 CLI）。两个探针验证（`.qoder/skills/qoder-probe-dotqoder/` 真实目录、`.qoder/agents/qoder-probe-agent-a.md`，均已清理）：

| 验证项 | 结果 |
|:--|:--|
| 项目级技能路径 | ✅ **`.qoder/skills/<name>/SKILL.md`** —— 官方 CLI 与 IDE 文档一致；**不是**项目根 `skills/`（Qoder 故障排查文档里的 `skills/` 是省略 `.qoder/` 前缀的简写） |
| **是否跟随软链** | ✅ **跟随** —— `learn-digest` 是**软链目录**，照样出现在插件的 `/` 技能列表里。**这条最要紧：整套「一份真相源 + 软链出口」在 Qoder 上成立** |
| 子代理目录 | ✅ **`.qoder/agents/<name>.md`** 被识别（md + YAML，正文＝系统提示词） |

**附带发现**：Qoder 官方**插件**文档只写了 `.qoder/rules` + MCP 两种定制机制，**未提** Skills/Subagents —— 但实测插件确实识别，**文档滞后于实现**。

### Codex 实测（2026-10-03）

两个探针（`.agents/skills/codex-probe-skill/` + `.codex/agents/codex-probe-agent.toml`，均已清理）：

| 验证项 | 结果 |
|:--|:--|
| 项目级技能路径 | ✅ **`.agents/skills/<name>/SKILL.md`** —— 与 DSH **共用同一出口**，⇒ **Codex 零新增出口** |
| **是否跟随软链** | ✅ **跟随**（`learn-digest` 软链目录同样被识别） |
| 子代理契约 | ✅ **`.codex/agents/<name>.toml`** 可用 —— 该契约原本**只有二手来源**（OpenAI 开发者站对本机 403），探针证实其正确 |

**至此三个主力工具（DSH / Qoder / Codex）的技能层全部实测通过，且都跟随软链。**

## 四、各工具契约表

**已核实**（一手官方文档 / 实测）：

| 工具 | 技能目录 | 布局要求 | 目录软链 | subagent | 来源 |
|:--|:--|:--|:--|:--|:--|
| **DSH** | `.dsh/skills/`、`.agents/skills/` | **两种都认**（扁平 `x.md` 与 `x/SKILL.md`） | 扁平文件软链 ✅（现网在用） | 待核实 | **实测**（§三） |
| **Claude Code** | `.claude/skills/<name>/SKILL.md`（另有 personal / `--add-dir`） | **目录布局**；frontmatter 兼容标准 + Claude 扩展字段 | ✅ **官方明确支持**（多位置指向同一目标只加载一次） | `.claude/agents/` | 官方 skills 文档 |
| **Qoder** | **`.qoder/skills/<name>/SKILL.md`**（项目级）；用户级 `~/.qoder/skills/` | 目录布局；frontmatter `name`（≤64，小写/数字/连字符）+ `description`（≤1024）；`/skills reload` 重载 | ✅ **实测跟随软链**（JetBrains 插件） | **`.qoder/agents/<name>.md`**（md+YAML，正文＝系统提示词）✅ 实测识别 | Qoder 官方 CLI+IDE 文档 + 本机实测 |
| **Codex** | **`.agents/skills/<name>/SKILL.md`**（项目级，**与 DSH 共用**）；用户级 `~/.codex/skills/` | 目录布局 | ✅ **实测跟随软链** | **`.codex/agents/<name>.toml`**（**TOML**：`name`/`description`/`developer_instructions` 必填；`nickname_candidates`/`model`/`model_reasoning_effort`/`sandbox_mode`/`mcp_servers` 可选）✅ 实测可用 | 本机实测（契约原为二手来源，探针已证实） |
| **Cursor** | `.cursor/rules/*.mdc`（**纯 `.md` 被忽略**）；`AGENTS.md` 为官方第四类规则 | 自家 frontmatter | — | — | cursor.com/docs/rules |

**待核实**：Gemini CLI、GitHub Copilot（两者在 Agent Skills 46 客户端名单与 Vercel 79 家 agent 表内）。**路径写错比留空危害更大，故留空待核。**（**Codex 已于 2026-10-03 实测确认**，见上表。）

**两条硬约束**：

1. **没有任何单一目录能被所有工具读到**——Claude Code 官方明确**不读 `.agents/`**；DSH 不读根 `skills/`；Qoder 只认 `.qoder/skills/`。**押注单一中立目录这条路不存在。**
2. **主流要求正在收敛到"目录布局 `<name>/SKILL.md`"**（Claude Code、Qoder 都要求；DSH 两种都行）。故目录化是"一份真相源喂多工具"的必要条件。

## 五、方案：扩展现有机制（最小改动）

### 5.1 真相源：维持位置，改为官方目录布局

```
现状：ai-engineering/skills/learn-digest.md
改为：ai-engineering/skills/learn-digest/SKILL.md
（roles/ 12 个同理，分批）
```

理由：Claude Code 与 Qoder **只认目录布局**；DSH 两种都认 ⇒ 目录布局是唯一能同时喂三家的形态。保持 `ai-engineering/` 作为唯一真相源不变（治理体系、frontmatter 契约、guard 覆盖都在这里）。

### 5.2 出口：扩展 `link-skills.sh` 的 `TARGETS`

| 出口 | 给谁 | 方式 |
|:--|:--|:--|
| `.dsh/skills/<name>` | DSH | 目录软链（或维持扁平文件软链——两种都认） |
| `.agents/skills/<name>` | DSH · Codex · Cursor · Gemini CLI · GitHub Copilot · OpenCode 等（Vercel 79 家 agent 表里的**最大公约数**） | 目录软链 |
| `.claude/skills/<name>` | Claude Code | 目录软链（官方明确支持） |
| `.qoder/skills/<name>` | **Qoder**（CLI / IDE / JetBrains 插件） | 目录软链 |

`TARGETS` 从 1 项扩到 4 项；`REGISTER` 保持"只放直触发技能"的纪律（新增直触发技能时才加名字）。

### 5.3 门禁

- **T4 已覆盖**多工具多位置的真身判定，无需改判据；扩出口后自动纳入检查。
- **新增布局校验**（并入 RFC 20261003-skill-conformance 的 `guard-skills.sh`）：技能必须是 `<name>/SKILL.md`；**目录名 == frontmatter `name`**；目录内无游离文件。
- **防第二真相源**：出口必须是**软链**且指向本仓库；发现副本 → FAIL（T4 的真身判定已天然覆盖这一点）。

## 六、迁移（分批，先做 1 个验证闭环）

| 批 | 内容 | 说明 |
|:--|:--|:--|
| **批 1** | 只迁 `learn-digest` 一个技能 → `ai-engineering/skills/learn-digest/SKILL.md`，扩 `TARGETS` 到 4 个出口，跑通 | 最小闭环；**可当场验证**（热更新）|
| 批 2 | 其余直触发技能目录化 | 按需 |
| 批 3 | `roles/` 12 个审查官目录化 | **不注册到工具出口**（成本纪律），仅在项目内保持布局一致 |

**迁移的真正风险是引用**：技能路径散布全仓（技能互相的 `depends-on`/`related`、`_index.md` 清单、`AGENTS.md` 规则表、process / checklists / workflow 的引用）。**动手前必须先产出「受影响文件清单」**——本项目已有"幽灵引用"先例（RFC 20260913 被 6 个文件 12 处引用，而实体文件不存在）。

回滚：目录化单独成批，`git revert` 即可；出口是软链且不在 git 内，重跑 `link-skills.sh` 重建。

## 七、验收（热更新 ⇒ 当场可验）

1. `bash scripts/link-skills.sh --check` 全绿；`guard-tools.sh` 的 T4 把新出口全部识别为指向本仓库。
2. `guard-meta` / `guard-feature` / `guard-skills` PASS，反例逐条触发。
3. **当场验证 DSH**：批 1 落地后，`learn-digest` 应仍在技能清单里（布局变了但发现机制不变）；**无需重开会话**。
4. **Claude Code 实测**：`.claude/skills/<name>` 目录软链能否被识别（官方承诺，仍建议实跑一次）。
5. 零内容改动：技能正文与 frontmatter 字段值一个字节不改（只改位置）。

## 八、明确不做

- **不预造 `agents/`**：当前无独立子代理需求；审查官继续"流程内触发"，不注册成工具级 subagent（成本纪律）。
- **不注册全部 16 个技能到工具出口**——只注册直触发技能。
- 不把真相源放进任何工具目录。
- 不改技能正文内容、不动 `data/` 与产品代码。

## 九、待拍板

| # | 决策 | 建议 |
|:--|:--|:--|
| 1 | 是否按"扩展现有 `link-skills.sh`"推进（而非新建机制） | **是**——避免重复造轮子 |
| 2 | 真相源是否目录化 | **是**（Claude Code / Qoder 只认目录布局） |
| 3 | 出口清单 | **4 个**：`.dsh/skills`、`.agents/skills`、`.claude/skills`、`skills/`（后者只服务 Qoder） |
| 4 | 是否先做批 1 最小闭环 | **是**（1 个技能 + 4 出口，当场可验） |
| 5 | Gemini / Copilot 的目录 | 用到时再核实补齐（不猜）；**Codex 已实测 ✅** |

---

## 十、落地记录

### 批 1 ✅（2026-10-03，用户「做」）

| 项 | 结果 |
|:--|:--|
| 技能迁移 | `ai-engineering/skills/learn-digest.md` → **`learn-digest/SKILL.md`**（`git mv`，官方目录布局；frontmatter 相对路径 +1 层） |
| 出口 | `scripts/link-skills.sh` 的 `TARGETS` **1 → 4**：`.dsh/skills` · `.agents/skills` · `.claude/skills` · `.qoder/skills`（Qoder CLI/IDE/JetBrains 插件）；自动清理上一代扁平软链 + 目标非软链时拒绝覆盖 |
| `.gitignore` | 新增**锚定根** `/skills/`（关键：写成 `skills/` 会连真相源 `ai-engineering/skills/` 一起忽略） |
| 守卫修复 | `guard-meta` 收集 glob 不递归 → 新布局**完全漏检（假绿）**（已修）· `guard-tools` T3 同因（已修，16 个）· `guard-meta` M1/M3 新增 `supersededBy` 且**归一化标量值**（已修） |
| 死链 | `docs/rfc/20260829-learn-plugin.md` 的 `supersededBy` 就地修正（迁移造成） |
| 文档跟随 | `assets/skills-spec.md` · `guides/skills-usage.md` · `guides/development.md` · `AGENTS.md` |
| 验证 | `link-skills.sh --check` 全绿 · `guard-meta` **PASS**（175 files）· `guard-feature` **PASS** · `guard-tools` **10 通过 / 0 警告 / 0 失败**（T3 **16 个** · T4 **4 出口**）· T6 shell-lint PASS · **DSH 技能清单当场验证**（迁移瞬间消失、重建软链后恢复） |
| 沉淀 | `assets/pitfalls.md` 第二十三章（5 条）· `docs/reference/change-log.md` 顶部本批 |

### 未做（留给后续批）

- `roles/` 12 个审查官**仍扁平**（批 3；且按成本纪律**不注册**到工具出口）。
- `guard-skills.sh`（布局合规校验）属 RFC 20261003-skill-conformance，未建。
- Gemini CLI / GitHub Copilot 的技能目录**待核**（**Codex 已实测 ✅**；路径写错比留空危害更大）。

---

**追加方式**：§四 表随核实与实测更新；出口清单随新工具接入扩展。本文件状态由 `draft` → `approved` 须经用户点头。
