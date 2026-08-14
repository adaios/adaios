# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

AdaiOS 不是传统 CRUD 应用，而是一套 Personal AI Operating System。
以 Kernel (Context + Memory + Knowledge) 为核心、个人文件为资产、Domain OS 为能力边界的个人智能系统。

**阅读 VISION.md（必读）**：在做任何决策或写任何代码前，先阅读 `docs/VISION.md`——它定义了 AdaiOS 的核心理念、五层产品架构和工程原则。CLAUDE.md 只记录技术细节，VISION.md 记录"为什么"。

**核心理念**：[`docs/VISION.md`](docs/VISION.md) — 项目愿景、核心理念、五层产品架构（唯一理念真相源）。

**文档入口**：[`docs/README.md`](docs/README.md) — 所有文档的索引入口，按"必读 → 架构 → 功能 → API → 决策 → 部署"分层组织。

**阅读 `docs/architecture/product-architecture.md`**：了解 AdaiOS 五层产品架构（AI 问答 / 主动推送 / 数字身份 / 通用记录 / 外部信息 / 交易系统反哺），任何新功能必须明确归属哪个层级。

## 工作焦点分离

> 你是 monorepo，但 Claude 的工作焦点始终是你 cd 到的那个子项目。

本项目按焦点分为三个独立子项目 + 一个全局层，**每个子目录有自己的 CLAUDE.md**。你在哪个目录启动 `claude`，它就只看哪个领域：

| 焦点 | 目录 | 启动 | 负责 |
|:----|:-----|:-----|:-----|
| **后端** | `services/adai-core/` | `cd services/adai-core && claude` | Java/Spring Boot，Controller、Context Engine、AI 集成 |
| **前端（移动）** | `apps/adai-app/` | `cd apps/adai-app && claude` | Flutter Material 3，卡片状态机、输入栏、主题 |
| **前端（桌面）** | `apps/adai-web/` | `cd apps/adai-web && claude` | Flutter Web 独立桌面端，两栏壳 + 8 模块桌面形态 |
| **交易知识** | `os/trading-os/` | `cd os/trading-os && claude` | 课程整理、规则提炼、术语融合 |
| **全局** | 根目录 | `claude`（默认） | 架构讨论、文档更新、跨项目协调 |

**在子目录工作时不处理后端/前端/交易知识以外的内容。** 当你在全局根目录更新架构文档时，需要同步检查各子项目的 CLAUDE.md 和文件是否一致。

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
  adai-core/           # ★ 唯一核心运行时（Java 17 + Spring Boot 3.3）
                        #   独立 Gradle 项目：gradlew / build.gradle.kts / gradle/ / settings.gradle.kts
apps/
  adai-app/            #   Flutter 前端（Web / Android / iOS）
os/                    # Domain OS（File First：知识资产 + 领域定义）
  trading-os/          #   交易系统知识库（有独立 CLAUDE.md 和工作流；definition/ 为领域定义）
  life-os/             #   个人生活（骨架已建立，11-context/ 交付层就绪；definition/ 为领域定义）
  project-os/          #   项目管理（骨架已建立，含 git log 自举 + Status API；definition/ 为领域定义）
data/                  # 个人数据资产（File First；除 project/ 与 identity sample 外均 gitignore 保护隐私）
  identity/            #   个人档案
  records/             #   原始记录（按年月组织）
  memory/              #   AI 理解沉淀
  trading/             #   持仓数据
  project/             #   项目任务数据
  index/               #   标签索引
ai/                    # AI 上下文模板
  context/             #   project / architecture / developer 上下文模板
infra/                 # 基础设施
  docker-compose.yml
docs/                  # 项目文档
  VISION.md            #   项目愿景与核心理念（必读）
  architecture/        #   架构文档
  ideas/               #   想法/方案归档区（未定型但有价值，正式入口）
apps/
  adai-app/            #   Flutter 前端（Web / Android / iOS）
    scripts/             # 构建/部署脚本
      serve_web.sh       #   Flutter Web 构建 + 本地 CanvasKit 补丁 + 启动
  adai-web/            #   Flutter 桌面端（Web 独立工程，两套 UI 非适配，参考元宝电脑端）
    scripts/
      serve_web.sh     #   Flutter Web 构建 + 本地 CanvasKit 补丁 + 启动（:8082）
  adai-admin/          #   Flutter 管理端（产品后台：账号/内容/数据/系统/知识管理，类企业管理系统，独立体系）
    scripts/
      serve_web.sh     #   Flutter Web 构建 + 本地 CanvasKit 补丁 + 启动（:8083）
```

## adai-core 架构（根包 `com.adaiadai.core`）

```
com.adaiadai.core/
├── kernel/                     ★ Kernel — 操作系统内核层
│   ├── identity/                 个人档案（静态偏好、AI 协作规则）
│   ├── record/                   最小个人事件单元（ContentRecord / CardRecord / RecordRepository / CardRepository 端口）
│   ├── timeline/                 Record 的时间序列投影（TimelineEntry / TimelineProjection）
│   ├── market/                   行情数据源（MarketDataSource / TencentMarketDataSource / MarketData）
│   ├── ai/                       AI 端口（AiClient / AiUnderstanding——实现在 infrastructure/ai，#22 依赖倒置）
│   ├── storage/                  存储端口（FileStorage——实现 LocalFileStorage 归 infrastructure/storage）
│   ├── context/                  ★ Context Engine（核心能力）
│   │   ├── IntentRecognizer       中文意图识别（STATEMENT / QUESTION，纯 AI，失败抛异常）
│   │   ├── engine/                上下文引擎（ContextContributor 插件机制）
│   │   │   ├── ContextContributor  接口 → Domain OS 实现（isDefault / supports / enrich / globalContext）
│   │   │   ├── TagIndexReader      标签索引只读端口（实现 TagIndexService，#22）
│   │   │   ├── DefaultContextContributor 通用场景回退
│   │   │   └── ContextPackage      上下文数据包
│   │   ├── prompt/                Prompt 模板管理（预留）
│   │   ├── token/                 Token 管理（预留）
│   │   └── policy/                策略管理（预留）
│   ├── memory/                   个人记忆（Memory / MemoryService / MemorySummary）
│   ├── knowledge/                KnowledgeSource 接口 + Trading/Life/Project 三个实现
│   └── search/                   全文搜索（SearchService / SearchResult）
│
├── domain/                     ★ Domain OS — 领域能力层
│   ├── trading/                  金融交易 ✓（TradeRecord / Position / PortfolioSnapshot / TradingContextContributor / MarketContextContributor）
│   ├── life/                     个人生活管理（LifeContextContributor + LifeKnowledgeSource）
│   └── project/                  项目管理（ProjectContextContributor + ProjectKnowledgeSource + Status API）
│
├── application/                应用层 — 用例编排、意图分流
│   ├── RecordFlowAppService     MVP 原闭环（Memory 重建使用）
│   ├── QuestionAppService        问句处理：ContextEngine → AI 回答 + 摘要 + 标签（QUESTION 场景）
│   ├── FeedAppService            时间线 Feed 构造（日志 + 问答 + 推送，earlierCount 实际统计）
│   ├── TimelineAppService        时间线查询
│   ├── BriefAppService           今日概览摘要（含交易活动检测）
│   ├── TradingAppService         交易领域用例（持仓查询、交易记录）
│   ├── TradingReviewAppService   复盘生成（数据聚合 → AI → 文件写入）
│   └── ProjectStatusAppService   项目状态聚合（git log + RFC + Kernel/Domain 状态）
│
├── interfaces/                 入站适配层 — Controller
│   ├── RecordController         POST /api/v1/records（统一入口，自动分流 STATEMENT / QUESTION）
│   ├── ConversationController   POST /api/v1/conversations/end（结束对话总结）
│   ├── FeedController           GET  /api/v1/feed（时间线 Feed）
│   ├── TimelineController       GET  /api/v1/timeline
│   ├── BriefController          GET  /api/v1/brief（今日概览）
│   ├── MemoryController         GET  /api/v1/memory + POST /api/v1/memory/rebuild（重建）
│   ├── IdentityController       GET|PUT  /api/v1/identity（个人档案读写）
│   ├── SearchController         GET  /api/v1/search?q=（全文搜索）
│   ├── TagIndexController       GET  /api/v1/tags（标签统计）
│   ├── TradingController        GET|POST /api/v1/trading/*（持仓+复盘+反哺）
│   ├── CardController           POST /api/v1/cards/migrate（卡片迁移）
│   ├── MediaController          POST/GET /api/v1/records/media（图片记录 + 原图访问）
│   ├── AccountController        GET|POST /api/v1/accounts（账号管理）
│   ├── AdminController          GET /api/v1/admin/**（数据/系统/知识管理）
│   └── ProjectStatusController  GET  /api/v1/project/status（项目状态）
│
└── infrastructure/             出站适配层 — 依赖倒置
    ├── WebConfig                 CORS 跨域配置
    ├── storage/                  文件存储实现（LocalFileStorage / StorageException；FileStorage 端口在 kernel/storage）
    │   ├── RecordFileRepository   Record 文件读写（保存时自动触发标签索引）
    │   ├── IdentityFileRepository Identity 文件读写
    │   ├── PositionFileRepository 持仓文件读写
    │   ├── CardFileRepository     卡片对话文件读写
    │   ├── TradingReviewFileRepository 复盘文件读写（data/trading/reviews/）
    │   ├── CardMigrationService   卡片文件迁移
    │   ├── TagIndexService        标签索引（data/index/tags.json）
    │   └── TagIndexConfig         标签索引初始化配置
    ├── database/                 数据库访问（预留，Phase 2）
    ├── search/                   搜索（SearchService 在 kernel/search/，全文搜索 + 搜索结果 DTO）
    └── ai/                       ★ AI 模型接入（非业务层）
        ├── llm/                   LLM 客户端实现（AiClient 端口在 kernel/ai，#22）
        │   ├── DeepSeekAiClient   DeepSeek（唯一实现）
        │   └── LlmResponseParser  LLM 回复解析
        ├── vision/                视觉理解（多模态 L4）
        │   ├── VisualAiClient      接口
        │   └── GlmVisualAiClient   GLM-4.1V-Thinking-Flash（唯一实现）
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
3. **AI 不是业务层，是基础设施** — LLM 调用归 `infrastructure/ai`，Prompt 管理归 `kernel/context/prompt`（预留）
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

单人开发，按改动量级区分流程，不强制"先写文档"。

### 流程

```
量级         → 流程
──────────────────────────
新增 Domain   → RFC（3-5 句确认方向）→ 实现
新 API       → 直接改代码，api-spec.md 事后保持一致
复杂 UI 交互  → UI Flow（半页）→ 实现
字段增删/重构 → 直接改代码
Bug 修复     → 直接改
```

### 文档规则

| 类型 | 位置 | 何时写 | 说明 |
|:----|:------|:-------|:-----|
| **RFC** | `docs/rfc/` | 新功能/架构选型时 | 3-5 句到 1 页，确认方向 |
| **API Spec** | `docs/architecture/api-spec.md` | 事后同步 | 记录最终接口，不要求在写代码前写 |
| **UI Flow** | `docs/rfc/` | 复杂交互时 | 半页-1 页 ASCII/流程图 |

### 功能落地（/ship）

- `/ship` 开发收尾闭环：测试 → api-spec 同步 → CLAUDE.md 当前焦点更新 → 新文档登记 → 规范提交
- 与 `/review` 配套：ship 保证产出完整（上游），review 检查遗漏（下游）

### 定期审核

- `/review` 触发项目审核（增量默认审 git diff，`--full` 全量）：5 角色（文档/后端/前端/产品UI/知识数据）+ 守护检查，滚动更新 `docs/review/REVIEW.md`
- 守护检查已脚本化：`bash docs/review/guard.sh` 一条命令跑 G1-G7（自动 cd 到仓库根，输出 PASS/HIT），检查点清单 `docs/review/checklists/` 是活文档，每次审核沉淀新检查模式（补清单 + 脚本同步）
- 审核只报告不直接修（P0 数据丢失可与用户确认后修）

### 底线

- **api-spec.md** 必须与代码保持一致（唯一真相源，将来多人协作时就是契约）
- 修改 API 后确认 api-spec.md 已同步
- 重大方向变化走 RFC（因为需要你看一眼确认）

## 构建与常用命令

```bash
# ── 构建 ───────────────────────────────────────
cd services/adai-core && ./gradlew build -x test          # 编译（跳过测试）
cd services/adai-core && ./gradlew build                  # 编译 + 测试

# ── 测试 ───────────────────────────────────────
cd services/adai-core && ./gradlew test                   # 运行全部测试
cd services/adai-core && ./gradlew test --tests "*ClassName*"   # 单个测试类
cd services/adai-core && ./gradlew test --tests "*ClassName.methodName"  # 单个方法

# ── 部署 ───────────────────────────────────────
cd services/adai-core && ./gradlew bootJar
cd services/adai-core && ./deploy.sh 49.235.37.220 build/libs/adai-core-0.0.1-SNAPSHOT.jar   # 部署（scp + 重启 + 验证）
# ⚠️ 部署是外向动作，由你确认后手动触发（脚本只负责上传/重启，见 deploy.sh 头部说明）

# ── 运行 ───────────────────────────────────────
# DeepSeek 模式（默认，需在 .env 配置 DEEPSEEK_API_KEY）：
cd services/adai-core && ./gradlew bootRun

# Mock 模式（无需 API Key，临时测试用）：
# cd services/adai-core && ./gradlew bootRun

# ── Flutter ────────────────────────────────────
cd apps/adai-app && flutter run -d chrome          # Web
cd apps/adai-app && sh scripts/serve_web.sh        # Web（本地补丁 + Python 服务器）
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
- `docs/architecture/product-roadmap.md` — 🚩 产品路线 v1.0.0（唯一蓝图，路线驱动开发，从这里拆任务/确认目标）
- `docs/architecture/product-architecture.md` — 五层产品架构（必读）
- `docs/architecture/system-architecture.md` — 系统架构细节
- `docs/architecture/frontend-reference.md` — 前端统一参考（UI 术语对照 + 布局视觉参考）
- `docs/architecture/api-spec.md` — API 接口契约
- `docs/architecture/data-format-freeze.md` — 📦 v1.0.0 数据格式冻结（data/ 文件格式契约 + 变更规则）
- `os/*-os/definition/` — 各 Domain 的职责、概念、工作流
- `docs/rfc/20260728-project-development-suggestions.md` — 项目发展建议（产品/前端/UI 三方）
- `ai/context/` — AI Context 模板

## 当前焦点（2026-08-02）

> 🚩 **会话锚点：先看 [`docs/architecture/product-roadmap.md`](docs/architecture/product-roadmap.md)** —— 产品唯一蓝图，从这里拆任务、确认目标。以下为本版本即时状态。

### 已完成
- **Phase 1 带图 ask（多图问答，2026-08-14，RFC 讨论后直接落地）** ✅：输入栏附图可 **log 也可 ask**（参考元宝/ChatGPT 心智——附文本发送，后端按意图分流，无模式切换按钮）+ **图片数量上限 3**（选图 `pickMultiImage(limit:)` 剩余额度 + 拍照满 3 拦截 + 预览条 `n/3` 角标 + `debugInjectImages` 同限）；**多图一次问答**——新端点 `POST /records/media/ask-batch`（已上传 1-3 张一次提问 → `VisualAiClient.askMulti` 一个 content 数组多个 image_url → GLM 综合多图回答 → `image_qa` 记录引用全部图片 ID + Q/A 追加首图卡，Feed 刷新后首图卡显示气泡复用 #209 合并链路零新渲染）；**intent 分流与文本记录一致**（Controller 用 `IntentRecognizer` 判定问句/陈述，AI 失败降级问号启发式，陈述直接返回不烧 VLM）；**askMulti 降级兜底**（GLM 多图失败 → 单图首张 ask，不阻塞）；api-spec v3.17 + freeze §2.1 image_qa 单/多图格式登记；后端 **387**（+10：ask-batch 数量校验/分流/降级/落盘）· adai-app **86**（+3：上限角标/ask 触发/分流）全绿；**adai-web 已同步多图 ask（S-1，2026-08-14）**——桌面端 `askBatch` 接入（上传成功图 id 收集 + caption 分流 + 回答 SnackBar + 刷新首图卡气泡）+ 图片上限 3 + `_syncActiveCard` 对齐，adai-web **44** 全绿
- **08-14 概览卡定位 + 图片交互 + 删除残留三问题修复批** ✅：**概览卡（定位：最直观/最有温度的门面，阿呆 08-14 补充）**——1+3 层次（首行问候 18px 加粗 + 最多 3 行内容，前后端 truncate 4）+ 铺满顶部（`width: infinity` 修 Column shrink-wrap 居中）+ **DeepSeek 空内容/超时自动重试 1 次**（`sendAndParse` 重试辅助，偶发返回空内容→brief 降级 2 行根因，日志 09:23 实锤）+ **降级增强**（AI 失败时本地数据拼 📋记录/🧠记忆/☕收尾，不再干巴巴 2 行）+ **主动提示待办**（Brief prompt 注入未完成任务 TODO/DOING 前 3 + 规则「主动提醒」，阿呆「重要信息不提示我」反馈）；**删除残留 P0**——`Memory` 加 `cardId` 字段（canonical + 旧签名兼容构造）+ `deleteByRecordId` 双匹配（recordId/cardId）——08-13 删对话卡 card_1786623111529 但记忆 recordId 指向 rec_xxx、id 分离导致删不掉（日志「Memory not found for deletion」实锤），生产残留 3 记忆 + 3 记录已全清（备份 `_cleanup_20260814/`）；**图片交互**——拍照入口（`+`→拍照，image_picker camera + iOS 相机/相册权限 + `CFBundleLocalizations zh-Hans` 修英文界面）+ 选图统一 image_picker（移除 file_picker，app 瘦身 ~3MB）+ 上传进度条（📤 n/m）+ 恢复输入栏上滑切 World（键盘弹起时仍排除防误触，阿呆「无法到背面主页」反馈——原 #16 只留空白区快速上滑被 ListView 吞掉）+ **AI 回答截断定性**（10:24 回答在「事：」处断——DeepSeek 偶发生成不完整，非保存 bug，出现在 10:25 prompt 历史佐证）；freeze §2.4 记忆格式加 cardId；后端 **377** · adai-app **83** 全绿
- **键盘收起修复（阿呆 08-13 反馈）** ✅：adai-app 主页此前无任何「收起键盘」逻辑——点空白/发送后焦点不释放、键盘一直霸屏遮挡 Feed。修复：壳层 `GestureDetector` 加 `onTap → FocusScope.unfocus`（点击空白收起，子级 onTap 手势竞技场优先不受影响）+ `InputBar._send()` 发送后 `_focusNode.unfocus`；+2 回归测试（发送后收起/点空白收起，`test/input_bar_keyboard_test.dart`）；adai-app **83** 全绿（analyze 无新增 warning）
- **R2 记录↔任务关联（2026-08-13，RFC `20260813-record-task-and-sports-analysis` implemented）** ✅：domain=project 记录自动转任务——方案 B（默认转 + AI `actionable` 挡 + `#备忘`/`#想法` 排除标签手动挡）：`Task` 加可空 `sourceRecordId`（`ProjectFileRepository` 正则 `\s*`→`[ \t]*` 修「空字段贪婪吞下一行」根因，旧任务文件向后兼容）+ `RecordToTaskLinker`（幂等查重 + best-effort 不阻塞记录 + 转任务后 `MemoryService.clearActionable` 清记忆待办——跟踪归任务、记忆只留回顾，方案 A）+ `RecordController.handleStatem` 挂点；api-spec v3.16 + freeze §2.11 + ideas 登记；后端 **374** · adai-app 81 · adai-web 42 全绿。**相机动作分析（A2）阿呆决定搁置**：Phase 1「分析动作」按钮已撤掉，保留 L4 图片上传/追问基础通道
- **REVIEW deep 修复批 P（deep 审核 31 项清 22，2026-08-13）** ✅：**战略** #234 Feed 分页终止口径（双端按核心计数，最旧记录可达，+2 回归测试）；**P1** #235 上传失败重试真重试（占位卡留字节重走 uploadImage）+ #236 记忆页刷新保位 + #237 frontend-reference 虚构端点改真 + #238 api-spec 正文 400/413 同步 + #239 确认 f3ca035 未推送远端；**P2** #240 endpoints inputs.dir / #241 候选括号闭合 / #242 右栏守卫 / #243 http.put 收敛 / #244 全图 errorBuilder / #245 双份渲染去重 / #246 失败提示挂根 ScaffoldMessenger；**P3 14 项**：#247 apiEndpoints 可空显示「未知」/ #248 损坏文件日志 / #249 正则预编译 / #250 413 配置化 / #251 adai-core CLAUDE.md 包树补 kernel/ai+storage / #252 DeepSeek 默认模型 / #253/#254/#255 双端统一 / #256 serve_web config 唯一校验 / #258 SnackBar 双端统一+防连弹 / #259 REVIEW 口径 / #260 README 模型名 / #261 UML TagIndexReader；api-spec v3.15；后端 **362** · adai-app **81** · adai-web **42** 全绿
- **REVIEW 收官批 O（一次清完剩余该做项，2026-08-12）** ✅：**战略** #101 Feed「加载更早」分页（adai-web）+ #103 Timeline/Memory 保活刷新入口（双端）+ #177 多账号切换链路测试（adai-app +10，修复 DualWorldShell ApiService 缓存分裂 bug）+ #179 登记 v1.0.1（用户决策登录体系单独立项）；**P2** #19 RecordFileRepository 优化（findAll 收窄 + findById 直读）+ #22 kernel 反向依赖倒置（FileStorage/AiClient/AiUnderstanding 移 kernel 端口，抽 CardRepository/TagIndexReader 接口）+ #115 Feed 右栏联动 + #228 端点计数单一口径（Gradle endpoints.txt）；**P3 顺手 14 项**：#166 上传 413 + emoji 截断 / #170 待办第二人称 / #202 复盘剥代码块 + 旧数组日期回归 / #231 ai-log systemPrompt / #232 部署文档模型名 / #122 frontend-reference 色值 + API 表 / #125 记忆页跨年年份 / #198/#230 选号页 / #199 时间线图 error / #200 serve_web 三端校验 / #201 userId 溢出 / #203 候选尾换行 + 半角括号 / #229 tagsCache + tooltip / #233 CLAUDE.md 端点表 + freeze §2.13；api-spec v3.14；后端 **362** · adai-app **78** · adai-web **40** 全绿
- **REVIEW 修复批 N（#216/#217/#223，2026-08-12）** ✅：**#216 CardMigration 误判即删/数据淹没**——`parseAsCard` 判定收紧（`type: conversation` 或 body 含「用户：」对话标记，原「含 `## ` 即视为卡片」太宽）；缺 `id` 字段的文件跳过（不再并入 `card_unknown` 被 findAll 合并淹没）+ 3 测试；**#217 rewriteId 锚定 frontmatter**——改写只在首对 `---` 之间（`group(1)` 替换重拼），body 中的 `id:` 行不再被误改 → 双文件复发根除；**#223 adai-core CLAUDE.md os/ 只读例外登记**——补 promote 写 `99-inbox/` 唯一例外说明（K4/K13 漂移修复）；后端 359 全绿
- **顶部摘要优化（阿呆 08-12 反馈，2026-08-12）** ✅：今日概览卡**去绿点前缀**（adai-app/adai-web）——AI 每行 emoji（prompt 已要求）直接展示，消除「• 🌟」双重前缀；行数 3→5（`truncateLines(…,5)` + prompt「max 5 lines」）；降级路径第二行改 💬（去 `• `）；+1 降级测试；api-spec §7 说明同步；后端 356 · adai-app 68 · adai-web 30 全绿
- **REVIEW 修复批 M（#129/#218/#222，2026-08-12）** ✅：**#129 promote 前端入口（战略闭环）**——双端交易页复盘弹窗加「反哺入库」按钮（`promoteReview` API 传 `{}`，成功后展示 #178 message 提示），知识反哺闭环前后端打通（此前只有 adai-admin 手动）；**#218 visual durationMs**——`LoggingVisualAiClient` understand/ask 测真实耗时，不再恒 null（对齐 `LoggingAiClient`）；**#222 问候加中午段**——`greetingForHour` 加 11-13 → 中午好（`greetingEnForHour` midday、`emojiForHour` 🌤️/下午 🌇），12 点不再机械归「下午好」（用户选定方案）；api-spec v3.13；后端 355 · adai-app 68 · adai-web 30 全绿
- **REVIEW P2 修复批 L（#214/#215/#221，2026-08-12）** ✅：**#214 图片追问长度上界**（`MediaRecordAppService.askImage` question 超 500 字符 → 400，防超大 prompt/记录/日志行）+ **#215 available 最小集**（`GET /accounts/available` 改返回 `List<String>` 纯 userId，不再暴露 role/enabled/createdAt——无鉴权端点去 admin 标记枚举面；双端选号页去角色渲染 + 删 `AccountModel` 死代码）+ **#221 问候语降级 emoji 按时段**（`emojiForHour` 凌晨 🌙/早上 ☀️/下午 🌤️/晚上 ✨，不再固定 ☀️ 配深夜好）；api-spec v3.12；后端 355 · adai-app 68 · adai-web 30 全绿
- **AI 日志隐私治理（#210，2026-08-12）** ✅：prompt 全文明文落盘的配套治理（R1 遗留）——**retention** `AiInteractionLogger` 默认保留 30 天（`adai.ai-log.retention-days`），写入时惰性清理（每用户每日一次）过期日志文件，防无限明文堆积（`retentionDays<=0` 关闭）；**读取治理** `GET /admin/ai-logs` 加 `page`/`size`（上限 500，响应带 `total`）+ `date` 早于保留期返回 400（已清理不可查，防扫任意历史明文）；api-spec v3.11；后端 351 全绿
- **数据/隐私加固 + 反哺提示（#227/#213/#178，2026-08-12）** ✅：**#227** 定时重补过滤禁用账号（`RecordRetryService` 加 `.filter(Account::enabled)`，与 MarketAlertService 口径一致；无启用账号不再 fallback "default"——#212 后 default 已迁移移除）+ **#213** `AiTraceCleanupInterceptor` 请求级清理（每个 HTTP 请求 `afterCompletion` 无条件清空 `AiTraceContext` ThreadLocal，消灭 Tomcat 线程池复用下的跨请求残留——漏 set trace 的调用不再把日志落进上一个请求的用户目录）+ **#178 A 档** promote 响应加 `message` 提示「入库候选不自动融入 AI context，需在 trading-os 工作流融合后重建 11-context」（融合本身属 os/ 能力边界，不自动化）；后端 344 测试全绿
- **deep 审核 P1 修复批（A-D + #184，2026-08-12）** ✅：**#184 promote 脱敏**（`sanitizeReviewContent` 生成源替换股数/市值/成本/现价/现金→占位符，标的名保留 + 不误伤大盘指数；候选文件改名 `YYYY-MM-DD_主题.md` + 重写脱敏版；git 历史旧版保留不重写，`78df9f9`）+ **批 A 前端** #204 双 pop（守卫包住闭包 nav.pop）+ #205 firstWhere→indexWhere + 选号 widget 测试 5 个（#177 战略落地）+ **批 B 后端** #206 updatedAt 缺失回退 createdAt + #207 recorded 哨兵（长摘要截断消灭无限重补）+ **批 C 图片追问** #208 active 态原图可见 + #209 Q/A 持久化到图片卡 card 文件 + FeedAppService 合并 turns + #219 waiting 卡死复位 + #220 双端对齐 + **批 D** #211 候选文件名约定 + #212 迁移脚本 default→adai；**相机拍照/视频→动作分析想法登记**（`docs/ideas/20260812-camera-sports-analysis.md`，复用 L4 图片通道，视频留 v2）；后端 340 · adai-app 68 · adai-web 30 全绿
- **R1 AI 交互日志（P1+P2 后端一步到位）** ✅：记录每次 AI 调用入参/响应——回答阿呆"提示词怎么组装的"。**装饰器方案**：`LoggingAiClient`/`LoggingVisualAiClient` 包装 DeepSeek/GLM（`@Primary`），9 处 AI 调用点零改动自动记录；**落盘** `data/{userId}/ai-logs/YYYY/MM/ai-log-{date}.jsonl`（`AiInteractionLogger` + `FileStorage.append` 新增 append 语义，同步锁防并发丢行）；**记录内容** kind/scene/prompt 全文/预估 tokens/耗时/状态/响应摘要 + `AiTraceContext` ThreadLocal 挂载 userId/recordId/cardId/source（Question/RecordUnderstanding/Media/TradingReview/Conversation/Retry/Brief/Intent 8 处调用点，**生产验证后补 brief+intent**——修复前无 trace 的调用 fallback default 造成日志落错用户目录）；**读取端点** `GET /api/v1/admin/ai-logs?userId=&date=`（X-Admin-Token 鉴权）；api-spec §17 + data-format-freeze §2.13 契约登记；管理端可视化页顺延（等日志积累）；后端 340 测试全绿 · **已部署生产并 E2E 验证**（brief 真实调用落 `data/adai/ai-logs/`，userId/source 正确，admin 端点读回 ok，无 token 401）
- **阿呆 08-12 生产反馈修复批（#14/#15/#16）** ✅：**#14 凌晨问候语显示 morning**（`BriefAppService` 时段判断提取 `greetingForHour`/`greetingEnForHour`——凌晨 0-5 → 深夜好/late night，原误归早上好/morning，阿呆 00:33 反馈）+ **#15 继续聊天内容被简化**（`feed_card` 折叠条件加 `!_isActive`——chatting/waiting 态始终显示完整对话，原 active 也被 `_truncateTurns` 折叠成首+末2条且渐隐遮罩误导"外表看着很全"，阿呆 00:43 反馈；adai-web 桌面端不折叠不受影响）+ **#16 输入框上滑误触丢草稿**（移除 InputBar 上滑切世界手势 + 壳层手势记录 `_dragStartY`、起点落在底部 140px 不响应，根治打字上滑误触切 World 丢内容，阿呆 00:44 反馈）；后端 314 · adai-app 63 全绿 · analyze 0 error（`22b5da5`/`c495554`，issue-log v9.0 已登记 + R1 AI 交互日志 / R2 记录↔任务想法入 ideas）
- **生产验收批（CORS 事故修复 + 图片追问 + 改名，2026-08-11）** ✅：**8083 CORS 修复**（`AdminAuthInterceptor` 放行 OPTIONS 预检——8082/8083 调 accounts/admin 端点被 CORS 拦死根因，`70338a2` 已热部署生产验证）+ **图片追问（L4 图片问答）**（`POST /api/v1/records/media/{id}/ask` 重新取图 → GLM-4.1V 看图自然语言回答 → 沉淀 `image_qa` 记录进时间线/搜索；`VisualAiClient.ask` 新方法区别于 understand 的结构化 JSON；adai-app/adai-web 图片卡 ── 提问 ── 追问，回答以气泡追加在卡下，`6a26208`）+ **adai-admin 改名「阿呆控制台」**（顶栏 + title + index.html 三处，`d020700`）；api-spec v3.9（后端 313 · adai-app 61 · adai-web 30 · adai-admin 31 全绿；**生产部署待用户确认批次**）
- **多账号 deep 审核修复（批 K，v1.0.0 发布前）** ✅：REVIEW deep 审核 16 项修复——**P1** #180 freeze 契约同步（intent 落盘声明 + 单用户路径 adai）+ #181 rebuild 幂等漏聊天首问（RecordController 首问带新 cardId 补写 intent=question + 回归测试）；**P2** #182 前端 default userId 无效化强制选号 + #183 MarketAlert 轮询去硬编码 default（enabled 账号）+ #185 切换防重入 + #186 切换后清 URL ?userId（刷新不再回退）+ #187 端点计数生产恒 0（Gradle 生成 `META-INF/endpoints.txt` 资源 + dev 回退扫源码）+ #188 回写保留用户 tags + #189 persist 先于 summary 落盘 + #190 空态可执行重试；**P3** api-spec 升版 v3.8 / Release Notes 日期待定 / 302→300 / alpha 越界 / 死代码等 7 项；后端 300 · adai-app 60 · adai-web 27 全绿
- **多账号前端选号/切换（v1.0.0 提前）** ✅：产品前端选号进入——后端新增无鉴权 `GET /api/v1/accounts/available`（仅 enabled，`WebConfig` exclude 拦截）+ adai-app World B「切换账号」+ adai-web 底部 `@userId` 点击切换 + 记住上次账号（URL `?userId=` 优先 > 持久化 > 首屏选号）+ 切换重建整树（ValueKey 换 ApiService，缓存清空）；**wasm 白屏修复**：dart2wasm 下 shared_preferences web 插件不注册（`MissingPluginException` 启动白屏）→ `UserStore` 条件导出改 `package:web` localStorage 直读（`user_store_web.dart`，io 端仍 shared_preferences）+ **切换账号崩溃修复**：State context 在 MaterialApp 外 `Navigator.of` 返回 null → `GlobalKey<NavigatorState>` + `navigatorKey` push/pop；api-spec §16 + Release Notes 同步
- **阿呆系统页 CanvasKit 必现崩溃修复** ✅：点击「阿呆系统」release minify 必现 `PictureRecorder` wasm 崩溃（该页首帧 + 路由动画并发 + spinner 无限重绘触发 CanvasKit 绘制密集不稳定，非项目 bug，其他入口同路由动画正常）→ 入口改无动画跳转 + 加载 spinner 换静态占位（`launcher_page.dart` / `project_status_page.dart`，adai-app 60 测试绿）
- **v1.0.0 验证修复（updatedAt 时间基准 + #175 分页 + 复盘生成）** ✅：卡片时间/日期按最后更新 `updatedAt`——跨日续接对话归最后活跃日（`CardFileRepository.findTodayCards` 由按创建目录查改为全量扫 + 按 updatedAt 过滤；`FeedAppService.toCardFeedEntry` 卡片 time/date 用 updatedAt）/ #175 分页 page 0 返回完整 `size` 条最新核心、余数放末页（`FeedAppService.getFeed` 分页改新在前切片，前端 `_loadMore` 顺序自洽零改动）/ 复盘生成走新增 `AiClient.generate` 生成语义（不再复用 understand 的 JSON 摘要 → AI 只回一句话），产出 5 节结构化复盘引用真实规则；World B 时间线页补齐图片缩略图 + 原图（批 2 漏了 TimelinePage）；api-spec + REVIEW + Release Notes 同步（后端 298 · 前端 60 全绿）
- **adai-web 独立桌面端（两套 UI 非适配）** ✅：拆独立工程 `apps/adai-web`（Web，:8082）——两栏壳（左导航 200 + lazy IndexedStack 保活）+ 8 模块桌面原生形态（Feed 对话流 880 + 右上下文栏 / 交易 DataTable / 记忆 master-detail / 时间线月历 / 任务看板 / 项目仪表盘 / 搜索高亮 / 档案两栏）；API 层值复制 + 3 项改进（utf8 解码 / 缓存参数感知 / ApiException）（adai-app 保持 8081 移动端，两套 UI 各做各的）
- **adai-app 即产品入口（砍掉 adai-entry）** ✅：adai-entry 账号选择前门移除——app 直接作为产品入口（交流 + 页面操作一体），adai-admin 定位拨正为独立产品后台（账号/内容/数据/系统/知识，类企业管理系统）；多账号分流仍保留 `?userId=` query 注入 X-User-Id 能力
- **adai-admin 全栈（MD11-16）** ✅：后端账号体系（seed adai + CRUD + 内置保护）+ admin 端点（memory 修正 / data 文件树 / os 知识浏览）+ 前端四模块（账号/数据/系统/知识）接真实 API（`1337b62`/`f9cf6bf`）
- **多账号架构预留（v1.0.0 前置）** ✅：全链路 userId 分层（`data/{userId}/`，Controller `X-User-Id` header → AppService → FileStorage 显式透传）+ 数据迁移脚本（`data/` → `data/default/`）+ .gitignore 通配防隐私裸露 + 多用户隔离测试（FileStorage/Record/TagIndex/Memory 四维）。RFC：`docs/rfc/20260802-multi-account-prep.md`
- **v0.2.0 前端 actionable 闭环 + 行情嵌入** ✅：action 待办卡 + 完成按钮（PATCH done）、memory 页 kind/superseded/待办标记、Feed 分页终止修复（totalToday 只计核心）、L5 大盘行情条 type=market（`a4c584b`/`ca2d4a8`/`7d9b607`）
- **记忆系统进化 Phase 1-5** ✅：kind 类型（`135f671`）+ 主题级合并 superseded（`7e98555`）+ actionable 闭环 + PATCH /memory/{id}/done（`8ef3739`）+ 时效淘汰（`c96e83b`）+ 筛选降噪（`b6a169c`）
- **文档体系精简** ✅：产品路线 v1 文档（`docs/architecture/product-roadmap.md`，唯一蓝图 + 路线驱动开发）+ 文档结构精简（inbox 归位 17 个重复/未定型文件 + frontend-reference 合并 + data-flow 并入 system-architecture + 引用统一，`26f130d`/`ad3f58a`）
- **产品发布版本机制 RFC** ✅：版本号规则 + 发布流程 + Release Notes 模板 + v0.1.0 规划（`docs/rfc/20260801-release-versioning.md`，draft 待确认）
- **记忆系统进化 RFC** ✅：元记忆对比 + 主题合并/actionable 闭环落地方案（`docs/rfc/20260801-memory-system-evolution.md`，draft 待确认）
- **第三批审核修复** ✅：#33 审核路由表补 .claude/**（自审盲区）、#38/#39/#41 文档同步（README 索引 / definition 愿景声明 / data-flow 对齐代码）、#21 ProjectFileRepository 保留手写注释、#23 TradingController conflicts 改解析真实 rules.md（R119/R96）
- **第二批审核修复（代码）** ✅：#24 记忆沉淀断裂（AI 失败降级原文入记忆 + 洞察升级覆盖 + 重补防阻塞）、#12 复盘改走 ContextEngine（trading 场景注入规则/知识/行情）、#14 测试缺口（新增 9 测试，110+ 全绿）
- **审核/交付流程基建** ✅：/review 三档（light 守护+快扫 / --deep / --full）+ 5 角色 + guard.sh 守护脚本化（一条命令跑 G1-G7）+ /ship 收尾闭环（测试→api-spec→文档→提交）
- **docs/ideas 想法归档区** ✅：未定型想法/方案的正式位置，成熟升级 RFC
- **os/ 目录统一** ✅：domains/ 合并入 os/*-os/definition/，消除顶层重复
- **任务系统修复** ✅：ID 加毫秒防冲突、save() 加 synchronized、中文支持、清除 65MB 损坏文件
- **Project OS 使用指南** ✅：`docs/guides/project-os-usage.md`
- **方向 A Phase 1** ✅：行情接入（kernel/market + MarketContextContributor）
  - 腾讯行情 API 拉大盘指数 + 持仓实时价
  - CHAT 模式上下文注入修复（之前全局上下文未发给 DeepSeek）
- **后端接口测试全覆盖** ✅：15 Controller 46 端点全部有接口测试——TradingControllerTest 重写（补 positions/portfolio/trades/复盘/promote 8 端点）+ 新增 ProjectStatus/Card/Search/TagIndex 测试类 + Memory/Record 扩展（dates/count/修正/domain/retry），203→236 测试全绿
- **多模态图片记录（L4）** ✅：图片 → GLM-4.1V-Thinking-Flash 视觉理解 → 文本化进现有闭环（Timeline/Memory/Search 零改动）——`VisualAiClient` 端口（infrastructure/ai/vision/）+ `GlmVisualAiClient` + `POST/GET /api/v1/records/media`（multipart，File First 落 `records/.../media/`）+ FileStorage 字节读写 + 记忆 KIND_INSIGHT（Phase 5 筛选适配）+ VLM 失败降级不丢数据；前端 adai-app/adai-web 输入栏图片上传（256 测试绿 · 26/25 前端测试绿）
- **adai-web 验收交互修复（批1）** ✅：live 验收反馈迭代——图片上传改**输入栏内联多图 + 可选文字**（选多张→横向预览逐张移除→发送逐张上传每张一条记录+记忆、caption 共享；发送按钮变绿提示有图）；ask 卡 waiting 态反馈（spinner +「正在思考…」占位）；简单卡时间戳（badge 类型在前+时间随后左上角）+ 行情红涨绿跌着色（`feed_page.dart` / `desktop_feed_card.dart`，analyze 0 · 25 测试绿）
- **adai-app 同步批1 交互改进** ✅：验收三项同步到移动端——输入栏内联多图+可选文字（`onSendMedia` 逐张上传 caption 共享）、ask waiting「正在思考…」占位、简单卡时间戳+行情红涨绿跌（`input_bar.dart` / `feed_card.dart` / `main_page.dart`，analyze 0 error · 29 测试绿）
- **adai-web 验收批2（每卡日期 + 原图可见）** ✅：后端 `FeedEntry.date`（MM-dd 各构建点）+ `mediaPath`（仅图片记录，`RecordRepository.findMediaPath`）+ `TimelineEntry.mediaPath`；前端双端——普通卡/简单卡显示日期、adai-web 时间竖列两行、图片记录 FeedCard 缩略图 + 点击看原图 Dialog、时间线页（modal/page）缩略图；api-spec 同步（后端 258 测试 · 双端 analyze 0 error · adai-app 31 / adai-web 27 测试绿）
- **adai-app 语音 stub 移除（REVIEW #164）** ✅：砍掉误导性语音入口（可切语音态+长按录音→弹「开发中」），输入栏简化为文字输入 + [+]附件（图片/文件/链接）；**语音移入 v2 方向**（`input_bar.dart`，analyze 0 error · 31 测试绿）
- **adai-app 主轴问题批量修复（批 E）** ✅：#108 故障 vs 无数据（memory/timeline/search/task 4 页错误态+重试按钮）；#113 错误态人话（trading+task）；#102 交易页复盘入口（api_service review 方法 + markdown 复盘弹窗）；#162 Feed push 类型双端映射；#132 移动端交易页红涨绿跌统一；#131/#123 移动端微文案中文化（placeholder / Feed 导航 / 记忆页）（analyze 0 error · adai-app 33 / adai-web 27 测试绿）
- **adai-app 质量锁定批（批 F）** ✅：#117 Feed 状态机 12 个 widget 测试（`feed_state_machine_test.dart` 锁住 ask→waiting→chatting→ended / 追加 / 错误重试 / 删除 / 加载更多 / #100 竞态，ApiService 注入 MockClient 测试性改造）；#123 状态机文案全量中文化（ask·log·end·chat·结束对话，adai-app 零英文 UI 残留）（analyze 0 error · adai-app 45 测试全绿）
- **adai-app 6 页面测试（批 G，#117 剩余）** ✅：`pages_widget_test.dart` 14 测试——memory/timeline/search/trading/task/profile 六页数据渲染 + #108 错误态人话 + 重试按钮（复用 MockClient 基建）（analyze 0 error · adai-app 59 测试全绿）
- **#127 最小封闭鉴权** ✅：admin/accounts 端点 `X-Admin-Token` 拦截（常量时间比较 · 未配置 fail-closed 503）+ CORS `*`→配置化 origin 白名单（默认 localhost:*，生产 `ADAI_ALLOWED_ORIGIN_PATTERNS`）；adai-admin `--dart-define=ADMIN_TOKEN` 注入；4 鉴权测试 + api-spec v3.6（后端 262 · adai-admin 31 全绿）
- **adai-web 桌面残留清理（批 H）** ✅：#102 复盘入口（markdown 弹窗）/ #132 红涨绿亏（A股）/ #161 时间线 type 中文化（13 类映射+兜底）/ #131 桌面文案全量中文化 / #124 CLAUDE.md 端口 8082 / #158 记忆待办完成按钮 / #159 Feed 空态引导 chips / #118 `_check` utf8 / #165 type 硬转换兜底（analyze 0 error · adai-web 27 测试全绿）
- **adai-app 对话体验收尾（批 I）** ✅：#13+#11 card 写入剥离 AI 原始 JSON（`LlmResponseParser.extractNaturalText` 剥离 JSON+代码块围栏，`QuestionAppService` 写卡与返回均用自然语言、空白回退 summary，实时显示=刷新后）/ #148 Feed ai_note 按记录日期归属（`MemoryService.findByRecordIds` 跨日补齐 + `toAiEntry` 用记录时间，重补/升级跨日不错日不丢失）/ MD1 世界切回 Feed 自动刷新（`DualWorldShell` ValueNotifier → `MainPage.refreshTick`，覆盖 admin 记忆重建后 Feed 陈旧，不清 active 态）（后端 287 · 前端 60 测试全绿）
- **v1.0.0 定调 + 发布准备** ✅：**版本定调**——v0.1.0-0.3.0 是内部里程碑，**第一版 = v1.0.0**（首个正式发布）；**数据格式冻结**（`docs/architecture/data-format-freeze.md`：data/ 全部文件格式契约 + 变更规则 + 发布前差异核对项）；**路线图同步 v1.0.0-first**（§二 定调 / §四 里程碑与 v1.0.0 缺口 / §五 标准调整——去掉"跑通≥2版本"前提、Life OS 不设硬门槛、用户体系后端✅前端选号顺延 v1.0.1）；**v1.0.0 Release Notes**（`docs/releases/v1.0.0.md`，覆盖全能力；删 v0.1.0.md 占位）。tag + 部署待用户确认触发
- **P1 清理批（批 J，v1.0.0 核心闭环）** ✅：#144 rebuild 幂等（intent 落盘 `RecordFileRepository` + QuestionAppService 无条件落盘 intent=question + MemoryController 用"summary 已处理 + 降级记忆重跑"过滤 + 处理后落盘标记）/ #147 SELL 未持有与超额报错（`TradingException` + `GlobalExceptionHandler` 400）+ 持仓读改写每用户锁 + 清仓 0 行不落盘 / #106 api-spec portfolio 契约对齐（后端 `PortfolioSnapshot.positionCount` 派生，adai-web 持仓数不再恒 0）/ #112 CANCELLED 任务看板可见（adai-web 第四列）/ #150 apiEndpoints 动态统计（硬编码 21 → 实际 46）+ FeedAppService 移除 BriefAppService 死依赖（后端 293 · adai-web 27 全绿）
- **方向 A Phase 2 行情主动推送** ✅：`MarketAlertService` 交易时段轮询（30min cron，工作日 9-11/13-15）——单日跌≥3% 止损预警 / 涨≥5% 放飞提示 / 跌破成本线风控（阈值配置化 `adai.market.alert.*`）；`MarketSnapshotRepository` 当日签名去重（`market_snapshot.json`，跨日自动重置）+ `MarketPushRepository` 落盘 `trading/pushes/{date}.json`；FeedAppService 按日注入 `type=push` 条目（前端 push 卡片 #162 已支持，零前端改动）；轮询遍历 `default ∪ 启用账号`（防漏当前单用户）；14 新测试（276 全绿）
- **数据冻结 3 项差异处理（v1.0.0 发布前核对）** ✅：freeze #1 持仓手写注释 / #2 档案 body 说明 → 挪入 `data-format-freeze.md`（§2.5/§2.6 手动维护），接受代码归一化，磁盘已对齐契约；freeze #3 账号 `createdAt` 统一 ISO 字符串（`AccountFileRepository` 禁用 `WRITE_DATES_AS_TIMESTAMPS`，读取兼容旧 `[年,月,日]` 数组）+ 磁盘迁移 + 1 新测试（后端 294 全绿）

### 方向进展
| 方向 | Phase | 状态 |
|:-----|:------|:----:|
| B Project OS | Phase 2-3 (RFC跟踪+自举) | ✅ 完成 |
| B Project OS | Phase 4 (前端任务面板) | ✅ 完成 |
| A 行情接入 | Phase 1 (上下文注入) | ✅ 完成 |
| A 行情接入 | Phase 2 (主动推送) | ✅ 完成（2026-08-06）|
| C Life OS | Phase 0-3 | ⏸ 等数据积累 |
| 记忆系统进化 | Phase 1-5 (kind/主题合并/actionable/时效/降噪) | ✅ 完成 |
| v0.2.0 | 前端 actionable 闭环 + L5 行情嵌入 | ✅ 完成（待验收）|
| 多账号预留 | 全链路 userId 分层（v1.0.0 前置） | ✅ 完成（架构预留，功能层 v1.0.0）|
| adai-admin | 规划 RFC 转正（`20260802-adai-admin`，approved）| ✅ 方向确认：v1.0.0 与多账号合并（独立产品后台：账号/内容/数据/系统/知识管理，类企业管理系统）|
| adai-admin 全栈 | MD11-16：账号体系 + admin 端点 + 数据/系统/知识页 + memory 修正 | ✅ 后端 `1337b62` + 前端 `f9cf6bf`（31 测试过，真实 API）|
| adai-app 即入口 | 砍掉 adai-entry，app 直接作为产品入口（交流 + 页面操作一体）| ✅ 已执行（删除 `apps/adai-entry`，根 CLAUDE.md 同步）|
| adai-web 桌面端 | 独立工程两套 UI：两栏壳 + 8 模块桌面形态（Feed/交易/记忆/时间线/任务/项目/搜索/档案）| ✅ 已完成（analyze 0 · 27 测试绿 · web 构建通过）|
| 多模态图片记录 | L4 图片 → GLM-VLM 文本化 → 现有闭环（v0.3.0 目标）| ✅ 前后端完成 · 验收批1/批2 双端已落地（图片内联多图+caption + 每卡日期 + 原图可见）|
| 安全基线 | #127 最小封闭鉴权：admin/accounts 令牌 + CORS 收窄（v1.0.0 前置）| ✅ 完成（用户层 X-User-Id 鉴权留待 v1.0.0 多账号正式开放）|
| adai-web 残留 | 桌面端 REVIEW 残留清理（批 H：#102/#132/#161/#131/#124/#158/#159/#118/#165）| ✅ 完成（analyze 0 · 27 测试绿）|

### 测试状态
- **后端** 387 测试，0 失败（含多用户隔离 5 测试 + **#127 鉴权 4 测试** + **行情推送 14 测试** + **#13/#11 剥离 JSON + #148 跨日记忆 10 测试** + **#144/#147/#106 交易与幂等 6 测试** + **freeze #3 账号 ISO 序列化 1 测试** + **updatedAt 归日 + #175 分页 4 测试** + **多账号选号 available 3 测试** + **图片追问 ask 接口 8 测试** + **CORS 预检回归 1 测试** + **#14 问候语时段边界 1 测试** + **#221 问候语降级 emoji 1 测试** + **#222 问候加中午段 1 测试** + **Brief 降级增强（📋/🧠/☕ 3 断言）1 测试** + **#216 CardMigration 判定收紧 + 缺 id 跳过 3 测试** + **R1 AI 交互日志 20 测试** + **#206/#207 幂等与时间基准 3 测试** + **#209 图片追问持久化 1 测试** + **#184 promote 脱敏 2 测试** + **#227 定时重补过滤禁用账号 2 测试** + **#213 追踪上下文请求级清理 2 测试** + **#210 AI 日志保留期/分页治理 7 测试** + **#202 复盘剥代码块围栏 2 测试 + 旧数组账号日期回归 1 测试** + **R2 记录↔任务：sourceRecordId round-trip + 旧文件兼容 2 测试 + Linker 触发/排除标签/幂等/清待办 8 测试 + MemoryService.clearActionable 2 测试** + **08-14 删除残留：Memory cardId round-trip + 按 cardId 删除 + recordId 路径回归 3 测试** + **Phase 1 带图 ask：ask-batch Controller 分流/数量校验/降级 6 测试 + askImages 落盘/上限/校验 4 测试**；**15 Controller 50 端点接口测试全覆盖** + 多模态 18 测试）
- **前端** adai-app 86（+2 #235/#245 图片上传占位卡重试/成功卡测试 + 2 输入栏键盘收起 + 2 Phase 1 带图 ask-batch 触发/分流 + 1 数量上限角标）· adai-admin 31 · adai-web 42（+2 #234 分页终止口径 / #236 记忆页刷新保位回归），全部 0 失败

### 运行环境
- 后端：`localhost:8080`（DeepSeek 模式 + GLM 视觉——`.env` 需配 `GLM_API_KEY` 才有真 VLM 理解，无 key 时上传降级不丢数据）
- 前端：adai-app `localhost:8081`（移动端入口，Web 形态）· adai-web `localhost:8082`（桌面端入口）· adai-admin `localhost:8083`（产品后台）（均 Flutter Web + CanvasKit 补丁）
- 生产服务器：49.235.37.220
- 数据路径：`data/{userId}/...`（本机账号 = `data/adai/`，default 已迁移移除；测试可用 `data/default/`）

### 关键文档
- `docs/architecture/product-roadmap.md` — 🚩 产品路线 v1.0.0（唯一蓝图，路线驱动开发）
- `docs/architecture/frontend-reference.md` — 前端统一参考（术语对照 + 布局视觉）
- `docs/guides/project-os-usage.md` — Project OS 使用指南
- `docs/rfc/20260802-multi-account-prep.md` — 多账号架构预留 RFC
- `docs/rfc/20260802-multimodal-image-glm.md` — 多模态图片记录 RFC（implemented）
- `docs/rfc/20260730-market-data-and-push.md` — 行情接入 RFC
- `docs/rfc/20260729-development-retrospective.md` — 近期 Bug 复盘
