---
title: 交易插件规则层审查（第三阶段 deep 增量深审）
description: 2026-08-30 交易插件规则层实施批审查——backend/frontend/docs/knowledge 四官 + 对抗官隔离并行，未修项归口 REVIEW.md
version: 1
created: 2026-08-30
updated: 2026-08-30
status: active
lines: 116
depends-on:
  - ../../architecture/trading-plugin-architecture.md
related:
  - ../../review/REVIEW.md
tags: [review, trading, plugin, rules]
---

# 交易插件规则层审查（2026-08-30）

> **模式**：deep 增量深审（review.md 规范）——backend/frontend/docs/knowledge 四官 + adversarial 对抗官独立并行隔离审查（官间不互通，多官命中 = ⭐ 交叉印证）。
> **基线**：a4f875b（收盘小结批）→ 工作树（未提交，32 修改 + 6 新增）
> **守护**：G1-G7 7 PASS / 0 HIT · META-GUARD PASS · GUARD-ALIGN PASS

## 审查范围

交易插件第三阶段（trading-plugin-architecture.md）：规则层按用户隔离——TradingRuleSettings（16 参数）/TradingRuleSettingsRepository（rules.yaml）/引擎按用户（止损仓位/行为标注/清仓 verdict/买点/打分权重/建议硬约束）/知识注入用户私有（knowledge.md）/GET-PUT /trading/rules 端点/web 规则 Tab/adai 规则包生成。

## 交叉命中（⭐ 多官独立命中同一问题）

> 隔离审查下多官命中同一问题 = 独立证据价值高，优先修复。

| # | 问题 | 命中官 | 严重度 |
|:-:|:-----|:-----|:-----|
| X1 | **保存链路三处静默**：写盘失败吞异常仍返回 updated=true / 非法值静默修正为默认 / 空提交也 200——用户「以为生效」实际没生效（信任炸弹）| 对抗官 P0-1 · backend P2-6 · frontend P1-1 | ⭐⭐⭐ P0 |
| X2 | **止损判定线分裂**：`TradingLotService.analyzeBehaviors` 用 `effectiveStopLoss(lot)` 无 userId（默认 −7%），异动推送用 `effectiveStopLoss(lot, userId)`（用户规则）——同一用户两条路径判定不一致 | 对抗官 P1-4 · backend P1-2 | ⭐⭐ P1 |
| X3 | **硬约束区间 min>max 空区间静默失效（fail-open）**：`PUT constraintRuleMin=200`（max 保持 95）→ 区间 (200,95) 恒空 → LLM 建议失去全部 R66-R95 硬约束 | 对抗官 P0-2 · backend P1-1 | ⭐⭐ P0 |
| X4 | **知识注入同步断链**：os/ → data/adai/trading/knowledge.md 一次性快照无生成脚本，os/ 课程更新后 adai 知识注入永久滞后；`TradingAdviceAppService` 硬约束仍实时读 os/ rules.md → 同一请求双源分裂 | knowledge P1-2 · backend P1-4 · 对抗官 P1-5 | ⭐⭐⭐ P1 |
| X5 | **并发写无锁 RMW**：PUT /rules 读-改-写无锁，并发编辑丢更新（pitfalls「save 无锁」复发信号）| knowledge P2-1 · backend P2-8 · 对抗官 P1-7 | ⭐⭐⭐ P2 |
| X6 | **NaN/Infinity 校验缺失**：`TradingRuleSettings` double 参数 `<=0` 对 NaN 恒 false 穿透；`1e400`→Infinity 触发 500；前端 double.tryParse 放行 NaN | backend P2-7 · 对抗官 P2 | ⭐⭐ P2 |
| X7 | **多用户知识泄漏（B3 红线）**：非 adai 用户无 knowledge.md → fallback 注入 os/ 五文件（含 adai 身份/规则/current.md 真实持仓）| knowledge P1-1（主命中）+ 对抗官术语泄漏佐证 | ⭐ P1 |

## 未修项（归口 REVIEW.md）

> 按严重度 + 交叉印证排序。P0×2（信任炸弹 + 建议引擎 fail-open）、P1×6、P2×8、P3×6。

### P0（2 项，上线前必须处理）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P0-1 | **保存链路三处静默（X1 ⭐⭐⭐）**：①`TradingRuleSettingsRepository.save()` catch 吞 StorageException → 写盘失败仍返回 updated=true；②构造器非法值静默回落默认（填 0/1000/1.0 用户无感知）；③前端 double.tryParse 全失败 → params={} → 空提交 200 → toast「已更新」实际啥也没改 | `TradingRuleSettingsRepository.save` / `TradingController.updateTradingRules` / `trading_page.dart:1551-1558` | 保存链路 fail-visible：写盘失败 500；非法值拒绝或返回「已修正为 X」字段级提示；空 params 400；前端清空/乱填有反馈 |
| P0-2 | **建议引擎硬约束可被配置掏空（X3 ⭐⭐）**：min>max 时区间恒空 → LLM 无硬约束自由给建议（建议=钱，最危险）；prompt 三处写死 R66-R95（ADVICE_SYSTEM_PROMPT/buildPrompt 标题/OUTPUT_CONTRACT）与用户区间矛盾；LLM 可能编造规则号 | `TradingRuleSettings.java:124-129` / `TradingAdviceAppService` prompt | 区间校验 min≤max 且非空，限制只能在 R66-R95 内收缩；prompt 标题/契约随区间参数化 |

### P1（6 项）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P1-1 | **止损判定线分裂（X2 ⭐⭐）**：行为标注用默认 −7%，推送用用户规则 | `TradingLotService.java:439` | 改 `effectiveStopLoss(lot, userId)`；同步修批次视图 `toView`（:508）|
| P1-2 | **知识注入同步断链（X4 ⭐⭐⭐）**：os/ → data/ 快照无生成脚本；adai 行为机制性改变（快照 vs 实时刷新）；硬约束与知识描述双源分裂 | `data/adai/trading/knowledge.md` / `TradingKnowledgeSource` | 生成脚本入库（os/ 收敛后重新生成）；或方案 C 混合加载；文档明示更新机制 |
| P1-3 | **多用户知识泄漏（X7 ⭐，B3 红线）**：非 adai 用户 fallback 收到 adai 私有知识 + 真实持仓 | `TradingKnowledgeSource.enrich/globalContext` | fallback 收窄：仅 adai（或白名单）回落 os/；其他用户无 knowledge.md 不注入交易知识；补测试（当前零覆盖）|
| P1-4 | **domain 层依赖 infrastructure 具体类（C7 分层红线）** | `DefaultTradingRuleEngine` import infra Repository | domain 定义 `TradingRuleSettingsPort` 接口，infra 实现 |
| P1-5 | **降级语义未定稿**：蓝图 §8.2「客观降级」 vs 实现「默认值=adai 判定」+ Javadoc 自相矛盾（声称存在不存在的「无规则分支」）| 蓝图 §8.2 / `TradingRuleSettings` Javadoc | 产品拍板：无规则用户 = 默认值兜底（现状）还是纯客观降级；同步文档 |
| P1-6 | **前端保存失败无反馈 + 无校验**：catch 空块零反馈；超范围输入被后端静默修正却提示成功；无「恢复默认」按钮；exists 未消费 | `trading_page.dart:1558` | catch 给 toast；前端校验范围 + 后端 fail-visible；编辑弹窗加「恢复默认」；exists 区分「默认 adai 包」vs「已自定义」|

### P2（8 项）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P2-1 | **并发 PUT 无锁 RMW（X5 ⭐⭐⭐）** | `TradingRuleSettingsRepository` | per-user 写锁（对齐 TradeLogRepository 条带锁）|
| P2-2 | **NaN/Infinity（X6 ⭐⭐）**：`1e400`→500、NaN 穿透、params 非 Map→CCE | `TradingController` num()/强转 | Double.isFinite 校验 + params instanceof Map 判断 |
| P2-3 | **每请求读文件无缓存**：evaluatePosition 每持仓读盘 | `DefaultTradingRuleEngine`/`TradingLotService` | per-request 读一次透传或 TTL 缓存 |
| P2-4 | **knowledge.md 每次 enrich 读盘（73KB）无缓存** | `TradingKnowledgeSource.readUserKnowledge` | 时间戳缓存（对齐 os/ 模式）|
| P2-5 | **save() 整文件覆盖丢未来键**：规则层进阶层（rules/signals/behaviors）后置后会被 PUT 抹掉 | `TradingRuleSettingsRepository.save` | 只更新 params 键（读改写保留其它键）|
| P2-6 | **阈值调太松 → 推送/总结轰炸**：买点/行为标注参数可调到天天触发 | `WatchlistBuyPointService`/`TradingLotService` | 激进值确认 + 高频命中去重上限 |
| P2-7 | **evaluatePosition 文案误导**：「超仓位上限 40%（默认 25%）」用户已自定义仍提默认 | `DefaultTradingRuleEngine:75` | 规则生效显示「你的仓位上限 X%」，默认才提默认 |
| P2-8 | **测试依赖真实数据文件**：`AdaiRulePackRealFileTest` 读真实 data/，新环境无规则包则红 | `AdaiRulePackRealFileTest` | 改测试内构造，真实文件验证移入一次性脚本 |

### P3（6 项，记录）

- **P3-1** 死代码常量残留（`TradingLotService` DEFAULT_* 4 个、`TradingAdviceAppService` CONSTRAINT_* 2 个）
- **P3-2** GET 返回 String / PUT 只收 Number 类型不对称（第三方回传 GET 结构静默忽略）
- **P3-3** 模板方法 userId 参数未使用（buildMorning/Midday/CloseTemplate）
- **P3-4** Javadoc 失真（`TradingRuleSettings` 声称「无规则分支」不存在）
- **P3-5** 测试缺口：min>max、Infinity、并发 PUT、PUT 字符串忽略无覆盖
- **P3-6** verdict 规则引用固定 R66/R53，用户自定义阈值后语义错位（前端契约需保，建议规则号参数化）

### 文档类（docs 官独有，归文档批）

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| D-1 | api-spec 头部版本声明 v3.29/08-25 残留（应 v3.33/08-30）| `api-spec.md:5` |
| D-2 | buy-points 契约「无配置入口」与本批实施矛盾（应注明按用户规则）| `api-spec.md:591` |
| D-3 | 蓝图 §10 与 REVIEW.md「后端 842」 vs 事实源 864 漂移 | `trading-plugin-architecture.md` / `REVIEW.md:48` |
| D-4 | 蓝图标题「第二阶段」与正文/全库「第三阶段」矛盾 | `trading-plugin-architecture.md` frontmatter + `_index.md:33` |
| D-5 | 规则文件名混用（rules.md/settings.json/settings.yaml vs 定案 rules.yaml）| 蓝图 §6.1/§7.2/§9.1 |
| D-6 | 决策点未逐项记录拍板结果，D11 去向不明 | 蓝图 §11 |
| D-7 | feature-reference §9 仍称「五 Tab」（应六 Tab）| `feature-reference.md:745` |
| D-8 | trading-features.md 未同步（蓝图 §10.4 承诺的第 9 个文件）| `trading-features.md:147` |
| D-9 | 蓝图 §2.1「36 端点」与实际 40/42 不符 | `trading-plugin-architecture.md:73` |
| D-10 | REVIEW.md frontmatter last-review 未更新 | `REVIEW.md:3-4` |
| D-11 | change-log 测试数明细算术（16 vs 明细 22）| `change-log.md:8` |
| D-12 | api-spec 变更记录 v3.21 行序倒错（旧批遗留）| `api-spec.md:45` |
| D-13 | freeze §2.16「JSON Schema 可校验」超前实现 | `data-format-freeze.md:396` |
| D-14 | os/current.md 持仓入库未脱敏（既有，本批固化）| `os/trading-engine/knowledge/context/current.md` |

## 正面确认（通过项）

- **核心设计正确**：参数化 record + fail-closed 校验 + SafeConstructor YAML + 默认值兜底 + 测试配套（backend 官核对：构造器参数顺序三处一致、YAML round-trip 自洽、SnakeYAML 安全、路径无新增暴露、降级回归通过）
- **数据资产合格**：rules.yaml 16 参数与课程默认全量一致、knowledge.md 五文件逐字节合并、均被 gitignore 保护（knowledge 官）
- **契约层对齐**：api-spec v3.33 + freeze §2.16 与实现逐字段一致、双 guard PASS（docs 官）
- **前端主流程合格**：加载→展示→编辑→保存→刷新→toast 链路完整、mounted 守卫到位（frontend 官）

## 结论

**核心设计方向正确、大部分实现自洽、数据资产合格**，但存在 **2 个 P0（保存链路静默信任炸弹 + 建议引擎硬约束可被掏空）** 与 **6 个 P1**，其中 3 处多官交叉印证（X1/X2/X3/X4/X5）。**建议修复 P0-1/P0-2/P1-1/P1-2/P1-3 + 文档 D-1~D-4 后合批**；P1-5 需用户拍板降级语义；P2 可随修复批或下一批；P3 顺带。

**待用户拍板**：
1. **降级语义（P1-5）**：无规则用户 = 默认值兜底（现状，简单）还是纯客观降级（需加无规则分支，工作量大）？
2. **修复范围**：先修 P0×2 + P1 关键项 + 文档漂移，还是全量修？
