# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

AdaiOS 不是传统 CRUD 应用，而是一套 Personal AI Operating System。
以 Kernel (Context + Memory + Knowledge) 为核心、个人文件为资产、Domain OS 为能力边界的个人智能系统。

**阅读 VISION.md（必读）**：在做任何决策或写任何代码前，先阅读 `docs/VISION.md`——它定义了 AdaiOS 的核心理念、五层产品架构和工程原则。CLAUDE.md 只记录技术细节，VISION.md 记录"为什么"。

**阅读 `docs/architecture/product-architecture.md`**：了解 AdaiOS 五层产品架构（AI 问答 / 主动推送 / 数字身份 / 通用记录 / 外部信息 / 交易系统反哺），任何新功能必须明确归属哪个层级。

## 工作焦点分离

> 你是 monorepo，但 Claude 的工作焦点始终是你 cd 到的那个子项目。

本项目按焦点分为三个独立子项目 + 一个全局层，**每个子目录有自己的 CLAUDE.md**。你在哪个目录启动 `claude`，它就只看哪个领域：

| 焦点 | 目录 | 启动 | 负责 |
|:----|:-----|:-----|:-----|
| **后端** | `services/adai-core/` | `cd services/adai-core && claude` | Java/Spring Boot，Controller、Context Engine、AI 集成 |
| **前端** | `apps/adai-app/` | `cd apps/adai-app && claude` | Flutter Material 3，卡片状态机、输入栏、主题 |
| **交易知识** | `os/trading-os/` | `cd os/trading-os && claude` | 课程整理、规则提炼、术语融合 |
| **全局** | 根目录 | `claude`（默认） | 架构讨论、文档更新、跨项目协调 |

**在子目录工作时不处理后端/前端/交易知识以外的内容。** 当你在全局根目录更新架构文档时，需要同步检查三个子项目的 CLAUDE.md 和文件是否一致。

## 技术栈

| 层面 | 选型 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.3.x |
| 构建 | Gradle (Kotlin DSL, 多模块) |
| 数据库 | MySQL 8.0 (dev) / H2 (test) |
| 仓库形态 | Monorepo |
| 架构风格 | Modular Monolith |

## 目录结构

```
services/
  adai-core/           # ★ 唯一核心运行时（Java 17 + Spring Boot 3.2）
                        #   独立 Gradle 项目：gradlew / build.gradle.kts / gradle/ / settings.gradle.kts
apps/
  adai-app/            #   Flutter 前端（Web / Android / iOS）
domains/               # Domain OS 领域定义文档
  trading-os/          #   金融交易
  life-os/             #   个人生活（预留）
  project-os/          #   项目管理（预留）
os/                    # Domain OS 知识资产（File First）
  trading-os/          #   交易系统知识库（File First，有独立 CLAUDE.md 和工作流）
data/                  # 个人数据资产（File First，Git 追踪）
  identity/            #   个人档案
  records/             #   原始记录（按年月组织）
  memory/              #   AI 理解沉淀
  trading/             #   持仓数据
ai/                    # AI 上下文模板
  context/             #   project / architecture / developer 上下文模板
  prompts/             #   提示词模板（预留）
  workflows/           #   AI 工作流定义（预留）
infra/                 # 基础设施
  docker-compose.yml
docs/                  # 项目文档
  VISION.md            #   项目愿景与核心理念（必读）
  architecture/        #   架构文档
scripts/               # 开发脚本（预留）
```

## adai-core 架构（根包 `com.adaiadai.core`）

```
com.adaiadai.core/
├── kernel/                     ★ Kernel — 操作系统内核层
│   ├── identity/                 个人档案（静态偏好、AI 协作规则）
│   ├── record/                   最小个人事件单元（ContentRecord / RecordRepository）
│   ├── timeline/                 Record 的时间序列投影（TimelineEntry / TimelineProjection）
│   ├── context/                  ★ Context Engine（核心能力）
│   │   ├── IntentRecognizer       中文意图识别（STATEMENT / QUESTION，正则 + AI 兜底）
│   │   └── engine/                上下文引擎（ContextContributor 插件机制）
│   │       └── ContextContributor  接口 → Domain OS 实现
│   │       └── ContextPackage      上下文数据包
│   ├── memory/                   个人记忆（Memory / MemoryService / MemorySummary）
│   └── knowledge/                结构化知识资产（预留）
│
├── domain/                     ★ Domain OS — 领域能力层
│   ├── trading/                  金融交易 ✓（TradeRecord / Position / PortfolioSnapshot / TradingContextContributor）
│   ├── life/                     个人生活管理（预留）
│   └── project/                  项目管理（预留）
│
├── application/                应用层 — 用例编排、意图分流
│   ├── RecordFlowAppService     MVP 原闭环（当前未使用，STATEMENT 不走 AI 后降为存档）
│   ├── QuestionAppService        问句处理：ContextEngine → AI 回答 + 摘要 + 标签
│   ├── FeedAppService            时间线 Feed 构造（日志 + 问答 + 推送）
│   ├── TimelineAppService        时间线查询
│   ├── BriefAppService           今日概览摘要
│   └── TradingAppService         交易领域用例（持仓查询等）
│
├── interfaces/                 入站适配层 — Controller
│   ├── RecordController         POST /api/v1/records（统一入口，自动分流 STATEMENT / QUESTION）
│   ├── FeedController           GET  /api/v1/feed（时间线 Feed）
│   ├── TimelineController       GET  /api/v1/timeline
│   ├── BriefController          GET  /api/v1/brief（今日概览）
│   ├── MemoryController         GET  /api/v1/memory
│   └── TradingController        GET  /api/v1/trading/*
│
└── infrastructure/             出站适配层 — 依赖倒置
    ├── WebConfig                 CORS 跨域配置
    ├── storage/                  文件存储（FileStorage / LocalFileStorage / StorageException）
    │   ├── RecordFileRepository   Record 文件读写
    │   ├── IdentityFileRepository Identity 文件读写
    │   └── PositionFileRepository 持仓文件读写
    ├── database/                 数据库访问（预留，Phase 2）
    ├── search/                   搜索（预留）
    └── ai/                       ★ AI 模型接入（非业务层）
        ├── llm/                   LLM 客户端
        │   ├── AiClient           接口（@ConditionalOnProperty 切换）
        │   ├── MockAiClient       模拟（adai.ai.provider=mock）
        │   ├── DeepSeekAiClient   DeepSeek（adai.ai.provider=deepseek）
        │   └── LlmResponseParser  LLM 回复解析
        ├── router/                 模型路由（预留）
        └── provider/               供应商适配（预留）
```

**分层依赖规则：**
- `interfaces → application → domain/kernel ← infrastructure`
- Kernel 内的 Record → Timeline → Context → Memory 是数据流水线
- Domain 之间**不允许**直接依赖；跨域协作通过 `application` 层编排
- `infrastructure` 实现 `kernel` 和 `domain` 层定义的接口（依赖倒置）

## 架构原则

1. **AdaiOS 不是 App → Service → Database** — 而是 Human → Record/File → Kernel (Context + Memory + Knowledge) → Domain OS → AI Model
2. **Context 是内核能力，不是 AI 辅助模块** — Context Engine 在 Kernel 中，负责组合上下文 Package 提供给 AI
3. **AI 不是业务层，是基础设施** — LLM 调用归 `infrastructure/ai`，Prompt 管理归 `kernel/context/prompt`
4. **两类 Domain** — Kernel Domain (identity/record/timeline/context/memory/knowledge) 是所有 Domain OS 共享的系统域；Domain OS (trading/life/project) 是挂载其上的业务域

## 最高设计原则

### 适用范围说明

AdaiOS 采用 **File First** 原则，但不同区域适用程度不同：

| 区域 | 适用 | 说明 |
|:----|:----:|:-----|
| `os/`（Domain OS 知识资产） | **File First** | 知识以 Markdown 格式文件存在，Git 统一管理，独立工作流 |
| `data/`（个人数据资产） | **File First** | records / memory / identity 按年月组织为文件 |
| `services/adai-core/` | **Code Only** | Java/Spring Boot 工程，非知识资产，不用文件存储知识 |
| `apps/adai-app/` | **Code Only** | Flutter 前端工程，同样不适用 |

### File First（适用于 os/ 和 data/）

| 原则 | 含义 | 开发问法 |
|------|------|---------|
| **File First** | `os/` 和 `data/` 下的所有长期知识以文件(Markdown + 目录)形式存在，Git 可管理、AI 可直接读取 | "数据最终以什么文件格式沉淀？" |
| **Database Second** | 文件是 Source of Truth，数据库为查询/搜索/同步性能而存在 | "这个表能根据文件重建吗？" |
| **Context Always** | 任何模块通过 Context Engine 暴露能力，不直接暴露数据库 | "这个模块的 Context Package 是什么？" |

详见 `docs/VISION.md` §3.5。

## 开发规则

- **不提前微服务化** — Modular Monolith 是默认架构。当 Domain OS 满足独立生命周期、独立数据边界、独立部署需求、多人维护四条时再拆分。
- **不混合代码和知识** — 代码仓库只放代码、配置、构建脚本。Prompt 模板归 `kernel/context/prompt`。
- **新能力必须明确所属 Domain** — 先回答：属于 Kernel 还是 Domain OS？找不到归属时先讨论架构。
- **优先设计数据流** — 先明确：Record 文件格式 → Timeline 投影 → Context 组合 → Memory 沉淀，再写代码。
- **提交前确认根包** — 所有 Java 代码在 `com.adaiadai.core` 下。
- **os/ 目录下的项目保持独立工作流** — 每个 `os/*/` 项目有独立的 `CLAUDE.md`、独立的工作流和目录规则。AdaiOS mono repo 只是存放它们的地方，不干涉其内部流程。它们不依赖 adai-core 的代码，adai-core 通过文件系统只读读取它们产出的知识资产。**Git 统一管理，工作焦点各自独立**。| 区域 | 工作位置 | CLAUDE.md | Git |
|:----|:---------|:----------|:---:|
| `os/trading-os/` | `cd os/trading-os && claude` | 专注交易知识工程 | 统一在根仓库 |
| `services/adai-core/` | `cd services/adai-core && claude` | 专注 Java 后端 | 统一在根仓库 |
| `apps/adai-app/` | `cd apps/adai-app && claude` | 专注 Flutter 前端 | 统一在根仓库 |
| 全局 | 根目录 | 全局架构原则 + 五层产品 | 根仓库 |
- **入口统一，后台分流** — `POST /api/v1/records` 是所有输入的单一入口，通过 `IntentRecognizer` 自动分流到 log / question，App 不感知

## 开发工作流

采用 **"文档先行，确认后再写代码"** 的开发流程，减少往返、提高确定性。

### 流程

```
1. RFC / API Spec / UI Flow  →  写文档（描述做什么、为什么、长什么样）
2. 确认                        →  用户审阅文档，点头了再继续
3. 实现                        →  写代码（后端测试 + 前端构建）
4. 验证                        →  跑测试 + 构建通过即完成
```

### 三种文档类型

| 类型 | 文件名 | 长度 | 适用场景 | 位置 |
|:----|:------|:----:|:---------|:----|
| **API Spec** | `docs/architecture/api-spec.md` | 1页 | 前后端接口对接 | 追加到 `api-spec.md` |
| **UI Flow** | `rfc/YYYYMMDD-topic.md` | 半页-1页 | 新交互/新页面 | `docs/rfc/` 目录 |
| **RFC** | `rfc/YYYYMMDD-topic.md` | 3-5句 | 新功能、架构选型 | `docs/rfc/` 目录 |

### 规则

- 后端新 API → 先写 API Spec（URL、入参、出参）→ 确认 → 写测试 → 实现
- 前端新 UI → 先画 UI Flow（卡片状态流转、低配文字图）→ 确认 → 写 Flutter
- Bug 修复 → 不需要文档，直接修
- 纯逻辑改动（加测试、重构）→ 不需要文档，直接修

## 构建与常用命令

```bash
# ── 构建 ───────────────────────────────────────
cd services/adai-core && ./gradlew build -x test          # 编译（跳过测试）
cd services/adai-core && ./gradlew build                  # 编译 + 测试

# ── 测试 ───────────────────────────────────────
cd services/adai-core && ./gradlew test                   # 运行全部测试
cd services/adai-core && ./gradlew test --tests "*ClassName*"   # 单个测试类
cd services/adai-core && ./gradlew test --tests "*ClassName.methodName"  # 单个方法

# ── 运行 ───────────────────────────────────────
# Mock 模式（默认）:
cd services/adai-core && ./gradlew bootRun

# DeepSeek 模式:
$env:ADAI_AI_PROVIDER="deepseek"; $env:DEEPSEEK_API_KEY="sk-xxx"; cd services/adai-core && ./gradlew bootRun

# ── Flutter ────────────────────────────────────
cd apps/adai-app && flutter run -d chrome          # Web
cd apps/adai-app && flutter run -d android         # Android

# ── 依赖分析 ───────────────────────────────────
cd services/adai-core && ./gradlew dependencies           # 查看依赖树
```

> **零数据库启动**：MVP 阶段不需要 MySQL。所有数据通过 File First 存储到 `data/` 目录。

## 代码约定

- **Java 17 特性**：优先使用 Record、Sealed Class、Pattern Matching、Text Block
- **注入方式**：禁止 `@Autowired` 字段注入，统一 Constructor Injection
- **日志**：SLF4J + Lombok `@Slf4j`
- **异常**：继承 `RuntimeException` 的业务异常，在 Domain 内定义
- **Module 命名**：小写 kebab-case
- **Package 根**：`com.adaiadai.core`

## 相关文档

- `docs/VISION.md` — ⚡ 项目愿景与核心理念（必读）
- `docs/architecture/product-architecture.md` — 五层产品架构（必读）
- `docs/architecture/system-architecture.md` — 系统架构细节
- `domains/*-os/` — 各 Domain 的职责、概念、工作流
- `ai/context/` — AI Context 模板
