# 任务开发文档（Task Log）

> **定位：** 新功能开发和系统改进的追踪文档。按模块组织，标注优先级、状态、关联 RFC。
> **对照：** `feature-reference.md` 定义"现在有什么"，此文档定义"接下来要做什么"。
>
> **文档版本：** v1.0 | **最后更新：** 2026-07-29

---

## 模块对照表

同 `issue-log.md` 的模块划分：

| 编号 | 模块名 | 参考文档章节 |
|:----:|:-------|:------------|
| M1 | 主页 Feed 流 | [§1](feature-reference.md#1-主页-feed-流) |
| M2 | 记录提交流 | [§2](feature-reference.md#2-记录提交流) |
| M3 | 问答会话流 | [§3](feature-reference.md#3-问答会话流) |
| M4 | FeedCard 卡片组件 | [§4](feature-reference.md#4-feedcard-卡片组件) |
| M5 | 简报模块 | [§5](feature-reference.md#5-简报模块) |
| M6 | 时间线模块 | [§6](feature-reference.md#6-时间线模块) |
| M7 | 记忆模块 | [§7](feature-reference.md#7-记忆模块) |
| M8 | Launcher 导航模块 | [§8](feature-reference.md#8-launcher-导航模块) |
| M9 | 交易模块 | [§9](feature-reference.md#9-交易模块) |
| M10 | 项目管理模块 | [§10](feature-reference.md#10-项目管理模块) |
| M11 | 搜索模块 | [§11](feature-reference.md#11-搜索模块) |
| M12 | 身份资料模块 | [§12](feature-reference.md#12-身份资料模块) |
| M13 | 标签模块 | [§13](feature-reference.md#13-标签模块) |
| M14 | 账号体系（多账号功能层）| RFC `20260802-multi-account-prep` |
| M15 | adai-admin 管理后台 | RFC `20260802-adai-admin` |
| M-AI | AI 提示词/解析 | — |

---

## 优先级定义

| 级别 | 含义 | 响应要求 |
|:----:|:------|:---------|
| P0 | 阻塞性 bug / 核心流程不通 | 立即处理 |
| P1 | 功能完整性问题 / 影响体验 | 本轮迭代内 |
| P2 | 体验优化 / 非核心功能 | 可排下一轮 |
| P3 | 长远架构 / 探索性 | 不定时 |

---

## 当前任务

### M2 — 记录提交流

#### MD1：记忆重建后刷新 Feed（P1，待确认）

| 字段 | 值 |
|:-----|:----|
| **描述** | `POST /api/v1/memory/rebuild` 后 Feed 中 `ai_note` 未更新，需要手动刷新页面 |
| **现状** | `createRecord` 会失效缓存但 `rebuild` 不会 |
| **方案** | `MemoryController.rebuild()` 失效 Feed 缓存，或前端 rebuild 后手动调 `_loadFeed()` |
| **涉及文件** | `MemoryController.java`, `main_page.dart` |
| **关联 RFC** | `20260727-memory-upgrade.md` P0 |
| **来源** | CLAUDE.md 已修复列表 |

---

### M3 — 问答会话流

#### MD2：Chat 关闭流程修复确认（P2，可延期）

| 字段 | 值 |
|:-----|:----|
| **描述** | ConversationController prompt 已改为去第一人称；前端改为只弹窗不兜底。但关闭后内容消失是否需要缓存？ |
| **现状** | close 后内容不保留，需要重新 ask 才能看到历史 |
| **待定** | 是否需要保留已结束对话的可读视图？ |

---

### M7 — 记忆模块

#### MD3：Memory Phase 2 — 生命周期 + 评价机制（P2）

| 字段 | 值 |
|:-----|:----|
| **描述** | 记忆需要引入：时效性评分（recency）、一致性评分（coherence）、冲突检测（contradiction） |
| **现状** | 记忆只存不评，所有记忆等权重 |
| **目标** | 记忆按质量和时效性排序，低质量自动降权 |
| **关联 RFC** | `20260727-memory-upgrade.md` Phase 2 |
| **前置** | Phase 0-1 已完成 |

#### MD4：Memory Phase 3 — Context Engine 深度集成（P2-P3）

| 字段 | 值 |
|:-----|:----|
| **描述** | Context Engine 当前只取最近 7 天记忆。Phase 3 应做到：相关记忆优先（按 tag/entity）、时效分级注入、冲突标记 |
| **现状** | 简单按日期取最近 N 条 |
| **目标** | 按相关度 + 时效性从 Memory OS 检索 |
| **关联 RFC** | `20260727-memory-upgrade.md` Phase 3 |
| **前置** | MD3 |

#### MD5：Memory Phase 4 — 知识反哺闭环（P3）

| 字段 | 值 |
|:-----|:----|
| **描述** | 高频确认的 pattern/preference 自动写入 Domain OS 知识库（os/*/11-context/） |
| **现状** | pattern/preference 存在记忆文件但不反哺 |
| **目标** | 自动从记忆提炼规则 → 人工审核 → 入库 |
| **关联 RFC** | `20260727-memory-upgrade.md` Phase 4 + `20260725-layer6-knowledge-feedback-loop.md` |
| **前置** | MD3 |

---

### M8 — Launcher 导航模块

#### MD6：Launcher 双指手势灵敏度优化（P2）

| 字段 | 值 |
|:-----|:----|
| **描述** | 双指滑动切换 World A/B 的门槛是 400px/s。部分用户觉得太灵敏/太迟钝 |
| **现状** | 200px/s（MainPage 上滑触发）+ 300px/s（Launcher 下滑返回）+ 400px/s（DualWorldShell 切换），阈值不一致 |
| **方案** | 统一为 350px/s，或加可配置 |
| **涉及文件** | `main.dart`, `main_page.dart`, `launcher_page.dart` |
| **关联 RFC** | `20260723-launcher-polish.md` |

---

### M10 — 项目管理模块

#### MD7：任务详情编辑页（P2）

| 字段 | 值 |
|:-----|:----|
| **描述** | 当前任务只能改状态（TODO/DOING/DONE）、没有独立的编辑页面。长按/点击任务卡片应可编辑全部字段 |
| **现状** | `ProjectTaskPage` 只有列表态 + 新建表单 + 状态点击流转 |
| **方案** | 点击任务卡片 → 编辑弹窗 / 独立编辑页，支持更新 title/description/priority/tags |
| **涉及文件** | `project_task_page.dart`, `api_service.dart` |

---

### M4 — FeedCard 卡片组件

#### MD8：FeedCard 交互规范文档化（P1，建议类）

| 字段 | 值 |
|:-----|:----|
| **描述** | 当前 4 种 CardMode × 3 种 Intent × 2 种 loading，条件分支 20+。建议写一份交互规范文档，定义每个状态视觉表现、转换条件、错误策略 |
| **现状** | 逻辑散落在 `feed_card.dart` 和 `main_page.dart` 中 |
| **来源** | `20260728-project-development-suggestions.md` 建议 A |

---

### M-AI — AI 提示词

#### MD9：STATEMENT 走轻量链路，QUESTION 走重链路（P2，架构建议）

| 字段 | 值 |
|:-----|:----|
| **描述** | 当前 STATEMENT 走完整 ContextEngine（identity + memory + tagIndex + search），QUESTION 反而走简化链路。实际提问更需要上下文 |
| **现状** | STATEMENT: 完整 ContextEngine → AiClient 分析模式 (temp=0.3)；QUESTION: DeepSeek 聊天模式 (temp=0.7) |
| **方案** | 交换权重：QUESTION 用最全面的上下文，STATEMENT 用轻量链路（只取 identity + 标签相关） |
| **来源** | `20260728-project-development-suggestions.md` 建议 B |

#### MD10：简报 prompt 统一为中文（P2/P3）

| 字段 | 值 |
|:-----|:----|
| **描述** | STATEMENT prompt 是中文指令，Brief prompt 是英文指令。建议统一 |
| **现状** | Brief prompt 全英文，但要求输出中文 |
| **方案** | Brief prompt 改为中文 |
| **涉及文件** | `BriefAppService.java` |

---

### M14 — 账号体系（多账号功能层，v1.0.0）

#### MD11：Account 存储 + 登录端点（P0）

| 字段 | 值 |
|:-----|:----|
| **描述** | 账号存储（File First，`data/accounts/`，含 disabled 状态）+ `POST /api/v1/auth/login`（校验 userId + 简单口令 → 返回身份）。不做注册，账号由 admin 创建 |
| **前置** | 多账号架构预留 ✅（userId 全链路透传，`f4efc5c`）|
| **涉及文件** | 新 `AccountRepository`、`AuthController`、api-spec §auth |
| **来源** | RFC `20260802-multi-account-prep.md` §六 |

#### MD12：前端登录页 + 登录态（P0）

| 字段 | 值 |
|:-----|:----|
| **描述** | 登录 UI + 本地存储身份 + 所有 API 请求带 `X-User-Id` 头 + 未登录重定向登录页 |
| **前置** | MD11（后端登录端点）|
| **涉及文件** | adai-app：`api_service.dart`、新登录页、入口路由 |
| **待定** | 鉴权强度：本机/单管理员用简单 token；复杂登录（JWT）不在 v1 范围 |

### M15 — adai-admin 管理后台（v1.0.0）

#### MD13：账号管理界面（Phase 0，P0）

| 字段 | 值 |
|:-----|:----|
| **描述** | 管理员界面：账号列表 / 建号（无注册）/ 禁用 / 删除 + admin 端点（复用 MD11 存储）|
| **前置** | MD11（账号存储）；adai-admin 前端形态定案 |
| **待定** | ① admin 前端形态：adai-app 内 admin 模式 vs 独立入口 ② bootstrap 管理员怎么建（首个账号）|
| **来源** | RFC `20260802-adai-admin.md` §3.0 |

#### MD14：数据管理基础（Phase 1，P1）

| 字段 | 值 |
|:-----|:----|
| **描述** | records 浏览/编辑/删除/批量 · memory 视图（kind/superseded/待办）+ 手动修正 · identity/tasks/positions 管理 · `data/` 文件树浏览 |
| **前置** | MD13（admin 框架就位）|
| **来源** | RFC `20260802-adai-admin.md` §3.1 |

#### MD15：系统操作台（Phase 2，P2）

| 字段 | 值 |
|:-----|:----|
| **描述** | Feed 预览（含 action/market）· 行情快照/复盘/知识反哺操作 · 记忆重建/重补/清理触发（`/memory/rebuild` 等）|
| **前置** | MD14 |
| **来源** | RFC `20260802-adai-admin.md` §3.2 |

#### MD16：知识浏览（Phase 3，P3）

| 字段 | 值 |
|:-----|:----|
| **描述** | `os/` 知识资产浏览（trading/life/project）+ 术语/规则查看 |
| **前置** | MD15 |
| **来源** | RFC `20260802-adai-admin.md` §3.3 |

---

## 已完成任务

### M7 — 记忆模块

| ID | 任务 | 完成 | 关联 RFC |
|:---|:-----|:----|:---------|
| DONE-1 | **Memory Phase 0：修复复读机** — summary/insight 拆分 | 07-28 | `20260727-memory-upgrade.md` P0 |
| DONE-2 | **Memory Phase 1：多类型化** — pattern/preference 支持 | 07-28 | `20260727-memory-upgrade.md` P1 |

### M4 — FeedCard + M3 — 问答流

| ID | 任务 | 完成 | 关联 |
|:---|:-----|:----|:-----|
| DONE-3 | **FeedCard 状态机修复** — ask 直接回复、close 显示总结、load more 滚动 | 07-28 | CLAUDE.md |
| DONE-4 | **Chat 关闭流程修复** — ConversationController prompt 去第一人称、前端弹窗处理 | 07-28 | CLAUDE.md |
| DONE-5 | **P0-P1 两轮修复** — 18 个问题的完整修复（详见 `issue-log.md`） | 07-28 | issue-log.md |

### M-AI — 提示词修复（本轮 07-29）

| ID | 任务 | 完成 | 说明 |
|:---|:-----|:----|:-----|
| DONE-6 | **#1 人称代词根除** — system prompt 同步改"避免人称代词" | 07-29 | `DeepSeekAiClient.java` system prompt |
| DONE-7 | **ND1 输出指令统一** — CHAT system prompt 升级为完整 JSON | 07-29 | 统一两处 prompt |
| DONE-8 | **前端 stripping 兼容多行 JSON** — `_removeTrailingJson` | 07-29 | 配合 ND1 |

### M4 — FeedCard（本轮 07-29）

| ID | 任务 | 完成 | 说明 |
|:---|:-----|:----|:-----|
| DONE-9 | **#3/#4 ended mode 正确赋值** — `_closeChat` 设 `mode: CardMode.ended` | 07-29 | `main_page.dart` |

### M2 — 记录提交流（本轮 07-29）

| ID | 任务 | 完成 | 说明 |
|:---|:-----|:----|:-----|
| DONE-10 | **ND2 死代码清理** — 删除 `handleDecision()` + `DecisionResponse` | 07-29 | `RecordController.java` |

---

## 路线总览

```
Phase                    P0  P1  P2  P3
────────────────────────────────────────
Memory Phase 0-1         ✅  ✅  —   —
Memory Phase 2           —   —   MD3 —
Memory Phase 3           —   —   —   MD4
Memory Phase 4           —   —   —   MD5
交互规范文档              —   MD8 —   —
提示词权重调整            —   —   MD9 —
任务编辑页               —   —   MD7 —
拉手灵敏度               —   —   MD6 —
简报 prompt 统一          —   —   MD10 —
记忆重建刷新 Feed         —   MD1 —   —
Chat 关闭保留历史         —   —   MD2 —
────────────────────────────────────────
v1.0.0（adai-admin + 多账号）：
  账号存储+登录           MD11 —   —   —
  前端登录页+登录态       MD12 —   —   —
  admin 账号管理          MD13 —   —   —
  admin 数据管理基础      —   MD14 —   —
  admin 系统操作台        —   —   MD15 —
  admin 知识浏览          —   —   —   MD16
────────────────────────────────────────
本轮完成（07-29）：
  #1 人称代词 ✓  ND1 prompt 统一 ✓  ND2 死代码 ✓  #3/#4 ended ✓
```
