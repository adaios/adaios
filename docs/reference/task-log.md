# 任务开发文档（Task Log）

> **定位：** 新功能开发和系统改进的追踪文档。按模块组织，标注优先级、状态、关联 RFC。
> **对照：** `feature-reference.md` 定义"现在有什么"，此文档定义"接下来要做什么"。
>
> **文档版本：** v1.1 | **最后更新：** 2026-08-15（v1.1：REVIEW P3/观察项迁移入列，RFC `20260815-docs-governance`）

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

#### MD1：记忆重建后刷新 Feed（P1，✅ 已修 2026-08-07）

| 字段 | 值 |
|:-----|:----|
| **描述** | `POST /api/v1/memory/rebuild`（adai-admin 触发）后 Feed 中 `ai_note` 未更新，需手动下拉刷新 |
| **现状** | rebuild 在 adai-admin（独立 app），adai-app 无从感知 → Feed 内存态陈旧 |
| **方案（已实施）** | `DualWorldShell._toggleWorld` 世界切回 Feed 时递增 `ValueNotifier` → `MainPage.refreshTick` 监听重载 `_refreshFeed()`（不清 active 态，保持对话现场） |
| **涉及文件** | `main.dart`（壳层）、`main_page.dart`（refreshTick 监听） |
| **测试** | `feed_state_machine_test.dart` 新增「MD1 世界切回 Feed 刷新」用例 |

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

#### MD11：Account 存储 + 账号列表端点（P0）

| 字段 | 值 |
|:-----|:----|
| **描述** | 账号存储（File First，`data/accounts/`，含 disabled + 角色 admin/user）+ `GET /api/v1/accounts`（列表，供 adai-app 选号进入）。**无口令/登录校验**（现阶段：选择即进入，不校验）；账号由 adai-admin 创建（不做注册）|
| **seed** | `adai`（admin 角色，文件预置，2026-08-02 定）|
| **鉴权** | ⏸ 后补：v1 纯 userId 隔离 + 选择进入；口令/token 鉴权 app+后端统一后补 |
| **前置** | 多账号架构预留 ✅（userId 全链路透传，`f4efc5c`）|
| **涉及文件** | 新 `AccountRepository`、`AccountController`、api-spec §auth |
| **来源** | RFC `20260802-multi-account-prep.md` §六 |

#### MD12：adai-app 选号进入（P0）

| 字段 | 值 |
|:-----|:----|
| **描述** | adai-app 首屏：加载账号列表（`GET /accounts`）→ 用户选一个 → 本地存 userId → 所有请求带 `X-User-Id` → 进入主界面。**无口令**（选择即进入）|
| **鉴权** | ⏸ 后补：纯选择进入；口令/token 鉴权与后端统一后补（2026-08-02 定）|
| **前置** | MD11（账号列表端点）|
| **涉及文件** | adai-app：`api_service.dart`、新选号页、入口路由 |

### M15 — adai-admin 管理后台（v1.0.0）

#### MD13：adai-admin 账号管理（Phase 0，P0）

| 字段 | 值 |
|:-----|:----|
| **描述** | 后台建号工具：账号列表 / 建号（无注册，管理员建）/ 禁用 / 删除。**后台直接进入**（本机管理工具，无口令）|
| **形态** | ✅ 独立前端入口（2026-08-02 定）：adai-admin 独立于 adai-app 的构建/路由，复用其设计系统/组件 |
| **前置** | MD11（Account 存储）；seed `adai` 文件预置 |
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

---

## 待办迁移（2026-08-15 自 REVIEW P3/观察项）

> RFC `20260815-docs-governance`：REVIEW.md 只留「战略 + P0-P2 未修复」；可排期项入此区，纯记录/已实现项删除。来源编号保持 REVIEW 原编号可追溯。

### 可排期待办

| # | 任务 | 位置/说明 | 优先级 |
|:-:|:-----|:---------|:------:|
| 08-15 前端×2 | adai-admin 内置 adai 插件开关按 `isProtected` 门控（enabled/删除有保护、插件开关 Row 无——可关掉 owner 插件）| `accounts_page.dart:523-532` | P2 |
| 08-15 前端×2 | launcher 插件门控测试补「仅 trading」与「插件拉取失败」两分支 | `pages_widget_test.dart:365-405` | P3 |
| 08-15 后端×6 | `PluginService.enabledPlugins` 每次调用读 accounts.json 无缓存（statement/question/feed 每请求全量读）| `PluginService.java:32-36` | P2 |
| 08-15 后端×6 | `AccountController.isValidPlugins` 不查重（`["trading","trading"]` 合法落盘；消费端 Set 去重故行为正确）| `AccountController.java:132-134` | P3 |
| 08-15 后端×6 | `gateDomain` 对未知 domain 原样放行（AI 返回越界值保留，非本批引入）| `PluginService.java:46-55` | P3 |
| 08-15 后端×6 | `init()` 迁移新增启动期 findAll+writeAll 依赖（accounts.json 损坏即启动 fail-fast，可接受需知悉）| `AccountFileRepository.java:67-82` | 知悉 |
| 08-15 后端×6 | intent max_tokens 50→512 输出预算升 10 倍成本（可考虑关闭推理模式）| `DeepSeekAiClient.java:137` | P3 |
| 08-15 后端×6 | 迁移走 `writeAll` 非原子（#126 预存债）| `AccountFileRepository.java:139-149` | P3 |
| 08-15 docs×7 | RFC 20260814 frontmatter「四决策」vs 正文 D1-D5 五条（D25）| `docs/rfc/20260814-domain-plugin-model.md:5` | P3 |
| 08-15 docs×7 | 根 CLAUDE.md 架构树缺 MeController + kernel/plugin（adai-core CLAUDE.md 已补、根未同步）| 根 `CLAUDE.md` | P3 |
| 08-15 docs×7 | api-spec domain 判定关键词与代码不一致（trading 缺 股票/大盘/行情/买卖、project 缺 开发，与 P2-2 同源）| `docs/architecture/api-spec.md` | P3 |
| 08-15 docs×7 | task-plugin-model T1.3 行仍写 owner 过滤、实现已被插件门控取代，未标 superseded | `docs/reference/task-plugin-model.md:19` | P3 |
| 08-14 前端×8 | ImagePicker `limit` Web 静默失效（选图上限依赖截断兜底）| `input_bar.dart` | P3 |
| 08-14 前端×8 | `_truncateForSnack` UTF-16 substring 可能劈开 emoji（SnackBar 半字符）| `main_page.dart` | P3 |
| 08-14 前端×8 | `_mimeTypeOf` HEIC 默认误标 `image/png` | `input_bar.dart` | P3 |
| 08-14 前端×8 | `Navigator.pop` 盲弹未守卫 | `main_page.dart:428` | P3 |
| 08-14 前端×8 | `_showImageLimitToast` 在 setState 内副作用 | `main_page.dart:663` | P3 |
| 08-14 前端×8 | askBatch 无「正在看图…」占位 | `main_page.dart` | P3 |
| 08-14 前端×8 | 多图问答完整回答只在首图卡气泡需手动点开 | `main_page.dart` | P3 |
| 08-14 前端×8 | 并发上传进度条互相覆盖 | `input_bar.dart` | P3 |
| 08-14 docs×6 | api-spec v3.15 changelog 漏 #247 | `docs/architecture/api-spec.md` | P3 |
| 08-14 docs×6 | `apiEndpoints: 21` 示例陈旧（实 51）| `docs/architecture/api-spec.md` | P3 |
| 08-14 docs×6 | ask-batch 错误列表未限问句分支 | `docs/architecture/api-spec.md` | P3 |
| 08-14 docs×6 | frontend-reference `AskResponse` 命名应为 `AskBatchResponse` | `docs/architecture/frontend-reference.md` | P3 |
| 149 | 多账号细节：accounts.json 无锁 / 删号不清理数据 / 允许创建 default | `AccountFileRepository` / `AccountController` | P2（v1.0.1）|
| 153 | 数据形态失衡观察：08 月 131/133 条为对话摘要，原始 note <2% | `data/adai/records/2026/08/` | 观察 |
| 176 | 交易录入无严格校验：TradeRequest 仅 @NotBlank/@Positive，建议三层校验（格式/quote 存在性/名称模糊比对）；用户指出输入校验+持仓分析+反哺流程整体待打磨 | `TradeRequest` / `TradingAppService.recordTrade` | P2（v1.0.0 后批次）|
| 117 | 缓存 key 分桶未测（价值低，留待多账号批）| `test/` | P3 |
| 163 | adai-admin 记录页只看得到今天（Feed 契约只返回当天）| `data_api_store.dart:60-76` | P2 |
| 166 剩余 | MediaController 上传 413 + emoji 截断已修；剩余 market id 同秒碰撞 | 后端多处 | P3 |
| 168 | os/ 知识 P3 杂项：空文件 / 重复 JSON / PNG 入库 / life-os 引用漂移 / project-os 路径漂移 / 未索引标签 / gitignore 单层 / decision 死分支 | `os/` 多处 | P3 |
| 171 | 优化方向（非问题）：项目页「项目记录」聚合视图 + 记录可标记类型（问题/建议）并流转为任务 | adai-app 项目页 + domain 体系 | 产品方向 |
| 172 | 记忆页 superseded 记忆仍显示「待办/已完成」标记（语义矛盾）；建议隐藏 actionable 标记 + 灰角标说明 | `memory_page.dart:239-261` | P3 |
| 202 剩余 | `userTradeLocks` 按 userId 无界累积 / `AiClient.generate(ctx, null)` 默认 system 仍是 JSON 分析指令与生成语义矛盾 | 后端多处 | P3 |
| 229 剩余 | 首轮把「图片摘要文本」渲染成用户气泡（应居中提示）/ 折叠渐隐遮罩色不一致 / #15 折叠对超长 active 卡不设上限 / `main()` 首帧 await 延迟 | `main_page.dart:926-930` / `feed_card.dart` / `main.dart:11-22` | P3 |
| 121 | 无最小宽度/响应式保护（批 H 已评估：桌面端专用产品、常规宽度无问题，极窄窗口才压缩，低优先级）| `desktop_shell.dart` | 已评估 |
| 125 剩余 | README 默认模板 / hover 无手型 / 圆角 token 散落 | 多处 | P3 |
| 263 | 99-inbox 预存项：`7家公司IPO...json` 与 `-gemini.json` MD5 重复；`AI 图形知识工程.md`/`outline.md` 缺尾部换行（数据卫生，下次 os 治理批处理）| `os/trading-os/99-inbox/` | P3 |
| 08-15 deep 后端 | 存量越界 domain 标注不清理（S-3 只防新增；插件上线前无插件用户已落盘的 trading/project 标注不纠正，可一次性迁移清理）| `data/*/records/` | P3 |
| 08-15 deep 后端 | PATCH plugins 契约语义（传 `null` 保留旧值、传 `[]` 清空）需在 api-spec 明确「清空须传空数组」| `docs/architecture/api-spec.md` | P3 |
| 08-15 deep 后端 | ANALYSIS 模式 system 指令硬编码全量 domain（`life/trading/project之一` 与收敛后 prompt 矛盾，落盘有 gateDomain 兜底仅判定质量受影响）| `DeepSeekAiClient.java:337` | P3 |
| 08-15 deep 后端 | Timeline 显示 conversation 记录 vs Feed 排除的口径差异（若为有意设计请注释说明）| `TimelineProjection.java:64` vs `FeedAppService.java:117` | P3 |
| 08-15 deep 前端 | web 插件重试失败 SnackBar 队列堆积（连续失败多条依次播放，show 前 `clearSnackBars()`）| `desktop_shell.dart:96-100` | P3 |
| 08-15 deep 前端 | web 插件重试成功后失败 SnackBar 残留（成功路径 `hideCurrentSnackBar()`）| `desktop_shell.dart:93-101` | P3 |
| 08-15 deep 前端 | `_currentLabel`/`_visited` 硬编码 `_allEntries.first.label` 与条目顺序隐式耦合（抽常量或按首个 plugin==null 动态解析）| `desktop_shell.dart:63-64` | P3 |
| 08-15 deep 前端 | web fallback `_items.first` 无空列表防御（当前 6 基础服务恒非空，全门控化会 RangeError）| `desktop_shell.dart:88-91` | P3 |
| 08-15 deep 前端 | 插件中部插入导致已访问页 widget 槽位移 → 页面 state 重置（IndexedStack children 按 label 加 `ValueKey` 保活；P1-5 测试测不出状态重置）| `desktop_shell.dart:123-129` | P3 |
| 08-15 deep 前端 | `_select(int i)` tap 时重解析 `_items[i]`（build↔tap 间列表变更亚帧窗口；tap 直接传 label/条目）| `desktop_shell.dart:104-110` | P3 |
| 08-15 deep 前端 | admin 插件 toggle 触发全页 spinner 闪烁（`_load()` 置 `_loading=true`；改 `_load(silent: true)` 静默刷新，F4 同类）| `accounts_page.dart:41-45,186-190` | P3 |
| 08-15 deep 前端 | admin `latest == null` 防御分支静默丢操作且实际不可达（删分支或给一次性反馈）| `accounts_page.dart:104-107` | P3 |
| 08-15 deep docs | CLAUDE.md 目录树 `apps/` 重复两次（51-52 与 73-82 行，瘦身引入）| `CLAUDE.md:47-83` | P3 |
| 08-15 deep docs | CLAUDE.md:131 表格头与正文段落同在一行（Markdown 渲染异常，拆行）| `CLAUDE.md:131` | P3 |
| 08-15 deep docs | docs/README:75 引用 `20260726-next-phase-direction.md`「已归档至 inbox/」——该文件 08-15 已删（断链，改指 rfc 去向或删除）| `docs/README.md:75` | P3 |
| 08-15 deep docs | `docs/rfc/20260730-health-management-scenario.md` 无 YAML frontmatter（D13：污染 /project/status rfcItems status=unknown）| `docs/rfc/20260730-health-management-scenario.md` | P3 |
| 08-15 deep docs | 根 CLAUDE.md 当前焦点批缺「展示层聚合（S-2）」登记（REVIEW/change-log 已出表，可接受）| `CLAUDE.md:242-244` | P3 |

### 已删除（纯记录/已实现，2026-08-15 出表）

- **#173 带图提问 intent=question** → 已被 Phase 1 带图 ask（`ask-batch` 问句分流）实现
- **#262 stripCodeFences 边界** → 标注"可接受记录"（复盘正文罕见含代码块，无需修）

## 全维度走查（2026-08-15 首轮，7 官）

> docs/ai/process/audit.md 首轮走查。P0/战略/P1 在 REVIEW.md 走查区；以下为 P2/P3 待办。

### P2 待办

| # | 任务 | 位置/说明 | 优先级 |
|:-:|:-----|:---------|:------:|
| W-P2-1 | 双端对拍修复：图片重试字节（web F27）、删除确认（app U16）、全图 Dialog 接入（3 处）、搜索错误态（web）| 多文件 | P2 |
| W-P2-2 | 记忆页日期连点异步乱序守卫（web 有 app 无）| `memory_page.dart:54-73` | P2 |
| W-P2-3 | admin 插件 toggle 串行队列 catchError 恢复（非 ApiException 永久 error）| `accounts_page.dart:96-117` | P2 |
| W-P2-4 | Feed 缩略图 cacheWidth 降采样（app/web Image.network 全分辨率解码）| `feed_card.dart:418` / `desktop_feed_card.dart:238` | P2 |
| W-P2-5 | web 图片成功卡 content=用户 caption（#245 同步）| `feed_page.dart:200-212` | P2 |
| W-P2-6 | project tasks 写端点加 project 插件门控（B40 对称）| `ProjectStatusController.java` | P2 |
| W-P2-7 | accounts.json 原子写（writeAll 截断写 → 损坏全系统起不来）| `AccountFileRepository.java:191` | P2 |
| W-P2-8 | TagIndex 并发 RMW 锁（cacheByUser 非线程安全）| `TagIndexService.java:53-95` | P2 |
| W-P2-9 | 交易落 Record 流水线（recordTrade 只改 positions.md，不进 Timeline/Search/Memory）| `TradingAppService.java:48-92` | P2 |
| W-P2-10 | MemoryService 序列化三缺陷：suggestion 未单行化 / createdAt now() 回退 / contains 哨兵匹配 | `MemoryService.java:689-803` | P2 |
| W-P2-11 | 数据卫生：alice 越界 domain 标注 + 连调残留 / positions symbol-name 错配 / os/ 空文件+PNG+重复 JSON | data/alice + positions.md + os/ | P2 |
| W-P2-12 | roadmap 状态漂移：数据冻结标「待做」实为定稿、#144/#106/#112 已修仍列待清；§3.2 补插件模型（S-W1）| `product-roadmap.md` | P2 |
| W-P2-13 | feature-reference 附录 API 全集补 7 端点 + §16 PATCH 全量→合并语义 | `feature-reference.md` | P2 |
| W-P2-14 | api-spec §5 trades/review 补 403 插件门控契约 | `api-spec.md:439-474` | P2 |

### P3 打磨（选录）

| # | 任务 | 位置/说明 | 优先级 |
|:-:|:-----|:---------|:------:|
| W-P3-1 | IndexedStack children 按 label ValueKey + tap 传 label（F36 闭环）| `desktop_shell.dart:125-129` | P3 |
| W-P3-2 | 失败 SnackBar 双端统一 clear/hide（F35 补全）| 双端 | P3 |
| W-P3-3 | 「最后记录」恒显「刚刚」（updatedAt 未传值）| `main_page.dart:1074-1093` | P3 |
| W-P3-4 | `_closeChat` 裸 firstWhere → indexWhere（#205 口径）| `main_page.dart:255` | P3 |
| W-P3-5 | admin setPlugins 死接口清理（页面已全走 mergePlugins）| `account_api_store.dart:23` | P3 |
| W-P3-6 | DTO 契约小漂移：PromoteResultDto.message / sourceRecordId / lastConfirmed / totalCost | 三端 api_dto | P3 |
| W-P3-7 | promoteReview 重复 Content-Type header | 双端 api_service | P3 |
| W-P3-8 | web 全图 Dialog errorBuilder（404 白框）| `desktop_feed_card.dart:267-280` | P3 |
| W-P3-9 | web FilePicker 无压缩（全量字节内存 + 上传）| `feed_page.dart:976-1005` | P3 |
| W-P3-10 | web 对话态发媒体不退出 active chat（F25 对拍残留）| `feed_page.dart:163-227` | P3 |
| W-P3-11 | 时间线第三人称 ai_summary 自然化（P1-W4 细化）| `TimelineProjection.java` | P3 |
| W-P3-12 | 卡片删除 substring 误删（card_123 命中 card_1230）| `CardFileRepository.java:162-176` | P3 |
| W-P3-13 | surrogate pair 拆断（Memory.fromContentFallback/GlmResponseParser substring(0,100)）| 2 文件 | P3 |
| W-P3-14 | ImageQaFormatter 问句含「答：」误切 | `ImageQaFormatter.java:24-34` | P3 |
| W-P3-15 | image_qa Feed intent 错位（持久化 question 输出 log）| `FeedAppService.java:215` | P3 |
| W-P3-16 | ContextEngine.loadMemorySummary → touchActive 读路径写文件 | `ContextEngine.java:342` | P3 |
| W-P3-17 | LocalFileStorage stripUserPrefix 与 resolve 默认值不一致（null vs default）| `LocalFileStorage.java:160-181` | P3 |
| W-P3-18 | rebuild 幂等死角 + retryCards 空白 summary 累积 | 2 文件 | P3 |
| W-P3-19 | AccountRepository.save/delete 无锁 RMW（mergePlugins 已锁）| `AccountFileRepository.java:142-189` | P3 |
| W-P3-20 | promote 脱敏漏洞（[\d.]+ 不匹配 1,400 带逗号）| `TradingController.java:272-284` | P3 |
| W-P3-21 | 记忆噪声 decision 127/172 误标（#153 复发）+ 3 条悬空 recordId | data/adai/memory | P3 |
| W-P3-22 | docs/ai roles frontmatter 断链 14 处 + context-reviewer 外部路径 + frontmatter-spec related（自伤）| `ai-engineering/roles/*` | P3 ✅ 已修（W1 批）|
| W-P3-23 | 审查官计数 7 vs 8 同步 + lines 字段校准 16/17 | ai-engineering + _index | P3 ✅ 已修（W1 批）|
| W-P3-24 | status.md 端点 51→52 + 「15 Controller」→16 + v1.0.0.md/task-plugin-model 数字快照 | status/releases | P3 |
| W-P3-25 | implemented RFC 残留「待确认」（multimodal/record-task）+ review-skill RFC 引用旧 .claude 路径 | 3 RFC | P3 |
| W-P3-26 | api-spec feed 示例缺 domain + docs/_index 漏 memory-os-design | 2 文档 | P3 |
| W-P3-27 | os/ 各 README 引用漂移（life-os K19/project-os 漏 rules/PROJECT_SUMMARY 过期）| os/ | P3 |
