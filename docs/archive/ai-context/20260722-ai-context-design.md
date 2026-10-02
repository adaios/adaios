# 阿呆 App - AI Context 设计文档

## 1. 文档目的

本文档用于指导阿呆 App 接入 AI 能力。

目标不是训练 AI 模型，而是通过用户数据、上下文管理和记忆机制，让 AI 助手逐渐理解用户，提供个性化服务。

阿呆 App 的核心价值：

> 一个能够长期记录、理解和陪伴用户的 AI 助手。

---

# 2. 当前产品定位

阿呆 App 是一个个人记录入口。

用户可以通过：

* 文字
* 语音
* 图片

记录自己的：

* 日常事件
* 想法
* 情绪
* 投资交易
* 学习内容
* 灵感

AI 的职责：

1. 理解用户输入
2. 总结和整理信息
3. 提炼用户长期规律
4. 提供提醒和建议

---

# 3. AI 调用原则

## 3.1 模型不是核心资产

DeepSeek、GPT、Claude 等模型只是 AI 能力提供方。

阿呆 App 的核心资产：

```
用户数据
+
用户上下文
+
用户历史记忆
+
用户行为模式
```

因此 AI 调用必须包含 Context Layer。

---

# 4. AI Context 分层设计

## 4.1 用户基础信息 Profile

稳定信息。

示例：

```
user_profile

用户昵称：
阿呆

职业：
软件工程师

兴趣：
AI
投资
软件开发

当前目标：
开发阿呆 App
```

特点：

* 更新频率低
* 长期有效

---

## 4.2 用户日常记录 Daily Records / Log

用户原始输入（STATEMENT / log 意图）。

例如：

```
2026-07-21

类型：
想法

内容：
DeepSeek API 和网页版聊天体验不同，
阿呆 App 需要自己的上下文系统。
```

采用 **File First** 存储：`data/records/YYYY/MM/rec_xxx.md`

特点：

* 高频产生
* 保留原始数据
* AI 自动打标签，供 Context Engine 索引

---

## 4.3 AI 对话 Chat / Ask

用户主动提问（QUESTION / ask 意图）。

例如：

```
用户：我昨天说过什么诗词？

AI：根据记录，你昨天提到过《诗经·关雎》中的“窈窕淑女，君子好逑”...
```

与 Log 的区别：

| | Log（记录） | Chat（问答） |
|:--|:-----------|:------------|
| 触发 | 陈述、描述 | 提问、询问 |
| AI 回复 | 打标签 + 摘要 | 自然语言回答 |
| 上下文 | 无需多轮 | 需要对话历史 |
| 存储 | data/records/ | data/records/ + card 文件（对话轮次） |

---

## 4.4 AI 记忆 Memory Summary

AI 从大量记录中提炼出的长期信息。

例如：

```
用户近期关注：

1. AI Agent
2. AI Context
3. 阿呆 App 架构

用户特点：

喜欢系统化设计，
关注长期价值。
```

特点：

* 定期更新（当前机制：按标签聚合多轮记录）
* 控制上下文长度

---

## 4.5 用户行为模式 Pattern

AI 根据历史发现规律。

例如：

```
发现：

用户经常产生大量架构想法，
容易扩大范围。

建议：

提醒用户优先完成 MVP。
```

该模块后续实现。

---

# 5. AI 请求上下文结构

禁止直接：

```
用户输入
    |
    |
LLM
```

应该：

```
用户输入

+

System Prompt

+

User Profile

+

Recent Memory

+

Recent Records

↓

LLM
```

示例：

```
System:

你是阿呆的私人 AI 助手。

你的任务：
帮助用户记录生活，
总结经验，
发现长期趋势。


User Context:

用户：
阿呆

职业：
软件工程师

最近关注：
AI Context
阿呆 App


User:

今天完成 Flutter APK 构建。
```

---

# 6. 数据存储

当前阶段不引入数据库，采用 **File First** 原则：

| 数据 | 存储方式 | 路径 |
|:----|:---------|:-----|
| User Profile | Markdown 文件 | `data/identity/profile.md` |
| Daily Record | Markdown 文件 | `data/records/YYYY/MM/rec_xxx.md` |
| AI Memory | Markdown 文件 | `data/memory/YYYY/MM/rec_xxx.md` |
| Card 对话 | Markdown 文件 | `data/cards/card_xxx.md` |
| 标签索引 | JSON 文件 | `data/index/tags.json` |
| Domain 知识 | Markdown 文件 | `os/*/11-context/` |

数据库在后续阶段按需引入，现阶段文件足够。

---

# 7. Memory 更新机制

当前机制（已实现）：

```
每次 Record 保存 → 自动触发标签索引更新
ContextEngine 加载时 → 按标签聚合记忆回读（近 7 天，每个标签取 2 条摘要）
```

同步 Memory 总结暂不实现（担心实时生成质量不可控），保持"标签聚合回读"机制。

---

# 8. 当前不实现

以下功能暂不开发：

* Agent 自动规划
* MCP
* 向量数据库
* 多智能体
* 自动执行任务
* 复杂知识图谱

原因：

先验证：

> 用户持续记录后，AI 是否越来越懂用户。

---

# 9. 后续演进方向

未来可以扩展：

```
阿呆 App

    |
 AI Context Layer

    |
 ----------------
 |              |
Life OS      Trading OS

    |
 Knowledge
 Memory
 Skill
 Workflow
```

但当前目标：

完成一个真正可长期使用的个人 AI 助手 MVP。

---

# 10. Claude Code 开发要求

开发过程中：

1. 优先保持简单架构
2. 不提前引入复杂 AI Agent
3. 所有 AI 能力必须围绕 Context 管理
4. 数据模型设计需要支持未来扩展
5. 保持模块化，方便后续演进为 ADAIOS
