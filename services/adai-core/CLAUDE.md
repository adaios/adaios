# CLAUDE.md — adai-core

AdaiOS 核心运行时（Java 17 + Spring Boot 3.3.x）。

> 这是 AdaiOS monorepo 的一个子项目。在根目录下有全局 CLAUDE.md 和 VISION.md。
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

# ── 部署（生产服务器 49.235.37.220） ──
./gradlew bootJar
./deploy.sh 49.235.37.220 build/libs/adai-core-0.0.1-SNAPSHOT.jar
```

## 包结构（根包 `com.adaiadai.core`）

```
com.adaiadai.core/
├── kernel/                     ★ Kernel — 操作系统内核层
│   ├── identity/                 个人档案
│   ├── record/                   最小个人事件单元
│   ├── timeline/                 时间序列投影
│   ├── market/                   行情数据源（TencentMarketDataSource）
│   ├── ai/                       端口（REVIEW #22 依赖倒置：AiClient/AiUnderstanding 在 kernel，实现归 infra）
│   ├── storage/                  端口（REVIEW #22 依赖倒置：FileStorage 在 kernel，实现归 infra/storage）
│   ├── context/                  ★ Context Engine（核心）
│   │   ├── IntentRecognizer       意图识别
│   │   └── engine/                上下文引擎（ContextContributor 插件机制）
│   ├── memory/                   个人记忆
│   └── knowledge/                结构化知识（预留）
│
├── domain/                     ★ Domain OS
│   ├── trading/                  金融交易（TradingContextContributor + MarketContextContributor + 复盘）
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

- **Java 17 特性：** Record、Sealed Class、Pattern Matching、Text Block
- **注入：** Constructor Injection，禁止 `@Autowired` 字段注入
- **日志：** SLF4J + Lombok `@Slf4j`
- **异常：** 继承 `RuntimeException` 的业务异常
- **测试：** Controller 层用 `@WebMvcTest` + MockBean，Service 层用纯单元测试

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
| GET / POST | `/api/v1/accounts` | 账号查询/创建（admin，**需 `X-Admin-Token`**）|
| GET | `/api/v1/admin/**` | 数据/系统/知识管理（admin，**需 `X-Admin-Token`**）|

> **管理鉴权（REVIEW #127）**：`/api/v1/admin/**` 与 `/api/v1/accounts/**` 由 `AdminAuthInterceptor` 保护（`infrastructure/security/`），要求 `X-Admin-Token` = 配置 `adai.security.admin-token`（env `ADAI_ADMIN_TOKEN`）。未配置令牌时 fail-closed 返回 503；CORS 由 `WebConfig` 收窄为配置化 origin 白名单（默认 localhost:*，生产 `ADAI_ALLOWED_ORIGIN_PATTERNS`）。

## 当前测试状态

后端测试在 `src/test/java/`，当前 **387 个测试，0 失败**（15 Controller 50 端点接口测试全覆盖 + 多模态 18 测试 + #127 鉴权 4 测试 + 行情推送 14 测试 + #14 问候语时段边界 1 测试 + #221 问候语降级 emoji 1 测试 + #222 问候加中午段 1 测试 + Brief 降级增强 📋/🧠/☕ 1 测试 + #216 CardMigration 判定收紧 + 缺 id 跳过 3 测试 + R1 AI 交互日志 20 测试 + #184 promote 脱敏 2 测试 + #206/#207 幂等与时间基准 3 测试 + #209 图片追问持久化 1 测试 + #227 定时重补过滤禁用账号 2 测试 + #213 追踪上下文请求级清理 2 测试 + #210 AI 日志保留期/分页治理 7 测试 + #214 图片追问长度上界 2 测试 + #215 available 最小集 1 测试 + #202 复盘剥代码块围栏 2 测试 + 旧数组账号日期回归 1 测试 + **R2 记录↔任务：sourceRecordId round-trip + 旧文件兼容 2 测试 + Linker 触发/排除标签/幂等/清待办 8 测试 + MemoryService.clearActionable 2 测试** + **08-14 删除残留：Memory cardId round-trip + 按 cardId 删除 + recordId 路径回归 3 测试**）。
新增功能必须配套测试。

## 外部依赖

- **交易知识库：** `os/trading-os/`（monorepo 兄弟目录，adai-core **只读**，不写入；**唯一例外**：`POST /trading/reviews/{date}/promote` 写 `os/trading-os/99-inbox/` 入库候选，见 `TradingController.promoteToInbox`，融合归正式目录需在 trading-os 工作流手动完成）
- **个人数据：** `data/`（monorepo 根目录，File First）

## 相关文档

| 文档（根目录的需 CLI read 查看） | 位置 | 说明 |
|:-------------------------------|:----|:------|
| `api-spec.md` | `../../docs/architecture/` | API 接口契约（全局唯一真相源） |
| `feature-reference.md` | `../../docs/reference/` | 功能参考（含后端能力，唯一功能真相源） |
| `system-architecture.md` | `../../docs/architecture/` | 系统架构、Kernel/Domain 分层 |
| `product-architecture.md` | `../../docs/architecture/` | 五层产品架构详解 |
| `VISION.md` | `../../docs/` | 项目愿景与核心理念 |
