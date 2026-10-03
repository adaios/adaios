---
title: docs/rfc 目录索引
description: 决策记录区目录治理——RFC 清单 + 状态（draft/approved/implemented），过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-10-03
status: active
lines: 97
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
| 20261003-trading-cash-position-linkage.md | 资金与持仓的强关联——一账两源的收口（触发＝用户 2026-10-03「资金和持仓是强关联的：转入后有现金才能买，卖出后才有现金才能转出」；**生产实测**＝1721 笔流水仅 **30 笔（1.7%）** 走「记录→动现金」路径，其余走导入——而「持仓股导入」只改持仓、「资金股份导入」只覆盖现金，故两侧原子在最常用路径上不成立；按流水重放：09-11→09-23 现金虚高 **24,909.84**（＝600536 那 2 笔无委托号流水，**日志取证显示它们实际走了完整记账**）、08-16→08-23 少算 **25.42**（股息不在成交文件里，且既有 13 笔已于 09-23 被误清）；现金漂移轨迹取证：**−37,226.29 → −12,721.84 → −6,093.97 → +24,101.01**，而券商真值全程 ≤2,278.16；治法＝**七条契约**（单一账本·两侧原子 · 现金不足警告引导 · 可用/可取分离含 **T+1 A 档** · 全量导入改「对账+确认」 · 流水加 `cashApplied` 自证 · 幂等键补齐 · 锚定 fail-closed 收口）+ 三批实施 + 黄金回放夹具；**§2.5 元根因**＝数据给的是「状态」而系统要「事件」（通达信快照 vs 事件流，股息/转账不在任何导出里）+ 机制全在防「重复」没人在防「缺失与错位」（`integrity` 只管持仓不管现金——全程报绿而现金错 23,686.15）+ 结论「正确性被外包给文件完整性与用户记忆，系统无自证能力 → 出错只能来问用户」；**四项拍板**：D1 警告不硬拒 / D2 A 档 / D3 独立文件 / D4 先出报告（已交付）；**同源主题已拆出**→ 清仓复盘与「次日操作规则提醒」见 `20261003-trading-plan-and-review-loop.md`；**用户已定**：症结在资金↔持仓强关联、自选/清仓/持仓股导入**均保留不裁剪**）| draft |
| 20261003-trading-plan-and-review-loop.md | 交易计划与复盘闭环——事前承诺 · 事中守约 · 事后对账（触发＝用户 2026-10-03「我可以整理第二天操作规则，买和卖，到时候结合起来提醒我；必须像开公司一样认真对待每一笔投资，像打仗规划好子弹，必须抬高自身态度」）：**核心是反转「谁写计划」**——现状 09:15「早盘计划」是**系统算的**（近似建议），本方案改为**用户前晚自己定、系统只守约**（引用原话提醒，天然不构成投资建议）；**三步闭环**＝① 事前：`trading/plans/YYYY-MM-DD.md`（标的三要素：标的·条件·动作）+ **一句话写**（复用 `/trades/parse`）+ **没写要提醒**（20:30 一次，「明天不动」算合法计划）→ ② 事中：09:15 念计划 / 盘中条件触发「**你定的 45.50 到了**」/ 14:50 进度（三条纪律＝引用原话 · 只报事实 · 触发一次即止）→ ③ 收盘对账：**计划执行率** + ⚠️ **计划外操作**（正面命中 R96 四不原则）；**§五 深度复盘**＝「一轮完整交易」识别（口径 **C 同日合并**：清仓股表是标的级总账，160/172 的「买卖次数」等于全部流水笔数 → 172 条实际对应 **232 轮**，同日「卖光→买回」6 处合并、最快 **82 秒**）× 规则检查矩阵，**在用户 230 轮真实交易上实测**：R55 盈转亏 **65 轮 28.3%**（平均峰值 +6.9% → 最终 −4.3%、扛 5.4 个交易日）、R66 跌破 −4% 未当日走 **38 轮（平均多扛 13.2 个交易日）**、R69 被套加仓 **172 轮/819 次**、R53 32/77、胜率 **35.2%**；典型＝600584 长电科技 峰值 +9.4% → −25.9%（扛 9 个交易日）；**两条硬约束**＝止损位从未进系统（1721 笔流水 **0 笔**带 stopLossPrice）+ 复盘阈值必须进 `rules.yaml`（实测阈值 2%→94 轮 vs 3%→65 轮）；**边界**＝不生成计划 · 不下判断 · 不预测 · 不自动执行，只做「记录 · 提醒 · 对账」；含四批实施与 D1–D4 待拍板 | draft |
| 20261003-project-level-ai-context-layer.md | 项目级 AI 上下文中间层——工具无关的真相源与逐工具适配（触发＝用户 2026-10-03 补充目标：「我可以用 qoder / codex / claude code / DSH，需要一个中间层，项目级 AI 上下文在项目层、不与工具深度绑定、但每个工具都能读 skill/subagent/脚本」；**★实测校准版（两处重大修正）**：① **机制早已存在**——`.agents/scripts/link-skills.sh` + `.gitignore` 忽略三工具目录 + `guard-tools.sh` T4 检查已在运行，只是只注册 **1 技能（learn-digest）/ 1 工具（DSH）**，故由「新建中间层」改为「**扩展现有机制**」；② **DSH 三探针当场实测**：`.dsh/skills/` ✅（扁平与目录两种布局都认）、`.agents/skills/` ✅、**工作区根 `skills/` ❌ 不生效**，且技能清单**热更新**（无需重开会话）；治法＝真相源目录化 `<name>/SKILL.md`（Claude Code / Qoder 只认目录布局）+ 出口由 1 个扩到 4 个（含只服务 Qoder 的根 `skills/`）+ **沿用「只注册直触发技能」成本纪律**（审查官保持流程内触发）；落地先做批 1 最小闭环（1 技能 + 4 出口，当场可验））| draft |
| 20261003-skill-conformance-and-supply-chain.md | 技能包合规校准与 AI 供应链锁定——补上行业调研报告 §9/§12 指出的两个空白（触发＝用户 2026-10-03「补」；盘点＝16 个技能包为**扁平文件**布局，`name` 字符集/长度、五段结构、内容完整性**全无机器判据**；治法＝**选项 B**：对齐可对齐项 + 显式记录两条结构性偏离（不做目录迁移）+ 新增 `guard-skills.sh`（S1–S8）+ `skills-lock.json` 内容哈希「技能改动必须重签」；依赖层只做清单快照、**不引联网 SCA**；第三方技能准入留口）| draft |
| 20261001-feature-index-and-authoring-gate.md | 功能索引层与编写侧判据前置——把「功能」升格为唯一主轴（触发＝用户 2026-10-01「补需求/设计文档 + 每过程配角色 + 从文档树看每个功能」；盘点＝已有八成（RFC 六维立了 43 天采用率 **1/64**、RFC status **9 种写法**、`feature-reference.md` 1377 行**无 frontmatter 属图谱盲区**、12 审查官 : 3 建设技能）；治法＝**唯一新增物是功能索引层**（索引行全项目一张、**不做内容副本**）+ `guard-feature.sh` 状态对拍 + checklist 编写/审查双用；**角色不按过程铺开**，对抗只在架构选型/数据口径/契约变更三处、≤2 轮且必须有外部信号；**D2 已拍板降本版**＝索引行全覆盖 + 按需生长的 ≤12 行意图卡（每插件一份文件、卡内禁写实现细节），D1/D3/D4/D5 按推荐待确认）| draft |
| 20260929-conversation-context-engineering.md | 对话模式的上下文工程——从「平铺拼接」到分层装配（触发＝用户 2026-09-29「对话模式会丢失上下文」自查后的定性「目前没有采用什么优秀的实践」；量化＝30 天内 151 次对话模式中 **72% 注入的是「最近 20 条无关记录」**、**93.4% 的检索空转**、**60% 的对话 ≤5 轮**；三路调研对标（上下文工程 / 记忆架构 / 检索与评测）；治法＝**按轮次分档**（≤3 轮只注入身份+前文）+ 两臂召回 + 指纹去重 + token 预算 + 单条 system 与顺序重排 + 上下文回放，落点是项目预留但空置的 `context/prompt·policy·token` 三包）| draft |
| 20260929-context-engineering-batch1-design.md | **批 1 实施设计（文件级）**——把主方案的「少注入」五项精确到文件与行：按轮次分档 · 收掉「最近 20 条」· 去重复注入 · 单条 system 与顺序 · 真实 token 计量；含影响面（**10 处 `new ContextEngine(...)` 测试调用点**、`estimateTokens` 2 例断言）、新增 `adai.context.*` 配置（默认 `legacy` 可灰度可回滚）、测试计划、以及**动代码前必须先验的 DeepSeek `usage` 解析**（现为零解析）。拍板＝D1 保留极少核心 / D2 只对新记忆生效 / D3 先出实施设计）| draft |
| 20260928-market-source-consolidation.md | 行情渠道收敛与稳定——删东财 K 线源 + 腾讯双域名 + 新浪升兜底（触发＝用户 2026-09-28「几个行情渠道，有稳定的吗」；逐源实测＝腾讯 quote 200 / 腾讯 K 线 200 / 新浪 200 / 东财 push2his 000 / tdx 止于 09-04 → 名义四层只有一层在干活；治法＝批 1 腾讯第二域名（配置级）· 批 2 删东财出链路 + 新浪升兜底 + 可观测性 + tdx 周节奏降噪 · 批 3 tdx 数据包周导入）| approved |
| 20260924-trading-app-form.md | 交易 App 端形态重做——「一句判断 + 一行持仓」与截图入账三条主线（触发＝用户 2026-09-24「我对 app 端的交易插件还是不满意」+ Shopify 回归原生引出的「app 与 web 是两个角度」；七问收敛焦点＝无主心骨/主线不顺/太常规；首屏四层形态 + 截图入账三卡点解法 + 不做边界 + 三批实施）| draft |
| 20260923-rhythm-and-memory-temporality.md | 节律与记忆时效——「每周四发版」不是待办（概览卡天天提醒的根因与治法：节律独立为 Kernel 一等条目（RRULE）+ 记忆 bi-temporal 有效期 + 简报注入三闸 + 变更走「问一句」；触发＝用户 2026-09-23「阿呆 app 概览卡片天天提醒我」）| approved |
| 20260923-market-data-resilience.md | 行情（K 线）链路韧性——域名可配 + 第三源 + 可用性可见（2026-09-22 三条源同时失效：腾讯 K 线被 WAF 拦 501 / 东财被限 / tdx 滞后；治法 A 域名可配（默认备用域名 + 区间本地裁剪）· B 新浪作最后一层兜底 · D 健康端点 + 双端横幅）| implemented |
| 20260922-trading-decision-copilot.md | 交易插件目标形态——决策时点的对话式提醒与四要素铁证（日线/早盘买尾盘卖 → 只在早盘与尾盘出现、收盘后复盘；每条意见必须带本人历史统计 + 数字证据 + 规则原文 + 可回溯；账收盘后同步、行情自取）| approved |
| 20260918-trading-app-restructure.md | 交易 App 端重排——「记录 → 对账 → 照见」三层与两条铁律（UI/UX 专项审查 24 条的结构性收敛；三套口径以锚定日为界；四批 A1/A2/B/C）| draft |
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
| 20260815-media-event-unification.md | 图文一体——媒体事件数据层统一（一次输入 = 一条记录） | implemented（2026-09-22 提前落地：`mediaIds` + 薄附件 + Feed/Timeline 多图）|
| 20260815-image-chat-interaction.md | 带图交流——发图即对话（交互方案：AI 判定 log/ask 分流，ask 直进对话态） | approved（2026-09-22 用户拍板 A 方案；§九 实施记录）|
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
