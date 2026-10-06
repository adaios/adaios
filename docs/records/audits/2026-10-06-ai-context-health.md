---
title: AI 上下文体系体检报告（首次实跑 · D-20261006-07）
description: ai-context-health-reviewer 首次实跑——机械层照抄（health 75/100 · structure PASS · meta 2 FAIL · tools 12-0-0）+ H1–H8 叙事层体检（13 条待修，无 P0/P1）；重点 H2 文档⇄行为 · H4 跨目录双源 · H6 结构断裂 · H7 死胡同与孤儿；只报告不改，每条带文件:行 + 可复现命令
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 224
depends-on: []
related:
  - ../../../.agents/toolkit/roles/ai-context-health-reviewer.md
  - ../../../.agents/toolkit/checklists/ai-context-health-reviewer.md
  - ../../../.agents/rules/assets/ai-context-engineering.md
tags: [audit, ai-context, health]
---

# AI 上下文体系体检报告（2026-10-06 · 首次实跑）

> **触发**：派单 `D-20261006-07`（`AGENTS.md` 规则 12「体检」的叙事层）；执行者 `ai-context-health-reviewer`。
> **纪律**：**只报告不改**（B7）· 每条带 `文件:行` + 可复现命令 · 猜的不写 · **不重写已有判据**（机械层照抄）。
> **复现环境**：`/Users/adai/Projects/adaios` @ 2026-10-06 14:39–14:52。

## 一、机械层结论（照抄，不解读）

| 守卫 | 本单实测 | 与派单材料的差异 |
|:--|:--|:--|
| `ai-guard-health.sh --full` | **75 / 100** —— ①结构 ✅ ②元数据 ❌(2 FAIL) ③命名 ✅ ④体积 ✅ ⑤契约 ✅ ⑥新鲜度 ✅ | 派单写「100/100」 |
| `ai-guard-structure.sh` | **PASS**（29 个子目录 · 两件套齐备 · 清单⇄实际一致 · 依赖与守卫引用有效 · 两件套不重复） | 一致 |
| `ai-guard-meta.sh` | **2 FAIL**（同一文件） | 派单写「PASS 285 文件」 |
| `ai-guard-tools.sh` | **12 通过 / 0 警告 / 0 失败**（T1–T8） | 一致（派单「12-0-0」） |
| `ai-guard-skills.sh`（补跑） | **PASS**（20 个技能包 · S3/S4/S5/S7） | — |

两条 FAIL 逐字：

```
M2 .agents/workspace/_meta/inbox.md: lines 声明 57 != 实际 66
M4 .agents/workspace/_meta/inbox.md: 正文路径引用不存在 docs/records/audits/2026-10-06-ai-context-health.md
```

> **差异归因**（不是守卫退化，也不是派单写错）：① `inbox.md` 在派单之后被继续追加「已完成」条目，`lines:` 未同步 ⇒ M2；② 派单正文指向**本次要交付的报告**（当时尚未落盘）⇒ M4。**本报告落盘后 M4 自动消解**；M2 见待修 P3-13。
> **体系含义**：机械层结论是**时点快照**——「主链已跑 100/100」与两小时后实测 75/100 **可以同时为真**，因为派单文件自身仍在被编辑。

## 二、H1–H8 逐条

### H1 总纲 ⇄ 实际 —— ⚠️ 待修（8 处）

**✅ 已对（README 口径，S6 覆盖范围内）**：`rfc` 67 ✓ · `guards` 15 ✓ · 角色 16 ✓ · 顶层 7 ✓（口径见 H6-a）。

```bash
ls .agents/direction/rfc/*.md | grep -v '_index\|_directory' | wc -l   # 67
ls .agents/mechanism/guards/*.sh | wc -l                               # 15
ls .agents/toolkit/roles/*.md | grep -v '_index\|_directory' | wc -l   # 16
```

**❌ 未对（全在 `ai-context-engineering.md` / `ai-context-layer-spec.md` 内部——S6 只断言「顶层 / 角色」两类，其余不在范围，故守卫全绿）**：

| # | 位置 | 写了 | 实测 | 命令 |
|:--:|:--|:--|:--|:--|
| 1 | `ai-context-engineering.md:86` | RFC **66** 份 | **67** | 见上 |
| 2 | `ai-context-engineering.md:108` | 守卫 **14** | **15** | 见上 |
| 3 | `ai-context-engineering.md:110` | 检查清单 **14 份** | **17** | `ls .agents/toolkit/checklists/*.md \| grep -v '_index\|_directory' \| wc -l` |
| 4 | `ai-context-engineering.md:65` `:393` | 受管子目录 **19**（「20 子目录」） | **29** | `find .agents -name _directory.md \| wc -l` → 30（含根） |
| 5 | `ai-context-engineering.md:98` `:99` | 路径 `reference/*.md`；手册 **6 份** | 实际分子目录；手册 **4 份** | `ls .agents/knowledge/reference/{contracts,designs,manuals}/*.md \| grep -v '_index\|_directory' \| wc -l` → **16** |
| 6 | `ai-context-engineering.md:367` | `ai-guard-tools` **T1–T7** | **T1–T8** | `bash .agents/mechanism/guards/ai-guard-tools.sh` |
| 7 | `ai-context-engineering.md:106` + `AGENTS.md:54` | 技能「**官方目录布局**」 | 1 目录 + **3 扁平** | `ls -la .agents/toolkit/skills/` |
| 8 | `ai-context-layer-spec.md:120` | 审查官注册 **12/12** | **16/16** | `ls .qoder/agents/*.md \| wc -l` → 16 |

### H2 文档 ⇄ 行为 —— ⚠️ 待修（4 处）

**H2-1（最重）角色布局：总纲**内部相反****

- `AGENTS.md:52`：「**审查角色（16 个）**……均封装为 **SKILL.md 技能包**（触发/步骤/约束/输出/参考 五段）」
- 实测：`ls -d .agents/toolkit/roles/*/` → **No such file**；`ls .agents/toolkit/roles/SKILL.md` → **No such file**；16 个角色**全部是扁平** `<name>.md`。
- 同体系另外两份写的是**扁平**：`ai-context-engineering.md:107`「审查官（**扁平** `<name>.md`＝ subagent 真相源）」· `ai-context-layer-spec.md:58`「审查官……（**扁平**）」· `:158`「角色**刻意保持扁平**」。
- ⇒ `AGENTS.md:52` 是 2026-10-03 目录化迁移**之前**的旧口径，与实测、与同体系规范**三方相反**。

**H2-2 技能布局：规范要求 ⇄ 守卫判据冲突，且缺「偏离在案」**

- 规范：`ai-context-layer-spec.md:57` `:80`「技能真相源**必须用目录布局** `<name>/SKILL.md`」；`AGENTS.md:54` 同。
- 实测：`.agents/toolkit/skills/` 下 `code-api-writer.md` · `code-domain-writer.md` · `task-ship.md` 为**扁平**，仅 `data-learn-writer/` 是目录（`ls -la .agents/toolkit/skills/`）。
- 守卫：`ai-guard-skills` **PASS**；`ai-guard-tools` T3 明确「**两种布局都算**」⇒ **守卫接受扁平、规范要求目录**，两者判据不一致。
- `ai-context-layer-spec.md:132` 自己规定「有意的偏离**必须留痕**（写进本规范 + `pitfalls.md`）」——查无此留痕。

**H2-3 pre-commit 层数：三处口径不一**

- `.githooks/pre-commit:2` 自称「**六层**」（其下列 9 条）；
- `ai-context-engineering.md:278` `:327` 称「pre-commit **11 层**」；
- 实测标号检查块 **10 个**：`grep -n '^# [0-9]' .githooks/pre-commit` 输出 11 行，其中 `:196` 是续行注释误匹配；且其中两块**都编号 `0)`**（`:24` 范围守卫、`:72` 隐私闸门）。
- **正面参照**：`ai-context-layer-spec.md:171` 已用「（**多层，数字不写死**）」——**这就是本项建议的修法**。

**H2-4 `ai-engineering/` 已不存在仍被当路径引用**——详见 H7-1。

**✅ 通过项（实测确认在跑）**：技能出口 4 个软链实物齐备（T4）· T8 出口清单与 `lib/ai-export-targets.sh` 一致 · 「知识回流需人确认」在 `ai-context-engineering.md:341` `:369` 诚实标 ❌ 待建 · **规则 0c 收件箱通道有效（本单即由它领活）** · `ai-guard-context.sh` 无 `--topic`（`AGENTS.md:64` 已修正标注）· `ai-guard-health --full/--json/--fix` 参数真实存在。

### H3 交叉一致 —— ⚠️ 待修（3 处残留）

**✅ 已一致**：`README.md:44` `:63` · `AGENTS.md:52` · `ai-context-engineering.md:323` · `ai-context-layer-spec.md:158` · `review-driven.md:23` 的「**16 个角色**」全线一致；`review-driven.md:30`「审核者 **14**」= 需求评审 1 + 域客观 8 + 对抗 1 + 外部 3 + 体检 1 ✓。

**❌ 残留（旧口径未清）**：

| 位置 | 写了 | 现役口径 |
|:--|:--|:--|
| `.agents/rules/guides/worktree-workflow.md:41` | 「Qoder/Codex 连 **12 个审查官** 都没有」 | 16 个角色（审核者 14） |
| `.agents/rules/process/review-driven.md:154` | 「**8+3 个审查官** 判产物对不对」 | **同文件 `:30` 自己已定义为审核者 14** |
| `.agents/toolkit/roles/process-reviewer.md:21` | 「**8+3 个审查官（QC）**」 | 同上 |

（`change-log.md` · `pitfalls.md:348` · `rfc/20261003-*` 里的「12」属**历史账本与决策记录**，按 `M4_SKIP` 立意**不算残留**。）

### H4 跨目录双源 —— ⚠️ 待修（本轮重点：2 组真双源 + 1 组正确范式）

> 判据：**一处详述、其余指针**。`S5` 只查两件套**内部**的整行重复——**跨目录的没人查**。

**H4-1 目录职责表双源**：`.agents/README.md:33-45`（顶层表）与 `ai-context-engineering.md:63-113`（资产清单）**都在逐条详述「哪个目录装什么」**，且**已实际漂移**（同一事实两处不同）：

| 事实 | `README.md` | `ai-context-engineering.md` |
|:--|:--|:--|
| rfc 数 | `:39` **67** | `:86` **66** |
| guards 数 | `:45` `:57` **15** | `:108` **14** |
| 检查清单数 | — | `:110` **14**（实际 17） |
| 子目录数 | — | `:65` `:393` **19 / 20**（实际 29） |

**H4-2 守卫清单双源**：`AGENTS.md:59-73`（逐条说明 + 参数）与 `ai-context-engineering.md:102-113`（工具层表）都在详述守卫；已漂移（**T1–T8 vs T1–T7**；层数「多层」vs「11」）。`README.md:57` 是第三处简表。

**H4-3 正确范式（照抄即可）**：出口契约——`ai-context-layer-spec.md:63-83` 是**人读镜像**，`:73` 明写「机器可读版是唯一真相源」（`lib/ai-export-targets.sh`），并由 `ai-guard-tools` **T8 对拍**两者口径。⇒ **H4-1/H4-2 的修法即照此办理**。

### H5 滞后 —— ⚠️ 待修（3 处）

| 位置 | 症状 | 证据 |
|:--|:--|:--|
| `ai-context-engineering.md:6` | `updated: 2026-10-06`，内容却含 10-03/10-04 的数字（66 RFC / 14 guards / 12 审查官 / 19 受管目录）⇒ **`updated` 字段失真**（改了文件没改内容） | 同 H1 表 |
| `docs/records/_index.md:6` `:22` | `updated: 2026-10-04`；写 audits「**27 份**」，实测 **28 份**（本报告落盘后 29） | `ls docs/records/audits/*.md \| wc -l` |
| `.agents/workspace/_meta/inbox.md:8` | `updated: 2026-10-06`，`lines: 57` 而实际 **66**（M2 FAIL，同机械层） | `wc -l .agents/workspace/_meta/inbox.md` |

> ⚠️ `ai-context-engineering.md:401` **自己已记过这条坑**（「本文件天生易滞后……任何目录变动都必须同步它」）——风险**已被识别，但缺机械保障**（S6 只对拍顶层/角色两类）。

### H6 结构断裂 —— ⚠️ 轻（2 处；上次 4 项已清零）

**三份总纲已逐行完整读过**（`README.md` 84 行 · `AGENTS.md` 86 行 · `ai-context-engineering.md` 408 行）。上次报告的「两套结构并存 / 孤立残行 / 丢标题的表」**均已修复、无残留**。剩 2 处叙述瑕疵：

- **H6-a `README.md:33` vs `:50`**：`:33` 标题「顶层结构（**7** 个顶层）」，而 `ls -d .agents/*/` 实见 **8** 个（多出口位 `.agents/skills/`，`.gitignore:35` 忽略）；`:50` 表标题是「**顶层内的**关键子目录」，表内却混入**顶层** `skills/`（`:65`）与跨顶层子目录（`deployment/` `process/` `reference/`…），读者无法从表判断归属。⇒ 建议加「所属顶层」列 + 一句出口位说明。
- **H6-b `ai-context-engineering.md` 状态三口径**：`:3` description「机制部分**已生效**」· `:22`「**状态**：方案稿（`status: active`），**待拍板**」· `:390`「**已落地**」。

### H7 死胡同与孤儿 —— ⚠️ 待修（本轮重点：4 条，**全部是 M4 盲区**）

> `M4` 只认 `.agents/` / `docs/` 开头的形态——**缩写路径与无扩展名目录是它的盲区**。

**H7-1 `ai-engineering/` 死胡同（真断链）**：目录**已不存在**（2026-10-03 迁移批收进 `.agents/`），仍有 4 处引用：

| 位置 | 用法 |
|:--|:--|
| `AGENTS.md:37` | 规则 7 正文，当**现役目录**举例（「`ai-engineering/` 协作规范与流程」） |
| `ai-context-layer-spec.md:60` | §三 资产清单表格「脚本与门禁 \| `ai-engineering/*.sh`」——**当资产位置写** |
| `ai-context-layer-spec.md:28` `:81` | 叙述性（弱发现举例 / 历史决策） |

```bash
ls -d ai-engineering        # ls: ai-engineering: No such file or directory
grep -rn 'ai-engineering/' AGENTS.md .agents/rules/assets/ai-context-layer-spec.md
```

M4 漏检原因：既不以 `.agents/` / `docs/` 开头，也不含扩展名。

**H7-2 索引点名了不存在的文件**：`docs/records/_index.md:27` 列 `audits/_index.md`（存档清单）——**该文件不存在**：

```bash
ls docs/records/audits/_index.md   # No such file or directory
```

本区 `M4` **整体豁免**（`docs/records/_index.md:47`；`ai-guard-meta.sh:198` 的 `M4_SKIP` 含 `docs/records/`）⇒ **无人检查**。豁免立意是「存档含『当时』的路径属正常」，与「索引指向的文件不存在」不同，故仍报。

**H7-3 路径形态不实**：`ai-context-engineering.md:98` `:99` 写 `.agents/knowledge/reference/*.md`——实际 `reference/` 下**只有 `status.md`**，其余 16 份在 `contracts/`(2) · `designs/`(10) · `manuals/`(4)；且 `:99` 名单里的 `framework-plugin-gap` 现名 `designs/framework-plus-plugin-model.md`（原文件已不存在）。

**H7-4 技能真相源形态不实**：`AGENTS.md:54` 写 `.agents/toolkit/skills/<name>/SKILL.md`——实际 3/4 为扁平 `.md`（同 H2-2）。

**孤儿抽验（结论：无新增孤儿）**：

- `toolkit/checklists/code-perf-reviewer.md` **不是孤儿**——被 `rules/process/review.md:122` `:125` · `roles/code-frontend-reviewer.md:13` `:25` `:51` · `checklists/_index.md:28` 引用 ✓。
- 但 **`AGENTS.md:57`「与 16 个角色**一一对应**」口径不成立**：清单 17 份 = 15 份角色同名清单 + `code-perf-reviewer`（前端官 `/perf` 专项，无同名角色）+ `ai-cost-checklist` + `ai-guard-checklist`；而角色中 **`ai-adversarial-reviewer` 与 `docs-design-writer` 无同名清单**。

### H8 可执行性 —— ✅ 通过（本轮实跑）

| 文档里的命令 | 实测 |
|:--|:--|
| `bash -n` 全部 shell（guards 15 + scripts + `lib/` + `.githooks/pre-commit`） | **0 语法错误** |
| `ai-guard-health.sh --full` / `--json` / `--fix` | 参数真实存在；`--full` 实跑 75/100 |
| `ai-guard-release.sh --json` | 参数存在 |
| `task-cadence.sh` 子命令 | **8 个全在**：`daily` `ship` `weekly` `todo` `release` `check` `cost` `mark`（脚本 `:450-457`） |
| `python3 .agents/mechanism/scripts/ai-domain-view.py overview` | 实跑正常（37 功能 · 未修 P2 16 · 在制品 40 份） |
| 引用的脚本真身 | `ai-worktree-prep.sh` · `ai-link-skills.sh` · `ai-sync-agents.sh` · `task-weekly-audit.sh` · `code-deploy-gate.sh` · `lib/release-units.sh` **全部存在** |

**唯一 ❌**：文档内**层数类数字**不可验证（H2-3）——归 H1/H3，不在 H8 重复计。

## 三、待修清单（P0–P3）

> **无 P0 / 无 P1**（全是叙事层，不涉及数据丢失、错误行为或对外承诺）。

| 级别 | # | 症状 | 根因 | 落点 | 建议改法 |
|:--:|:--:|:--|:--|:--|:--|
| **P2** | 1 | H1 的 8 处数字/路径不实 | 总纲手工维护 + S6 只对拍「顶层/角色」 | `.agents/rules/assets/ai-context-engineering.md:65` `:86` `:98` `:99` `:106` `:108` `:110` `:367` `:393` | 逐个改实测值，**并把 rfc/guards/清单/子目录/手册 纳入 S6 的 TRUTH 断言**（否则必复发） |
| **P2** | 2 | `AGENTS.md:52` 说角色「均封装为 SKILL.md 技能包」 | 10-03 迁移前旧口径未随迁移更新 | `AGENTS.md:52` | 改「**扁平** `<name>.md`（subagent 真相源），五段结构写在文件内」——与 `ai-context-engineering.md:107` 对齐 |
| **P2** | 3 | 技能布局：规范要目录布局 / 实际 3/4 扁平 / 守卫接受两种 | 规范（`:80`）与守卫判据（T3「两种布局都算」）未同步，偏离未留痕 | `ai-context-layer-spec.md:57` `:80` · `AGENTS.md:54` | **⏳ 待拍板**：① 承认两种布局（改规范 + 按 `:132` 补「偏离在案」）——成本小；② 迁移 3 个技能到 `<name>/SKILL.md`——成本中（44 处引用） |
| **P2** | 4 | `ai-engineering/` 4 处死胡同引用 | 10-03 迁移批引用替换收尾不彻底 | `AGENTS.md:37` · `ai-context-layer-spec.md:28` `:60` `:81` | `AGENTS.md:37` 删该例（或改 `.agents/`）；`:60` 表内改 `.agents/mechanism/scripts/` |
| **P2** | 5 | H4-1/H4-2 跨目录双源（职责表 / 守卫表）且已漂移 | 同一知识两处详述，无机器对拍 | `README.md:33-45` ⇄ `ai-context-engineering.md:63-113`；`AGENTS.md:59-73` ⇄ `:102-113` | 照 `ai-context-layer-spec.md:73` 范式：**定一处为唯一真相源**，另一处只留指针 |
| **P2** | 6 | `ai-context-layer-spec.md:120`「审查官全量注册（12/12）」 | 角色 12→16 后未更新 | 同上 `:120` | 改 **16/16**（或去掉硬数字，写「全部角色」） |
| **P3** | 7 | H3 三处旧口径残留（12 个 / 8+3 个） | 加角色后未全域回扫 | `worktree-workflow.md:41` · `review-driven.md:154` · `process-reviewer.md:21` | 前两处改「16 个角色 / 审核者 14」；末处改「审核者 14」 |
| **P3** | 8 | pre-commit 层数三口径（六层 / 11 层 / 实测 10 块） | 层数硬编码在文档，门禁改造后未回改 | `.githooks/pre-commit:2` · `ai-context-engineering.md:278` `:327` | 统一为 `ai-context-layer-spec.md:171` 的「**多层，数字不写死**」；顺手把重复编号的两块（`:24` `:72`）改为 `0` / `0a` |
| **P3** | 9 | `docs/records/_index.md:27` 指向不存在的 `audits/_index.md`；`:22`「27 份」 | 存档区豁免 M4，无人检查 | `docs/records/_index.md:22` `:27` | 删该行（或补建）；份数改实测值 |
| **P3** | 10 | 检查清单「与 16 个角色一一对应」不成立 | 新增/专项清单后口径未修 | `AGENTS.md:57` · `README.md:44` · `ai-context-engineering.md:110` | 改「大部分一一对应 + 2 份专项清单」 |
| **P3** | 11 | `ai-context-engineering.md` 状态三口径（已生效 / 待拍板 / 已落地） | 方案稿转正时未收口 | 同上 `:3` `:22` `:390` | 二选一：正式稿（删「方案稿/待拍板」）或明确「哪部分待拍板」 |
| **P3** | 12 | `README.md:33`「7 个顶层」vs `ls` 见 8 个（出口位） | 出口位在 gitignore，机器口径排除它，README 未说明 | `README.md:33` `:50` | 加一句「`.agents/skills/` 是出口位（gitignore），不计入顶层」；子目录表加「所属顶层」列 |
| **P3** | 13 | `inbox.md` 自身 `lines` 漂移（57 vs 66，M2 FAIL） | 派单文件被持续追加而未同步元数据 | `.agents/workspace/_meta/inbox.md:8` | `bash .agents/mechanism/guards/ai-guard-meta.sh --fix`；**本次为满足交付判据 C1 已同步** |

**已知且在案、不新增**：`docs/` 侧 4 区缺 `_directory.md`（`ai-context-engineering.md:400` 自记）· `records/state` 等本机状态不入 git（设计如此）。

## 四、一句话结论

**体系主干健康**——机械层唯一 FAIL 是**派单文件自身的元数据漂移**加一条**指向本报告的断链**；**叙事层的债集中在「总纲数字/形态靠手工维护」与「同一知识两处详述」两处**——`ai-context-engineering.md` 一份就欠 **8 处数字/路径**，而该文件**自己已预言**「天生易滞后」。**建议把可机读的数字与形态交给 S6 对拍，总纲只留指针**；否则「体检」会周期性报同一批。

---

## 附：本次**未**采信的候选（遵循「宁可报少，不可报错」）

- **缩写路径共 100+ 处**（如 `README.md:63` 的 `process/review-driven.md` 实指 `rules/process/…`）：粗略扫描命中 113 处，**逐条核对后均为成立的上下文缩写或表内目录名，无一为真断链**——这正是 `pitfalls.md:348` 记的「M4 认裸路径会报 123 处」同类；本次**不重复计入**。
- `AGENTS.md:71` 写「**cron** 每周自动审查」而实际是 **launchd**（`ai-guard-tools` T7 实测三个 LaunchAgent）：属同义泛称，**仅记此一行，不列待修**。
- `docs/records/audits/` 无两件套：该目录**不是受管目录**（无 `_directory.md` 即不在 `structure` 口径内），**非违规**。
