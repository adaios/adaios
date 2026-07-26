# 项目系统身份声明

> 本文件描述 Project OS 的核心定位与当前项目元信息。
> 版本：v0.3 | 更新：2026-07-26

---

## 当前项目

**AdaiOS** — Personal AI Operating System

| 属性 | 值 |
|:-----|:----|
| 类型 | AI 原生个人操作系统 |
| 架构 | Modular Monolith（Java 17 + Spring Boot 3.3 + Flutter 3） |
| 仓库 | Monorepo |
| 当前分支 | main |
| 总提交数 | 27（2026-04 至今） |

---

## 五层产品架构进度

| 层 | 名称 | 进度 | 关键能力 |
|:---|:-----|:----:|:---------|
| L1 | AI 问答 | ✅ | 意图识别、会话卡片、Context Engine、Knowledge 注入 |
| L2 | 主动推送 | 🟡 | 今日简报（基础），推送/异动通知未开始 |
| L3 | 数字身份+记忆 | ✅ | Identity/Memory 文件存储、标签索引、记忆重建 |
| L4 | 通用记录入口 | 🟡 | 文字记录+意图路由 ✅，多模态 ❌ |
| L5 | 外部信息接入 | ❌ | 完全未开始 |
| L6 | 交易系统反哺 | 🟡 | 持仓/交易记录 ✅，知识召回+DECISION ✅，复盘沉淀 ✅，知识反哺 ✅ |

---

## Kernel 六大组件

| 组件 | 状态 | 关键文件数 | 说明 |
|:-----|:----:|:---------|:------|
| Identity | ✅ | 2 | profile.md 读写，跨会话恢复 |
| Record | ✅ | 4 | ContentRecord + CardRecord + 文件存储 |
| Timeline | ✅ | 2 | Record 时间序列投影 |
| Context | ✅ | 5 | ContextEngine + IntentRecognizer + 3 Contributor |
| Memory | ✅ | 3 | Memory/MemoryService/MemorySummary |
| Knowledge | ✅ | 4 | KnowledgeSource 接口 + 3 个实现（Trading/Life/Project） |

---

## Domain OS 状态

| Domain | 状态 | 知识数据 | Java 代码 |
|:-------|:----:|:---------|:----------|
| Trading OS | ✅ 完整 | 87课精炼，11-context/ 五份文件 | Controller + Service + Contributor + KnowledgeSource |
| Life OS | 🏗 骨架 | 11-context/identity.md | Contributor + KnowledgeSource |
| Project OS | ✅ 完整 | 11-context/ identity + rules<br/>+ task files | Contributor + KnowledgeSource + Status API<br/>+ Task CRUD + RFC tracking + 前端任务页 |

---

## API 端点清单

```
POST /api/v1/records              — 统一输入入口
POST /api/v1/conversations/end    — 结束对话
GET  /api/v1/feed                 — Feed 流
GET  /api/v1/brief                — 今日简报
GET  /api/v1/timeline             — 时间线
GET  /api/v1/memory               — 记忆查询
POST /api/v1/memory/rebuild       — 记忆重建
GET  /api/v1/identity             — 读取档案
PUT  /api/v1/identity             — 更新档案
GET  /api/v1/tags                 — 标签统计
GET  /api/v1/search               — 全文搜索
GET  /api/v1/trading/positions    — 持仓查询
GET  /api/v1/trading/portfolio    — 组合快照
POST /api/v1/trading/trades       — 记录交易
POST /api/v1/trading/review       — 生成复盘
GET  /api/v1/trading/review       — 查询复盘
GET  /api/v1/trading/reviews      — 复盘列表
GET  /api/v1/trading/has-activity — 交易活动检测
POST /api/v1/trading/reviews/{date}/promote — 入库候选
GET  /api/v1/trading/knowledge/conflicts    — 规则冲突检测
POST /api/v1/cards/migrate        — 卡片迁移
GET  /api/v1/project/tasks        — 任务列表
POST /api/v1/project/tasks        — 创建任务
PUT  /api/v1/project/tasks/{id}   — 更新任务
DELETE /api/v1/project/tasks/{id} — 删除任务
GET  /api/v1/project/tasks/stats  — 任务统计
```

总计 **26 个端点**。

## 前端页面

| 页面 | 文件 | 说明 |
|:-----|:-----|:------|
| MainPage | main_page.dart | Feed 流 + 聊天模式（list/chatting 双模式） |
| LauncherPage | launcher_page.dart | 导航中心 + 标签宇宙 |
| ProfilePage | profile_page.dart | 身份档案编辑 |
| MemoryPage | memory_page.dart | 记忆浏览器（按天翻阅） |
| TimelinePage | timeline_page.dart | 日历月视图 |
| SearchPage | search_page.dart | 全文搜索 |
| ProjectStatusPage | project_status_page.dart | 项目仪表盘（Kernel/Domain/RFC 状态 + 任务统计） |
| ProjectTaskPage | project_task_page.dart | 任务列表 + 创建/编辑/状态推进 |

## 开发原则

1. **文件优先，文档先行** — 先写 RFC，确认后再写代码
2. **入口统一，后台分流** — 所有输入走单一入口
3. **os/ 独立工作流** — 各 Domain 有独立 CLAUDE.md，adai-core 只读不写
4. **不过度设计** — 有真实用例才定义抽象
5. **知识反哺** — 日常数据沉淀后反哺知识系统
