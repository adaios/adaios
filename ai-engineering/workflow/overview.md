---
title: workflow 工作流总览（主心骨 + 五段闭环 + guard 挂点）
description: AdaiOS AI 协作工作流一张图——约束模型（文档约束代码、审核约束两者）+ 五段闭环（讨论→方案→开发→ship→审查）+ guard 家族挂点；任何 AI 读此文件即定位自己在流程中的位置
version: 1
created: 2026-08-23
updated: 2026-08-23
status: active
lines: 73
depends-on:
  - ../README.md
  - ../process/ship.md
  - ../process/review.md
related:
  - discuss.md
  - design.md
  - develop.md
tags: [ai, meta, workflow, overview]
---

# 工作流总览

> 一张图看懂 AdaiOS 的 AI 协作工作流：**主心骨（约束模型）→ 五段闭环（过程）→ guard 挂点（自动化）**。新会话/新 AI 工具读此文件即可定位自己在流程中的位置。

## 一、主心骨：文档约束代码，审核约束两者

```mermaid
flowchart LR
    DOC["文档 / 契约<br/>api-spec · freeze · feature-reference"] -- 约束 --> CODE["代码 / 实现"]
    CODE -- "验证对齐<br/>guard-align 机器对拍" --> DOC
    CODE -- 有问题 --> REVIEW["REVIEW.md<br/>未修项记录"]
    AUDIT["审核<br/>8 审查官 · 人"] -- 约束 --> DOC
    AUDIT -- 约束 --> CODE
    AUDIT -- 发现问题 --> REVIEW
    REVIEW -- "排期修复<br/>出表才闭环" --> CODE
    META["ai-engineering 本身<br/>也是项目 · 自伤自查"] -. 也要被审 .-> AUDIT
```

## 二、五段闭环

```mermaid
flowchart LR
    A["① 讨论<br/>只聊不动手 · 沉淀过滤器"] -->|"用户说「开工/做/改」"| B["② 方案<br/>RFC · 六维需求 · ADR 三问"]
    B --> C["③ 开发<br/>动工前查边界/坑/规范"]
    C --> D["④ ship 收尾<br/>五件套：代码+测试+契约+门禁+清单"]
    D --> E["⑤ 审查<br/>派官独立并行 · 交叉印证"]
    E -->|发现问题| R["REVIEW.md"]
    R -->|下一批修复出表| C
```

## 三、guard 挂点（自动化）

```mermaid
flowchart TB
    T1["开工"] --> G1["guard-context<br/>上下文基线 / 成本提醒"]
    G1 --> T2["开发"]
    T2 --> T3["提交 pre-commit"]
    T3 --> G2["guard-align<br/>代码↔文档对拍 · 自动拦截"]
    G2 --> T4["收尾"]
    T4 --> G3["guard-meta --fix<br/>元治理 · lines 校准"]
    G3 --> G4["guard-sediment<br/>沉淀 / 出表 / 登记检查"]
    G4 --> T5["部署"]
    T5 --> G5["deploy-gate<br/>最硬闸门 + smoke"]
    G1 -. 收工 .-> G6["guard-cost --record<br/>成本入账"]
```

## 四、日常四问（人只需记这些）

1. **做之前**：契约文档写了吗？（先文档后代码）
2. **做之后**：测试 + guard-align 对拍过了吗？（验证对齐）
3. **发现问题**：记 REVIEW 了吗？（记录）
4. **该审了**：派官审了吗？（审核约束）

其余全是自动化——guard 脚本不是额外步骤，是第 ④⑤ 步的机器化。
