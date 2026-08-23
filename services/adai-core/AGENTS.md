# AGENTS.md — adai-core

AdaiOS 核心运行时（Java 17 + Spring Boot 3.3.x）。

> 这是 AdaiOS monorepo 的一个子项目。在根目录下有全局 AGENTS.md 和 VISION.md。
> **在本目录工作时，你的上下文限制在 adai-core 后端，不处理前端、交易知识等其他项目。**

---

## 技术栈

| 层面 | 选型 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.3.x |
| 构建 | Gradle (Kotlin DSL，独立项目) |
| 数据库 | MySQL 8.0 (dev) / H2 (test) |
| 架构风格 | Modular Monolith |

## 构建与常用命令

```bash
# ── 编译 ──
./gradlew build -x test          # 编译（跳过测试）
./gradlew build                  # 编译 + 测试

# ── 测试 ──
./gradlew test                   # 全部测试
./gradlew test --tests "*ClassName*"              # 单个测试类
./gradlew test --tests "*ClassName.methodName"    # 单个方法

# ── 运行 ──
./gradlew bootRun                # Mock 模式（默认）

# DeepSeek 模式（需先配置 .env）：
# DEEPSEEK_API_KEY=sk-xxx ./gradlew bootRun

# ── 部署（生产服务器 82.156.111.146） ──
./gradlew bootJar
./deploy.sh 82.156.111.146 build/libs/adai-core-0.0.1-SNAPSHOT.jar
```

## 包结构（根包 `com.adaiadai.core`）

```
com.adaiadai.core/
├── kernel/                     ★ Kernel — 操作系统内核层
│   ├── identity/                 个人档案
│   ├── record/                   最小个人事件单元
│   ├── timeline/                 时间序列投影
│   ├── ai/                       端口（REVIEW #22 依赖倒置：AiClient/AiUnderstanding 在 kernel，实现归 infra）
│   ├── storage/                  端口（REVIEW #22 依赖倒置：FileStorage 在 kernel，实现归 infra/storage）
│   ├── context/                  ★ Context Engine（核心）
│   │   ├── IntentRecognizer       意图识别
│   │   └── engine/                上下文引擎（ContextContributor 插件机制）
│   ├── memory/                   个人记忆
│   ├── plugin/                   插件注册（RFC 20260814 Domain=插件模型：PluginRegistry 映射 + PluginService 按账号 enabledPlugins）
│   └── knowledge/                结构化知识（预留）
│
├── domain/                     ★ Domain OS
│   ├── trading/                  金融交易（TradingContextContributor + MarketContextContributor + 复盘 + 行情载体）
│   │   └── market/                行情数据载体（MarketDataSource 接口 + TencentMarketDataSource——行情服务跟插件走，归 trading 插件域，2026-08-16 G-1 拨正）
│   ├── life/                     个人生活（LifeContextContributor + LifeKnowledgeSource）
│   └── project/                  项目管理（ProjectContextContributor + Task 实体 + TaskRepository）
│
├── application/                应用层 — 用例编排
│   ├── RecordFlowAppService      记录流程
│   ├── QuestionAppService        问答处理
│   ├── FeedAppService            Feed 构造
│   ├── TimelineAppService        时间线查询
│   ├── BriefAppService           今日简报
│   ├── TradingAppService         交易领域用例
│   ├── MarketAlertService        行情异动主动推送（Phase 2：交易时段轮询 → type=push 入 Feed）
│   ├── ProjectStatusAppService   项目状态聚合（Git + RFC + Kernel 组件）
│   ├── ProjectTaskAppService     任务 CRUD 编排
│   └── RecordToTaskLinker        R2 记录↔任务关联：domain=project 记录自动转任务（方案 B 触发 + 幂等 + 清记忆待办）
│
├── interfaces/                 入站适配层（Controller）
└── infrastructure/             出站适配层（实现 kernel/domain 端口，依赖倒置）
    ├── storage/                  文件存储实现（LocalFileStorage 等，端口在 kernel/storage）
    ├── database/                 数据库（预留）
    └── ai/                       AI 模型接入
        ├── llm/                    LLM 客户端（DeepSeekAiClient，端口在 kernel/ai）
        └── vision/                视觉理解（VisualAiClient / GlmVisualAiClient，多模态 L4）
```

## 架构原则

1. **分层依赖规则：** `interfaces → application → domain/kernel ← infrastructure`
2. **Kernel Domain** (identity/record/timeline/context/memory/knowledge) 是所有 Domain OS 共享的系统域
3. **Domain OS** (trading/life/project) 是挂载其上的业务域，之间不允许直接依赖
4. **AI 不是业务层，是基础设施** — LLM 调用归 `infrastructure/ai`
5. **File First** 适用于 `data/` 目录，`services/adai-core/` 本身是 Code Only
6. **Context Engine 是内核能力** — 所有模块通过 ContextContributor 插件暴露能力

## 代码约定

> 代码/文档/协作规范**唯一真相源**：`ai-engineering/assets/conventions.md`（本处只留指针）。

- Java 17 / 注入 / 日志 / 异常 → 见 conventions.md C1-C8
- **测试（adai-core 特有）**：Controller 层用 `@WebMvcTest` + MockBean，Service 层用纯单元测试

## API 端点

| 方法 | 路径 | 用途 |
|:----|:-----|:-----|
| POST | `/api/v1/records` | 统一入口（自动分流 STATEMENT / QUESTION） |
| POST | `/api/v1/records/media` | 图片记录（multipart，GLM-VLM 理解文本化） |
| GET | `/api/v1/records/media/{id}` | 图片文件（原图访问） |
| POST | `/api/v1/records/media/{id}/ask` | 图片追问（L4 图片问答，VLM 看图回答） |
| POST | `/api/v1/records/media/ask-batch` | 多图问答（Phase 1 带图 ask：1-3 张一次提问，IntentRecognizer 分流问句/陈述）|
| POST | `/api/v1/conversations/end` | 结束对话，AI 总结 |
| GET | `/api/v1/feed` | Feed 流 |
| GET | `/api/v1/brief` | 今日简报 |
| GET | `/api/v1/timeline` | 时间线 |
| GET | `/api/v1/memory` | 记忆查询 |
| GET / POST | `/api/v1/trading/*` | 交易查询、复盘、知识反哺 |
| GET | `/api/v1/project/status` | 项目状态（Kernel + Domain OS + RFC + Git） |
| GET / POST / PUT / DELETE | `/api/v1/project/tasks` | 任务 CRUD |
| GET | `/api/v1/project/tasks/stats` | 任务统计 |
| GET / PUT | `/api/v1/identity` | 个人档案读写 |
| GET | `/api/v1/search?q=` | 全文搜索 |
| GET | `/api/v1/tags` | 标签统计 |
| POST | `/api/v1/cards/migrate` | 卡片迁移 |
| POST | `/api/v1/cards/cleanup` | 卡片冗余记录清理（迁移后去除重复 rec_*） |
| GET | `/api/v1/accounts/available` | 启用账号列表（**无鉴权**，仅返回 userId 最小集，REVIEW #215）|
| GET | `/api/v1/me/plugins` | 当前用户启用插件（**无鉴权**，前端模块显隐，RFC 20260814）|
| GET / POST | `/api/v1/accounts` | 账号查询/创建（admin，**需 `X-Admin-Token`**）|
| GET | `/api/v1/admin/**` | 数据/系统/知识管理（admin，**需 `X-Admin-Token`**）|

> **管理鉴权（REVIEW #127）**：`/api/v1/admin/**` 与 `/api/v1/accounts/**` 由 `AdminAuthInterceptor` 保护（`infrastructure/security/`），要求 `X-Admin-Token` = 配置 `adai.security.admin-token`（env `ADAI_ADMIN_TOKEN`）。未配置令牌时 fail-closed 返回 503；CORS 由 `WebConfig` 收窄为配置化 origin 白名单（默认 localhost:*，生产 `ADAI_ALLOWED_ORIGIN_PATTERNS`）。

## 当前测试状态

- **测试数/端点数唯一事实源：`../../docs/reference/status.md`**（RFC `20260815-docs-governance`，/ship 时更新，本文件不复制数字）
- 测试在 `src/test/java/`，覆盖：全部 Controller 接口测试全覆盖 + 多模态 + #127 鉴权 + 行情推送 + AI 日志 + 多用户隔离 + R2 记录↔任务 + 插件门控等
- **新增功能必须配套测试。**

## 外部依赖

- **交易知识库：** `os/trading-engine/`（monorepo 兄弟目录，adai-core **只读**，不写入；**唯一例外**：`POST /trading/reviews/{date}/promote` 写 `os/trading-engine/99-inbox/` 入库候选，见 `TradingController.promoteToInbox`，融合归正式目录需在 trading-engine 工作流手动完成）
- **个人数据：** `data/`（monorepo 根目录，File First）

## 相关文档

| 文档（根目录的需 CLI read 查看） | 位置 | 说明 |
|:-------------------------------|:----|:------|
| `api-spec.md` | `../../docs/architecture/` | API 接口契约（全局唯一真相源） |
| `feature-reference.md` | `../../docs/reference/` | 功能参考（含后端能力，唯一功能真相源） |
| `system-architecture.md` | `../../docs/architecture/` | 系统架构、Kernel/Domain 分层 |
| `product-architecture.md` | `../../docs/architecture/` | 五层产品架构详解 |
| `VISION.md` | `../../docs/` | 项目愿景与核心理念 |
