---
title: AI 上下文体系体检报告（复检 r2 · D-20261006-08）
description: ai-context-health-reviewer 复检 r2——机械层照抄（health 75/100 · structure PASS · meta 2 FAIL · tools 12-0-0 · skills PASS）+ 上轮 13 条逐条回归（11 清 · 2 未清/复发）+ H1–H8 复检与新发现 12 条（无 P0/P1）；重点 H2 文档⇄行为 · H4 跨目录双源 · H6 结构断裂 · H7 死胡同；只报告不改，每条带文件:行 + 可复现命令
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 216
depends-on: []
related:
  - ./2026-10-06-ai-context-health.md
  - ../../../.agents/toolkit/roles/ai-context-health-reviewer.md
  - ../../../.agents/toolkit/checklists/ai-context-health-reviewer.md
  - ../../../.agents/rules/assets/ai-context-engineering.md
tags: [audit, ai-context, health]
---

# AI 上下文体系体检报告（2026-10-06 · 复检 r2）

> **触发**：派单 `D-20261006-08`（`AGENTS.md` 规则 12「体检」的叙事层）；执行者 `ai-context-health-reviewer`。
> **两个目的**：① 验证上轮 13 条（`2598ba5c` · `c01d2b4e` · `38a9f8c5`）**真的清零** ② 找出三轮修复**新引入**的问题。
> **纪律**：只报告不改（B7）· 每条带 `文件:行` + 可复现命令 · 猜的不写 · **不重写已有判据**（机械层照抄）· 宁可报少不可报错。
> **复现环境**：`/Users/adai/Projects/adaios` @ 2026-10-06 15:01–15:30（`git log -1` = `38a9f8c5`）。

## 一、机械层结论（照抄，不解读）

| 守卫 | 本单实测 | 与派单材料的差异 |
|:--|:--|:--|
| `ai-guard-health.sh --full` | **75 / 100** —— ①结构 ✅ ②元数据 ❌(2 FAIL) ③命名 ✅ ④体积 ✅ ⑤契约 ✅ ⑥新鲜度 ✅ | 派单写「100/100」 |
| `ai-guard-structure.sh` | **PASS**（29 个子目录 · 两件套齐备 · 清单⇄实际一致 · 依赖与守卫引用有效 · 两件套不重复） | 一致 |
| `ai-guard-meta.sh` | **2 FAIL**（同一文件：`inbox.md`） | 派单写「PASS 285」 |
| `ai-guard-tools.sh` | **12 通过 / 0 警告 / 0 失败**（T1–T8） | 一致 |
| `ai-guard-skills.sh` | **PASS**（20 个技能包 · S3/S4/S5/S7） | 一致 |

两条 FAIL 逐字：

```
M2 .agents/workspace/_meta/inbox.md: lines 声明 69 != 实际 82
M4 .agents/workspace/_meta/inbox.md: 正文路径引用不存在 docs/records/audits/2026-10-06-ai-context-health-r2.md
```

> **差异归因（与上轮同型，非守卫退化、也非派单写错）**：① 派单 `D-20261006-08` 是在主链跑完守卫**之后**写进 `inbox.md` 的，`lines:` 未同步 ⇒ M2；② 派单正文指向**本次要交付的报告**（截图时尚未落盘）⇒ M4，**本报告落盘后自动消解**。M2 见第三节 #12。
> **含义重申**：机械层结论是**时点快照**；「主链已跑 100/100」与此刻 75/100 **可以同时为真**——因为派单文件自身仍在被编辑。

## 二、上轮 13 条回归（逐条）

> 判据：回看**落点**是否真改 + 全域是否还有同类残留。命令一栏可逐条复现。

| # | 上轮条目 | 判定 | 证据（文件:行 + 命令） |
|:--:|:--|:--:|:--|
| P2-1 | `engineering.md` 8 处数字/路径 | ✅ **落点全清**·⚠️ 同类另 3 处见 #7 | 8/8 对：`:67` 29 受管 → `find .agents -name _directory.md \| wc -l` = 30（含根）· `:88` 67 → `ls .agents/direction/rfc/*.md \| grep -v '_index\|_directory' \| wc -l` · `:100/:101` 路径形态 + 手册 4 → `ls .agents/knowledge/reference/manuals/*.md`（除两件套）= 4 · `:108` 4 目录布局 → `ls -d .agents/toolkit/skills/*/` = 4 · `:110` 15 → `ls .agents/mechanism/guards/*.sh \| wc -l` · `:113` 17 → `ls .agents/toolkit/checklists/*.md \| grep -v '_index\|_directory' \| wc -l` · `:370` T1–T8 ✓ · `layer-spec:120` 16/16 → `ls .qoder/agents/*.md \| wc -l` = 16 |
| P2-2 | `AGENTS.md` 说角色「均封装为 SKILL.md」 | ✅ 清零 | `AGENTS.md:54` 现为「**角色为扁平 `<name>.md`**……只有技能用官方目录布局」；`ls -d .agents/toolkit/roles/*/` → No such file ✓（⚠️ 同类残留 `skills-spec.md:60` 见 #3） |
| P2-3 | 技能布局：规范要目录 / 实际 3/4 扁平 | ✅ 已按 **B 案**落地 | `ls -d .agents/toolkit/skills/*/` → code-api-writer · code-domain-writer · data-learn-writer · task-ship **4/4**（`git show c01d2b4e --stat`）。⚠️ 配套规范未同步 → #3 |
| P2-4 | `ai-engineering/` 4 处死胡同引用 | ✅ 落点全清·⚠️ 同类另 5 处见 #4 | `grep -rn 'ai-engineering/' AGENTS.md .agents/rules/assets/ai-context-layer-spec.md` → 仅剩 `:28`（历史叙述，已改成 `.agents/`）· `ls -d ai-engineering` → No such file |
| P2-5 | H4-1/H4-2 跨目录双源已漂移 | ✅ 三处指针已加·⚠️ **第三份同类表未纳入**（见 H4 / #1） | `README.md:54` · `AGENTS.md:46` · `engineering.md:65` 三处交叉指针齐备；但 `.agents/_index.md:28-35` 是**同一知识的第三份详述表**且已漂（6 vs 7） |
| P2-6 | `layer-spec:120` 12/12 | ✅ 清零 | 现为「**16/16**」；实测 `.qoder/agents/*.md` = 16 · `.codex/agents/*.toml` = 16 |
| P3-7 | 旧口径 3 处（12 个 / 8+3 个） | ✅ 清零 | `grep -rn '12 个审查官\|8+3' .agents --include='*.md'`（除 `pitfalls.md`/`change-log.md`/`/rfc/`/本报告区）→ **无命中**；三处落点现为「16 个角色」/「14 个审核者」 |
| P3-8 | pre-commit 层数三口径 | ⚠️ **未清**（只改了 2 处落点中的 1 处） | `grep -rn '11 层\|六层门禁' .agents --include='*.md' \| grep -v 'pitfalls\|change-log'` → **3 命中**：`engineering.md:330` · `review-driven.md:129` · `.agents/_directory.md:40` |
| P3-9 | `docs/records/_index.md` 指向不存在的 `audits/_index.md` | ✅ 清零 | `grep -n 'audits/_index' docs/records/_index.md` → 无命中；`:22` 「29 份」= `ls docs/records/audits/*.md \| wc -l` = 29 ✓ |
| P3-10 | 清单「与 16 个角色一一对应」不成立 | ✅ 清零 | `AGENTS.md:59` / `README.md:46` 现为「**部分一一对应**：16 个角色中 14 个有同名清单 + 3 份专项」；机械对拍：缺同名清单者恰为 `ai-adversarial-reviewer` · `docs-design-writer`；多出者恰为 `ai-cost-checklist` · `ai-guard-checklist` · `code-perf-reviewer` ✓ |
| P3-11 | `engineering.md` 状态三口径 | ✅ 清零 | `:22` 现为「**正式稿**……仍待拍板的仅 §4.5 / §六」；`:3` description「机制部分已生效」· `:393` 「已落地」自洽 |
| P3-12 | README「7 个顶层」vs `ls` 见 8 个 | ✅ 清零 | `README.md:37` 已加出口位说明；`ls -d .agents/*/` = 8（含出口位 `.agents/skills/`，gitignore）⇒ 7 顶层成立。⚠️ 但根索引仍写「6 个顶层」→ #1 |
| P3-13 | `inbox.md` 自身 `lines` 漂移 | ⚠️ **复发** | `wc -l .agents/workspace/_meta/inbox.md` = 82，声明 69（M2 FAIL）。本次为满足交付判据 C1 已同步（见第五节） |

**小结**：**11 条清零**（其中 P2-1/P2-2/P2-3/P2-4/P2-5 的**落点**确实改到位，但同类残留分别见 #3/#4/#7），**2 条未清**（P3-8 层数口径、P3-13 派单文件元数据）。**无 P0/P1**。

## 三、H1–H8 复检

### H1 总纲 ⇄ 实际 —— ⚠️（落点已对，**根索引/根契约这一层从未被查**）

**✅ 已对**：`README.md` 口径（rfc 67 · guards 15 · 角色 16 · 顶层 7 · 清单 14/16+3）逐项实测一致（命令同第二节 P2-1）。

**❌ 未对**（全在**根两件套**与**非总纲文件**里——S6 只扫 `README.md`/`AGENTS.md`/`ai-context-engineering.md` 三份，见 `ai-guard-structure.sh:239`）：

| # | 位置 | 写了 | 实测 | 命令 |
|:--:|:--|:--|:--|:--|
| 1 | `.agents/_index.md:3` `:24` · `.agents/_directory.md:23` | **6 个顶层**（且 `workspace/` 仍挂在 `records/` 下，`:33`） | **7 个顶层**（`workspace/` 2026-10-04 三批已独立，见 `README.md:29`） | `ls -d .agents/*/` → direction knowledge mechanism records rules **skills（出口位）* toolkit **workspace** |
| 2 | `.agents/_index.md:30` | `rfc/`（**68 份**） | **67** | `ls .agents/direction/rfc/*.md \| grep -v '_index\|_directory' \| wc -l` |
| 3 | `engineering.md:86` | 已知坑 + 复发信号（**25 章**） | **37** | `grep -c '^## ' .agents/rules/assets/pitfalls.md`（health ④ 亦按 37 章计） |
| 4 | `engineering.md:396` | 「`**29 个受管子目录**`……**目录两件套 22 组**」（同一行自相矛盾） | 受管 29 · 两件套 **30** | `find .agents -name _index.md \| wc -l` = 30 |
| 5 | `engineering.md:403` | 「5 个区中**仅 `records/`** 有 `_directory.md`」 | **records + research 两家** | `find docs -maxdepth 2 -name _directory.md` |
| 6 | `docs/README.md:53` | 走查存档 **27 份** | **29**（本报告落盘后 30） | `ls docs/records/audits/*.md \| wc -l` |
| 7 | `.agents/_directory.md:56` · `mechanism/guards/_directory.md:46` | `ai-guard-tools` **T1–T7** | **T1–T8** | `bash .agents/mechanism/guards/ai-guard-tools.sh` |

### H2 文档 ⇄ 行为 —— ⚠️（本轮重点：**规范与实物相反** 2 处 + **守卫自称范围与实际不符** 2 处）

**H2-1（本批新引入）技能目录化已落地，规范却说「仍扁平」**——`c01d2b4e` 把 3 个扁平技能迁成 `<name>/SKILL.md`，其 commit message 声称「skills-spec.md 的口径从此与实物一致、偏离消失」，**但该文件未随之修改**：

- `skills-spec.md:79`（偏离 3）仍写「**过渡期部分技能仍扁平** → `code-api-writer` / `code-domain-writer` / `ship` 暂留 `<name>.md`」——实物已是 4/4 目录布局（`ls -d .agents/toolkit/skills/*/`）。
- 守卫为何没拦：`ai-guard-skills.sh:87-92` 的 **S7 只验「偏离在案」段落是否存在**，不验内容 ⇒ PASS。

**H2-2（上轮漏报·同类旧口径）`skills-spec.md:60` 说审查官是 `<name>/SKILL.md`**——与同一文件 `:78`（偏离 2「审查官保持扁平 `roles/<name>.md`」）**自相矛盾**，也与实物相反（`ls -d .agents/toolkit/roles/*/` → No such file）。这正是上轮 P2-2（`AGENTS.md:52`）的同一句旧口径，**只修了总纲那一处**。

**H2-3（本批新引入）命名映射表的「形态」列未随迁移更新**：`naming-spec.md:120-122` 三行仍写「扁平（按需加载）」，体系名仍是无后缀的 `code-api-writer.md` / `code-domain-writer.md` / `task-ship.md`。

**H2-4（本批新引入）同一行两个技能名形态不一**：`review-driven.md:106`「`skills/code-api-writer/SKILL.md` / `code-domain-writer.md`（建设技能引导）」——`c01d2b4e` 只改了前半个。

**H2-5（机制覆盖面）「M2 查 lines」名不副实**：`meta` 的 `files` 清单按「逐子目录 glob」枚举（`ai-guard-meta.sh:33-68`），**漏掉 11 个受管文件**（4 组顶层两件套 + 根契约 + `docs/README.md` + `docs/records/_directory.md`），其 `lines` 声明全部与实际不符且**永不被查**——见 #2 待修。
**实证**：本次为满足 C1 跑 `bash .agents/mechanism/guards/ai-guard-meta.sh --fix`，输出 `--fix: 1 文件回写 lines/updated → .agents/workspace/_meta/inbox.md` ——**上述 11 个文件一个都没被碰**（连 `lines: 1` 都原样留着），证明它们确实不在扫描范围内。

**H2-6（机制覆盖面）S6c 实测只对拍 4 行**：新增的 S6c（`ai-guard-structure.sh:249-266`）刻意保守（只认单个 `.agents/...*.md|*.sh` glob + 单个加粗数字），实测命中 `:88` `:110` `:112` `:113` **4 行**；上轮建议里的「子目录数 / 手册数 / pitfalls 章数」**未纳入**，故 `:86`（25 章）与 `:396`（22 组）这类漂移仍无人查。

**✅ 通过项（实测在跑）**：4 个技能出口软链实物齐备（tools T4）· subagent 出口 **16/16**（`.qoder/agents/*.md` / `.codex/agents/*.toml`）· pre-commit 实件与 `AGENTS.md:63-65` 声称的「自动触发」一致（`.githooks/pre-commit:164` skills / `:146` feature / `:121` align）· 规则 0c 收件箱通道**有效**（本单即由它领活）· `--topic` 谎报已修（`AGENTS.md:66`）。

### H3 交叉一致 —— ⚠️（4 组数字两处不一）

| 事实 | 一处 | 另一处 | 实测 |
|:--|:--|:--|:--|
| 顶层数 | `README.md:29` `:33` **7** | `.agents/_index.md:3` `:24` · `.agents/_directory.md:23` **6** | **7** |
| RFC 份数 | `engineering.md:88` **67** | `.agents/_index.md:30` **68** | **67** |
| `ai-guard-tools` 范围 | `engineering.md:370` **T1–T8** | `.agents/_directory.md:56` · `mechanism/guards/_directory.md:46` **T1–T7** | **T1–T8** |
| pre-commit 层数 | `layer-spec:171`「多层，数字不写死」· `.githooks/pre-commit:2`「多层」 | `engineering.md:330` · `review-driven.md:129` **11 层** · `.agents/_directory.md:40` **六层** | **10 个标号块** |

### H4 跨目录双源 —— ⚠️（收口动作到位，但**少算了一份**）

`c01d2b4e` 已给三处加交叉指针（`README.md:54` · `AGENTS.md:46` · `engineering.md:65`），并在 `engineering.md:65` 声明「本章是资产清单的**唯一详述源**」。**但同一知识（哪个目录装什么 / 几个顶层）实际有第四份详述**：

- `.agents/_index.md:28-35` 的「子目录」表 = **顶层职责表**（`direction/` `knowledge/` `rules/` `records/` `toolkit/` `mechanism/` 六行 + 每行装什么），既没被纳入「唯一详述源」口径，也没被任何守卫查（见下 H2-5 / 待修 #1），**结果正是它漂了**（6 顶层 / workspace 仍归 records / rfc 68）。
- 判据建议：它应当降级为**指针**（指向 `engineering.md` §二），或明确写成「顶层速查」并与 `README.md` 同源同校。

### H5 滞后 —— ⚠️（根两件套停在「三批之前」+ `updated` 未随内容刷新）

| 位置 | 症状 | 证据 |
|:--|:--|:--|
| `.agents/_index.md:6` · `.agents/_directory.md:6` | `updated: 2026-10-04`，内容却停在**三批之前**（`workspace/` 仍未提为顶层，与同日的 `README.md:29` 相反） | `sed -n '3p;24p;33p' .agents/_index.md` |
| `.agents/_directory.md:8` | `lines: 30`，实际 **66**（M2 查不到该文件，见 H2-5） | `wc -l .agents/_directory.md` |
| 本批被改的 **9 个文件** | 内容已改（commit 日期 2026-10-06）而 `updated` 未跟：`knowledge/reference/_directory.md`(10-04) · `ai-context-layer-spec.md`(10-05) · `conventions.md`(08-18) · `skills-usage.md`(10-03) · `toolkit/_index.md`(10-04) · `toolkit/skills/_index.md`(10-03) · 3 个 `SKILL.md`(08-20) | `git show --name-only 2598ba5c c01d2b4e` |
| `docs/README.md:8` | `lines: 1`，实际 **92** | `wc -l docs/README.md` |

> **根因（#5 待修）**：`ai-guard-meta.sh:152-156` 明写「updated **只在 lines 实际漂移**（内容变更）时刷新」——本批 9 处内容变更**未引起行数变化**，于是 `updated` 不动。规范 `frontmatter-spec.md:28` 却承诺「updated 由工具维护、手写必漂移」。

### H6 结构断裂 —— ⚠️ 轻（1 处；上文三份总纲已**完整读过**）

- **`engineering.md:318`**：mermaid 图（`:308-322`）里定义的是 `TG["<分支名>/LEDGER.md 账本"]`，而边引用了**未定义的 `TK`**：`TK -->|"合并收尾：待归档"| CL[...]`。Mermaid 会渲染出一个游离的 `TK` 节点——正是 H6 判据里的「孤立残行」。
- **复现**：`python3 - <<'EOF'` 扫 `.agents/**/*.md` 全部 mermaid 块的「用而未定义」节点 → 全库**仅此 1 处**（`sed -n '310,320p' .agents/rules/assets/ai-context-engineering.md`）。
- **✅ 已确认无**：三份总纲（`README.md` 88 行 · `AGENTS.md` 88 行 · `engineering.md` 411 行）通读，无「两套结构并存 / 尾巴挂 `|` / 丢标题的表」；上轮 4 项无回退。

### H7 死胡同与孤儿 —— ⚠️（**行内 `bash <路径>` 是 M4 的双重盲区**）

`ai-engineering/` 目录 2026-10-03 已不存在，但**5 条可执行命令**仍指向它（行内、非代码块）：

| 位置 | 原文（命令） | 实测 |
|:--|:--|:--|
| `.agents/toolkit/skills/code-api-writer/SKILL.md:35` | `bash ai-engineering/ai-guard-align.sh` + `bash ai-engineering/ai-guard-meta.sh` | 两条皆不存在 |
| `.agents/toolkit/skills/data-learn-writer/SKILL.md:82` | `bash ai-engineering/ai-guard-cost.sh --record` | 不存在 |
| `.agents/toolkit/skills/task-ship/SKILL.md:33` | `bash ai-engineering/ai-guard-sediment.sh` | 不存在（**`c01d2b4e` 明确修 task-ship 同类 bug 时漏掉的第 5 条**——它只改了「参考资料」段的 4 条） |
| `.agents/_directory.md:60` | `bash guards/ai-guard-structure.sh --fix` | 仓根与 `.agents/` 下均不存在（真身 `.agents/mechanism/guards/`） |

```bash
ls ai-engineering/ai-guard-align.sh ai-engineering/ai-guard-meta.sh ai-engineering/ai-guard-cost.sh ai-engineering/ai-guard-sediment.sh   # 全 No such file
sed -n '4p' .githooks/pre-commit   # 见 M4 判据来源
```

**为何 M4 漏检**（`ai-guard-meta.sh:203-221`）：① 代码块规则只认 ```bash 块**内**的 `bash <path>`——上述 4 条都在**正文行内**；② 行内路径正则只认以 `docs/` `.agents/` `AGENTS.md` 开头的形态 ⇒ `ai-engineering/…` 与 `guards/…` 双逃。

**孤儿抽验：无新增孤儿**（`meta` M3 全绿；`docs/records/_index.md:27` 的 `audits/_index.md` 已删）。

### H8 可执行性 —— ✅ 通过

| 文档里的命令/对象 | 实测 |
|:--|:--|
| `bash -n` 全部 shell（guards 15 + scripts + `lib/` + `.githooks/pre-commit`） | **0 语法错误** |
| `task-cadence.sh` 子命令 | **8 个全在**：`daily` `ship` `weekly` `todo` `release` `check` `cost` `mark`（脚本 `:450-457`） |
| subagent 出口 | `.qoder/agents/*.md` = 16 · `.codex/agents/*.toml` = 16（= `layer-spec:120`「16/16」） |
| 技能出口 | `ai-guard-tools` T4 PASS（4 个出口软链实物齐备；只注册直触发技能 `data-learn-writer`，与 `layer-spec:117-119` 口径一致） |
| `ai-guard-health/–structure/–meta/–tools/–skills` 参数 | `--full` `--json` `--fix` 均真实存在并实跑 |

**唯一 ❌**：上文 H7 的 5 条行内命令不可执行——**归 H7 计，不在 H8 重复**。

## 四、待修清单（P0–P3）

> **无 P0 / 无 P1**（全是叙事层，不涉及数据丢失、错误行为或对外承诺）。**#1–#5 为本轮新引入或本轮暴露的高价值项**。

| 级别 | # | 症状 | 根因 | 落点 | 建议改法 |
|:--:|:--:|:--|:--|:--|:--|
| **P2** | 1 | **容器根两件套**（`.agents/_index.md` + `_directory.md`）内容陈旧：6 顶层（实际 7）· `workspace/` 仍挂在 `records/` 下 · rfc 68（实际 67）· T1–T7 · 「六层门禁」· `lines: 30`（实际 66）· `bash guards/…` 不可执行 | **根两件套的正文不在任何守卫范围内**：`ai-guard-structure.sh` 的 S1/S2 只遍历 `.agents/` 的**子目录**（`SUBS` `:125-128`，根不在其中）；`meta` 收了根 `_index.md`（只验 lines）却**没收根 `_directory.md`**（`:33`）；S6 只扫三份总纲（`:239`） | `.agents/_index.md:3` `:24` `:30` `:33` · `.agents/_directory.md:8` `:23` `:40` `:56` `:60` | 按 7 顶层重写两处（workspace 提为顶层行）；**并把根两件套纳入 structure 的 S1/S2 + meta 的 M2**（否则必再漂） |
| **P2** | 2 | **M2 `lines` 盲区 11 处**：4 组顶层两件套（knowledge/rules/toolkit/mechanism 的 `_index`+`_directory`）声明 `lines: 1`（实际 38–83，`toolkit/_index.md` 声明 71 实际 73）· `.agents/_directory.md` 30/66 · `docs/README.md` 1/92 · `docs/records/_directory.md` 1/43 | `ai-guard-meta.sh:33-68` 按「逐子目录 glob」枚举，缺 `.agents/{knowledge,rules,toolkit,mechanism}/*.md`、`.agents/_directory.md`、`docs/README.md`、`docs/records/_directory.md` | `ai-guard-meta.sh:33-68` | 补上述 glob（或改 `rglob` 后按 `M4_SKIP`/`is_light` 排除），再跑 `--fix` 一次回写 |
| **P2** | 3 | **技能目录化的配套文档未同步**（本批新引入）：`skills-spec.md:79` 偏离 3 仍称「部分技能仍扁平」（与实物相反）· `skills-spec.md:60` 仍称审查官是 `roles/<name>/SKILL.md`（与本文件 `:78` 及实物相反）· `naming-spec.md:120-122` 形态列仍写「扁平」· `review-driven.md:106` 同行两技能名形态不一 | 迁移批只扫了「技能文件 + 索引」，未扫「规范 / 命名映射 / 流程表」；S7 只验偏离段**存在**、不验**内容** | `skills-spec.md:60` `:79` · `naming-spec.md:120` `:121` `:122` · `review-driven.md:106` | 四处同步为 `<name>/SKILL.md` 目录布局；**删除偏离 3**（删后表内 4 行 ⇄ 导语「四条」自洽，见 #11） |
| **P2** | 4 | **5 条行内命令死路径**（`ai-engineering/` 已不存在）：3 个技能文件 4 条 + `.agents/_directory.md:60` 1 条 | M4 双重盲区：只认**代码块内** `bash <path>` + 行内只认 `.agents/`/`docs/` 前缀（`ai-guard-meta.sh:203-221`） | `code-api-writer/SKILL.md:35`（2 条）· `data-learn-writer/SKILL.md:82` · `task-ship/SKILL.md:33` · `.agents/_directory.md:60` | 5 条一律改 `.agents/mechanism/guards/…`（`.agents/_directory.md:60` 改 `.agents/mechanism/guards/ai-guard-structure.sh --fix`）；M4 增加「行内 `bash <path>`」判据（注意按 pitfalls 二十三先反例回归，防误报） |
| **P2** | 5 | **`updated` 未随内容刷新（9 文件）**：本批 3 次提交 touch 的文件内容已改而 `updated` 停在旧日期（清单见 H5 表第 3 行）；`frontmatter-spec.md:28` 承诺「由工具维护」 | `ai-guard-meta.sh:152-156` 只在 `lines` 漂移时才刷 `updated`——「内容变更必引起行数变化」的假设在本批不成立 | `ai-guard-meta.sh:152` · 9 个文件 | 二选一：① `/ship` 时按 `git diff --name-only` 刷 `updated`；② 改 `frontmatter-spec.md:28` 的措辞，写明「`updated` 仅表示行数变更时点」 |
| **P3** | 6 | **P3-8 未清**：层数口径 3 处 | 上轮落点只改了 2 处里的 1 处（改的是 Mermaid 节点 `:281`） | `engineering.md:330` · `review-driven.md:129` · `.agents/_directory.md:40` | 统一为 `layer-spec:171` 的「**多层，数字不写死**」 |
| **P3** | 7 | 不实数字：`25 章`（实际 37）· `22 组`（实际 30，且**与同一行的「29 个受管子目录」自相矛盾**）· `docs/README.md` 「27 份」（实际 29） | 手工数字；S6c 跳过「无 glob / 花括号 / 多数字」的行（`:251-254`）且不扫 `docs/README.md` | `engineering.md:86` `:396` · `docs/README.md:53` | 改实测值；把「章数」「两件套数」纳入 S6 的 TRUTH 断言（`:234`） |
| **P3** | 8 | P2-1 第 6 项只改了一处：`T1–T7` 仍残留 2 处 | 同 #3 的「只改落点、未全域回扫」 | `.agents/_directory.md:56` · `.agents/mechanism/guards/_directory.md:46` | 改 `T1–T8`（或写「T1–T8」前先与 `ai-guard-tools.sh` 的编号对拍） |
| **P3** | 9 | `docs/` 契约缺口表述不实：「5 个区中**仅 `records/`** 有 `_directory.md`」，实际 **records + research** 两家 | 10-04 同批建的 `docs/research/_directory.md` 未被计入 | `engineering.md:403` | 改「2 区有（records / research），其余 3 区只有 `_index.md`」 |
| **P3** | 10 | mermaid 未定义节点 `TK`（同图账本节点已改名为 `TG`） | 改名时漏改边引用 | `engineering.md:318` | `TK` → `TG` |
| **P3** | 11 | `skills-spec.md:73` 导语写「**四条**有意偏离」，表内实为 **5 行** | 追加偏离 5 时未回改导语 | `skills-spec.md:73` | 随 #3 删掉偏离 3 后自洽（4 行 = 四条）；若保留偏离 3 则改「五条」 |
| **P3** | 12 | `inbox.md`（派单文件）`lines` 复发（69 vs 82）+ M4 指向尚未落盘的 r2 报告 | 派单文件在守卫跑完后被继续追加；派单正文指向**交付物本身** | `.agents/workspace/_meta/inbox.md:8` | 短期：`bash .agents/mechanism/guards/ai-guard-meta.sh --fix`（**本次为满足 C1 已执行**）；长期：派单正文别写「尚未落盘」的交付路径，或把 `_meta/inbox.md` 纳入 M4 豁免 |

**已知且在案、不新增**：`docs/` 侧 4 区缺 `_directory.md`（`engineering.md:403` 自记，本次只纠正其计数）· `records/state` · `change-log.md`/`task-log.md`/`docs/archive/` 的 `lines` 漂移（**append-only 账本与存档，按设计豁免 M2**）· `.agents/skills/` 出口位不计顶层（`README.md:37` 已说明）。

## 五、纪律声明（改了什么 / 没改什么）

- **只报告不改**（B7）：**未动**第二节 13 条与第三节 H1–H8 点名的**任何被点名文件**。
- **仅为满足交付判据 C1 的守卫同步（3 处，全部记录在案）**：① 本报告落盘；② 登记 `docs/records/_index.md`（audits 29 → **30** 份 + `updated`）；③ 跑 `bash .agents/mechanism/guards/ai-guard-meta.sh --fix` 回写**派单文件自身**的 `lines/updated`（#12；该文件不在本轮任何发现内）。
- **守卫复验**：`ai-guard-meta.sh` **PASS** · `ai-guard-structure.sh` **PASS**（见下）。

```
META-GUARD: PASS (285 files, edges/lines/orphans all ok)
STRUCTURE-GUARD: PASS (29 个子目录 · 两件套齐备 · 清单⇄实际一致 · 依赖与守卫引用有效 · 两件套不重复)
```

## 六、一句话结论

**上轮 13 条：11 清、2 未清**（层数口径 P3-8 · 派单文件元数据 P3-13 复发），**三批修复本体是有效的**——落点逐条对得上实测。**但修复的方式暴露出一个系统性弱点：改「落点」不改「类别」**，而守卫又恰好在这些类别上是盲的——于是留下 **3 个新引入的文档⇄行为不一致**（技能目录化后 `skills-spec`/`naming-spec` 仍说扁平）、**5 条行内死命令**（`c01d2b4e` 修 task-ship 时漏的第 5 条），以及**本轮最大的结构性发现**：**容器根两件套（`.agents/_index.md` + `_directory.md`）内容陈旧 4 天却无任何守卫覆盖**——「6 个顶层 / workspace 归 records / rfc 68 / T1–T7」与 `README.md` 的 7 顶层口径**正面相反**，而 `ls` 站在 README 一边。**建议下一批：先修 #1 + #2（把根两件套与 11 个漏检文件纳入守卫范围），再批量清 #3/#4/#6/#7 的措辞与数字**——否则「体检」会周期性报同一批。

---

## 附：本次**未**采信的候选（遵循「宁可报少，不可报错」）

- **`engineering.md:402`「`.agents/` 199 份」**：实测 `git ls-files .agents | wc -l` = 316、`.md` 273，**无法复现 199 的口径**（该行是一句结论式表述，未定义统计范围）⇒ 不列为待修，仅此一行备查。
- **`AGENTS.md:73` 写「cron 每周自动审查」而实为 launchd**：同义泛称，上轮已记，不重复计。
- **`ai-context-engineering.md:404`「本批实测……7 处过时数字（14 子目录 / 23 章 / 70 份 / 11 守卫 / 4 流程…）」**：自记的**历史批次**账，不作为现状缺陷。
- **`docs/ideas/20261003-ai-asset-usage-ledger.md:37` 仍描述「三个技能为扁平布局」**：带日期的想法稿（档案馆区），属「当时的事实」，不计。
- **`ADR-005.md:21`（8 审查官 / 9 字段）· `rfc/20261001-*`（12 审查官）· `pitfalls.md:221`（T1–T7）**：ADR 为 append-only 决策记录、RFC 与 pitfalls 为历史账，按 `M4_SKIP` 立意不计。
- **`review-driven.md:106` / `naming-spec.md` 之外的 60+ 处缩写路径**（如 `process/review-driven.md` 实指 `rules/process/…`）：与上轮同判——**成立缩写，非断链**（`pitfalls.md:348` 的 M4 裸路径教训），不重复计入。
- **`AGENTS.md:42` 只定义 `docs/records/audits/<日期>-ai-context-health.md` 这一命名模式**：本次复检因同日二次运行而实际落 `-r2.md`；属**命名约定未覆盖复检**，观察级，不列待修。
