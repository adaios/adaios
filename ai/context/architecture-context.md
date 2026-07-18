# Architecture Context

> 架构级上下文，描述 AdaiOS v0.2 的系统架构、模块边界与关键设计决策。
> 在涉及架构设计或跨模块变更时加载。

## 系统架构总览

AdaiOS 不是 App → Service → Database。

```
Human → Record/File → Kernel → Domain OS → AI Model
```

## adai-core 内部架构（v0.2）

```
com.adaiadai.core/
│
├── kernel/                    ★ Kernel — 操作系统内核层
│   ├── identity/                个人档案（File First）
│   ├── record/                  最小事件单元（File First）
│   ├── timeline/                Record 时间序列投影（自动生成）
│   ├── context/                 上下文引擎（★ 核心能力）
│   │   ├── prompt/              Prompt 规则（上下文构建规则）
│   │   ├── token/               Token 管理（窗口/压缩/优先级）
│   │   └── policy/              上下文策略（权限/范围/时效）
│   ├── memory/                  长期记忆
│   └── knowledge/               结构化知识
│
├── domain/                     ★ Domain OS — 领域能力层
│   ├── trading/                  Trading OS（含研究）
│   ├── life/                     Life OS
│   └── project/                  Project OS
│
├── application/               用例编排、跨域协作、事务边界
├── interfaces/                入站适配（Controller、WebSocket、Listener）
└── infrastructure/            出站适配（依赖倒置）
    ├── database/                JPA Repository
    ├── storage/                 文件存储（本地/云）
    ├── search/                  全文/向量检索
    └── ai/                      AI 模型接入（非业务层）
        ├── llm/                 LLM 客户端
        ├── router/              模型路由
        └── provider/            供应商适配
```

### 分层依赖规则

1. **单向依赖**：`interfaces → application → {kernel, domain} ← infrastructure`
2. **Kernel 内部流水线**：`Record → Timeline → Context → Memory/Knowledge`
3. **Domain 之间不直接依赖**：跨域协作通过 `application` 层编排
4. **Domain/Kernel 定义接口**：infrastructure 实现这些接口（依赖倒置）
5. **AI 不是业务层**：LLM 调用在 `infrastructure/ai`，Prompt 管理在 `kernel/context/prompt`

## 两类 Domain

### Kernel Domain（系统级 — 所有 Domain OS 共享）

| 包 | 职责 | 存储策略 | 说明 |
|----|------|---------|------|
| identity | 个人档案 | File First | 用户静态信息、偏好、AI 协作规则 |
| record | 最小事件单元 | File First | 一切上层能力的事实基础 |
| timeline | Record 时间投影 | 自动生成 | 不独立存储，由 Record 派生 |
| context | ★ 上下文引擎 | 动态组合 | Kernel 核心能力 |
| memory | 长期记忆 | File + DB | 跨会话持久化 |
| knowledge | 结构化知识 | File + DB | 从 Record 提炼的知识资产 |

### Domain OS（业务级 — 挂载在 Kernel 上）

| 包 | 领域 | 概念文档 |
|----|------|---------|
| trading | Trading OS（含研究） | `domains/trading-os/` |
| life | Life OS | `domains/life-os/` |
| project | Project OS | `domains/project-os/` |

## Context Engine

Context 不是 AI 辅助模块，是 Kernel 核心能力。

### 工作方式

```
用户提问 → Context Engine
  ├── 读取 Identity（谁在问）
  ├── 读取 Timeline（最近发生了什么）
  ├── 读取 Memory（历史相关记忆）
  ├── 读取 Knowledge（相关结构化知识）
  ├── 读取当前 Domain 状态
  └── 组合为 Context Package → 提供给 AI
```

### 示例："我的交易问题是什么？"

Context Engine 组合：
```
Identity（风险偏好、投资风格）
  + Timeline（最近交易记录）
  + Memory（历史复盘记忆）
  + Knowledge（交易规则、策略）
  = Trading Context Package → AI
```

## 关键技术决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 架构风格 | Modular Monolith | 不提前微服务化 |
| AI 定位 | Infrastructure | AI 不是业务层，LLM/路由/适配归 infra |
| Prompt 管理 | Kernel/Context | Prompt 是上下文构建规则，不是 AI 能力 |
| Token 管理 | Kernel/Context | Token 是窗口管理问题，不是 AI 问题 |
| 文件存储 | Source of Truth | 数据库为派生（File First） |
| Timeline | Record 投影 | 不独立存在 |

## 技术选型

| 层面 | 选型 | 版本/说明 |
|------|------|----------|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.x |
| 构建 | Gradle | Kotlin DSL |
| 数据库 | MySQL | JPA（运行态），H2（测试） |
| 仓库 | Monorepo | Gradle 多模块 |

## 相关文档

- [system-architecture.md](../../docs/architecture/system-architecture.md) — 完整架构文档
- `domains/*-os/` — 各 Domain 定义文档
