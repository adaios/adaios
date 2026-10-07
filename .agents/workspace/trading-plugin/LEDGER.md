---
title: feat/trading-plugin · 分支账本
description: ② 交易线的分支账本——本批目标 + 并行作业纪律 + 待交接；一个分支一个目录（feat/trading-plugin → trading-plugin/）；合并时搬运完即删整目录
version: 1
created: 2026-10-05
updated: 2026-10-08
status: active
lines: 93
depends-on: []
related: [../../records/REVIEW.md, ../../rules/guides/git-workflow.md, ../../rules/guides/worktree-workflow.md]
tags: [workspace, task, trading, ledger]
---

# 交易线首批任务卡

> **在制品**——完成后删除；定稿的结论入 `../../records/`（收工由主仓库会话办理）。

## 本批目标

从 `.agents/records/REVIEW.md` 取两条**口径已明确、只欠动手**的：

| 编号 | 一句话 |
|:--|:--|
| **P2-交易87** | 「今天没动」已能回填进计划记录，但**收盘复盘与 20:30 提醒还不感知它**——用户填了，当晚的复盘读起来仍像「这天什么都没有」 |
| **P2-交易81** | 新用户「初始化顺序」未产品化：正确顺序（持仓股 → 资金股份 → 历史成交）只写在 owner 私有运维手册，产品内零引导，第一次用处处撞墙 |

## 三条纪律（违反会造成账本冲突或静默污染）

1. **不写三本账**——`change-log.md` / `REVIEW.md` / `status.md` 由**主仓库统一誊写**。
   本批的「摘要 + 出表项 + 测试数变化」写在本目录（如 `trading-batch1-notes.md`）。
2. **默认不碰 `.agents/` 机制**（脚本、守卫、规范由 ① 线统一改）——**例外**：2026-10-06 用户授权
   本分支直接改流程契约（`rules/process/` · `toolkit/roles/` · `workspace/` 契约）；改了要**登记**并跑守卫。
3. **不跑全局状态命令**——`task-cadence.sh ship` / `mark` 在 worktree 里会被守卫**直接拒绝**（rc=2）；
   `ai-guard-context.sh --write-local` 会**覆盖主仓库的开工快照**。两者都不要跑。

## 提交

- **显式路径** + `ADAI_BATCH_PATHS="<前缀1>,<前缀2>"`，**禁 `git add -A`**（pre-commit 第 0 层范围守卫）。
- 信息格式 `type(scope): 摘要`，正文写**为什么**（不是「改了什么」——diff 已说明）。
- **分支上只提交**；收工 / 合并 / 发布在主仓库办理（说一声「合」即由主会话执行）。

## 待交接给主会话（合并后改外围）

- 🟥 **未交付：前端整体（web / app）——编码段只交了后端（2026-10-06 发现）**
  **设计 §6「三端呈现」的 web/app 范围 0 实现**：本批 78 文件里 **67 个是后端**，前端三端 11 个文件**全是**推送卡徽章 / B1 文案那类顺手修复；`R-02`～`R-13` 的界面**一个都没做**（新端点 `advisory` / `rules/*` / `analysis/*` / `trading/import` 在 `apps/adai-web/` **0 引用**）。
  **补做清单 + 分批 + 验收口径 → [`frontend-gap-20261006.md`](./frontend-gap-20261006.md)**（批 1 = `R-12` 一次交文件 + `R-05` 看见第一笔分析，走通需求**验收 1**）；登记 `REVIEW P1-交易101`。
  **教训**：本清单**原本没有「未交付项」这一栏**——缺口因此一路绿灯过审 / 合并 / 上线（深审只审「改动对不对」，前端没动＝不在视野内）。**今后本栏为强制项**：交付时必须逐条对照设计范围标「已交付 / 未交付」。
- ✅ **已完成（2026-10-06 · `8bb5291b`）**——**`routine.md` 的导入顺序**：改成「**资金股份查询 → 持仓股 → 历史成交查询**」（2026-10-05 用户定：**钱先于货**）。
  机制上两份快照互为锚定、**谁先都行**（锚定日取较晚者）——统一口径即可、非硬约束；硬约束仍是**快照在流水之前**。
- ✅ **已完成（2026-10-06 · `8bb5291b`）**——顺带：`REVIEW.md` **P2-交易81** 正文里引的旧顺序（「持仓股 → 资金股份 → 历史成交」）需同步。
- ✅ **已完成（2026-10-06 · `8bb5291b`）**——**`REVIEW.md` P2-交易66**（总盈亏依赖手工本金、不可自证）：**现在有解了**——通达信「**资金流水查询**」里有银行转存/转取事件，**净投入可由事件推出**（2026-10-05 实测与已核实真值一致）→ 该条从「**无解**」变成「**待实施**」，请主会话据此更新。
- **需求文稿已定稿**（2026-10-06，`workspace/trading-plugin/requirement.md`）→ 合并后由主会话**归档到 `.agents/direction/rfc/`**（或 `knowledge/features/`）**并删本分支目录**。
- ~~新角色~~ **已完成**（2026-10-06）：`docs-requirement-reviewer` 已建（`roles/` + `checklists/`）+ 注册 `ai-sync-agents.sh` + `review-driven.md` 入口加环。
- ~~流程问题 12 条~~ **已完成**（2026-10-06）：12 条改进已在本分支落地（`review-driven.md` · **12 个角色补 C1** · `workspace` 契约 · `_templates/`）——逐条状态见 `_meta/process-issues-20261006.md` §三。
- **目录重构已完成**：`workspace/` 从「按产物阶段」改成「**按分支任务**」；旧 `requirements/` `designs/` `tasks/` 三目录与契约**已在本分支删除**（合并后生效）。
- ✅ **已完成（2026-10-06 · `c1bf7955`）**——**★ S2 落点两件（编码准入的硬条件 · 2026-10-06 新出）**：
  ① `_meta/20261006-trading-positioning-expansion.md` → **搬进 `.agents/direction/rfc/`**，按件内「搬到 `rfc/` 时怎么改 frontmatter」那节改格式（`draft → approved` 须**人定稿后**改），并补 `rfc/_index.md`；
  ② `_meta/roadmap-diff-trading-20261006.md` → **按三条改动落进 `product-roadmap.md`**（L6 行 · Trading OS 行 · 挂 `P2-交易81`）。
  **前置**：RFC 里 ★1/★2/★3 三处**待 adai 拍板**——拍完才算定稿，两件齐备前**不进编码**（见 `design-final` §13.3）。**（2026-10-06 已消：★1–★4 已拍板 · 两件已落位）**
- **★ 编码段 deep 深审的誊写与归档（2026-10-06 · 报告 `trading-plugin/review-code-deep-20261006.md`）**——**14 条已于同日全修**（修复批落点见报告 §三；验证：后端 202 suites / **2597** tests · app 441 / web 410 / admin 73 全绿）。报告为在制品（落 `workspace/trading-plugin/`），**收工时**统一办：
  ① **誊写 `REVIEW.md`**：14 条**预分配**编号（P1-交易89~94 · P2-交易95~100 · P2-admin1 · P2-工程16，誊写时核对主仓库占用）→ P1 段注记 + 下方大表 14 行 + 「最近审核」表一行 + `unfixed-gate` 一行（注明「已修出表」）；
  ② **报告归档** → `docs/records/audits/` 下（文件名 `2026-10-06-trading-plugin-code-review.md`）；
  ③ **收工契约账**（align FAIL，ship 前必补）：api-spec 补 11 新端点 + 升版 · status.md 测试数 2462→**2597**、端点数 173→186。

## 本任务遗留记录（随分支归档）

### 本轮进度（UI/UX 体验重构 · 2026-10-07 ~ 08）

- ✅ **差异决算 5 批 + 补批**（`91035896`）——列预算 / 信息带收薄 / 对齐 / P3-6 抽屉 / P3-8 打码
- ✅ **实施清单 7 批**（`fbfed3bd` … `c9a6f329`）——一级 8 区横 Tab + 去二级 · 宽度断点 1200 · 表格自适应（降列 + 操作列冻结）· K 线统一组件（B/T/S + 图例 + 放宽）· 分析屏（默认带票 + 嵌图）· 活跃市值条移规则区 · 文档同步
- ✅ **走查两轮**（`7eab26d2` 决算批回验 · `940286e2` 七批验收）+ 规格补齐（`039e731f`：`design-uiux` / `scope-uiux` / `review-driven §10`）
- ✅ **批 8 已交付（2026-10-08）**：`R-10` 案例 4 格提到首屏（已收下 33 / 成功·失败 33-0 / 本周新增 0 / 等你认 8）+ `R7` 分析标题带名称（`云南锗业 002428 · 做完 0 笔`）；另**更正 v4 的 `R6` 误报**（4 格一直在渲染，只是位置在页面下方）—— 见走查 §八附
- ⏳ **待派**：`design-uiux-20261007.md` 的**独立设计审核**（`ux-interaction-reviewer` + `ux-visual-reviewer`）—— 规范要求设计稿有独立审核官，本轮尚未派
- ⏳ **收尾时办**（沿用本文件下方 10-06 那条的同一批动作）：编码段 deep 深审誊写 · 本轮报告的 `docs/records/audits/` 归档 · `REVIEW.md` 编号誊写 · `status.md` 测试数同步

> **在制品文档地图（2026-10-08 定格）**：需求 `requirement.md` + 3 轮审核 · 设计 `design-v1~v3` + `design-final` + `design-kline-r04` + **`design-uiux`** · 设计审核 `review-v1~v4`（多官）· 交付 `scope-frontend` + **`scope-uiux`** · 走查 **`review-web-uiux`**（§六/§七 两轮）+ **`review-annotations`**（批注与决策）+ **`diff-decisions`**（差异处置）+ **`impl-plan`** · 素材 `uiux-discovery` / `input` / `blueprint` / `overview` / `frontend-gap` · 交接 `handoff-*` · 账本 `LEDGER` · 思考 `thinking-log`

- 🟥 **活跃市值「改为 admin 导入」——已定方向、未落地（2026-10-07 核出并登记）**
  方向（2026-10-05 红线）：行情导入 + 活跃市值导入**都归 admin**、不涉及用户（`blueprint.md` L100）；设计 §5/§6 均声明 admin 侧「公共数据导入」。
  现状：仍是**用户手判**（web/app 红绿切换条 → `PUT /trading/market-stage`）；后端无 admin 活跃市值导入端点、admin 前端无对应界面（行情包导入已有：维护页签 → `POST /admin/market/tdx-import`）。
  闭环：`scope-frontend-20261006.md` 条目表已如实拆分（行情包 = 已交付 / 活跃市值 = 未交付），其去向即本条。

## 运行环境（本 worktree 特有）

- **`data/` 是 copy 快照**（APFS 写时复制，与主仓库**双向隔离**）：这里的写入**不会回流**主仓库，
  主仓库后来新增的数据这里也看不到。需要同步真实数据时找主会话。
- **起后端换端口**：`--args='--server.port=8180'`；前端指向它：`--dart-define=API_BASE_URL=http://127.0.0.1:8180`。
- **构建错峰**：`~/.gradle` 与 pub 缓存与本仓库其余工作副本**共享**，别与主会话同时构建。

## 开工自举（按 AGENTS.md 规则 0）

读 `AGENTS.md`（项目入口）+ `AGENTS.local.md`（开工快照）即可；
**不要**在会话开头跑全量 `ai-guard-context.sh`——它的输出极大（会吃掉大量上下文），效果与读快照相同。
