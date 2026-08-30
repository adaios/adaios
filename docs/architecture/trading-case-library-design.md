---
title: 完美买点案例库设计方案——案例沉淀与判定当下（trading 插件第四阶段）
description: 规划 RFC 20260830 的可落地设计——案例数据模型、特征提取算法、归一化相似度引擎、API 契约草案、K 线图画图方案、判定接入与降级；分四期实施，P1 最小闭环先行
version: 1
created: 2026-08-30
status: draft
depends-on:
  - 20260830-trading-perfect-case-library.md
related:
  - trading-plugin-architecture.md
  - api-spec.md
  - data-format-freeze.md
  - ../reference/trading-features.md
tags: [trading, 案例库, 设计, 第四阶段]
---

# 完美买点案例库设计方案

> **定位**：规划 RFC `20260830-trading-perfect-case-library.md`（方向/决策）的**详细设计**——落到可实施的类、算法、契约、文件格式。设计原则：**案例是手段，判定是价值**；与第三阶段规则层正交叠加，不推翻任何现有能力。
>
> **状态**：draft（2026-08-30 用户拍板方向后撰写，待评审开工）

---

## 一、范围与目标

### 1.1 设计目标

1. **案例标注闭环**（环 1-2）：用户一句话标注 → 自动拉前 60 + 后 30 日 K → 特征画像 → 案例落盘 → web 端 K 线图还原画面
2. **案例理解**（环 3）：LLM 消化案例 → 结构化洞察 → 归纳共同特征，对照修正 B1/B2 参数
3. **判定当下**（环 4）：新买点形态与案例库归一化相似度匹配，输出参照信号——核心价值
4. **双轨判定**：规则引擎（确定性基线）与案例相似度（经验增强）独立判定、独立降级

### 1.2 非目标（本期不做）

- 分时/分钟级别案例（D1 用户拍板：日线）
- 案例自动发现（不从历史数据自动挖案例，只消化用户标注的）
- **全市场扫描（D4 用户拍板 2026-08-30：不要全市场）**——判定当下每日自动只覆盖**自选股 + 持仓**（复用 buy-points 扫描范围），手动按需可查任意代码；全市场选股是另一条线（公司透视等方向），不与案例库混
- 规则引擎改造（相似度不覆盖止损/仓位等硬判定）
- 跨用户案例共享/市场（多用户后置）
- 后端生成图片文件（K 线图由前端 CustomPaint 渲染，见 §七）

### 1.3 用户拍板基线（2026-08-30）

| # | 决策 | 值 |
|:-:|:-----|:-----|
| D1 | 案例级别 | 日线（`KlineSource` 现有能力直接可用）|
| D2 | 自定义指标 | 黄白线/洗盘短线/转型图——第一版语义近似，公式源码后补精确化 |
| D3 | 数据窗口 | 前 60 + 后 30 日 |

---

## 二、总体架构

```
interfaces
  └─ TradingCaseController        POST/GET/DELETE /trading/cases、POST /trading/cases/match
        │（插件门控 requireTradingPlugin，X-User-Id 隔离）
application
  └─ TradingCaseAppService        编排：标注→拉K→特征→落盘；匹配→拉K→特征→相似度
        │
domain/trading/cases
  ├─ CaseRecord                   案例实体（YAML 映射）
  ├─ CaseRepository               案例读写（data/{userId}/trading/cases/，复用 PushSettings 仓储模式）
  ├─ CaseFeatureExtractor         特征提取（MACD/MA/形态/量能，复用 KdjIndicator）
  ├─ CaseFeatureNormalizer        特征归一化（min-max → 0-1）
  └─ CaseSimilarityEngine         相似度（加权欧氏 + Top N）
        │
kernel/infrastructure
  ├─ KlineSource（已有）          日 K 重放（前 60 + 后 30 = 90 根 ≤ 320 上限）
  └─ AiClient（已有）             环 3 LLM 理解
```

**分层依赖遵循 C7**：`interfaces → application → domain/kernel ← infrastructure`；新增代码全部在 `domain/trading/cases/` + `application/TradingCaseAppService` + `interfaces/TradingCaseController`，不碰现有交易域。

---

## 三、数据模型设计

### 3.1 目录结构（File First，B2）

```
data/{userId}/trading/cases/
├── _index.json               # 案例清单（id/symbol/name/buyDate/buyType/+5d 收益，检索/列表用）
├── 2026-08-03_000725.json    # 案例真相源（单文件，可 diff/可回滚）
└── kline/                    # K 线快照（可选，默认不落盘——按日期+标的可重放）
    └── 2026-08-03_000725.json
```

> **格式实现修正（2026-08-30 动工定稿）**：原设计草案为 YAML，**实现定为 JSON**（对齐项目数据惯例——trades/push/account 均 json，freeze 2.13-2.15；案例是机器消费数据，Jackson 直接映射嵌套 record 比 SnakeYAML 手写转换更可靠；YAML 仅规则层 D9 决策专用）。契约见 `data-format-freeze.md` §2.17。
>
> **K 线快照策略（设计取舍）**：默认**不落 K 线本体**——案例存 `symbol + buyDate + window`，画图/重算按需重放（`KlineSource.klineRange` 日期直查，腾讯/东财均支持）。特征画像已固化在 json 内，重放只影响画面不影响判定。快照作为可选开关（数据源变更/停更时启用）。

### 3.2 案例 YAML 完整契约（草案）

```json
# data/{userId}/trading/cases/2026-08-03_000725.json
formatVersion: 1
case:
  id: "2026-08-03_000725"
  symbol: "000725"
  name: "京东方A"
  buyDate: "2026-08-03"
  buyType: "B1"                # B1/B2/B3/SB1/unknown——用户标注；unknown 由环 3 推测
  description: "回踩 60 日线 + 地量，次日大阳启动"   # 用户可选
  labels: [缩量回踩, 黄线支撑]  # 可选自由标签
  labeledAt: "2026-08-30T10:00:00"
  window: { beforeDays: 60, afterDays: 30 }
features:                     # 买点日特征画像（环 2 自动计算，全部标准化相对值）
  drawdownFromHighPct: 52.3     # 距前高（20 日窗口）回撤 %
  volumeShrinkRatio: 0.62       # 3 日均量 / 5 日均量
  kdjJ: 8.4                     # KDJ.J（复用 KdjIndicator 口径）
  kdjGoldenCross: true
  macdHist: -0.31               # MACD 柱值（DIF-DEA）
  macdCrossUp: true
  maRelation: "close_above_ma20_below_ma60"   # close vs MA20/MA60
  distToMa60Pct: 1.8            # 距 60 日线 %（黄线近似主特征）
  yellowLineState: "near"       # near/touch/below/above（黄线近似态）
  whiteAboveYellow: false       # 白线（MA10）在黄线（长均线）之上 = 开门
  sidewaysDays: 5               # 近 10 日振幅 < 3% 的天数
  breakoutFromHigh: false       # 收盘破前 20 日高点
verify:                       # 后验窗口（环 2 同时计算）
  +5dReturnPct: 18.2
  +10dReturnPct: 24.5
  maxDrawdownAfterBuyPct: -2.1
  stopLossHit: false
aiInsight:                    # 环 3 LLM 理解产物（P2 填充）
  summary: ""
  keyFeatures: []
  confidence: 0.0
  reviewed: false
```

### 3.3 _index.json（清单）

```json
version: 1
cases:
  - id: "2026-08-03_000725"
    symbol: "000725"
    buyDate: "2026-08-03"
    buyType: "B1"
    summaryFeatures: { drawdownFromHighPct: 52.3, volumeShrinkRatio: 0.62, kdjJ: 8.4 }
    verify: { "+5dReturnPct": 18.2 }
```

> 清单用于列表页/匹配时的轻量读取（不逐个读全量案例文件）；写时原子更新（tmp+move，对齐 push-settings 模式）。

---

## 四、特征提取设计（核心）

### 4.1 特征计算算法（全部从 OHLCV 重算，无外部依赖）

| 特征 | 算法 | 复用 |
|:-----|:-----|:-----|
| `drawdownFromHighPct` | (前 20 日最高收盘 − 买点日收盘) / 前 20 日最高收盘 × 100 | 前高窗口同 B2 口径 |
| `volumeShrinkRatio` | 3 日均量 / 5 日均量（量比 <1 缩量）| — |
| `kdjJ` / `kdjGoldenCross` | KDJ 9,3,3；J 值 + 金叉（K 上穿 D）| `KdjIndicator` ✅ |
| `macdHist` / `macdCrossUp` | EMA12/EMA26 → DIF，DIF 的 9 日 EMA = DEA；柱 = DIF−DEA；金叉 = DIF 上穿 DEA | 新增 `MacdIndicator` |
| `maRelation` / `distToMa60Pct` | MA20/MA60；|close−MA60|/MA60×100 | 新增（黄线近似）|
| `yellowLineState` | 距长均线（默认 60，可配 `adai.trading.case.yellow-ma`）：<0.5% touch / <2% near / 其他 above/below | 参数化 |
| `whiteAboveYellow` | MA10 > 长均线（白线在黄线之上 = 开门态）| 参数化（白线均线周期可配）|
| `sidewaysDays` | 近 10 日逐日 (high−low)/close < 3% 计数 | — |
| `breakoutFromHigh` | 收盘 > 前 20 日最高收盘 | — |

### 4.2 自定义指标三态还原（D2）

| 态 | 内容 | 状态 |
|:---|:-----|:-----|
| 语义近似 | 黄线 ≈ 长均线（`adai.trading.case.yellow-ma` 默认 60 日，已配置化）；白线 ≈ MA10（`white-ma` 默认 10）；洗盘短线 ≈ 缩量长下影（`(high−low)/close` 大 + 下影 > 实体 + 缩量）；转型图（砖形图）≈ 趋势分段（N 日新高/新低翻转）| **已实现 + 参数化（2026-08-30）** |
| 公式精确 | 用户提供通达信公式源码 → 转写 Java 指标（公式语言翻译）| 待用户提供 |
| 校验 | 精确实现后对历史案例重算对比（画面一致）| 公式落地批 |

> 语义依据（2026-08-30 核实）：`os/trading-engine/01-raw/2025-10-04_国庆课程.md`——黄线 = 主力成本线（「接近于 60 日线但非 60 日线」）、白线在黄线之上 = 开门买入区 / 之下 = 关门。

### 4.3 归一化规则

- 每特征定义 `{min, max}` 边界 → `v' = (v − min) / (max − min)`，钳制 0-1
- 初始边界（第一版固定表）：回撤 0-100、量比 0-3、KDJ.J 0-100、MACD 柱 ±5（钳制）、距 60 日线 0-20、sideways 0-10、布尔 0/1
- 边界放 `TradingCaseNormalizerConfig`（YAML/默认常量），后期可用案例库实际分布校准（后置）

---

## 五、相似度引擎设计（环 4，P3）

### 5.1 匹配流程

```
输入：symbol + date（或复用 buy-points 扫描的当前快照）
  → 拉前 60 日 K → CaseFeatureExtractor 算当前特征向量（缺 buyDate 用最近交易日）
  → 与案例库全部特征画像归一化对比
  → 输出 Top N（相似度降序）：
      caseId / similarity / buyType / +5d/+10d 后验 / aiInsight.summary
  → 案例库为空 / 无命中 → 空结果（静默，不影响规则判定）
```

### 5.2 距离度量

- **主度量**：加权欧氏距离 `d = √Σ wᵢ(vᵢ'−cᵢ')²`，权重表（第一版）：回撤 0.25 / 量比 0.20 / KDJ.J 0.15 / 距 60 日线 0.15 / MACD 柱 0.10 / sideways 0.10 / 布尔 0.05
- **相似度**：`similarity = 1 − d/d_max`（d_max = 全异向量距离），映射 0-100%
- **辅助**：余弦相似度用于形态方向（突破类案例），P3 二期
- 权重表落 `TradingCaseSimilarityConfig`（可配，第一版默认值）

### 5.3 阈值策略

第一版**不拍死阈值**——输出 Top N + 分数，由用户观察后定（避免过早拍死）；预留 `adai.trading.case.min-similarity` 配置位，用户拍板后填。

---

## 六、API 设计（契约草案）

> 全部需 trading 插件（403）；`X-User-Id` 隔离。前缀 `/api/v1/trading/cases`。

| 方法 | 路径 | 功能 | 落盘/说明 |
|:--|:--|:--|:--|
| POST | `/trading/cases` | **标注一个完美买点案例** | body `{symbol, buyDate, buyType?, description?}` → 拉 K → 特征画像 → 落盘 json + 清单 → 返回 `{caseId, features, verify, klineRef}`（含后验窗口结果，供前端画图）|
| GET | `/trading/cases` | 案例列表 | 分页/按 buyType 过滤，返回清单（id/symbol/name/buyDate/buyType/verify 摘要）|
| GET | `/trading/cases/{caseId}` | 案例详情 | 全量 json + 特征 + aiInsight；`?kline=true` 附 90 根日 K（前端画图用）|
| DELETE | `/trading/cases/{caseId}` | 删除案例（标注错了）| 删 json + 清单条目；404 不存在 |
| POST | `/trading/cases/match` | **判定当下（核心）** | body `{symbol, date?}` → 当前形态 vs 案例库相似度 → Top N；空案例库返回 `{matches: []}`（不报错）|

**与现有 buy-points 的关系**：独立端点，不污染 `GET /trading/buy-points` 契约（P3 二期可在 buy-points 响应附 `caseMatches` 可选字段，向前兼容）。

### 6.1 POST /trading/cases 响应示例

```json
{
  "caseId": "2026-08-03_000725",
  "symbol": "000725",
  "name": "京东方A",
  "buyDate": "2026-08-03",
  "features": {
    "drawdownFromHighPct": 52.3, "volumeShrinkRatio": 0.62,
    "kdjJ": 8.4, "macdHist": -0.31, "distToMa60Pct": 1.8,
    "sidewaysDays": 5, "breakoutFromHigh": false
  },
  "verify": { "plus5dReturnPct": 18.2, "plus10dReturnPct": 24.5, "maxDrawdownAfterBuyPct": -2.1 },
  "kline": [ {"date": "2026-06-02", "open": 4.2, "high": 4.3, "low": 4.15, "close": 4.25, "volume": 123456}, "..." ]
}
```

### 6.2 错误与降级

- 拉 K 失败（网络/数据源）→ 502 人话错误，**不落半成品案例**（fail-visible，对齐 P0-1 原则）
- 案例已存在（同 symbol+date）→ 409 + 提示「已标注过，可查看或删除重标」
- buyDate 非法（未来日期/非交易日）→ 400
- match 无案例库 → `{matches: []}`（200，静默降级）

---

## 七、K 线图画图方案（web 先行）

### 7.1 技术选型

- **web（adai-web，Flutter Web）**：`CustomPaint` 手绘 K 线——零新依赖，与现有交易页集成（弹窗/独立 Tab）
- **app（adai-app）**：后置（P3 二期，同款 CustomPaint 组件抽共享逻辑）
- 后端**不生成图片**（省 Java 图表依赖，数据 JSON 下发，前端按需绘制可交互）

### 7.2 画布结构

```
┌──────────────────────────────┐
│ 主图：K 线（红涨绿亏）+ MA20/MA60（黄线）│
│      + 买点日标记（▲ 绿/红标 + 日期）    │
├──────────────────────────────┤
│ 副图①：成交量（红涨绿亏柱）           │
│ 副图②：KDJ（K/D/J 三线）+ MACD（柱+DIF/DEA）│
└──────────────────────────────┘
```

- 数据：`GET /trading/cases/{caseId}?kline=true`（90 根 OHLCV + 特征 + 买点日期）
- 交互（第一版最小）：滚动条（90 根画布内）、买点日竖线高亮；缩放/十字线后置
- 红涨绿亏贯穿（对齐 A 股配色硬规则，P2-UI1 冲突不引入——买点标记用蓝色/中性色避免再犯）

---

## 八、判定接入（环 4 与现有扫描的关系）

> **标的范围（D4，2026-08-30 用户拍板「不要全市场」）**：
> - **每日自动**：仅**自选股 + 持仓**（复用 15:10 buy-points 扫描同一批标的，几十只毫秒级）
> - **手动按需**：match 端点任意 6 位代码（随查随用）
> - **不做**：全市场 5000+ 每日扫描（行情成本 + 算力量级增长，且不符合择时→选股纪律，全市场选股属另一条线）

| 接入点 | 方式 | 阶段 |
|:-----|:-----|:-----|
| `POST /trading/cases/match` | 手动/按需查询（web 交易页「案例匹配」入口，任意代码）| P3 |
| 15:10 买点扫描（`WatchlistBuyPointService`）| ✅ 已实现（2026-08-30）：开关 `adai.trading.case.scan-match`（默认关，向前兼容）→ 每只自选股附案例相似度 Top 3；规则未命中但案例相似 → `buyPoint="case"` 参考类型（**推送跳过**，web 可见）；**范围=自选股** | ✅ 完成 |
| `GET /trading/buy-points` | ✅ 响应附 `caseMatches`（可选字段，开关默认关 → 空）| ✅ 完成 |

> 性能：特征提取 90 根 K 为毫秒级；案例库匹配为内存向量对比（≤数百案例无压力）；K 线拉取走现有缓存/并发池。

---

## 九、降级与安全

| 场景 | 行为 |
|:-----|:-----|
| 案例库为空 | 标注照常；match 返回空；页面显示「还没有案例，标注第一个」空态 |
| 拉 K 失败 | 标注 502 不落半成品；match 502（不影响 buy-points 规则判定）|
| 数据源变更/停更 | 特征已固化在 json；画面重放失败 → 显示「K 线暂不可用」+ 特征画像仍可看 |
| 用户隔离 | `data/{userId}/trading/cases/`，X-User-Id 全程隔离（对齐现有 trading 数据）|
| 隐私 | 案例 = 行情 + 用户标注（无账户/持仓数据）；`data/` gitignore 全局生效（B3）|
| 相似度不覆盖硬判定 | 止损/仓位等确定性判定只走规则引擎；案例相似度仅为参考信号（B4 边界不破坏）|

---

## 十、测试要点

| 层 | 用例 |
|:---|:-----|
| 特征提取（单元）| 构造 K 线序列（含已知形态）→ 断言特征值（对齐 `KdjIndicator` 测试风格）；边界：不足 60 根/停牌缺口 |
| 归一化（单元）| 边界钳制、min=max 除零保护 |
| 相似度（单元）| 同形态案例相似度高、不同形态低；空库返回空；权重生效 |
| Repository（单元）| json 读写 round-trip、清单原子更新、损坏文件 fail-visible |
| Controller（WebMvcTest）| 标注/列表/详情/删除/match 五端点；409 重复、400 非法日期、502 拉 K 失败、403 无插件 |
| 降级（集成）| 无案例库 match 空、拉 K 失败不落半成品 |

---

## 十一、实施步骤

### P1 环 1-2 最小闭环（信任起点）

| # | 任务 | 涉及 |
|:-:|:-----|:-----|
| 1 | `CaseRecord` + `CaseRepository`（json 读写 + 清单原子更新）| domain/trading/cases/ |
| 2 | `MacdIndicator` + `CaseFeatureExtractor`（§4.1 全特征 + 黄白线近似）| domain |
| 3 | `TradingCaseAppService` + `TradingCaseController`（POST/GET/DELETE cases）| application/interfaces |
| 4 | ✅ web K 线图 `CaseKlineChart`（三区 CustomPaint）+ 交易页第 7 Tab「案例」（2026-08-30 完成）| apps/adai-web |
| 5 | 测试 + 文档同步（api-spec/data-format-freeze/trading-features/status/change-log）| 全 |
| 6 | 用户验收：标注 1 个真实案例 → 画面还原认可（**待用户验收**）| — |

### P2 环 3 理解

| # | 任务 | 涉及 |
|:-:|:-----|:-----|
| 1 | LLM 案例理解（读特征 + K 线 → aiInsight 结构化输出，复用 AiClient 结构化模式）| application |
| 2 | 批量归纳（≥20 案例触发「共同特征」分析 → 对照 B1/B2 参数）| application |
| 3 | `aiInsight.reviewed` 人工确认流程（web 案例详情可编辑 summary）| web |

### P3 环 4 判定当下

| # | 任务 | 涉及 |
|:-:|:-----|:-----|
| 1 | `CaseFeatureNormalizer` + `CaseSimilarityEngine`（加权欧氏 + Top N）| domain |
| 2 | `POST /trading/cases/match` + web「案例匹配」入口 | application/interfaces/web |
| 3 | 15:10 扫描接入（可选开关）+ buy-points 响应附 caseMatches（二期）| application |
| 4 | 相似度阈值用户拍板定稿 | 配置 |

### P4 指标精确化

| # | 任务 | 涉及 |
|:-:|:-----|:-----|
| 1 | 用户提供黄白线/洗盘短线/转型图公式源码 → 转写 Java 指标 | domain |
| 2 | 历史案例重算校验（画面一致性对比）| 全 |
| 3 | 案例导出/备份（复用规则包导出思路）| 全 |

---

## 十二、文档同步面

| 文档 | 同步内容 | 阶段 |
|:-----|:-----|:-----|
| `api-spec.md` | cases 五端点契约 | P1（前 4 端点）/P3（match）|
| `data-format-freeze.md` | §2.17 案例文件契约（cases/ 目录 + json 格式）| P1 |
| `trading-features.md` | 新章节「完美买点案例库」（四环能力 + 端点 + 双轨判定 + 画图）| P1 |
| `feature-reference.md` §9 | 交易模块补案例库/相似度判定 | P1 |
| `status.md` | 测试数/端点数 | 每期 ship |
| `REVIEW.md` | **S7 随 P3 出表或改口径**（「完美图匹配度」有样本库支撑）| P3 |

---

## 附：与现有文档的关系

- `../rfc/20260830-trading-perfect-case-library.md`——方向/决策（本设计的上位文档）
- `trading-plugin-architecture.md`——第三阶段规则层（案例库的地基：用户规则/降级语义/仓储模式）
- `api-spec.md` / `data-format-freeze.md`——契约真相源（P1 实施时同步）
- `os/trading-engine/01-raw/`——黄白线/洗盘/转型图课程语义（§4.2 近似实现依据）
