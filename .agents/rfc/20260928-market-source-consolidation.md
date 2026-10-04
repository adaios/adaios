---
title: 行情渠道收敛与稳定——删东财 K 线源 + 腾讯双域名 + 新浪升兜底
description: 2026-09-28 逐源实测（腾讯 quote 200 · 腾讯 K 线新域名 200 · 新浪 200 · 东财 push2his 000 不可达 · tdx 数据止于 09-04）→ 名义四层链路里三层没在干活，稳定全靠腾讯单点。本 RFC 定「删东财出链路 + 腾讯加第二域名 + 新浪升为兜底 + tdx 按周导入节奏降噪」四件治法与验收口径。
date: 2026-09-28
status: approved
decided-by: adai（2026-09-28「1 落盘，2 做，3 看我心情吧，我基本保证一周导入一次」）
tags: [trading, 行情, 数据源, 稳定性, RFC]
related:
  - 20260923-market-data-resilience.md
  - ../reference/trading-features.md
  - ../records/REVIEW.md
  - ../reference/status.md
  - ../../services/adai-core/src/main/java/com/adaiadai/core/application/KlineService.java
---

# 行情渠道收敛与稳定——删东财 K 线源 + 腾讯双域名 + 新浪升兜底

> **触发**：2026-09-28 用户问「几个行情数据获取渠道，目前什么情况，有稳定的吗」→ 逐源实测后发现，
> 20260923 那份 RFC 建起来的**四层链路（tdx → 腾讯 → 东财 → 新浪）名义上是冗余，实测只有一层在干活**。
> 用户随后拍板：**东财可删**、**腾讯第二域名要做**、**tdx 按「一周导入一次」的真实节奏设计**。

## 一、现场证据（2026-09-28 01:00，从生产机直连各源）

| 源 | 链路角色 | 实测 | 近 7 天日志 |
|:--|:--|:--|:--|
| 腾讯实时行情 `qt.gtimg.cn` | 持仓现价 / 异动推送 | ✅ 200 · 0.18s | **0 次失败** |
| 腾讯 K 线 `proxy.finance.qq.com` | K 线**主源** | ✅ 200 · 0.13s | 腾讯空 75 次，其中 64 次是 512690（代码前缀缺陷，与渠道无关） |
| 腾讯 K 线老域名 `web.ifzq.gtimg.cn` | （未在链路内） | ✅ **200 · 0.13s**（09-22 曾被 WAF 拦 501，已恢复） | 未启用 |
| 新浪 `money.finance.sina.com.cn` | 最后一层兜底 | ✅ 200 · 0.06s | **从未被记录过一次成功或失败**（成功静默、空返回也静默） |
| 东财 `push2his.eastmoney.com` | K 线兜底 | ❌ **000（连接层不可达）** | 失败 1711 · 成功 182（失败率 90%+） |
| 东财 `datacenter-web.eastmoney.com` | 除权因子 | ✅ 200 | 0 次失败 |
| 东财 `searchapi.eastmoney.com` | 名称解析 | ✅ 200 | 正常 |
| tdx 本地数据包 | 名义「第一优先」 | ⚠️ 数据止于 **09-04**（少数 09-18） | 每天 1300～2100 条「tdx 数据滞后」WARN |

**按天看（兜底也空 / 双双失败）**：09-21 `170/1` · 09-22 `1669/3` · 09-23 `15/6` · 09-24 `3/3` · 09-25 `0/0` · 09-26 `0/0` · 09-27 `13/9` · 09-28 `13/2`。
**09-24 之后所有失败 100% 是 512690 一只标的**（剔除后近 5 天零失败，非 ETF 标的上一次失败是 09-22 23:54:55）。

## 二、根因（认知修正）

1. **「四层冗余」是纸面的**：tdx 数据陈旧（不算兜底）· 东财 90%+ 失败（不算兜底）→ 真正能顶上的只有新浪，而新浪**从未被实战验证过**。
   现在的稳定 = **腾讯单点健康**，不是冗余换来的。腾讯哪天再被 WAF 拦（09-22 发生过，断了一夜），链路仍会整段挂。
2. **东财 K 线已无保留价值**：留着只有负面作用——刷日志（7 天 1711 条）+ 熔断期把请求批量打到它身上白等超时。
   但**东财不要一刀切**：除权因子（`datacenter-web`）与名称解析（`searchapi`）两个域名实测可达且 0 失败，**保持不动**。
3. **兜底不可观测**（P2-交易57 的遗留）：09-23 给东财补了成功日志，新浪没有——「最后一层到底能不能顶」至今无法从日志回答。
4. **tdx 的判定节奏与现实不符**：用户实际**一周导入一次**，而 `tdxStale` 阈值是 3 天 → 常态下每周有 4～5 天被判滞后，
   每次取数多绕一次本地读，还每天刷上千条 WARN。
5. **配置钩子缺失**：`adai.market.tdx-enabled` 与 `kline-primary` 在 `application.yml` 里是**硬编码**（无 `${ADAI_...:}`），
   而 `@Value` 不走 relaxed binding → 想临时关 tdx / 切主源，改 `.env` **静默不生效**（2026-09-14 adj-path 事故同型）。

## 三、治法（三批）

### 批 1 — 零代码，改配置即生效（2026-09-28 执行）

生产 `.env` 增设（原先无此项，用 jar 内默认的单一域名）：

```bash
ADAI_MARKET_TENCENT_KLINE_BASES=https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get,https://web.ifzq.gtimg.cn/appstock/app/fqkline/get
```

- 代码语义是**按序尝试、第一个成功即停** → 常态零额外延迟，只在主域名失败时才多打一次。
- 两个域名参数形态同构（`?param=sh600519,day,,,N,qfq`），代码直接拼接即可，无需改动。
- 生效方式：改 `.env` + 重启 `adai-core.service`。

### 批 2 — 代码批（要改代码 + 测试 + 发版）

| # | 改动 | 说明 |
|:--|:--|:--|
| 1 | `KlineService` 去掉东财，**新浪从「最后一层」升为兜底** | 删 `EastMoneyKlineDataSource` 及其测试；链路收敛为 `tdx → 腾讯(双域名) → 新浪`。改动面：4 个文件（`KlineService` / 该类 / `KlineSource` 注释 / `KlineServiceTest`） |
| 2 | 新浪源补成功/失败日志 | 让「兜底是否在工作」在日志里可答；`health().sources` 自动变为 `[tdx, 腾讯, 新浪]` |
| 3 | `tdx-enabled` / `kline-primary` 补 `${ADAI_...:}` 环境变量挂钩 | 消除「.env 配了不生效」的同型坑 |
| 4 | tdx 按**周节奏**降噪 | 「tdx 数据滞后」日志降为 INFO/DEBUG；滞后超阈值时**直接跳过本地读**（不再每次多绕一次文件读） |
| 5 | 兜底低频主动体检（可选，P2-交易58 遗留） | 每日收盘后探一次新浪与腾讯第二域名，让「兜底是死的」在平日就暴露 |
| 6 | tdx 新鲜度可见（可选） | `health` 里带出 tdx 最后一根日期，周导入到期时用户/巡检能看见 |

### 批 3 — 运维动作（用户周期性执行）

**tdx 数据包导入**：`POST /api/v1/admin/market/tdx-import`（multipart zip）入口现成，用户**保证一周导入一次**。
这是唯一不受第三方风控影响的源——**是它把「腾讯单点」变成真正的冗余**，因此批 2 的降噪以「周节奏」为前提设计。

## 四、验收（怎么算做完）

1. **批 1 生效**：重启后启动日志 `TencentMarketDataSource 初始化 | K线域名=[新域名, 老域名]` 含两项；
   `GET /trading/market-data/health` 返回 `ok=true`。
2. **降级链可演练**：把主域名配成不可达 → 观察到降级到第二域名；再把两者都配成不可达 → 降级到新浪且 `lastSuccessSource=新浪`。
3. **东财出链路**：代码与测试中不再引用 `EastMoneyKlineDataSource`；`health().sources` 为 `[tdx, 腾讯, 新浪]`。
4. **不误删东财**：`AdjFactorRepository`（除权因子）与 `NameToSymbolResolver`（名称解析）保持原样且测试全绿。
5. **兜底可观测**：新浪取数成功/失败在日志中可见。
6. 三端 `analyze` 0 issue、后端测试全绿、`ai-guard-meta` / `ai-guard-align` PASS。

## 五、决议记录

| # | 决策 | 结论 |
|:-:|:--|:--|
| D1 | 东财 K 线源去留 | **✅ 用户拍板（2026-09-28）**：删（出链路） |
| D2 | 腾讯第二域名 | **✅ 用户拍板**：加（批次 1，配置级） |
| D3 | tdx 节奏 | **✅ 用户拍板**：不改导入方式，**一周导入一次**；方案按此节奏设计（降噪而非关停） |
| D4 | 方案落盘 | **✅ 用户拍板**：落盘本 RFC |

## 六、非目标

- **不修 ETF（5 开头）前缀缺陷**：`TencentMarketDataSource.kline` / `EastMoneyKlineDataSource` 判前缀只认 `6`/`9`，
  把沪市 5 开头 ETF（如 512690）当成深市代码 → 取数必空（实测 `sz512690` 空、`sh512690` 有数据）。
  用户 2026-09-28 明确「先排除 ETF」，**另案处理**。本文档只如实记录其影响（资金曲线持续缺当日盈亏）。
- **不动实时行情链路**：`qt.gtimg.cn` 7 天 0 失败，且服务早/尾盘推送等更敏感场景；本次不加第二实时源（可作后续备选）。
- **不改行情口径**：只换「去哪取」，前复权/成交额校验等口径不变；新浪的不复权差异**如实标注**，不悄悄混用。
- **不做多源投票/交叉校验**：本次问题是可用性，不是数据质量。

## 七、实施记录

### 批 1（2026-09-28，已完成）

| 步骤 | 结果 |
|:--|:--|
| 备份 | `/opt/adaios/backend/.env.bak-20260928-0107`（改动前，39 行） |
| 配置 | 追加 `ADAI_MARKET_TENCENT_KLINE_BASES=新域名,老域名`（systemd `EnvironmentFile` 加载，重启即生效） |
| 重启 | `adai-core.service` **active** · 启动 **3.861s** |
| 启动日志 | `TencentMarketDataSource 初始化 \| K线域名=[proxy.finance.qq.com/…, web.ifzq.gtimg.cn/…]` —— **双域名在位** |
| health | `ok=true` · `sources=["tdx","腾讯","东财","新浪"]`（批 2 删东财后变三层） |

**真链降级演练**（2026-09-28 01:08，生产机实做）：

1. 临时把主域名置为不可达（`https://127.0.0.1:9/blackhole`）、保留老域名，重启服务；
2. 触发 `GET /trading/buy-points/scan` → **HTTP 200 · 1.146s**（自选标的结果正常返回）；
3. 日志出现 **23 条** `腾讯 K线失败 | base=https://127.0.0.1:9/blackhole`，而**老域名零失败日志** → 第二域名确实顶上；
4. `health`：`ok=true` · `lastSuccessSource=腾讯` · `consecutiveFailures=0`；
5. **配置已恢复**为双域名并重启，服务 active。

→ 验收第 1、2 条 **PASS**（第 2 条的老域名→新浪段待批 2 删东财后一并演练）。

### 批 2（2026-09-28，代码已就绪，待发版）

| # | 改动 | 落点 |
|:-:|:--|:--|
| 1 | **东财 K 线源出链路**：`KlineService` 构造去掉东财，**新浪由「最后一层」升为兜底** | 链路 = `tdx → 腾讯（双域名）→ 新浪`；删除 `EastMoneyKlineDataSource`（无专属测试类）；改动面 4 文件 |
| 2 | **新浪源补可观测**：成功 `INFO`、返回空 `WARN`（原先两者全静默 → 「兜底到底有没有在干活」无法从日志回答） | `SinaKlineDataSource.kline` |
| 3 | **补环境变量挂钩**：`tdx-enabled` 改 `${ADAI_TDX_ENABLED:true}`；`kline-primary` **随东财一并移除**（无第二主源可选，留着就是假开关） | `application.yml` |
| 4 | **tdx 按周节奏降噪**：滞后日志 `WARN`（每天 1300～2100 条）→ **同一滞后日期只记一条 `INFO`** | `KlineService.logTdxStaleOnce` |
| 5 | **tdx 新鲜度可见**：`health` 增 `tdxLastDate`（本地数据包最后一根日期；`null` = 关闭 / 还没取过） | `KlineService.Health` + 双端 |
| 6 | **未做（如实登记）**：兜底源低频主动体检（P2-交易58 遗留，属新增调度能力，建议单开一批） | — |

**为什么不做「滞后即全局跳过本地读」**（原方案第 4 条的后半句）：滞后是**逐标的**的——生产实测同一时刻存在停在 `09-04` 与 `09-18` 两批，全局跳过会误杀仍然新鲜的标的。改为「日志降噪 + 新鲜度可见」，`tdxStale` 阈值仍为 3 天（安全优先：导入后第 4 天起自动走网络源）。

**测试**：`KlineServiceTest` 15 → **16**（删 `eastMoneyPrimaryConfig_switchesOrder`；新增 `tdxDisabled_neverTouchesLocalSource`、`health_reportsTdxLastDate`；`sources` 断言改 `["tdx","腾讯","新浪"]`）；`TradingControllerTest` 两处 `Health` 构造随记录扩字段更新 + 新增 `$.tdxLastDate` 断言。**后端全量 2228 全绿**（原 2227）；`EastMoney` 全仓残留引用 **0**；端点 **160 不变**（仅响应扩字段）。web 端 `MarketHealth` 是通用 `is List` 解析，加字段无需改动。

### 批 3（用户周期性）

tdx 数据包导入：`POST /api/v1/admin/market/tdx-import`（multipart zip）或 admin「系统 → 维护」页上传，节奏＝**一周一次**。这是唯一不受第三方风控影响的源——**是它把「腾讯单点」变成真正的冗余**，因此批 2 的降噪以「周节奏」为前提设计。
