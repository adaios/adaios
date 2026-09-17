---
title: docs/rfc 目录索引
description: 决策记录区目录治理——RFC 清单 + 状态（draft/approved/implemented），过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-09-17
status: active
lines: 84
depends-on: []
related:
  - ../_index.md
tags: [meta, index, rfc]
---

# docs/rfc 目录索引

**职责**：AdaiOS 决策记录区（RFC）。每个 RFC 有 frontmatter（title/date/status/decided-by），状态驱动后续会话判断「是否已决策」。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| 20260917-learn-representation.md | learn 表征适配与链路打通（门控 B + 偏好回流 + 反馈入口 + 精美导出）| approved |
| 20260917-todo-kernel-retire-project-plugin.md | 待办归 Kernel——撤 project 插件与待办重定位（能力三层 core/builtin/optional；待办纯清单两态 + 到期推送、不进 Feed；端点硬切 `/api/v1/todos*`）| implemented |
| 20260718-context-memory-knowledge-loop.md | Context 闭环 — 记忆回读 + 知识召回 | implemented |
| 20260720-context-architecture.md | Context 架构重构：三层上下文 + 标签索引 + 记录晋升 | implemented |
| 20260721-ai-chat-quality.md | AI 对话质量修复：从分析模式到对话模式 | implemented |
| 20260722-dual-world.md | 双主页（Dual World） | implemented |
| 20260722-features-memory-timeline-search.md | 记忆页 + 时间线页 + 搜索 | implemented |
| 20260722-identity-page.md | 身份页（Identity Page） | implemented |
| 20260723-launcher-polish.md | 导航幽默化 + 标签云点击 + 色彩 | implemented |
| 20260723-tagcloud-gesture.md | 标签图谱 + 双视图切换 | implemented |
| 20260725-frontend-project-trading-pages.md | 前端项目状态页 + 交易页 | implemented |
| 20260725-layer6-knowledge-feedback-loop.md | Layer 6 知识反哺闭环 | implemented |
| 20260725-life-project-os-skeleton.md | Life OS + Project OS 骨架 | implemented |
| 20260726-project-status-and-roadmap.md | AdaiOS 项目现状与三大方向规划 | superseded |
| 20260727-memory-upgrade.md | Memory 升级路线 — 从复读机到真记忆 | revised |
| 20260728-project-development-suggestions.md | AdaiOS 项目发展建议 | draft |
| 20260729-development-retrospective.md | 开发复盘：近期 Bug 反复的根因分析 | completed |
| 20260730-health-management-scenario.md | 20260730-health-management-scenario.md | active |
| 20260730-market-data-and-push.md | Layer 5 行情接入与主动推送 MVP | implemented（Phase |
| 20260801-memory-system-evolution.md | 记忆系统进化 — 元记忆对比与落地方案 | implemented |
| 20260801-release-versioning.md | 产品发布版本机制（Release Versioning） | accepted |
| 20260801-review-skill.md | 审核流程 Skill 化（Review Skill） | implemented |
| 20260802-adai-admin.md | adai-admin 管理后台规划 | approved |
| 20260802-multi-account-prep.md | 多账号架构预留 — 数据路径 userId 分层（v1.0.0 前置） | implemented |
| 20260802-multimodal-image-glm.md | 多模态图片记录（GLM-VLM）— 图片 → 文本闭环（L4） | implemented |
| 20260813-record-task-and-sports-analysis.md | 记录↔任务关联 + 相机动作分析（想法升级 RFC） | implemented |
| 20260814-domain-plugin-model.md | Domain=插件模型（Kernel 基础服务 / Domain 受控插件） | approved |
| 20260815-docs-governance.md | 文档治理——瘦身 + 单一事实源（先于功能开发） | approved |
| 20260815-trading-interaction-redesign.md | 交易模块交互重设计（app 说人话 / web 详细管理；定位条款已被 20260902 取代）| superseded |
| 20260816-trading-os-engine.md | trading-engine 领域引擎化（从插件到独立可复用的交易引擎）| draft |
| 20260816-trading-data-model.md | 交易数据模型分层（用户提供 vs 可查询，trading domain 可执行化）| draft |
| 20260815-ai-engineering-layer.md | AI 工程层——从「文档子目录」到「一等公民」（草案）| draft |
| 20260815-media-event-unification.md | 图文一体——媒体事件数据层统一（一次输入 = 一条记录） | approved |
| 20260815-image-chat-interaction.md | 带图交流——发图即对话（交互方案：AI 判定 log/ask 分流，ask 直进对话态） | draft |
| 20260816-framework-plus-plugin-model.md | 框架+插件——AdaiOS 形态总纲（决策记录，已提升为正式架构文档 `architecture/framework-plus-plugin-model.md`） | approved |
| 20260816-trading-agent-plugin-model.md | 交易 Agent 三阶段插件模型（裸问答 → +行情插件 → +规则插件，能力按用户叠加） | approved |
| 20260816-trading-session-push.md | 交易时段节奏推送——早盘计划/午间跟踪/尾盘建议 + 微信渠道（PushChannel 插件化）| draft |
| 20260816-trading-data-intelligence.md | 交易数据智能——自选股买点提示 + 清仓复盘闭环 + 打分系统（K线为核，B1 完美图参照系）| draft |
| 20260817-trading-push-image-trade-log.md | 交易推送体验（样式/模板/开关）+ 图片对话流 + 交易日志自动归集 | approved |
| 20260822-trading-trade-time-review.md | 成交时间点采集 + 当日交易复盘（纯客观数据——去掉理由/情绪）| approved |
| 20260823-trading-history-tab-backfill.md | 历史成交 Tab 页（常驻第 5 Tab，取代页头 Dialog）+ 导入缺失成交时间回填（updated 计数）| implemented |
| 20260824-trading-company-insight.md | 公司透视——上市公司客观画像九维度 + 红黄绿排除判定（查公司，v1=单点+喂建议引擎）| draft |
| 20260825-trading-lot-tracking-behavior.md | 交易逐笔批次跟踪与行为纠偏（lot 模型/LIFO/批次止损/行为标注/当日同步/推送过期/回合总账）| implemented |
| 20260822-memory-plugin-isolation.md | Memory 与插件隔离——记忆属于框架不按插件隔离；补 domain 标记留过滤基础 | draft |
| 20260829-learn-plugin.md | learn 插件——外部内容学习沉淀（⛔ **2026-09-12 B 形态产品插件整体下架（含读层），需求回归 A 形态会话技能 learn-digest**；下架理由与待执行清单见该文 §九；C Agent 方向仍搁置）| superseded |
| 20260830-trading-perfect-case-library.md | 完美买点案例库——从案例积累到判定当下（日 K 案例沉淀 → 归一化相似度 → 双轨判定）| draft |
| 20260901-auth-login.md | 用户认证登录体系——根治 X-User-Id 零鉴权（账号密码 + 服务端会话，AuthFilter 覆盖 X-User-Id）| implemented |
| 20260902-trading-memory-positioning.md | 交易模块定位重设——从「建议引擎」到「交易记忆」（用你的历史，理解你的当下；取代 20260815 定位条款）| approved |
| 20260905-trading-cognition-layer.md | 交易⑤认知层落地——个人画像（profile，客观推导+主观补全、注入建议/复盘）与建议闭环（advice 留痕→卖出回查→「说X做Y得Z」）；吸收 TradeZella/TraderSync/Edgewonk 纪律量化 | approved |
| 20260909-trading-clearance-derivation.md | 清仓股双轨收录（RFC 20260909 批 1 已落地）——流水证明清仓自动收录 sold.json（provenance=flow，只填空白）+ 缺基线 pending 提示（GET /trading/sold 双轨响应）；批 2 多段派生视图待排 | implemented |
| 20260912-learn-product-digest.md | 产品内容消化（D 形态）——app/web 喂入 → **服务端抓取** → 结构化留存；A 的能力下沉产品（B 死因是「不做抓取」）；**2026-09-12 拍板：独立页面为基线前提 + 会话内集成为增强、首期 B站/文章/图片、转写必须对接 fun-asr 且费用须可控（§3.8 六条）**；**2026-09-12 阶段 1 抓取主干已本地落地（§十 落地记录；未部署生产；topic 契约迁移与图片源留待下批）** | approved |
| 20260912-trading-ledger-integrity.md | 交易账本完整性——三条真源（券商快照/逐笔流水/派生持仓）收口：快照锚点 **fail-closed**、append 与 replay 幂等统一、卖超**不丢数据**、`GET /trading/integrity` 账实自检 + deploy-gate 门禁（2026-09-12 生产实测：锚点文件缺失→重放双计，现金 -26666.85 应 ≈1381.93；3 笔真实卖出被静默丢弃）| implemented |
| 20260914-login-credential-experience.md | 登录体验方案（待拍板）——L1 补 `AutofillGroup`/`autofillHints` 让 iCloud 钥匙串记住并填充密码；L2 token 落 Keychain + `local_auth` Face ID 本地门禁 + 后端会话设备列表与单设备撤销；L3（二期可选）Passkey / Sign in with Apple。生物特征永不出设备，Face ID 只是本地解锁闸；会话维持 30 天滑动（痛点不在时长，在「登录那一次」与「打开时无门禁」）| draft |
| 20260914-ios-share-extension.md | iOS 分享扩展（Share Extension）——B站/抖音分享面板直达阿呆：新增 Extension target + App Groups 共享 `learn:digest` 限权令牌，扩展自进程内提交 `/learn/digest`、**不拉起主 App**（2026-09-14 拍板：反馈「已交给阿呆，正在读…」约 1 秒自动关 / 令牌签发时自动写入 / 只放「整理」一个动作）| approved |
| 20260915-share-extension-credentials.md | 分享扩展凭据方案（待拍板）——把「用户手点一次签发」改为**登录后自动签发 + 自动续期**（保住「扩展只拿限权令牌」的取舍，同时去掉手动步骤），并可选把凭据通道从 App Group **明文容器**升级为**共享 Keychain**；起因是 2026-09-15 TestFlight 首次实装真机复验暴露「别人装完、登录了、分享却用不了，且不知道原因」——业界共识是登录即自动写入、用户零感知 | draft |
| 20260916-first-meeting.md | 第一次见面——新用户冷启动批：①admin 账号页补 **learn 插件开关**（后端早已注册，前端硬编码漏项，学习功能此前无处可勾）；②对话流空态改**阿呆先开口 + 3 个可点开场问句**（走问答路径，插件默认全关时唯一零配置能跑通的能力面）并注入**能力边界 prompt**（禁越界承诺）；③档案页「**阿呆对你的了解**」（`GET /memory/insights` 聚合长期沉淀的 patterns/preferences + 确认回流 identity）；含昵称语义修正（「姓名」→「阿呆怎么称呼你」、默认值不再用 AI 自己的名）| implemented |

## 过期判断

- `status: draft` 长期未决策 → 候选清理（或催决策）
- `status != implemented` 且 `updated` 超 3 个月 → 候选归档
- 新增 RFC：补本索引 + frontmatter（title/date/status）
