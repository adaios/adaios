# 项目系统身份声明

> 本文件描述 Project OS 的核心定位与当前项目元信息。
> 版本：v0.4 | 更新：2026-07-30

---

## 当前项目

**AdaiOS** — Personal AI Operating System

| 属性 | 值 |
|:-----|:----|
| 类型 | AI 原生个人操作系统 |
| 架构 | Modular Monolith（Java 17 + Spring Boot 3.3 + Flutter 3） |
| 仓库 | Monorepo |
| 当前分支 | main |
| 总提交数 | 36+（2026-07-21 至今） |

---

## 五层产品架构进度

| 层 | 名称 | 进度 | 关键能力 |
|:---|:-----|:----:|:---------|
| L1 | AI 问答 | ✅ | 意图识别（STATEMENT/QUESTION）、会话卡片（idle→waiting→chatting→ended 四态）、Context Engine + Knowledge 注入 |
| L2 | 主动推送 | 🟡 | 今日简报（带 2 分钟缓存、时效感知、交易活动检测），推送/异动未开始 |
| L3 | 数字身份+记忆 | ✅ | Identity/Memory 文件存储、标签索引、记忆重建 API、删除联动的 Memory 清理 |
| L4 | 通用记录入口 | 🟡 | 文字记录+意图路由 ✅，生活快速记录模板 ✅，多模态 ❌ |
| L5 | 外部信息接入 | ❌ | 完全未开始 |
| L6 | 交易系统反哺 | 🟡 | 持仓/交易记录 ✅，复盘生成+查询 ✅，知识反哺（promote/conflicts）✅ |

---

## Kernel 六大组件

| 组件 | 状态 | 说明 |
|:-----|:----:|:------|
| Identity | ✅ | profile.md 读写，跨会话恢复，Context 注入（不含用户称呼） |
| Record | ✅ | ContentRecord + CardRecord 双仓 + File First 存储 |
| Timeline | ✅ | Record 时间投影，支持类型/数量筛选 |
| Context | ✅ | ContextEngine + IntentRecognizer（正则+AI）+ 4 个 ContextContributor |
| Memory | ✅ | MemoryService 沉淀+重建+删除联动，File First 存储 `data/memory/` |
| Knowledge | ✅ | KnowledgeSource 接口 + 3 个实现（Trading/Life/Project） |

---

## Domain OS 状态

| Domain | 状态 | 说明 |
|:-------|:----:|:------|
| Trading OS | ✅ 完整 | 87课精炼 + 11-context/ 五份文件 + 复盘+反哺闭环 |
| Life OS | 🏗 骨架 | LifeKnowledgeSource + LifeContextContributor，数据不足（等待日常使用积累） |
| Project OS | ✅ 完整 | 身份+规则 KnowledgeSource + ContextContributor（Git/RFC/任务注入）+ Status API + 任务 CRUD |

---

## 交付物清单

| 类别 | 交付物 | 状态 |
|:-----|:-------|:----:|
| API | POST /api/v1/records 统一入口 + 自动分流 | ✅ |
| API | 对话总结（POST /api/v1/conversations/end） | ✅ |
| API | Feed 分页（GET /api/v1/feed?page=&size=） | ✅ |
| API | 今日简报（GET /api/v1/brief） | ✅ |
| API | 时间线（GET /api/v1/timeline） | ✅ |
| API | 记忆 CRUD + 重建 + 删除联动 | ✅ |
| API | 身份读写（GET|PUT /api/v1/identity） | ✅ |
| API | 标签统计（GET /api/v1/tags） | ✅ |
| API | 全文搜索（GET /api/v1/search） | ✅ |
| API | 交易持仓+复盘+反哺（Trading API） | ✅ |
| API | 项目状态仪表盘 + 任务 CRUD + 统计 | ✅ |
| API | 卡片迁移 + 清理 | ✅ |
| 前端 | MainPage（Feed + 聊天 + 输入栏） | ✅ |
| 前端 | LauncherPage（导航中心 + 标签宇宙） | ✅ |
| 前端 | ProfilePage | ✅ |
| 前端 | MemoryPage（日历导航 + 标签聚合） | ✅ |
| 前端 | TimelinePage | ✅ |
| 前端 | SearchPage | ✅ |
| 前端 | ProjectStatusPage + ProjectTaskPage | ✅ |
| 前端 | LifeQuickEntry（生活快速记录弹窗） | ✅ |
| 架构 | 15 份 RFC 文档覆盖所有决策 | ✅ |
| 架构 | Context Engine 插件机制 | ✅ |
| 架构 | File First 存储模式 | ✅ |
| 后端 | 100+ 测试，0 失败 | ✅ |
| 前端 | 23 测试，0 失败 | ✅ |
| 修复 | 删除双仓 + Feed 分页方向 + AI 称呼 + 折叠渐隐 + ↑ top | ✅ |

---

## 近期（2026-07-30 起）

| 方向 | 优先级 | 说明 |
|:-----|:------:|:------|
| B: Project OS 加深（Phase 2） | 进行中 | RFC 状态前端展示 + KnowledgeSource 增强 |
| A: Layer 5 行情接入 | 待评估 | 填补最大架构空白，东方财富日频行情 |
| C: Life OS 情绪趋势 | 等待数据 | 需日常使用积累 10+ 条带情绪记录 |

## 开发原则

1. **文件优先，文档先行** — 先写 RFC，确认后再写代码
2. **入口统一，后台分流** — 所有输入走单一入口
3. **os/ 独立工作流** — 各 Domain 有独立 CLAUDE.md，adai-core 只读不写
4. **不过度设计** — 有真实用例才定义抽象
5. **知识反哺** — 日常数据沉淀后反哺知识系统
