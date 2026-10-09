---
title: 后端推送文案批 · 编码段增量深审（2026-10-09）
description: 深审 MarketAlertService 文案重写 / 新增 distancePctText / lockScreenMessage 措辞 / TradingSessionPushService 尾盘收尾句 / MarketAlertServiceTest 断言改动——逐条对拍 design-push-copy §六 与 design-final §11.6，给 P0–P3 结论
version: 1
created: 2026-10-09
updated: 2026-10-09
status: active
lines: 146
depends-on:
  - ./design-push-copy-20261009.md
related:
  - ./scope-app-20261009.md
  - ./design-final-20261006.md
  - ./design-app-20261009.md
tags: [workspace, trading, push, review, backend]
---

# 后端推送文案批 · 编码段增量深审（D-20261009-04）

**轮次**：v1 · 2026-10-09 · **审核**：后端代码官（`code-backend-reviewer` · 子代理 `/root/rev_backend`）
**审核对象**：本批（`e87f926e` 之后）后端推送文案改动，**3 文件 / +54 −27**

| 文件 | 本批改动 |
|:--|:--|
| `services/adai-core/src/main/java/com/adaiadai/core/application/MarketAlertService.java` | `message()` 四类重写 · 新增 `distancePctText()` · `lockScreenMessage()` 措辞 |
| `services/adai-core/src/main/java/com/adaiadai/core/application/TradingSessionPushService.java` | `buildCloseContent()` 尾盘收尾句 |
| `services/adai-core/src/test/java/com/adaiadai/core/application/MarketAlertServiceTest.java` | 5 处断言同步 |

> **判据**：只判改动**对不对**（正确性 + 边界 + 完备性），不判「该不该做」（已拍板）。**只报告不改**（B7）。

## 一、结论

**⚠️ 有条件通过** —— **无 P0 · 无 P1**。

主体文案（B1/B2/B4/B5 + B 组锁屏 + 尾盘收尾句）**逐字对拍 `design-push-copy-20261009.md` §六 成立**；持仓级隐私口径落实（正文去成本数字、锁屏不点名不带价）；测试实跑绿。
需处理：**P2 × 3 + P3 × 4**（下表）。其中 **P2-1 偏强**，若本批随发布上线，建议提前修。

## 二、核对通过（逐条对拍，不计入问题）

| 判据 | 结果 | 证据 |
|:--|:--|:--|
| **B1 破线**文案＝`…在你写的止损 Y 下方（R66）。要不要按你昨晚定的处理？` | ✅ 与 §六 逐字一致 | `MarketAlertService.java:376-378` |
| **B2 临近**文案＝`…离你的止损 Y 还有 <实际距离>%（R66）。` | ✅ 句式一致（**数值未被断言** → 见 P2-3） | `MarketAlertService.java:380-384` |
| **B4 破成本**正文**去成本数字** | ✅ `…跌过你的成本了。成本数字不在推送里，打开阿呆看。` | `MarketAlertService.java:391-393` |
| **B5 异动**（跌 / 涨）去形容词 | ✅ 跌带「离你的线 + 距离」，涨为陈述句 | `MarketAlertService.java:385-390` |
| **B 组锁屏 5 条**措辞 | ✅ 破了你的线 / 快到你的线了 / 今天跌得不少 / 涨得不错 / 跌过你的成本了；**不点标的、不带价** | `MarketAlertService.java:327-336` |
| **R66 保留**（与 design-final §11.6 引用纪律一致） | ✅ stop-loss / near-stop-loss / loss 三处均含「（R66）」 | `:378` `:384` `:388` |
| **合并逻辑仍成立** | ✅ `mergeBySymbol` 重建 `PushMessage` 的 8 参与 `record PushMessage(title,content,type,symbol,name,time,lockScreenContent,lockScreenTitle)` 顺序一致 | `MarketAlertService.java:302-304` ⇄ `PushChannel.java:61-70` |
| **A3 尾盘收尾句落点** | ✅ 落在 `closeAdvice()`（14:50）的 `buildCloseContent`，「无触发不发」路径未变 | `TradingSessionPushService.java:707-712` · 早退在 `:701` |
| **本批未误伤其它推送** | ✅ 改动只涉 `content` / `notificationContent` 文本，未动 `type` → Feed/徽章按 `type` 的映射不受影响 | `FeedAppService.java:674-684` |
| **测试实跑绿** | ✅ BUILD SUCCESSFUL（见 §五） | `MarketAlertServiceTest` · `TradingSessionPushServiceTest` |

## 三、问题清单

| # | 级别 | 问题（位置） | 依据 |
|:--|:--|:--|:--|
| 1 | **P2** | 同票「破线 + 当日大跌」合并时，`loss` 正文出现**负距离**「还有 −4.08%」（`MarketAlertService.java:385-389` + `:401-408`） | 本批 B2 新口径「给实际距离」；负距离与之矛盾 |
| 2 | **P2** | 隐私总则「正文永不出现成本金额」**未覆盖批次级止损路径**（正文仍带「该批成本 X」，`:355-361`） | `design-push-copy` §一 原则 4 |
| 3 | **P2** | 新增 `distancePctText()` **0 测试**；B2 的核心行为（实际距离%）未断言；两处既有断言被**弱化** | 派单 ①⑤ · checklist `B47` |
| 4 | P3 | 注释与实现/设计相悖：写「不带规则编号」，实现保留 R66（`:373-375` vs `:378/:384/:388`） | `design-push-copy` §六（R66 保留） |
| 5 | P3 | `loss` 文案「今天跌 **−3.5%**」负号与「跌」重复（`:385`） | 文案红线「诚实、不绕」 |
| 6 | P3 | 尾盘新句在「所有持仓都触发」时多一个**空行**（`TradingSessionPushService.java:706` + `:712`） | 排版一致性 |
| 7 | P3（观察·既有） | §五 决定 4「通知标题＝带标的名」与实现不符——锁屏标题恒为通用「行情提醒」 | `MarketAlertService.java:322` · `PushChannel.java:44-46` |

## 四、逐条详述

### P2-1 · 破线 + 大跌同票合并 → `loss` 正文出现负距离

**路径**：同票同轮既破止损（`md.price() ≤ effectiveStopLoss` → `stop-loss`，`:192-196`）又当日跌 ≥3%（→ `loss`，`:206-208`）→ 两条进 `alerts` → `mergeBySymbol`（`:237` `:275`）按严重度降序拼接 ⇒ `loss` 行紧跟 `stop-loss` 行。

**计算**：`loss` 分支调用 `distancePctText(md.price(), p.effectiveStopLoss(), null)`（`:388`）；分母为**他自己的止损位**，分子 `price − stop`。破线时分子为负。
例：止损 `4.90`、现价 `4.70` ⇒ `(4.70 − 4.90) / 4.90 × 100 = −4.08`（`HALF_UP` 两位）。

**用户实际读到**：

```
京东方A 现价 4.7 在你写的止损 4.9 下方（R66）。要不要按你昨晚定的处理？
京东方A 今天跌 -3.5%，现价 4.7，离你的 4.9 还有 -4.08%（R66）。
```

第二行与第一行**自相矛盾**——已经破了线，却还说「还有 −4.08%」。

**为何没被测出**：`sameSymbolMultiType_mergesIntoSinglePush`（`:236-272`）刻意取 现价 `9.50` / 止损 `9.00`（现价在止损**上方**）⇒ 只验 `loss + break-cost` 合并，**从不覆盖 `stop-loss` 参与的合并**。破线+大跌这条最需要看文案的路径，恰好无断言。

**建议**：`loss` 分支在「现价已在止损下方」时改中性陈述（或干脆省略距离，与 B1 同义）。属**文案取舍** → 建议主链 / 人给一句口径后再改。

### P2-2 · 隐私总则未覆盖批次级止损路径

`design-push-copy` §一 原则 4：「正文**永不**出现数量与成本金额（成本类告警只说『跌过你的成本』，不给数字）」。本批据此把 B4 正文去掉了成本数字（`:391-393`）✓。

但**同一类的批次级止损**（`addLotAlertIfNew`，`:344-367`）两条正文仍带成本数字：`:357`「（该批成本 X）」、`:360`「（该批成本 X，你还没设止损位）」。锁屏版已脱敏（`:366`）✓，**正文没有**。

**性质**：该路径**不在本批 diff 内**（既有行为），但与本批同属「交易推送文案」，且 §一 是「**先于所有文案**」的总则 ⇒ 构成**完备性缺口**。
**建议（二选一，属取值取舍 → 请人拍板）**：① 按总则去掉批次成本数字；② 在文案库显式写下「批次级止损为成本例外的理由」。**不擅自改**（B7）。

### P2-3 · 新增逻辑无测试 · B2 真实距离未断言 · 断言被弱化

1. **新逻辑 0 测试**：`distancePctText()`（`:401-409`）是本批唯一新增逻辑，全仓仅主源码一处引用，无单测。
2. **B2 的核心行为未钉**：`nearStopLoss_within2Percent_createsPush` 只断言 `contains("离你的止损")`（`MarketAlertServiceTest.java:542`），**不校验「还有 X%」的数值**——而「给实际距离而不是给阈值」正是 B2 的全部意义（§六）。
3. **两处断言被弱化**：
   - `:209` 由 `contains("止损位 7.44")` → `contains("7.44")`（任何含 `7.44` 都过，与「止损位」的绑定丢失）；
   - `:170` 由 `contains("单日大跌")` → `contains("今天跌")`（2 字前缀）。
   回归灵敏度下降——正是派单 ⑤ 要防的「为过而改弱」。
4. **边界实况（派单 ①）**：`null price / null stop / stop==0` 三个兜底分支（`:402-403`）在当前调用点**均不可达**（near 分支要求 `price > stop`；loss 分支已保证 `stop != null` 且 `price != null`）⇒ 兜底实为**死代码**；且 `"说不准"`（`:403`）会被拼成「还有 说不准（R66）。」。**真正该处理的边界是负距离（P2-1），而它无测试。**

**建议**：补 2–3 条断言——① 精确距离（如「还有 2.04%」）② 破线/负距离场景 ③ 兜底分支（或删死分支）。新逻辑测试应与功能同批（checklist `B47`）。

### P3-4 · 注释与实现/设计相悖（可能诱导后来者删掉 R66）

`:373-375` 的新注释写「**不带规则编号**（依据在 web 看）」，但实现 `:378` `:384` `:388` 都带「（R66）」，且 §六 与 design-final §11.6 明确**保留** R66。同处还留着旧注释 `:371-372`（「只说『已跌破你的止损位 X（R66）』」）已与现文案不符。
**建议**：删旧句 + 把「不带规则编号」改为「**保留 R66**（依据在 web 看）」——否则后来者照着注释删 R66 会造成口径倒退。

### P3-5 · `loss` 文案负号冗余

`:385`「今天跌 **−3.5%**」——`change` 本身带负号，又冠以「跌」，读作双重否定。建议与 `TradingSessionPushService` 同口径用 `signed(...)` 或取绝对值。

### P3-6 · 尾盘新句前导空行

`TradingSessionPushService.java:706` 的 `sb.append(String.join("\n", blocks)).append("\n")` **已以 `\n` 结尾**；`if (held > blocks.size())`（`:707`）分支的句子无前导换行，而新增句（`:712`）以 `"\n"` 打头。当**所有持仓都触发**（`held == blocks.size()`）时，正文因此多出一个空行。建议把换行统一到拼接处或对末句用 `strip`。

### P3-7 · 决定 4「通知标题带标的名」与实现不符（既有）

`design-push-copy` §五 决定 4 的立论是「通知标题＝带标的名，**锁屏上就能认出是哪只**，不用点进去」。但实测锁屏标题恒为通用 `"行情提醒"`（`MarketAlertService.java:322` 的 `notificationTitle`），`PushChannel.notificationTitle()` 有锁屏标题即用、否则回落完整标题（`PushChannel.java:44-46`）⇒ **锁屏上认不出标的**。站内 Feed 标题为「<名> 行情提醒」，含名（✓）。
属**既有**（`git show e87f926e` 同），§六 明确本批标题不动 ⇒ **非本批引入**；但决定 4 与实现不一致，建议登记（标题与 App 徽章映射同批改，见 §六）。

## 五、验证方式（可复现）

1. **逐行核对 diff**：`git diff e87f926e HEAD -- services/adai-core/src/main/java/com/adaiadai/core/application/MarketAlertService.java .../TradingSessionPushService.java .../test/.../MarketAlertServiceTest.java`（+54 −27）。
2. **测试实跑**：

```
cd services/adai-core
./gradlew --offline test \
  --tests "com.adaiadai.core.application.MarketAlertServiceTest" \
  --tests "com.adaiadai.core.application.TradingSessionPushServiceTest"
# → BUILD SUCCESSFUL（compileTestJava UP-TO-DATE · 两测试类 FROM-CACHE 通过）
```

3. **旧文案残留排查**：`grep -rn "单日大跌\|跌破你的止损位\|距你的止损位" services/adai-core/src apps/adai-app/lib` ——仅剩注释与 `FeedAppServiceTest` 的**合成旧数据样例**（`:287/:307/:319/:451`）+ `FeedAppService.java:680` 的 **type→标题兜底**（仅旧数据用），**无线上文案残留**。
4. **逐字对拍**：`design-push-copy-20261009.md` §三 B 组 / §六 落地记录。

## 六、备注（非缺陷）

- **与设计的既定差距**（§六 已声明「本批不动 / 排下批」，不计问题）：① §三 B 组标题（「破了你的线 · 云南锗业」等）未落地，实现仍是「<名> 行情提醒」（`:320`）；② B3 放飞线未做（缺目标价字段）。
- **纪律**：遵循派单「**不要改 `_index.md`**（主链统一登记）」——故本文件未登记索引，`ai-guard-structure` / `ai-guard-meta` 会各报 1 项 FAIL ＝ 本文件未登记，**同一根因、属派单约束**，留主链收口。
- **未改**任何 `design-*` / `review-*` / 代码 / 契约 / `_index.md`。
