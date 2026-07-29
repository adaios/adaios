# 04_AI_NATIVE_AGENT_FRAMEWORK

版本：v1.0

------------------------------------------------------------------------

# 1. 文档目的

AI Native Agent Framework 定义 AI Agent 在软件工程体系中的设计方式。

它解决的问题：

> 当 AI
> 不再只是聊天助手，而成为研发流程中的执行角色时，如何设计、管理和协作
> Agent。

本文件关注：

-   Agent 角色设计
-   Skill 能力封装
-   Tool 工具调用
-   MCP 外部能力连接
-   Agent 与 Context、Knowledge 的关系

------------------------------------------------------------------------

# 2. Agent 基本模型

一个工程 Agent 由以下部分组成：

    Agent

    ├── Role
    ├── Goal
    ├── Context
    ├── Skill
    ├── Tool
    ├── Memory
    └── Output

## Role

定义 Agent 身份。

例如：

-   Architect Agent
-   Developer Agent
-   Reviewer Agent
-   Tester Agent

------------------------------------------------------------------------

## Goal

定义 Agent 目标。

例如：

Developer Agent：

> 根据需求和架构约束，实现可靠、可维护的软件代码。

------------------------------------------------------------------------

# 3. Agent 与传统开发角色

传统：

    产品经理
        |
    架构师
        |
    开发工程师
        |
    测试工程师

AI Native：

    Human

     |
     +-- Product Agent

     |
     +-- Architect Agent

     |
     +-- Developer Agent

     |
     +-- Review Agent

     |
     +-- Test Agent

AI Agent 不是替代角色，而是扩展角色能力。

------------------------------------------------------------------------

# 4. Skill 设计

Skill 是 Agent 的专业能力模块。

例如：

    Developer Agent

    Skills:

    - Spring Boot Development
    - Flutter Development
    - Database Design
    - Unit Test
    - Code Refactoring

Skill 应该：

-   单一职责
-   可复用
-   可组合
-   可测试

------------------------------------------------------------------------

# 5. Tool 与 MCP

## Tool

Tool 是 Agent 可以调用的具体能力。

例如：

    Git
    File System
    Terminal
    Database
    API

------------------------------------------------------------------------

## MCP

MCP 是 Agent 连接外部系统的标准协议。

例如：

    Agent

    ↓

    MCP Server

    ↓

    GitHub
    Database
    Documentation
    Issue Tracker

MCP 解决：

> Agent 如何安全、标准化地访问外部能力。

------------------------------------------------------------------------

# 6. Agent 工作流程

标准流程：

    Receive Task

    ↓

    Understand Context

    ↓

    Analyze

    ↓

    Plan

    ↓

    Execute

    ↓

    Verify

    ↓

    Report

禁止：

    Receive Task

    ↓

    直接修改代码

------------------------------------------------------------------------

# 7. Agent 必须具备的工程能力

## Impact Analysis

修改前分析：

-   影响模块
-   数据流
-   API
-   测试范围

## Validation

修改后验证：

-   单元测试
-   场景测试
-   回归测试

## Documentation Update

代码变化同步：

-   Context
-   Knowledge
-   Decision

------------------------------------------------------------------------

# 8. Agent 与其他层关系

    Context Structure

    提供:
    项目理解


    ↓

    Agent Framework

    负责:
    执行能力


    ↓

    Knowledge System

    负责:
    经验沉淀


    ↓

    Engineering Standard

    负责:
    质量约束

------------------------------------------------------------------------

# 9. 核心原则

1.  Agent 不是聊天机器人，而是工程角色。

2.  Skill 不描述回答方式，而描述工作能力。

3.  Agent 执行必须基于 Context。

4.  Agent 输出必须可验证。

5.  Agent 产生的重要经验必须进入 Knowledge System。
