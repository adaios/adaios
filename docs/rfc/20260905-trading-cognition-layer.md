---
title: 交易⑤认知层落地——个人画像（profile）与建议闭环（advice-loop）
description: 把 RFC 20260902 定义的「⑤认知层（缺口）」落地为两层：A 个人交易画像（客观系统推导 + 主观情绪补全，注入建议/复盘/问答前）与 B 建议闭环（建议留痕→卖出回查→「阿呆当时说X你做了Y结果Z」）；吸收 TradeZella/TraderSync/Edgewonk 的纪律量化机制；试点 8 卡 + 画像 v1 已生成在 data/（隐私）
date: 2026-09-05
status: approved
decided-by: 用户（2026-09-05 拍板：A 画像 + B 闭环两条方向，按 §七 顺序全量执行；同日三官深审修复 + 三项口径拍板后确认转 approved）
tags: [trading, 认知层, 画像, 闭环, 纪律量化, 第五层]
related:
  - ./20260902-trading-memory-positioning.md
  - ../reference/trading-journal-benchmark.md
---

# 交易⑤认知层落地——个人画像与建议闭环

> **本 RFC 实施 RFC 20260902 的「⑤认知层」**：该 RFC 已 approved 并明确「⑤ 是定位重设后真正要长出来的新形态」（①事件②模式③案例④规则均已建成）。用户 2026-09-05 补充两条（A 画像、B 闭环）即第五层的两条腿。本 RFC 只谈实施，不改 20260902 定位。

## 一、目标与验收

**一句话**：让阿呆在建议/复盘/问答前**认识你**（画像），并让每一次建议**可验证、可回忆**（闭环）——输出主语永远是「你」（合规红线，20260902 §四）。

| 层 | 交付 | 验收标准 |
|---|---|---|
| A 画像 | `data/{userId}/trading/profile.md`（客观层自动 + 主观层人工回填） | 建议 prompt 注入画像后，阿呆能说「你上次在 X 就是这样追高，这次你计划怎么防」而非只背 R66 |
| B 闭环 | 建议留痕 + 卖出自动回查 + 复盘「说X做Y得Z」段 | 一笔清仓复盘里出现「2026-08-13 阿呆建议减仓（R66）→ 你 8-17 才割 → 比建议晚 4 天，多亏 N%」 |

## 二、现状核对（2026-09-05 代码/数据实测）

| 面 | 现状 | 缺口 |
|---|---|---|
| knowledge.md | 73KB 系统规则（87 课），经 TradingKnowledgeSource 全量注入 | **不是画像**——AI 满脑规则不认识你 |
| 建议引擎 | `/trading/advice` 返回结构化 DTO（symbol/suggestion/reason/rules），有硬判定覆盖 | **不留痕**：只实时返回；推送文本 3 天过期（expiresAt）|
| 行为标注六类 | TradingLotService 自动判（亏损加仓/追高/短线新开/破止损未走/浮盈回吐/短线超期） | 散在复盘文本，**不聚合、不进画像** |
| 复盘 | 五节模板写 `reviews/YYYY-MM-DD_review.md` | 单向输出，无「当时建议对照」段 |
| 清仓表 sold.json | 163→168 笔，有 psychology 端点（PUT /sold/{symbol}/psychology）但**全空** | 主观情绪零沉淀 |
| 打分 SoldScore | 买点/执行二维实时算 | 不落盘、不关联建议 |

**结构性发现（画像 v1 推导）**：清仓表「首买→末卖/持仓期涨幅」口径**掩盖多段进出**——航天发展实为两轮（+29% 兑现后 5 个月 2.4 倍价追回）、天齐三轮（+11%/-2.3%/≈0）、方正三轮做 T。168 笔的 holdPnlPct 是纸上区间收益，不是实际资金收益。**画像与记忆必须基于逐笔流水，不能信清仓表标签。**

## 三、A 层设计：个人画像（双簿：客观 + 主观）

### 3.1 文件与职责分离

| 文件 | 内容 | 谁写 |
|---|---|---|
| `data/{userId}/trading/knowledge.md`（现有） | 系统规则（87 课）——「阿呆的教科书」 | sync 脚本 |
| `data/{userId}/trading/profile.md`（新增） | **你的画像**——「阿呆眼中的你」 | 客观层系统自动 + 主观层 AI 从用户回答回填 |

**不合并进 knowledge.md 的原因**：73KB 规则已占满注入预算；画像要独立演进、可被 AI 单独引用（「你上次」的证据来自 profile，不来自规则书）。

### 3.2 画像内容（分层，红线①数字只能系统给）

```
# 个人交易画像（v2）
## 客观画像（系统从流水推导，只读生成）
- 纪律遵守率（54%）：扛单 37 笔 @平均-10.6% / 短打 54 笔 @平均-2.5%
- 行为签名频次（六类标注按月聚合）
- 节奏：持仓天数中位 5 天 / 55% ≤5 天
- 多段失真警示：N 笔清仓表标签 ≠ 实际回合收益
- 建议遵守率（B 层接入后）：「阿呆建议 clear 的 12 次里你 3 次照做」
## 主观画像（AI 从用户补全的情绪回填）
- 追高的心理（利欧 8-13「怕买不到」…）
- 扛单的心理（汾酒 479 天「欠它一个交代」…）
- 修正后的行为签名 S1~S6（草案见 profile-v1.md）
## 当前状态
- 持仓 / 现金 / 手动多空判定（market-stage）——与 TradingContextContributor 同源
```

### 3.3 注入位置（已定位，改动面小）

| 位置 | 现状 | 改法 |
|---|---|---|
| `TradingKnowledgeSource.enrich()` | trading/decision 场景注入 knowledge.md | 追加读 profile.md（存在才注入，缓存同知识模式）|
| `TradingAdviceAppService` prompt | 规则 + 行情 | 前置注入「画像·与该票相关的历史」（如「你在 600584 上亏损加仓两次后 -29% 割在黎明前」）|
| `TradingReviewAppService` 复盘 prompt | 当日数据 | 注入「当日操作命中的行为签名 + 历史对照」（A 点：复盘主动点老毛病）|

### 3.4 主观情绪采集（低负担，Edgewonk 借鉴：决策三刻×三档，但我们用对话提问）

- 清仓卡/持仓卡上「补情绪」入口 → **阿呆提问 3~5 问**（试点卡模式）→ 用户说话回答 → AI 结构化回填 profile.md + sold psychology 字段
- 禁给空表让用户填（Edgewonk 校准③：start small、低负担才不弃用）
- 信任模型（红线②）：**行为标注系统判，情绪用户补，AI 两者交叉**——Edgewonk 软肋是「全靠自我报告、情绪化时最不诚实」（TraderTrac 评测），我们让 AI 从成交序列交叉验证（如自评「守纪律」但流水显示亏损后 5 分钟又开仓）

### 3.5 画像 v1 已生成（草案，隐私在 data/）

`data/adai/trading/profile-v1.md` + 8 张试点卡 `data/adai/trading/memory-cards/`——含全量统计、6 条行为签名草案、3 例标签失真警示。等 adai 补情绪后转正并接注入。

## 四、B 层设计：建议闭环（建议→行动→结果）

### 4.1 三件事（都有现成零件）

**① 建议留痕**：advice 响应已是结构化 DTO → 落 `data/{userId}/trading/advice-history/YYYY-MM.json`
```json
{"date":"2026-08-13","symbol":"002131","name":"利欧股份",
 "suggestion":"reduce","reason":"追高 3 倍于抄底…","rules":["R66"],
 "source":"session-push|manual-advice","hardVerdict":true}
```
- 落点：TradingAdviceAppService.generateAdvice() 出口统一落，定时推送逐票建议同格式落
- **不动建议引擎逻辑**，只在出口加一层（已确认 DTO 字段齐）

**② 卖出自动回查**：一笔清仓（sold/sell/导入）发生时，查 advice-history 里该 symbol 最近 N 天建议 → 附到 sold 记录

**③ 复盘「说X做Y得Z」段**：TradingReviewAppService 组装时注入
```
「阿呆当时说」：8-13 建议减仓（R66，追高 3 倍于抄底）
「你做了」：8-17 全仓割肉，晚 4 天
「结果」：比建议多亏约 N%（建议日价 5.75 → 实际割 5.24）
```
- 数字全部代码算（红线①），LLM 只组织语言
- 建议遵守率 → 聚合进画像 A 层（B 反哺 A）

### 4.2 合规对齐（20260902 §四：主语是你）

- 复盘里说「阿呆当时建议减仓、你晚了 4 天」= 讲「你 vs 你自己的历史」，合规
- 建议动作按钮从产品层消失（20260902 已定），B 层只做「记忆与对照」，不新增任何执行建议

## 五、市场借鉴（调研实证：TradeZella × TraderSync × Edgewonk，2026-09-05）

| 竞品机制 | 做法 | 本设计吸收点 |
|---|---|---|
| TradeZella Rule Adherence Score | 每笔平仓打二元「守规/破规」，守规组 vs 破规组独立统计，85%+ = 精英档 | **我们更自动**：六类行为标注系统判，无需用户打标；但可加「遵守率趋势」可视化 |
| TradeZella Progress Tracker | 强制项系统自动 fail（超亏/缺止损/未挂策略），Finish My Day 锁定 | 借鉴「系统自动 fail」：破止损未走已在标注，可升级为每日纪律分 |
| TradeZella Zella AI 5 层 | 逐笔自动评入场/仓位/离场/情绪/历史相似 | 情绪从**数据签名**检测（亏后短间隔加仓=revenge），不靠自报——与本设计红线②同构 |
| TradeZella Zella Insights | 命名规则触发（loss streak short gap / overtrading day）以个人基线分位触发 | 画像引用「偏离你的常态」而非教科书——S1~S6 应配个人基线 |
| TraderSync Strategy Checker | 用户规则对每笔自动逐笔合规检查，P&L 图按守规/违规着色 | 建议遵守率同构（我们查建议 vs 行动）|
| TraderSync Cypher Coach | 主动 AI（不提问也盯，异常主动 ping）| B 闭环成熟后：新操作命中历史老毛病时主动提醒 |
| Edgewonk 三刻×三档 | 入场/持仓/离场各打 ±/0 一档 | 情绪采集形态：决策三刻提问，非空表 |
| Edgewonk Efficiency | 正÷(正+负) 单一纪律健康分 | 「遵守率」做单一可趋势分数 |

**共性结论**：成熟产品都朝「AI 从你的数据认识你 + 系统自动判纪律 + 美元化违规成本」走——本设计已全部对齐，且**判定比竞品更自动**（竞品靠用户打标/自报，我们有逐笔流水 + 行为标注引擎），差异点是护城河。

## 六、数据与文档产物（本次已生成，隐私在 data/ 不进 git）

- `data/adai/trading/memory-cards/`：8 张试点卡（001~008，客观分析完成，情绪 3~5 问待补）
- `data/adai/trading/profile-v1.md`：画像 v1 草案（全量统计 + S1~S6 + 失真警示）
- 生产数据同步至本地：168 清仓 + 1672 逐笔流水（2025-04~2026-09）+ tdx K线
- 调研报告：TradeZella/TraderSync（子代理）/ Edgewonk（子代理）机制要点见 §五

## 七、实施状态（2026-09-05 用户拍板「按实施顺序全量执行」，代码已全量落地）

1. ✅ **P0 建议留痕**（B①）：AdviceEntry/AdviceHistoryRepository/AdviceHistoryFileRepository + advice 出口落盘（含 degraded）+ GET /trading/advice-history（+11 测试：仓储 5 + 出口 2 + controller 3）
2. ✅ **P1 画像接入**（A）：TradingProfileService（客观实时算 + profile.md 读写）+ TradingProfileContributor（trading/decision 注入）+ GET/PUT /trading/profile + 建议引擎 prompt 前置注入画像（+19 测试）
3. ✅ **P1 卖出回查 + 复盘对照**（B②③）：复盘「阿呆当时说 X → 你做了 Y → 规则对照」段（+2 测试）
4. ✅ **P2 主观情绪采集**：TradePsychologyService 确定性提问 + GET /sold/{symbol}/psychology-questions + POST /sold/{symbol}/psychology/answer（+12 测试）
5. ✅ **P2 建议遵守率**（B 反哺 A）：卖前 clear/reduce + 10 天内执行 = 遵守；/profile 带 adviceAdherence + 画像注入（+3 测试）
6. ✅ **远期拦截预留**：建议引擎逐票 symbolHistoryNote 回头草对照（+2 测试）

**测试增量**：1137 → **1188**（净 +51：4 新测试类 34 法 + 既有类新增 17，含审查修复批与同会话首页行情条批 +5——唯一分解口径，change-log/status 同）。端点数 108 → 113。红线三项自查通过（§八）。api-spec v3.47 / change-log / status 已登记。**待用户确认决策后本 RFC draft → approved。**

## 八、红线自查（20260902 §五）

- [x] 数字只能系统给——建议/遵守率/多亏 N% 全部代码算，LLM 只组织语言
- [x] 理解客观推导——行为标注系统判，情绪仅补全不兜底
- [x] 合规——输出主语是「你」，无执行建议

---
*approved：2026-09-05 起草 → 同日用户拍板全量执行 → 三官深审修复出表 → 用户确认转 approved（测试 1189 全绿）。*
