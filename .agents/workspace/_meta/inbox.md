---
title: 派单收件箱（子代理投递兜底通道）
description: 消息通道丢载荷时的兜底派单通道；任何 AI 开工先看本文件（AGENTS.md 规则 0c）
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 82
depends-on: []
related:
  - ./process-issues-20261006.md
  - ./dispatch-v3-product-20261006.md
tags: [workspace, meta, dispatch]
---

# 派单收件箱

> **任何 AI 开工先看本文件**（`AGENTS.md` 规则 0c）。**有分配给你的任务就执行；没有就忽略**。

## 当前待处理派单

- **D-20261006-08**（**AI 上下文体系体检 · 复检 r2** · `ai-context-health-reviewer`）
  - **背景**：`D-20261006-07` 报了 **13 条待修，已全部修复**（三轮：`2598ba5c` · `c01d2b4e` · `38a9f8c5`）。
    本次复检两个目的：**① 验证 13 条真的清零**（逐条回看落点）**② 找出三轮修复新引入的问题**（动了 ~30 个文件）。
  - **机械层（主链已跑、照抄）**：health **100/100** · meta **PASS 285** · structure **PASS** · skills **20** · tools **12-0-0**。
  - **主链已自查、不必重做**：`ai-engineering` 残留 5 处与「12/12」「8+3」2 处**经逐条甄别全部合理**
    （scope 标签 / Mermaid 概念节点 / 历史 RFC 文件名 / pitfalls 的历史记录 / 守卫注释里的测试数据 / H3 的历史列）
    —— 请**不要**把它们当新问题重报。
  - **请集中查**：**H2 文档⇄行为** · **H4 跨目录双源**（上轮刚收口，验证是否真不再漂）· **H6 结构断裂**
    （上轮改了很多行：重点看 `.agents/README.md` / `AGENTS.md` / `ai-context-engineering.md` / `layer-spec`）· **H7 死胡同**；
    外加**回归验证**：对前一轮 13 条的落点**逐条**给「✅ 已清零 / ⚠️ 未清或回退」。
  - **交付**：`docs/records/audits/2026-10-06-ai-context-health-r2.md`（H1–H8 + **13 条回归表** + 新发现 P0–P3）。
  - **纪律**：只报告不改（B7）· 每条带证据（文件:行 + 可复现命令）· **宁可报少不可报错** · 不重写已有判据。

- **D-20261006-07**（**AI 上下文体系体检** · 新角色 `ai-context-health-reviewer` 首次实跑）· ✅ **已完结 2026-10-06**（交付与结论见下方「已完成」）
  - **任务**：按 `toolkit/checklists/ai-context-health-reviewer.md` 的 **H1–H8** 对 `.agents/` 做全景体检。
  - **材料前置**：先读你的角色定义 `toolkit/roles/ai-context-health-reviewer.md`（含触发/步骤/约束/输出）。
  - **机械层（照抄结论、不重判；主链已跑）**：`ai-guard-health --full` **100/100** · `meta` **PASS 285 文件** · `structure` **PASS** · `tools` **12-0-0**。
  - **主链已查完、不必重做**：**H1** 数字（rfc **67** ✓ · guards **15** ✓ · 顶层 **7** ✓ · 角色 **16** ✓）· **H3**（「审核者 14」三处一致 · 残留 0）· **H5**（五份总纲最后改动均为 2026-10-06，无滞后）· **H8**（`--topic` 谎报已修）。
  - **请集中查这四条**：**H2 文档 ⇄ 行为**（规范声称在跑的机制是否真在跑）· **H4 跨目录双源**（同一知识是否两处详述；`S5` 只查两件套内部）· **H6 结构断裂**（**完整读**三份总纲：README / AGENTS.md / ai-context-engineering）· **H7 死胡同与孤儿**（**M4 的缩写路径盲区**：`assets/xxx` 实为 `rules/assets/xxx` 这类）。
  - **交付**：`docs/records/audits/2026-10-06-ai-context-health.md` —— H1–H8 逐条（✅ 过 / ⚠️ 待修 **+ 证据：文件:行 + 可复现命令**）+ **待修清单（P0–P3，每条给落点文件）**。
  - **纪律**：**只报告不改**（B7）· 每条**带证据**、猜的不写 · **不重写已有判据** · 「**宁可报少，不可报错**」。

> `D-20261006-01` ~ `07` + `D-20261007-01` ~ `04` **全部已完结**（见下方「已完成」）。

## 已完成

- **D-20261006-07**（**AI 上下文体系体检** · 新角色 `ai-context-health-reviewer` 首次实跑）→ `docs/records/audits/2026-10-06-ai-context-health.md`（**224 行**）✅ 2026-10-06
  （**领取**：`ai-context-health-reviewer` · 14:39 · **机械层照抄**：`health --full` **75/100**（派单写 100/100 —— 差异归因：派单后本文件被继续追加 ⇒ M2 `lines` 漂移 57→66；派单正文指向**尚未落盘**的报告 ⇒ M4。**非守卫退化**）· `structure` **PASS**（29 子目录）· `meta` **2 FAIL**（两条都在本文件）· `tools` **12-0-0** · 补跑 `skills` **PASS**（20 技能包）。**H1–H8 逐条**：**✅ H8 全过**（全部 shell `bash -n` 0 语法错 · `task-cadence.sh` 8 个子命令 · `python3 ai-domain-view.py overview` 实跑正常）；**⚠️ H1/H2/H3/H4/H5/H6/H7 = 13 条待修 · 无 P0/P1**。**六条主链盲区（本轮新发现）**：① **H2-1** `AGENTS.md:52`「16 角色**均封装为 SKILL.md 技能包**」——实测 `roles/` **全扁平、无 SKILL.md**，且同体系 `ai-context-engineering.md:107` 与 `layer-spec:58`/`:158` 写「扁平 / 刻意保持扁平」⇒ **总纲内部三方相反**（10-03 迁移前旧口径）；② **H7-1** `ai-engineering/` 目录**已不存在**（10-03 已收进 `.agents/`）仍有 **4 处**死胡同引用（`AGENTS.md:37` · `layer-spec:28`/`:60`/`:81`）——M4 盲区（不以 `.agents/`/`docs/` 开头且无扩展名）；③ **H1** `ai-context-engineering.md` 内部 **8 处数字/路径不实**（RFC 66→**67** · guards 14→**15** · 清单 14→**17** · 受管目录 19/20→**29** · `reference/*.md` 实为三子目录且手册 6→**4** · T1–T7→**T8** · 技能「官方目录布局」实为 **1 目录 + 3 扁平** · `layer-spec:120` 12/12→**16/16**）；④ **H4** 两组**真跨目录双源且已漂移**（`README.md:33-45` ⇄ `engineering:63-113` 目录职责表 · `AGENTS.md:59-73` ⇄ `engineering:102-113` 守卫表）——`S5` 只查两件套**内部**，跨目录无人查；⑤ **H7-2** `docs/records/_index.md:27` 点名**不存在**的 `audits/_index.md`（该区**整体豁免 M4** ⇒ 无人查）；⑥ **H3** 旧口径残留 **3 处**（`worktree-workflow.md:41`「12 个审查官」· `review-driven.md:154` 与 `process-reviewer.md:21`「8+3 个审查官」——而 `review-driven.md:30` 自己已定义审核者 **14**）。**未采信（避免噪音）**：113 处缩写路径逐条核对**全为成立缩写**，无一真断链（同 `pitfalls.md:348` 记的 M4 裸路径教训）。**纪律**：只报告不改（B7）——13 条各给落点文件 + 建议改法，**未动任何被点名文档**。**守卫同步（仅为满足交付判据 C1）**：登记 `docs/records/_index.md`（audits 27→**29** 份 + `updated`）· **仅**同步本文件自身 `lines` 声明。）

- **D-20261007-04**（修订 S2 两份草稿 · 87 课 = 规则层 · 私人财产）→ `.agents/direction/rfc/20261006-trading-positioning-expansion.md`（**188 行**，r1 168 → r2 188）+ `.agents/workspace/_meta/roadmap-diff-trading-20261006.md`（**89 行**，r1 88 → r2 89）✅ 2026-10-06
  （**领取**：`s2_revise_writer` · 交付 **首改 <3 分钟 · 15 分钟内完成两份**。**五处逐条落地**——① **§三** 标题「87 课**知识库**」→「87 课**规则层**」；表体「owner 的私有**知识资产**」→「**owner 的规则层 · 私人财产**（**人工写就**）」，并**新增一行「与『你自己的规则』的关系 = 同构」**（区别只在**来源**：87 课 = 人工写就，新用户 = 从自己数据长出来；**正因为同构，才不能拿一个人的规则层当另一个人的默认值**）；「一句话边界」改「**我的规则层只服务我**」，保留「非 owner 一律不注入」+ 防越界/防失真两条理由。② **§六**（原 §五）`N-01` 加理由「**分享 = 处分私人财产**（尤其那些**源自 87 课**的规则）」+ 表下**埋两问**（① 哪些规则可分享？② 87 课出身的规则算不算？）。③ **§七**（原 §六）表前加「**一条主线**」——「**记忆 → 规则**」是**同一条线的两段**（`20260902` 管记忆 · 本 RFC 管规则），**不是两个孤立决定**。④ **新增 §四「规则层的写入路径」**（紧接 §三）+ **★4 进 §九「★ 待 adai 拍板」**（三处 → **四处**；全稿口径同步：description · 文首 · §九 标题/引言 · 决策记录）。⑤ **roadmap 改动 2（含改动 3 的 A 案）**「仅对 owner 注入」补成与 RFC 同口径（**87 课规则层 = owner 的私人财产 · 非 owner 一律不注入 87 课**）+ 理由同步。**章节顺延**：新增 §四 后原 §四~§九 **顺序下移为 §五~§十**，**全稿交叉引用逐条同步**（§十 影响面 · §九 ★ · §七 承接 · §五 合规口径 · §六 不做清单 · §四 ★4）。**守卫**：`ai-guard-meta` **PASS**（283 文件全绿；两文件 frontmatter **10 字段齐全** · `lines:` = `wc -l` = **188 / 89** ✓）；`ai-guard-structure` **PASS**（两文件已登记 `workspace/_index.md`）。**未改** 任何 `design-*` · `_index.md` · `rfc/` · `product-roadmap.md`。**提请注意**：`_index.md:50` 摘要仍写「待 adai 拍 **3 处** ★」——按派单「不要动 `_index.md`」**未改**，留主链随索引收口为「4 处」。）

- **D-20261007-03**（S2 落点 · 两份可搬移草稿）→ `.agents/direction/rfc/20261006-trading-positioning-expansion.md`（**168 行**）+ `.agents/workspace/_meta/roadmap-diff-trading-20261006.md`（**88 行**）✅ 2026-10-06
  （**领取**：`s2_handover_writer` · 13:23 · **骨架 13:23**（<1 分钟，闸门内）· 两份交付 **13:24** 完成（**≈2 分钟**，远在 15 分钟时限内）。**交付 1 · RFC 草稿**：照 `20260902` 结构（背景 / 新定位 / 边界 / 备选与理由），**逐条落六段**——① 定位变更「建议引擎/owner 专属」→「**人人可用的交易记录与照见**」；② 与 87 课（`os/trading-engine`）关系 = **owner 私有资产 · 只对 owner 注入 · 新用户规则从自己数据长出来**；③ 与 `20260902` **逐条承接表**（明确「**承接 + 增量、不取代**」，7 行逐条对齐）；④ 不做清单 `N-01`/`N-02`/`X-02`（注明 `X-01` 永久不做不在内）；⑤ 合规口径沿用（主语永远是「你」+ 不给品种/时机建议文本）；⑥ **三处 ★ 原样标出**（★1 非 owner 不注入 87 课 · ★2 不做清单即对外承诺 · ★3 与 `20260902` 承接还是取代）。文首加**「搬到 rfc/ 时怎么改 frontmatter」**节（7 字段对照 + `status: draft→approved` 由人定稿后改）。**交付 2 · roadmap 三条**：每条给「现状原文（逐字抄自 `product-roadmap.md:90` / `:96`）→ 改后原文」+ 理由——① L6 行补「目标用户扩容（人人可用）」；② Trading OS 行补「仅对 owner 注入」+「候选规则自生长」+「规则分享先不做」；③ 未修项关联增挂 `P2-交易81`（**如实登记**：roadmap **无「未修项关联」字段/列**，给 A/B 两案 + 推荐 A，字段结构改动交主链拍板）。**守卫**：`ai-guard-meta` **PASS**（282 文件全绿；两文件 frontmatter 10 字段齐全 · `lines:` = `wc -l` = 168 / 88 ✓；并**顺手校正** `inbox.md` 自身 `lines` 66 ✓）；`ai-guard-structure` **1 项 FAIL ＝ 本单两文件未登记 `workspace/_index.md`**（按派单「**不要改 `_index.md`**，索引登记由主链统一做」，与 D-03/04/05/06 同根因，留主链收口——**未自行 `--fix`**）。**未改**任何 `design-*` · `requirement.md` · `decisions.md` · `_index.md` · `rfc/` · `product-roadmap.md`。）

- **D-20261007-02**（差异复核 · r1 → r2 三条改动 · 后端官视角）→ `.agents/workspace/trading-plugin/review-r2-backend-20261006.md` ✅ 2026-10-06
  （**领取**：`r2_diff_review_backend` · 13:11 · 交付 **100 行** · 13:12 完成（**≈2 分钟**，远在 15 分钟时限内）。**判定：✅ 三条全部真到位 · 无连带新引 P0/P1**。① **§4.1**：表体机械计数 = **27 行** ✓（`#25/#26/#27` 为新增 3 条）；3 条「实存」**逐条代码实测为真**——`CashAdjustmentFileRepository.java:31` `trading/cash-adjustments.json` · `LearnTradingCandidateFileRepository.java:36` `trading/candidates/` · `LotStopLossOverrideRepository.java:40` `trading/lot-stoploss.json`（均主源码在写 + 有同名单测）；§13.2 `:539`「**8 项未收录 + 2 项新增 + 1 项差异**」与 `:591` 自评风险 5 的逐项列名（8 项）**完全对齐**、表体「登记补契约」行数 = **11** 一致。**额外完备性加验**：`infrastructure/storage/` **21 个 Repository** 的路径字面量 + storage 外 4 处盘路径（`knowledge.md` / `profile.md`×2 / `imports/`）**全部命中 §4.1，0 条同型残留**（`/trading/screenshots` 系端点路径、明写「不落原图」，不算漏项）→ **+3 是该类缺陷的收口，非「补三漏四」**。② **§10.1**：规则 1 正则含 **10 个口语变体** 且**自带示例「今天卖了 500 股」现可命中**（P1-2 指控消除）；反向回归 ①–⑥ **逐类 6 条**、与「共 6 类」表头数目一致，明确点名 `note`/`sections` 也要过清洗 → 假绿已消（仅附 1 条编码期细节观察：⑥ 未给字面样例串，不阻断）。③ **§1** `:99`「**三个** `ContextContributor`」与 §8.1 `:314-318` 三行、§8.2 `:325`「共 2 个」**口径一致**。**无 P0 / 无 P1**；仅 1 条 **P3 用词建议**（§9#28 `:390` 仍写「两条正在跑的注入链」，与 §1「三个」字面并列易被误读为打架；系 r1 既有措辞、**非 r2 新引**，建议改「其中两条须按白名单收窄」）。**守卫**：frontmatter 10 字段 ✓ · `lines` = `wc -l` = 100 ✓；`ai-guard-meta` / `ai-guard-structure` **各 1 项 FAIL、同一根因** ＝ 本文件未登记 `workspace/_index.md`（按派单「**不要改 `_index.md`**」未自行登记，与 D-20261006-03/04/05/06 同根因，留主链收口）。**未改**任何 `design-*` · `review-*` · `_index.md` · 代码 · 契约；另**顺手校正** `inbox.md` 自身 `lines` 声明（因本轮新增领取/完结行而漂移）。）

- **D-20261007-01**（design-final 就地修订 · 收 v4 两官 3 条 P1）→ `.agents/workspace/trading-plugin/design-final-20261006.md` ✅ 2026-10-06
  （**领取**：`writer_fix_r2` · 13:09 · **首改 <1 分钟**（先落「修订记录」骨架，闸门内）· 交付 **r2 · 597 行**（r1 585 → r2 597）。**三条逐条落地**：① **§4.1** 补 3 条实存落盘路径（`cash-adjustments.json`（标 **U2 迁移目标**）· `candidates/` · `lot-stoploss.json`）→ 表 **24 → 27 行**、**§13.2** 未收录 **5 → 8 项**、自评风险 5 的「登记补契约」总数 **8 → 11 项**；② **§10.1** 规则 1 补口语变体（`买了` / `卖了` / `抛出` / `购入` …）＋ 反向回归改为**逐类覆盖 6 类规则的示例**（消假绿）；③ **§1**「两个 `ContextContributor`」→「**三个**（`Trading` / `Market` / `TradingProfile`；其中两个为改造项、一个保留）」。文首**新增「修订记录」节**（r1 → r2，逐条落到审核编号）。**守卫**：`ai-guard-meta` **PASS**（frontmatter 10 字段 ✓ · `lines` = `wc -l` = 597 ✓）· `ai-guard-structure` **PASS**。**未改**任何 `design-v1/v2/v3` · `review-*` · `requirement.md` · `_index.md` · `decisions.md`。）

- **D-20261006-06**（本轮流程记录 · 流程官）→ `.agents/workspace/_meta/process-log-20261006-v4.md` ✅ 2026-10-06
  （**领取**：`process_v4_agent` · 04:14 · **骨架 04:14:31**（<1 分钟，闸门内）· 交付 **119 行**。**四段**：① 事实（走 0c 收件箱档 · 派单→领取→交付时间台账 · 空转 · 代笔核对 · 清单漂移，逐条带 文件:行 / mtime / 命令输出）② 判据（**P-04 / P-05 / P-08 / P-11 / P-12 = FAIL**；P-01 / P-02 / P-03 / P-06 / P-09 PASS；**P-07 PASS⁻**（promote 落点 ★升人项悬置，两官均判无需升人但建议人一句话确认）；P-10 无本轮新判据）③ 改进提案 **×7**（每条带落点）④ 结论：**C 级 —— 过程可用、派单节拍不达标**。**守卫**：frontmatter 10 字段 ✓ · `lines`=`wc -l`（119）✓；`ai-guard-structure` 1 项 FAIL ＝ 本单未登记 `_index.md`（按派单「不要改 _index.md」，与 D-03/04/05 同根因，留主链收口）。**未改**任何 `design-*` / `review-*` / `process-issues-20261006.md` / `_index.md`。）

- **D-20261006-05**（design-final 收敛性审核 · 产品面）→ `.agents/workspace/trading-plugin/review-v4-20261006.md` ✅ 2026-10-06
  （**领取**：`review_v4_product_agent_b` · 04:09 · **判定：❌ 不收敛（产品面 · 无 P0）**——上轮 6 项**全部真闭环**：战略 S2（§13.3 门禁化、不再谎称闭环；`rfc/` 最新仍 `20261003` + roadmap L6/Trading 行未改 ⇒「未闭环」属实）· 产品 P1-1（§5 截图走 `/trading/screenshots`，保住「不进 Feed/时间线」，与 `TradingController.java:1266` 注释逐字对拍）· 产品 P1-2（§8.1/§8.2/§9#28 三处同口径，「死代码」残留清零）· 点2 双真源已治 · 点3 数量声明四处 24/2/31/6 逐一对齐；**新引 P1×1**——§1 声明「两个 `ContextContributor`」而 `domain/trading` 实有**三个**（Trading/Market/TradingProfile 均 `implements ContextContributor`）→ 与自身「数量声明须可机械核对」规则打架、且为 final 新加（`design-v2:78` 无此计数）。**另裁决**：编写者自评风险 1（promote 落点改 `data/{userId}/`）**不违反人拍板 S1、无需升人**。**守卫**：frontmatter 10 字段 ✓ · `lines`=`wc -l`（81）✓；`ai-guard-structure` 1 项 FAIL（未登记 `_index.md`，按派单「不要改 `_index.md`」，与 D-20261006-04 同根因，留主链收口）。**并发提示**：本单曾与 `review_v4_product_retry` 同名文件竞写（04:12 空骨架覆盖完整稿），已覆盖回完整稿并去消息协调。）

- **D-20261006-04**（design-final 收敛性审核 · 后端面）→ `.agents/workspace/trading-plugin/review-v4-backend-20261006.md` ✅ 2026-10-06
  （**领取**：`review_v4_agent_a` · **判定：❌ 不收敛（无 P0）**——S2 · P1-1（Contributor 三处口径）· P1-2（Market 白名单）· P1-3/P1-4（promote 三件）**已真闭环**；S1 读侧六面**闭环**；**P1-3 / P1-6 部分闭环**；**新引 P1 × 2**——① **§4.1 路径表漏 3 条实存落盘**（`cash-adjustments.json` · `candidates/` · `lot-stoploss.json`，均为代码在写、契约未收录；且 `cash-adjustments.json` 正是 §9#4 的迁移目标）→ U2 迁移会漏搬 + §13.2 补录计数失真；② **§10.1 脱敏正则与自带示例不符**（规则 1 只认 `持有|买入|卖出|成交`，而规则 5 自举的例是「今天**卖了** 500 股」→ 按表实现的清洗器漏网，反向回归（只塞「卖出 500 股」）给**假绿**）。
  **守卫**：frontmatter 10 字段 ✓ · `lines` 与 `wc -l` 一致（96）✓；**未登记** `_index.md`（按派单「不要改 _index.md」）→ `ai-guard-structure` / `ai-guard-meta` 会各报 1 项 FAIL，**同一根因、属派单约束**，留主链收口。
  **注意**：本稿**未改**任何 `design-*` / `review-*` / `_index.md`；提请注意 **promote 第一跳落点变更**（`os/` → `data/{userId}/`）建议在编码准入前由人一句话确认。）

- **D-20261006-03**（设计 final 稿 · 合并正文）→ `.agents/workspace/trading-plugin/design-final-20261006.md` ✅ 2026-10-06
  （**交付**：585 行**合并正文**，取代 v2 / v3；两份 v3 审核的**战略 2 条 + P1×6 + 新引 P1×4** 与派单 7 项**逐条落点**；
  四处数量声明**机械核对通过**——§4.1 = 24 行 · §8.2 = 2 白名单 · §9 = 31 行 · §11.3 = 6 面。
  **守卫**：frontmatter 10 字段 ✓ · `lines` 与 `wc -l` 一致（585）✓；`ai-guard-meta` 与 `ai-guard-structure` **各 1 项 FAIL、同一根因**——交付文件尚未进 `workspace/_index.md` 清单（按派单「**不要改 _index.md**，索引登记由主链统一做」，**未自行登记**）。
  **需审核者注意**：本轮把 `TradingContextContributor` 由「死代码 · 删除」更正为「**活的 · 改造**」；并把 promote 落点从 `os/trading-engine/99-inbox/` 改到 `data/{userId}/trading/reviews/promote/`——**后者是对 S1 拍板结论的实现层改动**，已在设计稿「自评风险 1」显式请判。）

- **D-20261006-01**（通道探针）→ `.agents/workspace/_meta/inbox-receipt.md` ✅ 2026-10-06
- **D-20261006-02**（v3 产品视角 3 点复核）→ `.agents/workspace/trading-plugin/review-v3-product-20261006.md` ✅ 2026-10-06
  （**通道结论**：本次派单**未**经消息通道——收件人靠本条 inbox 领活；文件已落盘、已登记 `_index.md`、meta + structure 双守卫 PASS。3 点判定：S2 **不成立**（推后非闭环）· 增量稿与正文自相矛盾 **成立** · 「11 组」数量声明 **部分成立**；新引 P1×2）
