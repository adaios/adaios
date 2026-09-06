---
title: 交易日志竞品调研——TradeZella / TraderSync / Edgewonk 机制基准
description: 为交易⑤认知层（画像 + 建议闭环，RFC 20260905）提供的竞品机制基准——三家产品的纪律量化、情绪采集、AI 画像、拦截机制 + 最值得抄清单 + 来源 URL；调研产物由子代理 2026-09-05 抓取核验
version: 1
created: 2026-09-05
updated: 2026-09-05
status: active
lines: 115
depends-on:
  - ../rfc/20260902-trading-memory-positioning.md
related:
  - ../rfc/20260905-trading-cognition-layer.md
tags: [trading, 竞品调研, 纪律量化, 画像, benchmark]
---

# 交易日志竞品调研基准

> **用途**：RFC 20260905（⑤认知层）§五市场借鉴的完整支撑材料。三家产品的机制级拆解，供实施画像/闭环时随时查阅。
> **证据口径**：TradeZella/Edgewonk 机制来自官方 help/blog 原文整页抓取（机制级可信）；TraderSync 官网反爬，机制来自第三方深度测评原文（已标注）。子代理 2026-09-05 完成。

## 一、TradeZella（官方原文）

### 1.1 Rule Adherence Score（规则遵守分）
不是自动按钮，是**可测量的纪律系统（5 组件）**，每周一个百分比：
- `遵守分 = 本周"规则被遵守"单数 / 总单数 × 100`。<60% 系统问题（规则太复杂→砍到 3 条）、60-75% 渐进、75-85% 强纪律、85%+ 精英。核心：把"破规"折算成美元——守规组 vs 破规组的 win rate / profit factor / P&L 对照。
- 5 组件：①定义 5 条非协商规则（入场/风险/离场/仓位/行为）；②盘前 30 秒 checklist（5 问，任一 No/Emotion 不交易，常驻交易页 0/6 实时进度）；③每笔平仓 5 分钟内打二元标签 Rules Followed/Broken（强制二值诚实，无灰色地带）；④两组标签自动累积独立绩效；⑤识别触发器（时间型/序列型/情绪型），针对单一最差触发器建防线。

### 1.2 Progress Tracker（三阶段规则 + 日循环）
- 规则分 Prepare/Trade/Reflect 三阶段，mandatory + custom 两类。
- 强制项含**系统自动判定**：单笔/单日净亏损超限自动 fail、缺止损 fail、交易未挂 playbook fail、"Start My Day By <时间>"超时 fail。
- 仪式化日循环：Start My Day → Planning → Trading → Import → Trade Review → **Finish My Day 锁定当日不可改**。Dashboard：Today's Score + heat map（越深守规越多）；streak 将断发邮件提醒。
- 关键设计：**自动判定项（亏损超限）与手动诚实项（checklist/标签）分离**——系统裁决 + 自省分开。

### 1.3 Zella AI / AI Trade Analysis（逐笔 5 层自动分析）
每笔导入瞬间即跑（不等周复盘）：①入场质量（实际 vs 策略条件）；②仓位（实际风险 vs 规则，识别 confidence creep/凭感觉下注的模式）；③离场（计划 R vs 实际 R：赢家跑太早/亏单扛过止损/过早移保本）；④情绪模式——**不靠手动标签，从数据签名检测**：亏后短间隔更大仓位再进=revenge、连续快单破规=tilt、无策略匹配大行情追单=FOMO；⑤历史相似匹配（当前单 vs 自己历史相似单的 win rate/PF；50 笔起步、500 笔成个人数据集）。
- AI 入口全局悬浮 chat，**上下文感知**，能执行动作（自动打标/建策略/改规则/记住我）。
- **Zella Insights（规则化洞察引擎）**：输出约 30 种**命名确定性洞察模板**（非自由文本），全基于**个人 60 天基线 percentile/阈值**，每条可当筛选维度。例：`loss streak short gap`（亏后 30 秒再进又亏=revenge）、`overtrading day`（笔数>个人均值 100%）、`overconfidence`（连胜后加仓大亏）、`unusual volume high`（>个人 75% 分位）、`left money on table`。
- **Zella Score**（账户总分）：PF 25% / AvgW/L 20% / MaxDD 20% / Win% 15% / Recovery 10% / Consistency 10%。
- **Zella Scale**（单笔执行分）：潜在（按计划本可）vs 实际 P&L，把执行损耗可视化。
- **Memory**：AI 跨会话记住你的风格/目标/重复错误（"What do you know about me?" 可查可改）。

### 1.4 复盘习惯驱动
Session Review（AI 写当日叙事：rule adherence / playbook fit / 与盘前计划偏离），用户只补"为什么"。官方分工：**AI 打分找模式，人补语境**。

## 二、TraderSync（第三方原文为主）

### 2.1 计划遵守（plan adherence）
- **Strategy Checker**：用户定义规则（仓位上限/最大日亏/盈利目标/可交易时段）→ 系统对**每一笔（历史与未来）自动逐笔检查合规** → compliance %、最常违反规则、守规与盈利相关性。
- **Trading Plan**（Elite）：P&L 图按"符合计划/违反规则"颜色编码；Cypher Coach 对比"实际表现 vs 按最优策略本该有的表现"（守规的机会成本美元化）。
- 双体系标签：setup tags（进场目标）+ error/mistake tags（错在哪：FOMO/没及时砍仓）。合规系统自动算，错误归因用户自省。

### 2.2 情绪状态采集
- 情绪随每笔交易 tags + notes 记录（自由标签体系）；移动端**平仓后立刻记录**（减少事后合理化）；Premium 情绪×结果关联报表。
- ⚠️ 反爬缺口：官方字段级证据（下拉预设/三段式采集）未取得——二手显示是自由标签 + 置信度，非三段式向导。

### 2.3 Cypher / Cypher Coach（AI 分层）
- Cypher = 被动问答分析师（按额度 5/15/60 条/天），基于个人成交记录，识别 revenge/overconfidence/仓位错误，会算"计划偏离花了你多少钱"。
- Cypher AI Insights：对具体单笔自动点评。
- **Cypher Coach（主动）**：不提问也工作，检测到异常主动 ping——拦截靠主动监测。
- 数据门槛：30 笔以下趋通用，50-100+ 笔才显个人化。

### 2.4 行为维度统计
按小时/星期盈亏、sector/volume 报告、MFE/MAE、exit efficiency、hold time、mistake tags 分布、心理学报表、Market Replay（逐 tick 执行回放，250ms + Level II）。

## 三、Edgewonk（官方原文，详见独立报告节选）

### 3.1 核心校准
- **没有 "Psychology Journal / Habits & Rules" 独立模块**。心理采集 = Trade Comments + Custom Stats + 截图 + Notebook + Sessions 组合；纪律 = Setup → Checklist → Trade Comment 三层。
- **没有人格测验**。画像靠 Edge Finder（每周 AI 报告）+ 免费人工 Journal Review + 周期 Report——画像从长期结构化数据长出来。
- **情绪无固定量表**，官方只给示例词表（Fear/Greed/Uncertainty/Stress/Tired/Worry），维度用户自建。

### 3.2 三决策时刻 × 三档评级（最值得抄的地基）
每笔拆成入场 / 持仓管理 / 离场三刻，各打正/负/中性一档 Trade Comment：
- 正 = 按计划做对 / 负 = 违反规则或偏离计划 / 中性 = 拿不准。违反规则/偏离计划→负，直接作为"纪律守了吗"的打分器。
- **Efficiency 纪律率 = 正 ÷ (正+负)**（中性不计分母），官方例 7 正 2 负 = 78%。
- **Tiltmeter**：Journal 表逐笔红/绿纪律列（负评驱动转红），展示纪律 streaks，可叠加权益曲线——"纪律差段 ≈ 账户亏段"一眼可见；把纪律连到钱（dollar cost of tilt）。
- Checklist（2025-05）：Setup 挂核对单（必选/可选），开仓前勾选，按完成度拆胜率/盈亏。
- 低负担：start small（新手先 3 个标签，30-50 笔后加细）；Tiltmeter Challenge 二值自省（只问"这决定会让表变绿还是红"）；一句话教训模板禁写小作文。
- **软肋（差异化机会）**：纪律全靠自我报告，情绪化当下最不诚实（TraderTrac 评测）——让 AI 从成交行为序列反推交叉验证（如自评守纪律但流水显示亏损后马上再进）。

### 3.3 周期收敛件
- **Edge Finder**（2026-01）：每周自动扫整本日志，按六大类输出"最赚/最亏模式+数据点+观察"，固定周报节奏、无需用户提问（刻意不做 AI 聊天："多数人不知道问什么"）。
- 免费人工 Journal Review：解读 ≥50 笔账本给个性化计划，标志性结论"系统本身赚钱，亏在没执行/情绪化"。

## 四、对「AI 认识个人风格 + 出手前拦截」的机制级结论

1. **结构化风格原料**（三家共识）：策略带显式规则、setup/error 双标签、二元遵守标签、情绪 tags——无结构则 AI 只能给通用建议。
2. **个人基线是画像核心**：洞察全用"个人 60 天分位/均值"当标尺，用"个人历史相似单"当对照组——**异常 = 偏离自己常态，非偏离教科书**。最该抄的范式。
3. **情绪/行为不靠自报，靠数据签名**：revenge/tilt/FOMO 从间隔时间、仓位变化、持时推算，自报只互补不兜底。
4. **拦截放在流程闸口**：盘前 checklist 常驻交易页、系统自动 fail 超限单、先立计划再逐笔对账——"下次出手前拦住"靠当日硬闸口，不能靠周报。
5. **用钱说话**：守规 vs 破规的美元差、计划偏离成本、按最优策略本该赚多少——行为改变的杠杆是把纪律差折算成钱。
6. **复盘习惯产品化**：日循环 Start/Finish 锁定 + heat map + streak 断签邮件——AI 写初稿人补情绪，降低启动成本。
7. **主动 AI 与被动 AI 分层**：Cypher（你问的）vs Cypher Coach（主动 ping）——拦截靠主动监测 + 违规成本播报。

## 五、最值得抄的 8 个机制（一句话理由）

1. **Prepare/Trade/Reflect 三阶段 + Start/Finish 日循环锁定**（TZ）——复盘做成有仪式起止、锁档的习惯，画像才有连续数据。
2. **二元守规标签 + 标签级独立绩效**（TZ）——二值诚实 + 自动分组对比美元差。
3. **盘前 30 秒 checklist 常驻界面实时 0/6**（TZ）——出手前最后一道闸。
4. **系统自动判定硬规则**（超限/缺止损自动 fail，TZ+TS）——自省交给系统裁决，用户只诚实打标。
5. **规则化洞察模板 + 个人基线分位**（TZ Zella Insights）——30 种命名洞察用"偏离自己常态"触发，AI 输出确定可解释可沉淀。
6. **逐笔 5 层自动评分 + 导入即反馈**（TZ）——缩短交易→学习回路；情绪用数据签名检测兜底。
7. **主动 AI 监测 + 计划偏离成本播报**（TS Cypher Coach）——拦截应主动 ping 而非等用户问。
8. **情绪随单即时记录 + 情绪×结果关联**（TS + Edgewonk）——采集贴近交易瞬间，情绪列成为画像维度。

## 六、来源 URL（调研抓取核验）

**TradeZella 官方**：tradezella.com/blog/trading-discipline · help.tradezella.com/en/articles/10371695（progress tracker rules）· /10352042（start my day）· /10352075（widget）· tradezella.com/blog/ai-trade-analysis-per-trade-feedback-that-finds-what-you-miss · help.tradezella.com/en/articles/11201153（zella ai）· /12418995（zella insights）· /7020769（strategies）· /7218420（zella scale）· /10305642（zella score）

**TraderSync**：官网反爬仅标题佐证（tradersync.com/features、/creating-a-trading-management-plan、/trading-journal-helps-you-to-track-emotions、/managing-custom-tags）；第三方原文：stockbrokers.com/review/tools/tradersync · tradingjournal.com/review/tradersync · en.tradersdiaries.com/review-diary-tradersync · traderssecondbrain.com/guides/tradersync-review · newyorkcityservers.com/blog/best-trading-journal-apps

**Edgewonk 官方**：edgewonk.com/trading-psychology · /features · /review · /blog/trading-checklists-update · /blog/edgewonk-edge-finder · /blog/mastering-trading-discipline-with-edgewonks-tiltmeter · /blog/what-to-write-in-a-trading-journal · /blog/how-to-build-a-tagging-system-that-actually-improves-your-trading · /blog/the-7-most-important-trading-emotions；帮助中心：edgewonk.zendesk.com/hc/en-us/articles/360010150259（tiltmeter）· /360010061440（trade comments ratings）· /12228256769042（efficiency）· /360010061200（custom statistic）· /360010150219（sessions）

---
*归档：2026-09-05 子代理调研产物（完整报告原稿见会话记录），供 RFC 20260905 实施参考。*
