---
title: Layer 5 行情接入与主动推送 MVP
date: 2026-07-30
status: draft
---

## 一、背景

### 为什么现在做

AdaiOS 五层产品架构中 **Layer 5（外部信息）** 是唯一完全空白的层。

用户日常使用 通达信 + 指南针 + 东方财富 看行情做交易，但目前 AdaiOS 对持仓和市场的认知停留在**用户手工输入**层面：

- `data/trading/positions.md` 中 `currentPrice` 手工填写，常与市场脱节
- Context Engine 注入的持仓上下文**没有实时价格**
- 用户问"阿呆我持仓怎么样"，AI 不知道当前盈亏
- 用户问"茅台现在怎么样"，AI 无法回答

### 核心需求

用户需要的不是"又多一个行情软件"，而是：

> 我说股票或持仓，AI 已经知道基础数据，直接帮我分析——按我的交易规则判断该不该买、该不该卖。不是让我先报一遍数据再分析。

### 可用数据源

| 源 | 可用性 | 提供数据 |
|:---|:------|:---------|
| **腾讯行情** `qt.gtimg.cn` | ✅ 实测可用，无 Key | 最新价、涨跌幅、最高、最低、今开、昨收、成交量 |
| 东方财富 `push2.eastmoney.com` | ❌ 502（不稳定） | — |
| 新浪 `hq.sinajs.cn` | ❌ Forbidden | — |
| 新闻/政策 API | ❌ 无稳定免费源 | — |

---

## 二、架构

```
kernel/market/              ← 新增 Kernel 组件
  MarketDataSource.java       ← 接口
  MarketData.java             ← DTO
  TencentMarketDataSource.java ← 实现（腾讯行情）
  MarketCache.java            ← 缓存策略

domain/trading/              ← 增强
  MarketContextContributor.java  ← 上下文注入（大盘 + 持仓）
  修改 TradingContextContributor  ← 替换手工 currentPrice

application/                 ← 新增
  MarketAlertService.java     ← 异动检测 → 主动推送
```

### 数据流

```
用户问"阿呆，茅台" / "我持仓怎么样"
             ↓
IntentRecognizer → trading 场景
             ↓
MarketContextContributor
  ├ 调 TencentMarketDataSource → 上证指数 + 股票行情
  ├ 读 data/trading/positions.md → 持仓
  ├ 匹配持仓代码 → 算盈亏
  └ 拼成上下文传给 AI
             ↓
DeepSeek 读：持仓+行情+交易规则 → 回答你
```

### 主动推送流

```
定时任务（每 30 分钟）
  ↓
MarketAlertService
  ├ 拉持仓行情
  ├ 与上次对比
  ├ 检测异动阈值（跌超3%/涨超5%/触发交易规则）
  └ 写入 Feed（type=market）
             ↓
你刷新首页 → 看到推送
```

---

## 三、Phase 1：行情接入 + 上下文注入（MVP）

### 改动清单

| 文件 | 改动 | 说明 |
|:-----|:-----|:------|
| **新建** `kernel/market/MarketDataSource.java` | 接口 | `quote(codes)` + `indices()` |
| **新建** `kernel/market/MarketData.java` | DTO | `code/name/price/changePercent/high/low/open/yesterdayClose/volume` |
| **新建** `kernel/market/TencentMarketDataSource.java` | 实现 | HTTP 调 `qt.gtimg.cn`，60 秒缓存 |
| **新建** `kernel/market/MarketCache.java` | 缓存 | 交易时段 60 秒，非交易时段冻结 |
| **新建** `domain/trading/MarketContextContributor.java` | ContextContributor | `supports("trading")` |
| **修改** `TradingContextContributor.java` | 替换 | 去掉手动 `currentPrice`，改为 MarketDataSource 实时价 |
| **修改** `data/trading/positions.md` | 格式 | 可去掉 `currentPrice` 字段（由系统自动补） |

### ContextContributor 注入内容

**`supports("trading")` 时注入：**
```
## 当前行情

**大盘指数：**
- 上证 3804.69(-0.62%) | 深证 13285.80(-2.73%) | 创业板 3244.62(-3.97%)

**持仓行情：**
- 立昂微 200股 成本25.30 → 现价25.30(0.00%) 浮盈+0
- 茅台 200股(参考) 成本1321 → 现价1361(+3.09%) 浮盈+8000

**市场状态：** 上证跌0.62%，深证跌2.73%，大盘整体弱势。
当前处于交易系统定义的空头区间（6/26触发-2.3%离场法则），
建议控制仓位，暂不积极寻找B1买点。
```

### 代码规则

腾讯 API 需要前缀映射：

```java
// 6位代码 → 腾讯格式
"600123"  → "sh600123"   // 上海 A 股（6开头）
"000001"  → "sz000001"   // 深圳主板（0开头）
"002415"  → "sz002415"   // 深圳中小板（2开头）
"300750"  → "sz300750"   // 深圳创业板（3开头）
"830799"  → "bj830799"   // 北交所（8开头）
```

### 不做

- ❌ WebSocket 实时推送（HTTP 轮询够用）
- ❌ 港股/美股/期货
- ❌ 技术指标计算（MACD/KDJ/BBI）
- ❌ 新闻搜索
- ❌ 自选股管理（持仓驱动）
- ❌ K 线图

### 工期

半天到一天（3-4 个新文件 + 1 个修改）。

---

## 四、Phase 2：主动推送（持仓异动）

### 设计

```
定时任务（@Scheduled 每30分钟）
  ↓
MarketAlertService.poll()
  ├ 取当前持仓行情
  ├ 对比上次快照（内存中）
  ├ 检测异动：
  │   ├ 单日跌幅 ≥ 3%  → ⚠️ 止损预警
  │   ├ 单日涨幅 ≥ 5%  → 📈 放飞条件检查
  │   ├ 触发-2.3%离场   → 🔴 择时信号变化
  │   └ 重回+4%区间    → 🟢 转多信号
  └ 生成 FeedEntry(type=market)
```

### Feed 推送示例

```
📈 立昂微今日涨 5.2%，现价 26.60
系统判断：超过中阳线标准，关注BBI线上是否出现
第二根中阳线作为放飞信号。

🔴 择时信号：今日上证跌 2.5%，触发 -2.3% 离场法则
建议转入空头区间，控制仓位。

📉 茅台今日跌 3.8%，距你的成本线 1321 还有 2.9%
当前未触发止损（进场K线未破），但需密切关注。
```

### 改动清单

| 文件 | 改动 |
|:-----|:------|
| **新建** `application/MarketAlertService.java` | 定时轮询 + 异动检测 + Feed 写入 |
| **修改** `infrastructure/storage/MarketSnapshotRepository.java` | 上次快照读写 |
| **修改** `FeedEntry.java` | 加 `market` 类型 |
| **修改** `FeedAppService.java` | Feed 包含 `type=market` 条目 |

### 不做

- ❌ 新闻政策推送（无可靠 API）
- ❌ B1 买点推送（缺技术指标）
- ❌ 止损线自动计算（需用户指定进场K线位置）
- ❌ 推送通知/弹窗（先 Feed 内展示）

### 工期

一天到两天。

---

## 五、不在范围内的（后续考虑）

| 功能 | 卡点 | 何时可能 |
|:-----|:-----|:---------|
| 新闻政策推送 | 无稳定免费新闻 API | 找到 API 后 |
| 技术指标推送（B1/止损/放飞） | 腾讯不提供 MACD/KDJ/BBI | 找到技术指标源后 |
| 通达信/指南针数据导出 | 需要研究平台导出格式 | 后续调研 |
| 美股/港股/期货 | 不在交易系统范围内 | 暂不考虑 |

---

## 六、效果验证

做完后用户可以：

1. 问"阿呆，我持仓怎么样" → AI 知道当前盈亏 + 按规则分析
2. 问"阿呆，茅台可以买吗" → AI 拉到当前价格 + 结合你成本判断
3. 问"阿呆，最近市场怎么样" → AI 拉大盘 + 判断择时区间
4. 打开首页看到持仓异动推送（Phase 2）

**不改变的事：** 你日常还是在通达信/指南针上操作交易，AdaiOS 是帮你保持理性判断的副驾驶。
