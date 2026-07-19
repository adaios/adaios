# adai-core

AdaiOS 后端核心服务 — Java 17 + Spring Boot 3.3。

## 启动

```bash
# Mock AI 模式（默认，不消耗 DeepSeek API）
./gradlew bootRun

# DeepSeek 模式
ADAI_AI_PROVIDER=deepseek DEEPSEEK_API_KEY=sk-xxx ./gradlew bootRun
```

服务监听端口 `8080`。

## 构建

```bash
./gradlew build          # 编译 + 测试
./gradlew build -x test  # 仅编译
./gradlew test           # 运行全部测试
```

## 技术栈

| 层面 | 选型 |
|:----|:-----|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.3.x |
| 构建 | Gradle (Kotlin DSL) |
| 数据库 | MySQL 8.0 (dev) / H2 (test) |
| AI | DeepSeek API (deepseek-chat) / Mock |
| 存储 | 文件系统（File First） |

## 核心架构

```
com.adaiadai.core/
├── kernel/             内核层
│   ├── record/         最小个人事件单元（ContentRecord）
│   ├── timeline/       时间序列投影
│   ├── context/        意图识别（IntentRecognizer）
│   │   └── engine/     上下文引擎（ContextEngine + ContextContributor）
│   ├── memory/         记忆（预留）
│   └── identity/       个人档案
├── domain/             领域能力层
│   └── trading/        金融交易（预留）
├── application/        应用层
│   ├── RecordFlowAppService
│   ├── QuestionAppService
│   ├── BriefAppService
│   ├── FeedAppService
│   └── TradingAppService
├── interfaces/         Controller
│   ├── RecordController     POST /api/v1/records
│   ├── FeedController       GET  /api/v1/feed
│   ├── BriefController      GET  /api/v1/brief
│   └── ConversationController POST /api/v1/conversations/end
└── infrastructure/     基础设施
    ├── storage/        文件存储（FileStorage + RecordFileRepository + CardFileRepository）
    └── ai/             AI 模型接入
        ├── llm/        DeepSeekAiClient / MockAiClient
        └── LlmResponseParser
```

## API 接口

| 端点 | 方法 | 说明 |
|:-----|:----|:------|
| `/api/v1/records` | POST | 统一输入入口，后端自动判断意图（STATEMENT → log / QUESTION → ask） |
| `/api/v1/feed` | GET | Feed 流，返回 brief + 当日卡片列表（含 intent, summary, tags） |
| `/api/v1/brief` | GET | 今日摘要（AI 问候，带 emoji 列表，分时段） |
| `/api/v1/conversations/end` | POST | 结束对话，AI 重新总结 |

## File First 存储

```
data/
└── records/
    └── YYYY/
        └── MM/
            ├── rec_yyyyMMdd_HHmmss.md     ← 单个记录
            └── card_*.md                   ← 会话卡片（对话轮次）

记录文件格式：
---
id: rec_20260719_123456
type: note
source: user_input
tags: [标签1, 标签2]
createdAt: 2026-07-19T12:34:56
summary: AI 生成的摘要
---
正文内容
```

## 意图判断逻辑

```
POST /api/v1/records
  │
  ├─ cardId 存在且卡片文件已存在 → 追加模式，直接 handleQuestion
  │
  └─ 新建卡片 → resolveIntent
       1. intent 参数手动指定
       2. AI 意图识别（DeepSeek）
       3. 正则兜底（问号 / 如何 / 为什么...）
       （没有 session-aware 逻辑）
```

## 当前状态

- 已对接 DeepSeek API
- File First 存储正常工作
- Feed API 返回 `intent` + `summary` 字段
- Mock 模式可用于开发测试
