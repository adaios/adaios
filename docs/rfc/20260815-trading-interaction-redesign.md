---
title: 交易模块交互重设计（app 说人话 / web 详细管理）
date: 2026-08-15
status: superseded
supersededBy: 20260902-trading-memory-positioning.md
---

> **定位条款已取代（2026-09-02）**：本文件「核心定位：不是记账工具，是建议引擎——输出买卖/持仓建议是目的」已被 [`20260902-trading-memory-positioning.md`](20260902-trading-memory-positioning.md)（交易记忆定位）取代。交互重设计/双端分工等其余内容仍有效。

# 交易模块交互重设计 — 记录真实交易，结合 trading domain 给买卖/持仓建议

> 类型：UI Flow（复杂交互方案）· 状态：draft · 日期：2026-08-15
> 范围：adai-app（手机）记录/持仓/建议交互重设计 + adai-web 管理增强 + adai-core 配套端点
> **核心定位（用户确认 2026-08-15）：不是模拟交易/记账工具，是「我的交易数据 ↔ trading domain 规则体系（os/trading-os R1-R120）→ 买卖/持仓建议」。记录是手段，建议是目的。**
> 依据代码现状核对：`apps/adai-app/lib/pages/trading_page.dart`（499 行）、`apps/adai-app/lib/services/api_service.dart`、`apps/adai-web/lib/pages/trading_page.dart`、`docs/architecture/api-spec.md` §5、`services/adai-core` TradingController / TradingAppService / TradingReviewAppService、`docs/architecture/data-format-freeze.md` §2.6/§2.12

---

## 0. 核心目的（用户确认，本方案第一原则）

> **交易模块 = 建议引擎，不是记账工具。** 用户明确：不是模拟交易，是要和 **trading domain** 结合起来——对我的**买、卖、持仓提供建议**。记录真实交易是手段（喂数据），建议是目的（输出价值）。

**建议类型（用户点破的）**：
- **买入建议**：我记录/想买某标的 → 阿呆按规则给"能不能买/多少仓位"建议
- **卖出建议**：持仓触发规则 → 阿呆给"减仓/清仓"建议（R66 只输一根K线 / R71 压力测试 / R68 止损位）
- **持仓建议**：阿呆看我的持仓结构 → 按 R81-R95 仓位规则给配置建议（如"京东方 30% 超 R81 单仓上限，建议减到 20%"）

**建议来源**：`os/trading-os/11-context/rules.md`（R1-R120 择时/选股/买入/应对/止损/仓位/纪律）+ `strategy.md`（v87 体系）+ 真实持仓/交易数据 + 行情。规则是**决策约束**，AI 分析时自动匹配相关规则（rules.md 已声明此用法）。

**输出形态（无第三视角）**：阿呆用自然对话给建议（"京东方已跌破你的止损线，按 R66 建议清仓"），不是指令、不是操作按钮。**不做"平仓/减仓"执行按钮**（用户不需要 app 执行交易，建议是输出）。

## 0.5 现状核对结论（设计依据，先读这个）

| 现状 | 问题 | 本方案 |
|:-----|:-----|:-------|
| 记录表单 5 字段（代码/名称/方向/单价/数量）`trading_page.dart` L176-218 | 用户嫌繁琐；代码+名称本质是同一个"标的" | 3 字段 + 方向由按钮承担；主入口升级为"一句话 + 确认" |
| 持仓 6 列窄表 L300-350 | 手机每列 ~50px 看不清，盈亏淹没在中间 | 每标的一张资产卡，盈亏大字为主角 |
| 价格校验仅 `volume <= 0`（L80），无 `price <= 0` | 可录 0/负价 | 前端 V4 规则 + 后端 `@Positive` 双保险（后端已有） |
| 后端 `TradeRequest` `@NotBlank name`（TradingController L331-337） | **web 标注"名称（可选）"却后端必填**——契约与 UI 不一致 | name 改可空（P0），缺失时补全/兜底 |
| `POST /records` 能理解"买了 1000 股京东方 @5.2"（AI 判 domain=trading），但**只入记录，不更新持仓** | 自然语言入口与交易系统是两条道 | 交易页新增 parse 端点把"一句话"结构化成 `POST /trading/trades` 的入参，复用同一写入链路 |
| `hasTradingActivity` 基于**记录关键词**（买/卖/仓/股…，TradingReviewAppService L136-147），交易页录入走 `/trading/trades` 不产生记录 | **交易页录的交易触发不了复盘提醒**（真实缺口） | `recordTrade` 同步写一条 domain=trading 记录（P0），闭环复盘提醒 |
| `TradeRecord` 实体是纯内存模型，交易历史未落盘 | web"历史明细"无数据源 | 新增交易历史落盘 + 查询端点（P1） |
| SELL 后端已支持：未持有报错、超卖报错、清仓自动移除 0 行（TradingAppService L59-83） | 前端不提供交易执行 | 持仓卡展示阿呆减仓/清仓**建议**（规则匹配，P0） |
| 复盘：右上角小图标 → POST /review → dialog → promote（L400-498） | 入口隐蔽、无主动提醒、无历史列表 | 横幅主动触发（has-activity）+ 保留手动入口 + 历史列表（P1） |

---

## 1. 交互流程总览（主流程图）

```
                    ┌──────────────────────────────────┐
                    │       交易页（adai-app 手机）       │
                    └──────────────────────────────────┘
 ┌──────────────┐  ┌──────────────────┐  ┌─────────────────────────┐
 │ ① 快照卡      │  │ ② 记录交易输入条    │  │ ③ 持仓区（资产卡+阿呆建议） │
 │ 总市值 总盈亏 现金│  │ 「买了1000股京东方    │  │ ▸ 京东方A  +260  +5.0%  │
 │（红涨绿跌）    │  │  @5.2」 ▸ 解析      │  │    阿呆：仓位超限，建议减仓 │
 └──────────────┘  └─────────┬────────┘  │ ▸ 贵州茅台  -80  -0.6%  │
                             │ ① POST /trading/      │    阿呆：跌破止损位，建议清仓│
                             │   trades/parse        └───────────┬─────────────┘
                             ▼                                  │ 点按卡片
                     ┌────────────────┐                         ▼
                     │ 确认卡片（回显） │              ┌──────────────────────┐
                     │ 买入 京东方A    │              │ 阿呆持仓建议（规则匹配）  │
                     │ (000725)       │              │ 「京东方仓位30%超R81   │
                     │ [数量][价格]     │              │  且跌破止损位(R66)，    │
                     │  确认 | 取消    │              │  建议减仓至20%」        │
                     └───────┬────────┘              │ ▸ 查看该股建议依据       │
                             │ POST /trading/trades  │ ▸ 管理持仓（去 web）     │
                             ▼                       └──────────────────────┘
                     ┌─────────────────────────────────────────────┐
                     │ 后端 TradingAppService.recordTrade            │
                     │  读-改-写(每用户锁) → positions.md → 返回持仓    │
                     │  ＋同步写 domain=trading 记录（P0 新增，建议闭环）│
                     └─────────────────────────────────────────────┘
                             │ 成功 SnackBar ＋ 持仓卡即时刷新（无整页 loading）
                             ▼
                     ┌─────────────────────────────────────────────┐
                     │ ④ 建议/复盘区（结合 trading domain 规则）        │
                     │  「阿呆说说」：今日持仓 × 规则匹配 → 减仓/清仓建议   │
                     │  「今日有交易 · 生成复盘？」──▸ dialog → 反哺入库   │
                     └─────────────────────────────────────────────┘
```

**记录交易子流程（② 的两种通道，共用同一确认/写入链路）**：

```
一句话输入条（默认主入口）
   │ 输一句 → 点「解析」
   │ POST /trading/trades/parse（LLM 结构化 + 正则兜底）
   ▼
确认卡片：NL 结果回显为可编辑的 3 字段（数量/价格 + 方向切换）＋名称代码只读行
   │ 解析失败 / 用户想精确输入
   ▼
「精确填写」折叠表单（标的 | 价格 | 数量 ＋ 底部 [买入][卖出] 双按钮）
   │
   ▼ 确认/提交
POST /trading/trades（现有端点，后端 @Positive 强校验 + 写持仓）
```

> **核心取舍**：不设"纯 NL 直通入库"（AI 解析错会静默成交，违背正确性要求）；NL 只负责"少打字"，正确性由**结构化回显 + 用户确认**这关兜底——这是券商下单确认页的行业惯例，也是"最少参数 + 校验正确性"两要求的交点。NL 与表单不是两个功能，是**同一个确认卡片的两条填法**：NL 自动填、表单手动填，任何一条失败都落到另一条，没有死路。

---

## 2. 极简记录方案（字段级设计）

### 通道 A：一句话输入（默认主入口）

**用户看到什么**：持仓区标题上方一条圆角输入条（替换现有「+ 记录交易」折叠开关 `_buildAddButton` L160-174），placeholder：`说一句，比如：买了 1000 股京东方 @5.2`，右侧「解析」按钮。

**用户怎么操作**：输入一句话 → 点「解析」→ 调新端点 `POST /api/v1/trading/trades/parse`（P0 后端，LLM 结构化 + 正则兜底，返回 `matched: true/false` + `{symbol, name, direction, price, volume}`）→ 弹确认卡片。

**确认卡片（关键设计：AI 错误在此拦截）**：
- 主行：方向徽标（买入=红底 / 卖出=绿底，A股红涨绿跌）+ `京东方A (000725)`（名称+代码只读回显，来自后端结构化结果，代码→名称由后端补全）
- 次行：两个可编辑数字输入 `[数量 1000]` `[价格 5.20]`（预填 NL 解析值，可改）+ 方向小切换（买入/卖出）
- 底部：「确认记录」主按钮 +「取消」

**用户得到什么反馈**：
- 解析失败（matched=false）：SnackBar「没听懂这句交易，试试：买了 X 股 名称 @价格」+ 自动展开精确表单（V10）
- 确认成功：SnackBar「已买入 京东方A 1000 股 @5.20」（绿字）→ 持仓卡用返回的 positions 即时刷新 → 复盘横幅出现（§6）
- 校验失败：见 §3 规则表逐条提示

### 通道 B：精确填写（折叠表单）

**触发**：输入条下方「精确填写」链接；或 NL 解析失败自动展开。

**字段（3 个输入，5 字段 → 3 字段的减法逻辑）**：

| # | 字段 | 输入方式 | 默认值 | 说明 |
|:-:|:-----|:---------|:-------|:-----|
| 1 | 标的 | 文本输入（一个框替代原「代码」+「名称」两框） | 空 | 可输 6 位代码或中文名称；只填代码时名称由后端补全（P0 name 可空），回显在后端返回里 |
| 2 | 价格 | 数字键盘 | 空（必填） | 成交单价 |
| 3 | 数量 | 数字键盘 | 空（必填） | 股数（整数） |
| — | **方向 = 提交按钮** | 底部两个等宽大按钮「买入」「卖出」 | 无（点哪个算哪个） | **方向不是字段而是按钮行为**：结构上不可能漏选；SELL 时前端用持仓预校验（V6） |

**删除的 2 个字段去哪了**：名称 → 后端补全/代码兜底（可推导）；方向 → 按钮承担（可合并）。时间戳后端取当天，不展示。

**为什么不是 2 字段**：价格+数量是交易的最小业务参数，缺一不可；合并成"金额"会丢均价（摊平成本靠单价），违背正确性。

### 默认值/预填（极简 + 防误录平衡）
- 价格：无默认（防误录）
- 数量：无默认
- 方向：无默认；由按钮决定
- 辅助联想（标的下拉）本版不做 → P2，见不做清单

---

## 3. 校验规则表

> 前端即时校验（友好文案，`_submitTrade` 校验段 L80 扩展）+ 后端契约校验（兜底，已有 `@Positive`）。**前端负责"人话提示"，后端负责"铁律"。**

| # | 字段 | 规则 | 失败反馈（用户看到什么） | 后端现状 |
|:-:|:-----|:-----|:------------------------|:---------|
| V1 | 标的 | 非空；格式：6 位数字 或 2-6 个汉字/字母（A股/港股/美股宽松） | 空：「请输入股票代码或名称」；格式错：「标的格式不对：6 位代码（如 600519）或股票名称」 | `@NotBlank symbol`（已有） |
| V2 | 名称 | 可空（本版放宽松）；缺失时后端补全/以代码兜底 | 无需前端校验；确认卡片回显后端补全的名称 | **现 `@NotBlank name` 收紧过度**（web 标"可选"却必填）→ P0 改可空 `@Size(max=32)` |
| V3 | 方向 | 由「买入/卖出」按钮决定 | 无（结构上不可能漏选） | `TradeDirection` 枚举（已有） |
| V4 | 价格 | 必填；数字；**>0**；≤1e7 | 空：「请输入成交价格」；非数字：「价格请输入数字」；**≤0：「价格必须大于 0」**；过大：「价格过大，请检查是否多打了 0」 | `@Positive price`（已有，后端 400 → 前端人话） |
| V5 | 数量 | 必填；整数；>0；≤1e7 | 空：「请输入数量」；非整数：「数量需为整数（股）」；≤0：「数量必须大于 0」 | `@Positive int volume`（已有） |
| V6 | 卖出 ≤ 持仓（前端预检） | SELL 数量 ≤ 该标的持仓；未持有不可卖 | 「卖出 2000 股超过持仓 1000 股」；「未持有 000725，无法卖出」 | `TradingException`（已有 #147：超卖/未持有明确报错） |
| V7 | 卖出校验 | SELL 数量 > 持仓量 → 拦截（后端已有） | 「超过持仓量」前端预检 | 0 行自动移除（已有 L83/L122） |
| V8 | 提交防重 | `_submitting` 置灰按钮 | 按钮变「提交中...」（现状已有） | — |
| V9 | 插件门控 | 未启用 trading 插件不显示交易页 | 模块整体隐藏（getMyPlugins 已有显隐） | 403（已有 L88-93） |
| V10 | NL 解析失败 | parse 返回 matched=false | 「没听懂这句交易，试试：买了 X 股 名称 @价格」+ 自动展开精确表单 | 新端点（P0） |

**改动落点**：app `_submitTrade` L80 校验段；web `_TradeDialogState._submit` L376-387 同步扩展（现只有 symbol 非空 + volume>0）。

---

## 4. 持仓展示方案（手机）

**结论：6 列窄表 → 每标的一张「资产卡」**。手机上 6 列每列约 50px、字号 12 挤成一团；用户"一眼"要看的是**名称 + 盈亏**，其余是次要。卡片把盈亏做成视觉主角。

### 卡片布局（替换 `_buildPositionTable` L300-350）

```
┌───────────────────────────────────────────────┐
│ 京东方A        000725          +260.00        │ ← 行1：名称(13-14 粗体) 代码(灰 11)
│ 1000股 · 成本 5.20 · 现价 5.46     +5.0%      │ ← 行2：数量·成本·现价(灰 11)  盈亏%(同色)
└───────────────────────────────────────────────┘
```

- 每卡：`Container` 圆角 10、`darkSurface2` 底、卡间距 8——与 `_buildSnapshotCard`（L270）风格统一
- **盈亏金额**：行1 右侧，18-20 大字号加粗，`_fmtMoney` 万单位
- **盈亏%**：行2 右侧，同色
- 颜色：`#132 红涨绿跌`——盈利 `darkRed`、亏损 `darkGreen`、|pnl|≤0.01 持平 `darkGrey3`
- 成本/现价/数量：灰色 11px 次要信息（用户需要时看得到，不抢主角）

### 每卡交互：点按 → 操作 BottomSheet

新方法 `_showPositionSheet(PositionItem)`：
- 头部：`京东方A (000725) · 持有 1000 股 · 成本 5.20 · 现价 5.46`
- 动作项：
  - **「减仓」** → 弹出极简卖出表单（数量[预填全部持仓] + 价格[预填现价] + 确认）→ POST /trading/trades (SELL)
  - **「阿呆建议」** → 展示该股规则匹配建议（止损位/仓位/压力测试，R66/R71/R81），附依据
  - **「管理持仓（电脑端）」** → §5 的 web 引导
  - 取消

### 状态视觉

| 状态 | 呈现 |
|:-----|:-----|
| 盈利 | 盈亏金额 + % 红色（darkRed） |
| 亏损 | 盈亏金额 + % 绿色（darkGreen） |
| 持平 | 灰色（darkGrey3） |
| 空仓 | 空态卡：「暂无持仓」+ 引导 ① 上方输入条记录第一笔 ②「有历史持仓？到电脑端导入」（链接 → §5 引导） |
| 加载/错误 | 保留 `_loading` / `_buildError`（L376） |
| 今日有交易 | 持仓区上方复盘横幅（§6），不占卡片位置 |
| 多持仓 | 卡列表可滚动，顶部小字「共 N 只」 |

快照卡（L270-298）保留 3 项（总市值/总盈亏/现金）；P2 可加第 4 项「持仓 N」。

**web 端不变**：DataTable 8 列保留（桌面宽屏合适），只加管理能力（§5）。

---

## 5. app / web 分工表

| 功能 | 归属 | 说明 |
|:-----|:-----|:-----|
| 一句话记录交易（NL） | **app 独有** | 手机打字场景；web 用表单即可 |
| 记录交易（精确表单） | 两端 | app=3 字段双按钮；web=现有 Dialog 修校验 + name 可空（对齐"可选"标注） |
| 看总资产 / 总盈亏 / 现金 | 两端 | app 快照卡 / web stat 卡（已具备） |
| 看单只盈亏 | 两端 | app 资产卡 / web 表格（已具备） |
| 减仓/清仓**建议** | app 展示（规则匹配）；web 执行记录（P1） | app 给建议、web 管数据，分工清晰 |
| 持仓全字段明细 | **web** | DataTable 8 列（已具备） |
| 持仓数据导入（CSV / 粘贴） | **web 独有** | 批量建仓/初始化，P1 后端 batch 端点 |
| 持仓编辑（改成本/数量/名称） | **web 独有** | 修正手误，P1 后端 PUT positions |
| 历史交易明细 + 筛选 | **web 独有** | 按日期/标的/方向，P1 后端 trades 查询 |
| 历史复盘列表 | 两端（app P1 仅列日期+查看） | `GET /trading/reviews` 已具备 |
| K线 / 走势 | **web（P2）** | app 不做 |
| 规则冲突检测 | **web（P2）** | `GET /trading/knowledge/conflicts` 已具备 |
| 复盘生成 / 反哺入库 | 两端 | 现有流程保留 |
| 复盘提醒 | 两端 | app 横幅 + 简报已有提醒（BriefAppService L236） |

### app 引导「详细管理去 web」的具体设计（3 处）

1. **空态卡**：「有历史持仓？→ 到电脑端导入」（可点）
2. **持仓区标题栏**（「持仓明细」右侧）加小图标 `Icons.open_in_new` + 文案「管理」→ 点按弹 BottomSheet：「详细管理（批量导入、持仓编辑、历史明细、K线）请在电脑端打开交易页」；`kIsWeb` 时额外显示「打开桌面版」跳转按钮
3. **持仓卡 BottomSheet** 内的「管理持仓（电脑端）」条目

统一文案：「详细管理去电脑端」。本版不做深链接（P2 可加 URL scheme）。

---

## 6. 复盘交互

### 现状问题
- 入口是右上角小图标（L123-130），不易发现
- **`hasTradingActivity` 基于记录关键词（买/卖/仓/股…，TradingReviewAppService L136-147）——交易页录交易走 `/trading/trades` 不产生记录 → 复盘提醒对交易页录入失效（真实缺口）**
- 无历史复盘入口（`getReviewDates` L369 已有端点但前端没用）

### 设计

**触发（双通道）**：
- **a) 主动（本版核心）**：交易页加载时调 `GET /trading/has-activity`（已有端点）→ true 则快照卡下方出现横幅卡：「📊 今日有交易 · 生成今日复盘？」+「生成复盘」按钮。**录完一笔交易后刷新，横幅即时出现**——"交易完回来就看到"，即轻量主动推送，无需市场时段轮询基建。
- **b) 被动**：保留右上角图标；新增「历史复盘」列表（`getReviewDates` + `getReview` 已有端点，P1 UI）。

**生成**：点「生成复盘」→ `POST /trading/review`（现有 generateReview，走 ContextEngine 注入规则/行情）→ dialog 展示 markdown（复用 `_buildReviewDialog` L420-477）。

**展示**：dialog 不变；成功后横幅区变「今日复盘已生成 ✓」，可再点开。

**反哺**：dialog 内「反哺入库」按钮（现有 promote，L480-498）→ 成功 SnackBar 显示 #178 提示（已有）；横幅标记「已入库」（本地状态，P2）。

**后端闭环修复（P0）**：`TradingAppService.recordTrade` 成功时同步写一条 domain=trading 的 `ContentRecord`（标题如「买入 京东方A 1000股@5.20」），使 `hasTradingActivity` 命中 + 交易进 timeline/记忆（符合"入口统一，后台分流"理念）。备选方案：has-activity 改查交易历史文件——**推荐前者**（交易可被 AI 语境引用，价值更高）。

---

## 7. 改动点清单

### 后端 services/adai-core

| 优先级 | 改动 | 文件 : 方法 |
|:------:|:-----|:-----------|
| **P0** | 新增 `POST /api/v1/trading/trades/parse`：一句话 → `{symbol, name, direction, price, volume, matched}`；LLM 结构化（复用 DeepSeekAiClient）+ 正则兜底；matched=false 前端转表单 | `interfaces/TradingController` 新方法 + 新 `application/TradingParseAppService` + 测试 |
| **P0** | `TradeRequest.name` 改可空（`@Size(max=32)`），买入缺名时以代码兜底/补全 | `TradingController.TradeRequest`（L331-337） |
| **P0** | `recordTrade` 成功时同步写 domain=trading 的 ContentRecord（复盘提醒 + 时间线闭环） | `application/TradingAppService.recordTrade`（L48-92）注入 `RecordRepository` + 测试 |
| **P1** | 交易历史落盘 + `GET /api/v1/trading/trades?from&to&symbol&direction`（web 历史明细） | 新 `infrastructure/storage/TradingHistoryFileRepository`（`data/trading/trades/{yyyy-MM}.json`）+ Controller + AppService + 测试 |
| **P1** | 批量导入 `POST /api/v1/trading/trades/batch`（逐条成功/失败结果） | `TradingController` + `TradingAppService.batchRecordTrade` + 测试 |
| **P1** | 持仓修正 `PUT /api/v1/trading/positions/{symbol}`（quantity/avgCost/name） | `TradingController` + `TradingAppService.correctPosition` + 测试 |
| **P0** | 持仓建议端点 `POST /api/v1/trading/advice`：输入持仓+行情 → 匹配 rules.md（R66/R68/R71/R81-R95）→ 输出逐票建议（买入/减仓/清仓/持有 + 依据规则号）| 新 `application/TradingAdviceAppService`（读 os/trading-os/11-context/rules.md + 持仓 + 行情，LLM 结构化） + Controller + 测试 |
| **P2** | 标的联想 `GET /api/v1/trading/stocks/suggest?q=` | 新 SuggestService（本地静态表/LLM） |

> api-spec.md §5 随 P0 同步（parse / batch / positions PUT / trades GET + TradeRequest name 可空 + has-activity 语义补注）。

### 前端 apps/adai-app

| 优先级 | 改动 | 文件 : 方法 |
|:------:|:-----|:-----------|
| **P0** | 记录区重构：NL 输入条 + 确认卡片 + 精确表单（3 字段双按钮） | `trading_page.dart`：`_buildAddButton`(L160)/`_buildTradeForm`(L176) → `_buildQuickRecord`/`_buildConfirmCard`/`_buildExactForm`；`_submitTrade`(L75) 拆 `_parseTrade`/`_confirmTrade` |
| **P0** | 持仓表 → 资产卡 + 点按看「阿呆持仓建议」（规则匹配）+ 去 web | `_buildPositionTable`(L300) → `_buildPositionCards` + 新 `_showAdviceSheet`（调建议端点）|
| **P0** | 校验扩展（V4 价格>0 / V5 / V6 SELL 预检 / V10） | `_submitTrade` 校验段（L80） |
| **P0** | ApiService 新增 `parseTrade`（+ P1 `batchImport`/`getTradeHistory`/`updatePosition`） | `api_service.dart` 在 `recordTrade`(L322) 旁 |
| **P1** | 复盘横幅（has-activity 检测 + 生成） | `initState`/`_loadData` 加 `_checkActivity`；新 `_buildReviewBanner` |
| **P1** | 历史复盘列表入口 | 复用 `getReviewDates`(L369)/`getReview`(L357) |
| **P1** | 「详细管理去 web」引导（空态卡/标题栏/卡片 Sheet） | 新 `_showWebGuide` |
| **P2** | 快照卡加持仓数；swipe 快捷减仓 | `_buildSnapshotCard`(L270) |

### 前端 apps/adai-web

| 优先级 | 改动 | 文件 : 方法 |
|:------:|:-----|:-----------|
| **P1** | 导入面板（CSV / 粘贴）→ batch 端点 | `trading_page.dart` 新 `_buildImportPanel` |
| **P1** | 持仓行内编辑 → PUT positions | DataTable 行加编辑（L204-235） |
| **P1** | 历史交易明细面板 → GET trades | `trading_page.dart` 新增 |
| **P1** | 历史复盘列表 | 复用 `getReviewDates` |
| **P1** | Dialog 校验补 V4（价格>0），name 改选填对齐标注 | `_TradeDialogState._submit`(L376-387) |
| **P2** | 行内卖出按钮；冲突检测展示 | 复用 conflicts 端点 |

### admin apps/adai-admin
P2 可选：data 管理页已可浏览持仓/复盘文件，本版不加交易专属页。

---

## 8. 不做清单（明确归 web / 明确延后）

| 不做项 | 归属/原因 |
|:-------|:----------|
| K线 / 分时图 | app 不做；web P2（行情卡已有基础） |
| app 批量导入 / CSV 粘贴 | 归 web 独有（app 只引导） |
| app 历史明细 / 复杂筛选 | 归 web 独有 |
| 复盘定时推送（市场时段轮询） | 本版用"交易后横幅"轻推送替代，不建推送基建 |
| 纯 NL 直通入库（无确认步） | 正确性优先，明确不做——AI 解析错误必须被确认步拦截 |
| 融资融券 / 期权 / 港股美股夜盘等复杂交易类型 | 只支持普通 BUY/SELL |
| 手续费 / 税费计算、券商数据自动对账 | 超出本版范围 |
| 标的联想下拉（suggest） | P2（本版为自由文本 + 格式校验） |
| 多账户切换 UI | 账号层已预留，本版不涉及 |
| 复盘自动反哺（无确认自动入库） | 保留人工确认（promote 现有语义，尊重 os/ 独立性） |
