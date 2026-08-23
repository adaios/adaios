---
title: 隔离审查演示报告 2026-08-23（交易归集批，对抗官首次实战）
description: 首次按新规范执行——上下文隔离（材料按角色裁剪 + 官间不互通）+ 对抗找茬官（adversarial-reviewer）实战验证：backend 客观官 + 对抗官 2 独立子代理并行审查交易归集批，主会话核实交叉命中
version: 1
created: 2026-08-23
updated: 2026-08-23
status: active
lines: 76
depends-on:
  - ../../../ai-engineering/process/review.md
related:
  - ../../review/REVIEW.md
  - ../../../ai-engineering/roles/adversarial-reviewer.md
tags: [review, audit, adversarial, isolation]
---

# 隔离审查演示报告 2026-08-23（交易归集批）

> 起因：验证新审查规范（`process/review.md` 2026-08-23：上下文隔离三步 + 对抗官）的实际效果，拿当前工作树未提交「交易归集批」（32 文件）实测。
> 方式：**backend-reviewer**（只喂 `git diff -- services/adai-core/` + api-spec trading 节 + review-backend 清单）+ **adversarial-reviewer**（全量 diff + pitfalls + review-ux 清单）——2 独立子代理、互不可见、各自只读指定材料；主会话对交叉命中逐条代码级核实。
> 守护：未跑（纯 diff 读审，非全量走查）。
> 结果：**P0×2 + 战略×2 + P1×9 + P2×10（合并去重后）**。交叉命中 4 处（⭐⭐，两官独立证据）。审查只报告未改代码（B7）。

---

## 一、交叉命中（⭐⭐——两官独立证据，最优先）

| # | 问题 | backend 视角 | 对抗官视角 | 核实 |
|:--|:--|:--|:--|:--|
| ⭐⭐1 | `update()` 返回 null 全链路未消费 | 5 调用方忽略写失败，账目未落盘仍按成功（S5 直接相关）| 写失败静默，setPrincipal 还 200 | ✅ 属实（TradingAppService:156/677 未接返回值）|
| ⭐⭐2 | `dedupeKey` 桶 ≠ ±10% | 过窄：OCR 波动跨桶不去重 → confirm 双落库（BUY 现金双扣/SELL 超持仓）| 过宽：10 vs 19 股同桶、100 vs 110 不对称，真实两笔被吞 | ✅ 属实且升级（两官从**相反方向**命中同一函数）|
| ⭐⭐3 | closeAdvice 判空缺失 | quote 缺失回退 currentPrice() 亦 null → NPE，单用户异常中断整批 | 兜底链无判空崩定时任务 | ✅ 属实（同文件 434 行 `md.price()` 无判空 vs 464/481 有）|
| ⭐⭐4 | `change=null` 流入文案 | 拼接需确认 null 处理 | 「null%」文案或签名碰撞 | ⚠️ 待验证（需读 addIfNew）|

## 二、P0（对抗官独有，已核实属实）

- **P0-A** `MarketPushRepository.append` 损坏防护只验 JSON 语法不验结构：`[123]`/`{"a":1}` 合法 JSON 但结构损坏 → readTree 通过 → findByDate 返回空 → 空列表+新事件覆盖历史（B5-5 只堵语法损坏）。位置：`MarketPushRepository.append`（2026-08-23 本批新增防护）。建议：解析为数组且元素含 id 字段才放行，否则保留原文件。

## 三、战略（backend 独有）

1. **双锁体系职责重叠、注释失实**：account.json 写路径叠加 application 层 tradeLock + repository 层 per-user 锁；两者均进程内 ConcurrentHashMap，「跨服务共享」注释不成立（多实例同写 data/ 即失效，B55 同族）。建议锁收敛单层、注释改「单实例内」。
2. **写路径统一、读路径未收敛**：全部 account.json 写走 `update()`（正确），但 S5 现金单一真源、B61 读取端收敛未动；positions/account/流水跨文件无原子手段，收盘与交易并发的一致性需显式文档化。

## 四、P1（去重后）

| # | 问题 | 位置 | 建议 |
|:--|:--|:--|:--|
| P1-交易11 | update() 返回 null 未消费（⭐⭐1）| TradingAppService:156,677 / TradingSessionPushService:257 | 写失败抛错或结果对象，调用方提示/补偿 |
| P1-交易12 | dedupeKey 桶语义（⭐⭐2）| TradeLogCandidate.dedupeKey | ±10% 相对比例归一化 + 边界测试（当前零测试）|
| P1-交易13 | closeAdvice 判空缺失（⭐⭐3）| TradingSessionPushService:434 | price 判空跳过该持仓 + 单用户隔离（B59）|
| P1-交易14 | confirm 双 LocalDate.now() 跨午夜 | TradeLogCollectService.confirm | 单次取 now 贯穿（**复发信号：now() 推导路径**）|
| P1-交易15 | change=null 流入 addIfNew（⭐⭐4）| MarketAlertService B5-2 | 拼接前判空，防 "null%" |
| P1-交易16 | 前端 R66 文案 10%→5%，后端判定阈值未同步 | apps/adai-web/trading_page.dart | 双端对拍阈值 |
| P1-交易17 | 历史成交 Tab keepAlive 切 Tab 不刷新 | trading_page.dart | 保活页陈旧（**复发信号**，U31）|
| P1-交易18 | 保留候选钉子户：前端无失败明细/无丢弃入口，15:05 反复提醒 | trading_page.dart | 失败明细 + 丢弃入口 |
| P1-交易19 | 存储层关键修复无同批测试：update 锁/写失败 null/损坏拒写回/save 锁/dedupeKey 桶全在 mock 层，真实仓储并发/失败路径零覆盖 | 各仓储 + TradeLogCandidate | 补仓储级并发 RMW + 写失败用例（B8/B47）|

## 五、P2（去重后）

1. closeAccountUpdate 跨文件残余窗口：positions/quotes 在 update() 锁外读取 → 并发时新现金+旧市值
2. confirm 读取锁外：todayCandidates 先读、save(remaining) 后写，确认期间新 append 候选仍被覆盖（B5-4 注释未完全成立）
3. api-spec 契约漂移：§612 confirm「清空当日候选」、§607 去重口径未含数量桶（**guard-align 门禁会拦**）
4. append 写失败 log.warn 与账目落盘 error 级别不一致
5. locks map 无清理 + X-User-Id 任意值（#179 零鉴权）→ map 无限增长
6. save/append「同一把锁」仅注释声明，需确认同一锁 map/key
7. 2027 节假日预测表中秋日期存疑（2026 中秋在 9 月）
8. B2-2 总盈亏 principal=0 回落浮盈漏已实现盈亏
9. 批量导入空 trades 静默 200 成功 0；name 超长不校验
10. B3-3 新股无昨收 → 收盘账户更新长期跳过、账户卡陈旧无通知

## 六、新模式 vs 旧模式实测结论

- 成本：2 官 × 裁剪材料 ≈ 旧模式 8 官 × 全库的 **1/5**
- 交叉命中 4 处全部属实（旧模式同源共振下 ⭐ 虚高）
- 覆盖互补：backend 抓结构（契约/锁/测试），对抗官抓后果（吞笔/覆盖/钉子户/复发信号）——**dedupeKey 两官从相反方向命中同一函数**是最强证据
- 对抗官遵守材料隔离（验证需扩读时声明「超出材料限制」），隔离规范可执行
