---
title: 协作默契（cadence）
description: 用户与 AI 之间的固定节奏——每日巡检 / 收工 / 发布 / 每周 / 待办，各自「上次到哪、这次做什么、做完记什么」；由 cadence.sh 调度 + state/cadence.json 记游标
version: 1
created: 2026-09-26
updated: 2026-10-03
status: active
lines: 144
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

> **层次（2026-09-26 补，用户纠正）**：本文件是**项目层落地说明**——即「工具层契约在 AdaiOS 的一个实例」。
> **通用节奏与纪律（跨项目、不依赖 AdaiOS）见 `~/.dsh/DEVELOPMENT-WORKFLOW.md`**；
> 本文件只写「AdaiOS 用什么数据源实现这些动作」——既**不重复定义通用契约**，也**不包含产品功能**（学习 / 交易等业务需求不属工作流）。

## 一、默契 ≠ 流程

| | 回答的问题 | 例子 |
|:--|:--|:--|
| **流程** | 一件事**怎么做对** | `ship.md`（五件套）、`review.md`（派哪个审查官）、`audit.md`（八官走查） |
| **默契** | **什么时候自动做什么，不用人交代** | 本文件 |

此前的固定动作是**无状态**的：每日巡检永远看「今天 + 近 7 天」，收工不知道上次收工是哪个 commit，
周审不知道自己上周来过没有——于是 AI 每次从零问起，用户每次重新交代背景。**那不叫默契，叫重新认识。**

**默契的技术前提 = 游标**：把「上次做到哪」落盘，AI 下次自己读得到。这就是 `state/cadence.json` 的全部意义。

## 二、五条默契（总表）

| 默契 | 用户说 | AI 自动做 | 游标键 | 产物 |
|:--|:--|:--|:--|:--|
| **每日巡检** | 「每日巡检」 | `cadence.sh daily`——从上次巡检**补看到今天**，逐日跑生产日报 | `inspection.covered_through` | 人话三条：用户之声 / 新异常 / 心跳趋势（规则 8） |
| **收工** | 「收工」「收尾」 | `cadence.sh ship`——本批 diff + 刷开工快照 + 成本入账 + 审查判定 + **提交本批**（2026-10-03 起默认含提交） | `ship.head` | diff 摘要 + **提交结果（commit 号）**；不 push / 不部署 |
| **发布** | 「发布」「发版」「要不要发」 | `cadence.sh release`——**只判定**：欠着什么没发、要发哪几端（后端 / Web / 管理后台 / iOS） | `release.need` | 逐端判定 + 生产↔本地 commit 对照 + 未推送数（**不部署**，规则 11） |
| **每周** | 「每周」「本周」 | `cadence.sh weekly`——跑每周审查 W1–W6 + 本周人肉清单 | `weekly.week` | 审查结论 + 到期红线（周一 09:00 另有 LaunchAgent 自动跑） |
| **待办** | 「待办」「当前待办」 | `cadence.sh todo`——REVIEW 未修项（战略/P1/P2） | —（无状态） | 当前欠着什么，一眼看全 |

配套命令（**不是默契，是工具**——没有触发词、没有游标，随用随跑）：

| 命令 | 用途 |
|:--|:--|
| `cadence.sh`（无参数） | **状态总览**：上次巡检 / 收工 / 发版体检 / 周审 + **欠账** + 到期红线 + 自动任务健康（秒回） |
| `cadence.sh check` | **交付门禁一键**：guard-meta + guard-align + guard-tools + guard.sh（G1–G7 防复发） |
| `cadence.sh cost [--record]` | 成本：按天 / 会话算钱（`--record` 入账——收工已自动做） |

## 三、游标：让「上次到现在」成立

**位置**：`ai-engineering/state/cadence.json`（**gitignore**——本机状态，与 `cost-log.jsonl` / `usage-cache.json` 同类）

```json
{
  "inspection": {"last_at": "2026-09-26T02:07:43+0800", "covered_through": "2026-09-26", "runs": 1},
  "ship":       {"last_at": "2026-09-26T02:07:53+0800", "head": "34f6cba6", "subject": "..."},
  "weekly":     {"last_at": "2026-09-26T10:00:00+0800", "week": "2026-W39"},
  "release":    {"last_at": "2026-09-26T02:13:00+0800", "need": "none", "prod_commit": "d5eb02cb"}
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
| 提交**默认做、但仍受范围守卫** | **2026-10-03 起「收工」默认含提交**（用户拍板「提交，且以后收工默认连提交」）——执行者是 AI，且在**审查判定之后**（有 P0/P1 先修再提交）。**严禁 `git add -A`**：仓库可能有**并发会话**（REVIEW P2-工程2 真实事故），一律按 `ship.md §7` 显式路径 + `ADAI_BATCH_PATHS` 范围守卫 |
| 不自动 push / 部署 | 原则 B8「外向动作默认不做」。**发布只判定、不执行**：`release` 说清「欠着什么、发哪几端」，真正部署走 `deploy-gate.sh`（门禁 + smoke）且须用户点头（规则 11）|
| 不因记账拖垮主角 | 游标写失败只静默跳过，不改巡检退出码 |
| 不重复造轮子 | 动作本体仍是 `guard-prod.sh` / `weekly-audit.sh` / `guard-context.sh` / `guard-cost.sh`；本机制只加「从上次到现在」+「做完记账」 |

### 收工与审查（工具层 D7 的项目层落地，2026-09-26 用户拍板「按建议实施」）

工具层 `DEVELOPMENT-WORKFLOW.md` 的 **D7** 定义「收工前必判审查」，项目层负责**怎么派、结论去哪**：

| 本批 diff | 审查档位 | 结论去哪 |
|:--|:--|:--|
| 含**代码文件**（`*.java` / `*.dart` / `*.ts` / `*.py` / `*.sh` …）**且**触及**并发 / 数据 / 契约 / 用户可见行为** | **deep**：按改动目录派对应官 + **对抗官**（`process/review.md` §3），**只读、不改码** | `docs/review/REVIEW.md` 新增「独立审查」条目 + `docs/reference/change-log.md` 本批行内写审查说明 |
| 纯文档 / 样式 / 配置 | **light**：`guard-meta` + `guard-align` + 守护快扫（`cadence.sh check`） | change-log 一句话即可 |
| 任意本批 | **P0/P1 先修再提交**；当场修不动的如实登记 REVIEW（写给用户拍板） | REVIEW 未修区 |

- **机械提示**：`cadence.sh ship` 会数本批 diff 里的代码文件数并提示派审（第 ③ 段）——不靠自觉。
- **为什么必须机械**：2026-09-26 一夜 8 批全是「自己写、自己测、自己登记」，用户不追问就没有**任何**独立审查；补审时 4 官当场查出 **P1×4，且 4 条全长在「自己声称已修」的项上**。
- **发布前另有硬闸**：`deploy-gate.sh` 强制 review + guard（项目既有），工具层只承认「发布门禁含审查」。
- **纪律（工具层同款）**：审查结论**不许只留在对话里**——只留在对话里 = 没审。

## 五、与既有资产的关系（谁负责什么）

```
cadence.sh          ← 调度 + 游标（唯一入口）
├── guard-prod.sh      生产日报（动作本体不变，尾部加记账）
├── guard-release.sh   发版判定（只读；release 子命令用它）
├── deploy-gate.sh     部署门禁 + smoke（★ 不自动跑，须用户点头，规则 11）
├── weekly-audit.sh    每周审查 W1–W6（动作本体不变）
├── guard-context.sh   开工上下文 / --write-local 刷快照
├── guard-cost.sh      成本入账 --record
├── guard-meta / align / tools + guard.sh   交付门禁（check 子命令串起来）
└── state/cadence.json 游标（gitignore）
```

- 用户侧人肉清单（盘后三份导出、每周盘账等）仍在 `docs/guides/routine.md`——**routine 管「人做什么」，本文件管「AI 什么时候自动做什么」**
- 产品心跳 C0 在 `AGENTS.local.md` 快照里，巡检时由 `cadence.sh daily` 取最新

## 六、候选默契（2026-09-26 盘点：还有哪些「靠人记」值得收进来）

> **先划界**：本表**只收工作流动作**（节奏 / 纪律）。**产品功能不进这里**——「盘后数据导入」「iOS / TestFlight 打包」「learn 消化」等
> 都是**阿呆的业务需求**，不是流程默契（2026-09-26 用户纠正：「你要区分好什么是项目层，什么是工具层；学习插件、交易插件那是产品功能」）。

| 候选（工作流侧） | 原先靠什么 | 处置 |
|:--|:--|:--|
| 交付门禁（meta / align / tools / 防复发） | 手敲四条命令，散在 `ship.md` | ✅ **已收**：`cadence.sh check` |
| 成本查看 | `guard-cost.sh`，只在收工记账 | ✅ **已收**：`cadence.sh cost`（收工仍自动入账） |
| 到期红线（30 天） | 只在巡检尾部出现 | ✅ **已收**：进 `status`，开工第一眼可见 |
| 定时任务健康（备份 / 周审 / 午间） | 要专门跑 `guard-tools.sh` 才看得到 | ✅ **已收**：进 `status`（**静默失效最危险**——备份停了没人知道） |
| 用户之声 → 需求登记 | 巡检时 AI 提议 | ⏸ **未收**：要语义判断，脚本做不了——仍由 AI 在巡检里提，不让脚本冒充 |
| 生产备份 | LaunchAgent 每日 21:10 自动 | ✅ 已在 `status` 显示（只监管，不需触发） |

> **默契 ≠ 功能清单**。判定四连：**固定触发时机 + 明确产物 + 能被游标记住**；再加层次判据——
> **换项目后「动作本身」不变**才算工作流；要重新定义「它是什么」的，属项目实现或产品需求。

## 七、加一条新默契（三步）

1. **定触发词**：用户说什么词触发（写进 `AGENTS.md` 规则段）
2. **加子命令**：在 `cadence.sh` 里加 `cmd_xxx` + `case` 分支；若需记忆，**先在 `cadence-lib.sh` 定游标键**
3. **登记**：本文件总表加一行 + `../_index.md` 若有新文件 + 跑 `bash ai-engineering/guard-meta.sh`

> 反模式：为一件**每次都要人重新交代**的事写脚本——那是工具，不是默契。默契的判据是「**用户少说一句话**」。
