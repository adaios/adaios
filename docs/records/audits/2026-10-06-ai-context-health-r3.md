---
title: AI 上下文体系体检报告（r3 · D-20261006-09）
description: ai-context-health-reviewer 第三轮体检——机械层照抄（health 75/100 · structure PASS 30 · meta 2 FAIL〔均在派单文件〕· tools 12-0-0 · skills/feature/align PASS）+ r2 的 12 条逐条回归（6 清 · 4 部分清/变形 · 2 未清）+ H1–H8 新发现（无 P0/P1）；重点 H1 数字 · H2 文档⇄行为 · H4 跨目录双源 · H6 结构断裂 · H7 死胡同（用新判据）；只报告不改，每条带文件:行 + 可复现命令
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 265
depends-on: []
related:
  - ./2026-10-06-ai-context-health-r2.md
  - ./2026-10-06-ai-context-health.md
  - ../../../.agents/toolkit/roles/ai-context-health-reviewer.md
  - ../../../.agents/toolkit/checklists/ai-context-health-reviewer.md
  - ../../../.agents/rules/assets/ai-context-engineering.md
tags: [audit, ai-context, health]
---

# AI 上下文体系体检报告（2026-10-06 · r3）

> **触发**：派单 `D-20261006-09`（`AGENTS.md` 规则 12「体检」的叙事层）；执行者 `ai-context-health-reviewer`。
> **两个目的**：① 验证 r2 的 12 条**是否清零**（逐条回看落点）② 找出**新机制/新数据**引入的问题。
> **判据**：本轮按 **H7 新判据**——**先分「指路」还是「记事」**；六类记事（scope 标签 / Mermaid 概念节点 / 历史文件名 / 迁移映射表 / 坑与史志旧路径 / 历史数字）**按设计就该写旧的，不计**。
> **纪律**：只报告不改（B7）· 每条带 `文件:行` + 可复现命令 · 猜的不写 · **不重写已有判据**（机械层照抄）· 宁可报少不可报错。
> **复现环境**：`/Users/adai/Projects/adaios` @ 2026-10-06 15:20–15:40。机械层采于 `git log -1` = **`5a3c49e1`**；15:20:58 主链并发落了一个只碰 3 个文件的小批 **`2e8f1689`**（H7 判据升级 + `.agents/workspace/_meta/` 纳入 M4_SKIP + 同步 `inbox.md` 的 `lines`）——**本报告所有发现均在该提交前后各复验一次，无一受影响**；工作区另有未提交的 r2/r3 登记改动。

## 一、机械层结论（照抄，不解读）

| 守卫 | 本单实测 | 与派单材料的差异 |
|:--|:--|:--|
| `ai-guard-health.sh --full` | **75 / 100** —— ①结构 ✅ ②元数据 ❌(2 FAIL) ③命名 ✅ ④体积 ✅ ⑤契约 ✅ ⑥新鲜度 ✅ | 派单写「100/100」 |
| `ai-guard-structure.sh` | **PASS**（**30** 个子目录 · 两件套齐备 · 清单⇄实际一致 · 依赖与守卫引用有效 · 两件套不重复） | 一致（r2 为 29 → **含容器根后 +1**） |
| `ai-guard-meta.sh` | **2 FAIL**（同一文件：`inbox.md`） | 派单写「PASS 299」 |
| `ai-guard-tools.sh` | **12 通过 / 0 警告 / 0 失败**（T1–T8） | 一致 |
| `ai-guard-skills.sh` | **PASS**（20 个技能包 · S3/S4/S5/S7） | 一致 |
| `ai-guard-feature.sh` | **PASS**（37 功能 · 3 卡文件 · 9 张卡） | 未在派单列出，补跑 |
| `ai-guard-align.sh` | **PASS**（A1 173 端点 · A2 后端 2462 / app 440 / admin 72 / web 409） | 未在派单列出，补跑 |

两条 FAIL 逐字：

```
M2 .agents/workspace/_meta/inbox.md: lines 声明 82 != 实际 94
M4 .agents/workspace/_meta/inbox.md: 正文路径引用不存在 docs/records/audits/2026-10-06-ai-context-health-r3.md
```

> **差异归因（与 r2 同型，非守卫退化、也非派单写错）**：派单是在主链跑完守卫**之后**写进 `inbox.md` 的（`lines:` 未同步 ⇒ M2）；派单正文指向**本次要交付的报告**（落盘前 ⇒ M4，本报告落盘后自动消解）。**机械层结论是时点快照**——「主链跑时 100/100」与「此刻 75/100」可以同时为真。
> **两处 FAIL 的收口（并发主链，非本单所改）**：`2e8f1689`（15:20:58）同步了 `inbox.md` 的 `lines`，并把 **`.agents/workspace/_meta/` 纳入 `M4_SKIP`**（`ai-guard-meta.sh:192-195`，理由写明「派单文件按设计就会指向尚未落盘的交付物」是**流程固有摩擦、不是缺陷**）。此后实测 `META-GUARD: PASS (299 files)`——本单 **未写** `inbox.md`（见第五节）。

## 二、r2 的 12 条回归（逐条）

> 判据：回看**落点**是否真改 + 全域是否还有同类残留。命令一栏可逐条复现。

| # | r2 条目 | 判定 | 证据（文件:行 + 命令） |
|:--:|:--|:--:|:--|
| 1 | 容器根两件套陈旧（6 顶层 / rfc 68 / T1–T7 / 六层 / `lines: 30` / `bash guards/…`） | ⚠️ **多数清 · 1 处残留（新盲区）** | 主干全清：`.agents/_index.md:24`「**7** 个顶层」· `:39`「rfc（**67** 份）」· `:43` `workspace/` 独立成行 · `.agents/_directory.md:8` `lines: 66`（= `wc -l`）· `:40`「**多层**门禁（数字不写死）」· `:60` `bash mechanism/guards/ai-guard-structure.sh --fix`（按本文件目录可解析 ✓）。**❌ 残留**：`.agents/_index.md:3` description 仍写「**6 个顶层目录**」——`sed -n '3p' .agents/_index.md`。**新守卫为何没抓**：S6 的 `NUM_PAT` 要求数字落在 `（…）` 内，`:3` 是 `——**6 个顶层目录**（按知识性质分）` ⇒ 不匹配（复现见下方代码块） |
| 2 | M2 `lines` 盲区 11 处（4 组顶层两件套 + 根 `_directory.md` + `docs/README.md` + `docs/records/_directory.md`） | ✅ **清零** | 11/11 声明=实际（`declared` 逐条 `= wc -l`）。命令：`for f in .agents/knowledge/_index.md .agents/knowledge/_directory.md .agents/rules/_index.md .agents/rules/_directory.md .agents/toolkit/_index.md .agents/toolkit/_directory.md .agents/mechanism/_index.md .agents/mechanism/_directory.md .agents/_directory.md docs/README.md docs/records/_directory.md; do echo "$f $(grep -m1 '^lines:' $f) $(wc -l <$f)"; done`。覆盖面亦真：按 `ai-guard-meta.sh:38-70` 的枚举逻辑复算 = **299 文件**（与 commit 声明一致） |
| 3 | 技能目录化的配套文档（`skills-spec:60/:79` · `naming-spec:120-122` · `review-driven:106`） | ⚠️ **多数清 · 2 处残留（含 1 处本批新引入）** | ✅ `naming-spec.md:120-122` 三行已改「目录（官方布局…）」· `review-driven.md:106` 两个技能名均 `<name>/SKILL.md`。**❌ 新引入**：`skills-spec.md:60` 同一路径写两遍——「…建设/流程技能用 `.agents/toolkit/skills/<name>/SKILL.md`（官方目录布局，进攻侧）**+ `.agents/toolkit/skills/<name>/SKILL.md`（建设/流程技能，进攻侧——…）**」；`git show 5a3c49e1 -- .agents/rules/assets/skills-spec.md` 可证该行由本批重写。**❌ 未真清**：`:79` 偏离 3 划除后，导语 `:73` 改成「**三条**有意偏离」而表内仍有 **4 条有效行**（1/2/4/5）= r2 #11 的另一种形态 |
| 4 | 5 条行内命令死路径（`bash ai-engineering/…` ×4 + `bash guards/…`） | ✅ **清零** | `grep -rn 'bash guards/\|bash ai-engineering' --include='*.md' .agents docs \| grep -v docs/records/` → **无命中**；`.agents/_directory.md:60` 现可解析。⚠️ 但**同类以别的形态仍在**（`sh`/`python3` 命令，见 H7 与待修 P2-1） |
| 5 | `updated` 未随内容刷新（9 文件） | ❌ **未清（根因未动）** | 6 文件内容已改而 `updated` 停旧：`toolkit/skills/{code-api-writer,code-domain-writer,task-ship}/SKILL.md` 08-20 · `conventions.md` 08-18 · `ai-context-layer-spec.md` 10-05 · `skills-usage.md` 10-03 · `toolkit/skills/_index.md` 10-03 · `knowledge/reference/_directory.md` 10-04（对照 `git log -1 --format=%ad -- <file>` = 2026-10-06）。根因未变：`ai-guard-meta.sh:146`「updated 只在 lines 实际漂移时刷新」，`--fix` 只碰过 `toolkit/_index.md` |
| 6 | 层数口径 3 处（`engineering:330` · `review-driven:129` · `_directory.md:40`） | ✅ **清零** | `grep -rn '11 层\|六层' --include='*.md' .agents docs \| grep -v 'docs/records/\|change-log'` → 仅 `direction/rfc/20261003-skill-conformance-and-supply-chain.md:44`「六层」——**带日期的 RFC 设计稿 = 记事**，不计 |
| 7 | 不实数字（25 章 / 22 组 / `docs/README` 27 份） | ✅ **落点全清**（另见 P3-11） | `engineering.md:86` 现「**37 章**」（`grep -c '^## ' pitfalls.md`=37）· `:396` 现「目录两件套 **30 组**」（`find .agents -name _directory.md \| wc -l`=30）· `docs/README.md:53` 现「走查存档 **30** 份」 |
| 8 | `T1–T7` 残留 2 处 | ❌ **未清** | `grep -rn 'T1–T7' .agents --include='*.md'` → `.agents/_directory.md:56`（「工具接入自检（T1–T7，含出口真身）」）· `.agents/mechanism/guards/_directory.md:46`（「`ai-guard-tools` T1–T7」）；实测 `ai-guard-tools.sh` 输出 **T1–T8** |
| 9 | `docs/` 契约缺口表述「仅 `records/`」 | ✅ **清零** | `engineering.md:403` 现「5 个区中仅 `records/` 与 `research/` 有 `_directory.md`，其余 3 区只有 `_index.md`」；`find docs -maxdepth 2 -name _directory.md` = 2 家 ✓ |
| 10 | mermaid 未定义节点 `TK` | ✅ **清零** | 主树无 `TK`（唯一命中在 `docs/records/audits/…-r2.md` 的报告正文里，即记事）；全库 mermaid 块重扫无「用而未定义」真命中（`.worktrees/*` 旧副本除外） |
| 11 | `skills-spec.md:73` 导语「四条」vs 表内 5 行 | ⚠️ **改了方向、未真清** | 现为导语「三条」+ 表内 5 行（第 3 行 `~~…~~` 划除）⇒ **有效偏离 4 条**（1/2/4/5），仍差 1。`sed -n '73p;75,79p' .agents/rules/assets/skills-spec.md` |
| 12 | `inbox.md` `lines` 复发 + M4 指向未落盘报告 | ⚠️ **复发（同型 · 属派单文件特性，已被机制豁免）** | `wc -l .agents/workspace/_meta/inbox.md` 曾=94 而声明 82（M2 FAIL）；`2e8f1689` 已同步 `lines` 并把该目录纳入 `M4_SKIP` ⇒ 现 `META-GUARD: PASS`。**本单未自行写该文件**（见第五节） |

**小结**：**6 条全清**（#2 · #4 · #6 · #7 · #9 · #10），**4 条部分清/变形**（#1 · #3 · #11 · #12），**2 条未清**（#5 · #8）。**无 P0/P1**。
> 与 r2 相比的进步：**#1/#2 的机制批确实生效**（容器根进 S1/S2、M2 覆盖 299、M4 抓出 5 条行内 `bash` 死命令并已修）。**未清的两条恰是「机制批没碰的类别」**——`updated` 刷新逻辑与「T1–T7 措辞」。

## 三、H1–H8 复检

### H1 总纲 ⇄ 实际 —— ⚠️（主干已对，**「加角色/改结构后未全域回扫」的老毛病仍在 3 处**）

**✅ 已对（逐项实测）**：`README.md:41` rfc **67** ✓ · `:46` roles **16**（14/16 同名清单 + 3 专项）✓ · `:47`/`:61` guards **15** ✓ · `:33` 顶层 **7** ✓ · `engineering.md:86` 37 章 ✓ · `:88` 67 份 ✓ · `:110` 15 守卫 ✓ · `:113` 17 清单 ✓ · `:112` 流程 5 份 ✓ · `:108` 技能 4 目录布局 ✓ · `AGENTS.md:54` 角色 16（1 + 14 + 1）✓ · `:59` 14/16 + 3 专项 ✓ · `:73` `AGENTS.md` ×7 ✓（`git ls-files | grep -c AGENTS.md`=7）。

**❌ 未对**（全在**非总纲文件**里——S6 只扫 `README.md` / `AGENTS.md` / `ai-context-engineering.md` / 根两件套，见 `ai-guard-structure.sh:237-241`）：

| # | 位置 | 写了 | 实测 | 命令 |
|:--:|:--|:--|:--|:--|
| 1 | `.agents/_index.md:3` | 「**6 个顶层目录**」 | **7** | `sed -n '3p' .agents/_index.md`；`ls -d .agents/*/ \| grep -v skills/` = 7 |
| 2 | `.agents/rules/process/audit.md:19` | 「`roles/` 共 **15** 个角色定义 = 上述 9 个 + 外部视角官 3 + 需求评审官 1 + 设计产作者 1 + 流程官 1」 | **16**（漏了 2026-10-06 新建的**体系体检官**）——**同文件 `:36` 已写 16**，一处文件两口径 | `sed -n '19p;36p' .agents/rules/process/audit.md` · `ls .agents/toolkit/roles/*.md \| grep -v '_index\|_directory' \| wc -l` = 16 |
| 3 | `.agents/rules/guides/development.md:52` | 「**16 个角色 × 2 家 = 30 个生成物**」 | **32**（16×2）——「16」改对了，「30」没跟着改（旧 15×2） | `ls .qoder/agents/*.md \| wc -l`=16 · `ls .codex/agents/*.toml \| wc -l`=16（`ai-sync-agents.sh --check` 全绿） |
| 4 | `.agents/_index.md:42` | `README.md` 的职责「定位 + **三层结构** + 工具接入」 | README **已无**「三层结构」节（现为 `## 顶层结构（7 个顶层）`）；该节在 2026-10-06 总纲收口批被整体重写 | `grep -n '三层\|顶层结构' .agents/README.md` → 仅 `:33` |
| 5 | `.agents/_directory.md:56` · `mechanism/guards/_directory.md:46` | `ai-guard-tools` **T1–T7** | **T1–T8** | `bash .agents/mechanism/guards/ai-guard-tools.sh \| tail -2` |
| 6 | `.agents/rules/assets/ai-context-engineering.md:100` | 「契约与设计（**13 份**）」 | 该行自身 glob 是 `{contracts,designs,manuals}/*.md` = **16**；按括号里举的例子（api-spec / data-format-freeze / 五层架构 / 插件模型 / 记忆设计 / 交易设计）= `contracts` + `designs` = **12**——**13 无任何口径能对上** | `ls .agents/knowledge/reference/{contracts,designs,manuals}/*.md \| grep -v '_index\|_directory' \| wc -l` = 16 · `ls .agents/knowledge/reference/{contracts,designs}/*.md \| grep -v '_index\|_directory' \| wc -l` = 12 |

> **S6 为何漏 `:3`**（可复现，非猜测）：
> ```bash
> python3 - <<'EOF'
> import re
> NUM_PAT = re.compile(r'（\*{0,2}(\d+)\*{0,2}\s*个(顶层|角色)\*{0,2}[^）]*）')
> for ln in (3, 24):
>     line = open('.agents/_index.md').readlines()[ln-1]
>     print(ln, bool(NUM_PAT.search(line)), line.strip()[:50])
> EOF
> # → 3 False description: …——**6 个顶层目录**（按知识性质分）…   （数字在括号**前**，正则要求括号**内**）
> # → 24 True  ## 子目录（**7** 个顶层 · …）
> ```

### H2 文档 ⇄ 行为 —— ⚠️（在跑的机制都真在跑；**新增判据的「反例证明」缺**，另有一类命令逃检）

**✅ 实测在跑**：
- **M2 覆盖面真扩了**：按 `ai-guard-meta.sh:38-70` 复算 = **299** 文件（派单/commit 声明一致），`docs/records/_directory.md` 这类此前被 `is_light()` 整体豁免的目录契约现在**真的进严格档**。
- **S1/S2 真的收进了容器根**：`ai-guard-structure.sh:112-129` 用 `.` 代表根 + `_structural(AG)` 特判；`structure` 输出从 29 → **30 个子目录**。
- **M4 行内 `bash` 真抓得到**：`ai-guard-meta.sh:213-219` 新增行内扫描（只认像路径的），本单实测 `grep 'bash guards/\|bash ai-engineering'` = 0 命中（batch 已把 5 条修掉）。
- 4 个技能出口软链（tools **T4** PASS）· subagent 出口 **16 + 16**（`ai-sync-all.sh --check` 全绿）· `.githooks/pre-commit` 多层门禁实件 · 派单收件箱通道**有效**（本单即由它领活）。

**⚠️ 不一致 / 覆盖面缺口**：
1. **`tests/` 契约的「反例证明」没跟上新判据**（观察级，**不列待修**——契约字面只说「新**守卫**」）：`tests/_directory.md:38`「**每个新守卫都要有反例**——守卫能抓必须被证明，不能只是声明」+ `:20`「新增守卫时 → 新增 fixture」；而 `tests/` 实物仍只有 **1 个** fixture（`guard-feature-fixture.py`，`tests/_index.md:22`「文件清单（1 项）」），本批给 3 个既有守卫加的新判据（根 S1/S2 · M2 全量 · M4 行内）**未留下任何可复跑的坏样本**（`git show 5a3c49e1 --stat` 无 `tests/`）。`engineering.md:371` 机制 7「反例回归 ✅」在**覆盖面**上偏乐观。
2. **M4 只认 `bash` ⇒ 文档里 `sh` / `python3` 命令整类逃检**（**P2-1 的根因**）：见 `ai-guard-meta.sh:205`（代码块）与 `:215`（行内，正则字面量就是 `` `bash\s+ ``）。后果：`.agents/rules/deployment/ios-release.md` 等 **18 行**runbook 命令指向**不存在**的 `.agents/mechanism/scripts/*`，而 meta 报 **PASS**——「守卫全绿」与「文档里的命令都能跑」被当成了同一件事。
3. `engineering.md:373`「防膨胀红线 ❌ **待建**」 vs `ai-guard-health.sh:23/:125` 已在实现「>300 提示 / >500 必拆」——**观察级**（该行还含「同知识点合并 / 超 3 月归档」两个未实现子句，故整行标 ❌ 不算错）。
4. `AGENTS.md:42` 只定义 `docs/records/audits/<日期>-ai-context-health.md` 一种命名——同日复检实际落 `-r2.md` / `-r3.md`（**命名约定未覆盖复检**，r2 已记，观察级不再重复计）。

### H3 交叉一致 —— ⚠️（4 组两处不一）

| 事实 | 一处 | 另一处 | 实测 |
|:--|:--|:--|:--|
| 角色数 | `audit.md:36` **16** · `README.md:46/:67` **16** · `engineering.md:326` **16** | `audit.md:19` **15** | **16** |
| 顶层数 | `README.md:33` **7** · `_directory.md:23` **7** · `_index.md:24` **7** | `_index.md:3` **6** | **7** |
| `ai-guard-tools` 范围 | `engineering.md:370` **T1–T8** · `AGENTS.md:63` 未写死 | `_directory.md:56` · `guards/_directory.md:46` **T1–T7** | **T1–T8** |
| subagent 生成物数 | `layer-spec:120` **16/16**（口径 = 每工具 16） | `development.md:52` **30**（口径 = 两家合计） | **32**（16+16） |

### H4 跨目录双源 —— ⚠️（本批**未漂**，但「第四份详述」仍未降级为指针）

- r2 指出的**同一知识（顶层职责）四份详述**依然存在：`.agents/_index.md:26-37`（「子目录（7 个顶层）」表 · 含「装什么」列）· `.agents/README.md:33-47`（顶层结构表，**已自标速查 + 指向 engineering §二**，`:54`）· `ai-context-engineering.md:63-113`（**唯一详述源**，`:65` 自declared）· `.agents/_directory.md:18-24`（职责边界里的顶层枚举）。
- **实测四处内容当前一致**（我逐行对拍 7 顶层的「装什么」），**所以不是漂移缺陷**；问题是 **`_index.md` 那一份没有「指针」声明、也没有任何守卫查跨目录重复**（`S5` 只查**同一目录内**两件套整行重复）⇒ 下次结构变动时它仍是第一顺位漂移点。
- **判据建议**：`.agents/_index.md:26` 该表降级为「顶层速查（详述见 `rules/assets/ai-context-engineering.md` §二）」，或明确声明与 `README.md:33-47` 同源同校。

### H5 滞后 —— ⚠️

| 位置 | 症状 | 证据 |
|:--|:--|:--|
| `.agents/rules/process/audit.md:7` | `updated: 2026-08-23`，内容停在**15 角色**口径（10-06 新建第 16 个角色后未回改）——与 H1-2 **同根** | `git log -1 --format=%ad -- .agents/rules/process/audit.md` |
| 6 个内容已变的文件 | `updated` 停在内容变更之前（r2 #5 未清） | `conventions.md`(08-18) · 3 个 SKILL.md(08-20) · `skills-usage.md`(10-03) · `toolkit/skills/_index.md`(10-03) · `reference/_directory.md`(10-04) · `ai-context-layer-spec.md`(10-05)；对照 `git log -1 --format=%ad -- <file>` |
| 本批实际改动过的文件 | 已正确刷新（5a3c49e1 的 `--fix` + 手工） | `.agents/_index.md:6` / `_directory.md:6` / `README.md:6` 均 2026-10-06 |

> **根因同 r2**：`ai-guard-meta.sh:146` 的「`updated` 只在 `lines` 漂移时刷新」在「内容变更不改变行数」时失效；`frontmatter-spec.md:28` 仍承诺「由工具维护」。

### H6 结构断裂 —— ⚠️（轻，**1 处新引入**；四份总纲无回退）

- **`skills-spec.md:60`**：同一路径**写两遍**（「…`skills/<name>/SKILL.md`（官方目录布局，进攻侧）**+** `skills/<name>/SKILL.md`（建设/流程技能，进攻侧——…）」）——本批重写该行时的重复子句。`sed -n '60p' .agents/rules/assets/skills-spec.md`。
- **✅ 已确认无**：**完整通读**四份总纲——`.agents/README.md`（89 行）· `AGENTS.md`（88 行）· `ai-context-engineering.md`（411 行）· 根两件套（`_index.md` 49 · `_directory.md` 66）：无「两套结构并存 / 尾巴挂多余 `\|` / 丢标题的表」；r2 的四项（`README` 双表 · 孤立残行 · 丢标题 · mermaid `TK`）**无回退**。
- 机械扫（`.agents/**/*.md` 的「非表格行却以 `|` 结尾」与「表头后直接接数据行」）命中项逐条核过：`backend-deployment.md:411-421` 是**引用块内的表**（`> |` 开头）· `change-log.md:271` 是账本长行 · 其余为多行长单元格的**假阳性**——均非缺陷。

### H7 死胡同与孤儿 —— ⚠️⚠️（**本轮最大发现**；全按新判据：先分「指路」/「记事」）

**① 指路（必须可解析）— ❌ 一类系统性死路径：`sh` / `python3` 命令指向不存在的脚本**

| 位置 | 原文 | 实测 |
|:--|:--|:--|
| `.agents/rules/deployment/ios-release.md:77,78,79,80,110` | `sh .agents/mechanism/scripts/release_testflight.sh …` | 不存在（真身 `apps/adai-app/scripts/release_testflight.sh`）|
| `.agents/rules/deployment/ios-release.md:89,90` | `python3 .agents/mechanism/scripts/asc_signing.py …` | 不存在（真身 `apps/adai-app/scripts/asc_signing.py`）|
| `.agents/rules/deployment/ios-release.md:108,109` | `python3 .agents/mechanism/scripts/testflight_status.py …` | 不存在（真身 `apps/adai-app/scripts/…`）|
| `.agents/rules/deployment/testflight-external-testing.md:138,139,140` | `python3 .agents/mechanism/scripts/testflight_external.py …` | 不存在（真身 `apps/adai-app/scripts/…`）|
| `.agents/rules/guides/routine.md:152` | `` `sh .agents/mechanism/scripts/release_testflight.sh --build-number N` ``（TestFlight 到期处置）| 不存在 |
| `.agents/rules/guides/development.md:43` | `cd apps/adai-app && sh .agents/mechanism/scripts/serve_web.sh` | 不存在（真身同目录 `scripts/serve_web.sh`）|
| `.agents/rules/deployment/backend-deployment.md:341,343` | `sh .agents/mechanism/scripts/build_web.sh …` | 不存在（真身 `apps/adai-app/scripts/build_web.sh`）|
| **`.agents/mechanism/guards/ai-guard-release.sh:290,296`** | 守卫**自己打印的下一步命令**：`cd apps/adai-web && sh .agents/mechanism/scripts/serve_web.sh …` | 不存在——**实跑即失败**（H8 实证）|

```bash
# 复现（一次列全 · 只认「本文件目录」与「仓库根」两种基准）
python3 - <<'EOF'
import pathlib,re
root=pathlib.Path('.').resolve()
pat=re.compile(r'\b(python3?|sh|bash)\s+([A-Za-z0-9_./{}-]+\.(?:py|sh))')
for p in [q for q in pathlib.Path('.agents').rglob('*') if q.suffix in ('.md','.sh','.py')]:
    for ln,l in enumerate(p.read_text(encoding='utf-8',errors='ignore').splitlines(),1):
        for m in pat.finditer(l):
            t=m.group(2)
            if not (root/t).exists() and not (p.parent/t).exists(): print(f"{p}:{ln}  {m.group(1)} {t}")
EOF
ls -ld .agents/scripts            # → No such file or directory
ls .agents/mechanism/scripts/     # → 无 release_testflight.sh / asc_signing.py / testflight_status.py / testflight_external.py / build_web.sh / serve_web.sh
sh .agents/mechanism/scripts/release_testflight.sh --status   # → No such file or directory
```

> **根因（可复现，非推测）**：这**不是**本批引入的，而是 **2026-10-03 大迁移的批量路径改写**把 `apps/adai-app` 语境下的**相对**写法也加了前缀。原文可证：
> ```bash
> git show dba86746:docs/deployment/ios-release.md | grep -n 'release_testflight'   # 2026-09-15 首版：sh scripts/release_testflight.sh ✅（配 cd apps/adai-app）
> git log --oneline --all -S'.agents/scripts/release_testflight.sh' -- .agents docs   # 引入者：ec3a3dfc（ai-engineering/ 整体迁入 .agents/）
> git diff 6d95cb37^ 6d95cb37 -- .agents/deployment/ios-release.md .agents/rules/deployment/ios-release.md | grep release_testflight  # 再被改名为 mechanism/scripts/
> ```
> **同类**：`.agents/mechanism/scripts/{data-sync-stock-names,data-verify-tdx,data-prewarm-adj-factors}.py:7,7,12` 的用法行 `python3 ai-engineering/09-.agents/mechanism/scripts/…`（拼接产物，路径不存在）。
> **守卫为何全绿**：M4 的两种形态都够不着——代码块只认行首 `bash `（`:205`），行内正则字面量就是 `` `bash\s+ ``（`:215`），且要求反引号内**以** `.agents/`/`docs/` 开头 ⇒ `sh …` / `python3 …` / 反引号里先写 `sh ` 的，**一律逃检**。

**② 指路 — 其余小项**：
- `.agents/_directory.md:10` `related: [./_directory.md]` **自指**（其余 6 个顶层 `_directory.md` 全为 `related: [./_index.md]`——`grep -n '^related:' .agents/*/_directory.md`），`_index.md:11` 那处才是对的。
- `.agents/_index.md:3`（6 顶层）与 `:42`（三层结构）见 H1。

**③ 记事（按新判据）—— ❌ 不计入，逐条列出以免复报**：
- **scope 标签**：`ai-guard-meta.sh:3` 头注释 + `.agents/mechanism/guards/_index.md:27` + `.agents/mechanism/_index.md:29` 的「ai-engineering/ + AGENTS.md 的 frontmatter 契约自检」——守卫职责的**简称标签**（D-08 已甄别为合理）。
- **坑与史志**：`ai-setup-launchd.sh:211`（明写「迁移（ai-engineering/ → .agents/）后」）· `ai-sync-agents.sh:91` · `ai-worktree-prep.sh:39,147` · `scaffold.md:42`（明写「旧稿曾把目录名写成 ai-engineering/」）· `ADR-001.md`（append-only 决策）· `pitfalls.md:221/:263/:348` · `change-log.md` / `REVIEW.md` / `docs/records/**` / `docs/archive/**` / `docs/ideas/**` / `/rfc/**` / `.worktrees/**`（旧副本）。
- **迁移映射表**：`docs/README.md:76-89` 的旧→新对照（含「走查存档 27 份」——**旧值即设计**；`:53` 的当前值已是 30）。
- **历史数字**：`engineering.md:396`「2026-10-04（归纳两批）…**29 个受管子目录**…目录两件套 30 组」——**带日期的批量账**，按 H7(f) 不动（同段 `:404` 的「14 子目录 / 23 章 / 70 份 / 11 守卫 / 4 流程」同）。
- **历史文件名**：`20261003-skill-conformance…md:44`「六层」· `thinking-log.md:79` / `dispatch-v4-process-20261006.md:21`「8+3 官」· `ai-context-health-reviewer.md:24/:26/:29/:30` 的「上次发现」列。
- **Mermaid 概念节点**：`ai-context-engineering.md` 图内节点名（如 `L2 约束层`）。

**④ 孤儿**：M3 全绿（无新增孤儿）✓。

### H8 可执行性 —— ⚠️（命令本体都能跑；**唯一不合格是守卫自己打印的那条**）

| 文档里的命令/对象 | 实测 |
|:--|:--|
| `bash -n` 全量 shell（`git ls-files '*.sh'` + `.githooks/pre-commit`） | **0 语法错误** |
| `bash .agents/mechanism/scripts/task-cadence.sh`（无参数） | 实跑正常（状态总览 + 欠账 + 到期红线 + LaunchAgent） |
| `task-cadence.sh` 子命令 | `daily` `ship` `weekly` `todo` `release` `check` `cost` `mark` 8 个全在 |
| `python3 .agents/mechanism/scripts/ai-domain-view.py overview` | 实跑正常（37 功能 / 2462·440·409·72 / 端点 173） |
| `bash .agents/mechanism/scripts/ai-sync-all.sh --check` | ✅ git hooks + 技能出口 4 + 子代理出口 2 组 |
| `ai-guard-release.sh` 打印的「③ 下一步」 | ❌ **不可执行**：`cd apps/adai-web && sh .agents/mechanism/scripts/serve_web.sh …` → No such file（归 P2-1） |
| `ios-release.md` 的发版命令（4 条） | ❌ 同上（归 P2-1）——**发版 runbook 目前照抄会直接报错** |

## 四、待修清单（P0–P3）

> **无 P0 / 无 P1**（全是叙事层，不涉及数据丢失、错误行为或对外承诺）。

| 级别 | # | 症状 | 根因 | 落点 | 建议改法 |
|:--:|:--:|:--|:--|:--|:--|
| **P2** | 1 | **18 行 runbook 命令指向不存在的脚本**（`sh`/`python3 .agents/mechanism/scripts/…`，6 文件），含**守卫自己打印的下一步命令**；meta 仍报 PASS | 2026-10-03 `ec3a3dfc` 批量改写把 `cd apps/adai-app` 语境的相对 `scripts/…` 加了 `.agents/` 前缀（原文可证），`6d95cb37` 再改名为 `mechanism/scripts/`；**M4 只认 `bash`**（`:205`/`:215`）⇒ `sh`/`python3` 整类逃检 | `rules/deployment/ios-release.md:77,78,79,80,89,90,108,109,110` · `rules/deployment/testflight-external-testing.md:138,139,140` · `rules/deployment/backend-deployment.md:341,343` · `rules/guides/routine.md:152` · `rules/guides/development.md:43` · `mechanism/guards/ai-guard-release.sh:290,296` | ① 18 处改为真身（如 `ios-release.md` 已 `cd apps/adai-app` ⇒ `sh scripts/release_testflight.sh`；`ai-guard-release.sh` 已 `cd apps/adai-web` ⇒ `sh scripts/serve_web.sh`）；② **M4 扩到 `sh`/`python3`**（按 pitfalls 二十三先做反例回归防误报——`bash -n` 类参数误判的教训已在本批 A3 踩过） |
| **P2** | 2 | **r2 #5 未清**：6 文件的 `updated` 停在内容变更之前（`conventions.md` 08-18 · 3 个 SKILL.md 08-20 · `skills-usage.md`/`toolkit/skills/_index.md` 10-03 · `reference/_directory.md` 10-04 · `layer-spec` 10-05） | `ai-guard-meta.sh:146`「updated 只在 lines 漂移时刷新」——「内容变更必引起行数变化」的假设不成立 | `ai-guard-meta.sh:146` + 上述 6 文件 | 二选一（同 r2 建议）：① `/ship` 时按 `git diff --name-only` 刷 `updated`；② 改 `frontmatter-spec.md:28` 措辞，写明「`updated` 仅表示行数变更时点」 |
| **P2** | 3 | **r2 #8 未清**：`ai-guard-tools` 范围仍写 **T1–T7**（2 处） | 上一轮「只改落点、未全域回扫」 | `.agents/_directory.md:56` · `.agents/mechanism/guards/_directory.md:46` | 改 **T1–T8**（或写「T1–T8」前先与 `ai-guard-tools.sh` 的编号对拍） |
| **P2** | 4 | **角色数 15 vs 16 两处不一**：`audit.md:19` 写 15（且枚举里没有体系体检官），同文件 `:36` 写 16 | 2026-10-06 新建第 16 个角色（`ai-context-health-reviewer`）后未全域回扫；该文件 `updated` 停在 08-23 | `.agents/rules/process/audit.md:19`（+ `:7` `updated`） | 改「共 **16** 个角色定义 = 上述 9 个 + 外部视角官 3 + 需求评审官 1 + 设计产作者 1 + **体系体检 1** + 流程官 1」；`updated` 同步 |
| **P2** | 5 | **`16 个角色 × 2 家 = 30 个生成物`** —— 算术与实测均为 **32** | 「15 → 16」改了一半（乘积未跟） | `.agents/rules/guides/development.md:52` | 改 **32 个生成物** |
| **P3** | 6 | 根索引 description 仍写「**6 个顶层目录**」；**新增的 S6 也抓不到它** | 本批修的是 `:24` 与 `_directory.md`（括号内写法），漏了 frontmatter description；S6 正则要求数字落在 `（…）` 内 | `.agents/_index.md:3`（守卫侧：`ai-guard-structure.sh:237-241` 的 `NUM_PAT`） | 改「**7** 个顶层目录」；或把 S6 的 `NUM_PAT` 放宽为「数字紧跟 `个顶层/个角色`」两种形态（放宽后须反向回归：确认不会命中「六顶层重构」「8+1 官」这类记事） |
| **P3** | 7 | `.agents/_index.md:42` 仍把 `README.md` 的职责描述为「定位 + **三层结构** + 工具接入」——README 已无此节 | 总纲收口批重写 README 时未回改引用它的根索引 | `.agents/_index.md:42` | 改「定位（规则与机制层）+ **7 个顶层结构** + 任何 AI 工具如何接入」 |
| **P3** | 8 | `skills-spec.md:60` **同一路径写两遍**（重复子句） | 本批重写该行时拼接残留 | `.agents/rules/assets/skills-spec.md:60` | 删掉后半段重复的 `+ .agents/toolkit/skills/<name>/SKILL.md（建设/流程技能…）`，只保留「官方目录布局，进攻侧」那一处 |
| **P3** | 9 | r2 #11 未真清：导语「**三条**有意偏离」 vs 表内 **4 条有效行**（1/2/4/5，第 3 行已划除） | 划除偏离 3 时把导语从「四条」改成「三条」，但被划除的那条**仍在表内** | `.agents/rules/assets/skills-spec.md:73`（+ `:79`） | 二选一：① 删掉偏离 3 整行 ⇒ 导语「三条」自洽；② 保留划除行 ⇒ 导语改「**四条**有意偏离（另有 1 条已于 2026-10-06 消除）」 |
| **P3** | 10 | 根目录契约 `related` **自指**（其余 6 个 `_directory.md` 均为 `./_index.md`） | 复制模板时未改 | `.agents/_directory.md:10` | 改 `related: [./_index.md]` |
| **P3** | 11 | 3 个脚本的**用法行**指向拼接出来的假路径：`python3 ai-engineering/09-.agents/mechanism/scripts/…` | 批量路径改写的拼接产物（与 P2-1 同源） | `mechanism/scripts/data-sync-stock-names.py:7` · `data-verify-tdx.py:7` · `data-prewarm-adj-factors.py:12` | 改 `python3 .agents/mechanism/scripts/<同名>.py`（同目录 `ai-domain-view.py:5` 已是正确写法，可照抄） |
| **P3** | 12 | `engineering.md:100`「契约与设计（**13 份**）」**无口径能对上**：本行 glob = 16 · 括号举的例子（contracts+designs）= 12 | 手工数字；该行因 `{}` 展开被 S6c **刻意跳过**（`ai-guard-structure.sh:250`） | `.agents/rules/assets/ai-context-engineering.md:100` | 明确口径并改实测值（建议「契约与设计（**12 份**）」+ 位置列改为 `{contracts,designs}/*.md`，把 `manuals` 留给下一行的「功能手册（4 份）」） |
| **P3** | 13 | 派单文件 `inbox.md` `lines` 复发（82 vs 94）+ M4 指向尚未落盘的 r3 报告 | 派单在主链跑守卫之后被继续追加；派单正文指向**交付物本身** | `.agents/workspace/_meta/inbox.md:8` | **M4 一侧已收口**（`2e8f1689` 把 `.agents/workspace/_meta/` 纳入 `M4_SKIP`，`ai-guard-meta.sh:192-195`——机制化豁免，正确解法）；**M2 一侧仍会复发**（派单写完即漂移）：建议派单**落笔后即跑一次 `ai-guard-meta.sh --fix`**，或把该文件的 `lines` 改成不校验的形态 |

**观察级（不列待修，供主链取舍）**：`engineering.md:112` 流程定义列名写「task-cadence」（该 glob 下真实文件是 `cadence.md`；`task-cadence` 是脚本名，属简写歧义）· `tests/` 契约的「新判据无反例 fixture」（H2-1）· `engineering.md:373` 防膨胀红线「❌ 待建」而已部分落地（H2-3）· `AGENTS.md:42` 报告命名未覆盖复检。

**已知且在案、不新增**：`engineering.md:402`「`.agents/` 199 份」（r2 已判无可复现口径）· `docs/` 侧 3 区缺 `_directory.md`（`engineering.md:403` 自记）· `change-log`/`task-log`/`REVIEW`/`docs/records/` 的 `lines` 与旧路径（append-only 账本与档案馆，**按设计豁免**）· `.agents/skills/` 出口位不计顶层。

## 五、纪律声明（改了什么 / 没改什么）

- **只报告不改**（B7）：**未动**第二节 12 条与第三节 H1–H8 点名的**任何被点名文件**（`audit.md` · `development.md` · `_directory.md` · `_index.md` · `skills-spec.md` · `ios-release.md` …）。
- **仅为满足交付判据 C1 的守卫同步（3 处，全部记录在案）**：① 本报告落盘；② 登记 `docs/records/_index.md`（走查存档 30 → **31** 份）；③ `docs/README.md:53` 的「走查存档 30 份」→ **31**（**本报告落盘导致的派生数字，不属被点名项**）。
- **未写派单文件**：`bash .agents/mechanism/guards/ai-guard-meta.sh --fix` 实跑输出「**无漂移，无需回写**」——`inbox.md` 的 `lines` 已由并发主链 `2e8f1689` 同步、M4 亦已按机制豁免该目录，故**本单未触碰** `inbox.md`。
- **守卫复验**：见文末。

```
META-GUARD: PASS (299 files, edges/lines/orphans all ok)
STRUCTURE-GUARD: PASS (30 个子目录 · 两件套齐备 · 清单⇄实际一致 · 依赖与守卫引用有效 · 两件套不重复)
```

## 六、一句话结论

**r2 的 12 条：6 清 · 4 部分清/变形 · 2 未清**（`updated` 刷新 · `T1–T7` 措辞）——**机制批是有效的**（容器根进 S1/S2、M2 覆盖 299、M4 抓出行内 `bash` 死命令并修完；并发批 `2e8f1689` 又把「派单文件」这一固有摩擦**机制化豁免**），但**「改落点不改类别」的老毛病第三次出现**：本轮三处新数字错（`audit.md` 15 角色 · `development.md` 30 生成物 · 根索引 6 顶层）**全部是「加了角色/改了结构，只改了一部分引用」**。**本轮最大的新发现是 H7 的 P2-1**：`sh`/`python3` 命令整类逃过 M4，导致 **18 行发版 runbook（含 `ai-guard-release.sh` 自己打印的下一步）指向不存在的脚本而守卫全绿**——建议下一批**先修 P2-1 + 把 M4 扩到 `sh`/`python3`**（配反例回归），再一次性清 P2-4/P2-5 与 4 处 P3 措辞；否则「体检」会周期性报同一批。

---

## 附：本次**未**采信的候选（遵循「宁可报少，不可报错」）

- **`engineering.md:396`「29 个受管子目录」**：本行是**带日期的历史批量账**（「2026-10-04（归纳两批）」），与 `:67`「30 个受管子目录（含容器根）」的**当期**口径分工不同 ⇒ 按 H7(f) **历史数字 = 记事**，不计。
- **`docs/README.md:84`「`docs/review/audits/`（27 份走查存档）」**：迁移映射表（旧→新）里的**旧值**，按 H7(d) **记事**，不计（该文件亦在 M4_SKIP 内，batch 已注明立意）。
- **`ai-guard-meta.sh:3` / `mechanism/guards/_index.md:27` / `mechanism/_index.md:29` 的「ai-engineering/」**：守卫职责的 **scope 标签**（D-08 已甄别为合理），按 H7(a) **记事**，不计。
- **`ai-guard-meta.sh:210/:218` 正则与注释里的 `ai-engineering/…`**：防残留的历史形态（`:218` 注释明写「正则保留防残留」），**记事**。
- **`docs/records/` 全区的旧路径**：`docs/records/_index.md:41` 明写「**不适用『引用必须指向现在』**：本区文件含『当时』的路径属正常（M4 对本区豁免）」——**记事**。
- **`.worktrees/*` 下的同名文件**（含旧 `ai-context-engineering.md` 里仍有的 `TK`）**：其他 worktree 的检出副本，本单范围外**。
- **60+ 处缩写路径**（如 `process/review-driven.md` 实指 `rules/process/…`、`guards/ai-guard-meta.sh` 实指 `.agents/mechanism/guards/…`）：与 r2 同判——**成立缩写，非断链**（`pitfalls.md:348` 的 M4 裸路径教训）。
- **`.agents/_directory.md:42`「`bash mechanism/guards/…`」**：按**本文件所在目录**（`.agents/`）解析成功（M4 已支持该基准）——**不是死路径**。
- **`backend-deployment.md:411-421` / `change-log.md:271` / 各 checklist 的「表头/孤立行」机械命中**：逐条核为**引用块内表**、账本长行与多行单元格**假阳性**，非 H6 结构断裂。
