---
title: 项目审核全量状态报告
updated: 2026-08-09
last-review: 2026-08-09
baseline: 7aecf9d
mode: 批 J（P1 清理：v1.0.0 核心闭环）
---

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-06 | 双轨修复（批 H + #127）| 89feaf1..HEAD | subagent(adai-web) + 主会话(后端) | 0 | 13（adai-web 9 项 + #127 4 项）|
| 2026-08-03 | full 全量（v0.3.0 前）| — | backend/frontend/docs/product/knowledge ×5 | P0×1 + 战略×7 + P1×13 + P2/P3×30 | 0 |
| 2026-08-03 | 修复批 A-D | — | — | 0 | 22（数据安全/状态机/契约/数据+文档）|
| 2026-08-02 | deep 增量（adai-web）| cc537db..HEAD | frontend/product/docs ×3 | P0×1 + 战略×3 + P1×9 + P2×8 + P3 打磨若干 | 0 |
| 2026-08-02 | full 全量（v0.1.0 发布前）| — | backend/frontend/docs/product/knowledge ×5 | 前端 3 项 + 后端 P1 4 项 + 文档 | 后端 P1 4 项 + 文档契约 |
| 2026-08-01 | 全量（初始） | — | backend / frontend / arch ×3 | 32 | 23 |
| 2026-08-01 | deep 增量 | a4a7c12..cd1231b | docs / knowledge | 11 新 / 1 升级 | 0 |

## 🔴 战略缺口（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 101 | Feed 无「加载更早」分页：只拉 `size:20`，更早记录不可达 | `feed_page.dart:55-56` | 📋 待办 |
| 103 | Timeline/Memory 保活数据陈旧：initState 只拉一次 + IndexedStack 保活，无刷新入口 | `timeline_page.dart` / `memory_page.dart` | 📋 待办 |
| 129 | 知识反哺闭环零产物：复盘→promote→99-inbox 代码完整但从未产出真实文件 | `os/trading-os/08-review/` | 📋 待办 |

## 🔴 P1（未修复）

> 2026-08-09 批 J 清理：#106 / #112 / #144 已修复出表。P1 当前清零。

## 🔴 P2（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 19 | Feed/Context/Memory 每次全量遍历 data 目录 | `RecordFileRepository.findAll` | 📋 待办（数据量小）|
| 22 | kernel 反向依赖 infrastructure（现 4 处：IntentRecognizer/ContextEngine/MemoryService/Memory.java）| 多处 | 📋 待办 |
| 115 | Feed 右栏（简报/标签云/任务快照）不随操作/刷新更新，数据陈旧 | `feed_page.dart:76-88` | 📋 待办 |
| 117 | 测试覆盖缺口：✅ Feed 状态机 12 测试（`feed_state_machine_test.dart`）+ 6 页面 widget 测试（`pages_widget_test.dart`：memory/timeline/search/trading/task/profile 数据渲染 + 错误态 + 重试）；缓存 key 分桶未测（价值低，留待多账号批）| `test/` | ✅ 主体 |
| 149 | 多账号细节：accounts.json 无锁 / 删号不清理数据 / 允许创建 default | `AccountFileRepository` / `AccountController` | 📋 待办（v1.0.1）|
| 153 | 数据形态失衡：08 月 131/133 条为对话摘要，原始 note <2% | `data/default/records/2026/08/` | 📋 观察 |

## 🔴 P3（未修复，打磨）

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| 121 | 无最小宽度/响应式保护：窄窗下 nav200+侧栏300 挤压主区（批 H 已评估：桌面端专用产品、常规宽度无问题，极窄窗口才压缩，低优先级）| `desktop_shell.dart` |
| 122 | frontend-reference 颜色表旧色值 + API 速查表缺 adai-web 消费的 8 端点 | `frontend-reference.md` |
| 125 | 打磨：README 默认模板 / aiNote 死代码 / hover 无手型 / 圆角 token 散落 / 记忆页日期无年份 | 多处 |
| 163 | adai-admin 记录页只看得到今天（Feed 契约只返回当天）| `data_api_store.dart:60-76` |
| 166 | MediaController 上传超限走 500（应 413）、title 50 字符 substring 拆断 emoji、market id 同秒碰撞 | 多处 |
| 168 | 知识 P3 杂项：空文件 / 重复 JSON / PNG 入库 / life-os 引用漂移 / project-os 路径漂移 / 未索引标签 / gitignore 单层 / decision 死分支 | `os/` 多处 |
| 169 | 问候语机械：按小时硬编码切分（`hour < 12 → morning`），凌晨也算 morning，不感知人类作息；建议按作息智能化（深夜/凌晨单独问候）| `BriefAppService.java:129` / `:105` |
| 170 | 待办建议第三人称：action 待办卡文案（`Memory.suggestion`，如「提醒用户休息」）用第三人称，应直接面向用户（「该休息了」）| `FeedAppService.toActionEntry` → 记忆生成 prompt |
| 171 | 优化方向（非问题）：项目分类记录无聚合——domain=project 记录散在 Feed/时间线，项目页只有状态+任务；用户以"日志记录问题/优化建议"为入口，建议项目页增加「项目记录」聚合视图 + 记录可标记类型（问题/建议），并可流转为任务 | `adai-app` 项目页 + domain 体系 |
| 172 | 记忆页 superseded 记忆仍显示「待办/已完成」标记（语义矛盾：被取代的历史版本不是当前待办）+「已取代」仅靠卡片变浅、区分度低含义不明；建议 superseded 记忆隐藏 actionable 标记，已取代状态加明确灰角标说明（如「已被新记忆取代」）| `memory_page.dart:239-261` |
| 173 | 优化方向（L4 演进）：带图提问——图片上传固定 intent=log（`MediaRecordAppService.recordImage` 硬编码），不支持"发图+问句→AI 基于图回答"；建议加 intent=question 通道 + AI 对话带图上下文 | `MediaController.uploadImage` / `MediaRecordAppService.recordImage` |

## ✅ 已修复区（最近 10 条，旧条目随滚动删除）

| # | 问题 | 修复 |
|:-:|:-----|:-----|
| 批 J | **2026-08-09 P1 清理（v1.0.0 核心闭环）**：#144 rebuild 幂等（intent 落盘 + summary 处理标记 + 降级记录仍重跑升级，`RecordFileRepository`/`MemoryController`/`QuestionAppService`）/ #147 SELL 未持有与超额报错（`TradingException` + GlobalExceptionHandler 400）+ 持仓读改写加锁 + 清仓 0 行不落盘 / #106 api-spec portfolio 契约对齐（补后端 `positionCount` 派生字段，adai-web 持仓数不再恒 0）/ #112 CANCELLED 任务看板可见（adai-web 补第四列）/ #150 apiEndpoints 动态统计（硬编码 21 → 实际 46）+ FeedAppService 死依赖移除；后端 302 · adai-web 27 测试全绿 | ✅ 2026-08-09 |
| 批 I | **2026-08-07 adai-app 对话体验收尾**：#13+#11 card 写入剥离 AI 原始 JSON（`LlmResponseParser.extractNaturalText` + `QuestionAppService` 写卡与返回均剥 JSON，实时=刷新，card 文件不再混入游离 JSON）/ #148 Feed ai_note 按记录日期归属（`MemoryService.findByRecordIds` 跨日补齐 + `toAiEntry` 用记录时间，重补/升级跨日不错日不丢失）/ MD1 世界切回 Feed 刷新（`DualWorldShell` ValueNotifier → `MainPage.refreshTick` 重载，覆盖 admin 记忆重建后 Feed 陈旧）；后端 286 测试 · 前端 60 测试 | ✅ 2026-08-07 |
| 批 H | **2026-08-06 adai-web 桌面残留清理 9 项**：#102 交易页复盘入口（markdown 复盘弹窗）/ #132 红涨绿亏（A股语义，快照+DataTable）/ #161 时间线 type 徽标中文化（13 类映射+未知兜底）/ #131 桌面文案全量中文化（shell/feed_card/task/feed/profile/project/网络错误）/ #124 CLAUDE.md 端口 8082 / #158 记忆页待办完成按钮 / #159 Feed 空态快速引导 chips（prefill 聚焦）/ #118 `_check` utf8 解码 / #165 type 硬转换兜底；#120 确认已实现未重复、#121 评估低优先级不修 | ✅ 2026-08-06 |
| 127 | **2026-08-06 最小封闭鉴权**：admin/accounts 端点 `X-Admin-Token` 拦截（常量时间比较、未配置 fail-closed 503）+ CORS `*`→配置化 origin 白名单（默认 localhost:*）；adai-admin 前端 `ADMIN_TOKEN` dart-define 注入；4 鉴权测试 + api-spec v3.6 | ✅ 2026-08-06 |
| 批 G | **2026-08-05 adai-app 6 页面 widget 测试（#117 剩余）**：`pages_widget_test.dart` 14 测试——memory/timeline/search/trading/task/profile 六页数据渲染 + #108 错误态人话 + 重试按钮（复用批 F MockClient 基建）| ✅ 2026-08-05 |
| 批 F | **2026-08-05 adai-app 质量锁定**：#117 Feed 状态机 12 个 widget 测试（`feed_state_machine_test.dart`：ask→waiting→chatting→ended / 追加 / 错误重试 / 删除 / 加载更多 / #100 竞态，ApiService 注入 MockClient 测试性改造）/ #123 状态机文案全量中文化（ask·log·end·chat·结束对话，adai-app 零英文残留）| ✅ 2026-08-05 |
| 批 E | **2026-08-04 adai-app 主轴修复 5 项**：#108 故障 vs 无数据（memory/timeline/search/task 4 页错误态+重试，profile 已好）/ #113 错误态人话（trading+task）/ #114 确认切日期已有 spinner 覆盖 / #116 确认交易提交已有 SnackBar 反馈 / #162 Feed push 类型双端映射 | ✅ 2026-08-04 |
| 164 | adai-app 语音误导性 stub 移除（语音移入 v2 方向，砍可切态+长按录音入口，`input_bar.dart`）| ✅ 2026-08-03 |
| 160 | api-spec mediaPath 示例日级→月级（批2 契约修正）| ✅ 2026-08-03 |
| 批 A-D | **2026-08-03 连续修复 22 项**：批 A 数据安全（#126 Memory 并发写锁+原子写 / #136 删除路径 createdAt / #137 TagIndex 删除钩子 / #138 cashBalance 保留 / #128 重补遍历用户）；批 B 前端状态机（#100 竞态崩溃 / #104 删卡残留 / #105 双卡互踩 / #107 缓存失效 / #109 删除确认 / #110 retry 幂等 / #111 mode 同步 / #119 计数）；批 C 契约编码（#133 kind 三端 / #134 错误态人话 / #140 优先级透传 / #145 utf8 解码 / #146 HEIC）；批 D 数据+文档（#135 frontmatter 单行化 + 存量 5 条 / #139 卡片双副本 / #151 悬空核实保留 / #152 count 校正 / #130 VISION 状态表 / #141 README 索引 / #142 roadmap 模型名 / #143 RFC frontmatter / #154-157 CLAUDE.md 树计数 / #167 feature-reference）| ✅ 2026-08-03 |
| 60/61/62 | v0.2.0 前端 actionable UI 消费：action 待办卡+完成按钮（`ca2d4a8`）、Feed 分页终止修复、memory 页 kind/superseded/待办展示（`7d9b607`）| ✅ 2026-08-02 |
| 后端 P1 ×4 | actionable 筛选豁免 + 无限重补修复 + ID 单调统一 IdGenerator + rebuild 幂等 + 跨日升级（`c41c2b7`）| ✅ 2026-08-02 |
| 13 | interfaces 层编排重复三处 → RecordUnderstandingService 统一（`bdb83da`）| ✅ 2026-08-02 |
| 33/38/39/41/21/23 | 第三批 6 项：review 路由表补 `.claude/**`；README 索引；os definition 愿景声明；data-flow 对齐；ProjectFileRepository 注释；TradingController 解析真实 rules.md | ✅ 2026-08-01 |
| 12/24/14 | 第二批 3 项：记忆沉淀断裂、复盘走 ContextEngine、测试缺口 9 个 | ✅ 2026-08-01 |
| 16a/34/35/36/37/40/42/43 | 第一批快修 8 项：孤儿卡片迁移；ship grep 修正；RFC 滚动；guard 对齐；trading README；CLAUDE.md 焦点；guard.sh G1 防挂起；零碎 | ✅ 2026-08-01 |
| 25-32 | 前端 P3 打磨 8 项（URL 编码/文本清理/日期硬编码/静默刷新/死代码/light主题）| ✅ 2026-08-01 |
| 15-20 | 数据目录 + api-spec v3.1 + CLAUDE.md 对齐 + 任务扫全部月份 | ✅ 2026-08-01 |
| 7-11 | P1 功能 bug 5 项（emoji 代理对/行情缓存键/AI失败保数据/mounted守卫/PnL序列化）| ✅ 2026-08-01 |

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-08-09 | 批 J（P1 清理 v1.0.0 核心闭环）| — | 0 | ~40min | 0 新 | 5（#144/#147/#106/#112/#150）|
| 2026-08-07 | light 增量（批 I 对话体验收尾 + ideas）| — | 0 | ~5min | 0 新（P3 观察×3）| 4（#13/#11/#148/MD1）|
| 2026-08-06 | light 增量（Phase 2 行情推送快扫）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-06 | light 增量（批H + #127 快扫）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-06 | 双轨修复（批 H + #127）| general-purpose（adai-web）| 1 | ~15min | 0 新 | 13 |
| 2026-08-06 | light 增量（批F/#123/批G 快扫）| — | 0 | ~3min | 0 新 | 0 |
| 2026-08-03 | full 全量（v0.3.0 前）| backend/frontend/docs/product/knowledge | 5 | ~30min | P0×1+战略×7+P1×13+P2/P3×30 | 0 |
| 2026-08-03 | 修复批 A-D | — | 0 | 4 批 | 0 | 22 |
| 2026-08-02 | full 全量（v0.1.0）| backend/frontend/docs/product/knowledge | 5 | ~25min | 前端 3 项 + 后端 P1 4 项 + 文档若干 | 后端 P1 4 项 + 文档契约 |
| 2026-08-02 | deep 增量（adai-web）| frontend/product/docs | 3 | ~10min | P0×1+战略×3+P1×9+P2×8+P3 若干 | 0 |
| 2026-08-02 | light 增量（多账号预留）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-04 | light 增量（批1/批2/语音/批E 快扫）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-02 | light 增量（v0.2.0）| — | 0 | ~3min | 0 新 | 0 |
| 2026-08-01 | light 增量 | — | 0 | ~2min | 0 新 | 0 |
| 2026-08-01 | deep 增量 | docs/knowledge | 2 | ~25min | 11+1升级 | 0 |
| 2026-08-01 | 全量 | backend/frontend/arch | 3 | ~2h | 32 | 23 |

> 跑几轮后用成本数据决定全量审核频率（目前建议：增量随时，全量 1-2 周一次）。
