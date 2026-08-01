# Trading OS — 金融交易领域

## 职责

Trading OS 负责金融交易全生命周期管理，包括行情接入、策略执行、订单管理、风控与回测。作为 AdaiOS 的金融交易大脑，连接个人交易策略与市场。

## 输入

| 来源 | 数据 | 说明 |
|------|------|------|
| Kernel (interfaces) | 交易指令 | 用户或 AI 发起的买卖指令 |
| Kernel (identity) | 用户画像 | 风险偏好、投资风格、长期目标 |
| Kernel (timeline) | 近期交易事件 | 最近买卖记录、市场行为 |
| Kernel (memory) | 历史交易记忆 | 过去决策复盘、经验教训 |
| 外部 | 实时行情 | 通过 infrastructure 层接入的行情源 |
| 外部 | 账户信息 | 券商/交易所的账户与持仓 |

## 输出

| 目标 | 数据 | 说明 |
|------|------|------|
| Kernel (record) | 交易事件 | 每个买卖指令作为 Record 文件沉淀 |
| Kernel (timeline) | 时间线投影 | Record 自动投影为 Timeline 事件 |
| Kernel (context) | 交易上下文 | 供 Context Engine 组合为 Trading Context Package |
| 外部 | 订单指令 | 发往券商/交易所的下单请求 |

## Trading Context Package

当用户询问交易相关问题时，Context Engine 自动组合：

```
Identity（风险偏好、投资风格）
  + Timeline（近期交易记录）
  + Memory（历史复盘记忆）
  + Knowledge（交易规则、策略）
  + Trading OS（当前持仓、盈亏）
  = Trading Context Package → AI
```

## 存储策略

采用 **File First**：

```
data/trading/
├── trades/          # 交易记录（Record 文件）
├── strategies/      # 策略定义
├── reviews/         # 复盘文档
└── research/        # 交易研究笔记
```

数据库为运行态查询服务，不替代文件存储。

## 与 adai-core 的关系

- **依赖方向**：`kernel` → `domain.trading`（通过 domain 接口调用）
- **协作方式**：trading-os 实现 `com.adaiadai.core.domain.trading` 包中定义的接口；`application` 层编排跨域用例；`infrastructure` 层提供行情源和交易所适配
- **上下文**：Context Engine 读取 identity + timeline + memory + trading 状态，组合为 Trading Context Package 提供给 AI
- **AI 不直连**：AI 通过 Context Engine 获取交易相关数据，trading-os 不直接暴露数据库或文件

## 边界范围

- **包含**：行情接入、策略引擎、订单执行、风控规则、回测框架、绩效分析
- **不包含**：行情原始存储（归 data/）、UI 渲染（归 apps/）
