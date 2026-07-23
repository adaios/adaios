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
│   ├── context/                  ★ Context Engine（核心）
│   │   ├── IntentRecognizer       意图识别
│   │   └── engine/                上下文引擎（ContextContributor 插件机制）
│   ├── memory/                   个人记忆
│   └── knowledge/                结构化知识（预留）
│
├── domain/                     ★ Domain OS
│   ├── trading/                  金融交易
│   ├── life/                     个人生活（预留）
│   └── project/                  项目管理（预留）
│
├── application/                应用层 — 用例编排
│   ├── RecordFlowAppService      记录流程
│   ├── QuestionAppService        问答处理
│   ├── FeedAppService            Feed 构造
│   ├── TimelineAppService        时间线查询
│   ├── BriefAppService           今日简报
│   └── TradingAppService         交易领域用例
│
├── interfaces/                 入站适配层（Controller）
├── infrastructure/             出站适配层
│   ├── storage/                  文件存储（File First）
│   ├── database/                 数据库（预留）
│   └── ai/                       AI 模型接入
│       └── llm/                    LLM 客户端（MockAiClient / DeepSeekAiClient）
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
| POST | `/api/v1/conversations/end` | 结束对话，AI 总结 |
| GET | `/api/v1/feed` | Feed 流 |
| GET | `/api/v1/brief` | 今日简报 |
| GET | `/api/v1/timeline` | 时间线 |
| GET | `/api/v1/memory` | 记忆查询 |
| GET | `/api/v1/trading/*` | 交易查询 |

## 当前测试状态

后端测试在 `src/test/java/`，当前 **100 个测试，0 失败**。
新增功能必须配套测试。

## 外部依赖

- **交易知识库：** `os/trading-os/`（monorepo 兄弟目录，adai-core **只读**，不写入）
- **个人数据：** `data/`（monorepo 根目录，File First）
