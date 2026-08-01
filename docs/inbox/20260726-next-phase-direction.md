# AdaiOS 下一阶段方向调整

日期：2026-07-26

## 当前判断

AdaiOS 已经进入「可运行系统阶段」。

当前核心已经形成：

- Kernel
- Context Engine
- Memory System
- Knowledge System
- Domain OS
- Flutter App

下一阶段重点不是继续扩大概念，而是验证：

> AdaiOS 是否能够长期服务真实的个人生活和开发工作。


---

# 下一阶段优先级调整


## 第一优先级：深化 Project OS

目标：

让 AdaiOS 使用自己的 Project OS 管理自身开发。

Project OS 不只是任务管理，而应该成为：

AI Native Software Engineering OS。


让 AI 能理解：

- 项目目标
- 系统架构
- 历史设计决策
- 当前开发状态
- 开发规范
- 下一步任务


### Phase 2 方向

1. RFC 状态化

给 RFC 增加状态：

- proposed
- approved
- implemented
- deprecated


让 RFC 成为项目知识的一部分。


2. Task 与 Commit 关联

建立：

Task
 ↓
Commit
 ↓
RFC
 ↓
Project Knowledge


3. Project Context 增强

Context 中自动包含：

- 当前任务
- 最近完成任务
- RFC 状态
- 最近 Commit
- 项目规则


目标：

Claude Code / Qoder 进入项目后，可以快速理解项目。


---

# 第二优先级：接入外部世界（Layer 5）

当前 AdaiOS 已经可以理解：

- 我是谁
- 我过去做过什么
- 我的知识和经验


但还不知道：

- 外部世界发生什么


因此增加：

Market Kernel。


第一阶段：

A 股行情接入。


新增：

kernel/market


包括：

- MarketDataSource
- MarketData
- EastMoneyMarketDataSource
- MarketContextContributor


数据流：

App

↓

Context Engine

↓

Market Data

↓

AI Context


目标：

让 AI 简报能够结合：

- 当前持仓
- 市场行情
- 新闻信息


第一阶段不做：

- 实时行情
- WebSocket
- 自动交易
- 技术指标
- 港美股


---

# 第三优先级：Life OS 数据积累

Life OS 当前问题不是代码，而是数据。


目前：

代码能力 > 数据能力。


保持记录入口：

- 心情
- 睡眠
- 运动
- 饮食


暂时不要继续开发复杂功能。


等数据积累：

10+ 条记录：

增加情绪趋势。


多个标签持续数据：

增加习惯分析。


连续两周：

增加生活周报。


---

# 暂停事项


## 不增加新的 Domain OS

暂时不要扩展：

- Health OS
- Learning OS
- Finance OS
- Social OS


核心目标：

验证 Kernel 是否可以支撑多个领域。


---

## 不急着做 Agent / MCP

顺序应该是：

Context

↓

Knowledge

↓

Action

↓

Agent


当前重点：

让 Context 和 Knowledge 稳定。


---

## 不继续架构抽象

暂时不要增加：

- Meta Kernel
- Universal Kernel
- 更高层 OS 概念


当前进入：

真实使用阶段。


---

# 未来30天目标


## 第一周

完成 Project OS：

- RFC 状态管理
- Task-Commit关联
- Project Context增强


结果：

AI 可以理解项目状态。


---

## 第二周

完成 Market Kernel：

- A股行情接口
- Context注入
- 简报增强


结果：

AdaiOS 第一次连接外部世界。


---

## 第三、四周

持续真实使用：

每天记录：

- 开发过程
- 交易
- 想法
- 生活


观察：

- Memory增长
- Context质量
- AI理解能力


---

# 核心原则


未来不要追求：

更大的概念。


而追求：

真实使用

↓

数据积累

↓

Context增强

↓

AI越来越懂用户


AdaiOS 当前阶段：

从「设计一个 AI OS」

进入：

「运行一个 AI OS」。