# AdaiOS 项目现状与三大方向规划

**日期：** 2026-07-26
**状态：** 规划文档

---

## 一、项目现状总览

### 五层产品架构

| 层级 | 状态 | 已实现 |
|:----|:----:|:-------|
| **Layer 1 AI 问答** | ✅ 完整 | 意图识别（STATEMENT/QUESTION/DECISION）、Context Engine、卡片续接、Memory 沉淀 |
| **Layer 2 主动推送** | 🟡 基础 | 今日简报（带时效感知 + 习惯注入 + 2 分钟缓存） |
| **Layer 3 数字身份 + 记忆** | ✅ 完整 | Identity、Record、Timeline、Context、Memory、Knowledge 六大 Kernel 组件全部完成 |
| **Layer 4 通用记录** | 🟡 基础 | 文字记录 ✅、沉默记录 ✅、多模态 ❌ |
| **Layer 5 外部信息** | ❌ 空白 | 没有任何外部数据源接入 |
| **Layer 6 交易闭环** | ✅ 完整 | KnowledgeSource、DECISION 路由、复盘生成、反哺管道（promote/conflicts） |

### 三个 Domain OS

| Domain | 状态 | 已实现 |
|:-------|:----:|:-------|
| **Trading OS** | ✅ 完整 | 87 课知识库 → 11-context/ → TradingKnowledgeSource → Context Engine。复盘生成 + 知识反哺（promote/conflicts） |
| **Life OS** | 🟡 半成品 | LifeKnowledgeSource + LifeContextContributor 从 Memory 自动浮现生活理解。但数据不足（data/memory 只有 2 条记忆，均无生活标签） |
| **Project OS** | 🟡 增强中 | Status API + git log 自举 + RFC 索引 + **轻量任务系统（刚完成）** |

### Kernel 六大组件

| 组件 | 状态 | 说明 |
|:-----|:----:|:------|
| Identity | ✅ | `data/identity/profile.md`，Repository 读写 |
| Record | ✅ | 最小个人事件单元，统一入口 POST /api/v1/records |
| Timeline | ✅ | Record 的时间序列投影 |
| Context | ✅ | Context Engine + ContextContributor 插件机制 + KnowledgeSource 注入 |
| Memory | ✅ | File First 存储 `data/memory/YYYY/MM.md`，去重沉淀，标签聚合回读 |
| Knowledge | ✅ | KnowledgeSource 接口，Trading/Life/Project 三个实现 |

### 源码统计

| 维度 | 数字 |
|:-----|:----:|
| Git 提交 | 30 |
| RFC 文档 | 11 |
| 后端 Controller | 13 |
| API 端点 | 26 |
| 后端测试 | 30 ✅ |
| 前端测试 | 19 ✅ |
| Flutter analyze | 0 issues ✅ |
| Flutter build web | ✅ |

### 后端 API 端点清单

| 方法 | 路径 | 用途 |
|:----|:-----|:------|
| POST | `/api/v1/records` | 统一入口（自动分流 STATEMENT / QUESTION / DECISION） |
| POST | `/api/v1/conversations/end` | 结束对话，AI 总结 |
| GET | `/api/v1/feed?date=&since=` | Feed 流 |
| GET | `/api/v1/brief` | 今日简报 |
| GET | `/api/v1/timeline?type=&limit=` | 时间线 |
| GET | `/api/v1/memory?date=` | 记忆查询 |
| GET | `/api/v1/memory/dates` | 有记忆的日期列表 |
| GET | `/api/v1/memory/count` | 记忆总条数 |
| POST | `/api/v1/memory/rebuild` | 记忆重建 |
| GET | `/api/v1/identity` | 身份读取 |
| PUT | `/api/v1/identity` | 身份写入 |
| GET | `/api/v1/tags` | 标签统计 |
| GET | `/api/v1/search?q=` | 全文搜索 |
| GET | `/api/v1/trading/positions` | 持仓查询 |
| GET | `/api/v1/trading/portfolio` | 组合快照 |
| POST | `/api/v1/trading/trades` | 记录交易 |
| POST | `/api/v1/trading/review` | 生成复盘 |
| GET | `/api/v1/trading/review` | 获取复盘 |
| GET | `/api/v1/trading/reviews` | 复盘列表 |
| GET | `/api/v1/trading/has-activity` | 检测交易活动 |
| POST | `/api/v1/trading/reviews/{date}/promote` | 知识反哺（提升为入库候选） |
| GET | `/api/v1/trading/knowledge/conflicts` | 规则冲突检测 |
| POST | `/api/v1/cards/migrate` | 卡片迁移 |
| GET | `/api/v1/project/status` | 项目状态 |
| GET | `/api/v1/project/tasks` | 任务列表（新增） |
| POST | `/api/v1/project/tasks` | 创建任务（新增） |
| PUT | `/api/v1/project/tasks/{id}` | 更新任务（新增） |
| DELETE | `/api/v1/project/tasks/{id}` | 删除任务（新增） |
| GET | `/api/v1/project/tasks/stats` | 任务统计（新增） |

### 前端页面清单

| 页面 | 说明 |
|:-----|:------|
| `main_page.dart` | 主页面 — TopBar + Feed + InputBar |
| `pages/launcher_page.dart` | World B — 启动器（身份/记忆/时间线/系统/交易/搜索/标签云） |
| `pages/profile_page.dart` | 关于我 |
| `pages/memory_page.dart` | 记忆查看（标签聚合 + 日历导航） |
| `pages/timeline_page.dart` | 时间线浏览 |
| `pages/search_page.dart` | 搜索 |
| `pages/project_status_page.dart` | 项目仪表盘 + 任务统计（增强） |
| `pages/project_task_page.dart` | 任务管理（新增） |
| `pages/trading_page.dart` | 持仓 + 交易记录 |
| `pages/life_quick_entry.dart` | 生活快速记录弹窗（新增） |

---

## 二、三大方向详细规划

### 方向 A: Layer 5 外部信息接入

**目标：** 让 AdaiOS 感知"外面正在发生什么"——股票行情、市场新闻。五层架构中唯一完全空白的层。

**现状：**
- 持仓数据存在于 `data/trading/positions.md`，但 `currentPrice` 是用户手工输入
- TradingContextContributor 注入持仓数据，无任何外部数据
- Feed/Brief 纯内部数据
- 没有任何 HTTP 客户端用于外部 API 调用

#### Phase 1: A 股日频行情接入（MVP）

```
kernel/market/                    ← 新增 Kernel 组件
    MarketDataSource.java           ← 接口：报价、新闻
    EastMoneyMarketDataSource.java  ← 第一个实现（A股日频）
    MarketData.java                 ← 行情 DTO（symbol/name/close/change%/volume）

domain/trading/
    MarketContextContributor.java   ← NEW：注入大盘指数 + 持仓个股行情
```

**改动清单：**

| 文件 | 改动 | 说明 |
|:-----|:-----|:------|
| **新建** `kernel/market/MarketDataSource.java` | 接口 | `quote(symbols)` + `marketIndices()` |
| **新建** `kernel/market/EastMoneyMarketDataSource.java` | 实现 | HTTP 调用东方财富接口，60 秒内存缓存 |
| **新建** `kernel/market/MarketData.java` | DTO | Java Record |
| **新建** `domain/trading/MarketContextContributor.java` | ContextContributor | `globalContext()` 注入大盘，"trading"注入持仓行情 |
| **修改** `TradingContextContributor.java` | 增强 | 持仓中包含最新价（目前无） |

**数据流：**
```
APP 打开 → Brief 请求 → MarketContextContributor → 东方财富 API → 大盘+持仓行情 → AI 上下文
```

**API 参考（东方财富免费接口）：**
```
https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&fields=f2,f3,f4,f12,f14&secids=1.000001,0.000001,0.300750
# f2=最新价, f3=涨跌幅%, f4=涨跌额, f12=代码, f14=名称
```

**缓存策略：** 60 秒内存缓存。非交易时间缓存到收盘。

#### Phase 2: 兴趣新闻推送

| 文件 | 改动 |
|:-----|:------|
| **修改** `BriefAppService.java` | 简报末尾加"市场速览"（大盘涨跌 + 1-2 条持仓相关新闻） |
| **新建** `kernel/market/NewsItem.java` | 新闻 DTO |
| **修改** `MarketDataSource.java` | 加 `news(keywords, limit)` 方法 |

#### Phase 3: Feed 嵌入外部信息

| 文件 | 改动 |
|:-----|:------|
| **修改** `FeedAppService.java` | Feed 末尾加入 1-2 条行情/新闻（`type=market`） |
| **修改** `FeedEntry.java` | 加 `market` 类型 |
| **修改** `apps/adai-app/` | FeedCard 渲染 `market` 类型 |

**不做：**
- 不做实时 WebSocket 推送（HTTP 轮询够用）
- 不做港股/美股/期货（先跑通 A 股）
- 不做技术指标计算（MACD/KDJ 留给以后）
- 不做自选股管理（用持仓驱动）

**工期：** Phase 1（2-3天）→ Phase 2（1-2天）→ Phase 3（1天）

---

### 方向 B: Project OS 加深

**目标：** 让 Project OS 从"能看到项目状态"变成"能管理项目任务"。AdaiOS 用 Project OS 管理自身的开发——自举。

**现状：** 轻量任务系统 Phase 1 已完成。以下是完整四个阶段的规划。

#### Phase 1: 轻量任务系统 ✅ 已完成

**领域模型：**
```java
public record Task(
    String id,           // task_yyyyMMdd_HHmmss
    String title,
    String description,
    TaskStatus status,   // TODO / DOING / DONE / CANCELLED
    String priority,     // P0 / P1 / P2 / P3
    List<String> tags,
    String rfcRef,       // 关联 RFC
    LocalDate createdAt,
    LocalDate updatedAt
) {}
```

**文件存储：** `data/project/tasks/YYYY/MM.md`，按月的 Markdown 文件，YAML frontmatter 格式。

**API 端点：**

| 方法 | 路径 | 说明 |
|:-----|:-----|:------|
| GET | `/api/v1/project/tasks?status=&tag=` | 任务列表筛选 |
| POST | `/api/v1/project/tasks` | 创建任务 |
| PUT | `/api/v1/project/tasks/{id}` | 更新任务 |
| DELETE | `/api/v1/project/tasks/{id}` | 删除任务 |
| GET | `/api/v1/project/tasks/stats` | 任务统计 |

**前端：** `project_task_page.dart` — 任务列表 + 创建表单 + 状态筛选 + 推进操作
**项目状态页增强：** `project_status_page.dart` — 增加任务统计卡片 + "管理"跳转

#### Phase 2: RFC 跟踪

将 RFC 文档状态化，与任务和 commit 关联。

| 文件 | 改动 |
|:-----|:------|
| **修改** `ProjectStatusAppService.java` | 解析 `docs/rfc/` 中每个 RFC 的 frontmatter 状态 |
| **修改** `ProjectContextContributor.java` | 注入 RFC 状态（proposed/approved/implemented/deprecated） |
| **修改** `ProjectKnowledgeSource.java` | 注入 RFC 状态 + 当前 sprint 任务 |
| **修改** `os/project-os/11-context/identity.md` | 更新为项目真实进度 |

每个 RFC 文件头部加入 frontmatter：
```markdown
---
title: 标题
date: 2026-07-25
status: implemented   // proposed | approved | implemented | deprecated
---
```

#### Phase 3: Project OS 知识自举增强

- ProjectContextContributor：注入 DOING 任务列表 + 最近 7 天 DONE 任务 + Commit-to-task 映射
- ProjectKnowledgeSource：读取 `os/project-os/11-context/rules.md`（17 条开发规则），`enrich("project")` 时注入

#### Phase 4: 前端任务板

- ProjectStatusPage（增强）：原有仪表盘 + 任务统计卡片
- LauncherPage 增加入口嵌套：`阿呆系统 → 任务`

**工期：** Phase 1（已完成）→ Phase 2（0.5天）→ Phase 3（0.5天）→ Phase 4（1天）

---

### 方向 C: Life OS 加深

**目标：** 让 Life OS 从"浮现生活记忆"变成"理解生活规律"——情绪趋势、习惯分析、生活洞察。

**关键约束：** 数据是核心瓶颈。当前 data/memory 只有 2 条记忆，均无生活标签。LifeKnowledgeSource 和 LifeContextContributor 因无数据而永远返回空。

#### Phase 0: 数据喂养 ✅ 已完成

在 InputBar 增加 🌿 按钮，弹出生活快速记录弹窗，四类预设模板：

| 类别 | 模板 |
|:-----|:------|
| 😊 心情 | 今天心情___，因为___ |
| 🏃 运动 | 今天运动了___分钟，感觉___ |
| 🍜 饮食 | 今天吃了___，感觉___ |
| 😴 睡眠 | 昨晚睡了___小时，质量___ |

选择模板后自动填充前缀，用户补充完整后发送到主输入流。

#### Phase 1: 情绪趋势（需 10+ 条数据）

Memory 已有 `sentiment` 字段（positive/negative/neutral），增强 LifeContextContributor 做趋势统计。

| 文件 | 改动 |
|:-----|:------|
| **修改** `LifeContextContributor.java` | `enrich("life")` 增加"最近 N 天情绪统计" |
| **修改** `LifeKnowledgeSource.java` | `globalContext()` 增加情绪轮廓 |

#### Phase 2: 习惯模式（需每种标签 5+ 条）

| 文件 | 改动 |
|:-----|:------|
| **新建** `kernel/life/HabitAnalyzer.java` | 星期几分组、时间段分组、频率检测 |
| **修改** `LifeKnowledgeSource.java` | `globalContext()` 加入习惯分析 |

#### Phase 3: 生活周报（需 2+ 周数据）

| 文件 | 改动 |
|:-----|:------|
| **新建** `application/LifeReviewAppService.java` | 生活周报生成 |
| **新建** `interfaces/LifeController.java` | `GET /api/v1/life/review?week=` |
| **修改** `BriefAppService.java` | 周一简报提示周报已生成 |
| **修改** `FeedAppService.java` | 周报作为 `type=life_review` 的 FeedEntry |

**数据依赖：**

| 阶段 | 所需数据 | 估计时间 |
|:-----|:---------|:--------|
| Phase 0 | 开始记录 | 立即（已完成） |
| Phase 1 | 10+ 条带情绪的生活记录 | 1-2 周日常使用 |
| Phase 2 | 每种标签 5+ 条 | 2-4 周 |
| Phase 3 | 2+ 周连续数据 | 3-4 周 |

**不做：**
- 不做健康数据接入（Apple Health / 华为运动健康——跨设备复杂）
- 不做日程管理/提醒（那是另一个垂直方向）
- 不做卡路里分析（数据来源不可靠）

**工期：** Phase 0（已完成）→ Phase 1（0.5天）→ Phase 2（1天）→ Phase 3（1-2天）

---

## 三、综合对比与建议

| 维度 | 方向 A (Layer 5) | 方向 B (Project OS) | 方向 C (Life OS) |
|:----|:----------------|:-------------------|:----------------|
| 用户价值 | 中 | **高** | 中（需数据积累） |
| 架构完整性 | ❌ 最大空白 | 🟡 已有骨架+任务系统 | 🟡 已有骨架 |
| 可行性 | ⭐⭐ 中（外部 API 不确定） | ⭐⭐⭐⭐ 高 | ⭐⭐⭐ 中（数据依赖） |
| 见效速度 | 慢 | **快**（Phase 1 已可用） | 慢（需积累数据） |
| 改动量 | 中 | 中 | 小 |
| 当前完成 | 0% | **Phase 1 ✅ (25%)** | **Phase 0 ✅ (25%)** |

### 建议执行顺序

1. **方向 B Phase 2**（RFC 跟踪）— 0.5 天，马上可做，进一步增强 Project OS
2. **方向 A Phase 1**（行情接入）— 2-3 天，填补 Layer 5 空白
3. **方向 C Phase 1**（情绪趋势）— 等数据积累后触发（0.5 天工作量）
4. **方向 B Phase 3-4**（自举增强 + 前端任务板）— 锦上添花
