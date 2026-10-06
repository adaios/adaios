---
title: feat/trading-plugin · 分支账本
description: ② 交易线的分支账本——本批目标 + 并行作业纪律 + 待交接；一个分支一个目录（feat/trading-plugin → trading-plugin/）；合并时搬运完即删整目录
version: 1
created: 2026-10-05
updated: 2026-10-05
status: active
lines: 67
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

- **`routine.md` 的导入顺序**：改成「**资金股份查询 → 持仓股 → 历史成交查询**」（2026-10-05 用户定：**钱先于货**）。
  机制上两份快照互为锚定、**谁先都行**（锚定日取较晚者）——统一口径即可、非硬约束；硬约束仍是**快照在流水之前**。
- 顺带：`REVIEW.md` **P2-交易81** 正文里引的旧顺序（「持仓股 → 资金股份 → 历史成交」）需同步。
- **`REVIEW.md` P2-交易66**（总盈亏依赖手工本金、不可自证）：**现在有解了**——通达信「**资金流水查询**」里有银行转存/转取事件，**净投入可由事件推出**（2026-10-05 实测与已核实真值一致）→ 该条从「**无解**」变成「**待实施**」，请主会话据此更新。
- **需求文稿已定稿**（2026-10-06，`workspace/trading-plugin/requirement.md`）→ 合并后由主会话**归档到 `.agents/direction/rfc/`**（或 `knowledge/features/`）**并删本分支目录**。
- ~~新角色~~ **已完成**（2026-10-06）：`docs-requirement-reviewer` 已建（`roles/` + `checklists/`）+ 注册 `ai-sync-agents.sh` + `review-driven.md` 入口加环。
- ~~流程问题 12 条~~ **已完成**（2026-10-06）：12 条改进已在本分支落地（`review-driven.md` · **12 个角色补 C1** · `workspace` 契约 · `_templates/`）——逐条状态见 `_meta/process-issues-20261006.md` §三。
- **目录重构已完成**：`workspace/` 从「按产物阶段」改成「**按分支任务**」；旧 `requirements/` `designs/` `tasks/` 三目录与契约**已在本分支删除**（合并后生效）。
- **★ S2 落点两件（编码准入的硬条件 · 2026-10-06 新出）**——本分支只出**草稿**，合并后由主会话搬移：
  ① `_meta/rfc-draft-trading-positioning-expansion-20261006.md` → **搬进 `.agents/direction/rfc/`**，按件内「搬到 `rfc/` 时怎么改 frontmatter」那节改格式（`draft → approved` 须**人定稿后**改），并补 `rfc/_index.md`；
  ② `_meta/roadmap-diff-trading-20261006.md` → **按三条改动落进 `product-roadmap.md`**（L6 行 · Trading OS 行 · 挂 `P2-交易81`）。
  **前置**：RFC 里 ★1/★2/★3 三处**待 adai 拍板**——拍完才算定稿，两件齐备前**不进编码**（见 `design-final` §13.3）。

## 运行环境（本 worktree 特有）

- **`data/` 是 copy 快照**（APFS 写时复制，与主仓库**双向隔离**）：这里的写入**不会回流**主仓库，
  主仓库后来新增的数据这里也看不到。需要同步真实数据时找主会话。
- **起后端换端口**：`--args='--server.port=8180'`；前端指向它：`--dart-define=API_BASE_URL=http://127.0.0.1:8180`。
- **构建错峰**：`~/.gradle` 与 pub 缓存与本仓库其余工作副本**共享**，别与主会话同时构建。

## 开工自举（按 AGENTS.md 规则 0）

读 `AGENTS.md`（项目入口）+ `AGENTS.local.md`（开工快照）即可；
**不要**在会话开头跑全量 `ai-guard-context.sh`——它的输出极大（会吃掉大量上下文），效果与读快照相同。
