---
title: 协作默契（cadence）
description: 用户与 AI 之间的固定节奏——每日巡检 / 收工 / 每周 / 待办，各自「上次到哪、这次做什么、做完记什么」；由 cadence.sh 调度 + state/cadence.json 记游标
version: 1
created: 2026-09-26
updated: 2026-09-26
status: active
lines: 101
depends-on:
  - ../cadence.sh
  - ../guard-prod.sh
related:
  - ./ship.md
  - ./review.md
  - ../../docs/guides/routine.md
  - ../state/_index.md
tags: [ai, process, cadence]
---

# 协作默契（cadence）

> **由来（2026-09-26 用户原话）**：「我认为我们需要某种默契，比如，每日巡检（**上次巡检日期到现在**，生产日志，内容等）；
> 收尾/收工，提交 diff；还有每周任务、当前待办等；你整理下」

## 一、默契 ≠ 流程

| | 回答的问题 | 例子 |
|:--|:--|:--|
| **流程** | 一件事**怎么做对** | `ship.md`（五件套）、`review.md`（派哪个审查官）、`audit.md`（八官走查） |
| **默契** | **什么时候自动做什么，不用人交代** | 本文件 |

此前的固定动作是**无状态**的：每日巡检永远看「今天 + 近 7 天」，收工不知道上次收工是哪个 commit，
周审不知道自己上周来过没有——于是 AI 每次从零问起，用户每次重新交代背景。**那不叫默契，叫重新认识。**

**默契的技术前提 = 游标**：把「上次做到哪」落盘，AI 下次自己读得到。这就是 `state/cadence.json` 的全部意义。

## 二、四条默契（总表）

| 默契 | 用户说 | AI 自动做 | 游标键 | 产物 |
|:--|:--|:--|:--|:--|
| **每日巡检** | 「每日巡检」 | `cadence.sh daily`——从上次巡检**补看到今天**，逐日跑生产日报 | `inspection.covered_through` | 人话三条：用户之声 / 新异常 / 心跳趋势（规则 8） |
| **收工** | 「收工」「收尾」 | `cadence.sh ship`——本批 diff + 刷开工快照 + 成本入账 | `ship.head` | diff 摘要 + 未提交清单 + 提交建议 |
| **每周** | 「每周」「本周」 | `cadence.sh weekly`——跑每周审查 W1–W6 + 本周人肉清单 | `weekly.week` | 审查结论 + 到期红线（周一 09:00 另有 LaunchAgent 自动跑） |
| **待办** | 「待办」「当前待办」 | `cadence.sh todo`——REVIEW 未修项（战略/P1/P2） | —（无状态） | 当前欠着什么，一眼看全 |

一条命令看全部节奏（开工第一眼，秒回）：

```bash
bash ai-engineering/cadence.sh          # = status：上次巡检/收工/周审 + 欠账提醒
```

## 三、游标：让「上次到现在」成立

**位置**：`ai-engineering/state/cadence.json`（**gitignore**——本机状态，与 `cost-log.jsonl` / `usage-cache.json` 同类）

```json
{
  "inspection": {"last_at": "2026-09-26T02:07:43+0800", "covered_through": "2026-09-26", "runs": 1},
  "ship":       {"last_at": "2026-09-26T02:07:53+0800", "head": "34f6cba6", "subject": "..."},
  "weekly":     {"last_at": "2026-09-26T10:00:00+0800", "week": "2026-W39"}
}
```

三条设计约束（都在 `lib/cadence-lib.sh` 里，被 `cadence.sh` 与 `guard-prod.sh` 共用）：

1. **只前进不后退**——`cadence_advance_day` 比较后写入；补看历史某天（`--date`）不会把游标拖回去
2. **补看不记账**——带 `--date` 的巡检只补历史，游标由 `cadence.sh daily` 统一推进到今天
3. **记账失败静默**——巡检/收工是主角，记账是附注；`source` 失败或写入出错绝不拖垮主角

**谁在写**：`guard-prod.sh` 尾部（无论谁跑都记账）+ `cadence.sh` 收尾。**谁在读**：`cadence.sh status/daily`。

## 四、边界：默契**不**包含的事

| 不做 | 为什么 |
|:--|:--|
| 不自动 `git commit` | 仓库可能有**并发会话**（REVIEW P2-工程2 真实事故）；收工只出 diff 与建议，提交按 `ship.md §7` 显式路径 + `ADAI_BATCH_PATHS` 范围守卫 |
| 不自动 push / 部署 | 原则 B8「外向动作默认不做」，部署走 `deploy-gate.sh` 硬闸门 + 用户确认 |
| 不因记账拖垮主角 | 游标写失败只静默跳过，不改巡检退出码 |
| 不重复造轮子 | 动作本体仍是 `guard-prod.sh` / `weekly-audit.sh` / `guard-context.sh` / `guard-cost.sh`；本机制只加「从上次到现在」+「做完记账」 |

## 五、与既有资产的关系（谁负责什么）

```
cadence.sh          ← 调度 + 游标（本次新增，唯一入口）
├── guard-prod.sh      生产日报（动作本体不变，尾部加记账）
├── weekly-audit.sh    每周审查 W1–W6（动作本体不变）
├── guard-context.sh   开工上下文 / --write-local 刷快照
├── guard-cost.sh      成本入账 --record
└── state/cadence.json 游标（gitignore）
```

- 用户侧人肉清单（盘后三份导出、每周盘账等）仍在 `docs/guides/routine.md`——**routine 管「人做什么」，本文件管「AI 什么时候自动做什么」**
- 产品心跳 C0 在 `AGENTS.local.md` 快照里，巡检时由 `cadence.sh daily` 取最新

## 六、加一条新默契（三步）

1. **定触发词**：用户说什么词触发（写进 `AGENTS.md` 规则段）
2. **加子命令**：在 `cadence.sh` 里加 `cmd_xxx` + `case` 分支；若需记忆，**先在 `cadence-lib.sh` 定游标键**
3. **登记**：本文件总表加一行 + `../_index.md` 若有新文件 + 跑 `bash ai-engineering/guard-meta.sh`

> 反模式：为一件**每次都要人重新交代**的事写脚本——那是工具，不是默契。默契的判据是「**用户少说一句话**」。
