---
title: AI 对话质量修复：从分析模式到对话模式
date: 2026-07-21
status: draft
---

# AI 对话质量修复

## 1. 问题

API 调用 DeepSeek 的回复"怪怪的"，和 DeepSeek 在线聊天感觉差很多。

### 1.1 根本原因（5 个）

| # | 问题 | 当前值 | 在线聊天值 | 影响 |
|:-|:-----|:-------|:----------|:-----|
| 1 | **Temperature 过低** | 0.3 | ~0.7-1.0 | 输出保守，像背模板 |
| 2 | **不是真实多轮对话** | 历史记录拼成一段 text | 真正 user/assistant 数组 | 模型失去对话感 |
| 3 | **System prompt 冲突** | "分析记录并输出 JSON" | "你是 DeepSeek 助手" | 模型在"分析模式"，不是聊天 |
| 4 | **Max tokens 太少** | 1024 | 不设硬限制 | 回复被截断，不敢放开说 |
| 5 | **两种意图公用一个 prompt** | STATEMENT（打标签）和 QUESTION（聊天）的前置上下文一样 | 分开 | 问答被 JSON 框住 |

### 1.2 当前 Prompt 结构

发给 DeepSeek 的 messages 数组：

```
messages: [
  {role: "system", content: "你是一个记录分析助手..."}    ← 永远是分析模式
  {role: "user", content: "## 相关历史记录\n...\n## 当前记录\n我昨天说过什么诗词\n\n（JSON 输出指令）"}
]
```

对比在线聊天：
```
messages: [
  {role: "system", content: "你是 DeepSeek 助手"},
  {role: "user", content: "你好"},
  {role: "assistant", content: "你好！有什么可以帮你的？"},
  {role: "user", content: "昨天我说过什么诗词"},
]
```

## 2. 方案

### 2.1 AiClient 接口拆分

当前 `AiClient` 只有一个 `understand()` 方法，同时处理打标签（STATEMENT）和聊天（QUESTION）。

改为两个方法：

```java
public interface AiClient {
    /** STATEMENT：分析记录，返回标签+摘要（低温度+JSON系统提示） */
    AiUnderstanding understand(ContextPackage ctx);

    /** QUESTION：多轮对话（高温度+自然系统提示+真实对话历史） */
    AiUnderstanding chat(ContextPackage ctx, String cardId);
}
```

`DeepSeekAiClient` 实现两种调用方式：

```
understand() → 保持当前逻辑（0.3 temp, JSON system prompt, 1 user message）

chat() → 新逻辑：
  - system prompt: "你是用户的个人AI助手阿呆..."
  - temperature: 0.7
  - max_tokens: 2048
  - messages: [
      {role: "system", ...},
      {role: "user", content: "你好"},        ← 从 card 文件读取
      {role: "assistant", content: "你好！"},  ← 从 card 文件读取
      {role: "user", content: "昨天我说过什么诗词"},
    ]
```

### 2.2 ContextEngine 的 Context 注入方式

当前：所有上下文（身份 + 标签历史 + 记忆 + 领域）**全部拼在 user message 前**，约 500-2000 字符。

改为：

- **STATEMENT 场景**：保持现状（分析打标签不需要对话上下文）
- **QUESTION 场景**：上下文只注入到 system prompt 作为背景知识，user message 只放用户说的话

```
Before (QUESTION):
  user: "## 相关历史记录\n...\n## 记忆回读\n...\n## 当前记录\n我昨天说过什么诗词\n\n回答并输出JSON..."

After (QUESTION):
  system: "你是阿呆的个人AI助手。以下是背景信息：\n- 用户身份：...\n- 当前日期：2026-07-21 周三\n- 用户近期关注：诗词、诗经\n\n请像朋友一样自然对话，回答完在末尾附JSON标签。"
  messages: [
    {role: "user", content: "你好"},
    {role: "assistant", content: "你好！今天想聊什么？"},
    {role: "user", content: "昨天我说过什么诗词"},
  ]
```

### 2.3 ContextEngine 需要新增的能力

ContextEngine 现在接受 `cardId` 但不解析 card 文件中的**AI 回复轮次**。需要：

1. CardFileRepository 或 ContextEngine 将 card 文件中的 `turns` 转换为 `{role, content}` 数组
2. QuestionAppService.answer() 传递这个数组给 AiClient.chat()

CardRecord.Turn 结构：
```java
record Turn(boolean isUser, String text, String time)
```

转换为：
```java
// isUser=true → "user", isUser=false → "assistant"
```

### 2.4 改动范围

| 文件 | 改动 | 工作量 |
|:----|:-----|:-------|
| `AiClient.java` | 新增 `chat(ContextPackage, String cardId, List<ChatMessage>)` 方法 | 3 行 |
| `ContextEngine.java` | 补充 `buildChatMessages()` 方法，从 card 文件提取对话历史 | 20 行 |
| `ContextPackage.java` | 可不动（chat 走不同路径），或新增 `chatHistory` 字段 | 可选 |
| `DeepSeekAiClient.java` | `chat()` 实现：多轮 messages 数组 + 0.7 temp + 2048 tokens + 自然 system prompt | 40 行 |
| `MockAiClient.java` | 实现 `chat()` 方法 | 5 行 |
| `QuestionAppService.java` | 调用 `aiClient.chat()` 替代 `aiClient.understand()` | 2 行 |
| `RecordController.java` | 传 card 对话历史到 QuestionAppService | 2 行 |

**不改前端的任何代码**。后端 API 返回格式不变（依然是 `summary` + `tags` + `rawResponse`）。

### 2.5 不涉及的部分

- `LlmResponseParser.java` — 不改，已支持自然文本 + 末尾 JSON 混合解析
- 前端 — 不改，API 返回值格式不变
- TagIndexService / MemoryService / ContextContributor — 不改，ContextEngine 组合方式不变

## 3. 预期效果

```
Before:
  user: 今天天气怎么样
  ai:   {"summary": "今天天气怎么样？我暂时没有获取实时天气数据的功能...", "tags": ["天气"]}

After:
  user: 今天天气怎么样
  ai:   我暂时没有获取实时天气数据的功能，建议你打开天气应用看看。
         [JSON: {"tags": ["天气"]}]
```

多轮对话效果（首次提问 → 追问，不走同一个 card）：

```
第一轮：
  system: [背景知识：你是谁、日期、近期关注]
  user: 你读过《关雎》吗？
  assistant: 读过。"关关雎鸠，在河之洲。窈窕淑女，君子好逑。"这是《诗经》的开篇...
  [JSON: {"tags": ["诗词", "诗经"]}]

第二轮（同一 cardId）：
  system: [背景知识 + 对话历史]
  messages: [
    {role: "user", content: "你读过《关雎》吗？"},
    {role: "assistant", content: "读过..."},
    {role: "user", content: "下一句是什么"},
  ]
  assistant: "求之不得，寤寐思服。悠哉悠哉，辗转反侧。"
  [JSON: {"tags": ["诗词", "诗经", "关雎"]}]
```

## 4. 实施顺序

1. `AiClient.java` — 加 `chat()` 接口方法
2. `DeepSeekAiClient.java` — 实现 `chat()`：多轮 messages + 0.7 temp + 2048 tokens
3. `ContextEngine.java` — 新增 `buildChatMessages(cardId)` 方法
4. `QuestionAppService.java` — 调用 `aiClient.chat()` 替代 `understand()`
5. `MockAiClient.java` — 实现 `chat()` 保持 mock 可用
6. 测试 → 构建 → 部署

Status: **draft**
