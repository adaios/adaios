---
title: 固定动作清单（每天 / 每周 / 到期）
description: AdaiOS 的周期性人肉工作总清单——哪些系统已自动（只需看）、哪些必须你亲自做（生产日报/盘后导入/备份/审查）、哪些是到期红线；配套 check_deadlines.py、guard-prod.sh 与两个 LaunchAgent
version: 1
created: 2026-09-14
updated: 2026-09-16
status: active
lines: 174
depends-on:
  - ../../docs/reference/trading-features.md
related:
  - ./development.md
  - ../deployment/icp-filing.md
  - ../reference/status.md
  - ../review/REVIEW.md
  - ../../ai-engineering/checklists/cost.md
tags: [guide, routine, ops]
---

# 固定动作清单（每天 / 每周 / 到期）

> **由来（2026-09-14 全仓库盘点）**：用户提出「我每天、尤其每周都要固定做一些工作」——查下来确实有，
> 但**散落在 cron 表达式、脚本头部注释、REVIEW 条目里，没有任何一处汇总**，全靠记。
> 本文件是唯一汇总；三个「本该自动却静默失效」的缺口见 §六。

## 怎么用

**日常只需要一句话：对 AI 说「每日巡检」**（触发协议见 `AGENTS.md` 规则 8）——AI 跑第一条命令并用人话讲结果，你不用敲任何东西。
需要自己查时，四条命令都能直接跑：

```bash
bash ai-engineering/guard-prod.sh                 # 生产日报：生产日志 + 真实对话卡片（每日第一眼）
bash ai-engineering/guard-context.sh            # 开工自动跑：状态/未修项/待办/C0 使用心跳
python3 scripts/check_deadlines.py               # 到期红线（≤30 天告警，≤7 天紧急）
bash ai-engineering/guard-unfixed.sh             # 未修问题全量（REVIEW + task-log + audits）
```

---

## 一、系统已自动（你**只需看**，不需要做）

交易日定时任务（`TradingSessionPushService` / `MarketAlertService`，均有节假日守卫，周末由 cron 排除）：

| 时间 | 推什么 |
|:--|:--|
| 09:15 | 早盘计划（含止损/买点/择时） |
| 12:00 | 午间跟踪 |
| 14:50 | 尾盘建议 |
| 15:05 | 收盘账户自动更新（缺行情则整体跳过并推「未自动更新」） |
| 15:10 | 收盘买点扫描（自选 B1/B2 命中） |
| 15:15 | 收盘交易日志确认（当日有候选才推） |
| 15:30 | **收盘小结**（当日成交 + 破止损 + 待确认候选 + 账实自检行） |
| 每 30 分钟（10–11 / 13–15） | 行情异动（破止损/接近止损/大跌/大涨/破成本/批次止损） |
| 每晚 20:00 | learn 复习提醒（`status=review` 且 7 天未 done） |

行情类推送次日 09:30 自动消失，汇总类次日 23:59 消失——**不用手动清**。

工程侧（定时机制，见 §六 现状）：
- 每日 21:10 → 生产数据备份（`com.adai.adaios-backup`）
- 每周一 09:00 → 每周审查 W1–W6（`com.adai.adaios-weekly-audit`）

---

## 二、每天你必须亲自做（交易日盘后，约 5 分钟）

### 0. 先看一眼生产日报（每天，1 分钟；2026-09-16 起固定）

> **你不用敲命令**——对 AI 说「**每日巡检**」四个字即可（触发协议见 `AGENTS.md` 规则 8）：
> AI 会跑下面这条命令，然后只用人话讲三件事：**用户之声 / 有没有新异常 / 心跳趋势**。
> 想自己看时再手动跑：

```bash
bash ai-engineering/guard-prod.sh
```

一条命令同时给两侧真相，**别看数字，看内容**：

| 版面 | 它在回答什么 | 看到什么要动手 |
|:--|:--|:--|
| 服务 / ERROR | 系统还活着吗 | 任一服务非 active、或出现 ERROR → 立刻查 |
| 告警（人话） | 什么在反复报错 | 新类目第一次出现 → 进 REVIEW；老朋友（东财 Kline）不必每天管 |
| 公网用量 | 有没有真实流量 | 已自动分四类：扫描器 / 部署探针 / 设计语义 / **★待关注**——只有 ★ 要管 |
| **用户之声** | **他今天真问了什么** | 一句产品吐槽 = 一条需求，别让它烂在 data/ 里 |
| 心跳 | 他还在用吗 | 连续多日为 0 → 优先修「让人愿意用」的摩擦，**停止加新功能** |

> **为什么单独立这一条**：2026-09-16 首次跑通，当天 6 张卡里 4 张是产品缺陷反馈
> （卡片乱序 / 交易重复展示 / 输入框表情包多余 / 背面菜单对新用户过载），
> 而 09-11~09-15 连续 5 天 0 张卡——**「有人在用」和「他在骂什么」，只有这里看得见**。
> AI 侧的等价入口：`guard-context.sh` 的 C0 心跳发现今日有新记录时会主动提示跑本命令。

### 1~3. 三份通达信导出（顺序不能换）

持仓/资金是锚点，先建锚定再补流水：

| 顺序 | 导出什么（通达信） | 打哪个端点 | 关键点 |
|:--:|:--|:--|:--|
| 1 | 「持仓股」 | `POST /trading/positions/import` | `replace=true` + `snapshotDate=文件名日期`；当日盈亏列**全表求和**传 `todayPnl`（**含 0 股行**） |
| 2 | 「资金股份查询」 | `POST /trading/imports/cash` | 必须带 `snapshotDate`，否则锚定日被写成今天 |
| 3 | 「历史成交查询」（当天） | `POST /trading/trades/import` | **唯一成交真相源**；可先 `dryRun=true` 预检不写盘 |

**然后看一眼（30 秒）**：
- 手机上的 **15:30 收盘小结**
- 交易页顶部**账实自检横幅**（有 `drift`/`gaps` 才出现——出现就说明账和流水对不上）
- 当日候选是不是都确认了（缺成交日期的要先补）

> ⚠️ 顺序反了的后果：先补流水、后导快照，会被 `auto` 模式当「需回放」→ 与快照双计
> （2026-09-07 / 09-09 / 09-12 三次事故都是这个形态，REVIEW P2-交易38）。

**可选（不固定）**：learn 喂链接消化；app 截图入账替代第 3 步。

---

## 三、每周你必须亲自做

| 事项 | 怎么做 | 为什么不能省 |
|:--|:--|:--|
| 看每周审查结论 | 看 `ai-engineering/state/weekly-audit.log` 尾部 | W1–W5 是「防审查休眠」，FAIL 才是重点 |
| TDX 盘后行情包同步 | admin「系统 → 维护」上传 .zip，或 `scripts/sync_tdx_data.sh <包>` | 本地 .day 决定前复权与买点特征精度；周级全量即可 |
| 未修项过一遍 | `bash ai-engineering/guard-unfixed.sh` | REVIEW 有 30+ 条，没有人替你判断优先级 |
| 盘一次账 | 交易页看账实自检 + 资金快照 | 期末对不上，越晚越难回溯 |

**AI 侧的每周/每次收工（你不用记）**：`guard-context.sh --write-local`（刷开工快照）+ `guard-cost.sh --record`（成本入账）。

---

## 四、到期红线（真正的「不能忘」）

**单一事实源**：`scripts/check_deadlines.py` 顶部的 `DEADLINES`——日期只写一次，三个出口共用：

```bash
python3 scripts/check_deadlines.py                # 人工查看
python3 scripts/check_deadlines.py --ics          # 生成日历文件（默认 ~/Desktop/adaios-deadlines.ics）
```

`--ics` 生成的事件**自带「提前 30 天 + 提前 7 天」两条提醒**，双击导入 macOS 日历即可——
这是 REVIEW P2-APNs5 说的「文档不会主动叫人，要落到日历才算闭环」。

当前登记项：

| 日期 | 事项 | 状态（2026-09-15） |
|:--|:--|:--|
| 2026-09-30 | 公安联网备案（ICP 后 30 天内） | ⚠️ **剩 15 天，未办**（REVIEW P1-合规1） |
| **2026-12-14** | **TestFlight 构建过期**（当前构建 1） | ⚠️ **90 天有效**；到期手机上的测试版打不开 → 发新构建即可（`sh scripts/release_testflight.sh --build-number N`） |
| 2027-01-30 | 域名 adaiadai.com 到期 | ✅ |
| 2027-09-13 | Apple Developer 账号到期 | ✅ 账号与描述文件同日 |
| 2027-09-13 | iOS 描述文件到期 | ✅ |
| **未登记** | **生产服务器续费日** | ⚠️ 连日期都不知道，建议先去腾讯云查 |

> 到期日变更：只改 `check_deadlines.py`，然后重跑 `--ics` 覆盖导入（日历里旧的同名事件先删）。

---

## 五、每月 / 额度型

| 事项 | 周期 | 现状 |
|:--|:--|:--|
| learn 转写额度（10 小时/月） | 月初自动重置 | ✅ 自动，`GET /learn/digest/quota` 可查 |
| DeepSeek 成本 | 每日记录 | ✅ `guard-cost.sh --record`，`cost-log.jsonl` 一日多行 |
| 本地备份清理 | 按需 | 脚本**刻意不自动删**旧备份（只提示），>30 份时人工清理 `~/backups/adaios-prod/` |

---

## 六、2026-09-14 盘点出的三个静默失效（已修）

「固定动作」之所以让人焦虑，是因为**该自动的没自动，而且没有任何地方会说**。查出并修掉：

| # | 缺口 | 真相 | 处置 |
|:--|:--|:--|:--|
| 1 | 生产备份 26 天没跑（最后一次 2026-08-19） | ① 从未挂定时；② **脚本用 `root@` 登录，而生产只允许 `ubuntu@` → 脚本本身根本跑不通** | 改 `ubuntu@` + `sudo`；新增 `com.adai.adaios-backup` 每日 21:10 |
| 2 | 每周审查疑似从未运行 | `crontab` 被 macOS TCC 拦截（2026-08-23 元审核标「⚠️ 待确认」），`/tmp/weekly-audit.log` 不存在 | 新增 `com.adai.adaios-weekly-audit`（launchd 绕开 TCC），日志改 `state/weekly-audit.log` |
| 3 | 到期日只活在文档里 | 公安备案 16 天后到期，此前连 task-log / 快照都没有 | 新增 `scripts/check_deadlines.py`（§四）+ 日历导入 + weekly-audit **W6** 每周播报 |

> **为什么不直接自动续费/自动备案**：B8「外向动作默认不做」——付钱、提交备案必须人确认。
> 自动化只负责**准时叫人**，不代替你拍板。

## 相关

- 交易模块完整功能与定时任务：`../reference/trading-features.md`
- ICP / 公安备案步骤：`../deployment/icp-filing.md`
- 成本纪律：`../../ai-engineering/checklists/cost.md`
- 收尾流程：`../../ai-engineering/process/ship.md`
