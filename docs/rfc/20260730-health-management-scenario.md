---
title: 健康管理场景（Health Management Scenario）
date: 2026-07-30
status: draft
---

# ADAIOS 个人健康管理场景设计

版本：v1.0

---

# 1. 背景

ADAIOS 当前核心架构：

```
Kernel Layer

(Context + Memory + Knowledge)

          ↓

Domain OS Layer

          ↓

Trading OS
Life OS
Project OS
...
```

Domain OS 的目的不是创建独立应用，而是为不同领域提供：

* 状态理解
* 领域知识
* 上下文增强
* AI 辅助决策

个人健康管理属于 Life Domain 的一个重要场景。

本文讨论如何将：

* 体重管理
* 饮食记录
* 运动习惯
* 睡眠状态

等个人健康信息接入 ADAIOS。

---

# 2. 设计目标

目标不是开发一个独立减肥 App。

目标：

> 让 ADAIOS 能够理解用户长期健康状态，并结合 Memory 和 Context，在关键节点提供建议。

例如：

用户输入：

```
今天下午想喝奶茶
```

普通 AI：

```
奶茶热量较高，建议少喝。
```

ADAIOS：

结合历史：

```
过去30天：

奶茶消费：
8次

其中：
6次发生在压力较大时期

最近：
睡眠下降
运动减少
体重连续上涨


建议：

今天不建议饮用。

如果确实想喝：
选择小杯、低糖，
并减少晚餐热量。
```

区别：

不是回答问题。

而是基于个人状态进行判断。

---

# 3. 在 ADAIOS 中的位置

健康管理属于 Life Domain。

结构：

```
Domain OS Layer

├── Trading OS
│
├── Project OS
│
├── Life OS
│
│    └── Health Management
│
└── Future Domains
```

Health Management 不应该成为独立系统。

原因：

健康状态与生活状态高度相关。

例如：

```
连续加班

↓

睡眠减少

↓

饮食失控

↓

体重变化

↓

影响工作状态
```

因此它属于 Life Domain。

---

# 4. 数据流设计

整体流程：

```
用户输入

↓

Record

↓

Timeline

↓

Context Engine

↓

Life Domain Context

↓

Health Context

↓

Memory

↓

AI Reasoning

↓

建议 / 提醒
```

---

# 5. 健康数据模型

健康场景主要包含：

## 5.1 基础状态

```
Weight State

当前体重
目标体重
体重趋势
阶段目标
```

示例：

```json
{
 "currentWeight":85.5,
 "targetWeight":75,
 "trend":"下降"
}
```

---

## 5.2 行为记录

```
Food

Exercise

Sleep

Emotion
```

例如：

```
2026-07-30

Food:
奶茶

Exercise:
无

Sleep:
6小时

Emotion:
压力较大
```

---

# 6. Memory 沉淀

健康信息不应该只是日志。

需要从：

Record

↓

Memory

例如：

原始记录：

```
7月30日

晚上吃烧烤
```

长期 Memory：

```
用户压力较大时，
容易选择高热量食物作为奖励。

该行为过去30天出现5次。
```

Memory 保存的是：

* 用户习惯
* 行为模式
* 长期趋势

---

# 7. Context Integration

健康信息通过 ContextContributor 接入 Context Engine。

类似：

当前：

```
TradingContextContributor

ProjectContextContributor
```

未来：

```
HealthContextContributor
```

职责：

根据当前问题提供健康相关上下文。

例如：

用户：

```
我要不要喝奶茶？
```

Context Engine：

组合：

```
用户身份 Context

+

Life Context

+

Health Context

+

Memory Context
```

提供给 AI。

---

# 8. AI 能力范围

健康场景主要提供三个能力：

## 8.1 状态分析

回答：

```
当前健康状态如何？
```

例如：

```
过去30天：

体重下降2kg

运动完成率70%

饮食控制良好

整体趋势正常。
```

---

## 8.2 决策辅助

例如：

用户：

```
今天想吃炸鸡
```

AI：

结合：

* 最近体重趋势
* 今日摄入
* 最近行为

给建议。

---

## 8.3 主动提醒

例如：

检测：

```
连续5天运动不足

睡眠下降

体重停滞
```

生成：

```
最近状态出现下降趋势。

今天建议：

20分钟运动

早点休息。
```

---

# 9. 与其他 Domain 协同

Domain OS 不应该独立。

例如：

Project OS：

发现：

```
近期开发时间增加
```

Health：

发现：

```
睡眠下降
运动减少
```

Life：

综合：

```
当前处于高压力阶段
```

最终：

ADAIOS 给出综合建议。

---

# 10. MVP 实现建议

第一阶段不增加复杂系统。

基于现有架构：

新增：

```
LifeContextContributor

或者

HealthContextContributor
```

支持：

* 健康记录
* 状态计算
* Memory 提炼
* AI 建议

不需要：

* 独立服务
* 独立数据库
* 独立 App

继续遵循：

File First

Context First

Memory First

---

# 11. 验证目标

通过健康管理场景验证：

## Context

AI 是否获得正确个人状态。

## Memory

AI 是否理解长期行为。

## Domain Extension

新的领域能力是否可以低成本接入。

## Agent

AI 是否可以主动辅助决策。

---

# 12. 未来扩展

Life Domain 可以逐步支持：

```
Life OS

├── Health
│
├── Finance
│
├── Relationship
│
├── Learning
│
└── Personal Growth
```

具体能力根据实际需求逐步增加。

---

# 总结

健康管理不是 ADAIOS 的新产品方向。

它是 Life Domain 下的一个真实应用场景。

通过这个场景，可以验证 ADAIOS 当前核心设计：

Record → Context → Memory → Knowledge → AI Decision

是否能够支持个人长期智能管理。
