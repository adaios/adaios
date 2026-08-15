# 批次变更日志（Change Log）

> 根 CLAUDE.md「当前焦点·已完成」历史条目归档（RFC `20260815-docs-governance`：状态与历史分离）。
> **规则：新批次合入时，在顶部追加一行**（日期 | 批次 | 一句话摘要 | 测试数变化）。详情以 git log / REVIEW 已修复区为准。

| 日期 | 批次 | 摘要 | 测试数变化 |
|:-----|:-----|:-----|:-----------|
| 2026-08-15 | deep 审核修复批 S | P1-B1 domainEnum 去引号语义（CHAT 双重引号修复）+ P1-B2/B3 时间线聚合跨天/intent/歧义边界 + P1-B4 图片 domain 走 gateDomain + P2-B1 trading 写入口门控（/trades、/review 403）+ P2-R1 admin 插件 toggle 串行队列（双连点竞态）+ 边界测试 | 后端 439（+6）· admin 33（+1）|
| 2026-08-15 | 展示层聚合（S-2 + 时间线 bug）| 产品决策「一次输入 = 一个事件」：TimelineProjection 多轮 chat 每会话只保留首问（时间线单条）+ 带图 ask image_qa 引用图不单独成条（图文事件，缩略图取首图）；FeedAppService 同口径聚合；前端零改动；数据层不变（freeze 不破坏，层 2 另立 v1.0.1）| 后端 433（+4）|
| 2026-08-15 | REVIEW 修复批 R（前端 + 文档）| P1-5 adai-web 桌面壳当前页按 label 重解析（插件加载后索引不错位）+ P2-5 插件拉取失败 SnackBar 反馈/重试；P1-6 adai-app Launcher 插件接口拆独立 try/catch（不再拖垮核心数据）；P2-6 adai-admin 插件开关 toggle 前从最新列表重取（快速连点不再覆盖丢开关）；P1-7 api-spec D1 通用化同步；P2-8 feature-reference 补插件模型章节 + 端点表补 3 端点 | web 47（+1）· app 92 · admin 32 |
| 2026-08-15 | REVIEW 修复批 Q（后端插件门控/健壮性）| S-3 重补路径走 gateDomain（D5 收敛铺满持久化入口）+ S-4 MarketAlert 写侧 trading 插件门控 + P1-4 账号迁移读原始字段存在性（PATCH 清空不被推翻）+ P2-2 domain 规则由关键词常量拼接（单一真相源）+ P2-3 Account 过滤 null 插件元素 + P2-4 ContextPackage 携带收敛 domainEnum（CHAT 模式不硬编码）| 后端 429（+7）|
| 2026-08-15 | research 目录整合 | 按「个人/公司、新/旧、单仓多项目/单项目单库」前提：方法论两篇合并为《项目级 AI 上下文体系方法论》+ 公司侧研究（AI4SE/Workspace 架构/ai-native 系列等）全部移出仓库至同级独立目录 `ai-context-research/`；阿呆早期设计 `ai-context-design.md` 归位 `docs/inbox/`；3 文件初判失误复核后从 git 恢复（哈希校验一致）；CLAUDE.md 移除 research/ 说明 | 不变 |
| 2026-08-15 | 文档治理 | RFC `20260815-docs-governance`：瘦身 + 单一事实源（status.md / change-log.md / CLAUDE.md 指针化 / REVIEW 减负 / P3 迁移 task-log）；v1.0.0 发布顺延 | 不变 |
| 2026-08-15 | Domain=插件模型第二步 | 插件门控全通道：Account.plugins + PluginRegistry/PluginService + ContextEngine 全量门控 + D5 domain 收敛 + `/me/plugins` + Feed/promote 门控 + 三端显隐（RFC `20260814-domain-plugin-model`）| 后端 422（+28）· app 92（+3）· web 46（+2）· admin 32（+1）|
| 2026-08-14 | 带图 ask（多图问答）| 输入栏附图可 log/ask + 上限 3 + `POST /records/media/ask-batch` 多图一次问答 + intent 分流 + GLM 多图降级兜底；adai-web 同步（S-1）| 后端 387（+10）· app 86（+3）· web 44（+2）|
| 2026-08-14 | 概览卡/图片交互/删除残留 | 概览卡 1+3 铺满顶部 + DeepSeek 空内容重试 1 次 + 降级增强（📋/🧠/☕）+ 主动提示待办；拍照入口 + 选图统一 image_picker + 上传进度条；删除残留 P0（Memory.cardId + 双匹配）| 后端 377 · app 83 |
| 2026-08-13 | 键盘收起 | adai-app 点空白/发送后收起键盘（壳层 onTap unfocus + 发送后 unfocus）| app 83（+2）|
| 2026-08-13 | R2 记录↔任务 | domain=project 记录自动转任务（方案 B：sourceRecordId + RecordToTaskLinker + 清记忆待办）；A2 相机动作分析搁置 | 后端 374 · app 81 · web 42 |
| 2026-08-13 | REVIEW 修复批 P | deep 31 项清 22：#234 分页终止口径 + #235-#238 P1 + #240-#246 P2 + P3 14 项 | 后端 362 · app 81 · web 42 |
| 2026-08-12 | REVIEW 收官批 O | 战略 #101/#103/#177 + #179 登记 v1.0.1；P2 #19/#22/#115/#228；P3 顺手 14 项 | 后端 362 · app 78 · web 40 |
| 2026-08-12 | 修复批 N | #216 CardMigration 判定收紧 + #217 rewriteId 锚定 frontmatter + #223 os/ 只读例外 | 后端 359 |
| 2026-08-12 | 顶部摘要优化 | 概览卡去绿点前缀 + 行数 3→5（阿呆 08-12 反馈）| 后端 356 · app 68 · web 30 |
| 2026-08-12 | 修复批 M | #129 promote 前端入口 + #218 visual durationMs + #222 问候加中午段 | 后端 355 · app 68 · web 30 |
| 2026-08-12 | P2 修复批 L | #214 图片追问长度上界 + #215 available 最小集 + #221 降级问候 emoji 按时段 | 后端 355 |
| 2026-08-12 | AI 日志隐私治理 | #210 retention 30 天 + ai-logs 分页/日期上界 | 后端 351 |
| 2026-08-12 | 数据/隐私加固 | #227 重补过滤禁用账号 + #213 追踪上下文请求级清理 + #178 promote 融合提示 | 后端 344 |
| 2026-08-12 | P1 修复批 A-D + #184 | #184 promote 脱敏 + #204-#209 前端/图片追问 + #211/#212 候选命名与迁移 | 后端 340 · app 68 · web 30 |
| 2026-08-12 | R1 AI 交互日志 | LoggingAiClient/VisualAiClient 装饰器 + ai-logs jsonl 落盘 + `GET /admin/ai-logs` + AiTraceContext | 后端 340（+20）|
| 2026-08-12 | 生产反馈三连修 | #14 凌晨问候语 + #15 chat 对话折叠 + #16 输入框上滑误触 | 后端 314 · app 63 |
| 2026-08-11 | 生产验收批 | 8083 CORS 修复 + 图片追问（L4 图片问答）+ adai-admin 改名「阿呆控制台」| 后端 313 · app 61 · web 30 · admin 31 |
| 2026-08-XX | 批 K（多账号 deep 修复）| #180-#190：freeze 契约同步 / rebuild 幂等 / default 无效化 / 切换防重入 / endpoints.txt 生产计数 | 后端 300 · app 60 · web 27 |
| 2026-08-XX | 多账号前端选号/切换 | `/accounts/available` + World B 切换账号 + 记住上次账号 + wasm 白屏修复 + 切换崩溃修复 | — |
| 2026-08-XX | CanvasKit 崩溃修复 | 阿呆系统页入口无动画跳转 + 静态加载占位 | app 60 |
| 2026-08-XX | v1.0.0 验证修复 | updatedAt 时间基准 + #175 分页 + 复盘生成语义（AiClient.generate）| 后端 298 · 前端 60 |
| 2026-08-XX | adai-web 独立桌面端 | 两栏壳 + 8 模块桌面形态（两套 UI 非适配）| web 27 |
| 2026-08-XX | adai-app 即产品入口 | 砍掉 adai-entry，app 直接作为产品入口 | — |
| 2026-08-XX | adai-admin 全栈 MD11-16 | 账号体系 + admin 端点 + 前端四模块 | admin 31 |
| 2026-08-XX | 多账号架构预留 | data/{userId} 分层 + 迁移脚本 + 隔离测试（RFC `20260802-multi-account-prep`）| — |
| 2026-08-XX | v0.2.0 闭环 | action 待办卡 + PATCH done + memory kind/superseded + L5 行情条 | — |
| 2026-08-XX | 记忆系统进化 Phase 1-5 | kind + 主题合并 superseded + actionable 闭环 + 时效淘汰 + 筛选降噪 | — |
| 2026-08-XX | 文档体系精简 | 产品路线 v1 + 文档结构精简（inbox 归位 17 个重复文件）| — |
| 2026-08-XX | 发布版本机制 RFC | 版本号规则 + 发布流程 + Release Notes 模板 | — |
| 2026-08-XX | 记忆系统进化 RFC | 元记忆对比 + 方案落点（draft）| — |
| 2026-08-XX | 第三批审核修复 | #33 审核路由表 + #38/#39/#41 文档同步 + #21/#23 代码 | — |
| 2026-08-XX | 第二批审核修复 | #24 记忆沉淀断裂 + #12 复盘走 ContextEngine + #14 测试缺口（110+）| — |
| 2026-08-XX | 审核/交付流程基建 | /review 三档 + 5 角色 + guard.sh + /ship 闭环 | — |
| 2026-08-XX | docs/ideas 想法归档区 | 未定型想法的正式位置 | — |
| 2026-08-XX | os/ 目录统一 | domains/ 合并入 os/*-os/definition/ | — |
| 2026-08-XX | 任务系统修复 | ID 毫秒防冲突 + save() synchronized + 中文支持 + 清 65MB 损坏文件 | — |
| 2026-08-XX | Project OS 使用指南 | `docs/guides/project-os-usage.md` | — |
| 2026-08-XX | 方向 A Phase 1 | 行情接入（腾讯）+ CHAT 模式上下文注入修复 | — |
| 2026-08-XX | 后端接口测试全覆盖 | 15 Controller 46 端点接口测试（203→236）| — |
| 2026-08-XX | 多模态图片记录 L4 | 图片 → GLM-VLM 文本化进闭环 + 记忆 KIND_INSIGHT + 失败降级 | 256 |
| 2026-08-XX | adai-web 验收批1 | 输入栏内联多图 + ask waiting 态 + 时间戳 + 红涨绿跌 | web 25 |
| 2026-08-XX | adai-app 同步批1 | 同上同步移动端 | app 29 |
| 2026-08-XX | adai-web 验收批2 | FeedEntry.date + mediaPath + 原图 Dialog + 时间线缩略图 | 后端 258 · app 31 · web 27 |
| 2026-08-XX | 语音 stub 移除 | 砍误导性语音入口（REVIEW #164）| app 31 |
| 2026-08-XX | 主轴问题批 E | #108/#113/#102/#162/#132/#131/#123 | app 33 · web 27 |
| 2026-08-XX | 质量锁定批 F | Feed 状态机 12 widget 测试 + 文案全量中文化 | app 45 |
| 2026-08-XX | 6 页面测试批 G | pages_widget_test 14 测试 | app 59 |
| 2026-08-XX | #127 最小封闭鉴权 | X-Admin-Token 拦截 + CORS 白名单 | 后端 262 · admin 31 |
| 2026-08-XX | 桌面残留清理批 H | #102/#132/#161/#131/#124/#158/#159/#118/#165 | web 27 |
| 2026-08-XX | 对话体验收尾批 I | #13+#11 JSON 剥离 + #148 跨日记忆 + MD1 世界切回刷新 | 后端 287 · 前端 60 |
| 2026-08-XX | v1.0.0 定调 + 发布准备 | 版本定调 + 数据格式冻结 + 路线图 v1.0.0-first + Release Notes | — |
| 2026-08-XX | P1 清理批 J | #144 rebuild 幂等 + #147 SELL 报错 + #106 portfolio + #112 CANCELLED + #150 动态计数 | 后端 293 · web 27 |
| 2026-08-06 | 方向 A Phase 2 行情推送 | MarketAlertService 交易时段轮询 → type=push 入 Feed | 后端 276（+14）|
| 2026-08-XX | 数据冻结 3 项差异 | freeze #1/#2 手动维护 + #3 账号 createdAt ISO 统一 + 迁移 | 后端 294 |
