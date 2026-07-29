# AI Native Team Framework

版本：v1.0

------------------------------------------------------------------------

# 1. 文档目的

AI Native Team Framework 定义 AI 原生团队环境下，多人、多项目、多 AI
协作者之间的组织方式。

它解决的问题：

> 当 AI 从个人开发助手演进为团队研发基础设施时，团队如何组织
> Context、Workflow 和 AI 能力。

本文件建立在：

-   01_AI_CONTEXT_STRUCTURE.md（Context Layer）
-   02_AI_NATIVE_PROJECT_WORKFLOW.md（Workflow Layer）

之上。

核心目标：

> 让团队中的人、AI、项目知识形成持续协作和进化体系。

------------------------------------------------------------------------

# 2. AI Native Team 三层关系

                        Team Collaboration Layer

                        03_AI_NATIVE_TEAM_FRAMEWORK

                                  ↑

                        Project Workflow Layer

                        02_AI_NATIVE_PROJECT_WORKFLOW

                                  ↑

                        Project Context Layer

                        01_AI_CONTEXT_STRUCTURE

关系：

-   Context 解决 AI 理解什么
-   Workflow 解决 AI 如何工作
-   Team Framework 解决多个 AI 和人如何协作

------------------------------------------------------------------------

# 3. 团队核心模型

传统团队：

    Person
     |
    Code
     |
    Project

AI Native Team：

                    Human Team

                        |
                        |

            +-----------+-----------+

            |                       |

        AI Workers             Knowledge Base

            |                       |

            +-----------+-----------+

                        |

                  Project Systems

团队资产不再只有代码。

还包括：

-   项目 Context
-   技术决策
-   工作流程
-   AI Skill
-   Agent 能力
-   工程经验

------------------------------------------------------------------------

# 4. Team Context Architecture

## 4.1 Context 分层

    .ai/

    ├── organization/
    │
    │   ├── engineering_rules.md
    │   ├── coding_standard.md
    │   └── security_policy.md
    │
    ├── projects/
    │
    │   ├── project-a/
    │   │
    │   └── project-b/
    │
    ├── roles/
    │
    │   ├── architect.md
    │   ├── developer.md
    │   ├── reviewer.md
    │   └── tester.md
    │
    └── knowledge/

        ├── decisions/
        ├── solutions/
        └── lessons/

------------------------------------------------------------------------

# 5. Context Ownership

## Organization Context

团队级共享。

内容：

-   编码规范
-   技术原则
-   安全规则
-   发布流程

所有项目继承。

------------------------------------------------------------------------

## Project Context

项目级上下文。

内容：

-   项目目标
-   架构设计
-   模块说明
-   技术限制
-   当前状态

项目独立维护。

------------------------------------------------------------------------

## Role Context

角色级上下文。

定义：

> 某类 AI 工作时应该具备的职责和判断方式。

例如：

Architect：

-   关注架构演进
-   评估技术风险
-   维护设计一致性

Reviewer：

-   检查代码质量
-   发现潜在问题
-   提供改进建议

------------------------------------------------------------------------

# 6. AI Role System

AI 不应该只是一个聊天机器人。

应该成为不同职责的工程角色。

------------------------------------------------------------------------

## Architect Agent

职责：

-   系统设计
-   技术选型
-   架构评审

输入：

-   Project Context
-   Requirements

输出：

-   Architecture Decision Record
-   Design Proposal

------------------------------------------------------------------------

## Developer Agent

职责：

-   编码实现
-   调试
-   重构

输入：

-   Task
-   Context
-   Workflow

输出：

-   Code
-   Test
-   Documentation

------------------------------------------------------------------------

## Reviewer Agent

职责：

-   Code Review
-   Quality Analysis
-   Risk Detection

输出：

-   Review Report
-   Improvement Suggestions

------------------------------------------------------------------------

## Tester Agent

职责：

-   测试设计
-   自动化验证
-   缺陷分析

------------------------------------------------------------------------

# 7. Human + AI Collaboration Model

不是：

    Human -> AI -> Code

而是：

    Human

     ↓

    Define Goal

     ↓

    AI Team

     ↓

    Analysis
    Design
    Implementation
    Review
    Testing

     ↓

    Human Decision

     ↓

    Knowledge Update

人负责：

-   目标
-   判断
-   决策
-   价值选择

AI负责：

-   分析
-   执行
-   检查
-   知识整理

------------------------------------------------------------------------

# 8. Multi Project Collaboration

多个项目之间：

                  Organization Context

                           |

            +--------------+--------------+

            |              |              |

        Project A      Project B      Project C


            |              |              |

        Project Knowledge Sharing

共享：

-   通用技术经验
-   工程规范
-   最佳实践

隔离：

-   业务逻辑
-   私有决策
-   项目状态

------------------------------------------------------------------------

# 9. Agent Collaboration

复杂任务：

    User Request

          |

    Planner Agent

          |

    +-----+-----+-----+

    |           |      |

    Architect Developer Reviewer

    |           |      |

    +-----+-----+-----+

          |

    Final Result

Agent 不直接替代团队。

而是形成虚拟工程团队。

------------------------------------------------------------------------

# 10. Skill System

Skill 是 AI 的能力模块。

区别：

Prompt：

> 告诉 AI 怎么回答

Skill：

> 定义 AI 怎么完成工作

例如：

    skills/

    ├── code-review/
    ├── architecture-design/
    ├── database-design/
    ├── testing/
    └── documentation/

Skill 包含：

-   工作流程
-   输入要求
-   输出格式
-   检查标准

------------------------------------------------------------------------

# 11. Knowledge Evolution

AI Native Team 的核心资产：

    Task

     ↓

    Solution

     ↓

    Decision

     ↓

    Knowledge

     ↓

    Future Context

每次研发活动都应该产生：

-   新知识
-   新规则
-   新 Skill

------------------------------------------------------------------------

# 12. Team AI Operating System

最终形态：

                    AI Native Team OS


            Context Management

                    +

            Workflow Management

                    +

            Agent Coordination

                    +

            Knowledge Evolution

团队拥有的不只是 AI 工具。

而是一套 AI 原生研发操作系统。

------------------------------------------------------------------------

# 13. 实施路线

## Phase 1

建立团队 Context：

-   organization context
-   project context
-   role context

## Phase 2

建立 AI Workflow：

-   requirement
-   development
-   review
-   release

## Phase 3

建立 Agent 协作：

-   role agents
-   skill system
-   automated workflows

## Phase 4

建立知识进化：

-   automatic learning
-   context update
-   engineering intelligence

------------------------------------------------------------------------

# 14. 总结

AI Native Team Framework 的目标：

> 让团队从"使用 AI 工具开发"，进化到"构建 AI 协作研发系统"。

未来的软件团队核心竞争力：

不是拥有更多开发人员。

而是拥有：

-   更好的 Context
-   更成熟的 Workflow
-   更强的 AI Collaboration
-   更持续的 Knowledge Evolution

------------------------------------------------------------------------

End of 03_AI_NATIVE_TEAM_FRAMEWORK.md
