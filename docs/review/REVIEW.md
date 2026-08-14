---
title: 项目审核全量状态报告
updated: 2026-08-15
last-review: 2026-08-15
baseline: 7b0a527 + 工作树（插件系统批）
mode: deep 增量（Domain=插件模型 + step-1）
---

> **结构（RFC `20260815-docs-governance` 减负）**：本文件只留「战略 + P0-P2 未修复 + 最近审核摘要 + 执行成本」；已修复详情见 `docs/reference/change-log.md` + git log；P3/观察项已迁移 `docs/reference/task-log.md`。

> 2026-08-15 deep 审核（范围：工作树未提交改动——第二步插件系统 T2.1-T2.10 + 第一步遗留，47 文件）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**战略×2 + P1×6 + P2×7 + P3×15**。P0 无。战略 S-3（重补路径 domain 未收敛）+ S-4（行情推送写侧未门控）；P1 六项（data/alice privacy 面 + 迁移补默认 vs PATCH 清空 + adai-web 索引漂移 + launcher Future.wait 耦合 + api-spec D1 通用化 + README 登记）；已沉淀检查点 B31-33 / F30-32 / D23-26。
> 2026-08-14 deep 审核（范围 `7b0a527..HEAD`，18 commits，带图 ask / 删除残留 / 图片交互批）：**P0×1 + 战略×2 + P1×2 + P2×2 + P3×14**。P0-1 + P1-1 + P1-2 + P2-1 + S-1 已修复出表，见已修复区；战略 S-2 与 P3 打磨项保留（P3 已迁移 task-log）。

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-15 | deep 增量（Domain=插件模型 + step-1）| 7b0a527 + 工作树 | backend/frontend/docs ×3 | 战略×2 + P1×6 + P2×7 + P3×15 | 0（审核不直接修）|
| 2026-08-14 | deep 增量（带图 ask + 删除残留 + 图片交互批）| 7b0a527..HEAD | 主会话（前端）+ docs/frontend agent×2 | P0×1 + 战略×2 + P1×2 + P2×2 + P3×14 | 5（P0-1 + P1-1 + P1-2 + P2-1 + S-1）|
| 2026-08-12 | 修复批 P（deep 新发现清理）| 7b0a527..HEAD | 主会话 + subagent×2 | 0 新 | 22（战略 #234 + P1 #235-238 + P2 #240-246 + P3 14 项）|
| 2026-08-12 | deep 增量（R1 AI 日志 + 图片追问 + 多账号 + CORS + 三连修）| 7aecf9d..HEAD | ×5 角色 | P0×2 + 战略×1 + P1×8 + P2×15 + P3 若干 | 0 |
| 2026-08-09 | deep 增量（多账号 + wasm + 数据迁移）| 7aecf9d..HEAD | ×5 角色 | 战略×3 + P1×2 + P2×10 + P3×14 | 0 |

> 更早审核（08-01 ~ 08-09）见「执行成本」表 + git 历史。

## 🔴 战略缺口（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 179 | 用户层 X-User-Id 零鉴权（任何人传任意 userId 即可读对应数据）；/accounts/available 已最小化（#215），但数据访问仍靠 header 注入无认证。真正收紧需登录体系 | `AccountController` / `WebConfig` | 📋 v1.0.1 立项（登录体系随多账号正式开放单独做）|
| S-2 | 附图文本写 4 份记录（caption×3 张图 + image_qa 问句）语义重复 | `MediaRecordAppService.askImages` + `_onSendMedia` | 📋 需产品确认 caption 归属策略 |
| S-3 | **D5 domain 收敛未铺满所有持久化入口**：`RecordRetryService.processRecord` 重补路径直接取 `understanding.domain()`（`:141`）未走 `pluginService.gateDomain`——无插件用户重补可能落盘 trading/project 标注，违反 RFC D5 核心不变量 | `RecordRetryService.java:141` | 🔴 未修（与 RecordController 口径统一即可）|
| S-4 | **插件门控读写侧不对称**：`MarketAlertService` 写侧只 `filter(Account::enabled)`（`:88`）未滤 trading 插件——无插件用户磁盘累积看不见的 push 残留 + 无谓行情轮询 | `MarketAlertService.java:88` | 🔴 未修（加 `hasPlugin(trading)` 与 Feed 口径对称）|

## 🔴 P1（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| P1-3 | **`data/*/project/` 未 gitignore + R2 通用化扩大隐私面**：`git check-ignore data/alice/project/tasks/2026/08.md` 实测未忽略（records/memory/ai-logs 均有保护、唯独 project/ 暴露）——任意私人记录派生为任务落入 git 跟踪目录 | `.gitignore` + `RecordToTaskLinker.java` | 🔴 未修（确认 project/ 是否应仅跟踪 owner + 清理 data/alice 联调残留）|
| P1-4 | **迁移补默认 vs PATCH 显式清空冲突（「删了又出现」K28 镜像）**：`AccountFileRepository.init()` 用 `plugins().isEmpty()` 补默认，但 `PATCH /accounts/{id}` 允许显式清空 → 管理端清空被下次启动迁移推翻 | `AccountFileRepository.java:73` | 🔴 未修（PATCH 禁清 owner 插件 或 迁移读字段存在性）|
| P1-5 | **adai-web 桌面壳插件加载后位置索引漂移，当前页静默跳模块**：`_loadPlugins` 异步，插件返回后中部插入 → `_current` 索引错位 | `desktop_shell.dart:62-83` | 🔴 未修（按稳定标识 label 重解析索引）|
| P1-6 | **adai-app Launcher `getMyPlugins` 并入致命 `Future.wait`**：插件接口失败 → 身份/标签/计数全降级 | `launcher_page.dart:70-98` | 🔴 未修（拆出单独 try/catch）|
| P1-7 | **api-spec 正文与代码契约漂移（D1 通用化未同步）**：任务 `sourceRecordId` 说明仍写「domain=project 转任务」，实现已去掉 domain 门槛；v3.18 changelog 漏 D1 | `api-spec.md:909,13` | 🔴 未修 |
| P1-8 | **README 索引未登记两个新增文档**：`20260814-domain-plugin-model` + `task-plugin-model` 未进 `docs/README.md` | `docs/README.md` | ✅ 2026-08-15（文档治理批修）|

## 🔴 P2（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| P2-2 | domain 关键词双源漂移：`detectDomainScene` 12 词 vs prompt 规则 8 词（丢 股票/大盘/行情/买卖/开发）→ 确定性路由与 AI 判定矛盾。修：`buildDomainRules` 由关键词常量拼接（单一真相源）| `ContextEngine.java:48-51 vs 587-590` | 🔴 未修 |
| P2-3 | `Account` 紧凑构造器 `List.copyOf(plugins)` 遇 null 元素 NPE：脏 `accounts.json` 的 `"plugins":[null]` → 账号/插件端点全挂 + 启动 fail-fast。修：构造器过滤 null 元素 | `Account.java:24-28` | 🔴 未修 |
| P2-4 | Chat 模式 system prompt domain 枚举未按插件收敛（`buildChatRequestBody` 硬编码 life/trading/project）——无插件用户空烧思维链 token | `DeepSeekAiClient.java:224` | 🔴 未修 |
| P2-5 | adai-web 插件拉取失败静默吞错无重试/无反馈：`catch (_) {}` → 有插件用户本次会话永久丢失模块入口 | `desktop_shell.dart:84-86` | 🔴 未修 |
| P2-6 | adai-admin `_togglePlugin` 快速连点 PATCH 全量覆盖竞态（后完成覆盖先完成）| `accounts_page.dart:94-110` | 🔴 未修（toggle 前从最新重取或禁点）|
| P2-8 | feature-reference（唯一功能真相源）零登记插件模型 | `docs/reference/feature-reference.md` | 🔴 未修 |

> P2 区历史观察项（#117 缓存分桶 / #149 账号细节 / #153 数据形态 / #176 交易校验）已迁移 `docs/reference/task-log.md`（2026-08-15 文档治理批）。P2-7 端点计数已由 status.md 单源化修复（CLAUDE.md 不再维护数字）。

## 🔴 P0 / P3

- **P0 未修复当前清零**（#226 ai-logs gitignore + P0-1 对话态崩溃已于 08-12/08-14 修复，见已修复区）
- **P3 打磨项全部迁移** `docs/reference/task-log.md`（2026-08-15 文档治理批；纯记录型已删除，可排期项进 task-log 待办）

## ✅ 已修复区（最近 10 条，一行摘要；详情见 `docs/reference/change-log.md` + git log）

| # | 摘要 | 修复 |
|:-:|:-----|:----:|
| S-1 | adai-web 多图 ask 同步（askBatch + 上限 3 + `_syncActiveCard`）| ✅ 2026-08-14 |
| P0-1 + P1-1 + P1-2 | 对话态发媒体崩溃/残留错乱/部分失败问句丢（`_syncActiveCard` + `_pendingAsk`）| ✅ 2026-08-14 |
| #169 + #257 | 问候语机械 + 测试覆盖确认出表（#14/#221/#222 已修；#234/#235 双端回归）| ✅ 2026-08-13 |
| 批 P | deep 31 项清 22：#234 分页终止 + #235-#238 P1 + #240-#246 P2 + P3 14 项 | ✅ 2026-08-12 |
| 批 O 收官 | #101/#103/#177 战略 + #19/#22/#115/#228 P2 + P3 顺手 14 项 | ✅ 2026-08-12 |
| #216 + #217 + #223 | CardMigration 判定收紧 + rewriteId 锚定 frontmatter + os/ 只读例外 | ✅ 2026-08-12 |
| #129 + #218 + #222 | promote 前端入口 + visual durationMs + 问候加中午段 | ✅ 2026-08-12 |
| #214 + #215 + #221 | 图片追问长度上界 + available 最小集 + 降级问候 emoji 按时段 | ✅ 2026-08-12 |
| #210 | AI 日志隐私治理（retention 30 天 + 分页/日期上界）| ✅ 2026-08-12 |
| #227 + #213 + #178 | 重补过滤禁用账号 + 追踪上下文请求级清理 + promote 融合提示 | ✅ 2026-08-12 |
| #184 | promote 脱敏（隐私红线）| ✅ 2026-08-12 |
| #226 + #223 | .gitignore 补 ai-logs + identity 白名单加固 | ✅ 2026-08-12 |

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-08-15 | deep 增量（Domain=插件模型 + step-1）| backend/frontend/docs ×3 | 3 | ~30min | 战略×2 + P1×6 + P2×7 + P3×15 | 0（审核只报告）|
| 2026-08-14 | deep 增量（带图 ask 批）| docs/frontend ×2 + 主会话 | 3 | ~40min | P0×1 + 战略×2 + P1×2 + P2×2 + P3×14 | 5 |
| 2026-08-12 | 修复批 P | subagent×2 + 主会话 | 2 | ~2h | 0 新 | 22 |
| 2026-08-12 | deep 增量（收官批 O 深度审核）| ×5 角色 | 5 | ~20min | 战略×1 + P1×5 + P2×7 + P3×18 | 0 |
| 2026-08-12 | 收官批 O | subagent×2 + 主会话 | 2 | ~4h | 0 新 | 22 |
| 2026-08-12 | 修复批 N/M/L + 顶部摘要 + 隐私批 | — | 0 | 逐批 20-40min | 0 新 | 14 |
| 2026-08-12 | deep 增量（R1 AI 日志批）| ×5 角色 | 5 | ~20min | P0×2 + 战略×1 + P1×8 + P2×15 + P3×5 | 0 |
| 2026-08-12 | P1 修复批 A-D + #184 | — | 0 | ~90min | 0 新 | 13 |
| 2026-08-09 | 批 K + deep 增量 + 验证修复 + 批 J | ×5 / 主会话 | 5 | ~2.5h | 战略×3 + P1×2 + P2×10 + P3×14 | 22 |
| 2026-08-03 | full 全量（v0.3.0 前）| ×5 角色 | 5 | ~30min | P0×1 + 战略×7 + P1×13 + P2/P3×30 | 0 |
| 2026-08-02 | full 全量（v0.1.0）| ×5 角色 | 5 | ~25min | 前端 3 + 后端 P1 4 + 文档 | 后端 P1 4 |
| 2026-08-02 | deep 增量（adai-web）| ×3 角色 | 3 | ~10min | P0×1 + 战略×3 + P1×9 + P2×8 | 0 |
| 2026-08-01 | 全量 + deep 增量 | ×3 / docs/knowledge | 5 | ~2.5h | 43 | 23 |

> 跑几轮后用成本数据决定全量审核频率（目前建议：增量随时，全量 1-2 周一次）。
