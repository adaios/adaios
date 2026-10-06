---
title: AI 上下文体系体检报告（r4 · D-20261006-10 · 封箱判定轮）
description: ai-context-health-reviewer 第四轮体检——机械层照抄（health 75/100 · structure PASS 30 · meta 1 FAIL〔`inbox.md` M2 · 时点快照；交付时已手工同步该字段，复验 health 100/100 · meta PASS 299〕· tools 12-0-0 · skills/feature/align PASS）+ r3 的 13 条逐条回归（8 清 · 3 未真清/残留 · 2 复发同型）+ H1–H8 复检；本轮专查「成对项漏改」（N×M 乘积 / 成对路径 / 同一事实多处副本）；无 P0/P1，但**新发现 1 条 P2：守卫 `--fix` 的幂等判断写错 ⇒ `lines` 校准静默失效且打印「无漂移」（本批 `cb89e6e5` 自己引入）** + 2 条 r3 P2 残留 + 8 条 P3 ⇒ **本轮不满足封箱判据（连续两轮无 P2 以上新类别），建议收口小批 + 轻量复检后转每周**；只报告不改，每条带文件:行 + 可复现命令
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 251
depends-on: []
related:
  - ./2026-10-06-ai-context-health-r3.md
  - ./2026-10-06-ai-context-health-r2.md
  - ../../../.agents/toolkit/roles/ai-context-health-reviewer.md
  - ../../../.agents/toolkit/checklists/ai-context-health-reviewer.md
tags: [audit, ai-context, health]
---

# AI 上下文体系体检报告（2026-10-06 · r4 · 封箱判定轮）

> **触发**：派单 `D-20261006-10`（`AGENTS.md` 规则 12「体检」的叙事层）；执行者 `ai-context-health-reviewer`。
> **本轮重点（派单指定）**：r3 揭示的病因是「**上一轮修复自己产生的伤**」（`skills-spec:60` 双写 · `development.md` 角色数改了乘积没改），
> 故**专查「成对项漏改」**——**数字的乘积（N×M）· 成对出现的路径（同一脚本多处引用）· 同一事实的多处副本（角色数 / 生成物数 / 层数口径）**。
> **判据**：H7 用**新判据**——先分「**指路**（必须可解析）」还是「**记事**（按设计就该写旧的）」；六类记事不计。
> **纪律**：只报告不改（B7）· 每条带 `文件:行` + 可复现命令 · 猜的不写 · 不重写已有判据（机械层照抄）· 宁可报少不可报错。
> **复现环境**：`/Users/adai/Projects/adaios` @ 2026-10-06 15:55–16:15。被检批次 = `cb89e6e5`（27 文件，已 push）；工作区唯一脏文件 = `.agents/workspace/_meta/inbox.md`（派单文件，本单**未写**，见第五节）。

## 一、机械层结论（照抄，不解读）

| 守卫 | 本单实测 | 与派单材料的差异 |
|:--|:--|:--|
| `ai-guard-health.sh --full` | **75 / 100** —— ①结构 ✅ ②元数据 ❌(1 FAIL) ③命名 ✅ ④体积 ✅ ⑤契约 ✅ ⑥新鲜度 ✅ | 派单写「100/100」 |
| `ai-guard-structure.sh` | **PASS**（**30** 个子目录 · 两件套齐备 · 清单⇄实际一致 · 依赖与守卫引用有效 · 两件套不重复） | 一致 |
| `ai-guard-meta.sh` | **1 FAIL**（`M2 .agents/workspace/_meta/inbox.md: lines 声明 94 != 实际 108`） | 派单写「PASS 299」 |
| `ai-guard-tools.sh` | **12 通过 / 0 警告 / 0 失败**（T1–T8） | 一致 |
| `ai-guard-skills.sh` | **PASS**（20 个技能包 · S3/S4/S5/S7） | 一致 |
| `ai-guard-feature.sh` | **PASS**（37 功能 · 3 卡文件 · 9 张卡） | 未在派单列出，补跑 |
| `ai-guard-align.sh` | **PASS**（A1 173 端点 · A2 后端 2462 / app 440 / admin 72 / web 409） | 未在派单列出，补跑 |

> **⚠️ 本轮加验（重要 · P2-r4-0 的现场）**：这条 FAIL **无法用守卫自带的修复命令消掉**——`bash .agents/mechanism/guards/ai-guard-meta.sh --fix` 实测打印「**无漂移，无需回写**」而 `lines: 94` 原封不动 ⇒ **守卫报 FAIL、它自己的修复命令说没漂移**（根因见 **P2-r4-0**，本批引入的回归）。
> **交付时的收口（如实）**：因 `--fix` 已失效，本单**手工**把 `inbox.md` 的 `lines` 同步为 **108**（与 `2e8f1689` 对同一字段做过的动作同性质，属**守卫同步**不属内容修复），此后 `META-GUARD: PASS (299 files)` · `health **100/100**`。**注**：`docs/records/audits/*` 属 meta 的**豁免区**（`ai-guard-meta.sh:54`），故文件数仍是 **299** 而非 300。
> **唯一 FAIL 的归因（与 r3 同型，非 M2 判据退化、也非派单写错）**：派单是在主链跑完守卫**之后**写进 `inbox.md` 的（写后 `lines` 未同步 ⇒ M2）；
> M4 一侧已被机制豁免（`ai-guard-meta.sh:200-203` 把 `.agents/workspace/_meta/` 纳入 `M4_SKIP`，理由写明「派单文件按设计就会指向尚未落盘的交付物」）⇒ **本报告落盘后 M4 自动消解，M2 仍会随派单追加而复发**（同 r3 P3-13，机制性摩擦，已在案）。
> **机械层结论是时点快照**——「主链跑时 100/100」与「此刻 75/100」可以同时为真。

## 二、r3 的 13 条回归（逐条 ✅/⚠️）

> r3 待修清单 = **P2×5（#1–5）+ P3×8（#6–13）**。命令一栏可逐条复现。

| # | r3 条目 | 判定 | 证据（文件:行 + 命令） |
|:--:|:--|:--:|:--|
| 1 | **P2-1** `sh`/`python3` 死命令 18 行 + M4 扩类 | ⚠️ **17/18 清 · M4 扩类只覆盖一半形态** | ✅ 真身已修：`ios-release.md:77,78,79,80,89,90,108,109,110` 全为 `apps/adai-app/scripts/…` · `testflight-external-testing.md:138,139,140` · `backend-deployment.md:341,343` · `routine.md:152` · `ai-guard-release.sh:290,296`（`sh scripts/serve_web.sh`，且已 `cd` 到对应 app）。✅ M4 **两处同步改到位**：`ai-guard-meta.sh:214`（代码块）与 `:225`（行内）均含 `bash\|sh\|zsh\|python3?`，注释明写「加新解释器要同时改」。**❌ 残留 1 行**：`.agents/rules/guides/development.md:43` `cd apps/adai-app && sh .agents/mechanism/scripts/serve_web.sh`（真身 `apps/adai-app/scripts/serve_web.sh`）。**❌ M4 两个形态仍逃检**：见下方 P2-r4-1。 |
| 2 | **P2-2** `updated` 未随内容刷新 | ⚠️ **7/8 清 · 1 残留 · 机制不回溯 · 并改出 1 条新 P2** | ✅ 已清：`conventions.md` · `code-api-writer/SKILL.md` · `code-domain-writer/SKILL.md` · `task-ship/SKILL.md` · `skills-usage.md` · `reference/_directory.md` · `ai-context-layer-spec.md`（7 个 `updated` 均已 = `git log -1` = 2026-10-06）。**❌ 残留**：`.agents/toolkit/skills/_index.md`（`updated: 2026-10-03` vs `git=2026-10-06`，`c01d2b4e` 改的）。**根因只治一半**：新逻辑（`ai-guard-meta.sh:152-159`）只刷「**本次 `git diff` 脏**」的文件，**已提交的旧漂移不会回溯** ⇒ 同批另有 4 个 10-06 内容已改而 `updated` 停旧：`product-roadmap.md`(10-04) · `naming-spec.md`(10-04) · `docs-design-writer.md`(10-04) · `skills/data-learn-writer/SKILL.md`(09-16)。**❌ 并改出回归**：本次重写的 `--fix` 把 `lines` 校准改坏了（**P2-r4-0**）。命令：`for f in …; do echo "$f $(grep -m1 '^updated:' $f) $(git log -1 --format=%ad --date=short -- $f)"; done` |
| 3 | **P2-3** `ai-guard-tools` 范围写 T1–T7（2 处） | ✅ **清** | `grep -rn 'T1–T7' --include='*.md' .agents docs` → 仅 `pitfalls.md:221`，该行是 2026-10-03 的**历史记录**（「…（T1–T7 会把已有工具链全列出来）」，配 `✅ 已用（2026-10-03）`）⇒ 按 H7(f) **记事，不计**。`ai-guard-tools.sh` 实测输出 **T1–T8**。 |
| 4 | **P2-4** `audit.md:19` 角色 15（漏体系体检官） | ✅ **清**（连带 1 处 P3 新残留） | `sed -n '19p;36p' .agents/rules/process/audit.md` → 两处均 **16**，`:19` 枚举已含「体系体检官 1」。⚠️ 但同句「**后三组**不进本走查」与新枚举（4 组不进）不符，`:36` 的枚举也未列体系体检官 → 见 P3-r4-5。 |
| 5 | **P2-5** `development.md:52` 「16 个角色 × 2 家 = 30 个生成物」 | ✅ **清** | `sed -n '52p'` 现「**16 个角色 × 2 家 = 32 个生成物**」；实测 `ls .qoder/agents/*.md \| wc -l` = 16 · `ls .codex/agents/*.toml \| wc -l` = 16 ⇒ 16×2=**32** ✓（`ai-sync-all.sh --check` 子代理出口 2 组 ✅）。 |
| 6 | **P3-6** `.agents/_index.md:3` 「6 个顶层目录」 | ✅ **清** | `sed -n '3p'` 现「**7 个顶层目录**」；`ls -d .agents/*/ \| grep -v skills` = 7 ✓。 |
| 7 | **P3-7** `.agents/_index.md:42` 「三层结构」 | ✅ **清** | `sed -n '42p'` 现「定位（规则与机制层）+ **顶层结构** + 任何 AI 工具如何接入」；`grep -n '三层\|顶层结构' .agents/README.md` 仅 `:33` ✓。 |
| 8 | **P3-8** `skills-spec.md:60` 同一路径写两遍 | ✅ **清**（同行留下 1 处 P3） | `sed -n '60p'` 现单次「…`<name>/SKILL.md`（官方目录布局，进攻侧——code-api-writer / code-domain-writer / **ship** / data-learn-writer）」。⚠️ 该列表里的 `ship` 实名应为 `task-ship` → 见 P3-r4-2。 |
| 9 | **P3-9** `skills-spec.md:73` 导语「三条」vs 表内有效 4 行 | ❌ **未清** | `sed -n '73p;75,79p'`：导语仍「**三条有意偏离**」；表内 **5 行**，#3 为 `~~划除~~`（2026-10-06 已消除），有效 = 1/2/4/5 = **四条**（#5 = 「`触发条件` 的等价标题」，确为有效偏离）⇒ 仍差 1。 |
| 10 | **P3-10** `.agents/_directory.md:10` `related` 自指 | ✅ **清** | `grep -n '^related:' .agents/_directory.md .agents/*/_directory.md` → **8/8** 均为 `related: [./_index.md]`。 |
| 11 | **P3-11** 3 个脚本的用法行指向拼接假路径 | ⚠️ **只清 1/3（典型成对项漏改）** | ✅ `data-sync-stock-names.py` 已修（`cb89e6e5` 唯一改的那个）。**❌ 残留 2 个**：`.agents/mechanism/scripts/data-verify-tdx.py:7` 与 `data-prewarm-adj-factors.py:12` 仍写 `python3 ai-engineering/09-.agents/mechanism/scripts/<同名>.py`。同目录 `ai-domain-view.py:5` 是正确的 `.agents/…` 写法（可照抄）。命令：`grep -rn 'ai-engineering/09-' --include='*.py' .agents`。 |
| 12 | **P3-12** `engineering.md:100` 「契约与设计（13 份）」 | ✅ **清** | `sed -n '100p'` 现「**12 份**」+ glob 收窄为 `{contracts,designs}/*.md`；实测 `ls .agents/knowledge/reference/{contracts,designs}/*.md \| grep -v '_index\|_directory' \| wc -l` = 12 ✓（manuals 4 单列 `:101`）。 |
| 13 | **P3-13** 派单文件 `inbox.md` `lines` 复发 + M4 指向未落盘报告 | ⚠️ **同型复发（机制性摩擦，已在案）** | M4 一侧已机制豁免（`ai-guard-meta.sh:200-203`）✓；M2 一侧本轮实测 **94 != 108**（派单继续追加）。**本单未写该文件**。 |

**小结**：**8 条清**（#3 · #4 · #5 · #6 · #7 · #8 · #10 · #12）· **3 条未真清/残留**（#1 · #2 · #11）· **2 条复发**（#9 未清 · #13 同型）。**无 P0 / 无 P1。**
> **本轮最重要的结论（正面）**：r3 点名的**病因**（「成对项漏改」）在本批**主犯位置没重犯**——M4 扩类的两处正则**同时改了**（`:214`/`:225`，且注释写明联动要求）；`16×2=32` 的**乘积**跟着角色数一起改了；`related` 自指 **8/8** 清；`T1–T7`→**T1–T8** 全域清。
> **但同型漏改换了地方**：本批只改了「r3 报告点名的那一处」，**没做全域回扫**——#11 的 3 处只改 1 处、#1 的 18 行只改 17 行，即最典型的两例。

## 三、H1–H8 复检

### H1 总纲 ⇄ 实际 —— ✅（总纲主干全对；漂移全部落在**总纲之外**）

**✅ 逐项实测**：`README.md:33` 顶层 **7** ✓（`ls -d .agents/*/` 8 项含出口位 `skills/`，`_index.md:37` 已声明不计）· `:41` rfc **67** ✓ · `:46`/`:67` 角色 **16** ✓ · `:47`/`:61` 守卫 **15** ✓（`ls guards/*.sh \| wc -l` = 15，且 `:61` 列举的 15 个名字与实件一一对应）· `AGENTS.md:54` 角色 **16**（1+14+1）✓ · `:56` 技能 **4** 目录布局 ✓ · `:59` 14/16 + 3 专项 = **17 清单** ✓（`ls checklists/*.md \| grep -v '_' \| wc -l` = 17；同名对拍实测 **14** 个，缺 `ai-adversarial-reviewer` / `docs-design-writer` = 无同名清单的 2 个）· `engineering.md:86` 37 章 ✓ · `:88` **67 份** ✓ · `:108` 技能 4 ✓ · `:110` 守卫 **15** ✓ · `:112` 流程 5 份（数目 ✓，**名称见观察级**）· `:113` 清单 **17** ✓ · `:67` 受管子目录 **30**（`find .agents -name _directory.md \| wc -l` = 30）✓ · `review-driven.md:23` 角色 16 ✓ · 审核者 **14** 的构成（需求评审 1 + 域客观官 8 + 对抗官 1 + 外部视角 3 + 体系体检 1）在 `README:67` / `AGENTS.md:54` / `engineering:326` **三处一致** ✓。

**❌ 未对（全在非总纲文件——S6 只扫 `README.md`/`AGENTS.md`/`engineering.md`/根两件套，见 `ai-guard-structure.sh:237-241`）**：本轮**没有**新数字错落在「总纲」里；所有副本漂移见 **H3** 与 **P3-r4-1～4**。

### H2 文档 ⇄ 行为 —— ⚠️（在跑的机制都真在跑；**新判据仍无反例 fixture** · **扩类只覆盖一半形态**）

**✅ 实测在跑**：M2 覆盖面 299（`ai-guard-meta.sh` 的枚举逻辑复算）· M4 行内扫描在跑 · `--fix` 的**`updated` 刷新**改为**按 git**（`ai-guard-meta.sh:135-159`，读代码 + 本批实跑痕迹：7 个文件 `updated` 确已刷到 10-06）· 4 个技能出口软链（tools **T4** PASS）· subagent 出口 16+16（`ai-sync-all.sh --check` ✅）· `.githooks/pre-commit` 多层门禁实件 · 派单收件箱通道**有效**（本单即由它领活）。

**❌ 声称但实际不工作（本批新引入）**：`ai-guard-meta.sh:6` 的头注释写「`--fix` 检查 + **回写 lines 字段（D34 校准）**」，而实测该能力**已失效**——`lines` 漂移时 `--fix` 静默不写盘且打印「无漂移，无需回写」（**P2-r4-0**）。**「文档 ⇄ 行为」不一致的最硬一例**：不是文档没跟上，是**守卫自己在说谎**。

**⚠️ 缺口（两条都是 r3 已记、本批未动）**：
1. **新判据仍无「反例证明」**（观察级，不列待修——契约字面只说「新**守卫**」）：`tests/_directory.md:38` 要求「每个新守卫都要有反例」，本批给 M4 加了「识别 `sh`/`python3`」这一新判据，却**没留下可复跑的坏样本**（`git show --name-only cb89e6e5 \| grep -c 'tests/'` = **0**；`ls .agents/mechanism/guards/tests/` = 1 个 fixture）。**若按契约只论「守卫」不论「判据」，字面不算违规**，故维持观察级。
2. **M4 扩类只覆盖「行首形态」** ⇒ 见 **P2-r4-1**（本轮最重的新证据）。

### H3 交叉一致（成对项 · 本轮重点） —— ⚠️（**乘积已对；副本仍有 4 组漂移，全部在非总纲文件**）

> 派单点名的三类成对项，逐类实测：

| 成对项类别 | 结论 | 实测 |
|:--|:--:|:--|
| **数字的乘积 N×M** | ✅ **对** | 唯一乘积：`development.md:52`「16 角色 × 2 家 = **32** 生成物」；实测 `.qoder/agents/*.md` 16 + `.codex/agents/*.toml` 16 = 32 ✓。另 `checklists` 侧「14/16 同名 + 3 专项 = 17」三处一致（`README:46` · `AGENTS.md:59` · `engineering:113`）。 |
| **成对出现的路径（同一脚本多处引用）** | ❌ **有漏** | **同批改了 `serve_web.sh` 的守卫侧、漏了 runbook 侧**：`ai-guard-release.sh:290,296` 已修为 `sh scripts/serve_web.sh`，而 `rules/guides/development.md:43` 仍是死路径 `sh .agents/mechanism/scripts/serve_web.sh`（P2-r4-1）。同名脚本 `data-verify-tdx.py` / `data-prewarm-adj-factors.py` 与已修的 `data-sync-stock-names.py` 是**三胞胎只改一**（P2-r4-2）。 |
| **同一事实多处副本** | ❌ **有漏（4 组）** | ① **角色数/构成**：`roles/_directory.md:16` 写 12（「8 客观官 + 1 对抗官 + 3 外部视角官」）vs README/AGENTS/engineering/review-driven 的 **16**；② **清单数/构成**：`checklists/_directory.md:16` 写 14（8+1+3+守护/成本）vs 实际 **17**；③ **改名后的清单文件名**：`guard.md` / `cost.md` / `review-<官>.md` 5 处（真名 `ai-guard-checklist.md` / `ai-cost-checklist.md` / `<角色名>.md`）；④ **走查存档份数**：`docs/_index.md:32` 写 **27** vs `docs/README.md:53` / `records/_index.md:22` 的 **31**（实测 31）。 |

### H4 跨目录双源 —— ⚠️（与 r3 同：**未漂，但第四份详述仍未降级为指针**）

- 「顶层职责」仍存在**四份**：`.agents/_index.md:28-36`（表：顶层/类/装什么/索引/契约）· `.agents/README.md:39-47`（自标速查 + 指向 `engineering §二`，`:54`）· `rules/assets/ai-context-engineering.md:63-115`（`:65` 自 declared 唯一详述源）· `.agents/_directory.md:21`（一行枚举）。
- **实测四处当前一致**（本轮逐行对拍 7 顶层的「装什么」）⇒ **不是漂移缺陷**；问题是 `_index.md` 那份**既无指针声明、也无守卫查跨目录重复**（`S5` 只查**同一目录内**两件套整行重复）⇒ 下次结构变动它仍是第一顺位漂移点。
- r3 的建议（把 `.agents/_index.md:26` 那表降级为「顶层速查（详述见 …§二）」）**本批未做**，状态与 r3 相同。

### H5 滞后 —— ⚠️（本批改动过的文件 `updated` 已跟上；**旧漂移不回溯** + **1 份整节滞后**）

| 位置 | 症状 | 证据 |
|:--|:--|:--|
| `.agents/toolkit/skills/_index.md` | `updated: 2026-10-03`，内容 10-06 已改（r3 P2-2 的唯一残留） | `grep -m1 '^updated:'` vs `git log -1 --format=%ad --date=short` |
| 4 个 10-06 内容已改的文件 | `updated` 停旧（新逻辑不回溯） | `product-roadmap.md`(10-04) · `naming-spec.md`(10-04) · `roles/docs-design-writer.md`(10-04) · `skills/data-learn-writer/SKILL.md`(09-16) |
| **`.agents/rules/guides/skills-usage.md`** | `updated` 已被刷到 **10-06**，**但正文 §二 整节停在旧模型**（description 说「**11 个技能**」· `:24` 标题说「技能清单（**12 个**）」· 实际是 **4 个技能包 + 16 个角色**，且角色**不注册为技能**——见同文件 `:87` 与 `AGENTS.md:56`）⇒ **日期看起来是新的，内容是旧的**（最危险的一种滞后） | 见 **P3-r4-3** |
| 本批实际改动过的规范/文档 | 已正确刷新（`--fix` 按 git 生效） | `conventions.md` · 3 个 `SKILL.md` · `reference/_directory.md` · `ai-context-layer-spec.md` 等均 = 10-06 ✓ |

> **根因同 r3**：`ai-guard-meta.sh:152-159` 的 git 版只覆盖「本次脏」；`frontmatter-spec.md:28` 仍承诺「`updated` … **工具/流程**（/ship 自动）… 手写必漂移，机器维护」——比实际机制更强。

### H6 结构断裂 —— ✅（**完整通读**五份总纲 + 本批 27 个改动文件，**无断裂**）

- **完整通读**：`.agents/README.md`（89 行）· `AGENTS.md`（88 行）· `rules/assets/ai-context-engineering.md`（411 行）· 根两件套（`_index.md` 49 · `_directory.md` 66）：**无**「两套结构并存 / 尾巴挂多余 `\|` / 丢标题的表」；r2 的四项（README 双表 · 孤立残行 · 丢标题 · mermaid `TK`）**无回退**。
- **本批 27 个改动文件**专门跑「非表格行却以 `|` 结尾」扫描：**0 命中**；`skills-spec.md:60` 的重复子句（r3 记的那条）已消除。
- 机械扫（`.agents/**/*.md` + `docs/**/*.md`，排除账本/存档/RFC）的「表头后直接接数据行」命中项逐条核过：**全部是多行长单元格换行**（末列含 `/`、下一行是同一行的续行）⇒ **假阳性，非缺陷**（与 r3 同判）。

### H7 死胡同与孤儿 —— ⚠️（全按新判据：先分「指路」/「记事」）

**① 指路（必须可解析）—— 两类死路径（都逃过 M4）**

| 类别 | 位置 | 原文 | 实测 |
|:--|:--|:--|:--|
| **a. `cd X && <解释器> <路径>` 形态** | `.agents/rules/guides/development.md:43` | `cd apps/adai-app && sh .agents/mechanism/scripts/serve_web.sh` | 不存在（真身 `apps/adai-app/scripts/serve_web.sh`）；M4 代码块正则要求**行首**是解释器（`ai-guard-meta.sh:214`），该行以 `cd` 开头 ⇒ 逃检 |
| **b. 注释 / 文档串里的 `用法: <解释器> <路径>`** | `mechanism/scripts/data-verify-tdx.py:7` · `data-prewarm-adj-factors.py:12` | `用法: python3 ai-engineering/09-.agents/mechanism/scripts/<同名>.py` | 不存在；行以 `用法:` 开头且无反引号 ⇒ M4 两处都够不着 |
| **c. 知识文档里的 `<旧前缀>/09-.agents/…`** | `knowledge/reference/designs/framework-plus-plugin-model.md:166` · `designs/trading-data-adjustment.md:100` · `manuals/trading-features.md:287` | `` `09-.agents/mechanism/scripts/{update-current.sh,sync-adj-factors.sh,sync-adai-rulepack.sh}` `` | `update-current.sh` / `sync-adai-rulepack.sh` 真身在 **`os/trading-engine/09-scripts/`**；**`sync-adj-factors.sh` 全库不存在**（`git ls-files \| grep sync-adj-factors` 空）|

```bash
# 复现（a/b/c 一次列全：M4 只认行首解释器 + 反引号内 .agents/ 开头）
python3 - <<'EOF'
import re, pathlib
ROOT = pathlib.Path('.').resolve()
cb  = re.compile(r'^\s*(?:bash|sh|zsh|python3?)\s+([\w./-]+)')          # ai-guard-meta.sh:214
inl = re.compile(r'`(?:bash|sh|zsh|python3?)\s+([\w./-]+\.(?:sh|py))')  # ai-guard-meta.sh:225
for f, ln in [('.agents/rules/guides/development.md', 43),
              ('.agents/mechanism/scripts/data-verify-tdx.py', 7),
              ('.agents/mechanism/scripts/data-prewarm-adj-factors.py', 12)]:
    line = pathlib.Path(f).read_text().splitlines()[ln-1]
    print(f"{f}:{ln}  代码块正则={bool(cb.match(line))}  行内正则={bool(inl.search(line))}")
EOF
# → 三条全部 False / False（守卫看不见）
ls apps/adai-app/scripts/serve_web.sh          # ✅ 真身在此
git ls-files | grep -c 'sync-adj-factors'      # → 0（真身不存在）
```

**② 指路 — 改名后残留的裸文件名（`guard.md` / `cost.md` / `review-<官>.md`）**：

| 位置 | 原文 | 实测真名 |
|:--|:--|:--|
| `.agents/toolkit/checklists/_directory.md:35` | 收尾自检 → `guard.md`（G1–G7） | `ai-guard-checklist.md`（`rules/process/review.md:38` 用的就是真名）|
| `.agents/toolkit/checklists/_directory.md:36` | 成本判断 → `cost.md` | `ai-cost-checklist.md` |
| `.agents/toolkit/checklists/_directory.md:34` · `:50` | 「对应 `review-<官>.md`」「新增 `review-<官>.md`」 | `<角色名>.md`（10-03 全域改名后）|
| `.agents/toolkit/roles/_directory.md:49` | 「建 `checklists/review-<name>.md`」 | 同上 |
| `.agents/toolkit/checklists/code-backend-reviewer.md:16` · `code-frontend-reviewer.md:16` | 「守护项（G#）见 `guard.md`」 | `ai-guard-checklist.md` |

> **M4 为何全绿**：这些是**裸文件名**（不以 `.agents/`/`docs/` 开头、也非 `bash <path>` 命令），`M4` 的两种形态都够不着——与 r3 记的「缩写路径盲区」同源（`pitfalls.md:348`）。

**③ 记事（按新判据）—— 不计入，逐条列出以免复报**：
- **历史数字/史料**：`pitfalls.md:221` 的「T1–T7」（2026-10-03 已用记录）· `pitfalls.md:292`「本会话建了 15 个守卫 / 7 顶层」（2026-10-04 归纳）· `engineering.md:396`「2026-10-04（归纳两批）… 29 个受管子目录 … 目录两件套 30 组」· `change-log.md:49`「13 角色 + 14 清单」（2026-10-03 改名批台账）。
- **迁移映射表**：`docs/README.md:66-87` 的旧→新对照（含 `docs/review/audits/`「27 份走查存档」= **旧值即设计**）。
- **历史文件名**：`layer-spec:81`「我们那 12 个子目录」（说的是**迁移前**的形态，佐证「别把真相源放进 `.agents/`」这一硬约束）· `naming-spec.md:169`「`task-ship` 已被技能占用」（改名史）· `rfc/**` 与 `ADR-001..006`。
- **scope 标签**：`ai-guard-meta.sh:3` 头注释 + `mechanism/{,_guards}/_index.md` 的「ai-engineering/ + AGENTS.md frontmatter 契约自检」。
- **坑与史志旧路径**：`ai-setup-launchd.sh:211` · `ai-sync-agents.sh:91` · `ai-worktree-prep.sh:39,147` · `scaffold.md:42` · `pitfalls.md:263,348` · `docs/records/**` · `docs/archive/**` · `.worktrees/**`。
- **Mermaid 概念节点**：`engineering.md` 图内节点名（如 `L2 约束层`）。

**④ 孤儿**：M3 全绿（`META-GUARD` 无 M3 FAIL）✓。

### H8 可执行性 —— ✅（命令本体都能跑；**唯一不合格是文档里那 3 条死路径**）

| 文档里的命令/对象 | 实测 |
|:--|:--|
| `bash -n` 全量 shell（`git ls-files '*.sh'` 43 + `.githooks/pre-commit` = **44** 个） | **0 语法错误** |
| `bash .agents/mechanism/scripts/task-cadence.sh`（无参数） | 实跑正常（状态总览 + 到期红线 + LaunchAgent） |
| `python3 .agents/mechanism/scripts/ai-domain-view.py overview` | 实跑正常（37 功能 / 2462·440·409·72 / 端点 173） |
| `bash .agents/mechanism/scripts/ai-sync-all.sh --check` | ✅ git hooks + 技能出口 4 + 子代理出口 2 组 |
| `ai-guard-release.sh` 打印的「③ 下一步」 | ✅ **已可执行**（`cd apps/adai-web\|adai-admin && sh scripts/serve_web.sh …`，`apps/{adai-web,adai-admin}/scripts/serve_web.sh` 实件在）——r3 P2-1 的「最刺眼那条」确已修好 |
| `ios-release.md` / `testflight-external-testing.md` / `backend-deployment.md` / `routine.md` 的发版命令 | ✅ 真身路径正确（`apps/adai-app/scripts/…`） |
| `development.md:43` | ❌ 不可执行（`No such file`）——归 P2-r4-1 |

## 四、待修清单（P0–P3）

> **无 P0 / 无 P1**（全是叙事层 / 机制层，不涉及数据丢失、错误行为或对外承诺）。
> **有 1 条 P2 新发现（P2-r4-0）**：本批**自己引入**的守卫回归——`--fix` 的 `lines` 校准静默失效且假成功。它落在 **H2「文档 ⇄ 行为」**（守卫声称的能力实际不工作），把 r3 的 H2 ⚠️ 从「观察级」推到 P2。另 2 条 P2 是 r3 既有类别的**残留**（P2-1「M4 抓不到的死路径」· P2-2「`updated` 漂移」）。

| 级别 | # | 症状 | 根因 | 落点 | 建议改法 |
|:--:|:--:|:--|:--|:--|:--|
| **P2** | r4-0 | **守卫 `--fix` 的 `lines` 校准静默失效 + 假成功（本批回归）**：任何 `lines` 声明 ≠ 实际的**已提交**文件，`--fix` **不写盘**，却打印「`--fix: 无漂移，无需回写`」。**现场**（2026-10-06 15:56–16:05，已完整记录）：`bash ai-guard-meta.sh` → `M2 … inbox.md: lines 声明 94 != 实际 108`；`bash ai-guard-meta.sh --fix` → 「无漂移，无需回写」；`grep -m1 '^lines:' inbox.md` → 仍是 **94**（该字段后由本单**手工**同步为 108 以满足交付判据，见 §一/§五）。**M2 会永远 FAIL 且自带解药无效**（全仓唯一会写 `lines:` 的就是这个 `--fix`，`grep -rn "lines: %d" .agents` 无第二处）。**可复现（不依赖任何具体文件）**：把 `:147-159` 的逻辑原样跑一遍——`lines_fixed = True` 而 `_new != lines = False` ⇒ **不写盘** | `ai-guard-meta.sh:150` **先把 `lines[i]` 就地改掉**，`:154` 才 `_new = list(lines)` 复制 ⇒ `_new` 与 `lines` **恒等**，`:158` 的 `if _new != lines:` 幂等判断在「只差 `lines` 字段、`updated` 已是今天」时**永远为假** ⇒ 跳过 `write_text`。**旧代码（`cb89e6e5^`）是 `if lines_fixed: … f.write_text(...)` 无条件写**，故这是本批「加幂等判断」时引入的回归 | `ai-guard-meta.sh:147-159` | 把幂等基线改成**改动前**的快照（`orig = list(lines)` 放到 `:148` 之前，或直接与原始 `whole` 文本比对），`:158` 改为 `if _new != orig:`；修完**必须做反例回归**：造一个 `lines` 声明错的临时文件 → `--fix` → 确认真的写盘、且第二次跑才「无漂移」（这正是 `pitfalls.md:281` 的复发信号，本批未做该回归） |
| **P2** | r4-1 | **M4 扩类只覆盖「行首」形态** ⇒ 两个形态整类逃检：`cd X && <解释器> <路径>`、注释/文档串里的 `用法: <解释器> <路径>`。实证残留 1 行 runbook 死命令（`development.md:43`，照着跑即 `No such file`）+ 2 处脚本用法行 | `ai-guard-meta.sh:214` 的正则锚 `^\s*(?:bash\|sh\|zsh\|python3?)\s+`（要求**行首**）；`:225` 要求**反引号**包裹 | `ai-guard-meta.sh:214`（+ 可选 `:225`）· 直接修 `rules/guides/development.md:43`（已 `cd apps/adai-app` ⇒ 改 `sh scripts/serve_web.sh`） | ① 先修 1 行 + 2 处用法行（`data-verify-tdx.py:7` · `data-prewarm-adj-factors.py:12` → `python3 .agents/mechanism/scripts/<同名>.py`）；② 若要机械化：把代码块正则从「行首」放宽为「**任一处** `(?:^\|&&\|;\|\|)\s*(?:bash\|sh\|zsh\|python3?)\s+`」+ 认 `#`/`用法:` 前缀；**必须先做反例回归**（pitfalls 二十三：`bash -n`、`bash TOKEN=…` 这类参数误判的教训已在 r2 踩过），确认不报 `cd build/web && tar …` 这类无脚本命令 |
| **P2** | r4-2 | **`updated` 漂移同型残留 + 机制不回溯**：`toolkit/skills/_index.md`（10-03 vs 内容 10-06）+ 4 个 10-06 内容已改的文件（`product-roadmap.md` · `naming-spec.md` · `docs-design-writer.md` · `skills/data-learn-writer/SKILL.md`）永不自愈 | `ai-guard-meta.sh:152-159` 只刷「**当前 `git diff` 脏**」的文件；`frontmatter-spec.md:28` 却承诺「机器维护」 | `ai-guard-meta.sh:135-159`（可选加 `--refresh-updated` 全量模式）· 上述 5 文件 · `frontmatter-spec.md:28` | 二选一：① `--fix` 增一个**显式**全量档（`--fix --refresh-updated`：对全部文件用 `git log -1` 比对后刷），在 `/ship` 里跑一次；② 把 `frontmatter-spec.md:28` 改为「`updated` **仅在文件被本批改动时**由 `/ship` 刷新」，如实描述现行机制 |
| **P2** | r4-3 | **`skills-usage.md` 日期新、内容旧（整节停在废除的模型）**：description 说「**11 个技能**」· `:24` 标题「技能清单（**12 个**）」· §二 把 9 个**审查官**列为「审查技能」· 4 处教人「加载 `ship` 技能」（实名 `task-ship`）· 漏了 `data-learn-writer`；而同文件 `:87` 已按新模型写「16 个角色…**不注册**」⇒ **一个文件两套模型** | 2026-10-03/06 技能迁目录布局 + 审查官改走 subagent 出口（不再算「技能」），正文未回扫；`updated` 又被 `--fix` 刷成 10-06（掩盖了滞后） | `.agents/rules/guides/skills-usage.md:3,24-46,32,53,59,65` | 重写 §二 为「**4 个技能包**（code-api-writer / code-domain-writer / task-ship / data-learn-writer）+ **16 个审查官**（走 subagent 出口，不注册为技能，见 `AGENTS.md:54-56`）」；`description` 的「11 个」同步；4 处 `ship` → `task-ship` 或「`/ship` 流程」 |
| **P3** | r4-4 | **`roles/_directory.md` 与 `checklists/_directory.md` 的构成枚举停在旧口径**：roles 写「8 客观官 + 1 对抗官 + 3 外部视角官」= **12**（实际 **16**，漏需求评审/产作者/流程官/体系体检）；checklists 写「8+1+3+守护/成本」= **14**（实际 **17**）；另 `roles/_directory.md:41` 命名例外的白名单漏 `docs-design-writer` | 加角色只改了总纲与 `_index.md`，**子目录契约的「职责」行没回扫**（S6 只扫总纲） | `.agents/toolkit/roles/_directory.md:16,41` · `.agents/toolkit/checklists/_directory.md:16` | roles 改「**16 个角色定义**（产作者 1 · 审核者 14 · 流程官 1；构成见 `../../rules/process/review-driven.md` §0）」；checklists 改「**17 份** = 14 份与角色同名 + 3 份专项」；`:41` 例外补 `docs-design-writer` |
| **P3** | r4-5 | **5 处裸文件名引用指向改名前的文件**：`guard.md`（真名 `ai-guard-checklist.md`）· `cost.md`（`ai-cost-checklist.md`）· `review-<官>.md`（`<角色名>.md`） | 2026-10-03 全域改名（`change-log.md:49` 记「75 文件 · 399 处」）**漏了这几处**；裸文件名不触发 M4 | `toolkit/checklists/_directory.md:34,35,36,50` · `toolkit/roles/_directory.md:49` · `toolkit/checklists/code-backend-reviewer.md:16` · `code-frontend-reviewer.md:16` | 逐处改真名（`rules/process/review.md:38` 已是正确写法，可照抄）；建议顺手在 `ai-context-reviewer` 清单加一条人肉判据「裸文件名引用 ≤ 改名批的残留」（**属新判据 ⇒ 需绑事故走 skills-spec §七 门禁**） |
| **P3** | r4-6 | `skills-spec.md` 技能名写 `ship`（实名 `task-ship`），2 处 | 10-06 改写该行时只删了重复子句，没核对技能名；`naming-spec.md:169` 与 `AGENTS.md:56` 都是 `task-ship` | `.agents/rules/assets/skills-spec.md:60,79` | 改 `task-ship`（`:79` 是划除行，可一并改或保留原样并注明）|
| **P3** | r4-7 | `skills-spec.md:73` 导语「**三条**有意偏离」vs 表内**有效四条**（1/2/4/5，#3 已划除）——r3 P3-9 **未清** | 划除偏离 3 时把导语从「四条」改成「三条」，但划除行仍在表内计数 | `.agents/rules/assets/skills-spec.md:73,79` | 二选一：① 删掉偏离 3 整行 ⇒ 导语「三条」自洽；② 保留划除行 ⇒ 导语改「**四条**有意偏离（另有 1 条已于 2026-10-06 消除）」 |
| **P3** | r4-8 | `docs/_index.md:32` 走查存档写 **27** 份（实际 **31**）——同一事实的**第三处副本** | r3 批把 `docs/README.md:53` 与 `records/_index.md` 从 30 改到 31，**漏了 docs 根索引这一份** | `docs/_index.md:32` | 改 **31**（本报告落盘后为 32；建议与该两处一并刷，见第五节） |
| **P3** | r4-9 | `audit.md` 的「不进本走查」说明没跟上第 16 个角色：`:19` 写「**后三组**不进本走查」（枚举却是 4 组）· `:36` 的「不进本流程」枚举未列体系体检官 | 上批补角色数（15→16）时只补了数字与枚举，**说明句没跟** | `.agents/rules/process/audit.md:19,36` | `:19` 改「**后四组**不进本走查——需求评审 / 产作者 / 流程官 / **体系体检**」；`:36` 枚举同步补 `体系体检官` |
| **P3** | r4-10 | 3 处知识文档引用 `<旧前缀>/09-.agents/mechanism/scripts/…`：`update-current.sh` / `sync-adai-rulepack.sh` 真身在 `os/trading-engine/09-scripts/`；**`sync-adj-factors.sh` 全库不存在** | 2026-10-03 批量路径改写的残留（与 r3 P2-1/P3-11 同源） | `knowledge/reference/designs/framework-plus-plugin-model.md:166` · `designs/trading-data-adjustment.md:100` · `manuals/trading-features.md:287` | 前两处改 `os/trading-engine/09-scripts/<同名>`；`trading-data-adjustment.md:100` 的 `sync-adj-factors.sh` 既不存在，建议改指真实入口（`data-prewarm-adj-factors.py`）或删该半句 |

**观察级（不列待修，供主链取舍）**：`engineering.md:112` 与 `rules/process/_directory.md` 的职责行把 `cadence.md` 写成「**task-cadence**」（那是脚本名；README:64 写的是 `cadence`——r3 已记，仍在）· `tests/` 缺 M4 扩类的反例 fixture（H2-1）· H4 的「第四份详述」未降级为指针（H4）· `AGENTS.md:42` 的报告命名约定未覆盖复检（`-r2/-r3/-r4`）· **`.agents/_directory.md:19` 把 `roles/` `skills/` `checklists/` 归在「③ 规则」**（同文件 `:21` 的 7 顶层里它们在 `toolkit/` ⑤ AI 能力）——取证有限、可能只是"罗列全部叶子目录"的松散写法，**未列待修**。

**已知且在案、不新增**：`engineering.md:402`「`.agents/` 199 份」· `docs/` 侧 3 区缺 `_directory.md`（`engineering.md:403` 自记）· `change-log`/`task-log`/`REVIEW`/`docs/records/`/`docs/archive/`/`docs/research/` 的 `lines` 与旧路径（append-only 账本与档案馆，**按设计豁免**）· `.agents/skills/` 出口位不计顶层。

## 五、纪律声明（改了什么 / 没改什么）

- **只报告不改**（B7）：**未动**第二节 13 条与第三节/H 段点名的**任何被点名文件**（`development.md` · `data-verify-tdx.py` · `data-prewarm-adj-factors.py` · `skills-usage.md` · `skills-spec.md` · `roles/_directory.md` · `checklists/_directory.md` · `docs/_index.md` · `audit.md` · `ai-guard-meta.sh` …）。
- **`--fix` 跑过一次，但未发生任何写入**（这本身是 P2-r4-0 的证据）：`bash .agents/mechanism/guards/ai-guard-meta.sh --fix` → 「无漂移，无需回写」，`inbox.md` 的 `lines: 94` 原封不动。
- **为满足交付判据 C1，本单手工同步了 1 个字段**：`.agents/workspace/_meta/inbox.md` 的 `lines: 94 → 108`（**这正是已失效的 `--fix` 本该做的事**，`git status` 里该文件的其它内容为主链写派单所改、非本单）。若该 bug 修好，此动作会自动完成。
- **守卫生成物**：`docs/records/audits/*` 在 meta 的**豁免区**（`ai-guard-meta.sh:54`）⇒ 本报告不入 `files`，meta 文件数仍 **299**。
- **仅为满足交付判据 C1 的守卫同步（3 处，全部记录在案）**：① 本报告落盘；② 登记 `docs/records/_index.md`（走查存档 31 → **32**）；③ `docs/README.md:53` 的「走查存档 31 份」→ **32**（**本报告落盘导致的派生数字**）。**注**：`docs/_index.md:32` 的 27 属**被点名项**（P3-r4-8），按 B7 **未改**。
- **除上一条外未写派单文件**：`inbox.md` 的**正文一个字未动**（只同步了 `lines` 声明值）。

```
META-GUARD: PASS (299 files, edges/lines/orphans all ok)
STRUCTURE-GUARD: PASS (30 个子目录 · 两件套齐备 · 清单⇄实际一致 · 依赖与守卫引用有效 · 两件套不重复)
```

## 六、封箱判定（本轮要交付的结论）

**判据（派单约定）**：「**连续两轮无 P2 以上新类别 ⇒ 封箱**（转每周节奏）」。

**r4 的事实（可复核）**：
- **P0/P1：0**。
- **P2 以上新发现：1 条**——**P2-r4-0**：守卫 `--fix` 的 `lines` 校准静默失效 + 假成功，**本批 `cb89e6e5` 自己引入的回归**（同 `pitfalls.md:281` 家族，属复发信号）。
- **P2 残留：2 条**（r4-1 M4 形态盲区 · r4-2 `updated` 不回溯）；**P3：8 条**（r4-3～r4-10）。
- **类别清单基本稳定**：除 P2-r4-0 外，r4 其余发现都落在 r1–r3 已建立的类别里（H1/H3 副本漏改 · H5 滞后 · H7 指路死路径）；**但 P2-r4-0 落在 H2「文档 ⇄ 行为」**，而 r3 的 H2 只到「观察级」——**故它是本轮的新类别**。

**结论：本轮不能封箱（不满足「连续两轮无 P2 以上新类别」）。** 理由与收口路径：

1. **判据本身不满足**：r4 出现了 **1 条 P2 新类别**（H2 · 守卫假成功），且它与 r3 的病因**同源**——「上一轮修复自己产生的伤」第四次出现（r3 是 `skills-spec` 双写 / 乘积没跟；r4 是「加幂等判断」把 `lines` 校准改坏）。**在这个信号消失前封箱，等于把一条已知的守卫失效留在系统里**。
2. **但代价很小、边界很清楚——不需要再开一轮全量体检**：收口 = **1 行代码语义修正**（P2-r4-0）+ **1 处 M4 形态放宽（可选）** + **3 处路径** + **8 条文档收口**（r4-3～r4-10，全部已给「文件:行 + 改法」）。建议并成 **一个收口小批** 走 `/ship`，**先修 P2-r4-0 并补反例回归**（否则 `lines` 会永久漂移、M2 永远 FAIL）。
3. **复检用「轻量档」，不单开 r5 全量轮**：修完只跑 —— ① `ai-guard-meta.sh`（应 **PASS 299**）· ② `--fix` **幂等 + 反例**复跑（对一个故意写错 `lines` 的临时文件必须先写盘、第二次才「无漂移」）· ③ 本报告 §四 的 11 条落点逐条 `grep`。成本约 r4 的 1/5。**该轻量档通过 ⇒ 立即封箱转每周**。
4. **转每周后保留的护栏**：`ai-guard-health.sh --full`（六维+健康分）· `ai-guard-meta/structure/skills/feature/align`（含 git pre-commit 自动触发）· `task-cadence.sh weekly` 的 W1–W6；**人肉判据**（H1–H8 里机械化不了的：跨目录副本 / 裸文件名 / 整节滞后 / 守卫的「声称 vs 实际」）按 `AGENTS.md` 规则 12「结构类改动之后 + 里程碑前」**按需触发**。
5. **若主链判定 P2-r4-0 属 `pitfalls.md:281` 的「已知类别复发」而非新类别**，并愿意承担「`lines` 继续漂移 + M2 长期 FAIL」——那也可以**直接封箱**；但此时**必须把 r4-0 排进收口小批的第一条**，不能因为封箱而不修。

> **给主链的一句话**：**本轮不宜封箱**——不是因为问题多，而是因为**唯一的新 P2 打在本项目最硬的那类资产（守卫）上，且症状是「报成功但没做事」**。修完 r4-0（+ 顺带 r4-1/r4-2 与 8 条文档）后跑一次**轻量复检**，即可封箱转每周。

---

## 附：本次**未**采信的候选（遵循「宁可报少，不可报错」）

- **`.agents/_directory.md:19`** 把 `roles/` `skills/` `checklists/` 列在「③ 规则」下（而同文件 `:21` 的 7 顶层把它们归 `toolkit/` ⑤）：判为**松散罗列**（该行的用途是"哪些资产算容器内"），**证据不足以定罪**，列观察级。
- **`layer-spec.md:81`「我们那 12 个子目录」**：说的是 `.agents/` 迁移**之前**的形态（论证「别把真相源放进被工具扫描的目录」），按 H7(c)/(e) **记事**，不计。
- **`docs/README.md:66-87` 的旧→新对照表**（含 `docs/review/audits/`「27 份走查存档」· `docs/rfc/`「66 份」）：**迁移映射表 = 旧值即设计**，按 H7(d) **记事**，不计（该文件在 `M4_SKIP` 内）。
- **`pitfalls.md:221`「T1–T7」**：2026-10-03 的事故记录（配「✅ 已用（2026-10-03）」），**历史数字**，按 H7(f) 不计。
- **`engineering.md:396`「29 个受管子目录」**：带日期的历史批量账（「2026-10-04（归纳两批）」），与 `:67`「30 个受管子目录（含容器根）」的当期口径分工不同 ⇒ **记事**，不计。
- **`.worktrees/*` 下的同名旧文件**：其他 worktree 的检出副本，本单范围外。
- **60+ 处缩写路径**（`process/review-driven.md` 实指 `rules/process/…`、`guards/ai-guard-meta.sh` 实指 `.agents/mechanism/guards/…`）：与 r2/r3 同判——**成立缩写，非断链**（`pitfalls.md:348` 的 M4 裸路径教训）。
- **`.agents/mechanism/guards/tests/` 的相对路径**（`guard-feature-fixture.py` 等）：按**本文件所在目录**解析成功 ⇒ 非死路径。
- **`.agents/mechanism/guards/ai-guard-prod.sh:266` `python3 /tmp/_adai_probe.py`** · **`ai-lint-shell-vars.py:30` `sh b.sh`** · **`backend-deployment.md:323` `python3 /opt/adaios/serve_static.py`**：分别是**运行时生成探针**、**脚本内自测样例**、**生产机路径**，非文档指路 ⇒ 不计。
