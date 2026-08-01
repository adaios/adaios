# Life OS — 生活管理领域

## 职责

Life OS 负责个人生活的系统化管理，涵盖日记、情绪、习惯追踪、健康数据、财务管理（非交易）等。作为 AdaiOS 的个人生活助手，帮助用户在 AI 的协助下管理日常生活的各个方面。

## 输入

| 来源 | 数据 | 说明 |
|------|------|------|
| Kernel (interfaces) | 生活指令 | 创建/查询/修改日记、习惯记录等 |
| Kernel (identity) | 用户画像 | 生活习惯偏好、作息模式 |
| Kernel (timeline) | 近期生活事件 | 最近日记、情绪变化、习惯完成情况 |
| Kernel (memory) | 历史记忆 | 过往生活模式、健康趋势 |
| 外部 | 健康数据 | 智能手表/手环 API、健康应用 |
| 外部 | 财务数据 | 银行流水、账单、预算（非交易数据） |

## 输出

| 目标 | 数据 | 说明 |
|------|------|------|
| Kernel (record) | 生活事件 | 日记、情绪、习惯记录作为 Record 文件沉淀 |
| Kernel (timeline) | 时间线投影 | Record 自动投影为 Timeline 事件 |
| Kernel (context) | 生活上下文 | 供 Context Engine 组合为 Life Context Package |
| Kernel (memory) | 生活记忆 | 习惯 streak、健康趋势等沉淀为长期记忆 |

## Life Context Package

当用户询问生活相关问题时，Context Engine 自动组合：

```
Identity（作息偏好、健康目标）
  + Timeline（近期日记、情绪变化）
  + Memory（过去健康趋势、习惯模式）
  + Life OS（当前习惯完成情况、健康数据）
  = Life Context Package → AI
```

## 存储策略

采用 **File First**：

```
data/life/
├── journal/         # 日记（按日期）
├── habits/          # 习惯追踪
├── health/          # 健康数据
└── finance/         # 个人记账
```

数据库为运行态查询服务，不替代文件存储。

## 与 adai-core 的关系

- **依赖方向**：`kernel` → `domain.life`（通过 domain 接口调用）
- **协作方式**：life-os 实现 `com.adaiadai.core.domain.life` 包中定义的接口；`application` 层编排跨域用例
- **上下文**：Context Engine 读取 identity + timeline + memory + life 状态，组合为 Life Context Package 提供给 AI
- **AI 不直连**：AI 通过 Context Engine 获取生活相关数据，life-os 不直接暴露数据库或文件

## 边界范围

- **包含**：日记管理、情绪记录、习惯追踪、健康数据管理、个人财务管理（非交易）
- **不包含**：交易相关金融功能（归 trading-os）、项目相关功能（归 project-os）
