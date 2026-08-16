---
title: 项目审核全量状态报告
updated: 2026-08-16
last-review: 2026-08-16
baseline: 7734d99..HEAD（2 commits，框架+插件形态 G-1~G-6）
mode: deep 增量（框架+插件 G-1~G-6 审查）
---

> **结构（RFC `20260815-docs-governance` 减负）**：本文件只留「战略 + P0-P2 未修复 + 最近审核摘要 + 执行成本」；已修复详情见 `docs/reference/change-log.md` + git log；P3/观察项已迁移 `docs/reference/task-log.md`。

> 2026-08-16 deep 增量（范围 `7734d99..HEAD`，2 commits / 63 文件）：守护 7 PASS / 0 HIT；派 backend/knowledge/docs ×3。**P0 无。战略×4 + P1×4 + P2×9（合并去重后）**。核心：application.yml:60 残留 11-context（三官交叉印证，G-4 声称"全引用同步"的缺口，P1）、总纲 §五 现状表自相矛盾（S1）、R81 占比分母不含现金（P1）、update-current.sh 非幂等（P1）。审查只报告未修；新增检查点 B43-B46 / K36-K39 / D44-D46。
> 2026-08-16 修复批（app-polish 审查落地）：**P-be-01 维护端点迁入 /admin/** 鉴权（安全）+ admin 收敛为纯治理（P-role 系列）+ app 补记忆修正/待办完成（P-role-02/P-app-03）+ 带图发图即对话 + 交易建议引擎**。后端 499 · app 112 · admin 34，全部出表。
> 2026-08-15 deep 审核（范围 `7b0a527..HEAD`，33 commits / 181 文件）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**P0 无。战略×2 + P1×5 + P2×7 + P3×21（P3 迁移 task-log）**。**修复批 S + S2 共 12 项已出表**（P1-B1-B4/D1 + P2-B1/B2/R1/R2/R3/D1/D2，后端 440 · app 94 · admin 33）；**战略剩余仅 #179（v1.0.1 登录体系）**；已沉淀检查点 B34-36 / F33-36 / D27-29。
> 2026-08-15 上午 deep 审核（范围：工作树未提交改动——第二步插件系统 T2.1-T2.10 + 第一步遗留，47 文件）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**战略×2 + P1×6 + P2×7 + P3×15**。P0 无。战略 S-3（重补路径 domain 未收敛）+ S-4（行情推送写侧未门控）；P1 六项；已沉淀检查点 B31-33 / F30-32 / D23-26。S-3/S-4/P1 全部出表（批 Q/R）。
> 2026-08-14 deep 审核（范围 `7b0a527..HEAD`，18 commits，带图 ask / 删除残留 / 图片交互批）：**P0×1 + 战略×2 + P1×2 + P2×2 + P3×14**。P0-1 + P1-1 + P1-2 + P2-1 + S-1 已修复出表；S-2 展示层已修（层 2 数据层另立 v1.0.1）。

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-16 | deep 增量（框架+插件 G-1~G-6）| 7734d99..HEAD（2 commits）| backend/knowledge/docs ×3 | 战略×4 + P1×4 + P2×9 | 0（审核不直接修）|
| 2026-08-15 | deep 增量（批 Q/R + 展示层聚合 + 发布核对）| 7b0a527..HEAD | backend/frontend/docs ×3 | 战略×2 + P1×5 + P2×7 + P3×21 | 0（审核不直接修）|
| 2026-08-15 | deep 增量（Domain=插件模型 + step-1）| 7b0a527 + 工作树 | backend/frontend/docs ×3 | 战略×2 + P1×6 + P2×7 + P3×15 | 0（审核不直接修）|
| 2026-08-14 | deep 增量（带图 ask + 删除残留 + 图片交互批）| 7b0a527..HEAD | 主会话 + docs/frontend agent×2 | P0×1 + 战略×2 + P1×2 + P2×2 + P3×14 | 5（P0-1 + P1-1 + P1-2 + P2-1 + S-1）|

> 更早审核（08-01 ~ 08-12）见「执行成本」表 + git 历史。

## 🔴 战略缺口（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 179 | 用户层 X-User-Id 零鉴权（任何人传任意 userId 即可读对应数据）；数据访问靠 header 注入无认证。真正收紧需登录体系 | `AccountController` / `WebConfig` | 📋 v1.0.1 立项 |
| FP-S1 | **总纲 §五「现状对照」与同批 gap 文档矛盾且表内自相矛盾**：正式文档（active）仍标 4 项缺口（行情跟插件 ⚠️/隔离 ⚠️/jar 边界 ⚠️/Agent ⬜），而 gap 已全 ✅；第 3 行标「G-1 拨正 ✅」第 4 行却标「行情服务跟插件走 ⚠️ 缺口」——G-1 恰是该项 | `docs/architecture/framework-plus-plugin-model.md:103-106` | 按 gap 刷新全 ✅ 或标注快照日期（D46）|
| FP-S2 | **确定性引擎口径与知识真相源无联动校验**：TradingRuleEngine 自述「knowledge 为真相源」，os 侧改 R66/R81 语义引擎不感知 | `TradingRuleEngine` / `os/trading-engine/engine/rules-api.md` | 契约测试读 rules.md 断言关键判定词（B44）|
| FP-S3 | **R81 判定口径被规格固化**：rules-api.md §3 把「positionPercent=市值/总市值」写进规格，分母偏差制度化（见 FP-P2）| `os/trading-engine/engine/rules-api.md` §3 | 修分母口径后同步规格 |
| FP-S4 | **update-current.sh 名不副实**：头注释声明"重组信号区块"，实际只注入 2 条来源注记+时间戳（SIGNAL_LINES 抽取后未写入）；且非幂等 | `os/trading-engine/09-scripts/update-current.sh` | 实现真重组或改声明；幂等化（K37）|
> S-R1（app 插件失败 SnackBar+重试，双端对拍）与 S-R2（服务端合并插件端点，竞态根治）已出表（2026-08-15，见已修复区）。S-2（展示层聚合）已出表；数据层整体化 RFC `20260815-media-event-unification` approved 排 v1.0.1；S-3/S-4 已出表（批 Q）。

## 🔴 P1（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| FP-P1 | **application.yml 默认路径残留 11-context → 交易知识注入静默断链**：`trading-engine-path: ${ADAI_TRADING_KNOWLEDGE_PATH:../../os/trading-engine/11-context}`（yml 覆盖 @Value 默认，目录已 git mv）→ TradingKnowledgeSource 读不存在目录 → trading 插件用户问答知识注入静默为空。G-4 声称"全引用同步"的缺口，三官交叉印证（B43/D45/K36）| `services/adai-core/src/main/resources/application.yml:60`（含 build/ 副本）| 默认值改 `knowledge/context`；核对 .env/ADAI_TRADING_KNOWLEDGE_PATH |
| FP-P2 | **R81 硬判定分母不含现金 → 单票持仓账户恒发"参考 reduce"**：positionPercent=单票市值/Σ持仓市值（无 cashBalance），R81 语义是占总资金 1/4~1/5；单仓+大额现金 → 占比恒 100% → 恒 OVER_WEIGHT（B46）| `TradingAdviceAppService.buildPositionViews` L257-270 + `rules-api.md` §3 | 占比改用总资产（持仓市值+现金余额，`PositionRepository.cashBalance` 已存在）；同步规格 |
| FP-P3 | **update-current.sh 非幂等 + 时间戳可骗门禁**：重复运行注记堆叠（1→3 条）；数据未刷新却戳"更新时间"到当天，build-engine >30 天门禁可被绕过 | `os/trading-engine/09-scripts/update-current.sh` | 注入前查重；时间戳语义区分「文件刷新」与「状态更新」（K37）|
| FP-P4 | **R66 口径偏差（现价 vs 规则文本"收盘跌破"）**：盘中插针即触发「必须 clear」硬信号，引擎 message 引用"收盘跌破"与规则原文不一致（K38）| `DefaultTradingRuleEngine.java` + `rules-api.md` §2 | 注明口径偏差或改用收盘价判定 |
> **P1 当前清零**（2026-08-15 修复批 S + S2 全部出表：P1-B1/B2/B3/B4 + P1-D1，见已修复区）。2026-08-16 框架+插件审查新增 FP-P1~P4（未修）。

## 🔴 P2（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| FP-P2a | **硬判定信号无输出侧校验**：引擎 verdict 算出，但 parseLlmAdvice 不校验 LLM 输出——BREACHED 而 LLM 输出 hold 原样透出，「必须 clear」只是 prompt 软指令 | `TradingAdviceAppService.parseLlmAdvice` | verdict↔suggestion 冲突覆盖/告警（B45）|
| FP-P2b | **R81「100万以下」前提未评估**：大资金账户也收到 25% 上限硬信号，可能与注入的 R82-R95 冲突 | `DefaultTradingRuleEngine.evaluatePosition` | 按资金量级分档或注明 |
| FP-P2c | **测试缺口**：硬判定信号段无直接断言（茅台 96.3% 触发 OVER_WEIGHT 未断言）；DefaultTradingRuleEngineTest 缺 currentPrice≤0 分支 | `TradingAdviceAppServiceTest` / `DefaultTradingRuleEngineTest` | 补断言与分支 |
| FP-P2d | **gap 无 frontmatter**：reference/_index 登记 active 但文件级状态无从核对（guard-meta scope 不含 reference/*.md）| `docs/reference/framework-plugin-gap.md` | 补 frontmatter；新建 docs/** 必须带 frontmatter 纳入 guard（D44）|
| FP-P2e | **docs/README.md 未登记新文档**：架构表无总纲、RFC 表缺 20260816 三篇、reference 区无 gap（D15/D24）| `docs/README.md` | 补登记 |
| FP-P2f | **三阶段 RFC 状态未随落地滚动**：status 仍 draft，Phase A-D 已全 ✅；§三「能力焊在 adai-core 未成 jar」在 G-3 后过时 | `docs/rfc/20260816-trading-agent-plugin-model.md` | 补实施记录/升 approved |
| FP-P2g | **gap 把总纲指为 RFC**：应指向已提位的正式架构文档 | `docs/reference/framework-plugin-gap.md:3` | 改指 `architecture/framework-plus-plugin-model.md` |
| FP-P2h | **update-current.sh 注记绝对路径入库 + 零收录**：注记含 ENGINE_ROOT 绝对路径；CLAUDE.md 未收录该脚本用法 | `update-current.sh` / `os/trading-engine/CLAUDE.md` | 相对路径 + CLAUDE.md 收录 |
| FP-P2i | **输出样板编号漂移**：agent-skill E1-E25 vs 实际 E1-E30；CLAUDE.md 第四层 R1-R60 vs 实际 R1-R120 | `os/trading-engine/output/agent-skill.md` / `CLAUDE.md` | 编号对拍（K39）|
> **P2 当前清零**（2026-08-15 修复批 S + S2 全部出表：P2-B1/B2/R1/R2/R3/D1/D2，见已修复区；历史观察项已迁移 task-log）。
> 2026-08-16 框架+插件审查新增 FP-P2a~i（未修）。

## 🔴 P0 / P3

- **P0 未修复当前清零**
- **P3 打磨项全部迁移** `docs/reference/task-log.md`（2026-08-15 两轮 deep 新增 21 项：后端 6 + 前端 8 + docs 7，已入待办迁移区）

## ✅ 已修复区（最近 10 条，一行摘要；详情见 `docs/reference/change-log.md` + git log）

| # | 摘要 | 修复 |
|:-:|:-----|:----:|
| S-R1/S-R2（deep 战略项）| launcher 插件失败 SnackBar+重试（双端对拍 web）+ 服务端合并插件端点 `PATCH /accounts/{id}/plugins`（账号级锁原子 add/remove，根治 PATCH 全量并发互覆）+ admin 改走合并语义 + 内置 admin 插件服务端保护；api-spec v3.20；后端 446（+6）| ✅ 2026-08-15 |
| 修复批 S2（P2-B2/R2/R3 + P1-D1 + P2-D1/D2）| Account null userId 拒绝（全局中断）+ admin 内置插件开关 isProtected 门控 + launcher 测试补分支 + review-context 断链 + RFC/docs 状态同步；后端 440（+1）· app 94（+2）| ✅ 2026-08-15 |
| 修复批 S（P1-B1-B4 + P2-B1 + P2-R1）| deep 审核后端/前端修复：domainEnum 去引号语义（CHAT 双重引号根治，补最终拼接断言）+ 时间线聚合跨天/intent/歧义边界 + 图片 domain gateDomain + trading 写入口门控（403）+ admin 插件 toggle 串行队列；后端 439（+6）· admin 33（+1）| ✅ 2026-08-15 |
| S-2（展示层）| 「一次输入 = 一个事件」：时间线多轮 chat 每会话单条 + 带图 ask image_qa 聚合为图文事件（引用图不单独成条，缩略图取首图）；Feed 同口径；前端零改动；数据层整体化另立 v1.0.1；后端 433（+4）| ✅ 2026-08-15（层 2 另立）|
| 批 R（P1-5/P1-6/P2-5/P1-7/P2-8）| 前端+文档：adai-web 壳 label 重解析防索引错位 + 插件失败 SnackBar 重试；adai-app Launcher 插件接口拆独立 try/catch；api-spec D1 通用化同步；feature-reference 补插件模型章节；web 47（+1）| ✅ 2026-08-15（**P2-6 除外，reopen 见 P2-R1**）|
| 批 Q（S-3/S-4/P1-4/P2-2/P2-3/P2-4）| 后端插件门控/健壮性六连修：重补路径 gateDomain + MarketAlert 写侧 trading 门控 + 账号迁移读字段存在性 + domain 规则关键词单一真相源 + Account null 过滤 + ContextPackage 收敛 domainEnum；后端 429（+7）| ✅ 2026-08-15（边界漏网见 P1-B1/B4/P2-B1/B2）|
| P1-3 | `data/*/project/` 隐私面补齐（gitignore + git rm --cached）| ✅ 2026-08-15 |
| S-1 | adai-web 多图 ask 同步（askBatch + 上限 3 + `_syncActiveCard`）| ✅ 2026-08-14 |
| P0-1 + P1-1 + P1-2 | 对话态发媒体崩溃/残留错乱/部分失败问句丢（`_syncActiveCard` + `_pendingAsk`）| ✅ 2026-08-14 |
| #169 + #257 | 问候语机械 + 测试覆盖确认出表 | ✅ 2026-08-13 |
| 批 P | deep 31 项清 22：#234 分页终止 + #235-#238 P1 + #240-#246 P2 + P3 14 项 | ✅ 2026-08-12 |
| 批 O 收官 | #101/#103/#177 战略 + #19/#22/#115/#228 P2 + P3 顺手 14 项 | ✅ 2026-08-12 |
| #216 + #217 + #223 | CardMigration 判定收紧 + rewriteId 锚定 frontmatter + os/ 只读例外 | ✅ 2026-08-12 |

## 🔍 全维度走查（ai-engineering/process/audit.md）

> 7 审查官独立并行全量走查，交叉印证（同一问题多官命中 = ⭐ 优先级高）。走查日期 + 摘要滚动保留。

> 2026-08-15 自伤自查（8 官全量，审查 AI 工程层自身）

> 📄 完整发现清单见 `docs/review/audits/2026-08-15-ai-engineering-self.md`。

> 守护：META-GUARD PASS（45 文件）。**P0 无。战略×7 + P1×14 + P2×26 + P3×24**。**核心**：①docs/ai→ai-engineering 迁移清理未闭环（6 官 ⭐⭐⭐⭐⭐⭐）——ship/audit/review 门禁命令 `bash docs/ai/guard-meta.sh` 按文档执行必失败；②guard-meta M1 只校验 frontmatter 边、不查正文路径（盲区）——迁移残留全绿 PASS。**战略 S-A1..A7 + P1-A1..A14** 见存档。检查点沉淀建议 11 条（M4 正文路径扫描 / 迁移三件套 / RFC 验收核验 等）。

### 2026-08-15 首轮（7 官全量）

> 📄 完整按角色发现清单见 `docs/review/audits/2026-08-15.md`（含修复状态标注）。

> 守护 7 PASS / 0 HIT。**P0×1 + 战略×3 + P1×16 + P2×14 + P3×27**。**修复批 W1+W2 后端已出表**（W1：P0-W1 卡片单行化 + W10 SafeArea + W1/W2 双端重试 + W3/W4 自然化 + W16 基建自伤；W2 后端：W12 parseDateTime / W13 门控旁路 / W14 prompt 引号 / W15 标签索引重建，后端 454）；**P1 全部出表**（W1 批 + W2 批：P0-W1/W10/W1-4/W16/W12-15/W5-9/W11——后端 454 · app 94 · web 47 · admin 33）。剩余：战略 S-W1/S-W2/S-W3（roadmap 插件模型 / 双端值复制漂移 / 请求超时已修故 S-W3 部分落地）+ P2/P3（task-log）。检查点沉淀 B37-42 / F37-44 / D30-43 / K29-31 / C1-C7 / U10-17 / V1-3。

**🔴 P0（数据丢失）**

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| P0-W1 | **对话卡片多行 turn 读-写不对称 → 对话历史静默截断丢失**：`toMarkdown` 原样写多行 AI 回答，`parseTurns` 只取前缀所在行 → 下次保存时截断覆盖原文件。须补「写→读→写→读」round-trip 测试（含 \n）| `CardFileRepository.java:186-254` |

**🏛 战略**

| # | 问题 | ⭐ |
|:-:|:-----|:--:|
| S-W1 | roadmap（唯一蓝图）未收录已 approved 的 Domain=插件模型方向——已实现能力在蓝图不可见 | ⭐ |
| S-W2 | 双端「值复制」修复漂移成常态（图片重试/删除确认/全图 Dialog/状态机各落一端）——建议固定对拍项或抽共享 package | ⭐⭐ |
| S-W3 | 请求层无超时/无取消/无响应归属校验——所有等待态卡死防御靠 UI 补丁 | ⭐ |

**🔴 P1（首轮走查 16 项已全部出表 ✅）**

> 走查 P1-W1..W16 全部修复（W1/W2 批），详情见 `docs/reference/change-log.md`（W1 批：P0-W1/W1-W4/W10/W16；W2 批：W5-W9/W11-W15）。P1 区当前无未修复项。

**🔴 P2（11 项，详见 task-log）**：双端重试/删除/文案/色值对拍（多官）、记忆页日期连点乱序、admin 队列无错误恢复、Feed 缩略图无降采样、web caption 丢失、项目写端点门控、accounts.json 非原子、TagIndex 并发 RMW、交易不落 Record 流水线、记忆序列化三缺陷、alice 越界 domain/残留、positions 错配、os/ 知识杂项、roadmap 状态漂移、feature-reference 过期等。

> P2/P3 完整清单已迁移 `docs/reference/task-log.md`（2026-08-15 首轮走查区）。P3 打磨项全部入待办。

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-08-16 | deep 增量（框架+插件 G-1~G-6）| backend/knowledge/docs ×3 | 3 | ~25min | 战略×4 + P1×4 + P2×9（去重后）| 0（审查只报告）|
| 2026-08-15 | **全维度走查（首轮）** | 7 官全量并行 | 7 | ~1h | P0×1 + 战略×3 + P1×16 + P2×11 + P3×35 | 0（审查只报告）|
| 2026-08-15 | deep 增量（批 Q/R + 展示层聚合 + 发布核对）| backend/frontend/docs ×3 | 3 | ~40min | 战略×2 + P1×5 + P2×7 + P3×21 | 0（审核只报告）|
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
