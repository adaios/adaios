---
title: 功能索引层与编写侧判据前置——把「功能」升格为唯一主轴
description: 2026-10-01 用户提出「补齐需求/设计文档，每个过程安排角色（编写+审核对抗），能从文档树上看每个功能」→ 盘点发现已有八成（RFC 六维 / architecture / 插件功能手册 / 12 审查官 / feature-reference 19 条），真缺口是「没有功能这根主轴」与「已有规范无人执行」（需求六维采用率 1/64、RFC status 9 种写法）。本 RFC 定：新增物只有**功能索引层**（不做内容副本）+ 状态机器对拍 + 编写侧判据前置；角色不按过程铺开，对抗只在三处高风险点跑。**功能条目＝索引行（机器维护）+ 按需生长的 ≤12 行意图卡（每插件一份文件，卡内禁写实现细节）**；D2 已按降本版拍板（2026-10-01）。
date: 2026-10-01
status: approved
decided-by: adai（2026-10-01「开始吧」→「别再问我了，继续，直到完成」——授权按推荐执行 D1/D3/D4/D5；D5 保留复核权）
tags: [ai-engineering, 文档体系, 功能索引, 审查, 流程, RFC]
related:
  - ../../knowledge/reference/manuals/feature-reference.md
  - ../../knowledge/reference/manuals/trading-features.md
  - ../../knowledge/reference/manuals/admin-features.md
  - ../../knowledge/reference/status.md
  - ../../records/REVIEW.md
  - ../product-roadmap.md
  - ../../rules/workflow/design.md
  - ../../rules/process/review.md
  - ../../rules/method/pipeline.md
---

# 功能索引层与编写侧判据前置——把「功能」升格为唯一主轴

> **触发**：2026-10-01 用户提出——「我想补齐需求、设计等文档，为每个过程安排一个角色，或者多个（编写+审核对抗，编写→审核→再编写）。每个功能、每个插件的需求是什么、采用什么技术，我需要个整体文档的感觉，我可以从需求/设计文档树上看每个功能、提出修改。这可能涉及工作模式的改变。」
>
> **本 RFC 的立场**：方向对，落点改。**不新建文档副本层、不按过程铺角色**；唯一新增物是一层「功能索引」，其余靠既有资产 + 机器对拍。

## 一、背景（2026-10-01 现场盘点）

### 1.1 用户想要的，项目里已有八成

| 诉求 | 已有对应物 | 实测差距 |
|:--|:--|:--|
| 每个功能需求是什么 | RFC 七段骨架 + **需求六维**（`workflow/design.md`，2026-08-19 立） | 64 篇 RFC 中**只有 1 篇真写了六维**（含「架构与边界」「质量门槛」关键词者各 1 篇）|
| 采用什么技术 | `.agents/knowledge/reference/` 18 篇（system/product/插件设计/数据冻结） | 有，但按主题散落，不挂功能 |
| 每个插件是什么 | `trading-features.md`（347 行）· `admin-features.md`（149 行），均含「已知缺陷」节 | 已成型，可直接复用 |
| 每功能一份文档 | `feature-reference.md`（自称「功能真相源」，19 条） | **只有「实现」维度**（文件/API/prompt），无需求、无选型理由、无状态、无验收；**1377 行单文件**；**无 frontmatter** → 在 `ai-guard-meta` 强制范围外，属图谱盲区 |
| 编写角色 | `.agents/toolkit/skills/`：code-api-writer / code-domain-writer / ship / data-learn-writer | **12 审查官 : 3 建设技能**，严重不对称 |
| 审核 + 对抗 | `roles/` 12 官 + `checklists/` 13 份 + 对抗官 + **上下文隔离**（每官只喂本域 diff，不喂他人发现） | 已属行业上游，是本项目最成熟的一环 |
| 文档树 | `_index.md` 体系 + frontmatter 图谱 + `ai-guard-meta.sh` | 有，但按**目录**组织，不按功能 |

### 1.2 五根轴，缺一根主轴

现状：时间轴（`.agents/direction/rfc/` 64 篇）· 主题轴（`.agents/knowledge/reference/` 18 篇）· 严重度轴（`REVIEW.md` 609 行，按 P0/P1/P2 组织）· 前端模块轴（`feature-reference.md` 19 条）· 版本轴（`product-roadmap.md`）。

后果：问「learn 插件现在欠着什么、需求定在哪一版、为什么选这个技术」要人肉翻五个地方；问「这个功能改过几次、哪份 RFC 是当前有效的」要靠时间戳猜。

### 1.3 状态没有机器真相

`.agents/direction/rfc/*.md` 的 `status` 实测有 **9 种以上写法**：`implemented`(23) · `approved`(19) · `draft`(14) · `superseded`(3) · `revised`(1) · `new` · `completed` · `active` · `accepted`，另有带注释与全角括号的变体。

`ai-guard-roadmap.sh` 只对拍规划文档（roadmap ↔ status.md），**没有任何守卫对拍功能级状态**。结论：手写状态必漂移（`method/pipeline.md` 原则 1「机制 > 内容」在此未被贯彻）。

### 1.4 对外部实践的取舍（2026-10-01 调研，来源见文末）

- **抄**：分层 + 上层只链接下层、禁止复制（Google Design Docs）；验收标准用可测试句式（Kiro/EARS 的 `WHEN … SHALL …`）；docs-as-code 门禁（断链/孤儿/生成对齐 → CI 红灯）。
- **不抄**：每功能 8 份 markdown 的 SDD 全流程（Böckeler 实测「一个小 bug 被拆成 4 个 user story + 16 条验收标准」）；spec-as-source（Tessl 式代码生成）。
- **本项目的硬约束（来自调研）**：无外部反馈时，同模型自我审查在推理任务上**无效甚至更差**（Huang et al., ICLR 2024）；多智能体 token ≈ 15×、编码任务并行度低（Anthropic）。→ **「编写→审核→再编写」不能是同一个 prompt 换角色名空转**，审核者必须拿到不同信息通道（测试结果 / 契约 diff / 运行日志）。

## 二、问题清单

| # | 病灶 | 证据 |
|:--|:--|:--|
| P1 | **功能不是一等公民**：五根轴各自成立，无一根以功能为主键 | §1.2；`REVIEW.md` 按严重度组织，「这个功能欠什么」需人肉 grep |
| P2 | **已有规范零执行**：六维立了 43 天（2026-08-19），采用率 1/64 | §1.1、§1.3 |
| P3 | **状态字段失控**：9 种写法 → 机器无法对拍 → 漂移无人管 | §1.3 |
| P4 | **判据只在事后**：13 份 checklist 全供审查官用，编写时用不上 | `process/review.md` 路由表 |
| P5 | **编写侧对抗缺外部信号**：RFC 由 AI 自由发挥，无「作者-审查者」闭环，也无「何时该对抗」的判据 | §1.4 |

## 三、方案

### 3.0 三条设计原则（先立规矩，再谈产出）

1. **索引不复制**：功能索引层**只放一行 + 链接**，正文各归其层（需求在 RFC、设计在 architecture、实现在 feature-reference、缺陷在 REVIEW）。理由：文档树一旦做内容副本，它自己会成为漂移最严重的一层。
2. **按需生长（不一次写全）**：只有被用户提出修改、或本批次要动的功能才补卡；没人碰的功能只留索引行。「用一次、长一张」——**不常用的功能不值得写文档**；一旦它出缺陷（进 REVIEW）自动触发补卡。Google 判据作参考：需要 upfront 澄清 / 跨边界 / 不可逆决策的功能**优先**补卡。
3. **状态由机器对拍**：人能写的字段一定漂移；功能状态由脚本从代码/契约/RFC 推导或校验。

### 3.1 唯一新增物：功能索引层（`.agents/knowledge/features/_index.md`）

一层注册表，一个功能一行（主键 = 用户可感知功能，不跟代码类名走）：

| 列 | 来源 | 机器可校验 |
|:--|:--|:--:|
| `ID` | 人（稳定 slug，如 `learn.digest`）| — |
| `功能名` / `插件` | 人 | — |
| `状态` | **脚本对拍**：`idea / rfc / designed / building / shipped / retired` | ✅ |
| `需求出处` | 链接到 RFC | ✅ 链接可达 |
| `设计出处` | 链接到 architecture | ✅ |
| `实现基准` | 链接到 feature-reference 锚点 | ✅ 锚点存在 |
| `接口` | 端点清单（或 `—`）| ✅ 端点真存在（复用 `ai-guard-align` 能力）|
| `验收标准` | 1–3 条可 grep 的判据 | ⚠️ 人工 |
| `未修项` | REVIEW 编号 | ✅ 编号存在 |

**意图卡**（按需生长，不是一次写全）：

- **触发**：用户提出改它 / 本批次要动它 → 才补卡（AI 从现有 RFC + 代码出初稿，人扫一眼，约 5 分钟）。
- **粒度**：**一个插件一个文件**（≤6 份，如 `.agents/knowledge/features/learn.md`），文件内按顺序排该插件的功能卡。避免 20 个文件带来的 frontmatter / 目录登记 / 索引与卡互相同步的成本。
- **单卡 ≤12 行**，只 4 项：**干什么用 / 不做什么 / 选型理由（有取舍才写）/ 验收标准**。「为什么做」链接 RFC，「欠着什么」看索引行。
- **硬约束**：卡内禁写实现细节（字段名 / 方法名 / 端点 / 请求响应）——写了脚本红灯。理由：实现细节高频变化，抄进卡里必烂。

### 3.2 状态机 + 机器对拍（`.agents/mechanism/guards/ai-guard-feature.sh`）

- 新脚本（与 `ai-guard-meta.sh` 同构：bash + inline python3），挂 **pre-commit**。
- 检查项：① 索引行字段齐（F1）② 所有链接可达（F2）③ 端点真存在（F3）④ `状态` 与证据一致（有 RFC 无代码 → ≤`designed`；有端点无 RFC → 告警）（F4）⑤ RFC `status` 枚举合法（F5，**新文件强制，存量渐进**）⑥ 索引无孤儿（代码里有模块/端点但索引无行）（F6）⑦ 卡内无实现细节（F7：字段名 / 方法名 / 端点 / 请求响应）⑧ 单卡 ≤12 行（F8）。
- **不做**：不回填 64 篇存量 RFC 的六维与 status（一次性迁移成本高、收益低）。

### 3.3 编写侧判据前置 + 受控对抗闭环

- **判据前置（零新增角色）**：把 `checklists/` 的 13 份清单**双用**——编写时按改动类型加载同一份清单的「应做」部分，审查时用「检查」部分。同一判据，两个消费时机。
- **对抗闭环只在三处跑**（架构选型 / 数据口径 / 契约变更）：`作者 → 对抗官 → 作者`，**≤2 轮**，第 3 轮升给人拍板。
- **硬前置**：审查者必须拿到**外部信号**（测试输出 / 契约 diff / 运行日志 / 失败复现）；没有外部信号时不派官——否则就是「同 prompt 换角色名」，必然退化成点头。
- **量级匹配**（沿用 `workflow/design.md`）：字段增删 / bug 修复 / 样式 → 直接改，不进本流程。

## 四、决策点（待拍板）

| # | 决策 | 推荐 | 理由 |
|:--|:--|:--|:--|
| **D1** | 功能粒度：用户可感知功能 vs 代码模块 | **用户可感知功能** | 与 `feature-reference` 19 条、双端功能清单对齐；跟类名走会随重构腐烂 |
| **D2** | 条目形态：索引行 + 意图卡，写到多细 | **已定（2026-10-01 拍板降本版）**：索引行全覆盖 + **按需生长**的 ≤12 行意图卡（**每插件一份文件**，≤6 份），卡内禁写实现细节 | 一次性成本 ≈0、要管文件 ≤6；代价＝不常用功能长期无卡（可接受——没人用不值得写，出缺陷自动触发补卡）；实现细节抄进卡必烂（调研硬结论：内容副本 = 第二真相源）|
| **D3** | 状态维护：脚本对拍 vs 人工 | **脚本对拍** | P3 实证：9 种写法，人写必漂 |
| **D4** | 对抗闭环范围：三处高风险 vs 全流程铺开 | **三处 + ≤2 轮** | 无外部信号的自我纠错无效；多智能体 15× token；项目 C7 成本纪律 |
| **D5** | 存量 64 篇 RFC：渐进 vs 全量回填 | **渐进（新文件强制，存量下次编辑顺手补）** | 与 `frontmatter-spec §四` 既有策略一致 |

## 五、风险与对策

| 风险 | 对策 |
|:--|:--|
| 索引行长期不更新 → 变成第二真相源 | 状态由脚本对拍（F4）；挂 pre-commit 红灯 |
| 大锤砸核桃（小改动套全流程）| 量级匹配表 + 「按需生长」门槛；验收标准里含「小改动零新增文档负担」的抽样检查 |
| 卡内被抄入实现细节 → 变第二真相源 | 脚本红灯（F7）；卡模板固定 4 项，实现一律链接 |
| 对抗沦为形式（critic 点头）| 硬前置：无外部信号不派官；≤2 轮熔断 |
| 成本上升（token 15×） | 只在三处高风险跑；批量错峰；`ai-guard-cost.sh --record` 入账 |
| 索引与 feature-reference 双维护 | feature-reference 退化为「实现明细」，索引只放链接与验收；同一事实不写两遍 |
| 并发会话冲突（仓库曾发生）| 本 RFC 只新增文件 + 追加 `_index.md` 一行，不改既有正文 |

## 六、落地路径（三批，每批可独立验收）

| 批 | 内容 | 产物 | 验收 |
|:--|:--|:--|:--|
| **批 1**（零代码，约 1 小时） | 建索引表（脚本从 RFC / feature-reference / 代码生成 ≥19 行初稿）+ 建插件卡文件骨架（≤6 份）+ 给 1 个**正在动**的功能写第一张卡作样板 | `.agents/knowledge/features/_index.md` + `.agents/knowledge/features/<插件>.md` 骨架 | `ai-guard-meta.sh` PASS；索引覆盖全部现有功能；样板卡 ≤12 行且无实现细节 |
| **批 2** | `ai-guard-feature.sh`（F1–F6）+ 挂 pre-commit | 脚本 + hook | 故意造一条坏行 → 红灯；正常索引 → 绿灯 |
| **批 3** | 判据前置（checklist 双用）+ 对抗闭环试点一次真实决策 | `skills/` 补一段「编写前加载清单」；一次真实对抗记录 | 试点决策有外部信号附证；轮次 ≤2 |

## 七、验收标准（可测量 / 可 grep）

1. `.agents/knowledge/features/_index.md` 存在，且每行含 `ID | 状态 | 需求出处` 三列非空。
2. `bash .agents/mechanism/guards/ai-guard-feature.sh` 退出码 0；索引中任意一行的链接被删后退出码 ≠ 0。
3. 索引表覆盖全部现有功能（≥19 行）；样板意图卡 1 张，≤12 行且**无实现细节**；卡文件 ≤6 份。
4. 本 RFC 自身满足六维（下方六节齐备，可 grep `### 目标与约束` … `### 安全约束`）。
5. 抽查近 5 个「bug 修复」类提交：**不新增索引行 / 卡**（口径修正 2026-10-01：原写「零新增文档负担（无 md 变更）」与实际相反——近 12 个提交 **10 个含 md**，本层不该、也做不到「不许动 md」）。
6. `feature-reference.md` 与索引无重复正文（索引只出现链接与验收标准，不复制功能描述段落）。

## 八、需求六维（本次自证，作为样板）

### 目标与约束

- **要**：让「功能」成为可索引、有生命周期、可机器对拍的一等公民；让已有规范（六维 / checklist）真正被执行。
- **明确不做**：不新建文档副本层；不为每个过程的每个阶段配角色；不手工维护状态；不回填 64 篇存量 RFC。

### 架构与边界

- 落点：`.agents/knowledge/features/`（新增）· `.agents/mechanism/guards/ai-guard-feature.sh`（新增）· `.agents/toolkit/checklists/`（复用，不重写）· `.agents/toolkit/skills/`（补一段）。
- **不动**：`services/**`、`apps/**`、`os/**`、`data/**`；不改 `feature-reference.md` 正文（仅后续自然退化）；不动既有 RFC 正文。

### 技术规范

- Markdown + YAML frontmatter（遵循 `.agents/frontmatter-spec.md` D1 契约）。
- 脚本与 `ai-guard-meta.sh` 同构：bash 包装 + inline python3，只读检查、失败非零退出。
- 命名遵循 conventions 的 D2 目录治理：`.agents/knowledge/features/_index.md` + 每目录 `_index`。

### 质量门槛

- 批 1：`ai-guard-meta.sh --fix` PASS、索引链接全可达、样板卡无实现细节。
- 批 2：`ai-guard-feature.sh` 正例绿 / 反例红（双向验证，不接受只测正例）。
- 批 3：试点决策产出物含外部信号（测试输出或契约 diff 或日志片段）。

### 边界条件

- 功能退役（如已退役的 project-os / 项目管理模块）→ 状态 `retired`，保留行不删除。
- 功能合并 / 拆分 → ID 不复用，旧行标 `retired` 并链接新 ID（防历史断链）。
- 并发会话：本 RFC 只新增文件 + `_index.md` 追加一行。
- 索引与代码暂时不一致（正在开发中）→ 状态 `building` 明确允许无端点。

### 安全约束

- 不落 `data/` 隐私内容（索引只放路径与端点名，不放用户数据）。
- 不新增任何外向动作（不部署、不推送、不外部请求）。
- 不改任何代理配置；不触碰 `os/**` 知识资产与 `data/**`。

## 九、关联外部来源（2026-10-01 调研）

- [Design Docs at Google](https://www.industrialempathy.com/posts/design-docs-at-google/) — 分层、不复制接口与 schema、review 的价值在「改动还便宜时发现」
- [GitHub Spec Kit](https://github.github.io/spec-kit/index.html) — Constitution → Specify → Plan → Tasks 分层
- [Exploring Gen-AI SDD tools（Böckeler/Thoughtworks）](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html) — 三级 SDD 与「大锤砸核桃」实测
- [Spec-driven development: the waterfall strikes back（marmelab）](https://marmelab.com/blog/2025/11/12/spec-driven-development-waterfall-strikes-back.html) — 双重审查、虚假安全感、存量库不适用
- [LLMs cannot self-correct reasoning yet（Huang et al., ICLR 2024）](https://arxiv.org/abs/2310.01798) — 无外部反馈时自我纠错无效
- [How we built our multi-agent research system（Anthropic）](https://www.anthropic.com/engineering/multi-agent-research-system) — 多智能体 +90.2% 但 15× token，编码任务并行度低
- [Why Multi-Agent Systems Fail（Cemri et al.）](https://arxiv.org/abs/2503.13657) — 14 种失败模式
- [Documenting Architecture Decisions（Nygard）](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions) — ADR 不可变、被 superseded 而非改写

## 十、实施记录（2026-10-01）

**批 1 ✅ / 批 2 ✅ / 批 3 ✅**（用户授权自主执行；全程未改代码、未动 `data/`、未部署、未推送、未提交）。

| 批 | 落地 | 证据 |
|:--|:--|:--|
| 批 1 | `.agents/knowledge/features/_index.md`（**37 功能**·5 域）· `.agents/knowledge/features/kernel.md`（第 1 张意图卡 `record`）· `docs/_index.md` 登记 | 链接自检 **37 条 0 死链**；`ai-guard-meta` PASS |
| 批 2 | `.agents/mechanism/guards/ai-guard-feature.sh`（F0–F10）· `.agents/mechanism/guards/tests/guard-feature-fixture.py`（反例回归）· `.githooks/pre-commit` 新增「2b」· `AGENTS.md` + `.agents/_index.md` + `ai-guard-meta.sh` 覆盖 登记 | **反例测试 18 条 FAIL 全触发**（含 4 处假绿 + 1 处误报的回归样本）+ F0 + 存量跳过；端到端跑通 hook（退出码 0）；`ai-guard-feature` 正例 PASS |
| 批 3 | `.agents/rules/workflow/develop.md` 新增「判据前置」入口条与功能主轴出口条；对抗官试点（外部信号＝脚本正反例输出）| 见 `docs/records/audits/` 审查记录 |

### 与方案的偏差（如实）

1. **F3 被改写**：原承诺「端点真存在」，实现为「⚠️ 行必须写明缺因」——索引行没有端点列，端点存在性由 `ai-guard-align.sh`（A1）负责，两处重复没有价值。**若日后索引加端点列，F3 应恢复为端点校验**。
2. **F6 被改写**：原承诺「索引无孤儿」，实现为「『欠着』编号必须在 REVIEW 里存在」。真正的孤儿检测（代码里有、索引里没有）需要代码侧清单，硬做会误报；**取实现得了且当天就有价值的那一半**（编号悬空是真实风险）。
3. **批 1 只建了 1 个卡文件**（方案写「≤6 份骨架」）：其余 4 个域按「按需生长」等真要动它时再建，预建空文件无价值。
4. **D5 未执行全量迁移**：存量 64 篇 RFC 的 status 未回填（符合方案），F5 只对新文件（`date >= 2026-10-01`）强制。
5. **F4 事后补强（自查实证）**：首版 F4 只校验状态枚举，**没有**「状态与证据一致」。抽查索引真实性时当场抓到两处自己填错——`trade.cognition` 标 `shipped` 而手册明写「认知层**待建**」、`share-ext` 的实现章节号填错（在 §19 内、不在 §17）。已补 **F4 粗对拍**（`shipped` 不得配「待建 / 未做 / 无专章 / 待生长」）并修正两行；反例测试同步加一条（现 **17 条 FAIL** 全触发）。**这条是「索引必须机器对拍、不能靠人填」的最直接实证**。
6. **索引消费口也补了两处**（防「没人看 → 腐烂」）：`ai-guard-context.sh` 开工清单新增 **C1.4 功能主轴**（每次开工自动报「全项目 N 个功能 + 卡数」）、`docs/README.md` 功能手册区加索引入口。**新层必须挂在既有消费口上，否则必然成为死文档**。
7. **F4 原承诺未实现（2026-10-01 对抗审查 A2，如实降格）**：§3.2 原写「状态与证据一致：有 RFC 无代码 → ≤`designed`；有端点无 RFC → 告警」——**未实现**。现状＝状态枚举 + 一条关键词粗对拍（`shipped` 不得配「待建/未做」），**状态列仍由人填**。已在 `.agents/knowledge/features/_index.md` 头部**如实降格**（不再宣称「状态由机器对拍」）；真对拍（每行带可机器验证的证据字段）列为后续独立批次。**需求出处同理**：只做存在性校验，不做相关性校验。
8. **pre-commit 挂点加了防御（2026-10-01 对抗审查 A3）**：守卫脚本或索引目录未落地时**跳过而非阻断**——否则部分克隆 / 只改了别的 md 的提交会被 `bash <缺失文件>`（=127）拦死；同时区分「索引 FAIL」与「守卫脚本自身出错」。**代价**：脚本、索引、卡与 hook 必须**同一次提交落地**（已写进完成报告）。
9. **对抗审查闭环（批 3 试点，1 轮）**：独立对抗官只读审查（夹具 23 个自建、外部信号齐全），抓出 **P1×3 + P2×6 + P3×8**，其中 **4 处结构性假绿 + 1 处误报**已全部修复并**固化为回归样本**（`tests/guard-feature-fixture.py` 现 18 条 FAIL 全触发）；报告与逐条处置见 `docs/records/audits/2026-10-01-feature-index-adversarial.md`。**交叉印证**：审查官独立命中的两处（切卡假绿、认知层状态填错）与作者自查重合。

### 幽灵引用的处置（2026-10-01）

`push` / `entry` 两个功能的原始编号「20260913」**无实体文件**（全仓库 **6 个文件 / 12 处**引用）。按目标「如实标注 + 改指实现出处、不编造历史」执行：

- **索引层如实标注**：`.agents/knowledge/features/_index.md` 两行的需求出处写「⚠️ 无 RFC 文件（2026-09-13 批直接实施）」。
- **现行文档加注**（3 处）：`.agents/knowledge/reference/contracts/api-spec.md` §19 · `.agents/knowledge/reference/manuals/feature-reference.md` §18 与 §19 的标题注明「原编号 RFC 20260913 ⚠️ 无实体文件，2026-10-01 核实」。
- **历史记录不动**（4 处：change-log / status / REVIEW / deployment）：历史如实保留，不篡改。
- **未做**：不新建一份「补记型」RFC——那会把今天的推断伪装成当时的决策。

### 仍待用户拍板（唯一遗留）

是否要**补一份真正的决策记录**（凭 change-log + 实现倒推，明确标注为事后补记）。AI 不替用户决定这件事。
