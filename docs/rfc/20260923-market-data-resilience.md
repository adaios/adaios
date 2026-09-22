---
title: 行情（K 线）链路韧性——域名可配 + 第三源 + 可用性可见
description: 2026-09-22 深夜生产实测三条 K 线来源同时失效（腾讯 K 线域名被 WAF 拦 501 · 东财长期被限 · tdx 数据包滞后）→ 资金曲线/周期盈亏/买点扫描/案例库整段退化，而用户侧毫无提示。本 RFC 定 A（域名可配）/ B（新浪作第三层兜底）/ D（可用性可见）三条治法与验收口径。
date: 2026-09-23
status: implemented
decided-by: adai（2026-09-23「ABD 一起，你做，我休息」）
tags: [trading, 行情, 韧性, 可观测性, RFC]
related:
  - ../reference/trading-features.md
  - ../review/REVIEW.md
  - ../reference/status.md
  - ../../services/adai-core/src/main/java/com/adaiadai/core/application/KlineService.java
---

# 行情（K 线）链路韧性——域名可配 + 第三源 + 可用性可见

> **触发**：2026-09-22 第十三次部署（v3.83）后做生产真链探针时发现——`GET /trading/buy-points/scan`
> 如实报出「**22 只自选全部没取到行情**」。顺着查下去，K 线链路（日 K，非实时行情）三条来源**同时**失效。

## 一、现场证据（2026-09-22 23:38，从生产机实测）

| 源 | 结果 |
|:--|:--|
| 腾讯 K 线 `web.ifzq.gtimg.cn/appstock/app/fqkline/get` | **501 + `waf.tencent.com/501page.html`**；加 iPhone UA 与 `Referer: https://gu.qq.com/` **仍 501** = **IP 级拦截**（不是请求头问题） |
| 腾讯 K 线备用 `proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get` | **200**，返回有效前复权 K 线（含 2026-09-21） |
| 新浪 `money.finance.sina.com.cn/.../getKLineData` | **200**，返回有效 K 线（含 2026-09-21） |
| 东财 `push2his.eastmoney.com` | **000**（连接层，长期被限，既有已知） |
| 腾讯**实时行情** `qt.gtimg.cn` | **200 正常** |
| tdx 本地数据包 | 最后一根 **09-18**（缺 09-21）→ `tdxStale` 判滞后 → 改走网络源 |

**影响面**：资金曲线、周期盈亏（日/周/月）、买点扫描（自选信号列 + 早盘买点段）、案例库特征与匹配。
**不受影响**：早盘/尾盘推送与行情异动（走 `marketDataSource.quote` = 实时行情，`qt.gtimg.cn` 正常）。

## 二、根因（两层）

1. **单点依赖**：网络 K 线只有「腾讯 + 东财」两条，而**两者同属被风控对象**（东财被限是常态、腾讯这次是 WAF）——「同时挂」不是小概率。
2. **不可见**：链路整段失效只落在日志（`KlineService` 的 WARN/ERROR）里；用户侧看到的是曲线平了、信号没了，**没有任何提示**。
   与 **P1-交易60** 同族：*「不知道」没有被渲染成「不知道」*。本次也是靠部署探针才发现，而不是系统自己报出来。

## 三、治法（A / B / D）

### A. K 线域名可配（`adai.market.tencent-kline-bases`，逗号分隔按序尝试）
- 默认改用**备用域名**（实测可用）；风控再变时改环境变量即可，**不必改代码重新部署**。
- **不自动重试老域名**：同一家的两个域名受同一套风控影响，常态下多试一次只换来一次完整超时的延迟（失败路径还有新浪/东财）。
- **区间一律本地裁剪**：实测备用域名 `newfqkline` **忽略 `start/end` 参数**（传 `2026-09-01~09-22` 却返回 `2025-06-05` 起的 320 根）
  —— 不赌第三方参数语义，拿到什么都在本地按 `[from, to]` 过滤，**哪个域名在服务都得到同一语义**（tdx 缺口补齐与案例窗口都依赖它）。

### B. 新浪作**最后一层**兜底（`SinaKlineDataSource`，`adai.market.sina-kline-enabled` 可关）
- 取数链变为 **tdx → 腾讯 → 东财 → 新浪**；正常日子里新浪一次都不会被调用。
- **两处口径差异如实标注、不假装一致**：① 该接口是**不复权**价（tdx/腾讯 qfq/东财 fqt=1 都是前复权）→ 除权日附近会跳空，因此**绝不把它当主源**；
  ② 它的 volume 单位是「**股**」而其它源是「手」→ 实现里 `/100` 统一，免得绝对成交量口径漂移（量比是比值，不受影响）。

### D. 可用性可见（`GET /trading/market-data/health` + 双端横幅）
- `KlineService` 记录：最近一次成功时刻/来源、最近一次**全失败**时刻、连续全失败次数、最后失败的标的。
- 新只读端点 `GET /api/v1/trading/market-data/health` 返回 `{ok, note, lastSuccessAt, lastSuccessSource, lastFailureAt, consecutiveFailures, lastFailedSymbol, sources}`；
  `note` 是**可直接展示的人话**（「行情取数连续 N 次都没拿到（最近一次失败 … · 600487）——资金曲线、自选信号、案例匹配可能不全，我在自动重试」）。
- 双端交易页**只在 `ok=false` 时**出横幅（无异常零显示，不制造噪音）；接口失败静默降级。
- 早盘推送那一半已由 **S-11 B 批**收口（买点段取不到行情会如实说，不再静默少几行）。

## 四、验收（怎么算做完）

1. **换域名后链路恢复**：生产 `GET /trading/buy-points/scan` 的 `unavailable` 清空（或明显减少）；`GET /trading/market-data/health` 返回 `ok=true` 且 `lastSuccessSource` 如实标注。
2. **区间语义不漂**：`klineRange` 结果始终落在 `[from, to]` 内（有回归用例；备用域名忽略参数也不影响）。
3. **不静默**：把三条源全部模拟成空时，`health.ok=false` 且 `note` 说清「没拿到」+ 最后失败的标的（有回归用例）。
4. **可关可退**：`sina-kline-enabled=false` 时行为退回改动前（tdx → 腾讯 → 东财），有回归用例钉住。
5. 三端 `analyze` 0 issue、测试全绿。

## 五、决议记录

| # | 决策 | 结论 |
|:-:|:--|:--|
| D1 | 换用备用腾讯 K 线域名 | **✅ 用户拍板（2026-09-23）**：先用备用域名，老域名不自动重试（配置可切） |
| D2 | 是否加第三源 | **✅ 用户拍板**：加（新浪，最后一层，可关） |
| D3 | 是否做可见化 | **✅ 用户拍板**：做（端点 + 双端横幅） |
| D4 | tdx 数据包 | **待用户操作**：重传一份最新全 A 数据包（治本：本地源不受任何第三方风控影响） |

## 六、非目标

- **不改行情口径**：A/B 只换「去哪取」，不改前复权/成交额校验等既有口径；新浪的不复权差异**如实标注**而不是悄悄混用。
- **不做全市场行情冗余**（多源投票/交叉校验）——本次问题不是数据质量，是可用性。
- **不动实时行情链路**（`qt.gtimg.cn` 正常，且它服务的是早/尾盘推送这类更敏感的场景）。

## 七、部署与验收（2026-09-23 实施）

**后端 v3.84 上线**：`deploy-gate.sh` **GATE-BEFORE 三门 PASS**（guard-meta 154 文件 · guard-align 160 端点 + 四数对齐 · guard.sh 10 PASS/0 HIT）
+ **GATE-AFTER smoke 6/6 + 领域自检 3/3**。

**生产真链验证**（部署后实测）：

| 探针 | 结果 |
|:--|:--|
| `GET /trading/market-data/health` | **200** · `ok=true` · **`lastSuccessSource=腾讯`** · `sources=['tdx','腾讯','东财','新浪']` · `note='行情正常（最近一次 09-23 00:20:38 · 腾讯）'` —— **A 的备用域名在服务**、**四层链在位** |
| 同上（JVM 刚起、还没查过时） | `ok=true` + `note='还没查过行情'` —— **从没查过不制造假警报**，与设计一致 |
| `GET /trading/buy-points/scan` | `unavailable=0` · `dataDate=2026-09-22`（修复前同一端点报「**22 只**没取到行情」） |
| web 四入口 | `/` **200** · `/m/` **200** · `/admin/` **200** · `/privacy` **200**；`main.dart.js` 含 `market-data/health`（双端横幅已上线）；部署前备份 `backups/web-before-20260923-mktdata.tar.gz`（20.4MB） |

**新浪层在生产未被调用**（腾讯成功即返回）—— 符合「正常日子它一次都不调」的设计；其正确性由 6 条单元测试（解析/单位换算/坏行跳过/HTTP 失败降级）保证。

**实施中踩到三个坑，已固化进 pitfalls 二十**（不靠记性）：

1. 本地 jar **夹带了另一个并发会话未提交的改动**（gradle 从**工作区**编译，而工作区混着别人正在写的代码）→ 改用 `git worktree` 在**干净 HEAD** 上构建再部署；
2. `gradlew bootJar -q | tail` **掩盖了「jar 没重新产出」**（第一次部署后新端点 404）→ 现在部署前**断言 jar 内含本批新增类**；
3. `git add` 之后又编辑了 `status.md` → 提交的是**索引里的旧版本**（门禁报「声明 401 / 实测 408」）→ 改完必须重新 add，提交前复核 `git diff --cached`。
