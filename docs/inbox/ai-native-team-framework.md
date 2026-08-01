# AI Native Team Framework Foundation v0.1

> Version：0.1 MVP
> Status：Draft
> Scope：团队 AI 协同开发基础框架
> Last Update：2026-07
> Related：Workspace、Project Agent、ADAIOS、AI Context

------

# 一、文档目标

本文档用于定义 **AI Native Team Framework** 的基础架构。

目标不是设计一套新的开发框架，而是探索 **AI 时代的软件工程方法**。

传统软件工程主要围绕：

- 人与代码
- 人与人
- 项目与项目

展开协作。

而 AI Native Team Framework 希望增加第四种协作关系：

> **AI 与项目。**

最终形成：

- 人理解项目
- AI 理解项目
- 项目理解项目
- 团队理解团队

本文档作为团队级 AI Context，供 Claude Code、ChatGPT、Qoder 等 AI 统一参考。

------

# 二、为什么需要 Team Framework

随着项目数量增加，一个需求通常涉及多个仓库。

例如：

一个新的功能可能同时涉及：

- 阿呆 App
- Trading OS
- 后端服务
- Web 管理后台

传统开发流程主要依赖：

- 开会
- 文档
- 口头沟通
- Issue
- Wiki

对于 AI 来说：

这些信息通常分散且缺少统一描述。

因此 AI 很难回答：

- 当前团队有哪些项目？
- 每个项目负责什么？
- 谁应该负责这个需求？
- 哪个项目提供什么能力？
- 当前项目依赖哪些团队项目？

因此需要建立统一的团队协同框架。

------

# 三、设计目标

Framework 需要满足以下目标。

## 1. AI First

所有设计首先考虑：

AI 是否容易理解。

而不是：

IDE 是否方便。

------

## 2. Text First

所有核心信息采用纯文本保存。

避免依赖：

- 特定 IDE
- 特定模型
- 特定厂商

任何 AI 都可以读取。

------

## 3. Convention First

框架主要依靠约定。

而不是代码。

目录、文件、命名、生命周期均保持统一。

------

## 4. Project Independent

任何项目均可独立存在。

不会因为团队框架失效而无法开发。

------

## 5. Evolution

Framework 必须允许持续演进。

MVP 并不追求最终答案。

------

# 四、整体架构

团队整体采用 Workspace 组织。

```
Workspace
│
├── Project A
├── Project B
├── Project C
│
├── Shared Context
├── Architecture
├── Standards
├── ADR
└── Team Knowledge
```

Workspace 是整个团队唯一入口。

所有 AI 首先理解 Workspace。

再理解具体项目。

------

# 五、Workspace

Workspace 并不是代码仓库。

而是：

整个团队的软件工程上下文。

Workspace 包含：

- 团队结构
- 项目列表
- 公共规范
- Architecture
- ADR
- 团队知识
- AI Context

Workspace 不负责业务。

Workspace 负责组织。

它类似于：

Spring 的 ApplicationContext。

统一协调整个团队。

------

# 六、单项目 AI Context

每个项目都维护自己的 `.ai` 目录。

例如：

```
.ai/

boundary/

memory/

workflow/

tasks/

pitfalls/

decisions/

modules/
```

作用：

帮助 AI 快速理解项目。

AI 不应该扫描整个仓库之后才理解项目。

而应该首先阅读：

```
.ai
```

再进入代码。

因此：

```
.ai
```

属于：

Project Identity。

而不是 Prompt。

------

# 七、Project Identity

每个项目必须能够回答以下问题：

我是谁？

我的目标是什么？

我的边界是什么？

我的职责是什么？

我的技术栈是什么？

我的模块有哪些？

当前正在开发什么？

当前有哪些限制？

这些内容共同组成：

Project Identity。

任何 AI 都应该能够快速获得。

------

# 八、Project Contract

每个项目应该向团队公开自己的契约。

建议采用 Contract，而不是 Public。

Contract 描述：

- 项目定位
- 能力
- 边界
- 数据模型
- 服务接口
- AI 可以如何使用

Contract 不代表实现。

它代表承诺。

未来任何实现变化。

Contract 尽量保持稳定。

------

# 九、Capability

Framework 推荐以 Capability 组织项目关系。

而不是：

Project Dependency。

例如：

错误方式：

```
依赖：

Trading OS
```

正确方式：

```
需要能力：

Knowledge Search

Reasoning

Research
```

真正重要的是能力。

而不是具体项目。

未来：

多个项目可以提供相同 Capability。

Workspace 负责协调。

------

# 十、External Dependency

项目之间允许声明依赖。

例如：

```
external/

backend

trading-os

research

calendar
```

目的：

帮助 AI 理解：

项目依赖关系。

团队结构。

影响范围。

未来可进一步演进为 Capability Dependency。

------

# 十一、Project Agent

Project Agent 是项目在团队中的代表。

它不是具体 AI。

而是一种抽象角色。

Project Agent 负责回答：

- 我是谁？
- 我负责什么？
- 我提供什么能力？
- 我依赖谁？
- 当前正在开发什么？
- 最近发生哪些变化？
- 当前有哪些风险？

Project Agent 不直接写业务代码。

它代表整个项目参与团队协同。

未来任何 AI 都可以扮演 Project Agent。

------

# 十二、Workspace Agent

Workspace Agent 位于团队最高层。

职责：

协调项目。

例如：

收到一个需求。

Workspace Agent 首先分析：

涉及哪些项目。

再通知对应 Project Agent。

Project Agent 再负责本项目实现。

因此：

Workspace Agent 负责协调。

Project Agent 负责执行。

形成统一协作流程。

------

# 十三、Requirement Flow

团队推荐采用统一需求流。

需求提出

↓

需求分析

↓

影响项目分析

↓

任务拆分

↓

Project Agent

↓

项目开发

↓

Review

↓

Merge

↓

Knowledge 更新

整个流程尽量由 AI 协助完成。

------

# 十四、Knowledge Flow

Framework 的核心不是代码流。

而是 Knowledge Flow。

任何项目产生的新知识：

例如：

- 新模块
- 新接口
- 新规范
- 新经验
- 新坑

最终都应进入团队知识。

形成长期积累。

Knowledge 属于团队。

而不是个人。

------

# 十五、参考思想

Framework 并非重新发明软件工程。

而是借鉴已有优秀设计。

Linux：

Everything is File。

强调统一抽象。

Git：

Everything is Commit。

强调历史可追溯。

Spring：

IOC。

强调统一管理。

Gradle：

Build as Code。

强调配置即代码。

Claude Code：

Project Context。

强调 AI 理解项目。

Framework 希望吸收这些思想。

结合 AI Native 开发。

形成新的工程实践。

------

# 十六、MVP 实施建议

当前阶段建议完成以下内容。

Workspace：

建立团队基础目录。

Architecture：

维护团队架构文档。

.ai：

建立统一项目 Context。

Contract：

定义项目定位与能力。

External：

建立项目依赖。

Workflow：

统一开发流程。

ADR：

记录重大架构决策。

其余能力后续逐步完善。

------

# 十七、不做什么

当前版本不追求：

- 全自动多 Agent
- 自动代码生成平台
- 完整 MCP Framework
- 微服务治理平台
- 自动项目管理
- 自动任务调度

Framework 当前重点只有一个：

帮助 AI 快速理解团队。

------

# 十八、未来方向

未来将持续探索：

- Workspace Agent
- Project Agent
- Team Memory
- Knowledge Bus
- Capability Registry
- Requirement Engine
- AI 自动需求拆分
- AI 自动架构分析
- AI 自动跨项目协同
- AI 自动更新 Context

这些能力将在真实项目中持续验证。

------

# 十九、总结

AI Native Team Framework 并不是新的开发框架。

它是一套面向 AI 的软件工程基础设施。

Workspace 提供团队视角。

Project Identity 提供项目身份。

Project Contract 定义项目能力。

`.ai` 提供项目上下文。

Project Agent 代表项目参与协同。

Knowledge Flow 连接整个团队。

Framework 的最终目标不是替代开发者，而是让 AI 能够像团队成员一样理解项目、参与协作，并随着团队不断成长。

------

# Open Questions

当前仍未确定的问题：

- Workspace 是否最终独立为一个项目。
- Project Agent 是否需要真实运行的 Agent。
- Contract 是否采用统一 Schema。
- Capability 是否需要注册中心。
- Requirement 是否采用事件驱动。
- Team Memory 如何自动维护。
- Knowledge Flow 是否需要消息总线。
- 多个 AI 如何共享统一上下文。
- 团队规范如何自动同步到所有项目。
- AI 如何自动维护 `.ai` 目录。

以上问题将在后续版本中持续实验、验证和演进。