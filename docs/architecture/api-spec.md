# AdaiOS API 文档

> 前后端接口契约。前端 Flutter、后端 Spring Boot，所有 API 返回 JSON。

**文档版本：v3.82 | 最后更新：2026-09-22**

---

## 变更记录

| 日期 | 版本 | 变更 |
|:----|:----|:------|
| 2026-09-22 | v3.83 | **S-11 B 批：三条推送改成决策时点的对话式提醒（RFC `20260922-trading-decision-copilot` §六 B；用户 2026-09-21 亲述「早盘买、尾盘卖，不太会在中间放飞和止损，需要阿呆提醒我、给我意见，尤其给我铁证」）**——① **B1 早盘（09:15）**：原 15:10 独立「买点提醒」**取消并并入早盘**；正文 = 持仓概览（昨收/数量/成本/止损/择时）+ 自选买点**逐条四要素铁证**（① 本人历史统计〔样本 N≥5，不足直说〕② 数字证据链 ③ 规则逐字原文 ④ 位置），**确定性渲染、不再走 LLM**（四要素必须逐条可指认）；买点段受 `buy-point` 开关门控；② **B2 尾盘（14:50）**：只列触发卖出条件（R66/R81）的持仓，四要素 + **账日期标注**（「按你 09-19 的账」）；③ **B3 收盘复盘**：由「15:30 到点硬发」改为**数据同步完成后触发**——导入持仓/资金/历史成交成功且 ≥15:00 即出复盘，否则 15:30 兜底；未同步则只说「今天的持仓/成交快照我还没看到」且**不落已发标记**（补导后仍能拿到真复盘）；每天至多一条；正文含 记账/账实/只记流水的/复盘/明天，账实判不了时直说判不了；④ **B4 行情失败显式降级**（**收口 P1-交易60**）：双源失败时不发带空洞数字的推送，改「今天行情我没取到，这条我暂时给不了」；逐只缺则在正文点名该只；⑤ **B5 午间（12:00）**：用户拍板保留但改**知会**——只报事实与位置（现价/涨跌/是否到你设的止损位），**不出现任何催促**，无异常不发。新增 `GET /trading/buy-points/scan`（`{hits, unavailable, dataDate}`；`/buy-points` 数组形状不变）；新增 `data/{userId}/trading/sync-state.json`（账同步/复盘已发状态，freeze MINOR）。端点 158 → **159** |
| 2026-09-22 | v3.82 | **图文一体：一次投递多图（RFC `20260815-media-event-unification` + `20260815-image-chat-interaction`；REVIEW P1-多图1/P1-多图2）**——新增 `POST /records/media/batch`（multipart `files` 1-3 张 + `text` 可空 + 可选 `Idempotency-Key`）：**一次投递 = 一个回合 = 一条主记录 = 一张卡**——N 张图各落一条**薄 image 附件记录**（仅原图索引：不做 VLM、不沉淀记忆、不单独进 Feed），主记录用新增 frontmatter 字段 **`mediaIds`** 引用全部附件（freeze §2.1 MINOR）；视觉侧**一次**多图理解（`VisualAiClient.understandMulti`）：无提问/陈述 → 一段**综合总结**（`type=image`），问句 → **据图作答**（`type=image_qa`，回答在 `answer`）。**幂等**：同 `Idempotency-Key` 的重发/重试直接返回首次结果（`duplicated=true`），**不重跑 AI、不重复落盘**（治 P1-多图2：客户端超时重试曾造成同一张图两份、md5 相同）。Feed 条目新增 **`mediaPaths`**（数组、按上传顺序；`mediaPath` 仍为首图兼容）→ 前端**一卡多图并列**；时间线条目同口径（薄附件不单独成条）。端点 156 → **158**（本批 +1：`/records/media/batch`；同日并发 A3 批 +1：`/trading/evidence/backfill`）|
| 2026-09-22 | v3.81 | **建议出口带四要素铁证 + 历史统计补「按形态分组」（RFC `20260922-trading-decision-copilot` A 批 A4）**——① `POST /api/v1/trading/advice` 的每条 `advice[]` 新增可选 **`evidence`** 对象（`{history, numbers, ruleTexts, basisId}`，**逐字段可空、补不出不编**）：`history` = 铁证①本人历史统计（**样本 < 5 → null**，调用方须说「样本还不够」）· `numbers` = ②当时的数字（现价 / 持仓占比 / 止损位）· `ruleTexts` = ③规则原文**逐字**（`rules` 里查得到的才进列表）· `basisId` = ④留痕 id（**A3 待填**）。出口**统一补齐**（成功与降级两条路径同一口径），**只读、不改建议本身**。② `GET /trading/evidence/history` 的历史统计**补 `BUY_POINT` 维度**（按买点形态分组）：形态记在**批次**上，用 `symbol+buyDate` join；**join 不上就归「未标形态」，不猜**（宁缺一个维度，不编一个形态）。③ **新增 `POST /trading/evidence/backfill`** —— 铁证④「结果回填」（幂等 · 只记事实不判对错 · 数据不全不写半成品）。端点 156 → **157** |
| 2026-09-22 | v3.80 | **「四要素铁证」底座上线（RFC `20260922-trading-decision-copilot` A 批）**——新增两个只读端点，把「阿呆凭什么这么说」变成可核对的数据：① `GET /trading/evidence/history`（**铁证① 本人历史操作统计**）：清仓回合按 `HOLD_DAYS`/`PNL_BUCKET`/`VERDICT` 分桶，给次数 / 胜率 / 平均盈亏 / 平均持天；**最小样本门槛 5**（用户 2026-09-22 拍板 D3）——不足的组 `sufficient=false`，全组不足时 `note` 直接说「样本还不够」，**不许拿 1-2 次巧合当规律**；② `GET /trading/evidence/rule/{ruleRef}`（**铁证③ 规则依据原文**）：按编号取 `rules.md` **逐字原文**（`R66`/`r66`/`66` 都认），找不到 → **404，不编造**。端点 154 → **156**（两个都是只读，不写任何用户数据；`/rule/**` 不做插件门控——规则原文属公共知识，运维可直接核对） |
| 2026-09-22 | v3.79 | **账实自检把「基线自洽的假绿」变成可见（P1-交易61 + P2-10）**——`GET /api/v1/trading/integrity` 响应新增 **`degraded[]`**（`{symbol,name,direction,volume,price,entryDate,inferred,reason}`）：**成交日 == 锚定日** 的流水被 `coveredByAnchor` 判成「已含在券商快照内」→ 只记流水、未进持仓。`drift`/`gaps` 对这种情况**结构上查不出来**——派生持仓与落地持仓**同源于那份快照**，必然相等（生产实据：09-18 导入的快照把锚定日写成 09-18 而非 09-17，当天 6 笔真实成交全部降级 → 持仓少 2 只、多算 400 股，而该端点 09-18~09-20 一直报「账实一致」）。判定依据是**锚定日是否为推断值**：落盘 `snapshot-anchor.json` 新增 `positionsFileDate`/`cashFileDate`（快照**文件里的导出日**，未归一化）——与锚定日不等即说明归一化动过它；`inferred=true` 的行才计入前端横幅（锚定日明确时那些成交确实在快照里，只是事实说明，不制造噪音）。**老锚定没有文件日期** → note 如实说「无法判断锚定日是否被推断」，不假装确定。**P2-10 补**：`snapshotDate` 是**未来日期**（文件名解析错/手改错）→ 按「没有文件日期」归一化，不再把锚定日写进未来（否则之后每笔成交都 ≤ 锚定日 → 全部静默降级，且锚定日只前进不后退、再也退不回来）。端点不变（响应加字段） |
| 2026-09-18 | v3.78 | **Feed 空态分流判据 `hasHistory`（REVIEW P1-UI14 复发修复）**——`GET /api/v1/feed` 响应新增 **`hasHistory`**（boolean）：该用户**是否有过任何历史记录**（不限当天），由本来就为筛当天而读的 `findAll` 派生，**零额外 IO**，**端点不变**。起因是「空 Feed = 新用户」这个假设：Feed 按天切，老用户当天还没记录（每天凌晨跨天时必现）就会收到 onboarding 口径的能力引导三问——用户原话「我只是今天没有数据，但我不是新用户，竟然也把用来引导用户的场景给了我」。判据必须放服务端：前端本地存「我是老用户」的标记一重装/换设备就丢，**正是本案现场**（用户刚更新 TestFlight 包）。前端据此分流——`false` = 真·新账号（空态播能力引导三问）；`true` = 老用户（空态改「今天还没听你说点什么。接着上次的聊也行，我记着。」，**不再摆三问**）。**旧后端不返回该字段 → 前端按 `false` 降级**（= 改动前行为，不崩不报错）。后端 2015 → **2019**（+4） |
| 2026-09-17 | v3.77 | **记录来源标记 + 付费动作令牌闸门（B4 凭据收口批）**——① **`POST /records` 新增可选 `source`**（P1-安全1 剩余项）：`external_entry` = Siri / 快捷指令 / `adai://record` 入口；缺省与**认不出的值**一律落回 `user_input`（白名单，**不因标记拒收记录**）。② **`POST /learn/digest/confirm` 新增令牌级频控**（S-凭据1 剩余项）：同一把外部令牌每分钟最多 **5 次**付费动作，超限人话拒绝；同一把钥匙**换来源 IP** 或超频 → WARN（**不阻断**——手机切网络会换 IP，但外泄正是这个形状，必须留痕），由每日巡检日报用人话捞出。**会话调用不受限**（限的是「钥匙被谁拿到」，不是限号主本人）；令牌标识经 `X-Adai-Token-Id` 由鉴权层注入（客户端不可伪造；该 header **非公开契约**，仅供服务端内部使用）。后端 1978 → **1985**（+7） |
| 2026-09-17 | v3.76 | **`purge` 语义修正 + 横排成交额校验 + 兜底源可见（B1 登记一致性 + B2 巡检遗留收口批）**——①**`DELETE /accounts/{userId}?purge=true` 响应契约变更**（P2-审查1）：`purged` 不再**无条件**为 `true`，而是「是否真清干净」（`failures` 为空才 true）；新增 `purgedDirs`（一并回收的空目录数）与 `failures`（逐项失败原因：删不掉的文件 / 目录列举失败 / 空目录清理失败）。原实现单文件删失败只 `log.warn`、最终仍回 `purged:true`，且**只删文件不删目录**（整棵空目录树残留）。②**横排表格补「价格 × 数量 = 成交额」交叉校验**（P1-交易56 残留）：列错位/OCR 漏列时成交额会被当成数量（生产实据 6827 元 → 6827 股），反推为正整数则修正为真实股数；**单笔 LLM 路径的 Schema 无成交额字段，仍无法校验**（如实登记，待跨层改动）。③**兜底源静默失效可见**（P2-交易58）：主源与兜底双双失败改为记 ERROR（30 分钟冷却防刷屏，冷却期内降 WARN），tdx 缺口补齐失败记 WARN 含缺口天数。④`recordTrade` 写记录补「**仅当日成交**」限定（P2-文档3，与 status v3.52 及实现三方对齐）。后端 1967 → **1978**（+11） |
| 2026-09-17 | v3.75 | **候选补日期 / 补元信息也改走行标识（P1-交易54 收尾）**——`PUT /trade-log/date` 与 `PUT /trade-log/meta` 新增**首选**参数 `id`（行级定位）；旧的 `symbol+direction` 口径保留但会命中**同代码同方向的多笔**（改一次日期会把那几条一起改，而它们可能来自不同成交日）。后端 1965 → **1967**（+2：只补 id 命中那一条 / 旧数据派生 id 仍稳） |
| 2026-09-17 | v3.74 | **截图入账识得「竖排表格」版式 + 候选行标识（REVIEW P0-交易53 / P1-交易54/55/56 / P2-交易57）**——**端点数不变（155），契约两处扩展**：①**`GET /trading/trade-log` 响应新增 `id`**（候选**行标识**）：候选此前没有 id，删除/补日期只能按「代码 + 方向」粗匹配——同标的同方向的多笔（生产实据：一张截图三笔亨通光电买入各 100 股）**一删全删**；旧候选文件无该字段 → 服务端按内容**派生稳定 id**（同一文件反复读出结果一致，否则前端拿着 id 删不掉）。②**`DELETE /trading/trade-log` 新增 `id` 参数（首选）**，`symbol+direction` 降为兼容旧客户端的粗粒度口径（正文已写明风险：同代码同方向会一起删、symbol 传空删光该方向）。③**`sameTrade` 去重补价格维度**：原来只看 symbol+方向+数量(±10%)，导致「同标的、同方向、各 100 股」的真实多笔被吞成一条（**解析修好也白修**）。④**识别链修复**（不改契约）：VLM 把表格「一行拆成多行」时横排正则 0 命中 → 原先降级单笔解析（Schema 只能装一笔）→ 一张 3 笔的截图只落 1 笔；现新增**竖排表格还原**（以方向行为锚点按行类型重建 + `价格×数量 = 成交额` 交叉校验修正数量），并把「有买卖字样却没认出来」的行如实进 `dropped`（原先彻底静默）。⑤**东财取数成功路径补 INFO 日志**（P2-交易57）：四个日志点原来全是 warn，叠加当日缓存命中，「成功率」在生产日志里根本答不出来。后端 1961→**1965** |
| 2026-09-17 | v3.73 | **learn 产物反馈 → 长期偏好（RFC `docs/rfc/20260917-learn-representation.md` §五 2b）**——**端点 154→155**：新增 **`POST /api/v1/learn/cards/feedback`**（body `{type,title,feedback}`）：把用户对卡片的评价（「太啰嗦」「多举例子」）**沉淀为一条 preference 记忆**（`Memory.fromFeedback`，置信度 0.9），从而经**画像回流**（v3.72 §四）自动作用于**下一次**卡片生成——反馈闭环不需要额外机制。**本端点不烧钱**：只写偏好、不调 LLM；响应 `canRepage` 告知「这张卡还有 `_raw` 素材、可按新偏好重排一版」，**是否重排由前端再问用户**（重排才花钱）。**幂等**：同一句话不重复沉淀（`status=exists`），防单句刷爆 Top 5 画像。**门控**：属「整理能力」，无 learn 插件仍 **403**（不适用 v3.72 的接收降级——接收免费、整理受控）。后端 1946→**1953**（本批 +7） |
| 2026-09-17 | v3.72 | **learn 门控 B：接收是基础能力、整理才是插件能力（RFC `docs/rfc/20260917-learn-representation.md`）**——起因：用户「**主要是把链路打通**」+「**学习是插件，可禁用的**」。只读盘点发现 `LearnController` **每个端点**都 `requireLearnPlugin` → 无插件用户分享一条视频**直接 403**，新用户冷启动第一步就断（详见 `docs/review/audits/2026-09-17-wiring-audit.md`）。①**`POST /learn/digest`（含兼容别名 `/learn/cards`）行为变更**：无 learn 插件时**不再 403**，改为「**只接收、不整理**」——素材落成一条普通记录（`type=note`、`source=external_import`，**不抓取、不转写、不调 LLM，零费用**），响应新增 `status=recorded`；**入参校验提前到门控之前**（无插件时 url/content 皆空仍 400，不得静默落一条空记录）。真正的花钱动作（抓取/转写/LLM 卡片化）**仍全部由 learn 插件门控，不旁路**。②**其余 learn 端点 403 不变**：`/digest/status`、`/digest/quota`、`/digest/confirm`、`/digest/image`、卡片读写等照旧。③**卡片生成回灌用户画像**（同 RFC §四）：`digest` 与 `repages` 的 user prompt 新增「我的长期画像」段——取 `MemoryService.findAllPreferences/findAllPatterns` **各前 5 条**（`PROFILE_TOP_N`），并明示「只决定怎么讲、不得复述、不得对用户下判断」；**无画像时该段不出现**（prompt 与改造前逐字一致）；画像读取失败**按无画像继续**、不影响消化主链路；日志只记条数不记内容。④**双端适配**：web 整理弹窗与 app 喂入页识别 `recorded` → **停止轮询**（无插件轮询必 403）并显示人话「已经帮你记下了。开启「学习」后，我可以把它整理成卡片。」。后端 1939→**1946**（本批 +5）全绿 |
| 2026-09-17 | v3.71 | **深夜第二批：通知深链 + 令牌轮换**（用户「继续，我还没睡」）——**端点 153→154**：①**通知点击深链**（P2-APNs1）：APNs payload 的 **root 级**新增 `adaiDeepLink`（不在 `aps` 里——那是系统保留区），取值由 `PushMessage.deepLink()` 从**已有字段**推导（带 `symbol` → `trading:<symbol>`，`learn-review` → `learn:review`，其余 → `trading:today`），所以**所有推送构造点零改动**、也不会漏传。客户端点击通知后据此定位到「那一条」并高亮 2.5 秒；找不到目标卡片时只刷新 + 滚到底（不假装定位成功）。旧推送 / 旧版 App 没有这个字段 → 行为与修前一致。②**新端点 `POST /api/v1/auth/tokens/{idOrPrefix}/rotate`**（S-凭据1 剩余项）：换一把新钥匙并**立刻作废旧的那把**，一次完成不留空窗（旧的撤不掉就把新的也回滚——两把同时有效比断链更糟）；label/权限继承旧钥匙，有效期重新算 90 天；前缀歧义同撤销口径**拒绝**；找不到 → 404。后端 1935→**1939** 全绿 |
| 2026-09-16 | v3.70 | **盘前当日盈亏口径 + 图片费用闸 + 来源标记可恢复 + 删号数据语义（晚间批，用户逐项拍板）**——**端点 152→153**：①**`dailyPnlDetail` 以「行情数据所在的交易日」为当日**（P2-交易51）：盘前 / 非交易日时行情接口给的「现价」仍是上一交易日收盘、「昨收」是再前一日收盘——照 `LocalDate.now()` 直接算，等于把**上一交易日的当日盈亏当成今天**（用户实测：09-15 真实 −503.90 被显示成 −180，同一持仓两天两个数）。现在盘前/非交易日自动改用上一交易日，并在 notes 首行标注「今天还没开盘…开盘后会自动换成今天的数」；**明确指定历史日期查询不受影响**。②**图片整理新增轻量日配额**（P2-learn26）：单次最多 3 次 VLM + 1 次 LLM 是花钱动作，而此前唯一限流是「同 user 单任务」→ `POST /learn/digest/image` 加每日张数上限（`adai.learn.image-daily-limit`，默认 30，0=不限），超限 400 人话；账本（`learn/_quota.json` 的 `images` 键，按日分键自动重置）读不出来 → **fail-closed 拒绝整理**。③**读图输出上限可按调用覆盖**（P2-learn25）：`VisualAiClient.ask(req,q,maxTokens)` 新增按次覆盖口子（**default 实现忽略 → 老实现零改动**），learn 图片链用 `adai.learn.image-max-tokens`（默认 4096）——长书页不再被全局 2048 静默截断。④**新端点 `POST /learn/cards/restore-origin`**（P2-learn21，见下）：origin 被抹掉的卡能认回来，判据不成立则人话拒绝。⑤**删号数据语义**（task-log #149）：`DELETE /accounts/{userId}` 默认**只删账号、保留 `data/{userId}/`**，`?purge=true` 才清理并回 `purgedFiles` 计数。⑥**读图提示词上限**与锁屏/配额等口径不变。后端 1926→**1935** 全绿 |
| 2026-09-14 | v3.67 | **登录设备可见 + 可单独撤销（登录体验批 L1+L2 后端部分，RFC `docs/rfc/20260914-login-credential-experience.md`）**——起因：2026-09-14 生产记录「阿呆阿呆的密码不能被苹果记住、自动填充，没有加入生物识别」。本批动的是**凭证生命周期**（客户端侧的钥匙串/Face ID 改造另行）：①**登录可选上报 `device`**（`{name, platform, appVersion}`，客户端自述、**不作安全判定**，只用于列表辨认；字段超长截断）；**登录响应新增 `sessionId`**（token 哈希前 8 位，前端据此判断「这台是不是当前设备」）。②**新增 `GET /api/v1/auth/sessions`**（列出登录着的设备：按最近活跃倒序、只列未过期、`device` 可为 null、当前会话 `current=true`）与 **`DELETE /api/v1/auth/sessions/{idOrPrefix}`**（撤销一台设备：**前缀非唯一命中 → 400**（防一次撤掉两台，与外部令牌撤销同口径）；**撤销当前设备 → 400** 提示用「退出登录」；找不到 → 404）；两条端点都**不续期、不写盘**，且复核账号仍存在且 enabled。③**向后兼容**：老会话文件没有 `device` 字段 → 读成 null（升级不会把已登录设备一次性踢下线）。端点 147→**149**；后端 1837→**1851** 全绿 |
| 2026-09-14 | v3.66 | **当日盈亏改券商口径 + 持仓列表逐股当日口径（用户拍板 A）**——起因：用户问「你可以精确算出每只股票的当日盈亏么？尤其像我这样中间有买卖的情况」→ 对账发现系统当日盈亏 **5826.18** 而券商 App「今日参考盈亏」**2245.00**，差 3587。①**口径修正（核心）**：当日盈亏「卖出部分」的基准由**建仓成本**改为**昨收**。原口径算的是「这笔交易从建仓到现在赚了多少」，把过去累积的浮盈记进了今天——实测云南锗业卖 100 股虚增 **3466 元**（成本 53.765 / 昨收 88.43 / 卖 90.32：旧口径 (9032−5376.53)=3655.47，券商口径 100×1.89=189）。用户提供的券商真值 2245 与逐股复算 `(90.32−88.43)×400 + (47.2−45.55)×900 + (14.81−14.83)×100 = 2239` 相差 6 元（两位小数价格精度），**口径确认一致**。②**行情符号集合**改为「**当前持仓 ∪ 今日有成交的票**」——今日卖光的票已不在持仓里，但它的昨收是算这笔卖出当日盈亏的**唯一基准**（旧实现只查持仓 → 该笔直接漏算）。③缺昨收 → 如实附注 + 不计入（`refreshTodayPnl` 见「实质未计入」拒绝写回，P2-交易46 的闸不变）。④**放弃**了原「建仓成本口径的已实现盈亏」——它本来就没有 UI 出口，本次起不再计算（`historicalBuyAvgCost` 随之删除）。⑤**新端点 `GET /api/v1/trading/positions/daily`**：`positions`（与原 `/positions` **元素同形状**，不改动那个端点）+ 平行 map `daily{symbol:{todayPnl, yesterdayClose, dayChangePct, positionRatio}}` + `totalPositionRatio`（总仓位＝持仓市值/总资产）/`cashRatio`/`notes`；`dayChangePct`=(现价−昨收)/昨收，`positionRatio`=该股市值/总资产（**含现金**口径，用户选定）。**所有可空字段消费端一律「—」，不得渲染成 0%**。端点 146→**147**；后端 1833→**1837** 全绿 |
| 2026-09-14 | v3.65 | **交易账本「丢行/丢值不可见」收口批（REVIEW P2-交易42/43/44/45/47/48 + P2-工程3/4）**——**端点总数不变（146）**，五处**对外行为/字段**变了：①**历史成交导入 `POST /trading/trades/import` 响应新增 `unparsed`（字符串数组，人话「第 3 行「…」：成交日期「2026080X」不是 yyyyMMdd 格式」）与 `unparsedCount`**——解析层原来 5 类 `continue` 静默丢行、响应无任何出口，用户只看到「识别出 N 笔」；现在每处丢弃带行号+原文+原因。同时修掉「价格列非数字 → `parseNum(...).stripTrailingZeros()` NPE 炸整个导入」的隐患。②**截图入账 `POST /trading/screenshots` 响应新增 `dropped`（字符串数组）与 `droppedCount`**——被表格规则跳过的行（未成交状态/新股申购/占位代码/0 价）原来只 `log.debug`。③**资金股份导入 `POST /trading/imports/cash` 响应新增 `unparsedRows`（int）**；且**首行正则命中但某一项读不成数字（如「余额:1.2.3」）→ 400 人话拒绝导入**（原来 null 一路写进 AccountSnapshot，账户资金变空且无提示）。④**账户快照 `GET /trading/account` 新增 `todayPnlSource`**（`"broker"` 券商文件求和 / `"calc"` 系统精确计算 / `null` 未知）——当日盈亏原来只给一个数字，同名字段有三个可能来源（2026-09-13「−2837 是周六算的」只能翻日志才定位）；前端据此标「券商口径 · 09-11」或「已过期」。⑤**`PUT /trading/profile` 空串与 null 同拒（400）**——原只判 null，空字符串可把 profile.md 整文件覆盖清空。另：全仓 18 个文件仓储的 JSON 读路径开启 `FAIL_ON_TRAILING_TOKENS`（损坏/截断文件不再被读成「合法前半段」后回写覆盖，P2-工程4）；**当日盈亏口径补充（用户拍板「券商负成本」）**：负成本持仓卖出的旧仓成本取**券商成本价（含负）**——原实现把负成本挡在门外会退化成「按净额计」（少算已实现），当日有买入致盘前成本取不到时退回历史买入均价并如实附注。后端 1817→**1833** 全绿 |
| 2026-09-14 | v3.64 | **部署前深审修复批（安全面：外部令牌生命周期 + 锁屏脱敏补全 + 抓取健壮性）**——**端点总数不变（146）**，但三处**对外行为**变了，前端与快捷指令需知道：①**外部工具令牌有了有效期**：新签发的令牌 **90 天后自动失效**（`POST /api/v1/auth/tokens` 与 `GET /api/v1/auth/tokens` 的每一项新增 `expiresAt`（ISO 字符串；存量老令牌为 `null` = 不过期）与 `id`（令牌哈希，64 位十六进制）——`id` 是撤销用的**稳定标识**，因为显示前缀只有 8 位十六进制、同账号碰撞时无法区分是哪一把）；签发响应同时新增 `id`/`expiresAt`，`notice` 说明有效期。②**撤销改为按 `id`**：`DELETE /api/v1/auth/tokens/{idOrPrefix}` 接受 `id`（推荐）或旧前缀；**前缀非唯一命中时拒绝撤销**（旧实现会一次删掉两把钥匙）。③**外部通知的锁屏文案口径收紧**（推送 payload，前端无需改）：锁屏正文改为 **fail-closed**——推送消息未显式给锁屏版时，外部渠道（APNs/Bark/微信）发中性文案「阿呆有新的提示，打开看看。」**不再回落完整正文**；行情/批次止损的**标题**同样不再带股票名（`PushMessage.notificationTitle()`）。站内 Feed 仍收完整正文，行为不变。后端 1805→**1817** 全绿；guard G1-G8 PASS |
| 2026-09-13 | v3.63 | **APNs 自有推送渠道批（RFC 20260913，付费开发者账号后「物尽其用」第一刀）**——此前 10 种推送（盘前/买点/止损/行情异动/收盘小结/复习提醒…）虽然全部投产，但**真正弹到手机这一步只能借第三方 App（Bark）转达**（`PushChannel` 只有 Feed 站内 + Bark 两个渠道），通知上写着别人的名字、点击也回不到阿呆。付费开发者账号解锁 `aps-environment` 能力后，新增**阿呆自己的 APNs 渠道**：①**新端点 `POST /api/v1/push/devices`**（客户端上报 APNs deviceToken：body `{token, platform?, environment?, bundleId?, label?}`——token 严格校验十六进制 32~200 位，因为它会被拼进 APNs 出站 URL 路径；同 token 幂等 upsert，保留首次注册时间；缺失/非法 → 400 人话）；②**新端点 `GET /api/v1/push/devices`**（本账号已登记设备）；③**新端点 `DELETE /api/v1/push/devices/{token}`**（注销，幂等返回 `{"removed":bool}`；登出时调用——否则换账号后新账号的止损/复盘推送会发到已登出的设备）；④**新端点 `GET /api/v1/push/status`**（链路自检：各渠道 enabled/configured + apns 的 keyId/teamId/灰度白名单 + 设备数——**配完 .p8 后用这一条确认生效，不必等下一次定时推送**）。⑤**环境分流（最易踩的坑）**：deviceToken 分属两套互不相通的 APNs 网关，故 `environment`（`sandbox`/`production`）**跟着 token 存**而不是跟后端部署环境走——侧载（development 描述文件签名）拿到的永远是 sandbox token，将来 TestFlight/上架才是 production；送错网关只会得到 `BadDeviceToken` 静默丢弃，因此推送日志把 reason 原样记下并附「下一步该干什么」的提示。⑥**灰度白名单**：`adai.push.apns.types` 逗号列表，留空 = 全量；第一刀只放 `close-summary,learn-review` 验证链路，验证通过清空即全量（不改代码）。⑦**失败一律不抛**：410 Unregistered → 自动清理该设备登记（否则此后每次推送都白跑）；其它错误 warn 记录但不影响推送生产方。⑧**落盘 `data/{userId}/push/devices.json`**（File First，per-user 条带锁原子写）；**损坏文件读路径降级为空、写路径拒绝写回**（防「损坏当空」后用空列表覆盖掉其它设备）。⑨凭据：APNs Auth Key（.p8，ES256）——比推送证书好，**不随年过期**且两套网关通用。客户端侧同批落地（`Runner.entitlements` 的 `aps-environment` + `Runner.xcodeproj` 三个配置挂 `CODE_SIGN_ENTITLEMENTS` + `AppDelegate.swift` 注册/回调 + `PushService`）。端点 139→**143** |
| 2026-09-13 | v3.62 | **当日盈亏券商口径入账 + 重算两道闸（用户实测：「屏幕上的当日盈亏 −2837 是对的么」→ 查出**真值 −1759.00**）**——两个独立缺陷叠在一起：①**券商「持仓股」导出的「当日盈亏」列从来没被读**（前端只解析 代码/名称/数量/成本；后端入参无此字段），而它是**权威值**——该文件 600206 −1116.00 / 002428 −644.00 / 600601 +1.00 = **−1759.00**，与逐股复算 `(45.55−46.79)×900 + (88.43−90.04)×400 + (14.83−14.82)×100` 一字不差，且「09-10 市值 79609 → 09-11 市值 77850」之差亦为 1759 → **`POST /trading/positions/import` 新增 query `todayPnl`**（前端把该列**全表求和**后传入，**含 0 股行**——当日清仓标的的已实现盈亏也在这一列里）；后端**三闸**才写：值为 null（缺列/有行取不到数）不写、无账户快照不写、**文件日期 ≠ 账户快照日期不写**（「当日」必须同日，否则就是混日期）；写前若已有同日值且不同 → **WARN 记录两个口径与差值**（差异可见化，不静默覆盖）。②**`refreshTodayPnl` 在周六被触发**（09-12 用户导入 09-11 历史成交 → 用周六的日期 + 周末行情接口给的「最后两个交易日收盘」+ 当时**被双计污染**的持仓，算出 −2837.00 并写成「当日盈亏」挂了整整两天）→ 新增**闸 1 非交易日不重算**（新增 `isTradingDayStrict`：周末 + 法定节假日；原 `isTradingDay` 只查节假日表，其调用前提是「周末由 cron 排除」，**禁止被非 cron 路径复用**——这次就是这么踩的）+ **闸 2 有实质未计入则不覆盖**（缺昨收/无成本基线时算出的值偏小，写回去比保留旧值更糟——它看起来像真的；「今日无成交记录」不算实质缺失）。③**当日盈亏三源与优先级定稿**：券商文件（权威，同日）> 收盘 15:05 精确计算（口径①）> 保留旧值；缺列/不可靠一律**不落零**（P2-交易37 约定）。④`todayPnl` 传非数字 → **400 人话**（「字段被无声忽略」正是本次事故的成因）。**端点 139→139（无增删，仅 query 扩展）** |
| 2026-09-12 | v3.61 | **交易账实一致性批（RFC 20260912 全量落地，用户「要流程上正解」）**——根治生产实测「一次历史成交导入把**已含在券商快照内**的成交又重放一遍」（持仓与现金双计，现金被算成 −26666.85）、**3 笔真实卖出静默消失**、4 笔流水重复落账：①**锚定 fail-closed**：`POST /trading/trades/import` 新增 query `mode`（`auto` 默认 \| `append`）——`auto` 按券商快照锚定**分派**（`entryDate ≤ 锚定日` 的成交只补流水，晚于锚定日才回放持仓+现金）；**锚定缺失而系统已有持仓/账户快照、且本次有需要回放的行 → 400 人话拒绝**（不再把「锚定读不到」当成「不做防重」继续重放），逃生路径 = 先导「持仓股」/「资金股份查询」快照建立锚定，或显式 `mode=append` 只补流水（全新用户无锚定无账目状态仍允许从零回放）；②**预检 `dryRun=true`**：只返回计划、**不写任何文件**，响应新增 `dryRun:true` 与 `plan:{new,merged,skipped,nonTrades,wouldReject,anchorKnown,syncMode}`；③**幂等统一（一个 intake、一个键空间）**：orderId 命中 → 缺元信息则合并回填否则跳过；指纹（`symbol\|direction\|entryDate\|price\|volume`）命中且成交时间兼容（任一侧缺失、旧值带纳秒、或相差 ≤1 分钟）→ **合并回填不新增行**（补 orderId/fee/成交时间），时间明显不同（同价同量同日两笔）→ 视为两笔——`updated` 语义改为**跨来源同笔合并回填**笔数；④**卖超/未持有不丢数据**：回放行 SELL 超出可归属持仓 → **只落流水 + `rejected` 明细 + ERROR 日志**（持仓/现金不动），新增 `rejected:[{symbol,name,direction,volume,price,entryDate,reason}]`（原因中文人话）；⑤响应新增 `anchor:{positionsReplace,cashImport,known,holdingsKnown,anchorDate}`（`anchorDate` = 两者较晚者，未知为 null）；⑥**新增对账闸门与锚定三端点**：`GET /trading/integrity`（`derived = 券商快照基线 + 锚定日之后流水净增减`，与落地持仓不一致即 `drift`，卖超缺口走 `gaps`，锚定/基线缺失诚实报「无法判定」）+ `GET /trading/anchor`（锚定状态只读）+ `PUT /trading/anchor`（存量环境显式回填锚定日/持仓基线，只改元信息、日期只前进）；⑦`POST /trading/positions/import?replace=true&snapshotDate=yyyy-MM-dd` 与 `POST /trading/imports/cash`（body `snapshotDate`）新增**快照自身日期**（通达信文件名里的日期；不传退回导入日）——补导几天前的快照文件不再把锚定日写成今天；⑧落盘 `trading/snapshot-anchor.json` 现为 `{positionsReplace,cashImport,recordedAt,holdingsRecorded,holdings:[{symbol,name,quantity}]}`（持仓 replace 导入记录基线并置 `holdingsRecorded=true`；更新锚定日**保留**既有基线，不把「未记录」写成「记录为空」——前者对账报「无法判定」，后者是合法基线）；⑨**收盘小结（15:30 `close-summary` 推送）新增一行账实自检**——委派 `TradingAppService.integrity`（唯一口径），有 `drift`/`gaps` 时推一行「⚠️ 阿呆对不上账：N 只标的的持仓和流水对不上、M 笔成交没能并进持仓——打开交易页，我把明细列给你看」（**无差异不推**，不制造噪音；自检失败静默降级，不中断推送主流程；文案遵循第一原则=阿呆口吻，非系统视角）。端点 134→**137** |
| 2026-09-13 | v3.61 | **learn 卡片管理批（补「缺口」：产品里终于能删卡 / 改主题）**——此前 learn 只有「写」没有「管」：清测试卡、给卡片换主题都得**手动改服务器文件**。①**新端点 `DELETE /learn/cards`**（`?type=&title=`）：**软删除**（文件移入 `learn/_trash/`，可人工捡回，不真丢内容）+ 从主题 README 索引摘行 + **级联清理**指向该卡的 trading 反哺候选（回应 REVIEW P2-learn11「孤儿回链」，回执里如实列出被清掉的候选标题）；**只读卡（别处整理的原始卡）人话拒绝**；②**新端点 `PATCH /learn/cards/topic`**（body `{"type","title","topic"}`）：把卡片挪到另一个主题目录（新主题内续号），frontmatter 的 `topic` 与**两个主题的 README**一起同步（老主题摘行、新主题追加，不重写手工内容）；同主题幂等；只读卡拒绝。端点 137→**139** |
| 2026-09-12 | v3.60 | **learn 完整升级批（用户拍板「我要的是完整的升级，成熟的方案」）**——learn 从「能用」变「完整可用」：产物契约与 Mac 侧技能统一 + 图片源 + 对话流 + 全文 + 搜索。①**落盘结构统一**：新卡从扁平 `{type}/{yyyy-MM-dd}_{title}.md` 改为 **`{type}/{topic}/NN-{slug}.md`**（主题目录 + 主题内编号，与 Mac 上 DSH 技能产物同契约），自动维护主题 `README.md`（产品只**追加** `## 阿呆整理记录（自动维护）` 段，**不重写**手工 README）；原始素材从 `learn/_raw/` 暂存区**归位**到 `{type}/{topic}/_raw/`（源与卡放一起）；老扁平卡**照旧可读可写、不强制迁移**。②**LearnCard 新增 `topic`（主题目录名，缺省「未归类」）与 `writable`（false = Mac 上整理的原始卡，只读）**；同名时**本产品卡优先**，别处手工卡同名不再拦住新建（P2-learn20 修复）。③**新端点 `GET /learn/content`**（按 md **原文**返回全文 + 元信息——列表只有产品建模的四段，手工卡的「关键内容详解/金句/概念关系」只在原文里）。④**新端点 `GET /learn/find`**（找卡片：「打开那篇」与学习页搜索，纯规则打分不烧 AI）。⑤**新端点 `POST /learn/digest/image`**（图片源：书页/PPT/讲义/截图 1~3 张 → 视觉模型**忠实提取**文字与图意 → 同一条消化流水线；原图先落 `_raw/`）。⑥`GET /learn/digest/status` 的 `stage` 新增 **`reading`**（正在读图）。⑦**新端点 `POST /learn/migrate`**（**幂等**：把 V1/V2 老式扁平卡 `{type}/{date}_{title}.md` 一次性迁到主题目录，补 `origin`/`topic` 键 + 主题内续号 + 维护该主题 README；Mac 侧技能整理的主题目录卡**一动不动**）。端点 130→**134** |
| 2026-09-12 | v3.59 | **learn 读侧对齐批（同一个 learn 目录有两个写入方）**——目录里既有产品写的卡（`{type}/{date}_{title}.md`），也有 Mac 上 DSH 技能 A 写在主题子目录里的手工卡（`{type}/{topic}/NN-{slug}.md`，文件名与段名都不一样）。本批**只改读侧与写守卫，不动任何落盘格式**：①`GET /learn/tree`、`GET /learn/cards`、`GET /learn/card` 现在能正确读出 A 形态卡的核心观点/疑问（段名容错：`## 核心观点（一句话）`、`## 二、核心观点` 均识别；A 的 `## 内容脉络` 不做语义改名，如实不映射为「关键要点」）；②`PATCH /learn/cards`、`PATCH /learn/cards/status` 对**产品之外的卡**（A 在 Mac 上整理的原始卡）返回 **400 + 人话**「这张《X》是在 Mac 上整理的原始卡，我在这里只当资料看、不改动它；想改的话我可以照它的内容另存一张能编辑的给你」（原先因按产品路径读写而报「卡片不存在」，语义不准）；③同端点的产品卡行为不变。无端点增删 |
| 2026-09-12 | v3.58 | **learn 抓取批·对抗审查修复（独立审查官 10 条：P0×2/P1×5/P2×3，全部处置）**——**对外行为有四处在用户可感知层面变了**：①**出站白名单（SSRF 修复）**：`POST /learn/digest` 的 `url` 现在会先过出站策略——**私有/回环/链路本地/云元数据地址、非 80/443 端口、非 http(s) 协议一律 400 人话拒绝**（「这个地址不像是能公开访问的内容页，我就不去抓了」），重定向改为**逐跳复检**（超 3 跳 → 人话拒绝），字幕/快照等第三方响应地址收敛到域名白名单，响应体加上限（正文 4MB / 音频 64MB，超限人话失败）；②**不再把「字幕接口报错」当「没字幕」**：接口报错/限流是可重试错误 → 直接人话失败（「B站字幕接口这次没返回（可能限流了），稍后再试一次」），**不再弹付费转写确认**（原先会引导用户为本来能省的钱买单）；同理**音频地址拿不到时在报价前就失败**，不让用户为做不到的事点头；③**转写费用按实际时长结算**（原先一律按预估；时长未知按 30 分钟估会低估）——`GET /learn/digest/quota` 与 `cost` 里的数字现在反映转码产物的真实时长；④**先预留后花钱**：转写前先记账（账本写不进去就一分钱不花），转写失败**退回预留**，因此「转写失败」不再消耗额度；另：账本文件损坏时**拒绝转写**（fail-closed，原先按空账本处理等于额度归零、闸门失效）；落盘要求「核心观点或要点至少一个非空」（原先只有标题也会落一张空卡）；等确认期间 `stage` 置空（原先返回 `transcribing`，前端会显示「正在转写」而实际在等你拍板）。无端点增删 |
| 2026-09-12 | v3.57 | **learn 抓取批（RFC 20260912 D 形态，阶段 1 抓取主干）**：learn 从「用户自己搞素材来粘」升级为**服务端自己抓**（这是 B 形态失败的根本原因——把最费力的一步留给了用户）。①**新端点 `POST /learn/digest`**（旧 `POST /learn/cards` 保留为兼容别名）：body 支持 `url`（**服务端抓取** B站视频元数据/字幕、文章正文）或 `content`（降级路径：抓不到时用户粘正文），二者至少一个；素材框里只粘了一个裸链接 → 自动按链接处理（不再拿链接本身当素材白烧一次 AI）；链接与正文同时给出 → 正文为准、链接只记来源；②**新端点 `POST /learn/digest/confirm`**（费用确认，**§3.8 费用可控条 5**）：无字幕视频要花钱转写，先回「该视频 37 分钟，预计约 0.18 元（本月剩余额度 10 小时）」→ 用户点头才真调云端 ASR；`{"confirm":false}` = 取消（元数据已留存，**不产生费用**）；③**新端点 `GET /learn/digest/quota`**（费用可控条 4）：本月转写用量/费用/剩余额度 + 单价 + ASR 可用性（不可用时带人话原因）；④**`GET /learn/digest/status` 扩展**：新增 `stage`（fetching/transcribing/structuring，抓取批后耗时从秒级变分钟级，进度要可见）、`source`（抓到的平台/标题/作者/时长回显）、`cost`（单次预估 + 本月额度）；status 取值新增 `needs_confirmation` / `cancelled`；⑤**费用可控六条落地**：字幕优先（有字幕就不转写）／同一素材只转一次（转写稿落 `_raw/{platform}-{id}-transcript.txt`，重整理零费用）／只在你明确发话时花钱（无批量后台转写）／月度配额硬闸 + 记账（`data/{userId}/learn/_quota.json`，月初自动重置，超限拒绝并说明剩余）／单次前置报价确认／ASR 走端口（可换更便宜通道）；⑥**源必留痕**：抓到的元数据/字幕/文章全文/转写稿全部落 `learn/_raw/`（文章会失效、原音频丢了不可重建）；⑦**首期不做清单明确**：YouTube（服务器网络不可达）、公众号/知乎/小红书/X/微博/抖音（反爬与登录墙）→ **人话告知 + 给替代路径**（把正文粘进来），不假装能抓、不绕登录墙 |
| 2026-09-10 | v3.56 | **learn 喂入入口批（用户拍板「先页面后对话流」，页面喂入）**：`POST /learn/cards` 由**同步消化改为提交式**（对齐复盘 submitReview 先例——learn 卡片化同走 LLM，几十秒级生成远超前端 15s/120s 客户端超时，原同步 POST 前端必先断「看似没反应」）：响应改 `{"status":"pending"|"running"}`——受理入后台执行器（learnSubmitExecutor 2 线程/8 队列，满 → 400「消化任务繁忙」）；同 user 已有消化在跑 → 直接 `running`（连点/双端并发只烧一次 AI）；body/校验不变（content 必填≤50000、type 越界 400、同 type+title 同名拒绝、fail-visible 素材留存 _raw/）。**新增 `GET /learn/digest/status`**（返回 `{"status":"idle"|"running"|"done"|"failed","type"?,"title"?,"message"?}`——done 后前端按 type/title 打开新卡；failed 带人话 message；done/failed 结果 60s 惰性清理回 idle）。卡片完成仍落 `data/{userId}/learn/`，产物查询全走既有端点不变 |
| 2026-09-09 | v3.55 | **当日盈亏精确计算（P2-交易37，用户拍板口径①）**：`GET /trading/account` 的 `todayPnl` 语义升级——收盘任务（15:05）按口径①写入精确值（当日已实现（卖出净额−卖出成本，当日买入冲抵/旧仓 avgCost/清仓回退历史买入加权） + 持仓日浮动 (现价−昨收)×数量（当日新买入按成本） + 当日股息入账（+）/红利税（−）），不再只是持仓浮动估算；`POST /trading/imports/cash` 明细表头**缺「当日盈亏」列 → 保留既有 todayPnl 不清零**（不再静默写 0），带列才以券商真源覆盖；数据前提：当日成交需经系统流水（导历史成交/手动记录），缺昨收/缺成本基线的部分不计入并后端 notes 记日志 |
| 2026-09-09 | v3.54 | **交易账目治本 + 清仓级联批 1（REVIEW P2-交易34/35/36 出表 + RFC 20260909）**：①**券商快照锚定防重（P2-交易34 治本）**——持仓 replace/资金股份导入落锚定日（`data/{userId}/trading/snapshot-anchor.json`）；`POST /trades` 等手动/确认成交 entryDate ≤ 锚定日 → 400（提示历史成交导入补流水或重导快照）；`POST /trading/transfer` date ≤ 最近资金快照日 → 400（纯净投入修正走 `PUT /trading/principal`）；历史成交导入对 entryDate ≤ 锚定日改走补录（只补流水不重算持仓/现金，股息类同日期跳过）；②`GET /trading/sold` 响应结构改为对象 `{"sold":[…SoldTrade 数组，每项新增 provenance:"flow"|"import"], "pendingClearances":[{symbol,name,sellDate,reason}]}`（RFC 20260909 双轨：flow=流水自动收录、pending=已清仓但缺买入基线的待补档案提示）；③**成交编号/手续费补链（P2-交易36 治本）**——当日候选新增 `orderId`/`fee` 字段，新增 `PUT /trading/trade-log/meta`（候选确认前补填）+ `PUT /trading/trades/{tradeId}/meta`（已落库流水按 id 补填 orderId/fee，幂等，404/无值语义）；`POST /trading/trade-log/confirm` 落库改走带 orderId/fee 链路（候选透传流水） |
| 2026-09-07 | v3.53 | **learn V2 审查修复批（2026-09-07 learn V2 增量深审 S-learn1/2 + P1-learn1~4 + P2-learn2~8 出表，用户拍板）**：①**状态流转约束**：只允许 new→review→done 与回退 review→new / done→review（跳变 new→done、done→new → 400）；进入 review 时卡片写 `review_at`（服务器日期，S-learn1 计时起点）——复习提醒按「进入复习队列满 7 天」提醒（不再按消化日 created 误判），同卡 7 天内不重复推（`reminded_at` 节流）；②**跨日同名拒绝**：`POST /learn/cards` 与候选生成改为「同 type + 同 title 任意日期已存在 → 400」（跨日同名曾致标题寻址歧义改错卡）；多张同名残留读侧抛 400 列日期；③**learn_card_id 回链精确化**：= learn 源卡真实文件路径（清洗后 title），不再 raw title 拼接；④**复习提醒开关 learn 侧可达**：新增 `GET /learn/push-settings`（返回 `{"learn-review":bool}`）+ `PUT /learn/push-settings/learn-review`（body `{"enabled"}`）——纯 learn 用户（无 trading 插件）也能自关，不再只藏交易设置页；⑤**编辑并发/保真**：编辑 merge 移入仓储锁内原子完成（并发 PATCH 不丢更新），写盘保留手工未知 frontmatter 键/正文段；⑥**Feed 类型级门控**：learn-review push 条目只需 learn 插件、交易类 push 条目需 trading 插件（防跨域漏给纯 learn/纯 trading 用户）；learn-review 条目 tags/domain 不再标「行情」 |
| 2026-09-07 | v3.52 | **复盘生成改提交式（复盘超时修复批，用户实测「点击复盘没反应」）**：`POST /trading/review` 不再同步阻塞等 AI（生成实测 77~176s，远超 App 15s/Web 120s 客户端超时——原实现前端必先断，「点击没反应」而复盘实际已在后端生成落盘）。改为**提交即返回 + 后台执行器生成 + 同日去重**：响应改 `{"date","status"}`——`exists`（已有复盘不重跑，前端 GET 即展示）/ `running`（同 user+date 正在生成中，连点只跑一次）/ `pending`（已受理，后台生成中）；生成失败只记日志不落半成品。前端轮询 `GET /trading/review?date=`（404=未就绪，200=内容）。**口径（2026-09-07 用户拍板）：交易写聊天记录仅限当日成交**——历史成交导入/补录/回放等非当日批量回填不再写 `domain=trading` 时间线记录（防逐笔刷 Feed；复盘卡点早已改「当日真实成交」口径，不依赖记录关键词） |
| 2026-09-07 | v3.51 | **learn V2 消化闭环批 4（复习提醒推送）**：新增每晚 20:00 定时推送（LearnReviewPushService）——遍历启用 learn 插件的用户，聚合「进入 review 已满 7 天仍未 done」的卡片推一条汇总（type=learn-review，标题「学习复习提醒」，PushChannel 渠道化进 Feed/外部渠道）；推送类型 `learn-review` 加入 PushSettings.ALL_TYPES（`GET/PUT /trading/push-settings` 可开关，默认开）；Feed push 条目注入门控由 trading-only 放宽为 **trading 或 learn**（纯 learn 用户也能在 Feed 看到复习提醒；market 行情条仍 trading-only） |
| 2026-09-07 | v3.50 | **learn V2 消化闭环批 3（trading 候选联动，RFC 20260829 3.5③）**：新增 `POST /learn/cards/candidate`（body `{"type":"trading","title"}` → 把 trade_related=true 的 trading learn 卡片反哺成规则候选——提炼建议卡落 `data/{userId}/trading/candidates/{date}_{title}.md`，含 `learn_card_id` 回链 learn 源卡；非 trading / 卡片不存在 / 未标 trade_related → 400 人话（审核闸前置防语义漂移））+ `GET /learn/cards/candidates`（候选列表，created 倒序，用户审核用）+ `DELETE /learn/cards/candidates`（?title= 删除，幂等）；候选只存建议不复制整卡（跨域无双写），不自动入库——需在交易知识库工作流审核后融合归正式目录（同复盘 promote 哲学） |
| 2026-09-07 | v3.49 | **learn V2 消化闭环第一批（复习流转 + 编辑，RFC 20260829）**：新增 `PATCH /learn/cards/status`（复习状态流转 new→review→done：body `{"type","title","status"}` → 原地改 frontmatter status 返回更新后卡片，正文/手写复述段原样保留）+ `PATCH /learn/cards`（编辑卡片正文：`?type=&title=` 定位，body 部分字段补丁 `{"coreView"?,"keyPoints"?,"questions"?,"retell"?,"tradeRelated"?,"tradeNote"?,"tags"?}`，缺省字段保留原值，type/title/created 不可改（改=移动文件拒绝））；LearnCard 新增 `retell` 字段（复述段建模——24h 内自己写 100-200 字消化关键，V1 漏建模仅空段，V2 读写对称 + 可编辑） |
| 2026-09-06 | v3.48 | **learn 插件 V1（RFC 20260829，独立端点喂入，用户 2026-09-06 拍板）**：新增 `POST /learn/cards`（喂入素材消化：body `{"content"必填≤50000,"type"?ai/trading/other,"platform"?,"author"?,"url"?,"published"?}` → AI 结构化 RFC 3.4 四段卡片落 `data/{userId}/learn/{type}/{date}_{title}.md`；LLM 失败/输出不可解析/缺标题 → 素材留存 `learn/_raw/` + 400 人话 fail-visible；同日同 type 同 title 重复 → 400「已有同日同名卡片」；type 越界回落 other；非 trading 内容 trade_related 强制 false）+ `GET /learn/cards`（`?type=` 筛选，created 倒序）+ `GET /learn/tree`（资产树按 ai/trading/other 分组）；全部需 learn 插件（未启用 403）；learn 是第三插件（PluginRegistry），不进 domain 收敛体系（type 仅文件分类） |
| 2026-09-05 | v3.47 | **交易⑤认知层（RFC 20260905，用户拍板全量执行）**：新增 `GET /trading/advice-history`（建议留痕查询：`?symbol=&days=` 按票近 N 天回查、缺 symbol 返回近 30 天全量倒序——「阿呆当时说 X」数据源）+ `GET /trading/profile`（个人交易画像：`{stats{…客观统计}, objectiveText, adviceAdherence{…建议遵守率}, subjective}`）+ `PUT /trading/profile`（body `{"content":"…"}`，保存画像主观层落 profile.md）+ `GET /trading/sold/{symbol}/psychology-questions`（按交易结构确定性生成 3~5 个补情绪提问）+ `POST /trading/sold/{symbol}/psychology/answer`（body `{"psychology":"…"}`，回答回填 sold.psychology 追加式 + 沉淀画像主观层）；建议出口逐票落建议留痕（含降级 degraded 标记）；复盘注入「建议对照」段（当日清仓 vs 卖前建议）；画像注入 trading/decision 场景与建议引擎 prompt（A 点） |
| 2026-09-04 | v3.46 | **账号矩阵（语义，无端点变更）**：内置管理员由 `adai` 迁为 **`admin`**（后台管理专用：seed 预置/不可删禁降级/插件保护全部随 `SEED_ADMIN_ID` 迁移）；`adai` 降为**产品主账号** role=user（app/web 登录，个人数据 `data/adai/` 不变，plugins 保留 trading/project）；再建普通受限账号（role=user、plugins=[]，如 family）——`/accounts`、`/auth/setup`、`GET /accounts/available` 语义与示例同步更新 |
| 2026-09-04 | v3.44 | **买点三重校验（REVIEW P1-交易9/20 出表，2026-09-04 晚间自主批 III）**：`GET /buy-points` 判定语义按课程校准 + 防连板误报——①B1「回调一半」几何改**课程口径**：回撤占波段（窗口最高 high − 最低 low）≥ 回调比例（默认 0.5，即 close ≤ (high+low)/2）；②B2 放量阈值默认 **1.5→2.0**（倍量柱，rules.yaml/adai 规则包同步）；③B2 加三重防护：KDJ.J 拐头向上 + **J 连续 ≥90 高位钝化排除**（首日拉起放行）+ 距窗口低点涨幅 >30% 不追 + 近 2 日连板（≥9.8%）不推；④响应命中项附 `dataDate`（判定 K 线最后日期）；15:10 定时推送要求 `dataDate=当日` 才推（防滞后一日信号冒充今日，楚天龙实锤） |
| 2026-09-04 | v3.43 | **按批次止损编辑闭环（决策文档 P1 方案 A，2026-09-04 晚间自主批 II）**：新增 `PUT /trading/lots/{lotId}/stop-loss`（body `{"stopLossPrice": 12.34}`，>0 且 ≤4 位小数，lotId 不存在 404——给某个买入批次单独设/改止损，落 `data/{userId}/trading/lot-stoploss.json` 覆盖层，不污染流水）+ `DELETE /trading/lots/{lotId}/stop-loss`（清除覆盖回退流水止损/默认 −7%，幂等 404）；`GET /trading/lots` 的 `stopLossPrice` 语义更新——批次推导后合并覆盖层（覆盖 > 流水止损 > 默认 −7%），推送/行为标注/复盘自动跟随 |
| 2026-09-04 | v3.42 | **行情数据包导入（MD17，task-log 2026-09-04 登记）**：新增 `POST /admin/market/tdx-import`（multipart `file`，登录 + role=admin）——上传通达信日线 .zip 数据包 → 后端校验包结构 + 每个 .day 可解析 → 按 sh/sz 前缀分流原子落盘 TDX 行情目录（`adai.market.tdx-path`）→ 返回 `{status, filename, imported, skipped, failed[], markets{sh,sz}, dayFilesAfter}`；空包/无 .day/非 zip → 400 人话。数据包 >5MB：生产需配 `ADAI_MAX_FILE_SIZE`/`ADAI_MAX_REQUEST_SIZE` |
| 2026-09-04 | v3.41 | **活跃市值区间开关（用户手动判定，2026-09-03 对话「指南针活跃市值=一切的前提」确立）**：新增 `GET /trading/market-stage`（读用户手动判定的活跃市值多空区间：`{"exists":true,"stage":"bear","updatedAt":"..."}`，无记录 → exists=false）+ `PUT /trading/market-stage`（body `{"stage":"bull"|"bear"}`，两档，非法 400；落 `data/{userId}/trading/market-stage.json`，per-user 锁原子写）；时段推送（早盘/午间/尾盘）的【择时状态】改三级读取——**用户手动判定优先 → current.md → 「择时状态未知」**（用户判定后不再被 current.md 的 OAMV 规则推断覆盖）|
| 2026-08-31 | v3.35 | **双止损位（trading-risk-plan，响应字段扩展，无新端点）**：`GET /trading/positions` 响应新增 `computedStopLossPrice`（系统计算止损：R=本金×1%，距离=min(R÷市值,5%)，动态算不落盘）+ `effectiveStopLoss`（生效止损=max(人工,计算)）；R66 判定/接近止损预警/建议引擎统一改用生效止损 |
| 2026-08-30 | v3.34 | **流式问答（P2-用户2 批 2）**：新增 `POST /records/ask-stream`（SSE 流式问答：`text` 增量事件 + `meta` 定稿事件 + `[DONE]`；后端内降级——模型无增量输出时回退同步 understand 一次；同卡同问 5 分钟去重直返既有回答；UTF-8 字节透传防中文乱码）+ 双端（adai-app/adai-web）`SseClient` 流式渲染（90ms 节流草稿、error 事件人话透出、流开始前失败自动降级旧同步端点） |
| 2026-08-30 | v3.33 | **交易插件规则层（第三阶段，trading-plugin-architecture.md）**：新增 `GET /trading/rules`（用户自己的交易规则参数：仓位上限/默认止损/行为标注阈值/买点参数/打分权重/硬约束区间，无规则 → 默认值）+ `PUT /trading/rules`（覆盖非空字段，落 `data/{userId}/trading/rules.yaml`）；规则参数按用户隔离，驱动止损/仓位判定、买点信号、行为标注、清仓 verdict、打分权重、建议硬约束、知识注入（`data/{userId}/trading/knowledge.md` 用户私有知识优先，os/ 作 adai 默认）——**每个人有自己的交易系统** |
| 2026-08-27 | v3.32 | **截图入账缺日期禁落库批（用户拍板「截图缺日期禁止落库，补充日期后再确认」二修）**：`POST /trade-log/confirm` 对**截图归集候选（source=image）无 `tradeDate` → 禁止落库**（计入 skipped、候选保留、failures 人话提示「缺少成交日期」）——不再回退确认当天；新增 `PUT /trade-log/date` 补写候选成交日期（补日期后再次确认可正常落库，成交日 ≠ 确认日不再记错） |
| 2026-08-27 | v3.31 | **截图入账日期归属修复批（用户反馈「今日 4 笔其实是昨天」）**：候选新增 `tradeDate`（截图表格「日期」列提取的成交日期，无 → null）；`POST /trade-log/confirm` 落库 `entryDate` 用候选 `tradeDate`（无日期才回退确认当天）——成交日 ≠ 确认日不再记错日期 |
| 2026-08-26 | v3.30 | **截图入账 + 复盘卡点批（契约同步）**：新增 `POST /trading/screenshots`（multipart 1-3 张 → VLM 归集候选，不建记录/不落原图）；`GET /trading/has-activity` 口径改「当日真实成交 > 0」（废除关键词扫描，复盘与截图入账成闭环）|
| 2026-08-25 | v3.29 | **一键按流水重建持仓（用户场景 2026-08-25）**：新增 `POST /trading/sync`——以流水为准重建 positions（已清仓快照残留自动移除，如中电电机；流水解释不了的真底仓保留 INIT），返回 `{positionCount, removed, keptInitial}`；与「每日导当天成交 sync 模式」互补（sync 处理增量、本端点对齐存量账本）|
| 2026-08-25 | v3.28 | **RFC 20260825 逐笔批次跟踪与行为纠偏（契约同步）**：新增 `GET /trading/lots`（批次视图：按日合并/LIFO 卖出/回合/初始批次/对账）；`POST /trades/import` 双模式（`syncMode` sync 同步持仓 / append 只补流水）+ 响应新增 `summary` 每日操作总结（买卖聚合 + 批次 diff + 行为标注六类）；推送事件加 `expiresAt`（行情类次日 09:30 消失——收盘后晚上仍可看，次日开盘前自动清；汇总类次日 23:59；`pushes/{date}.json` 记录新增字段，旧数据按类型默认保留期）|
| 2026-08-23 | v3.27 | **推送链路修复批（契约同步）**：`MarketPushEvent` 落库透传原标题（P1-推送1 根因）；`DELETE /trading/pushes/{id}` 推送删除持久化（P1-推送2）；`GET /trade-log` 去重 ±10% 区间（sameTrade）|
| 2026-08-23 | v3.26 | **隔离审查残留批（契约同步）**：`DELETE /trade-log` 丢弃保留候选（失败/不完整钉子户出口）；`GET /trade-log` 去重口径补 ±10% 区间（sameTrade）；`POST /trade-log/confirm` 响应明确 failed/skipped/failures 语义 |
| 2026-08-23 | v3.25 | **交易修复批（走查修复，契约同步）**：`POST /trades/batch` 逐行字段校验（代码/方向/价格/数量非法 → 行级人话失败，P1-2）；`POST /trades` 的 `direction` 必填（缺省 → 400，P1-1）；`POST /trade-log/confirm` 响应扩展 `{confirmed, failed, skipped, failures}`（失败/不完整候选保留不丢，P0-1）；buy-points 参数契约同步 KDJ.J<13（S6 确认）；has-activity 门控声明修正（见 §5 该行）|
| 2026-08-22 | v3.24 | **首屏提速（主页启动慢修复）**：新增 `GET /api/v1/brief/cached`（只返回 5 分钟缓存 Brief，不触发 AI 生成，空串=缓存过期）；双端主页首屏并行拉 feed+缓存 brief、渲染只等 feed，简报后到单独刷新——AI 简报不再阻塞主页加载 |
| 2026-08-18 | v3.23+ | **确认批次（2026-08-18）**：`POST /trades/batch` 补实现（此前前端调用一直 404）；`POST /positions/import?replace=true` 全量覆盖语义（文件为准，缺失删除）；`PUT /principal` 本金设置（总盈亏=资产−本金）；BUY 止损/买点放开为可选（app 简化）；`POST /trades/import` 历史成交导入（第五份文件，幂等+对账）|
| 2026-08-17 | v3.23 | **RFC 20260817 三项**：`GET/PUT /trading/push-settings[/{type}]`（推送开关）、`GET /trading/trade-log` + `POST /trading/trade-log/confirm`（交易日志自动归集：截图/文字识别 → 当日候选去重 → 用户确认落库）；推送内容结构化（总结+持仓逐行+建议）|
| 2026-08-17 | v3.22 | **交易 A-E 全部端点**：`/trading/account`（账户快照，含 principal/totalPnl）、`/trading/transfer` + `/trading/transfers`（银证转账净投入）、`/trading/imports/cash`（资金查询）、`/trading/imports/save`（文件留存）、`/trading/buy-points`（买点信号）、`/trading/sold/score`（复盘三维打分）、`PUT /trading/positions/{symbol}`（持仓编辑）——合计 15 端点 + 修订（手续费费率、account 语义、示例修正）|
| 2026-08-15 | v3.20 | **合并插件端点（S-R2）**：新增 `PATCH /accounts/{userId}/plugins`（body `{add[], remove[]}`，服务端账号级锁原子合并——根治前端全量 PATCH read-modify-write 并发互覆）；内置管理员插件受保护（400）|
| 2026-08-15 | v3.19 | **展示层聚合（S-2 图文一体）**：`mediaPath` 语义扩展——`type=image_qa` 记录（带图 ask 聚合后的图文事件）也返回媒体路径（引用首图，前端渲染缩略图）；`type=image` 原语义不变；多轮 chat 时间线按会话聚合为单条（记录层不变，仅展示口径）|
| 2026-08-15 | v3.18 | **Domain=插件模型（RFC 20260814 第二步，插件门控）**：新增 `GET /me/plugins`（当前用户启用插件，前端模块显隐）；Account 新增 `plugins` 字段（`POST` 建号可选 / `PATCH` 可改，仅 `trading`/`project`，非法 400）；domain 判定规则按用户启用插件收敛（D5：无插件用户只判 `life`，AI 判定若属未启用插件 → 收敛 `life`）；`POST /trading/reviews/{date}/promote` 仅启用 trading 插件用户可用（否则 403）；Feed 行情条/异动推送仅注入启用 trading 插件用户；**R2 D1 通用化**：记录自动转任务去 domain 门槛（任何可执行记录即转，`sourceRecordId` 不再限 domain=project）|
| 2026-08-14 | v3.17 | **Phase 1 带图 ask（多图问答）**：新增 `POST /records/media/ask-batch`（已上传 1-3 张图片一次提问 → VLM 综合多图回答 → `image_qa` 记录引用全部图片 ID + Q/A 追加首图卡）；intent 分流与文本记录一致（`IntentRecognizer` 判定，问句 → VLM 多图回答 / 陈述 → 纯记录；AI 失败降级问号启发式）；图片数量上限 3 张 |
| 2026-08-13 | v3.16 | **R2 记录↔任务关联**：任务模型新增可选 `sourceRecordId`（domain=project 记录自动转任务时关联源记录 `rec_xxx`）；非破坏性字段新增，前端手动建任务为 null |
| 2026-08-12 | v3.15 | **正文与 changelog 对齐（REVIEW #238）**：`POST /records/media` 错误列表 400（非图片）/ 413（超限）拆分；`POST /records/media/{id}/ask` 补「问题超过 500 字符 → 400」（v3.12 已声明，正文同步）|
| 2026-08-12 | v3.14 | **收官批 O（#166/#170/#202/#231/#122 等）**：AI 交互日志响应新增 `systemPrompt` 字段（generate 的复盘模板指令，understand/intent 为 null，#231）；上传超限改 413（`MaxUploadSizeExceededException` → PAYLOAD_TOO_LARGE，原 500，#166）；`/accounts/available` 契约补充无鉴权说明（#215 已最小集，此条再确认）；待办建议 prompt 改第二人称（#170）；复盘生成剥代码块围栏（#202）|
| 2026-08-12 | v3.13 | **REVIEW #129/#218/#222**：promote 前端入口说明（交易页复盘弹窗「反哺入库」按钮，`POST` body 传 `{}`）；AI 交互日志视觉调用补真实耗时（`LoggingVisualAiClient.durationMs`）；Brief 问候加中午段（11-13 → 中午好，#222）|
| 2026-08-12 | v3.12 | **REVIEW #214/#215/#221**：`POST /records/media/{id}/ask` 的 `question` 加长度上界（500 字符，超限 400）；`GET /accounts/available` 响应由账号对象改为 **userId 最小集**（`List<String>`，不暴露 role/enabled/createdAt）；Brief 降级问候 emoji 按时段（#221） |
| 2026-08-12 | v3.11 | **AI 日志隐私治理（REVIEW #210）**：`GET /admin/ai-logs` 新增 `page`/`size`（上限 500，响应带 `total`）；`date` 早于保留期（`adai.ai-log.retention-days` 默认 30 天）返回 400（已清理不可查）|
| 2026-08-12 | v3.10 | **R1 AI 交互日志契约登记**：新增 §17 `GET /admin/ai-logs?userId=&date=`（X-Admin-Token 鉴权，读 `data/{userId}/ai-logs/YYYY/MM/ai-log-{date}.jsonl`——2026-09-02 #178 后退役并入统一登录）；图片追问持久化（`POST /records/media/{id}/ask` 追问 Q/A 追加进图片卡 card 文件，Feed 图片记录 entry 带 turns）|
| 2026-08-11 | v3.9 | **图片追问（L4 图片问答）**：新增 `POST /records/media/{id}/ask`（图+问题 → GLM 自然语言回答 → 沉淀 `image_qa` 记录）；管理端点 CORS 预检修复（`OPTIONS` 放行，8082/8083 可正常访问 admin/accounts）|
| 2026-08-09 | v3.8 | **多账号前端选号 + 契约对齐**：新增 §16 `GET /accounts/available`（无鉴权选号）/ portfolio `positionCount` 派生字段（#106）/ Feed 分页 page0 完整核心 + 卡片时间基准 `updatedAt`（#175）/ `X-User-Id` 默认说明更新（v1.0.0 起前端必须携带所选账号）|
| 2026-08-06 | v3.7 | **行情异动主动推送（Phase 2）**：FeedEntry 新增 `type=push`（止损预警/放飞提示/跌破成本线/真止损 R66（现价跌破止损位，2026-08-16），`MarketAlertService` 交易时段轮询落盘 `data/{userId}/trading/pushes/{date}.json`，阈值可配 `adai.market.alert.*`）|
| 2026-08-06 | v3.6 | **管理端点鉴权（REVIEW #127）**：§账号、§管理端全部端点要求 `X-Admin-Token` 请求头（配置 `ADAI_ADMIN_TOKEN`，缺失 401 / 未配置 503 fail-closed——2026-09-02 #178 后退役并入统一登录）；CORS 由 `*` 收窄为配置化 origin 白名单（默认 localhost）|
| 2026-08-02 | v3.5 | **多模态图片记录（L4）**：新增 `POST /records/media`（multipart 上传 → GLM 视觉理解 → 记录+记忆）、`GET /records/media/{id}`（原图预览）|
| 2026-08-02 | v3.4 | **多账号功能层 + adai-admin**：新增 §账号（accounts CRUD）、§管理端（admin 文件树/知识浏览）；Memory 新增 `PATCH /memory/{id}` 手动修正 |
| 2026-08-02 | v3.3 | **多账号架构预留**：全 API 支持可选请求头 `X-User-Id`（默认 `default`），数据按用户分层 `data/{userId}/` |
| 2026-08-02 | v3.2 | **记忆进化 Phase 3**：新增 `PATCH /memory/{id}/done`（actionable 闭环完成标记）；Memory 条目新增 kind/topic/superseded/evolvedTo/doneAt 字段 |
| 2026-08-16 | v3.21 | **P-be-01 安全修复**：5 个维护端点（records/retry、memory/rebuild、memory/{id} PATCH、cards/cleanup、knowledge/conflicts）从 per-user 路径迁入 `/api/v1/admin/**`（需 X-Admin-Token——2026-09-02 #178 后退役，改登录 + role=admin），目标用户改 userId 查询参数；`GET /trading/has-activity` 保留产品路径（app 复盘横幅，只读）|
| 2026-08-01 | v3.1 | **补全缺失端点**：`DELETE /records/{id}`、`POST /records/retry`、`GET /memory/dates`、`GET /memory/count`、`GET /trading/positions`、`GET /trading/portfolio`、`POST /trading/trades`；§5 改为"交易" |
| 2026-07-31 | v3.0 | **行情数据注入**：ContextEngine 注入大盘指数+持仓实时行情；修复 CHAT 模式未注入上下文 Bug（市场/知识/记忆丢失） |
| 2026-07-29 | v2.9 | **Feed 分页方向修复**：page 0 从最早条目改为最新条目，优化刷新后新数据可见性 |
| 2026-07-29 | v2.8 | **Feed 分页**：`GET /api/v1/feed` 加 `page`/`size` 参数，移除 `brief`/`earlierCount`，新增 `totalToday`；Brief 独立接口 |
| 2026-07-27 | v2.6 | **移除 DECISION 意图**；意图识别改为纯 AI（无正则兜底，AI 失败抛异常）；新增 `POST /api/v1/cards/cleanup` |
| 2026-07-26 | v2.5 | 任务系统 (5 个 API) + RFC tracking，ProjectStatus.rfcCount→rfcItems[]，前端任务页 |
| 2026-07-25 | v2.3 | 新增交易复盘 API（生成/查询/列表），知识反哺 API（promote/conflicts），简报集成交易检测 |
| 2026-07-25 | v2.2 | 新增 DECISION 意图，Knowledge 集成到 Context Engine |
| 2026-07-24 | v2.1 | Feed 新增 `type: "card"` 带 turns，卡片文件隔离到 `records/cards/`，新增迁移 API |

---

## 0. 通用请求头（多账号预留）

> **v3.40（2026-09-01）登录体系（RFC 20260901-auth-login）**：产品端点鉴权从「信任 X-User-Id」升级为「会话 Bearer token」。`X-User-Id` 头仍保留（Controller 兼容读取），但**后端 AuthFilter 强制覆盖为会话 userId**——客户端伪造无效；无有效 `Authorization: Bearer <token>` 一律 401（fail-closed）。

| Header | 类型 | 必填 | 默认 | 说明 |
|:-------|:-----|:----:|:----:|:-----|
| `Authorization` | String | 是（登录后） | — | `Bearer <token>`，登录签发（32 字节 hex，落盘仅存 SHA-256 哈希）；30 天滑动续期；登出/改密即失效 |
| `X-User-Id` | String | 否 | — | 用户标识（兼容保留）；**后端以会话为准强制覆盖**——user 会话传什么都被忽略；**role=admin 会话保留客户端值**（管理口跨账号治理浏览，REVIEW #178）|

**约束**：`userId` 仅允许 `[a-zA-Z0-9_-]+`（后端校验，防路径注入）。不合法的 userId 返回 400。

**免鉴权路径**（仅此 3 类）：`POST /api/v1/auth/login`、`POST /api/v1/auth/setup`（首访一次性）、`OPTIONS`（CORS 预检）。其余全部要求 `Authorization: Bearer <token>`（v3.40 登录体系）。管理口 `/api/v1/admin/**` 与 `/api/v1/accounts/**` 需登录且会话账号 **role=admin**（REVIEW #178，2026-09-02：并入统一登录，X-Admin-Token 退役；非 admin → 403「仅管理员账号可访问」）；`GET /api/v1/accounts/available` 仅需登录（产品端遗留选号）。

---

## 0.1 认证（Auth，RFC 20260901-auth-login）

### `POST /api/v1/auth/login` — 登录（免鉴权）

请求：`{"account": "adai", "password": "...", "device": {"name": "iPhone 15", "platform": "ios", "appVersion": "3.66.0"}}`
（`device` 可省略。**属客户端自述，服务端不据此做任何安全判定**——只用于「登录设备」列表辨认；字段超长截断、全空记 null）

响应 200：
```json
{"token": "3ce1...8f", "userId": "adai", "role": "admin", "plugins": ["trading"], "expiresAt": "2026-10-01T15:53:54Z", "sessionId": "3ce1a9f2"}
```
- 401：账号或密码错误 / 账号未设密码（提示先 setup）/ 连续 5 次失败限流（15 分钟锁，按 IP+账号）
- token 明文只在此响应出现一次；落盘 `data/accounts/sessions.json` 仅存 SHA-256 哈希
- `sessionId` = 本次会话短标识（token 哈希前 8 位）——前端据此判断「这台是不是当前设备」

### `POST /api/v1/auth/logout` — 登出（会话）

无请求体；删除当前 token 会话。幂等。

### `GET /api/v1/auth/sessions` — 列出登录着的设备（会话，2026-09-14 登录体验批）

- 200：`{"sessions": [{"id": "3ce1a9f2", "device": {"name": "iPhone 15", "platform": "ios", "appVersion": "3.66.0"}, "createdAt": "...", "lastSeenAt": "...", "expiresAt": "...", "current": true}]}`
  —— 按最近活跃倒序；只列**未过期**会话；`device` 为 null = 老会话或客户端未上报；`current` 标记当前请求所用的会话
- 401：会话失效/未登录

### `DELETE /api/v1/auth/sessions/{idOrPrefix}` — 撤销一台设备的登录（会话，2026-09-14 登录体验批）

路径参数为 `id`（推荐，即列表里的值）或其前缀。
- 200：`{"message": "已撤销，这台设备需要重新登录"}`（该设备立即失效；不影响其它设备）
- 400：前缀在本账号内命中**多台**（「这个标识对应多台设备，请用完整的设备 id」）／撤销目标是**当前设备**（提示用「退出登录」）
- 404：没找到（可能已经退出过了）
- 401：会话失效

### `GET /api/v1/auth/me` — 当前会话信息（会话）

响应 200：`{"userId": "adai", "role": "admin", "enabled": true, "plugins": [...]}`
- 401：会话失效/未登录（前端据此清 token 回登录页）

### `POST /api/v1/auth/setup` — 首访一次性设密码（免鉴权）

请求：`{"account": "admin", "password": "至少8位"}`（未传 `account` 默认内置管理员 `admin`；也可为任意已存在账号设密码，如产品主账号 `adai`）
- 200：设置成功（仅当**全系统无任何账号设过密码**时可用；此后 404「系统已完成初始化」）
- 401：账号不存在 / 密码太短

### `POST /api/v1/auth/password` — 改密（会话）

请求：`{"oldPassword": "...", "newPassword": "至少8位"}`
- 200：`{"message": "密码已更新", "kickedSessions": N}`（踢除该账号其他会话，保留当前）
- 401：原密码错误 / 会话失效

### `POST /api/v1/auth/tokens` — 签发外部工具令牌（会话，2026-09-13 外部入口批）

给「我们控制不了凭据存放处」的工具（快捷指令等）发一把**限权、可撤销**的钥匙。
**不要**把登录会话交给它们——那是明文写在 plist 里、且 `.shortcut` 文件会被分享出去的东西。

请求：`{"label": "快捷指令", "scopes": ["learn:digest"]}`（`label` 可空 → 「未命名」；`scopes` 至少要有一项，未知 id 被丢弃）
- 200：`{"token": "adai_<64位hex>", "id": "<64位hex 哈希>", "prefix": "adai_xxxxxxxx", "label", "scopes", "createdAt", "expiresAt", "notice"}`
  —— **`token` 明文只在这里出现一次**（落盘只存 SHA-256），丢了就撤销重发一把；
  `expiresAt` = 有效期（**默认 90 天**，2026-09-14 晚间批），`id` = 撤销用的稳定标识
- 400：没给任何有效 scope（「至少要给它一项权限」）

### `GET /api/v1/auth/tokens` — 列出已签发的外部令牌（会话）

- 200：`{"tokens": [{id, prefix, label, scopes, createdAt, lastUsedAt, expiresAt}], "availableScopes": [{id, description, allowedRequests}]}`
  —— **不含任何明文**；`lastUsedAt` 为 null 表示「还没用过」；`expiresAt` 为 null 表示**不过期**（仅存量老令牌）

### `POST /api/v1/auth/tokens/{idOrPrefix}/rotate` — 轮换令牌（v3.71）

> REVIEW S-凭据1 剩余项（2026-09-17）：钥匙用久了 / 怀疑外泄时，用户要的是「换一把」，而不是「先撤旧的再签发新的」——后者中间有空窗，且撤完忘了签发就直接断链（快捷指令失效）。

- **一次完成**：先签发新令牌（继承旧令牌的 `label` 与 `scopes`，有效期重新算 90 天）→ 再撤销旧令牌。**旧的撤不掉就把新的也回滚**，绝不留两把同时有效（分不清哪把外泄）
- **路径参数**：令牌 `id`（哈希，推荐）或显示前缀；**前缀非唯一命中 → 404**（与撤销同口径，防一次换两把）
- `200` — 与 `POST /auth/tokens` 同形状：`{token, id, prefix, label, scopes, createdAt, expiresAt, notice}`（`token` 明文**只出现这一次**）
- `404` — 没找到这把令牌（可能已撤销）

### `DELETE /api/v1/auth/tokens/{idOrPrefix}` — 撤销一把外部令牌（会话）

- 路径参数：`id`（令牌哈希，**推荐**，2026-09-14 晚间批）或显示前缀（兼容旧版 app）
- 200：`{"message": "已撤销，这把令牌立刻失效"}`（不影响登录会话与其它设备）
- 404：没找到 / **前缀非唯一命中被拒**（同账号两把令牌前缀碰撞时不再一次删两把——旧行为会静默误撤）

> **外部令牌能访问什么**：由 `TokenScope` 白名单**精确匹配**决定，当前仅 `learn:digest`
> （`POST /api/v1/learn/digest`、`POST /api/v1/learn/digest/confirm`、
> `GET /api/v1/learn/digest/status`、`GET /api/v1/learn/digest/quota`）。
> ⚠️ **任何 scope 都不含 `/api/v1/auth/**`**——外部令牌无法自造一把权限更大的钥匙；
> 上面三条令牌管理端点只接受**会话**鉴权。

---

## 1. 记录（Records）

### `POST /api/v1/records` — 提交记录

单一入口。所有用户输入统一走此接口，后端自动分流。
支持会话卡片：当已有活跃聊天时，`cardId` 传当前卡片 ID，新输入作为对话延续。

**Request Body**

```json
{
  "content": "今天买了立昂微",      // required, 1-10000 字符
  "type": "note",                 // optional, 默认 "note"
  "tags": ["投资", "半导体"],       // optional
  "intent": null,                 // optional: "log" | "question" | null
                                  // null = 后端 AI 自动判断
  "cardId": null,                 // optional: 会话卡片 ID，有值则视为对话延续
  "source": null                  // optional: "external_entry" = Siri/快捷指令/adai:// 入口
                                  // 缺省或认不出的值一律落 user_input（**不报错**——标记错了也不拒收记录）
}
```

> **`source`（v3.77，2026-09-17 B4 批，P1-安全1 剩余项）**：只做**来源标记**，不改变任何展示与消费行为。
> 取值白名单：`user_input`（用户在输入框打的，缺省；存量记录都是这个）/ `external_entry`
> （外部入口：Siri「记一笔」、快捷指令、`adai://record`）。外部入口本身无凭据，留这道痕是为了
> 事后能回答「这条内容是被谁塞进来的」。

**Response — 陈述句（intent="log"）**

```json
{
  "intent": "log",
  "recordId": "rec_20260718_143000",
  "content": "今天买了立昂微",
  "tags": ["投资", "半导体"],
  "summary": "建仓了半导体"
}
```

前端行为：→ 展示记录卡片（内容 + 标签 + 底部 `── ask ──`）

**Response — 疑问句（intent="question"）**

```json
{
  "intent": "question",
  "recordId": "rec_20260718_143100",
  "summary": "今天多云转晴，20-28℃…",
  "tags": ["天气", "日常"],
  "rawResponse": "今天多云转晴，20-28℃，适合出行…"
}
```

前端行为：→ 展示聊天卡片，激活会话模式

> `rawResponse` 为 AI 自然语言回复，**已剥离 JSON 元数据**（2026-08-07，#13/#11：实时显示与刷新后一致，card 文件不混入游离 JSON）；自然语言为空时回退 `summary`。

**意图识别逻辑**

```
1. 前端指定 intent → 直接使用 — 支持 "log" / "question"
2. AI 识别意图（ask → QUESTION，其余 → STATEMENT）
3. AI 失败 → 抛异常，不静默降级
```

**domain 判定规则（AI 输出，RFC 20260814 D5）**

按优先级匹配关键词，**只在用户已启用插件间判定**（无对应插件 → 该关键词不判该域）：
- 指标、K线、持仓、走势、复盘、买入、卖出、仓位、股票、大盘、行情、买卖 → `trading`（需启用 trading 插件）
- 日常、想法、记录、心情、问题 → `life`

> 无插件用户一律 `life`（单一 domain）。即使 AI 输出 `trading` 或已撤除的 `project`，若该用户未启用对应插件，后端也会收敛为 `life`（`PluginService.gateDomain` 白名单只放行 `trading` + `life`，`project` 等未知值一律收敛 `life`）。插件名见 §16 `GET /me/plugins`。

---

### `POST /api/v1/records/ask-stream` — 流式问答（SSE，v3.34）

问答专用流式端点（`text/event-stream;charset=UTF-8`）。**仅问答**：显式 `intent: "question"` 或 `cardId` 续聊；自动意图的新输入仍走 `POST /records`（批次 3 再分流）。

**Request Body**

```json
{
  "content": "怎么玩铜？",          // required, 1-10000 字符，blank → 400
  "intent": "question",           // optional: "question"（首问显式指定；续问可省略，cardId 即问答语义）
  "cardId": "card_123"            // optional: 会话卡片 ID，有值则续聊（后端 ensureCardWithUserTurn 建卡/补用户轮次）
}
```

**Response — SSE 事件流**（`data:` 行，事件间空行分隔）

| 事件 | 载荷 | 说明 |
|:----|:----|:------|
| `text` | `{"type":"text","content":"…"}` | 正文增量（JSON 回执已由后端剥离，直接可显示）；多段顺序拼接 = 完整回答 |
| `meta` | `{"type":"meta","recordId","summary","tags","domain","content"}` | 定稿事件：`content`=剥离 JSON 后的最终正文（权威，前端以它替换草稿）；后端副作用（建卡/AI 轮次/记忆）已完成 |
| `[DONE]` | `data: [DONE]` | 流结束哨兵 |
| `error` | `{"type":"error","message":"…"}` | 失败事件（人话），随后必跟 `[DONE]`；客户端已收增量 → 保留草稿可重试，未收增量 → 自动降级旧同步端点一次 |

**后端内降级（ai-calling-governance §⑤）**：模型整轮无增量输出（只回了 JSON 回执）→ 后端回退同步 `understand` 一次再吐 `text`/`meta`——前端无感知。**同卡同问去重（S-9）**：同 cardId + 相同末条用户轮次 + 卡片 5 分钟内更新过 → 直接流式回放既有回答（`text`×1 + `meta`，不调 AI、不重复 append 轮次）。

**错误**

- `400` — content 空白
- `503` — 流式线程池满（`RejectedExecutionException`）
- 编码：事件 JSON 以 UTF-8 字节发送（`StringHttpMessageConverter` 默认 ISO-8859-1 会把中文变 `?`；byte[] 走 `ByteArrayHttpMessageConverter` 原样透传，MockMvc 与生产容器一致）

**前端行为（双端一致）**：`SseClient` 边到边读（IO：`http.Client.send` 流式；Web：fetch ReadableStream——dart http 浏览器实现无渐进响应）；草稿 90ms 节流 setState；`meta` 到达后以 `content` 定稿并刷新 feed/标签/记忆缓存；未收任何增量即失败 → 自动降级 `POST /records`（intent 与原调用同构）一次。

---

### `DELETE /api/v1/records/{id}` — 删除记录

同时清理两个仓库（`rec_` 文件可能在 `records/` 或 `cards/` 目录）+ 关联 Memory。

**Response**

- `204 No Content` — 删除成功（清理 record + card + memory 关联）
- `404` — 不存在也返回 204（幂等）

### `PATCH /api/v1/records/{id}/domain` — 修改记录所属领域

**Request Body**

```json
{ "domain": "trading" }
```

**Response**

- `204 No Content` — 修改成功
- `400` — domain 非法（仅 `life` / `trading`；RFC 20260917 起 `project` 已撤除）

### `POST /api/v1/admin/records/retry` — 手动触发重补（需登录 + role=admin，REVIEW #178）

调用 `RecordRetryService`，为没有 Memory 的历史记录补齐 AI 摘要与标签。

**Response**

```json
{
  "status": "ok",
  "memoriesBefore": 2,
  "memoriesAfter": 15,
  "newMemories": 13
}
```

### `POST /api/v1/records/media` — 上传图片记录（多模态 L4）

`multipart/form-data`：图片 → 存原图（`records/{yyyy}/{MM}/media/{id}.{ext}`）→ GLM 视觉模型理解 → 沉淀 ContentRecord（type=image）+ Memory。

**Request（multipart）**

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `file` | 二进制 | ✅ | 图片（jpeg/png/webp/gif，≤5MB）|
| `caption` | String | 否 | 用户备注（VLM 理解失败时降级为记录内容）|
| Header `X-User-Id` | String | 否 | 用户 ID（默认 `default`）|

**Response 200**

```json
{
  "recordId": "rec_20260802_143200123",
  "intent": "log",
  "summary": "持仓截图：浦发银行",
  "tags": ["交易", "持仓"],
  "mediaPath": "records/2026/08/media/rec_20260802_143200123.png"
}
```

- `400` — 非图片（非 jpeg/png/webp/gif）
- `413` — 超过大小上限（`spring.servlet.multipart.max-file-size`，默认 5MB；REVIEW #166/#238 由 400 拆分，v3.14 同步）

### `GET /api/v1/records/media/{id}` — 取回原图（预览）

**Response 200** — 图片字节流（Content-Type 按扩展名：jpeg/png/webp/gif）

- `404` — 无此媒体文件

### `POST /api/v1/records/media/{id}/ask` — 图片追问（L4 图片问答）

就一张已记录的图片提问：重新取原图字节 → GLM 视觉模型自然语言回答 → 沉淀 `image_qa` 记录（时间线/搜索可见，content 含图片记录 ID 溯源）。前端图片卡底部 `── 提问 ──` 进入追问。

**Request Body**

```json
{ "question": "这是什么股票？" }
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `question` | String | ✅ | 用户对图片的追问（非空）|
| Header `X-User-Id` | String | 否 | 用户 ID（默认 `default`）|

**Response 200**

```json
{
  "recordId": "rec_20260811_143200456",
  "answer": "这是浦发银行，持仓约 1000 股。",
  "imageRecordId": "rec_20260811_091500123"
}
```

- `400` — 问题为空 / 问题超过 500 字符（REVIEW #214，防超大 prompt/记录/日志行）/ 图片记录不存在 / 图片文件缺失

### `POST /api/v1/records/media/ask-batch` — 多图问答（Phase 1 带图 ask，2026-08-14）

对已上传的 1-3 张图片一次提问：VLM 综合多图回答（一次请求看全部图）→ 沉淀 `image_qa` 记录（content 引用全部图片 ID）+ Q/A 追加到首图卡 card 文件（Feed 刷新后首图卡显示问答气泡）。前端输入栏附图 + 文本，逐张上传完成后调用。

**intent 分流（与文本记录「入口统一，后台分流」一致）**：Controller 用 `IntentRecognizer` 判定附带的文本——问句（`question`）→ VLM 多图回答；陈述（`log`）→ 图片已在逐张上传时以 caption 记录，直接返回不调 VLM。AI 判定失败降级问号启发式（文本以 ？/? 结尾）。

**Request Body**

```json
{ "imageRecordIds": ["rec_..", "rec_.."], "question": "这两张图分别是什么？" }
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `imageRecordIds` | String[] | ✅ | 已上传的图片记录 ID（1-3 张，上限 Phase 1 拍板）|
| `question` | String | ✅ | 附图文本（后端按此判定 question/log 分流）|
| Header `X-User-Id` | String | 否 | 用户 ID（默认 `default`）|

**Response 200（question 分支）**

```json
{
  "intent": "question",
  "answer": "左图是持仓截图，右图是分时走势。",
  "recordId": "rec_20260814_143200456",
  "imageRecordIds": ["rec_..", "rec_.."]
}
```

**Response 200（log 分支）**

```json
{ "intent": "log", "imageRecordIds": ["rec_..", "rec_.."] }
```

- `400` — 图片为空 / 超过 3 张 / 问题为空 / 问题超过 500 字符 / 图片记录不存在
- 注：多图问答 `image_qa` 记录 content 格式 `【多图问答】图片记录：a, b … / 问：… / 答：…`（单图追问为 `【图片问答】`）

### `POST /api/v1/records/media/batch` — 一次投递多图（图文一体，v3.82，2026-09-22）

**一次投递（N 张图 + 可选一句话）= 一个回合 = 一条主记录 = Feed 一张卡**（RFC `20260815-media-event-unification` 数据层 + `20260815-image-chat-interaction` 交互；用户 2026-09-22 拍板 **A 方案**：先识别多图组成上下文，无提问给一段综合总结、有提问据图作答，图并列同卡）。

**Request（multipart）**

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `files` | File[] | ✅ | 图片文件（1-3 张，单张 ≤5MB，仅 `image/*`）|
| `text` | String | 否 | 用户随图发的那句话；空 = 纯图 |
| Header `Idempotency-Key` | String | 否 | **一次投递的唯一键**（前端生成、重试复用）。同键重发命中幂等 → 返回首次结果且 `duplicated=true`，不重跑 AI、不重复落盘 |
| Header `X-User-Id` | String | 否 | 用户 ID（默认 `default`）|

**落盘（File First）**：N 张原图各落 `records/YYYY/MM/media/{attachmentId}.{ext}` + 一条**薄 image 记录**（`summary=图片附件`，仅作原图索引；不做 VLM、不沉淀记忆、不单独进 Feed）；主记录一条，frontmatter `mediaIds: [id1, id2]` 引用全部附件（freeze §2.1 MINOR 变更）。

**Response 200**

```json
{
  "recordId": "rec_20260922_204109985",
  "mediaIds": ["rec_20260922_204109981", "rec_20260922_204109982"],
  "type": "image",
  "intent": "log",
  "summary": "群里在聊篮球夺冠，还提到你不在所以没打",
  "answer": null,
  "tags": ["群聊", "篮球"],
  "domain": "life",
  "duplicated": false
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `recordId` | String | 主记录 ID（同时是卡片 ID，追问挂它）|
| `mediaIds` | String[] | 附件（薄 image 记录）ID，**按上传顺序**；原图走 `GET /records/media/{mediaId}` |
| `type` | String | `image`（无提问/陈述 → 已给一段综合总结）/ `image_qa`（问句 → 已据图作答）|
| `intent` | String | `log` / `question`（Controller 用 `IntentRecognizer` 判定，失败降级问号启发式）|
| `summary` | String | 无提问：一段**综合**总结；有提问：回答摘要 |
| `answer` | String? | 有提问时的完整回答；无提问为 `null` |
| `duplicated` | boolean | `true` = 命中 `Idempotency-Key`，本次返回的是**首次**结果（未重复识别、未重复落盘）|

- `400` — 图片为空 / 超过 3 张 / 非图片 / 单张超过 5MB / 问题超过 500 字符
- AI 失败不丢数据：识别失败降级为「用户备注 / 图片记录」兜底 summary，原图与主记录照常落盘
- 旧单图端点 `POST /records/media` 保留（旧客户端与逐张链路不受影响）；`GET /records/media/{id}` 与 `ask-batch` 语义不变（薄附件仍是普通 image 记录，原图/追问链路零改动）

---

## 2. 对话总结（Conversations）

### `POST /api/v1/conversations/end` — 结束对话

**Request Body**

```json
{
  "turns": ["用户说", "AI答", "用户再说"],
  "cardId": "card_143000"
}
```

**Response**

```json
{
  "recordId": "rec_20260718_143200",
  "summary": "讨论了天气，建议带伞出门",
  "tags": ["天气", "出行"]
}
```

---

## 3. Feed 流

### `GET /api/v1/feed` — 获取今日 Feed（分页）

**Query Parameters**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|:----:|:----:|------|
| `date` | String | 否 | 当天 | 日期 `yyyy-MM-dd` |
| `page` | int | 否 | 0 | 页码，从 0 开始，page 0 = 最新条目（REVIEW #175：返回完整 `size` 条核心，余数放末页）|
| `size` | int | 否 | 5 | 每页条数 |

**Response**

```json
{
  "entries": [
    {
      "type": "card",
      "id": "card_1784902336974",
      "time": "22:12",
      "date": "08-03",
      "mediaPath": null,
      "title": "现在饿了，吃点什么呢",
      "content": "现在饿了，吃点什么呢",
      "tags": [],
      "intent": "question",
      "summary": "饿了推荐了夜宵选择",
      "turns": [
        {"isUser": true,  "text": "现在饿了，吃点什么呢", "time": "22:12"},
        {"isUser": false, "text": "哈哈饿了呀，那得看你想吃啥", "time": "22:12"}
      ]
    },
    {
      "type": "record",
      "id": "rec_...",
      "time": "14:30",
      "date": "08-03",
      "mediaPath": "records/2026/08/media/rec_20260803_143200123.jpg",
      "title": "标题",
      "content": "内容",
      "tags": ["标签"],
      "intent": "log",
      "summary": "AI摘要",
      "domain": "life",
      "turns": null
    }
  ],
  "hasHistory": true,
  "totalToday": 28
}
```

> feed 只返回今天的数据，历史数据走时间线（`GET /api/v1/timeline`）。
> 每日摘要单独调用 `GET /api/v1/brief`。
> **时间基准（updatedAt）**：卡片（`type=card`）的 `time`/`date` 按最后更新时间 `updatedAt`，跨日续接的对话归最后活跃日；`findTodayCards` 按 `updatedAt` 过滤。分页（REVIEW #175）：核心条目（record/card）按时间从新到旧切块，page 0 返回完整 `size` 条最新核心，余数放末页；附加条目（ai_note/action/market/push）只在 page 0 出现。
> **排序（v3.69，2026-09-16，REVIEW P1-前端2）**：`time` 只有 `HH:mm`，同分钟多条排序键完全相等 → 统一用 **`time` + `id`（含毫秒）双键升序**；且**附加条目与核心条目合并进同一条时间轴**再输出。原先附加条目是直接追加在末尾的，导致 10:03 的 `ai_note` 排到 21:30 的 `record` 之后——用户看到的就是「主页卡片乱序」。
> **同分钟同向成交折叠（v3.69，2026-09-16，REVIEW P2-UI12）**：`type=record && domain=trading` 且标题以「买入 / 卖出」开头、且落在**同一分钟、同一方向**的多条，折叠为一条（标题「买入 N 笔」，正文逐笔保留），并带 `mergedIds` 列出被折叠的原始 id。**账目真相源（trades / account / positions）与写侧记录都不动**——这是纯展示层折叠。
> **插件门控（RFC 20260814）**：`market`（行情条）与 `push`（异动推送）条目仅注入启用 **trading 插件** 的用户；无插件用户 Feed 无行情卡。
> **空态分流（v3.78，2026-09-18，REVIEW P1-UI14 复发修复）**：`totalToday=0` 时**不能**据此认定「新用户」——老用户当天恰好没记录也是 0。前端必须用 `hasHistory` 分流（见字段表）：`false` 才播新账号的能力引导三问，`true` 走「接着上次的聊也行，我记着」。旧后端无该字段 → 按 `false` 降级（= 改动前行为）。

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `type` | String | `record` / `card` / `ai_note` / `action`（未完成行动提醒，Phase 3）/ `market`（大盘行情，v0.2.0）/ `push`（行情异动主动推送，Phase 2：止损预警/放飞提示/跌破成本线/真止损 R66（2026-08-16））|
| `time` | String | `HH:mm` 格式（后端已格式化，无小数秒），卡片取首条用户消息时间 |
| `date` | String | `MM-dd` 格式，条目所属日期（每张卡片都带日期，前端展示）|
| `mediaPath` | String? | 媒体记录才有：`type=image`（图片记录原图）与 `type=image_qa`（S-2 展示层聚合：图文事件缩略图取引用首图）——媒体文件相对路径（GET `/api/v1/records/media/{id}` 取文件）；其余类型为 `null` |
| `mediaPaths` | String[]? | v3.82（2026-09-22，图文一体）：本条卡片引用的**全部**图（按上传顺序；一次投递多图时长度 > 1）→ 前端**一卡并列**展示。无图为 `null`。`mediaPath` 恒为首图，**旧前端不读新字段也能正常显示首图**（向后兼容）|
| `turns` | TurnDto[] | 仅 `type=card` 时有值，卡片对话轮次 |
| `mergedIds` | String[]? | v3.69（2026-09-16，P2-UI12）：本条是由哪几条原始记录折叠而来（同分钟同向成交）；未折叠的条目为 `null`。**前端删除时必须逐条删全**，否则刷新后折叠卡会带着剩下的记录回来 |
| `domain` | String | `life` / `trading` — AI 按关键词规则判定（RFC 20260917 起 `project` 已撤除，未知值收敛 `life`）|
| `totalToday` | int | **核心输入条数**（record/card，不含 ai_note/action/market/push 附加）；分页终止基准 |
| `hasHistory` | boolean | **该用户是否有过任何历史记录**（不限当天；v3.78，2026-09-18，REVIEW P1-UI14 复发修复）。空态分流判据：`false` = 真·新账号（空态播能力引导三问）；`true` = 老用户今天恰好还没记录（空态改「接着上次的聊也行，我记着」，不摆三问）。**旧后端不返回该字段 → 前端按 `false` 降级**。判据固定在服务端：前端本地标记重装/换设备即丢，正是本案场景 |

---

## 4. 卡片管理（Cards）

### `POST /api/v1/cards/migrate` — 迁移历史卡片文件

将旧路径 `records/YYYY/MM/DD/xxx.md` 的卡片迁移到 `records/cards/` 子目录。

**Response**

```json
{
  "totalScanned": 25,
  "migrated": 25,
  "failed": 0,
  "migratedFiles": ["旧路径 → 新路径"]
}
```

### `POST /api/v1/admin/cards/cleanup` — 清理卡片冗余记录（需登录 + role=admin，REVIEW #178）

删除卡片对话对应的冗余 ContentRecord（卡片内容已存储在 `records/cards/` 下，无需单独保留）。

**Response**

```json
{
  "deleted": 15
}
```

---

## 5. 交易

### `GET /api/v1/trading/positions` — 查询持仓
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。
>
> **Response**：`Position[]`，每项含双止损位字段（2026-08-31，trading-risk-plan）：
> - `stopLossPrice`：**人工止损位**（最近 BUY 值 / web 编辑，落盘 positions.md；可空）
> - `computedStopLossPrice`：**系统计算止损位**（风险预算公式动态算、不落盘：R=本金×1%，止损距离=min(R÷单仓市值, 5%)，止损价=成本×(1−距离)；本金缺失/异常输入 → null）
> - `effectiveStopLoss`：**生效止损位 = max(人工, 计算)**（取更严格，R66 判定/接近止损预警/建议引擎统一用本值；两者皆空 → null）
> - `pnlPercent`：**浮动盈亏%——成本价 ≤ 0 时为 `null`**（2026-09-13 负成本批）。负/零成本下「(现价−成本)/成本」语义翻转（实测 600601 成本 −5.078 会算出 **−392%**，而券商口径 +134%），故后端不下发数字。**消费端必须把 null 渲染成「—」而不是 0%**——「0%」会被读成「不赚不亏」。盈亏**金额**（`pnl`）不受影响，与券商一致（市值 − 成本额）。前端 adai-web / adai-app 均已按此实现（`double?` + 「—」）

### `GET /api/v1/trading/portfolio` — 查询投资组合快照
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。

### `GET /api/v1/trading/positions/daily` — 持仓列表视图（逐股当日口径，2026-09-14）
> 需 trading 插件（403）。
>
> **为什么单独一个端点**：`/positions` 返回 `Position[]`，app / web / admin 三处都在消费那个形状
> ——改成对象属破坏性变更。本端点把「逐股当日口径」放在**平行 map**（key = symbol），
> 前端原有解析原样可用，只多读一个 map。
>
> **Response**：
> - `positions`：`Position[]`，与原 `/positions` 元素同形状（含双止损位、`pnlPercent`）
> - `daily`：`{symbol: {...}}`，每项
>   - `todayPnl`：该股**当日盈亏**（**券商口径** = 今天真实赚亏）。**可空** = 缺昨收，该股今日成交未计入
>   - `yesterdayClose`：昨收（可空）
>   - `dayChangePct`：**今日涨跌幅 %** = (现价−昨收)/昨收（可空）
>   - `positionRatio`：该股市值 **占总资产（含现金）的 %**（可空 = 总资产为 0）
> - `totalPositionRatio` / `cashRatio`：**总仓位 %** / 现金比例 %（可空，**不得渲染成 0%**）
> - `notes`：未计入项的人话说明（非空 ⇒ 当日盈亏偏小；`refreshTodayPnl` 见实质缺失会拒绝写回）
>
> **口径变更（2026-09-14 用户拍板 A）**：当日盈亏「卖出部分」的基准由**建仓成本**改为**昨收**
> ——原口径算的是「这笔交易从建仓到现在赚了多少」，会把历史浮盈记进今天（实测云南锗业
> 卖 100 股虚增 3466 元，系统当日盈亏 5826 vs 券商 2245）。现与券商 App 的「当日参考盈亏」同口径。
> 配套：行情查询的符号集合改为「当前持仓 ∪ 今日有成交的票」——今日卖光的票不在持仓里，
> 但它的昨收是算这笔卖出当日盈亏的唯一基准。

### `POST /api/v1/trading/trades` — 记录一笔交易

**Request Body**

```json
{
  "symbol": "600123",
  "name": "立昂微",
  "direction": "BUY",
  "price": 25.30,
  "volume": 100,
  "entryDate": "2026-08-16",
  "tradeTime": "09:41:05",
  "stopLossPrice": 24.50,
  "buyPoint": "B1",
  "targetPrice": 30.00,
  "reason": "回踩支撑买入"
}
```

> `name` **可选**（≤32 字符，RFC 20260815）：缺省时后端以 symbol 兜底。`direction` 必填（BUY/SELL），`price`/`volume` 必须 > 0（`@Positive`）。
> **RFC 20260816（数据分层）**：`entryDate` 可空缺省今天；`targetPrice`/`reason` 可选（SELL 时止损/买点可空）。
> **2026-08-18（确认批次）**：`stopLossPrice`/`buyPoint` 由 BUY 必填改为**可选**——app 简化为纯买卖记录（标的/价格/数量/方向），止损位/买点归 web 端（记录对话框 / CSV 批量导入仍填；app 记录的持仓止损缺失 → 建议引擎纪律判定降级，web 持仓编辑补设后恢复）。
> **2026-08-22（RFC 20260822）**：`tradeTime`（成交时刻 `HH:mm:ss`）可选——缺省 = 落盘时刻时分（客观数据，供当日复盘时段分布）。
> recordTrade 成功后**同步写逐笔流水**（`data/{userId}/trading/trades/{yyyy-MM}.json`）+ **写一条 domain=trading 记录**（5 分钟窗口去重；**仅当日成交**——历史成交导入不写记录，防刷屏；与 `status.md` v3.52 口径一致，2026-09-17 B1 批补此限定）——交易进 timeline/记忆 + 复盘提醒闭环。

**Response**：`Position[]` — 更新后的全部持仓。需 trading 插件（403）。

---

### `GET /api/v1/trading/trades` — 查询交易逐笔流水（RFC 20260816）
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。

### `PUT /api/v1/trading/trades/{tradeId}/meta` — 流水补成交编号/手续费（P2-交易36 治本，v3.54，2026-09-09）
> 需 trading 插件（403）。

对**已落库**流水按 `tradeId` 补填 `orderId`（成交编号）/`fee`（手续费）——截图/手动确认早期缺字段的历史流水补上后即可幂等去重与对账（跨月文件定位幂等）。**body**：`{"orderId":"69351117","fee":3.95}`（可选，只覆盖非空新值；orderId 空白且 fee 空 → 400；fee 非数字 → 400）。**响应**：`{"updated":true,"tradeId":"trade_..."}`；找不到该 id → `{"updated":false,...}`。

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `from` | String | 否 | 起始日期 `yyyy-MM-dd` |
| `to` | String | 否 | 截止日期 `yyyy-MM-dd` |
| `date` | String | 否 | **RFC 20260822**：指定单日 → 返回 `{trades, daily}`（当日复盘聚合）|

**Response（无 date）**：`TradeRecord[]` — 逐笔流水（含 `tradeTime` 可空）。

**Response（带 date，RFC 20260822 当日复盘聚合）**：

```json
{
  "trades": [{ "id": "trade_...", "symbol": "000725", "direction": "BUY", "price": 5.2,
    "volume": 1000, "entryDate": "2026-08-22", "tradeTime": "09:41:05" }],
  "daily": {
    "date": "2026-08-22",
    "count": 4, "buyCount": 3, "sellCount": 1,
    "buyAmount": 12345.6, "sellAmount": 6789.0,
    "sessions": [
      {"name": "早盘", "range": "09:30-11:30", "count": 2},
      {"name": "午盘", "range": "13:00-14:30", "count": 1},
      {"name": "尾盘", "range": "14:30-15:00", "count": 1}
    ],
    "firstTradeTime": "09:41:00", "lastTradeTime": "14:52:00"
  }
}
```

> `daily` 为纯客观聚合（无 AI）：时段分桶口径（2026-08-22 用户确认）早盘 09:30-11:30 / 午盘 13:00-14:30 / 尾盘 14:30-15:00；`tradeTime=null` 的旧流水计入 count/金额，不计入 sessions（不误判时段）。

---

### `POST /api/v1/trading/trades/batch` — 批量记录交易（2026-08-18 补实现）
> 需 trading 插件（403）。

web 交易 CSV 批量导入（此前前端一直调此端点但后端未实现 → 404，本批次补上）。

**body**：`{"trades":[{"symbol":"600519","name":"贵州茅台","direction":"BUY","price":1500,"volume":100,"stopLossPrice":1350,"buyPoint":"B1","reason":"..."}, ...]}`（字段同 `POST /trades`）

- 语义：逐笔走 `recordTrade` 链路（持仓增减 + 现金 + 手续费 + 逐笔流水）——日常多笔录入
- **逐条失败不整批回滚**：返回每行成功/失败（带行号人话原因）

**响应**：`{"success":2,"failures":[{"row":3,"message":"卖出数量超过持仓: 000725（持有 100 股）"}]}`

### `POST /api/v1/trading/sync` — 一键按流水重建持仓（2026-08-25 用户场景）
> 需 trading 插件（403）。

导入历史成交后持仓快照可能过期（如已清仓股票还挂在快照，被当初始底仓）。本端点**以流水为准重建持仓**：

- 每个 symbol 的开放批次（含 INIT 底仓兜底）汇总为持仓（数量 = Σ剩余，成本 = 加权）
- **流水已全部卖出的 symbol 从持仓移除**（removed）——中电电机场景：流水 8/17 买 1000 → 8/24 卖 1000（净 0）→ 移除
- **流水解释不了的真底仓保留**（keptInitial，快照 entryDate 早于流水首笔或流水无卖出记录）
- 保留快照元信息（entryDate/止损/买点/角色）；写回 positions 后批次视图/建议引擎立即一致

**响应**：`{"positionCount":3,"removed":["603988"],"keptInitial":[]}`

> **与 sync 模式（`trades/import`）互补**：`trades/import` 的 sync 处理**增量**（每日当天成交），本端点一次性**对齐存量账本**（历史成交全量导入后清理快照残留）。web 历史成交 Tab「一键同步」按钮入口。

### `POST /api/v1/trading/trades/import` — 历史成交导入（第五份文件，2026-08-18；2026-08-23 加回填；2026-08-25 双模式；v3.61 锚定 fail-closed + 幂等统一 + 预检）
> 需 trading 插件（403）。

通达信「历史成交查询」导出 → 落逐笔流水（**唯一成交真相源**），并按**券商快照锚定**决定是否回放持仓/现金。

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `mode` | String | 否 | `auto`（默认）按锚定分派：`entryDate ≤ 锚定日` 只补流水、晚于锚定日回放；`append` **全部只补流水**（不动持仓/现金，锚定缺失时的安全模式）|
| `dryRun` | boolean | 否 | 默认 `false`；`true` = **只返回计划，不写任何文件** |

**body**：`{"content":"通达信历史成交查询导出文本（UTF-8 转码后，表头含 成交日期/证券代码/买卖标志/成交编号）"}`

- **锚定分派（`mode=auto`，v3.61）**：券商快照（`replace=true` 持仓导入 / 资金股份查询）落的锚定日代表「券商当下真实状态**已含**此前全部成交」，因此 `entryDate ≤ 锚定日` 的成交**只补流水**（不动持仓/现金），晚于锚定日才**回放**（持仓增减 + 现金/手续费推导 + 时间线记录）。RFC 20260825 的「sync/append 双模式自动识别」在 v3.61 被锚定口径取代——`syncMode` 字段仍在，含义变为「本次**是否有回放行**」。
- **fail-closed（v3.61，本批核心）**：**锚定未知**（`trading/snapshot-anchor.json` 缺失/损坏/两日期皆空）**且系统已有持仓或账户快照**，而本次又有需要回放的行 → **400 人话拒绝**（「券商快照锚定缺失…本次有 N 笔近日成交需要回放持仓/现金，但没有锚定日就无法判断哪些成交已包含在券商口径内——照旧回放会把它们重复计算一遍。请先导入『持仓股』或『资金股份查询』快照建立锚定；若只想补逐笔流水（不动持仓/现金），用『仅补流水』模式重试」）。旧行为 = 锚定读不到就当「不做防重」继续全量重放（2026-09-12 生产事故根因：持仓 + 现金双计，现金被算成 −26666.85）。**全新用户**（无持仓无账户快照）无锚定仍允许从零回放。
- **预检（`dryRun=true`，v3.61）**：只算计划不落盘（改账动作先让人看见，对齐「花钱先报价」）；响应带 `dryRun:true` + `plan:{new,merged,skipped,nonTrades,wouldReject,anchorKnown,syncMode}`，**trades/、positions.md、account.json、imports/ 字节不变**。
- **每笔落流水**：`entryDate`=成交日期、`fee`=|发生金额−成交金额|（券商实扣）、`orderId`=成交编号（**幂等键**）；无编号按指纹去重。
- **幂等统一（v3.61：一个 intake、一个键空间，append 与 replay 同一判定）**——`orderId` 与指纹**双键都判**，与旧行有无 `orderId` 无关：
  - `orderId` 命中 → 缺元信息（`fee`/成交时间/`orderId`）则**合并回填**，否则跳过；
  - 指纹（`symbol|direction|entryDate|price|volume`）命中且成交时间**兼容**（任一侧缺失、或旧值带纳秒、或相差 ≤1 分钟）→ **合并回填不新增行**（补 `orderId`/`fee`/成交时间）；
  - 时间明显不同（同价、同量、同日的两笔真实成交）→ 视为**两笔**，照常新增。
  这条修掉了 2026-08-26 四笔跨来源重复流水（旧行为「同步/补录两套判定」）。
- **卖超/未持有不丢数据（v3.61）**：回放行的 SELL 超出可归属持仓 → **流水照落**（真实成交必须有记录）+ 计入 `rejected` 行级明细 + ERROR 日志，**持仓/现金不动**，同一缺口由 `GET /trading/integrity` 的 `gaps` 可重复核算。旧行为 = 只写 WARN 后丢弃（3 笔真实卖出有去无回）。
- 数量 0 行（股息红利税等非交易资金事件）不落流水，计入 `nonTrades`
- **非交易占位代码跳过（2026-08-25 用户反馈）**：明显非股票代码（通达信占位段 `79/80/81/82` 开头 6 位，如 `799999`「登记指定」/配号）一律不落库，计入 `nonTrades`（前端「非交易 N」可见）——此前 `799999 登记指定` 被当真实持仓入库
- **股息类资金事件记账（2026-08-25 用户拍板方案 A）**：备注列含 股息/红利/入账 的数量 0 行（如「股息红利税差异化处理资金下账」「股息入账」）→ **计入现金**：入账（发生金额正）现金 +N、红利税（负）现金 −N；不动持仓、不进批次；落一条 volume=0 流水（amount=发生金额，reason=源文件备注）可回溯；幂等（symbol+日期+发生金额绝对值指纹）；其余数量 0 行（无备注识别）计入 `nonTrades`

**响应**（2026-08-25 扩展；v3.61 新增 `rejected`/`anchor`/`dryRun`/`plan`）：
```json
{"imported":45,"updated":3,"skipped":1,"nonTrades":1,
 "syncMode":"sync",
 "rejected":[{"symbol":"600487","name":"亨通光电","direction":"SELL","volume":400,"price":65.31,
   "entryDate":"2026-09-03","reason":"卖出数量超过可归属持仓（持有 0 股）——该笔已落流水、持仓/现金未动，请核对快照基线或补导买入成交"}],
 "anchor":{"positionsReplace":"2026-09-09","cashImport":"2026-09-09","known":true,
   "holdingsKnown":true,"anchorDate":"2026-09-09"},
 "dryRun":false,
 "summary":{"date":"2026-08-25","buyCount":2,"sellCount":1,"buyAmount":10600.0,"sellAmount":3900.0,
   "newLots":1,"deductedLots":1,
   "behaviors":[{"type":"loss-avg-down","label":"亏损加仓","symbol":"600000","name":"浦发银行",
     "date":"2026-08-25","message":"买价 9.2 低于上一买批成本 10.0——越跌越买/补仓摊薄"}]},
 "lines":[{"symbol":"000725","name":"京东方Ａ","count":7,"netVolume":-400,"holdings":4800,
   "note":"当前持仓 4800 ≠ 流水净 -400——存在窗口前基线或未导入成交（持仓快照为准，差额已按初始批次兜底）"}]}
```
- `imported` = 落流水笔数 / `updated` = **跨来源同笔合并回填**笔数（v3.61 语义统一；旧语义「回填缺失成交时间笔数」并入此处；RFC §四 原拟新增独立 `merged` 字段，**实现沿用 `updated`**，`merged` 只出现在 `plan` 里）/ `skipped` = 幂等去重跳过 / `nonTrades` = 非交易事件
- `rejected` = **已落流水、未动持仓/现金**的真实成交（卖超/未持有）行级明细，`reason` 为中文人话；不再静默丢弃
- `anchor` = 券商快照锚定状态：`known=false` → 无法判断哪些成交已含在快照内；`holdingsKnown` = 持仓基线是否已记录；`anchorDate` = `positionsReplace`/`cashImport` **较晚者**，未知为 `null`
- `dryRun` = 本次是否为预检（恒在响应中）；`plan`（仅 `dryRun=true`）= `{new,merged,skipped,nonTrades,wouldReject,anchorKnown,syncMode}`，**且不写任何文件**
- `syncMode` = `sync`（本次有回放行）或 `append`（全部只补流水）
- `summary` = **每日操作总结**（RFC 20260825 §6，仅 sync 模式存在；不耗 AI 秒出）：买卖笔数/金额 + 批次 diff（`newLots` 新增批次、`deductedLots` 被扣减批次）+ `behaviors` 行为标注（`type`：loss-avg-down 亏损加仓 / chase-high 追高 / short-new 短线新开 / stop-loss-ignored 破止损未走 / giveback 浮盈回吐 / short-overdue 短线超期）
- `lines` = 对账提示：每标的 流水净增减 vs 当前持仓快照，指出基线缺口/已清仓（只报告不改数据）

**错误**：锚定 fail-closed 拒绝 → **400** 人话（含两条逃生路径）；`content` 缺失 → 400。

### `GET /api/v1/trading/evidence/history` — 本人历史操作统计（铁证①，v3.80，2026-09-22）
> 需 trading 插件（403）。

把「你过去 N 次…」做成**可核对的数据**（RFC `20260922-trading-decision-copilot` A 批）：从清仓回合（`sold.json`）按维度分桶，给出次数 / 胜率 / 平均盈亏 / 平均持天。**只读**，不写任何用户数据。

**query**：`dimension`（可选，默认 `HOLD_DAYS`；大小写不敏感）——
`HOLD_DAYS`（持仓时长：`≤1 天` / `2-3 天` / `4-10 天` / `>10 天`）·
`PNL_BUCKET`（盈亏区间：`≥+10%` / `+5~10%` / `0~+5%` / `-5~0%` / `<-5%`）·
`VERDICT`（清仓判定，动态分组；空 verdict 归「未判定」，不伪装成某条规则）。非法值 → **400** 人话。

**Response（200）**：
```json
{
  "dimension": "HOLD_DAYS",
  "buckets": [
    {"label":"≤1 天","count":6,"wins":5,"winRate":0.8333,"avgPnlPct":2.12,"avgHoldDays":1.0,"sufficient":true}
  ],
  "totalRounds": 6,
  "anySufficient": true,
  "note": "按「持仓时长」看你自己的 6 个回合；只报样本 ≥ 5 的组"
}
```
- **`sufficient`（本端点的核心约束）**：该组样本是否 ≥ **5**（用户 2026-09-22 拍板 D3）——**`false` 时调用方不得引用这组数字**
- `anySufficient=false` → 文案必须转成「样本还不够」（`note` 已给好人话），**不许拿 1-2 次巧合当规律**
- `note` 含门槛口径，可直接进推送/对话文案
- 无清仓回合 → `buckets:[]` + `note`「还没有清仓回合记录——等你卖出几笔之后，我才能拿你自己的操作说话」

### `GET /api/v1/trading/evidence/rule/{ruleRef}` — 规则依据原文（铁证③，v3.80，2026-09-22）
> **不需**插件门控（规则原文属公共知识，运维也可直接核对）。

按规则编号取 `os/trading-engine/knowledge/context/rules.md` 的**逐字原文**——经 `TradingRuleEngine.parseRules` 解析，路径由 `adai.knowledge.trading-engine-path` 决定（与既有建议知识注入**同源同口径**）。用途：建议里引用 `R66` 时，把原文**照抄**给用户核对，不得由 AI 复述成「大概是这个意思」。

**path**：`ruleRef` —— `R66` / `r66` / `66` / `R 66` 都认。

**Response（200）**：`{"number":66,"title":"只输一根K线","detail":"核心理念：…"}`

**错误**：没有这条规则 → **404** `{"error":"没有这条规则的原文（编号 R999）——我不会替你编一条出来"}`；规则文件读不到 → 同样 404（降级为「不给引用」，**绝不编造**）。

### `POST /api/v1/trading/evidence/backfill` — 建议结果回填（铁证④，v3.81，2026-09-22）
> 需 trading 插件（403）。

把**已到期**（发出满 N 个交易日）的建议补上「后来怎么样了」——写进 `advice-history` 里那条留痕的 `outcome` 字段。**只记录事实，不判对错**：

- `afterDays` = 回看窗口（交易日；配置 `adai.trading.advice-outcome-days`，默认 **5**）
- `priceThen` / `priceAfter` = 建议日与 **N 个交易日之后**的收盘
- `pct` = 区间涨跌幅（%）
- `userActed` = 建议之后用户对这只票**有没有动作**：`traded` / `none` / `unknown`
  （流水读不到时记 `unknown` ——「不知道」与「没操作」是两件事，**不谎报 none**）

**幂等**：已有 `outcome` 的条目**直接跳过**（结果只写一次，重复回填不改写历史）。
**宁可留空、不写半成品**：K 线取不到 / 还没走满 N 个交易日 / 分母为 0 → 本次不写，留待下次。

**Response（200）**：`{"written": 2}`（本次真正写入的条数；请求体为空，用户取自 `X-User-Id`）

### `GET /api/v1/trading/integrity` — 账实一致性自检（对账闸门，v3.61，2026-09-12）
> 需 trading 插件（403）。

把「三条真源是否自洽」变成**当天可见**的只读闸门（2026-09-12 生产事故：口径互斥三天，靠用户肉眼发现）。**口径**：`derived` = 券商快照基线数量（锚定日 `replace` 导入时记录的 `holdings`）+ 锚定日之后**逐笔流水净增减**；与落地持仓不一致即 `drift`。本端点**只报告不改数据**。

**Response（200）**：
```json
{
  "anchor": {"positionsReplace":"2026-09-09","cashImport":"2026-09-09","known":true,
             "holdingsKnown":true,"anchorDate":"2026-09-09"},
  "holdingsKnown": true,
  "drift": [{"symbol":"002428","name":"云南锗业","snapshotQty":300,"ledgerDelta":100,
             "derived":400,"holdings":350,"diff":-50,
             "note":"应有 400 股（快照基线 300 + 锚点后流水 +100），落地 350 股，差 -50 股——账实不符，请核对流水/重导券商快照"}],
  "gaps": [{"symbol":"600487","name":"亨通光电","direction":"SELL","volume":400,"price":65.31,
            "entryDate":"2026-09-03","reason":"重放时持仓不足（持有 0 股）——快照基线缺口或漏导买入；该笔未计入派生持仓"}],
  "note": "账实不符：1 只标的持仓不一致、1 笔回放缺口（锚定日 2026-09-09）——先核对逐笔流水，再决定是否重导券商快照重建口径"
}
```
- `anchor` = 与导入响应同一结构；`holdingsKnown` = 快照基线是否已记录（决定能否对账）
- `drift[]`：`snapshotQty`（快照基线，缺则 `null`）/`ledgerDelta`（锚定日之后流水净增减）/`derived`（应有）/`holdings`（落地）/`diff`（落地−应有）/`note`（人话）；`diff=0` 的标的不列出
- `gaps[]`：重放时**卖超/未持有**的缺口行（与导入响应 `rejected` 是同一件事，可重复核算，不依赖当时返回）——缺口行**既不计入 `derived` 也不计入 `ledgerDelta`**（否则会得出「应有 −800 股」这种荒谬结论），只以 `gaps` 报出等人工核对/重导快照
- **降级诚实**：锚定缺失 → `anchor.known=false` + `note`「无法判定」+ `drift:[]`；锚定有但基线未记录（`holdingsKnown=false`）→ 同样不误报差异，`note` 指路「重导一次『持仓股』快照即可建立基线」
- **`degraded[]`（v3.79，2026-09-22，P1-交易61）**：`{symbol,name,direction,volume,price,entryDate,inferred,reason}`——**成交日 == 锚定日** 的流水被按「已含在券商快照内」处理（只记流水、未进持仓）。这类情况 `drift`/`gaps` **结构上查不出来**（两边同源于那份快照，必然相等 = 假绿），所以单独列出：**`inferred=true`** 表示锚定日是**推断**出来的（快照文件日期被归一化——盘前/非交易日导出会退到上一交易日），此时若快照实际基准日不是那一天，这些成交就不会体现在持仓里 → **前端据此出橙色横幅**；`inferred=false`（锚定日 = 文件日期）只是事实说明，**不出横幅**。锚定存在但没记录文件日期（老数据）→ `note` 补一句「无法判断锚定日是否被归一化推断过」
- 发现不符时后端记 ERROR 日志（含明细前 5 条）；deploy-gate 以「`anchor.known=false` 或 `drift` 非空」为显式告警

### `GET /api/v1/trading/anchor` — 券商快照锚定状态（v3.61，2026-09-12）
> 需 trading 插件（403）。

**只读**（不触发任何写入），供前端提示与部署自检。

**Response（200）**：`{"positionsReplace":"2026-09-09","cashImport":"2026-09-09","known":true,"holdingsKnown":true,"anchorDate":"2026-09-09"}`

- `positionsReplace`/`cashImport` = 持仓 `replace` 导入日 / 资金股份导入日（未发生过为 `null`）
- `known` = 至少一个日期存在（`false` → 无法判断哪些成交已含在快照内：导入会走 fail-closed 拒绝，需先建锚定或 `mode=append`）
- `holdingsKnown` = 快照持仓基线是否已记录（决定对账闸门能否判定）
- `anchorDate` = 生效锚定日 = `positionsReplace`/`cashImport` 的**较晚者**（皆无 → `null`）

### `PUT /api/v1/trading/anchor` — 锚定回填（存量环境显式自愈，v3.61，2026-09-12）
> 需 trading 插件（403）。

给「升级前已导过快照、但没写锚定文件」的存量环境一次**显式**自愈手段（否则 fail-closed 会把用户卡死，只能靠重导快照文件绕）。**只改元信息（锚定日/持仓基线），不动持仓/现金/流水**；日期**只前进不后退**。

**Request Body**（三者至少给一个，全空 → **400** 人话「锚点回填至少要给一个日期（positionsReplace / cashImport）或持仓基线」）：
```json
{"positionsReplace":"2026-09-09","cashImport":"2026-09-09",
 "holdings":[{"symbol":"600206","name":"有研新材","quantity":600}]}
```
- `positionsReplace`/`cashImport`：`yyyy-MM-dd`（兼容 `yyyyMMdd`；格式错 → 400 人话）；小于当前值 → 忽略（**不后退**）
- `holdings`：可选，补建对账基线（`GET /trading/integrity` 的 `snapshotQty` 来源）；`symbol` 空的行忽略

**Response（200）**：`{"positionsReplace":"2026-09-09","cashImport":"2026-09-09","known":true,"holdingsKnown":true,"anchorDate":"2026-09-09"}`（回填后的锚定状态，与 `GET /trading/anchor` 同结构）

### `GET /api/v1/trading/lots` — 批次视图（RFC 20260825 逐笔批次跟踪）
> 需 trading 插件（403）。

持仓细化到每一笔买入（批次）独立跟踪：成本/盈亏/止损/角色挂批次，**批次由逐笔流水重放推导（不落盘）**。规则（用户拍板）：同标的+同方向+**同日**合并一个批次（一天最多一个买批，成本=当日加权平均含费）；卖出按 **LIFO** 先扣最近买入批次，跨批按各自成本分算已实现盈亏；批次剩余 0 = 关闭（回合）；positions.md 有但流水覆盖不到的底仓 = 初始批次（`initial=true`，`lotId` 以 `_INIT` 结尾）。

| 参数 | 类型 | 说明 |
|:-----|:-----|:-----|
| `state` | String | `open`（仅持有中）/ `closed`（仅已关回合）/ `all`（全部，省略默认返回全部）|
| `symbol` | String | 可选，按代码过滤 |

**响应**：
```json
{"lots":[{"lotId":"600000_2026-08-03_B","symbol":"600000","name":"浦发银行","buyDate":"2026-08-03",
  "volume":1000,"remaining":500,"costPrice":10.0011,"currentPrice":10.5,"marketValue":5250.0,
  "pnl":249.45,"pnlPct":4.99,"stopLossPrice":9.3,"stopLossDistancePct":11.43,
  "buyPoint":"B1","role":null,"initial":false,"closed":false,"realizedPnl":250.0}],
 "reconcile":[{"symbol":"000725","name":"京东方Ａ","count":7,"netVolume":-400,"holdings":4800,
   "note":"当前持仓 4800 ≠ 流水净 -400——存在窗口前基线或未导入成交（持仓快照为准，差额已按初始批次兜底）"}]}
```
- `lots` = 批次明细（注入现价；行情失败 currentPrice=成本价）；`stopLossPrice` 未设时后端按默认 −7% 兜底返回；`stopLossDistancePct` 距止损%（正=安全，负=已破）；`closed=true` 时 `realizedPnl`=该批已实现盈亏（回合总账）
- `reconcile` = 流水重放 vs 持仓快照对账提示（防漏导一天成交静默错下去，只报告不改数据）
- 批次级止损已接入 30 分钟行情轮询：某批现价破它自己的止损 → 单独推送「批次止损预警」（不跟底仓混）
- **批次级止损覆盖（v3.43）**：`stopLossPrice` = 推导（流水）后**合并用户覆盖层** `lot-stoploss.json` 的值——覆盖 > 流水止损 > 默认 −7%；设/改与清除见下方 PUT/DELETE 端点

### `PUT /api/v1/trading/lots/{lotId}/stop-loss` — 设/改批次止损（v3.43，2026-09-04）
> 需 trading 插件（403）。给某个买入批次**单独设/改止损位（事后可调）**——落覆盖层不污染流水；改完批次预警/行为标注/复盘自动跟随。

**Body**：`{"stopLossPrice": 12.34}`（>0 且 ≤4 位小数；非法 → 400 人话）

**Response（200）**：`{"lotId": "600000_2026-08-03_B", "stopLossPrice": 12.34}`；lotId 不存在（流水/持仓推不出）→ **404**

### `DELETE /api/v1/trading/lots/{lotId}/stop-loss` — 清除批次止损覆盖（v3.43）

**Response（200）**：`{"lotId": "...", "cleared": true}`（清除后回退该批流水止损/默认 −7%）；lotId 不存在 → **404**

### `GET /api/v1/trading/equity-curve` — 资金曲线（v3.45，2026-09-04 决策方案 A）
> 需 trading 插件（403）。按日收盘净资产曲线：现金（account.json 现值反向锚定 + 逐笔交易/转账事件驱动）+ 持仓市值（流水回放 + 底仓恒持 + 当日收盘价；停牌/缺 K 沿用前收、再缺用成本兜底）。

**Response（200）**：
```json
{
  "points": [{"date": "2026-08-03", "totalAssets": 120000.00, "cash": 20300.00,
              "marketValue": 99700.00, "invested": 100000.00,
              "netValue": 1.2000, "drawdown": 0.0000}],
  "skippedDays": 0, "startDate": "2026-08-03", "endDate": "2026-09-04",
  "dailyPnl": {"2026-08-03": 1234.56, "2026-08-04": null}
}
```
- **`dailyPnl`（v3.69，2026-09-16，P2-交易52）**：逐日「当日盈亏」（`yyyy-MM-dd` → 金额），与 `pnl-periods` 的三档**同一份口径**（券商当日参考盈亏：卖出净额 − 昨收×卖量 ＋ 旧仓(今收−昨收) ＋ 当日买入(今收−含费均价)）。**某天缺收盘价时值为 `null`**（后端不猜 0）——app「收益日历」据此显示「—」。⚠️ 前端**不得自行差分推导**（差分会把资金曲线自身的估值偏差当成盈亏，见 `pnl-periods` 的实测说明）。
- `netValue` = totalAssets / invested（invested = 期初投入缺口 + 转账累计净投入）；invested ≤0 → `netValue: null`（本金未设不给误导值，P2-交易31 同口径）
- `drawdown` = 历史峰值到当日回落比例（0 = 新高）
- 无账户快照（从未导入资金/无记录）→ `points: []`（静默降级不抛错）
- **锚定重置（v3.68，2026-09-15）**：日期走到券商快照锚定日（`snapshot-anchor.json` 的 `latest()`）时，持仓数量重置为快照基线 `holdings`，之后只叠加锚定日**之后**的流水。此前纯流水回放会在「历史成交只补了买入、卖出没导全」的标的上凭空多出持仓（生产实测 000776/600487 各多 600/400 股，整条曲线市值虚高）；锚定未知或基线未记录 → 保持旧的纯回放行为。

---

### `GET /api/v1/trading/pnl-periods` — 今日/本周/本月盈亏（v3.68，2026-09-15）
> 需 trading 插件（403）。口径 = **券商「当日参考盈亏」逐日累加**（2026-09-16 修正）：逐日盈亏 = 卖出净额（已扣费）− 昨收×卖量 ＋ 旧仓(今收−昨收) ＋ 当日买入(今收−含费买入均价)；区间收益率 = 区间盈亏 ÷ 区间前一日总资产。
> **为什么不用总资产差分**：差分会把资金曲线自身的估值偏差当成盈亏——实测 2026-09-14 差分 −1102.40 vs 券商 App **+2225.59**（差 3300+）。改逐笔口径后同日复算 **2225.60**，与券商一字不差（同日根因：tdx 行情包滞后而 K 线未回落网络源，已同批修复）。

**Response（200）**：
```json
{
  "today": {"pnl": -503.90, "pct": -0.62, "base": 81224.63, "from": "2026-09-15", "partial": false},
  "week":  {"pnl": 1234.00, "pct": 1.53, "base": 80600.00, "from": "2026-09-14", "partial": false},
  "month": {"pnl": 2500.00, "pct": 3.10, "base": 80600.00, "from": "2026-09-01", "partial": true},
  "asOf": "2026-09-15", "anchorDate": "2026-09-11", "note": ""
}
```
- `pct` 可为 `null`（区间起点前没有曲线点 / 锚定日之前不可追溯）——**前端一律渲染「—」，不得写成 0%**
- `partial: true` = 该区间只有部分可追溯（如本月跨过锚定日）→ 前端如实标注「本月自券商快照 YYYY-MM-DD 起可追溯」
- 无任何资金/成交记录 → `today/week/month` 均为 `null` + `note` 人话说明（不给 0 冒充）

---

### `GET /api/v1/trading/search` — 标的搜索（代码/名称/拼音首字母，2026-08-30 验收反馈）
> 需 trading 插件（403）。

记不住 6 位代码只记得名字时的搜索入口（东财 suggest，如 `q=gzmt` → 贵州茅台；`q=中国稀土` / `q=000831` 均可）。

**Response（200）**：
```json
[{"symbol":"000831","name":"中国稀土"},{"symbol":"600831","name":"广电网络"}]
```
最多 10 条，仅 A 股 6 位代码；失败/无结果 → `[]`（不抛错）。

### `GET /api/v1/trading/lookup` — 按代码查询名称（代码输入带出 + 二次确认，2026-08-16）

| 参数 | 类型 | 说明 |
|:-----|:-----|:-----|
| `symbol` | String | 六位股票代码 |

**响应**：`{"symbol":"000725","name":"京东方A"}`（name 查询失败为空串，前端可手填）。需 trading 插件（403）。

### `GET /api/v1/trading/watchlist` — 自选股列表（RFC 20260816 交易数据智能）

返回自选条目（symbol/name/industry/industry2/longForm/midForm/shortForm/signal/addedAt，通达信形态与指标提示为买点判定原料）。需 trading 插件（403）。

### `POST /api/v1/trading/watchlist/import` — 自选股导入（通达信导出文本）

**body**：`{"content":"通达信自选导出文本（GBK 已转码）"}`。表头定位列（代码/名称/细分行业/一二级行业/长期/中期/短期形态/近日指标提示），按 symbol upsert。**响应**：`{"imported":27}`。

### `DELETE /api/v1/trading/watchlist/{symbol}` — 删除自选股

### `GET /api/v1/trading/buy-points` — 自选股买点信号（C2 盯盘买点，2026-08-16）

对全部自选股拉 K 线（腾讯主源 → 东财兜底，2026-08-24 主源切换）→ `BuyPointDetector` 判定 → 命中返回信号列表（**判定是提示不是指令**，买不买人决策）。

**响应**（P1-交易10 修正 2026-08-17：score 是 0-100 分，signals 是 detector 实际文案）：
```json
[{"symbol":"000725","name":"京东方A","buyPoint":"B1","score":87,
  "signals":["回撤 52%（到涨幅一半位）","缩量 0.6x","KDJ.J=12"],
  "caseMatches":[{"caseId":"2026-08-03_000725","buyDate":"2026-08-03","buyType":"B1","similarityPercent":92.5}],
  "dataDate":"2026-09-04"}]
```
- **caseMatches（环 4 二期，2026-08-30 可选）**：开关 `adai.trading.case.scan-match`（**默认 false**）开启时，每只自选股附「与完美买点案例库相似度 Top 3」参考（经验增强，不覆盖规则判定）；默认关 → 字段为空/缺失，行为与现状完全一致；`buyPoint="case"` 表示规则未命中但案例相似度高（参考信号，**推送一律跳过该类型**，仅 web 可见）
- **dataDate（v3.44；v3.83 改口径）**：判定所用 K 线最后一根日期（YYYY-MM-DD）——**早盘推送（09:15，B1 起买点并入早盘）要求 dataDate 不早于「最近一个已收盘交易日」**（09:15 时最近一根本就该是上一交易日；比它还旧 = 数据滞后，不推。原 15:10 口径为「=当日」，节点取消后随之改判据，P1-交易20「不用旧数据冒充新信号」的原意不变）；web 手动查询不受限（信号列可据此提示数据日）

- **B1 回调买点（2026-09-04 课程口径校准，P1-交易9 出表）**：回撤到波段**涨幅一半位置**——回撤占波段比例（窗口最高 high − 窗口最低 low 的区间）≥ 回调比例 + 缩量（3 日均量 < 5 日均量 × 缩量阈值）+ KDJ.J < 低位阈值；几何等价 close ≤ (high+low)/2（替代旧「距前高回撤 ≥50%」腰斩口径）
- **B2 突破买点（2026-09-04 三重校验，P1-交易20 出表）**：放量（当日量 > 5 日均量 × 放量倍数，默认 **2.0** = 课程「倍量柱」）+ 收盘破前高 + **KDJ.J 拐头向上 + J 连续 ≥90 高位钝化排除（首日拉起放行）+ 距窗口低点涨幅 ≤30%（防追高）+ 非近 2 日连板（单日 ≥9.8%）**
- **参数按用户规则**（2026-08-30 v3.33，交易插件规则层）：回调 0.5 / 缩量 0.7 / KDJ 13 / 放量 2.0 / 前高 20 日是**默认值**——从 `data/{userId}/trading/rules.yaml` 的 `buyPullbackPct/buyShrinkRatio/buyKdjLow/buyVolumeSurge/buyPriorHighDays` 读取（`PUT /trading/rules` 可配，无规则 → 默认值）；B1/B2 命名语义随 adai 规则包（通用原语：回调/缩量/KDJ/放量/突破）；规格详见 `os/trading-engine/engine/buy-point-rules.md`
- **扫描入口（v3.83，B1）**：~~收盘 15:10 定时任务独立推送「到买点了」~~ **已取消**——买点提醒并入 **09:15 早盘推送**（用户 2026-09-22 明确「买点提醒并入早盘」：他是早盘买的人，昨日收盘触发的信号该在他要动手的时刻出现）；web 自选 Tab 显示信号列；**B1?（部分满足候选）不推送**（P2-交易7）
- 需 trading 插件（403）。

### `GET /api/v1/trading/buy-points/scan` — 自选买点完整扫描（v3.83，B4；收口 P1-交易60）

与 `/buy-points` 同一份判定，但把**「没能判定的标的」**与**「判定了但没信号」**分开报——生产实据（2026-09-18 10:37）K 线双源失败恰好砸在用户看盘的同一分钟，旧实现两条路径同样表现为「不在结果里」，用户拿到的是**空值/旧值且没有任何提示**（「不知道」被渲染成「没问题」）。

**响应**：
```json
{"hits":[{"symbol":"000725","name":"京东方A","buyPoint":"B1","score":87,"signals":["回撤 52%"],
          "caseMatches":[],"dataDate":"2026-09-19"}],
 "unavailable":[{"symbol":"600487","name":"亨通光电"}],
 "dataDate":"2026-09-19"}
```
- `unavailable`：K 线取不到（双源失败）或判定异常而**没能判定**的标的（与「没信号」是两回事）；服务端每次扫描都有日志行（`N 只 → M 命中 · K 只没取到行情 · 数据到 …`）
- `dataDate`：本次判定所用 K 线末端日期（无数据 → 空串）
- `GET /trading/buy-points` 的**数组形状保持不变**（web / app 两处消费），新信息走本端点
- 需 trading 插件（403）。
- 需 trading 插件（403）。

### `GET /api/v1/trading/sold` — 清仓股列表（复盘闭环）
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。

**响应结构（2026-09-09 v3.54，RFC 20260909 清仓级联批 1 双轨）**：`{"sold":[SoldTradeDto…], "pendingClearances":[{symbol,name,sellDate,reason}]}`——`sold` 每项新增 `provenance`（`"flow"`=流水自动收录（ClearanceDetector：流水证明清仓且买卖均在流水内，只填空白 symbol、幂等）/ `"import"`=券商清仓股导出，老行缺省 import）；`pendingClearances` = 流水证明已清仓但**缺买入基线**（BUY<SELL）的待补档案（reason 人话「已清仓但流水缺买入基线——导入清仓股导出补全档案」），为空时省略。前端「导入清仓」后 `sold` 以券商字段校准（upsert 保留 psychology）。

### `POST /api/v1/trading/sold/import` — 清仓股导入（通达信导出文本）

**body**：`{"content":"..."}`。表头定位列（代码/名称/介入日期/清仓日期/持仓天数/买卖次数/持仓期涨幅%），按 symbol upsert（保留已有 verdict/psychology）。**响应**：`{"imported":42}`。

### `PUT /api/v1/trading/sold/{symbol}/psychology` — 清仓股心理标注

**body**：`{"psychology":"追高后恐慌割肉"}`（用户复盘素材，个人数据隐私保护）。

### `GET /api/v1/trading/sold/score` — 清仓复盘三维打分（D3，2026-08-16）

对全部清仓交易复盘打分（**分数是参考不是指令**，复盘用，买不买永远人决定）。三维：买点（买入日回溯 K 线 → B1/B2 完美图匹配度）/ 执行（verdict 纪律对照）/ 选股（关注后表现，数据积累后返回 null）。

**响应**：
```json
[{"symbol":"600519","name":"贵州茅台","buyPointScore":88,"buyPointSignal":"B1",
  "buyPointExplain":"回调 52%、缩量 0.6x、KDJ.J=12",
  "executionScore":90,"executionExplain":"盈利了结，执行到位",
  "totalScore":89,"verdict":"盈利了结"}]
```

- 买点维度：B2 突破 85-100 / B1 低吸 70-100 / B1? 候选 50 / 无形态（追高）25；买入日超出 K 线回溯范围或回溯 K 线不足 25 根 → `buyPointScore=null`（总分为 null 不糊弄，数据不足不误判追高）
- 执行维度：盈利了结 90 / 其他亏损按纪律 65 / 违反 R53 45 / 违反 R66 15
- 需 trading 插件（403）。

### `POST /api/v1/trading/transfer` — 银证转账（转入/转出，净投入跟踪，2026-08-16）

**body**：`{"type":"IN|OUT","amount":10000,"date":"2026-08-17","note":"补仓"}`

- `type` IN=转入（银行卡→证券）/ OUT=转出（证券→银行卡）；`amount` > 0；`date` 可空缺省今天
- **模型**：本金（净投入）+= 转入 - 转出；现金/资产同步 ±；总盈亏 = 资产 - 本金（转账本身不变盈亏）
- **响应**：`{"id":"transfer_...","type":"IN","amount":10000,"date":"2026-08-17"}`
- 需 trading 插件（403）

### `GET /api/v1/trading/transfers` — 转账流水
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。

### `POST /api/v1/trading/trades`（手续费自动）— 2026-08-16 起手续费按费率自动计算，表单不再填：

| 费 | 费率 | 规则 |
|:---|:-----|:-----|
| 佣金 | 万 0.854 | 买入卖出都收，四舍五入到分，无最低 5 元 |
| 印花税 | 万 5 | 仅卖出，去尾到分 |
| 过户费 | 万 0.1 | 仅沪市（6/9 开头），四舍五入到分 |

买入成本 = 价×量 + 佣金 + 过户费（摊薄成本价落盘）；卖出回款 = 价×量 - 佣金 - 印花税 - 过户费；买卖后现金自动推导（account.json）。

### `GET /api/v1/trading/account` — 账户总体快照（顶层账户卡，2026-08-16）

资金股份查询导入后返回券商口径账户：`{"assets":110504.88,"cash":292.88,"available":292.88,"withdrawable":292.88,"marketValue":110212.00,"pnl":15235.55,"todayPnl":0.0,"todayPnlSource":"broker","principal":150000,"snapshotDate":"2026-08-16"}`。**字段语义（2026-08-16 修正）**：`pnl` = 持仓浮动盈亏（券商口径，非总盈亏）；**总盈亏 = `assets - principal`**（本金由用户提供，累计投入 15 万 → 当前总盈亏 -39,495.12）。顶层展示总资产/可用/可取/参考市值/当日盈亏/总盈亏/本金。数据依赖导入；收盘 15:05 自动更新行情相关字段（参考市值/当日盈亏/浮盈，P2-交易19 修订），现金/本金保持券商导入+转账推导。需 trading 插件（403）。

**`todayPnlSource`（v3.65，P2-交易48）**：当日盈亏的**来源**——`"broker"` = 券商「资金股份」文件「当日盈亏」列求和（券商权威口径，写入时同时落 `snapshotDate`）；`"calc"` = 系统按当日成交流水精确计算（口径①）；`null` = 未知（v3.65 之前的存量数据）。字段缺失/null 时前端**不标注**（不编造来源）；配合 `snapshotDate` 可显示「券商口径 · 09-11」或标「已过期」。

### `GET /api/v1/trading/push-settings` — 推送开关（RFC 20260817 交易推送体验）
> 需 trading 插件（403）。

返回用户推送类型开关：`{"session":true,"buy-point":true,"stop-loss":true,"near-stop-loss":true,"loss":true,"gain":true,"break-cost":true,"market":true,"close-summary":true,"learn-review":true,"todo-due":true}`（类型 → 是否开启；未配置默认开）。关闭的类型定时任务不再生成、Feed 不再注入（双侧门控）。`close-summary`（2026-08-29，P2-用户3）= 15:30 收盘小结；`learn-review`（2026-09-07 learn V2 批 4）= 每晚 20:00 学习复习到期卡片汇总提醒；`todo-due`（2026-09-17 RFC 20260917）= 待办**到期当天** 08:00 与 18:00 各推一次（`TodoReminderService`），通知深链 `todo:today` 打开待办页。

### `PUT /api/v1/trading/push-settings/{type}` — 更新推送开关
> 需 trading 插件（403）。

- **path**：`{type}` ∈ session / buy-point / stop-loss / near-stop-loss / loss / gain / break-cost / market / close-summary / learn-review
- **body**：`{"enabled":false}`（未知类型 → 400）
- **响应**：更新后的全量开关对象

### `GET /api/v1/trading/rules` — 交易规则参数（第三阶段，用户自己的交易系统）
> 需 trading 插件（403）。2026-08-30 v3.33 新增，蓝图 trading-plugin-architecture.md。

返回用户规则参数（`data/{userId}/trading/rules.yaml`，无规则 → 默认值）：

```json
{
  "exists": true,
  "params": {
    "positionLimitPercent": "25",      // 单票仓位上限 %（R81 默认）
    "defaultStopLossRatio": "0.93",    // 默认止损比例（−7%）
    "givebackPeakPct": "20",           // 浮盈回吐：峰值浮盈 %
    "givebackRatioPct": "50",          // 浮盈回吐：回吐比例 %
    "shortOverdueDays": "5",           // 短线超期天数
    "soldStopLossPct": "5.0",          // 清仓止损阈值 %
    "soldShortHoldDays": "5",          // 清仓短持仓天数
    "buyPullbackPct": "0.5",           // 买点：回调幅度
    "buyShrinkRatio": "0.7",           // 买点：缩量阈值
    "buyKdjLow": "13.0",               // 买点：KDJ 低位
    "buyVolumeSurge": "1.5",           // 买点：放量倍数
    "buyPriorHighDays": "20",          // 买点：前高窗口
    "scoreBuyWeight": "0.5",           // 打分：买点权重
    "scoreExecWeight": "0.5",          // 打分：执行权重
    "constraintRuleMin": "66",         // 纪律硬约束：规则号下限
    "constraintRuleMax": "95"          // 纪律硬约束：规则号上限
  }
}
```

**语义（第三阶段）**：参数按用户隔离，驱动——止损/仓位判定（`TradingRuleEngine`）、买点信号（`BuyPointDetector`）、行为标注（`TradingLotService` 浮盈回吐/短线超期）、清仓 verdict（`SoldTradeVerdict` 阈值）、打分权重（`SoldScoreService`）、纪律硬约束区间（`TradingAdviceAppService`）、知识注入（`TradingKnowledgeSource` 读 `data/{userId}/trading/knowledge.md` 用户私有，os/ 作 adai 默认）。**无规则用户 → 全部默认值 = adai 现状行为（降级不坏）**；改自己的参数 = 改自己的交易系统，不影响别人。

### `PUT /api/v1/trading/rules` — 更新交易规则参数
> 需 trading 插件（403）。2026-08-30 v3.33 新增。

- **body**：`{"params":{"positionLimitPercent":30,"buyKdjLow":20}}`（只传要改的字段，缺省保持原值；值经 `TradingRuleSettings` 构造器 fail-closed 校验，非法回落默认）
- **响应**：`{"updated":true}`
- **落盘**：`data/{userId}/trading/rules.yaml`（File First，可导出/导入/版本管理）

### `GET /api/v1/trading/market-stage` — 活跃市值区间（用户手动判定开关，v3.41）
> 需 trading 插件（403）。2026-09-04 新增，来源：2026-09-03 用户对话「指南针活跃市值指标——一切的前提，由我来判定多头/空头」。

返回用户**手动判定**的活跃市值多空区间（指南针活跃市值口径，红涨绿亏：多头=红/空头=绿）：

```json
{
  "exists": true,
  "stage": "bear",        // bull（多头区间）| bear（空头区间），两档
  "updatedAt": "2026-09-04T09:00:00"   // 最近手动判定时间
}
```

- **无记录**（从未手动判定）：`{"exists":false,"stage":null,"updatedAt":null}`——此时推送回退 current.md 规则推断（见下方语义）。
- **语义**：此判定是**用户权威**——一旦设置，时段推送（早盘计划/午间跟踪/尾盘建议）与知识注入的【择时状态】一律以它为准，**不再被 current.md 的 OAMV 规则推断覆盖**（解决了 2026-09-03 对话暴露的问题：current.md 靠 6/26 一条旧规则永久锁死「空头」，用户无法表达自己的判断）。

### `PUT /api/v1/trading/market-stage` — 设定活跃市值区间（v3.41）
> 需 trading 插件（403）。2026-09-04 新增。

- **body**：`{"stage":"bear"}`（`bull`=多头区间 / `bear`=空头区间；缺失或非法 → 400 `{"error":"stage 必须是 bull（多头）或 bear（空头）"}`）
- **响应**：`{"updated":true,"stage":"bear","updatedAt":"2026-09-04T09:00:00"}`
- **落盘**：`data/{userId}/trading/market-stage.json`（File First，per-user 条带锁原子写，P2-交易28 锁池模式）

### `GET /api/v1/trading/trade-log` — 当日交易日志候选（RFC 20260817 交易日志自动归集）
> 需 trading 插件（403）。

返回当日已归集的交易候选（**未落库，待确认**）：`[{"id":"cand_20260917_154512345","symbol":"000725","name":"京东方A","direction":"SELL","price":6.1,"volume":5300,"tradeDate":"2026-08-26","source":"text","complete":true}]`。来源：用户发成交截图（VLM 识别）或说「清仓了XX」（文字解析），仅 trading 插件用户触发；**去重口径**：同 (symbol, direction, 当日) + **价格相同** + 数量差 ≤ ±10% 视为同笔（`sameTrade`），超量级/异价分别保留——**P0-交易53（2026-09-17）补价格维度**：原来只看 symbol+方向+数量，导致「同标的、同方向、各 100 股」的多笔成交（生产实据：一张截图三笔亨通光电买入 100 股 @68.27/67.73/67.92）被吞成一条。**id（P1-交易54，2026-09-17）**：候选**行标识**，删除（`DELETE /trade-log?id=`）按它行级定位；旧候选文件无该字段 → 服务端按内容派生**稳定** id。**tradeDate（2026-08-27）**：截图表格「日期」列提取的成交日期（历史成交截图，如 `2026-08-26`）；当日委托/文字归集无日期 → `null`。**2026-08-27 二修（v3.32）**：截图归集候选（source=image）`tradeDate=null` 时**确认会被拒**——需先补日期（`PUT /trade-log/date`）再确认，防「昨日成交今早确认被记成今天」。

### `POST /api/v1/trading/trade-log/confirm` — 确认交易日志落库
> 需 trading 插件（403）。

当日候选逐笔走 `recordTrade` 链路（持仓增减 + 现金 + 手续费自动算）；**2026-08-27（用户反馈「今日 4 笔其实是昨天」）**：落库 `entryDate` = 候选 `tradeDate`（截图日期列提取，成交日优先）；**v3.32 二修（用户拍板）**：截图归集候选（source=image）**无 `tradeDate` → 禁止落库**（计入 skipped、候选保留、failures 提示「缺少成交日期」）——不再回退确认当天，用户补日期（`PUT /trade-log/date`）后再次确认；文字归集（source=text）无日期仍回退确认当天（当日口语语义）。**B6-5（2026-08-23，P0-1 延伸）**：落库失败的候选（SELL 超持仓等）与不完整候选**回写保留**（不静默清空），用户可补全/修正/丢弃后再次确认。**响应**：`{"confirmed":2,"failed":1,"skipped":1,"duplicated":1,"ledgerOnly":0,"failures":["600519 贵州茅台: 未持有 600519，无法卖出","600206 有研新材: 缺少成交日期（截图未识别到日期列），请补充日期后再确认"],"duplicates":["600536 中国软件: 这笔之前已经记过了（2026-09-15 100 股 @ 32.13），没有重复入账"]}`（confirmed=成功 / failed=失败保留 / skipped=不完整或缺日期保留 / duplicated=与已落库流水同笔而跳过 / **ledgerOnly=命中券商快照锚定、只落流水不改账**（持仓/现金以快照为准） / failures=失败人话明细 / duplicates=重复跳过明细）。
**2026-09-18（P0-交易59）**：命中 `coveredByAnchor` 由**硬拒**改为**降级**（`ledgerOnlyTrade`：流水照落、持仓/现金不动），响应新增 `ledgerOnly` 计数——原实现对当天成交一律 400「已包含在券商快照中」，且锚定日只增不减，用户当天成交**永远补不回来**。
**2026-09-19（对抗审查 P0-1 / P1-5）**：①**同笔指纹纳入成交时间**——「同代码 + 同方向 + 同价 + 同量 + 同一天」的分单（生产实据：000831 两笔各 200 股 @53.300，10:03:44 / 10:04:09）只能靠成交时间区分；指纹不带时间时第 2 笔会命中第 1 笔刚落的流水、被判「这笔之前已经记过了」跳过（**卖 400 股只记 200 股**）。规则：双方都有时间且不等 → 不是同一笔；任一方无时间 → 退回原指纹（重传同图仍去重）。②**方向白名单**：候选 `direction` 非 `BUY`/`SELL`（旧脏数据或旁路写入）→ 计入 `skipped` 并保留候选，不再静默按 `BUY` 入账（方向反转会让持仓差 2× 股数）。**v3.68 防重复入账（2026-09-15 生产事故治本）**：同一张成交截图反复提交、每确认一次就落一笔 → 同一天同一笔被记多次（实测 600536 记成 1000 股、真实 800 股，现金被扣成 −6093.97）。现在确认前先判「是否已落库」——候选带 `orderId` 时**只认成交编号精确命中**（编号唯一，同价同量的真实分笔不误判）；无 `orderId` 时按**指纹**（同标的+同方向+同价+同量+同成交日）命中 → 跳过并不留候选，计入 `duplicated`。阿呆只归集不落库——用户确认后才写交易模块（建议引擎哲学）。**P2-交易36 治本（v3.54，2026-09-09）**：候选携带 `orderId`（成交编号）/`fee`（手续费，可选；`PUT /trade-log/meta` 在确认前补填），confirm 落库改走带 orderId/fee 的链路（`recordTradeWithOrderId`）——编号与费用透传逐笔流水（幂等去重/对账可用，历史成交页不再「—」）；已落库流水仍缺 → `PUT /trading/trades/{tradeId}/meta` 补填。

### `PUT /api/v1/trading/trade-log/date` — 补写候选成交日期（v3.32，2026-08-27）
> 需 trading 插件（403）。

截图归集候选缺日期被 confirm 拒后，用户补日期 → 更新当日候选 `tradeDate` → 再次确认可正常落库。**body**：`{"id":"cand_20260917_154512345","tradeDate":"2026-08-26"}`（**P1-交易54 收尾（v3.75，2026-09-17）：`id` 为首选**——行级定位，同代码同方向的多笔候选各补各的；旧口径 `{"symbol":"600206","direction":"SELL","tradeDate":"…"}` 仍可用，但它会把**同代码同方向的多笔候选一起补上同一日期**。tradeDate 格式 `yyyy-MM-dd`）。**响应**：`{"updated":true}`；当日无此候选 → 404；`id` 与 `symbol/direction` 都没给、或格式非法 → 400 `{"error":"..."}`。

### `PUT /api/v1/trading/trade-log/meta` — 候选补元信息 / **就地编辑**（P2-交易36 治本，v3.54，2026-09-09；A1-4 就地编辑 2026-09-18）
> 需 trading 插件（403）。

截图入账/手动确认成交缺 orderId/fee 时，在**确认前**给当日候选补填；**2026-09-18（RFC 20260918 A1-4）起同一端点兼作候选就地编辑**——改正截图识别错的价格/数量/方向/成交日期（VLM 对这三样都可能出错，原来只能「丢弃 → 重新发截图 → 重新补日期」）。

**body**：`{"id":"cand_20260917_154512345","price":56.67,"volume":100,"direction":"BUY","tradeDate":"2026-09-18","orderId":"1234567890","fee":5.5}`
- `id`（**首选**，P1-交易54 收尾 v3.75）——行级定位，同代码同方向的多笔各改各的。**就地编辑必须带 `id`**（否则 400：无行级定位会误改同代码同向的多笔）
- `price` / `volume` / `direction` / `tradeDate`（A1-4，均可选，只覆盖非空值）；带 `id` 时 `direction` 的语义 = **改成这个方向**；服务端改完**重算 `complete`**（补全后可确认，不再被跳过）
- `orderId` / `fee`（P2-交易36，均可选，只覆盖非空值）
- 旧口径 `{"symbol":"600206","direction":"SELL",…}` 仍可用，但**只支持补 orderId/fee**，不支持就地编辑

**响应**：`{"updated":true}`；当日无此候选/无可写新值 → `{"updated":false}`；`id` 与 `symbol/direction` 都没给、或 `price/volume/tradeDate` 非法 → 400。

### `DELETE /api/v1/trading/trade-log` — 丢弃一条保留候选（B6-5，2026-08-23，P1-交易18）
> 需 trading 插件（403）。

丢弃失败/不完整保留的「钉子户」候选（15:05 推送反复提醒同一笔时的出口）。**query**：`id`（**首选**——候选行标识，行级精确定位）、`symbol`（可选，旧口径）、`direction`（可选，BUY/SELL）。**响应**：`{"discarded":true}`；当日无此候选 → 404。**P1-交易54（2026-09-17）**：候选新增 `id` 字段（`GET /trade-log` 响应可见），删除**优先按 `id`**——旧口径 `symbol+direction` 是**粗粒度**的：同标的同方向的多笔候选（生产实据：当日三笔亨通光电买入各 100 股）会被**一起删掉**，`symbol` 传空更会删光该方向全部候选；该口径仅为兼容旧客户端保留。旧候选文件无 `id` → 服务端按内容**派生稳定 id**（同一文件反复读出的结果一致，否则前端拿着 id 删不掉）。

### `POST /api/v1/trading/screenshots` — 截图入账（2026-08-26，交易闭环第一环）
> 需 trading 插件（403）。

券商「当日委托/历史成交」截图（1-3 张）→ VLM 识别 → 归集为当日候选。**与 `POST /records/media` 的关键差异：不建记录、不落原图、不沉淀记忆**——截图入账是交易动作不是记录动作，候选确认落库后即权威数据，不污染 Feed/时间线。

- **multipart**：`files`（可多文件，字段名固定 `files`；每张 ≤ 5MB，超限/非图片/识别失败逐张降级进 `errors`）
- **响应**：`{"total":2,"processed":2,"candidates":[{"symbol":"002428","name":"云南锗业","direction":"SELL","price":93.48,"volume":100,"tradeDate":"2026-08-26","source":"image","complete":true}],"errors":[],"dropped":["第 1 张 · 第 1 行「…」：状态「已报」不是已成/部成（未成交的单子没有记）"],"droppedCount":1}`
  - `total` 提交张数 / `processed` 成功识别张数 / `candidates` 当日全部候选（跨图 sameTrade ±10% 自动去重，含本次新增）/ `errors` 逐张失败原因（空 = 全成功）
  - `dropped`（v3.65，P2-交易44）：被表格规则**没有记账**的行（人话：原文 + 原因——未成交状态 / 新股申购 / 占位代码 / 0 价），**仅在非空时出现**；`droppedCount` 为其条数。原实现只写 `log.debug`，用户看到「识别出 2 笔」不知道同一张截图里还有行被丢
  - `tradeDate`（2026-08-27）：截图表格「日期」列提取的成交日期；确认入账按此日期落 entryDate
- **校验失败**（空/超 3 张）→ 400 `{"error":"请选择截图"}` 等

### `DELETE /api/v1/trading/pushes/{id}` — 删除单条推送（B10-1，2026-08-23，P1-推送2）
> 需 trading 插件（403）。

删除当日一条推送事件（app 左滑删 / web 忽略按钮持久化——刷新/重启不再复活）。**响应**：`{"dismissed":true}`；当日无此事件 → 404（前端幂等成功）。

> **推送定时消失（RFC 20260825 §7，契约同步）**：`pushes/{date}.json` 记录新增 `expiresAt`（ISO LocalDateTime）——行情类（stop-loss / near-stop-loss / loss / gain / break-cost / market / session / buy-point）落盘时设为**次日 09:30**（当天收盘后晚上仍可看，次日开盘前自动清，防「收盘后看 App 推送没了」的误判），汇总类（每日操作总结 / 复盘）设为**次日 23:59**；Feed 读取侧过滤已过期条目（用户无需手动删时效推送）。旧数据无 `expiresAt` → 按类型默认保留期，不误删。

### `POST /api/v1/trading/imports/cash` — 资金股份查询导入（现金 + 精确成本）
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。

**body（v3.61）**：`{"content":"…转码后文本…","snapshotDate":"2026-09-09"}`——`snapshotDate` 可选（`yyyy-MM-dd`，兼容 `yyyyMMdd`），语义 = **快照自身日期**（通达信「资金股份查询」文件名里的日期）：该日期同时作为**账户快照日期**与**现金锚定日**（`trading/snapshot-anchor.json` 的 `cashImport`）；不传则退回导入日（今天）。补导几天前的资金文件必须传它，否则现金锚定日偏晚会把快照日之后、锚定日之前的现金变动误判为已包含。格式错 → 400 人话。

**响应（v3.65）**：`{"cash":1381.93,"assets":79231.93,"updatedCost":3,"unparsedRows":0}`——`unparsedRows`（P2-交易45）= 明细里**没看懂的行数**（>0 → 这些持仓的「精确成本」本次没更新；不阻塞导入，但不静默）。**同时收紧首行校验**：正则命中首行但「余额/可用/可取/参考市值/资产/盈亏」任一项读不成数字（如 `余额:1.2.3`）→ **400 人话拒绝导入**（原来 `null` 会一路写进账户快照，资产/现金变空且无提示）。

### `POST /api/v1/trading/imports/save` — 导入文件上传留存（通达信导出，2026-08-16）

**multipart**：`file`（通达信导出 txt，GBK/UTF-8 均可）

- **留存**：原始文件存 `data/{userId}/trading/imports/{yyyy-MM}/{ts}_{filename}`（可追溯）
- **转码**：GBK 自动转 UTF-8（UTF-8 严格解码失败按 GBK）
- **响应**：`{"path":"trading/imports/...","content":"转码后的 UTF-8 文本"}`——前端填充解析导入
- 需 trading 插件（403）。

### `POST /api/v1/trading/positions/import` — 持仓初始化导入（通达信导出 → 持仓快照，2026-08-16）

**query**：`replace`（可选，默认 `false`）——2026-08-18 确认批次：`replace=true` = **全量覆盖**（以文件为准，导入后移除文件里不存在的持仓，含 0 股残留；web 通达信持仓导入默认传 true）
**query（v3.61）**：`snapshotDate`（可选，`yyyy-MM-dd`，兼容 `yyyyMMdd`）——**快照自身日期**（通达信「持仓股」文件名里的日期）。`replace=true` 时该日期作为**券商快照锚定日**并记录**持仓基线**（`trading/snapshot-anchor.json` 的 `holdings`，对账闸门 `GET /trading/integrity` 用它算 `derived`）；不传则退回导入日（今天）。补导几天前的快照文件必须传它——否则锚定日被写成今天，锚定日之后、快照之前的真实成交会被误判为「已含在快照内」而丢掉持仓/现金增量。格式错 → 400 人话。
**query（v3.62）**：`todayPnl`（可选，数字，两位小数）——**券商「持仓股」导出「当日盈亏」列的全表之和**（🔴 **含 0 股行**：当日清仓标的的已实现盈亏也在这一列里，漏掉 0 股行会少算）。这是账户卡「当日盈亏」的**权威口径**（2026-09-13 用户实测：该列此前从未被读，系统只能退回自算，而自算值会错——真值 −1759.00 被自算成 −2837.00）。后端**三闸才写**：①值为空（文件没这一列 / 有行取不到数 → 前端不传）**不写**，保留账户旧值；②无账户快照（未导过资金股份）**不写**；③**`snapshotDate` ≠ 账户快照日 `snapshotDate` 不写**——「当日」必须同日，否则就是把 A 日的当日盈亏贴到 B 日的快照上。写前若账户已有同日值且不同 → WARN 记录两个口径与差值（以券商为准）。传非数字 → 400 人话（不静默忽略）。**当日盈亏三源优先级**：券商文件（权威，限同日）> 收盘 15:05 精确计算（口径①：当日已实现 + 持仓日浮动 + 当日股息/红利税）> 保留旧值；任何情况下**不落零**。

**body**（数组，可空）：
```json
[{"symbol":"600519","name":"贵州茅台","quantity":100,"avgCost":1400,
  "stopLossPrice":1350,"buyPoint":"B1","role":"基石","entryDate":"2026-08-01"}]
```
- `symbol`/`quantity`/`avgCost` 必填；`name` 缺失时后端按代码行情补全
- `stopLossPrice`/`buyPoint`/`role`/`entryDate` 可选——通达信导出无止损/买点，导入后**必须补设**（R68）建议引擎才按纪律判定
- **`avgCost` 允许 ≤ 0（2026-09-13 负成本批，用户实测事故）**：反复做 T / 分红把持仓成本摊到 0 以下是**真实且合法**的，通达信持仓导出就是这么记的（实测 600601 方正科技 成本 −5.078 / 100 股，该行盈亏 1990.77 / 市值 1483.00 = +134%，正说明成本在 0 以下）。**只有 `avgCost` 缺失（null）才 400**；原实现 `signum() <= 0` 一律抛异常，会让整批导入失败（一只都进不去）。`quantity` 仍须 > 0（0 股是券商文件里保留的已清空标的，不是持仓，前端应先行过滤为「已清空跳过」）
- ⚠️ **前端调用方（含本仓 adai-web）在 `replace=true` 下必须先确认「文件每一行都解析成功」再提交**：全量覆盖语义下漏一行 = 那只持仓被**静默删除**。adai-web 已按此 **fail-closed**（有看不懂的行 → 不发请求 + 弹窗逐行摆原因，2026-09-13 负成本批）

**响应**：`{"imported":2,"missingStopLoss":["600519 贵州茅台",...]}`（未设止损列表，前端提示补设）。按 symbol upsert（已存在更新，不存在新增）。需 trading 插件（403）。

### `PUT /api/v1/trading/positions/{symbol}` — 更新持仓元信息（web 持仓编辑，2026-08-17 补端点）

**body**（只带非空字段）：`{"role":"防守","stopLossPrice":1302}`（role/止损位可选）

- 之前前端与测试在调但后端从未实现（编辑一直 404）——2026-08-17 补上；`targetPrice` 后端 Position 无字段落盘（前端目标价编辑是既有无效功能，另记 P3）
- **响应**：更新后持仓对象（symbol/name/quantity/avgCost/stopLossPrice/buyPoint/role）；symbol 不存在 404；止损位非数字 400
- 需 trading 插件（403）。

### `PUT /api/v1/trading/principal` — 设置本金（累计净投入，2026-08-18）

**body**：`{"amount":150000}`（必须 > 0）

- **背景**：总盈亏 = 资产 − 本金；资金查询导入/转账推导都不覆盖本金 → 新建账号 principal=0 时总盈亏失真（本批次实测发现）
- 本金是**历史累计净投入**，不是当前资金变动——**只改 principal 字段，不动现金/资产/市值**（转账会动现金，不能用来初始化本金）；web 资金区「本金」按钮入口
- **响应**：更新后账户快照（含 principal）；amount 缺失/≤0 → 400
- 需 trading 插件（403）。

---

### `POST /api/v1/trading/trades/parse` — 解析一句话交易（RFC 20260815 通道 A）

把自然语言（「买了 1000 股京东方 @5.2」）结构化为交易入参，供前端确认卡回显。**只解析不落库**——写入仍走 `POST /trades`（正确性由确认步拦截）。

**Request Body**

```json
{ "text": "买了 1000 股京东方 @5.2" }
```

**Response**

```json
{
  "matched": true,
  "symbol": "000725",
  "name": "京东方A",
  "direction": "BUY",
  "price": 5.2,
  "volume": 1000,
  "stopLossPrice": 4.9,
  "buyPoint": "B1",
  "targetPrice": null,
  "reason": null
}
```

> `matched=false` 时其余字段为 null（前端转精确表单）。LLM 结构化优先，失败降级正则兜底。需 trading 插件（403）。

---

### `POST /api/v1/trading/advice` — 持仓解读（建议引擎机制，已建能力——模块定位见 RFC 20260902 交易记忆）

读用户持仓 + 实时行情 + 只读 `os/trading-engine/knowledge/context/rules.md` 与 `strategy.md`，将止损规则（R66-R80）与仓位规则（R81-R95）作为决策硬约束注入 LLM，结构化生成逐票解读（suggestion/reason/rules 必须引用规则号）。**输出是解读不是指令**，本端点不做任何执行动作。

**Request**：仅 `X-User-Id` header（body 空）

**Response**

```json
{
  "advice": [
    {
      "symbol": "000725",
      "name": "京东方A",
      "position_percent": 3.70,
      "suggestion": "reduce",
      "reason": "自然语言理由，必须引用规则号（如 R81）",
      "rules": ["R81", "R66"],
      "evidence": {
        "history": "你过去 6 次在「≤1 天」卖出，5 次盈利、平均 +2.1%",
        "numbers": "现价 5.46 · 持仓占比 3.70% · 止损 5.08",
        "ruleTexts": ["R66 只输一根K线：止损设在进场K线最低价下方几个价位（或1%），收盘跌破就走。"],
        "basisId": null
      }
    }
  ],
  "summary": "持仓总览一句话"
}
```

> `suggestion` 取值：buy / hold / reduce / clear。`position_percent` 后端按市值占比计算（确定性）。LLM 失败时降级返回基础数据（无建议字段），不抛错。需 trading 插件（403）。空仓返回空 advice。
>
> **`evidence`（v3.81，2026-09-22，RFC 20260922 A 批 A4）——四要素铁证**：后端在出口**统一补齐**（成功与降级同一口径、只读、不改建议本身），**每个字段都可为 null——补不出就留空，绝不编造**：
> - `history` = **① 本人历史操作统计**（清仓回合分桶；**样本 < 5 → null**，此时前端/推送必须说「样本还不够」而不是引用）
> - `numbers` = **② 当时的数字证据链**（现价 / 持仓占比 / 止损位）
> - `ruleTexts` = **③ 规则依据原文**（`rules` 里能查到原文的那些，**逐字**来自 `rules.md`；查不到的不进列表）
> - `basisId` = **④ 留痕 id**（advice-history 里那条，供日后回看追责）——**本批未填，A3 待做**
> - `evidence` 整体为 `null` = 没有持仓视图（如 LLM 漏票后的纯占位行）

---

### `GET /api/v1/trading/advice-history` — 建议留痕查询（RFC 20260905 B①，v3.47）

「阿呆当时说 X」的历史依据——每次建议生成出口逐票落盘（`data/{userId}/trading/advice-history/{yyyy-MM}.json`，成功 source=manual-advice、LLM 降级 source=degraded 都落，诚实留史）。需 trading 插件（403）。

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `symbol` | String | 否 | 标的代码；填 → 返回该标的最远 `days` 天内建议（倒序）；空 → 返回最近 `days` 天全部 |
| `days` | Integer | 否 | 回看天数，默认 30，**上限 90**（⚠️13：仓储只保留最近 3 个月建议，超 90 天会静默截断故封顶对齐） |

**Response** `200` — AdviceEntry 数组（倒序）

```json
[{"id":"adv_...","date":"2026-09-03","symbol":"600584","name":"长电科技",
  "suggestion":"clear","reason":"跌破止损位，按 R66 只输一根K线","rules":["R66"],
  "hardVerdict":true,"positionPercent":20.0,"source":"manual-advice","createdAt":"2026-09-03T14:50:00"}]
```

> 落盘只在建议出口（手动 /trading/advice 或未来推送逐票）；输出是记忆对照素材，非执行指令。

### `GET /api/v1/trading/profile` — 个人交易画像（RFC 20260905 A 层，v3.47）

客观统计（系统从清仓史实时推导）+ 建议遵守率 + 主观层原文（profile.md）。需 trading 插件（403）。

**Response** `200`

```json
{"stats":{"soldCount":168,"winRatePct":33.3,"medianPnlPct":-1.54,
  "disciplineViolationCount":91,"disciplineViolationRatePct":54.2,"avgHoldDays":12,
  "verdictBreakdown":{"盈利了结":57,"扛单超 5%":37,"短持仓亏损":54}},
 "objectiveText":"## 你的交易画像（客观统计…）",
 "adviceAdherence":{"withAdviceCount":12,"followedCount":8,"followRatePct":66.7},
 "subjective":"S1 追高的手 > 抄底的手（profile.md 原文，未建画像 → null）"}
```

> 红线：数字系统算（不靠 LLM 编造）、主语是你（合规）。

### `PUT /api/v1/trading/profile` — 保存画像主观层（RFC 20260905 A 层，v3.47）

**Body**

```json
{"content": "S1 追高…\nS3 下跌摊平…（自由文本，profile.md 原文）"}
```

**Response** `200` `{"updated":true}`；`content` 缺失 → 400。落 `data/{userId}/trading/profile.md`。

**v3.65（P2-交易47）**：`content` 为**空串或纯空白**同样 400（人话「画像内容不能为空——想保留原内容就别提交空白；确实要清空请直接说明」）。此前只判 `null`，空字符串可通过并把 `profile.md` **整文件覆盖清空**（画像主观层是用户手写/AI 回填的行为签名与情绪记忆，清空无从恢复）。

### `GET /api/v1/trading/sold/{symbol}/psychology-questions` — 清仓情绪提问（RFC 20260905 P2，v3.47）

按该笔清仓的交易结构（盈亏/持仓天数/买卖次数/verdict）确定性生成 3~5 个「当时为什么」提问（不耗 LLM）。需 trading 插件（403）。symbol 不在清仓史 → 404。

**Response** `200`

```json
{"symbol":"600584",
 "questions":[{"key":"deep_loss_trigger","question":"亏到 -29.22% 才走——是什么最终触发了离场？"}]}
```

### `POST /api/v1/trading/sold/{symbol}/psychology/answer` — 清仓情绪回答（RFC 20260905 P2，v3.47）

**Body** `{"psychology": "扛到受不了才割的…"}`——回答回填 `sold.psychology`（追加式保留已有）+ 沉淀 profile.md 主观层（已有画像时）。需 trading 插件（403）；空 psychology → 400；symbol 不在清仓史 → 404。

**Response** `200` `{"updated":true}`

---

### `POST /api/v1/trading/review` — 生成交易复盘

AI 基于当日交易记录 + 持仓变化生成复盘笔记，输出写入 `data/trading/reviews/YYYY-MM-DD_review.md`。需 trading 插件（403，W-P2-14 2026-08-17 补门控契约）。

> **2026-09-07 v3.52 提交式改造**：AI 生成实测 77~176s，远超客户端超时（App 15s / Web 120s）——原同步等待必然前端先断、「点击没反应」而复盘实际已生成。现改为**提交即返回 + 后台生成 + 同日去重**：
> - `status=exists`：该日期复盘已存在（不重复生成烧 AI），前端直接 `GET` 展示；
> - `status=running`：同 user+date 正在生成中（连点/双端并发只跑一次 AI），继续轮询 `GET`；
> - `status=pending`：已受理，后台执行器生成中，轮询 `GET /trading/review?date=` 直到 200（404=未就绪）；生成失败只记日志、不落半成品，前端轮询超时（4 分钟）提示稍后重试。

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `date` | String | 否 | 复盘日期 `yyyy-MM-dd`，默认当天 |

**Response**

```json
{
  "date": "2026-07-25",
  "status": "pending"
}
```

### `GET /api/v1/trading/review` — 查询复盘笔记
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。

**Response** `200` 复盘正文 `{"date","content"}`；**未生成/生成中 → 404**（2026-09-07 起前端以 404 表示「复盘未就绪」，配合 POST 提交式轮询）。

### `GET /api/v1/trading/reviews` — 列出所有复盘日期
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。

### `GET /api/v1/trading/has-activity` — 检测交易活动
> **⚠️ 唯一例外（2026-08-23 修正）**：本端点**不做 trading 插件门控**（代码无 `requireTradingPlugin`）——产品路径只读（app 复盘横幅），其余 33 个交易端点均 403 门控（v3.21 保留产品路径，此处显式标注防误读）。
>
> **口径（2026-08-26 复盘卡点，用户拍板）**：`hasActivity = 当日真实成交数 > 0`（`getDailyTradeSummary().count`，成交流水 `data/{userId}/trading/trades/{yyyy-MM}.json` 按 entryDate 统计）——**废除旧「关键词扫描对话记录」**（聊到"买/仓/股"即误报、导入成交后记录文本不带关键词反而不报）。语义：复盘生成与「今日有成交」绑定，无成交 → 前端横幅不出现 / 复盘按钮引导先截图入账。

---

## 6. 知识反哺

### `POST /api/v1/trading/reviews/{date}/promote` — 提升复盘为入库候选

将复盘笔记中的经验写入 `os/trading-engine/99-inbox/`，供用户在 trading-engine 工作焦点下审核入库。

**Path Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `date` | String | 是 | 复盘日期 `yyyy-MM-dd` |

**Request Body**

```json
{
  "note": "R33 这次 B1 入场很标准",
  "sections": ["3. 与系统规则对照", "4. 今日教训与心得"]
}
```

**Response**

```json
{
  "status": "ok",
  "path": "/path/to/os/trading-engine/99-inbox/2026-07-25_交易复盘.md",
  "message": "已写入入库候选。该内容不会自动进入 AI 上下文：请在交易知识库工作流（os/trading-engine）审核后归入正式目录，并在收敛时重建 knowledge/context。"
}
```

> **#178（2026-08-12）**：`message` 字段提示入库候选不会自动融入 AI context——promote 只写入 `99-inbox/`，融合需在 trading-engine 工作流收敛重建 `knowledge/context/` 后由 `TradingKnowledgeSource` 注入。`path` 文件名遵循 #211 约定 `YYYY-MM-DD_主题.md`。
>
> **#129（2026-08-12）**：前端入口已补——adai-app / adai-web 交易页复盘弹窗新增「反哺入库」按钮（`POST` body 传 `{}`，note/sections 可空），成功后展示 `message` 提示。知识反哺闭环前后端打通。
>
> **插件门控（RFC 20260814，v3.18）**：promote 写入共享 os/ 知识库 → 仅启用 trading 插件的用户可用；未启用 → `403`（`{"error":"trading 插件未启用，无法反哺知识"}`）。

### `POST /api/v1/trading/cases` — 标注完美买点案例（第四阶段环 1-2，2026-08-30）
> 需 trading 插件（403）。蓝图 trading-case-library-design.md + RFC 20260830-trading-perfect-case-library.md。

一句话标注 → 自动拉「前 60 + 后 30 交易日」日 K（腾讯主源/东财兜底，`klineRange`）→ 特征画像 + 后验窗口 → 落盘 `data/{userId}/trading/cases/{buyDate}_{symbol}.json`（File First，JSON）。

**Request Body**

```json
{
  "symbol": "000725",
  "buyDate": "2026-08-03",
  "buyType": "B1",
  "description": "回踩 60 日线 + 地量，次日大阳启动",
  "name": "京东方A",
  "labels": ["缩量回踩"]
}
```

`buyType`：`B1`/`B2` 正样本（完美买点）；`FAILED` 负样本（失败案例，2026-08-31 双轨方案——
形态像买点但走坏，不参与正样本画像/匹配，单独成「失败画像」供 match 风险警示）；
其他值 → `unknown`。失败案例建议 `description` 填失败原因（如「破位不收回」）。

**Response（200）**：完整案例（含特征画像 + 后验），`verify` 字段 `+5dReturnPct`/`+10dReturnPct`/`maxDrawdownAfterBuyPct` 缺数据为 `null`；案例库 ≥5 时附 `consensusCheck`（**同类型**画像逐维偏离校验，2026-08-31 双轨——标注 B1 对照 B1 画像；FAILED 不做偏离校验）。

**错误**：symbol 非 6 位数字 / buyDate 缺失 → 400（校验）；buyDate 未来 → 400；重复标注（同 symbol+date）→ 400「该案例已标注过」；K 线拉取失败 → 400「无法获取…K 线数据」（不落半成品，fail-visible）。

### `GET /api/v1/trading/cases` — 案例列表
> 需 trading 插件（403）。

返回全部案例（buyDate 倒序），每项含 id/symbol/name/buyDate/buyType/features/verify/aiInsight。

### `GET /api/v1/trading/cases/{caseId}` — 案例详情（可选 K 线窗口）
> 需 trading 插件（403）。

- `caseId` 格式 `{buyDate}_{symbol}`（如 `2026-08-03_000725`）
- `?kline=true` → 响应附 `kline`（90 根窗口日 K，前端画图重放；拉取失败 → 空数组）
- 不存在 → 400「案例不存在」

### `POST /api/v1/trading/cases/import` — 批量导入完美案例笔记（2026-08-31）
> 需 trading 插件（403）。

粘贴用户完美案例笔记（飞书/B1/B2 格式：`## 名称【缩写】` + 日期行 / `## 名称[缩写_日期]`）→
解析（名称/日期，兼容飞书转义 `\-`、8 位日期、区间取首、类型分组跳过）→ 名称转代码
（**本地全 A 名称表精确匹配** `data/market/names.json` → 东财 suggest 兜底）→ 逐条标注
（含前复权/特征/共识校验）。北交所（92 开头）本地无行情 → 明确失败。

**Response（200）**：逐条结果 `[{name, symbol, buyDate, status: ok|skipped|failed, error, consensusCheck}]`。
- `skipped`：缺日期 / 已存在（幂等）
- `failed`：名称未匹配（含错字如「百普塞斯」应为「百普赛斯」）/ 北交所 / 无交易数据

### `POST /api/v1/trading/cases/match` — 判定当下：双轨形态匹配（环 4，2026-08-30 → 2026-08-31 双轨）
> 需 trading 插件（403）。蓝图 trading-case-library-design.md §六 + trading-case-data-usage.md §3——**核心价值**：案例是手段，判定当下是价值。

当前标的形态（拉 60 日 K → 特征画像 → 归一化）与案例库加权欧氏相似度 Top 5（默认权重：回撤 0.25/量比 0.20/KDJ 0.15/距 60 日线 0.15/MACD 0.10/盘整 0.10/信号 0.05；权重和=1 → 相似度 = (1−距离)×100%；**可配置** `adai.trading.case.sim-weights`）。

**2026-08-31 双轨升级**：`matches`（全量 Top5，向后兼容）+ **双轨共识画像**（B1/B2 各自 25-75 分位区间互不稀释）+ **类型判定**（命中多的一轨）+ **失败画像警示**（与负样本最高相似度）。

**Request Body**

```json
{ "symbol": "000725", "date": "2026-08-03" }
```

`date` 可空 = 最近交易日（指定日无数据 → 回落最近交易日）。

**Response（200）**

```json
{
  "symbol": "000725",
  "matches": [
    { "caseId": "2026-08-03_000725", "symbol": "000725", "name": "京东方A",
      "buyDate": "2026-08-03", "buyType": "B1", "similarityPercent": 92.5,
      "plus5dReturnPct": 18.2, "aiInsightSummary": "缩量回踩黄线获支撑" }
  ],
  "type": "B1",
  "b1": { "hits": 5, "total": 6, "similarity": 82.5 },
  "b2": { "hits": 1, "total": 6, "similarity": 45.0 },
  "failedSimilarity": 78.5,
  "consensus": { "profile": [...], "hits": [...], "hitCount": 5, "total": 6 }
}
```

- `type`：`B1`/`B2`（命中多的一轨）/ `none`（两轨都低或样本不足）
- `b1`/`b2`：各轨 `{hits, total, similarity}`（similarity = 该轨 Top1 相似度）；案例不足 5 → null
- `failedSimilarity`：当前形态 vs 失败画像最高相似度（无负样本 → null；≥70% 前端红色警示）
- `consensus`：全量口径共识（旧前端兼容）
- 负样本恒不参与正样本匹配与画像（防污染参照系）

**语义**：案例相似度是「经验增强」参考信号，**不覆盖规则硬判定**（止损/仓位等仍以规则引擎为准）；案例库为空 → `matches: []`（静默，不影响规则判定）；K 线拉取失败 → 400。

### `POST /api/v1/trading/cases/{caseId}/insight` — 生成案例 AI 理解（环 3，2026-08-30）
> 需 trading 插件（403）。蓝图 trading-case-library-design.md §四环 3。

LLM 读案例特征画像 + K 线统计 → 结构化「为什么这是完美买点」→ aiInsight 落盘（reviewed=false 待人工确认）。

**Response（200）**：更新后的完整案例（`aiInsight` 含 `summary`/`keyFeatures`/`confidence`）。

**错误**：案例不存在 → 400；LLM 失败/输出不可解析 → 400「AI 理解生成失败」（fail-visible，不落半成品）。

### `DELETE /api/v1/trading/cases/{caseId}` — 删除案例
> 需 trading 插件（403）。

删除案例文件 + 清单条目；不存在 → 400「案例不存在」。响应 `{"deleted":true,"caseId":"..."}`。

### `GET /api/v1/admin/trading/knowledge/conflicts` — 检测规则矛盾（需登录 + role=admin，REVIEW #178）

从 `os/trading-engine/knowledge/context/rules.md` 解析真实规则，与当前持仓状态对比，标记可能违反的规则（规则名/描述取自真实规则内容，非硬编码）。

**Response**

```json
{
  "conflicts": [
    {
      "rule": "R119 空仓也是交易策略",
      "description": "当前无持仓。规则：仓位0到100，0也是交易。确认当前空仓是否符合择时信号（活跃市值绿柱下降期空仓 = 正确执行 R4）。",
      "category": "择时"
    },
    {
      "rule": "R96 四不原则",
      "description": "当前仅持有 1 个标的（xxx）。规则：不追 → 不动 → 不慌 → 不乱摸。若未分仓，检查是否违反四不原则。",
      "category": "仓位"
    }
  ]
}
```

> 无持仓 → 空仓检查（R119/R4）；仅持 1 个标的 → 单吊检查（R96 四不原则）。rules.md 不可读时返回空 conflicts。

---

## 7. 简报

简报中会自动检测当日是否有交易活动，若有则提醒用户生成复盘。

### `GET /api/v1/brief` — 今日简报

> 内容为 AI 生成的问候 + 当日要点：**首行问候、每行以 emoji 开头、最多 5 行**（后端 `truncateLines(…, 5)` + prompt「max 5 lines」）。前端直接渲染，无额外前缀（去绿点，避免与 AI emoji 双重前缀冲突）。AI 失败降级为按时段问候（🌙/☀️/🌤️/🌇/✨）+ 💬 引导行。
>
> ⚠️ **此端点会触发 AI 生成（实测 7~27s）**：主页首屏请改调 `GET /api/v1/brief/cached`（不触发 AI），缓存过期（空串）时再异步调本端点补全——避免首页加载被 AI 阻塞（v3.24）。

**Response**

```json
{
  "content": "小王晚上好！\n🍜 刚聊过饿了想吃啥\n💧 睡前记得喝水哦"
}
```

### `GET /api/v1/brief/cached` — 缓存 Brief（不触发 AI）

> v3.24：只返回 5 分钟内的缓存 Brief，**不触发 AI 生成**。缓存过期或从未生成时 `content` 为空串。主页首屏用它并行加载，空串时再异步调 `GET /api/v1/brief` 补全。

**Response**

```json
{
  "content": "小王晚上好！\n🍜 刚聊过饿了想吃啥"
}
```

> `content` 为空串表示缓存过期（前端应后台补 AI 生成，不阻塞首屏渲染）。

---

## 8. 时间线

### `GET /api/v1/timeline` — 时间线查询

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | String | 否 | 筛选类型 |
| `limit` | int | 否 | 条数限制，默认 50 |

**Response**

```json
[
  {
    "id": "rec_...",
    "type": "note",
    "title": "今天买了立昂微",
    "tags": ["投资", "半导体"],
    "dateTime": "2026-07-18T14:30:00",
    "mediaPath": null
  },
  {
    "id": "rec_...",
    "type": "image",
    "title": "图片摘要",
    "tags": ["photo"],
    "dateTime": "2026-08-03T09:15:00",
    "mediaPath": "records/2026/08/media/rec_20260803_091500123.jpg"
  }
]
```

> `mediaPath`：媒体记录才有——`type=image`（图片记录原图）与 `type=image_qa`（S-2 展示层聚合：图文事件缩略图取引用首图），指向媒体文件相对路径（GET `/api/v1/records/media/{id}` 取文件）；其余类型为 `null`。
>
> `mediaPaths`（v3.82，2026-09-22，图文一体）：本条目引用的**全部**图（按上传顺序；一次投递多图时长度 > 1）——前端一卡并列展示；无图为 `null`，`mediaPath` 恒为首图（**旧前端不读新字段也能正常显示首图**）。**被主记录 `mediaIds` 引用的薄图片附件不单独成条**（一次投递 = 一条时间线条目；否则时间线会冒出 N 条「图片附件」这种系统视角条目，违反第一原则）。

---

## 9. 记忆

### `GET /api/v1/memory` — 按日期查询记忆

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `date` | String | 否 | `yyyy-MM-dd`，默认当天 |

**Response**：`Memory[]`

### `GET /api/v1/memory/record/{recordId}` — 按记录 ID 查询

**Response**：`Memory` 或 `404`

```json
{
  "id": "mem_20260718_143000",
  "recordId": "rec_20260718_143000",
  "kind": "insight",
  "summary": "AI摘要",
  "tags": ["标签"],
  "sentiment": "positive",
  "actionable": false,
  "suggestion": null,
  "createdAt": "2026-07-18T14:30:00",
  "topic": null,
  "superseded": false,
  "evolvedTo": null,
  "doneAt": null,
  "lastConfirmed": null
}
```

### `POST /api/v1/admin/memory/rebuild` — 重建记忆（需登录 + role=admin，REVIEW #178）

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `date` | String | 否 | 重建指定日期；不传则重建全部 |

**Response**

```json
{
  "success": 63,
  "failed": 0,
  "total": 63,
  "errors": []
}
```

### `GET /api/v1/memory/dates` — 查询所有有记忆的日期

**Response**

```json
["2026-07-18", "2026-07-20", "2026-07-23"]
```

### `GET /api/v1/memory/count` — 记忆总数

**Response**

```json
{ "count": 15 }
```

### `GET /api/v1/memory/insights` — 阿呆对你的了解（2026-09-16「第一次见面」批）

把长期沉淀在 memory 里的 patterns / preferences 聚合出来，供「档案」页的「阿呆对你的了解」区块展示。
这些观察一直由 AI 从日常对话里自动写入（`MemoryService.findAllPatterns/findAllPreferences`，
带时间衰减 × 置信度排序），此前没有任何前端出口（REVIEW P2-认知3）。

两类合并后再按置信度降序；`observedSince` 为最早一条记忆的日期（没有记忆时为 `null`）。

**Response**

```json
{
  "total": 3,
  "patternCount": 2,
  "preferenceCount": 1,
  "observedSince": "2026-07-22",
  "insights": [
    { "kind": "pattern", "content": "用户常把科幻概念和现实人物类比推演", "confidence": 0.9 },
    { "kind": "preference", "content": "对《三体》战略思想有持续兴趣", "confidence": 0.85 },
    { "kind": "pattern", "content": "习惯在深夜记录想法", "confidence": 0.6 }
  ]
}
```

| 字段 | 类型 | 说明 |
|:-----|:-----|:-----|
| `kind` | String | `pattern`（行为模式）/ `preference`（明确偏好）|
| `confidence` | Number | 0~1 置信度（已含时间衰减）|

> 用户在档案页点「✓ 对」→ 前端把该条写回 `identity.preferences`（值 `"已确认"`），
> 走既有 `PUT /api/v1/identity`，**不新增写入端点**。

### `PATCH /api/v1/memory/{id}/done` — 标记行动类记忆为已完成

记忆进化 Phase 3（Reality→Knowledge→Action→Reality 闭环）：将 actionable 记忆标记为已完成（`actionable=false` + 记录完成时间）。完成后不再出现在"待行动事项"与 Feed 待办提醒。

**Path Parameters**

| 参数 | 类型 | 说明 |
|:-----|:-----|:-----|
| `id` | String | 记忆 id（`mem_xxx`）|

**Response**

```json
{ "success": true }
```

- `404 Not Found` — 记忆不存在

### `PATCH /api/v1/memory/{id}` — 手动修正记忆（用户端，P-role-02）

个人记忆修正归用户端（adai-app 记忆页「修正」）。走 `X-User-Id` 用户隔离（**非** admin 鉴权）。

**Request Body**（任一字段缺省保持原值）

```json
{ "kind": "fact", "summary": "修正后的内容", "tags": ["生活"], "actionable": true }
```

**Response**：`{"success": true}`；找不到 → 404

---

### `PATCH /api/v1/admin/memory/{id}` — 手动修正记忆（管理端，需登录 + role=admin，REVIEW #178）

adai-admin 数据管理：更新记忆的 kind/summary/tags/actionable/suggestion。任一字段缺省表示保持原值。

**Request Body**

```json
{
  "kind": "insight",
  "summary": "修正后的摘要",
  "tags": ["半导体", "交易"],
  "actionable": false,
  "suggestion": null
}
```

**Response**

```json
{ "success": true }
```

- `404 Not Found` — 记忆不存在

---

## 10. 用户身份（Identity）

### `GET /api/v1/identity` — 读取个人档案

**Response**

```json
{
  "name": "小王",
  "preferences": {"style": "简洁、直接"},
  "rules": {"confirmation": "交易类操作需确认"},
  "tags": ["投资", "半导体"]
}
```

### `PUT /api/v1/identity` — 更新个人档案

**Request Body**：同 GET Response（全量覆盖）

**Response**：更新后的完整 Identity（200 OK）

---

## 11. 标签

### `GET /api/v1/tags` — 获取所有标签统计

**Response**

```json
{
  "tags": [
    {"name": "半导体", "count": 12, "lastAt": "2026-07-22T10:00:00"}
  ],
  "total": 12,
  "updatedAt": "2026-07-22T12:00:00"
}
```

---

## 12. 搜索

### `GET /api/v1/search?q=xxx` — 全文搜索

**Response**

```json
{
  "results": [
    {
      "id": "rec_...",
      "type": "note",
      "title": "今天买了立昂微",
      "content": "...买了立昂微...",
      "tags": ["投资"],
      "dateTime": "2026-07-22T14:30:00"
    }
  ],
  "total": 1
}
```

---

## 13. 待办清单（Kernel builtin，RFC 20260917）

> **定位**：待办是 **Kernel builtin**（内置能力）——人人有、默认开、**无插件门控**（RFC `20260917-todo-kernel-retire-project-plugin.md` §三：core / builtin / optional 三层定位）。
> **形态**：纯清单两态（`OPEN` / `DONE`）+ 可选到期日（`due`）；未完成在上、已完成折叠；**不进 Feed**（`FeedAppService` 不再产出待办条目，Feed 回归纯对话流）。
> **收集**：记录里可执行 → 自动进待办（`RecordToTodoLinker`，保留 `sourceRecordId`）+ 清单页手动加。
> **提醒**：到期当天 **08:00 与 18:00** 各推一次（推送类型 `todo-due`，进 `PushSettings.ALL_TYPES`，默认开、可关）；通知深链 `todo:today` 直接打开待办页。
> **记忆联动（单向）**：建待办**不动记忆**；完成待办 → `markDone(记忆)` 同步；删除待办 → 清记忆 actionable。`#备忘/#想法` 排除判断前移到记忆写入侧（记忆落盘即 `actionable=false`）。
>
> **破坏性变更（breaking，无兼容别名，已装旧 App 需重装）**：原 project 插件的 **6 个端点**已随插件一起删除——`GET /api/v1/project/status`、`GET|POST /api/v1/project/tasks`、`PUT|DELETE /api/v1/project/tasks/{id}`、`GET /api/v1/project/tasks/stats`；新端点一律为 `/api/v1/todos*`（见下）。旧 `data/{userId}/project/` 目录**原样留存、不迁不删**（见 `data-format-freeze.md` §2.11）；`os/project-os/` 知识文件保留在仓库（File First），但**不再注入任何用户上下文**。

### 待办模型

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `id` | String | 自动生成的唯一 ID，格式 `todo_YYYYMMDD_HHmmssSSS`（毫秒，防同秒覆盖）|
| `title` | String | 待办标题（必填，空白 → 400 人话）|
| `status` | String | 两态：`OPEN` / `DONE`（旧文件里的 `DOING` / `CANCELLED` 读作 `OPEN`）|
| `due` | String? | 到期日 `yyyy-MM-dd`（可选；无 → `null`）|
| `sourceRecordId` | String? | 源记录 ID（记录自动转待办时关联 `rec_xxx`；手动建 → `null`）|
| `createdAt` | String | 创建日期 `yyyy-MM-dd` |
| `updatedAt` | String | 更新日期 `yyyy-MM-dd` |

```json
{"id":"todo_20260917_223000123","title":"给妈打个电话","status":"OPEN","due":"2026-09-20","sourceRecordId":null,"createdAt":"2026-09-17","updatedAt":"2026-09-17"}
```

存储：`data/{userId}/todos/YYYY/MM.md`（条目 YAML-ish frontmatter，`due` 与 `sourceRecordId` 为可选行）。

### `GET /api/v1/todos` — 获取待办列表

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `status` | String | 否 | 按状态筛选：`OPEN` / `DONE`（缺省返回全部）|

**Response** — `Todo[]`（新创建的在前）

### `POST /api/v1/todos` — 创建待办

**Request Body**

```json
{ "title": "给妈打个电话", "due": "2026-09-20" }
```

`due` 可选（`yyyy-MM-dd`，空/非法 → 400 人话）；`title` 必填。

**Response** — 完整的 `Todo` 对象（200 OK）

### `PUT /api/v1/todos/{id}` — 更新待办

**Request Body**（所有字段可选）

```json
{ "title": "给妈打个电话", "status": "DONE", "due": "2026-09-20" }
```

- **`null` = 保持原值**（只传要改的字段）；
- **`due: ""` = 清除到期日**；
- `status` 只接受 `OPEN` / `DONE`；`id` 不存在 → 404。
- 完成待办（`status=DONE`）→ 同步 `markDone(记忆)`；删除待办 → 清记忆 actionable（**单向：待办 → 记忆**）。

**Response** — 更新后的完整 `Todo` 对象（200 OK）

### `DELETE /api/v1/todos/{id}` — 删除待办

取消即删除（无 `CANCELLED` 态）。

**Response** — 204 No Content

### `GET /api/v1/todos/stats` — 待办统计

**Response**

```json
{ "total": 10, "open": 4, "done": 6 }
```

---

## 14. 前端卡片交互

### 卡片核心状态

| 字段 | 值 | 含义 |
|:----|:---|:------|
| `mode` | `idle` | 非聊天态 |
| `mode` | `chatting` | 聊天态 |
| `ended` | `true` / `false` | 对话是否已结束 |
| `intent` | `"question"` / `"log"` | 卡片类型 |

### 交互流程

```
list 模式
  ├── record（intent="log"）
  │     └── 点卡片 → 进入聊天模式
  │
  └── card（intent="question"，带 turns）
        └── 点卡片 → 进入聊天模式，可继续提问

chat 模式（全屏）
  ├── 输入 → API → AI 回复 → 继续对话
  └── [end conversation] → POST /conversations/end → 回到 list 模式
```

### 前端 API 映射

| 前端操作 | API 调用 |
|:---------|:---------|
| 新输入（自动意图） | `POST /api/v1/records` `intent: null, cardId: null` |
| 聊天输入 | `POST /api/v1/records` `cardId: "...", intent: "question"` |
| 结束对话 | `POST /api/v1/conversations/end` `cardId: "..."` |
| 加载 Feed | `GET /api/v1/feed` |
| 加载简报 | `GET /api/v1/brief` |
| 生成复盘 | `POST /api/v1/trading/review` |
| 查询复盘 | `GET /api/v1/trading/review?date=` |
| 检测交易活动 | `GET /api/v1/trading/has-activity` |
| 复盘入库候选 | `POST /api/v1/trading/reviews/{date}/promote` |
| 规则冲突检测 | `GET /api/v1/admin/trading/knowledge/conflicts` |
| 待办列表 | `GET /api/v1/todos`（可选 `?status=OPEN\|DONE`） |
| 创建待办 | `POST /api/v1/todos` |
| 更新待办 | `PUT /api/v1/todos/{id}` |
| 删除待办 | `DELETE /api/v1/todos/{id}` |
| 待办统计 | `GET /api/v1/todos/stats` |
| 卡片迁移 | `POST /api/v1/cards/migrate` |
| 卡片清理 | `POST /api/v1/admin/cards/cleanup` |

---

> **编号说明**：原 §15「项目任务」已随 project 插件撤销一并删除（2026-09-17 RFC `20260917-todo-kernel-retire-project-plugin.md`），内容并入 §13 待办清单；为保持历史变更记录中「§16 / §17」等编号引用稳定，本节及以后编号不回退。

## 16. 账号（多账号功能层）

> v1.0.0 多账号：账号由 adai-admin 后台创建（**不做注册**），adai-app / adai-web 前端登录后从可用账号列表选择/切换（`GET /api/v1/accounts/available`，**需登录**，仅返回 enabled 账号——产品端遗留选号）；前端记住上次账号（web 用 localStorage / io 用 shared_preferences，wasm 下 shared_preferences 插件不注册）+ 随时切换。seed 管理员 `admin` 由后端首次启动自动预置（**2026-09-04 账号矩阵**：内置管理员由 `adai` 迁为 `admin`（后台管理专用）；`adai` 降为产品主账号 role=user——个人数据在 `data/adai/` 不变；再建普通受限账号（无插件）供家庭/他人）。
>
> **管理鉴权（REVIEW #178，2026-09-02）**：管理口并入统一登录——本节除 `GET /api/v1/accounts/available`（**仅需登录**，产品端遗留选号）与 `GET /api/v1/me/plugins`（产品端，仅需登录）外，其余端点（账号 CRUD / 插件合并）与 §17 管理端所有端点均要求 `Authorization: Bearer <token>` 且会话账号 **role=admin**（非 admin → 403「仅管理员账号可访问」）；admin 会话保留客户端 `X-User-Id`（控制台跨账号治理浏览）。`X-Admin-Token` 体系已退役删除（`AdminAuthInterceptor` / `adai.security.admin-token` / env `ADAI_ADMIN_TOKEN` / 前端 `ADMIN_TOKEN` 全部移除）。账号响应一律经 AccountView DTO 过滤，**不含 passwordHash**（bcrypt 哈希不下发）。
>
> **插件模型（RFC 20260814 + RFC 20260829；RFC 20260917 撤 project）**：Account 带 `plugins`（**仅 `trading` / `learn` 两个可选插件**——project 插件已撤除，见 §13；`life`（生活）与待办/搜索/时间线/简报等 Kernel builtin 不在插件表、不可关）。启用载体 = 账号 plugins 字段；**历史文件里的残留 `"project"` 由 `PluginRegistry.isValid` 自动过滤（不迁移）**，admin 再写入 `"project"` → 400。seed admin `admin` 默认 `["trading"]`（新环境预置兜底）；新账号默认空。plugins 决定：知识/行情注入、模块显隐（前端 `GET /me/plugins`）、promote 权限、learn 消化端点（403 门控）。**三层定位**（RFC 20260917 §三）：**core 内核**（记录/问答/记忆/上下文/身份/存储，不可关）· **builtin 内置能力**（待办/搜索/时间线/简报，默认开、用户侧可关）· **optional 可选插件**（trading / learn，默认关）。

### `GET /api/v1/me/plugins` — 当前用户启用插件（前端模块显隐）

**需登录**（`Authorization: Bearer`，会话账号 = 当前用户）。返回当前用户启用的插件名列表；账号不存在 → 空列表。adai-app / adai-web 据此显隐插件模块（交易页 / 学习页），基础服务模块（含「待办」）不依赖此端点。

**Request Headers**

- `X-User-Id` — 用户标识（可选，兼容保留；后端以会话 userId 为准，客户端传值被覆盖）

**Response**（`List<String>`）

```json
[ "trading" ]
```

- 新用户（无插件）→ `[]`

### `GET /api/v1/accounts` — 账号列表

**Response**

```json
[
  {
    "userId": "admin",
    "role": "admin",
    "enabled": true,
    "createdAt": "2026-08-02",
    "plugins": ["trading"]
  },
  {
    "userId": "adai",
    "role": "user",
    "enabled": true,
    "createdAt": "2026-08-02",
    "plugins": ["trading"]
  }
]
```

- 响应经 AccountView DTO 过滤，**不含 passwordHash**（bcrypt 哈希不下发，REVIEW #178）

### `GET /api/v1/accounts/available` — 可用账号列表（产品端选号）

> **需登录**（REVIEW #178：产品端遗留选号/切换调用，仅需登录会话、无需 role=admin）。仅返回 `enabled=true` 账号的 **userId 最小集**（REVIEW #215：不暴露 role/enabled/createdAt，避免 admin 标记等枚举面）。

**Response**（`List<String>`，纯 userId）

```json
[ "adai", "alice" ]
```

- 空列表 → `200 []`（前端展示「去 adai-admin 创建账号」空态）

### `POST /api/v1/accounts` — 建号（adai-admin 后台）

**Request Body**

```json
{ "userId": "alice", "role": "user" }
```

- `role` 可选，默认 `user`（`admin` / `user`）
- `plugins` 可选，默认 `[]`（新用户只有基础服务）；仅允许 `trading` / `learn`，非法 → 400
- `password` 可选（**初始密码**，≥8 位，过短 → 400；不传则账号初始无密码——无法登录，可之后由 admin 用 PATCH 重置，REVIEW #178）
- `400` — userId 已存在 / 格式非法（仅 `[a-zA-Z0-9_-]+`）/ **保留字 `default`（task-log #149：历史遗留测试数据目录名，禁建真实账号）** / role 非法 / plugins 非法 / 初始密码 <8 位

### `PATCH /api/v1/accounts/{userId}` — 更新账号

**Request Body**

```json
{ "enabled": false }
```

- `enabled` / `role` / `plugins` 均可选，缺省保持原值（只改 enabled 不清空 plugins）；**清空插件须显式传空数组 `[]`**（传 null 视为缺省保留，P3 2026-08-17 契约明确）
- `plugins` 传全量列表（如 `["trading","learn"]`），仅允许 `trading` / `learn`，非法 → 400
- `password` 可选（**重置密码**，≥8 位，过短 → 400「新密码长度至少 8 位」；REVIEW #178）——重置后踢除该账号**全部**会话（`AuthService.kickSessions`，被重置者需重新登录）；**不携带则保留既有 passwordHash**（修复「只改 enabled/role 即清空密码」bug）
- **内置管理员 `admin`（2026-09-04 前为 `adai`，已迁移）不可禁用、不可降级**（400）
- `404` — 账号不存在

### `DELETE /api/v1/accounts/{userId}` — 删除账号

- **内置管理员 `admin`（2026-09-04 前为 `adai`，已迁移）不可删除**（400）
- **query `purge`（可选，默认 `false`）**（task-log #149，2026-09-16 用户拍板）：`false` = **只移除账号 + 踢会话，`data/{userId}/` 保留**（个人数据是不可逆资产，宁可留一份没人用的目录）；`true` = 连同该用户目录下的文件一起清理（**不可逆**，adai-admin 侧还要过一次「输入账号名确认」）
- `204` — 删除成功且保留数据（`purge` 缺省 / false）；`200` — 删除并清理数据（`purge=true`，响应 `{"deleted":true,"purged":<bool>,"purgedFiles":N,"purgedDirs":M,"failures":[...]}`）；**2026-09-17（v3.76）语义修正**：`purged` 是「是否真清干净」（`failures` 为空才 `true`，原实现无条件 `true` = 假成功）；`purgedFiles` = 实际删掉的文件数；`purgedDirs` = 一并回收的空目录数；`failures` = 逐项失败原因（删不掉的文件 / 目录列举失败 / 空目录清理失败）。删不掉的不再只进日志（P2-审查1）；`404` — 账号不存在

### `PATCH /api/v1/accounts/{userId}/plugins` — 合并插件（S-R2 服务端原子语义）

> REVIEW S-R2（2026-08-15）：根治前端全量 PATCH read-modify-write 并发互覆（快速连点两个开关不再丢）。服务端账号级锁内读改写合并。

**Request body**

| 字段 | 类型 | 说明 |
|:-----|:-----|:-----|
| `add` | String[] | 要启用的插件名（`trading`/`learn`，可选，默认空）|
| `remove` | String[] | 要停用的插件名（可选，默认空）|

**Response** — `200` 合并后的 `Account`；`400` — 插件名非法 / **内置管理员插件受保护**；`404` — 账号不存在

```json
{
  "userId": "alice",
  "role": "user",
  "enabled": true,
  "createdAt": "2026-08-02",
  "plugins": ["trading"]
}
```

---

## 17. 管理端（adai-admin）

> 系统级浏览端点（读取 `data/` 全部用户层 + `os/` 知识库），**不走 `X-User-Id` 用户层**，仅供 adai-admin 使用。路径一律防目录遍历（`normalize + startsWith` 校验）。
>
> **鉴权**：全部端点要求登录且会话账号 role=admin（同 §16，REVIEW #178：并入统一登录，X-Admin-Token 退役）。

### `GET /api/v1/admin/files?path=` — data/ 目录浏览

**Query Parameters**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|:-----|:-----|:----:|:----:|:-----|
| `path` | String | 否 | 空（data/ 根）| 相对 data/ 的目录路径，如 `default/records` |

**Response** — 目录条目数组

```json
[
  { "name": "records", "path": "records", "isDir": true },
  { "name": "notes.md", "path": "notes.md", "isDir": false, "size": 123 }
]
```

- `404` — 目录不存在

### `GET /api/v1/admin/files/content?path=` — data/ 文件内容

**Response**

```json
{ "path": "notes.md", "size": 123, "content": "文件内容" }
```

- `404` — 文件不存在；`400` — 文件 >512KB 或路径非法

### `GET /api/v1/admin/knowledge?domain=&path=` — os/ 知识资产浏览

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `domain` | String | 是 | `trading-engine` / `life-os` / `project-os`（白名单校验）|
| `path` | String | 否 | 相对 os/ 的目录路径，如 `trading-engine/knowledge/context` |

**Response** — 同 `/admin/files` 条目数组

### `GET /api/v1/admin/knowledge/content?path=` — os/ 文件内容

**Response** — 同 `/admin/files/content`

### `GET /api/v1/admin/ai-logs?userId=&date=` — AI 交互日志（R1）

**Query Parameters**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|:-----|:-----|:----:|:----:|:-----|
| `userId` | String | 否 | `adai` | 用户 ID（多账号下指定；非法字符 400）|
| `date` | String | 否 | 今天 | 日期 `YYYY-MM-DD`（格式错误 400）|
| `page` | int | 否 | 1 | 页码（从 1 起，<1 归 1）|
| `size` | int | 否 | 200 | 每页条数（上限 500，超出截断）|

**Response** — 当日 AI 交互日志条目列表（JSONL 解析后，按写入顺序，分页切片）

```json
{
  "userId": "adai",
  "date": "2026-08-12",
  "page": 1,
  "size": 200,
  "total": 3,
  "count": 2,
  "logs": [
    {
      "traceId": "uuid",
      "ts": "2026-08-12T10:00:00.123",
      "durationMs": 856,
      "userId": "adai",
      "kind": "understand",
      "scene": "trading",
      "recordId": "rec_xxx",
      "cardId": null,
      "source": "question",
      "model": "deepseek",
      "prompt": "完整组装 prompt 全文",
      "systemPrompt": null,
      "estimatedTokens": 1200,
      "status": "ok",
      "error": null,
      "responseLength": 240,
      "responseSummary": "summary=买入 | tags=[trading]"
    }
  ]
}
```

- **数据源**：`data/{userId}/ai-logs/YYYY/MM/ai-log-YYYY-MM-DD.jsonl`（File First，见 `data-format-freeze.md`）
- **kind**：`understand` / `generate` / `recognizeIntent` / `visual.understand` / `visual.ask`
- **systemPrompt**（#231）：仅 `generate` 有值（自定义 system 指令，如复盘模板），understand/intent/visual 为 null——完整还原"提示词怎么组装的"
- **关联**：`recordId`/`cardId`/`source` 由调用点在 AI 调用前通过 `AiTraceContext` 挂载（无关联时靠 `scene`+`prompt` 追溯）
- **落盘失败不影响业务**：日志 best-effort，AI 调用结果正常返回
- **REVIEW #210 隐私治理（2026-08-12）**：日志保留 `adai.ai-log.retention-days`（默认 30 天）——写入时惰性清理过期文件；`date` 早于保留期返回 **400**（已清理不可查，防扫任意历史明文）；`size` 上限 500 防单次拉全量

### `POST /api/v1/admin/market/tdx-import` — 行情数据包导入（MD17，2026-09-04）

**Body** — multipart/form-data，字段 `file`（通达信盘后数据 .zip 压缩包，可含 `sh/lday/*.day` + `sz/lday/*.day`，兼容 `vipdoc/` 嵌套布局）

**说明**

- 后端流式解析：只按条目 basename 收集 `(sh|sz)\d{6}.day`（天然防 zip-slip），单条上限 32MB / 单包上限 30000 文件；
- 每个 .day 校验可解析（`TdxFileKlineSource.parse` 出 ≥1 根 K 线），坏文件列入 `failed` 不落盘、不阻断其余；
- 校验后按 `sh*.day → {tdx}/sh/lday/`、`sz*.day → {tdx}/sz/lday/` 分流，`.tmp` + 原子 move 覆盖（读取方 mtime 缓存自动失效，无需重启）；
- 空包 / 无任何 .day / 全部无法解析 / 非 zip → **400** `{"error": "人话原因"}`；
- **上传大小**：数据包通常 >5MB，生产需配置 `ADAI_MAX_FILE_SIZE`（如 512MB）/ `ADAI_MAX_REQUEST_SIZE`（如 520MB），见 `application.yml` multipart 注释。

**Response** — 导入统计

```json
{
  "status": "ok",
  "filename": "sh_sz.zip",
  "imported": 9367,
  "skipped": 2,
  "failed": [],
  "markets": { "sh": 4922, "sz": 4445 },
  "dayFilesAfter": 9367
}
```

## 18. learn 学习沉淀（learn 插件，RFC 20260829）

> **learn 插件 V1（2026-09-06 用户拍板开工）**：外部内容（视频字幕/文章/链接原文）喂入 → AI 结构化卡片（RFC 3.4 渐进式摘要四段）→ File First 落 `data/{userId}/learn/` → 列表/资产树查询。V1 为**独立端点喂入**（2026-09-06 用户拍板：仿截图入账先例，learn 消化是动作不是记录——不建记录、不沉淀记忆、不污染 Feed/时间线；**不经 POST /records 主链路**）。资产页浏览（目录树+全文渲染）与 LearnKnowledgeSource 问答注入为 L2。
>
> **落盘结构（v3.60 起）**：`data/{userId}/learn/{type}/{topic}/NN-{slug}.md` + 主题 `README.md` + 主题 `_raw/`（**与 Mac 侧 DSH 技能 `learn-digest` 同契约**：手工整理与产品整理共用同一目录，读得到对方的产物）。V1/V2 时代的扁平 `{type}/{yyyy-MM-dd}_{title}.md` **照旧可读可写**（原地不动、不强制迁移）。
>
> 全部端点需 learn 插件（未启用 403「learn 插件未启用」）；X-User-Id 隔离 `data/{userId}/learn/`。type（ai/trading/other）是**卡片文件分类，非插件 domain 收敛对象**——learn 不进 life/trading 收敛（D5 不受影响）；trade_related 仅 type=trading 内容有意义（V1 只记录不联动规则库，防语义漂移走用户审核闸）。
>
> **L2（2026-09-07）问答注入**：新增 `LearnKnowledgeSource`（kernel 知识源，name=learn → PluginRegistry 映射 learn 插件门控）——ContextEngine 按用户 enabledPlugins 注入最近学习笔记（`## 你最近的学习笔记`，标题+type+核心观点，上限 5 篇；无卡片不注入；损坏文件/_raw 跳过；globalContext 注入 + enrich 空防双份）——你问「上次讲 RAG 那篇说了啥」时阿呆能引用自己消化过的卡片作答（RFC 3.7 ③ 价值呈现）。
>
> **V2 消化闭环第一批（2026-09-07 复习流转 + 编辑）**：卡片 `status` 从 V1 固定 new 变为可流转 new→review→done（PATCH 端点）；正文编辑支撑（复述段建模 retell + PATCH 编辑端点）——「对话流让阿呆改」的后端能力就绪（前端对话流接线随 UI 批）。
>
> **V2 审查修复批（2026-09-07，learn V2 增量深审 v3.53）**：流转只允许相邻（new↔review、review→done、done→review，跳变 400）；进入 review 写 `review_at`（提醒计时起点）+ `reminded_at` 节流；同 type+title **任意日期**同名拒绝（跨日同名歧义根治）；learn_card_id = 源卡真实路径（清洗后标题）；复习提醒开关 learn 侧可达（GET/PUT `/learn/push-settings[/learn-review]`，纯 learn 用户可自关）；Feed 类型级门控。
>
> **抓取批（2026-09-12，v3.57，RFC 20260912 D 形态阶段 1 抓取主干）**：**D 形态与 B 形态的分水岭是「抓取进服务端」**——B 要求用户自己搞到字幕/原文再粘贴（最费力的一步留给用户），D 由阿呆完成。喂入支持 `url` 后，用户只丢一个 B站链接或文章地址：服务端抓元数据/字幕/正文；**无字幕则先报价、用户点头再走云端转写**（实测多数视频确实没有可获取的字幕，所以转写是必经路径而非降级）。产物契约、卡片模板、落盘目录沿用既有 learn 契约不变（A 技能与 D 形态共用同一份产物格式，不建第二套）。
>
> **出站白名单（2026-09-12 对抗审查 P0-1 修复）**：抓取目标来自**用户输入**，因此服务端出站前先过策略——拒私有网段（10/172.16-31/192.168/100.64）、回环（127/::1/localhost）、链路本地与云元数据地址（169.254.169.254、metadata.*）、非 80/443 端口、非 http(s) 协议；**重定向关闭自动跟随、逐跳复检**（上限 3 跳）；B站字幕与 Web Archive 快照等**第三方响应给出的地址**同样收敛到域名白名单；响应体有上限（正文 4MB / 音频 64MB），超限人话失败而不是把内存打满。被拒绝时返回 400 + 人话（如「这个地址不像是能公开访问的内容页，我就不去抓了」）。
> **残余（如实）**：未做 DNS 解析后的复检（DNS rebinding 面）；单实例部署前提见 REVIEW P2-learn18（多实例会超卖）。
>
> **B8 授权边界（抓取批落地口径）**：只抓内容页（域名白名单：bilibili.com/b23.tv + 一般文章页），不触登录态接口、不绕付费墙与登录墙；单用户月度转写配额硬闸（默认 36,000 秒 = 10 小时，对齐阿里云免费额度）+ 单次前置报价确认；社交平台与 YouTube 首期不做自动抓取（反爬/登录墙/服务器网络不可达），一律人话告知并引导用户粘正文或截图。
>
> **喂入入口批（2026-09-10，v3.56，用户拍板「先页面后对话流」）**：`POST /learn/cards` 改**提交式**（后台消化 + `GET /learn/digest/status` 轮询，对齐复盘 submitReview 先例）+ **web 资产页「＋」弹窗 / app「最近学习」页头「＋」喂入页**双端页面喂入入口（消化完成自动定位打开新卡；失败人话可重试；超时/关闭后台继续，素材留存 `_raw/`）。空态引导同步改为指向页面入口（不再指对话流——对话流喂入为批 2 待排）。

### `POST /api/v1/learn/digest` — 喂入链接/素材 → 抓取 + 消化（提交式，v3.57）

> **推荐端点（v3.57 起）**：`POST /learn/cards` 是同一逻辑的**兼容别名**（2026-09-10 先例，双端旧版本仍在用），新接入请用 `/learn/digest`。

**Body**

| 字段 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `url` | String | 否* | 内容页链接（**v3.57 新增**）：服务端抓取——B站视频（元数据 + 字幕 + 音频线索）/ 文章正文（反爬 403 → Web Archive 快照兜底） |
| `content` | String | 否* | 素材原文（降级路径：抓不到时用户粘正文），1-50000 字 |
| `type` | String | 否 | 显式类型 ai/trading/other；缺省 = LLM 判定（越界回落 other） |
| `platform` | String | 否 | 来源平台（仅素材路径有意义） |
| `author` | String | 否 | 作者/UP 主（仅素材路径有意义） |
| `published` | String | 否 | 原文发布日期 yyyy-MM-dd（仅素材路径有意义） |

> \* `url` 与 `content` **至少给一个**。归一规则：`content` 若是单行裸链接且未给 `url` → 按链接抓取（用户很容易把链接粘进素材框）；两者都给 → **正文为准，链接记为卡片来源**。

**Response** `200`：

```json
{ "status": "pending" }
```

- `status`：`pending`（受理，后台执行）/ `running`（同 user 已有任务在跑——含等确认期间再次提交，会如实回 `needs_confirmation`，不覆盖待确认任务）/ `needs_confirmation`（**需用户确认转写费用**，见下）/ `recorded`（**v3.72：无 learn 插件**——只接收不整理，素材已落成一条记录，含 `recordId` 与 `message`）
- `400`：`url` 与 `content` 都为空、type 非法、执行器队列满（「消化任务繁忙」）；**平台不支持**也走 400 + 人话（「YouTube 从这台服务器连不上…把字幕或正文粘进来更稳」）
- 抓取/转写/结构化的进行与结果一律走 `GET /learn/digest/status` 轮询
- `403`：learn 插件未启用（**仅 v3.72 之前**；v3.72 起本端点改为降级回 `recorded`，不再 403——其余 learn 端点仍 403）

**流程（服务端）**：判源类型 → 抓元数据 + 字幕/正文（**源必留痕** `_raw/`）→ 有字幕直接结构化；**无字幕 → 报价 + 等确认**（见 `/digest/confirm`）→ 云端转写 → LLM 六段结构化 → 落卡片 → 轮询回 `done`

### `POST /api/v1/learn/cards/feedback` — 产物反馈 → 长期偏好（v3.73）

> RFC `docs/rfc/20260917-learn-representation.md` §五 2b：用户说一句「太啰嗦」，**下一次**整理出来的卡片就会变。

**Body**

| 字段 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `type` | String | 是 | 卡片类型 ai/trading/other |
| `title` | String | 是 | 卡片标题（与 type 一起定位卡片） |
| `feedback` | String | 是 | 用户原话，≤120 字（如「太啰嗦」「多举几个例子」） |

**Response** `200`：

```json
{ "status": "recorded", "message": "记住了，以后我按这个来。", "canRepage": true }
```

- `status`：`recorded`（已沉淀为偏好）/ `exists`（这句话已经记住过，不重复沉淀）
- `canRepage`：这张卡还有 `_raw` 素材 → 可按新偏好重排一版；**本端点不自动重排**（重排调 LLM 花钱），由前端据此再问用户
- `400`：卡片不存在 / `feedback` 为空或超长（都给人话）
- `403`：learn 插件未启用（**属「整理能力」，不适用 v3.72 的接收降级**）

**闭环**：本端点写一条 `kind=preference` 记忆 → 被 `MemoryService.findAllPreferences` 聚合 → 经画像回流（v3.72 §四）注入下一次生成的 prompt → **下一次真的变了**。

### `POST /api/v1/learn/digest/confirm` — 转写费用确认（v3.57）

> RFC 20260912 §3.8「费用可控条 5：单次可预期」——抓到无字幕视频时先回一条报价，**用户点头后才真花钱**。

**Body**

| 字段 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `confirm` | Boolean | ✅ | `true` = 继续转写；`false` = 取消 |

**Response** `200`：与 `GET /learn/digest/status` 同结构（`confirm:false` → `status=cancelled`）

```json
{ "status": "running", "stage": "transcribing", "message": null }
```

- `confirm:false` → `status=cancelled`，message 说明「已取消转写（没花钱），抓到的元数据我留着了」——**不产生任何费用**
- `400`：「现在没有等待确认的整理任务」（没有 pending 任务 / 已超时清理）
- 确认环节结论保留 30 分钟（短于它的 60s 结果 TTL 会误清），过期后回 `idle`
- `403`：learn 插件未启用

### `GET /api/v1/learn/digest/quota` — 本月转写用量与额度（v3.57）

**Response** `200`：

| 字段 | 类型 | 说明 |
|:-----|:-----|:-----|
| `month` | String | 账期 yyyy-MM（新月份键不存在即归零，**月初自动重置**，无需定时任务） |
| `usedSeconds` / `usedYuan` | Int / Double | 本月已用转写时长（秒，仅语音内容计费口径）/ 已产生费用（元） |
| `quotaSeconds` / `remainSeconds` | Int | 月度配额上限（默认 36000 = 10 小时）/ 剩余（不为负） |
| `yuanPerHour` | Double | 单价（默认 0.288 元/小时，阿里云公开价同族模型口径） |
| `asrAvailable` | Boolean | 转写链路是否可用（凭证 + 转码工具齐备） |
| `unavailableReason` | String? | 不可用时的人话原因（缺凭证 / 服务器没装 ffmpeg），可用时为 null |

```json
{ "month": "2026-09", "usedSeconds": 1200, "usedYuan": 0.096, "quotaSeconds": 36000,
  "remainSeconds": 34800, "yuanPerHour": 0.288, "asrAvailable": true, "unavailableReason": null }
```

- 记账落 `data/{userId}/learn/_quota.json`；记账写盘失败会**中止转写**（不产生不可追溯的费用）
- `403`：learn 插件未启用

### `POST /api/v1/learn/cards` — 喂入素材 → AI 消化成学习卡片（提交式，v3.56）

> **v3.48（V1）起为同步消化（响应即卡片）；v3.56 起改提交式**（2026-09-10 learn 喂入入口批）：AI 消化几十秒级，远超前端客户端超时（App 15s/Web 120s）——原同步 POST 前端必先断「看似没反应」（复盘血泪先例同因）。现在 POST 立即返回受理状态，消化放后台执行器，前端轮询 `GET /learn/digest/status` 直到 done/failed。

**Body**

| 字段 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `content` | String | ✅ | 素材原文（字幕/文章/链接内容），1-50000 字 |
| `type` | String | 否 | 显式类型 ai/trading/other；缺省 = LLM 判定（越界回落 other） |
| `platform` | String | 否 | 来源平台（bilibili/youtube/web…） |
| `author` | String | 否 | 作者/UP 主 |
| `url` | String | 否 | 原文链接 |
| `published` | String | 否 | 原文发布日期 yyyy-MM-dd |

**Response** `200`：

```json
{ "status": "pending" }
```

- `status`：`pending`（受理，后台消化中）/ `running`（同 user 已有消化在跑——连点/双端并发去重，不重复烧 AI，前端直接轮询）
- 卡片消化完成不在此响应返回，走 `GET /learn/digest/status` 轮询到 `done` 后按 `type/title` 经 `GET /learn/content` 读全文（**v3.60**：落 `{type}/{topic}/NN-{slug}.md`，`topic` 由 LLM 判定并优先归并到已有主题目录）
- `400`：素材为空/超长、type 非法（仅 ai/trading/other）、**同 type 同 title 已存在（任意日期，v3.53 跨日同名拒绝）**、消化任务繁忙（队列满）；AI 消化失败不在此返回——后台失败后 `GET /learn/digest/status` 返回 `failed` + 人话 message（原始素材留存 `learn/_raw/` 后可重试，fail-visible 不产半成品）
- `403`：learn 插件未启用

### `GET /api/v1/learn/digest/status` — 消化任务状态（v3.56；v3.57 加 stage/source/cost）

**Response** `200`：

| 字段 | 类型 | 说明 |
|:-----|:-----|:-----|
| `status` | String | `idle`（无任务/结果已过期清理）/ `pending` / `running` / `needs_confirmation`（**v3.57**：需确认转写费用）/ `done` / `failed` / `cancelled`（**v3.57**：用户取消转写） |
| `type` | String? | 仅 `done`：新卡 type（ai/trading/other） |
| `title` | String? | 仅 `done`：新卡标题（供 `GET /learn/card` 打开） |
| `message` | String? | 人话：`failed` 原因 / `needs_confirmation` 报价文案 / `cancelled` 说明；进行中为 null |
| `stage` | String? | **v3.57**：进行中阶段 `fetching`（抓取原文）/ `transcribing`（云端转写，分钟级）/ `structuring`（整理成卡片）；**v3.60 新增 `reading`（正在读图，图片源）**；任务结束后清空 |
| `source` | Object? | **v3.57**：抓到的源信息 `{platform,title,author,durationSeconds}`（抓取成功后即可回显，让用户看到阿呆在抓什么） |
| `cost` | Object? | **v3.57**：转写费用视图 `{durationSeconds,durationKnown,estimatedYuan,monthUsedSeconds,quotaSeconds,remainSeconds}`（等确认时必填；`durationKnown=false` 表示时长未知、按 30 分钟保守估算） |

```json
{ "status": "done", "type": "ai", "title": "RAG 与 Agent 的区别", "message": null, "stage": null, "source": null, "cost": null }
```

等确认时的典型响应：

```json
{ "status": "needs_confirmation", "stage": "transcribing",
  "message": "这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）",
  "source": { "platform": "bilibili", "title": "某视频", "author": "某UP", "durationSeconds": 2244 },
  "cost": { "durationSeconds": 2244, "durationKnown": true, "estimatedYuan": 0.1795,
            "monthUsedSeconds": 0, "quotaSeconds": 36000, "remainSeconds": 36000 } }
```

- 任务态为**内存态**（按 userId 单任务）：`done`/`failed`/`cancelled` 结果保留 60s 惰性清理回 `idle`；`needs_confirmation` 保留 **30 分钟**（用户可能过一会儿才点确认）；后端重启丢失 → 回 `idle`（已落盘卡片不受影响，刷新列表可见）
- `403`：learn 插件未启用

### `DELETE /api/v1/learn/cards` — 删卡片（软删除，v3.61）

> **软删除**：文件移入 `learn/_trash/`（不是真删——知识是资产，误删要能捡回来），并从主题 `README.md` 的自动索引段摘掉该行。

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `type` | String | ✅ | ai/trading/other |
| `title` | String | ✅ | 卡片标题（精确匹配） |

**Response** `200`：

```json
{ "deleted": true, "title": "可转债双低策略要点", "learnCardId": "learn/trading/可转债双低策略/01-….md",
  "cascadedCandidates": ["候选标题一"] }
```

- `cascadedCandidates`：指向该卡的 **trading 反哺候选**被一并清理的标题列表（**级联**，回应 P2-learn11 的孤儿回链；前端要如实告知用户）
- `400`：卡片不存在 / **是别处整理的只读卡**（`writable=false` → 人话拒绝，前端应隐藏入口）/ `type` 非法；`403`：learn 插件未启用

### `PATCH /api/v1/learn/cards/topic` — 改主题（v3.61）

**Body**

| 字段 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `type` | String | ✅ | ai/trading/other |
| `title` | String | ✅ | 卡片标题（精确匹配） |
| `topic` | String | ✅ | 目标主题名（空 → 400 人话）|

**Response** `200` 更新后的 LearnCard（`topic` 已是新值）。

- 语义：`{type}/{老主题}/NN-x.md` → `{type}/{新主题}/MM-x.md`（新主题内续号），frontmatter `topic` 同步，**两个主题的 README 一起维护**（老主题摘行、新主题追加；手工内容不重写）；**同主题幂等**（原样返回）
- 失败安全：新文件写成功但老文件删不掉 → **回滚新文件**并人话报错（不留两张同名可写卡）
- `400`：卡片不存在 / 只读卡 / `topic` 为空 / `type` 非法；`403`：learn 插件未启用

### `POST /api/v1/learn/cards/repages` — 重排页序列（历史卡回填，v3.69）

> **为什么需要**：卡片流（`pages`）是 v3.69 起新消化的卡才有的。此前整理的老卡是「一段话 + 几条长句」的段落墙——这个端点拿老卡**自己留在 `_raw/` 的原始素材**（转写稿/文章正文，不用重新抓取、不用重新转写）让 AI 补排页序列。

**Body**

| 字段 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `type` | String | ✅ | ai/trading/other |
| `title` | String | ✅ | 卡片标题（精确匹配） |

**Response** `200`：`{ "type": "ai", "title": "…", "repaged": true }`

- **只补呈现层**：只替换/追加 md 的 `## 卡片页` 段——frontmatter、核心观点/关键要点/我的疑问/复述、以及任何手工追加的段**一字不动**（不是重新消化，用户读过的内容不被重写）
- 底稿来源：该卡主题目录下 `_raw/` 里最长的 `.txt`/`.md`（转写稿、文章正文）；只认文本，`-meta.json` 与图片不算底稿
- `400` 人话：卡片不存在 / **只读卡**（Mac 侧技能整理的原始卡）/ **这张卡没留下原始素材** / AI 排页失败或没排出可用页
- 失败一律**不写盘**：写盘前所有校验与解析都已完成（解析用宽容入口，容忍围栏/前后废话/单对象）
- `403`：learn 插件未启用

### `GET /api/v1/learn/cards` — 卡片列表（v3.48；v3.60 加 topic/writable）

**Query Parameters**：`type`（可选 ai/trading/other；缺省返回全部，按 created 倒序）

**Response** `200` LearnCard 数组（元数据 + 正文段字段）。`type` 非法 → 400。

**LearnCard 字段（v3.60 新增两个）**：

| 字段 | 类型 | 说明 |
|:-----|:-----|:-----|
| `topic` | String | **v3.60**：主题目录名（同一主题的多源归并到一个目录）；老式扁平卡与未归类卡为 `未归类` |
| `writable` | Boolean | **v3.60**：`true` = 本产品产出的卡（可编辑/流转/反哺）；`false` = **在 Mac 上用 DSH 技能整理的原始卡 → 只读**（列表与全文照常可见，写操作后端返回 400 人话） |

### `GET /api/v1/learn/content` — 卡片全文（md 原文，v3.60；v3.69 加 `pages`）

> **为什么需要**：列表/单篇接口返回的是产品建模的段（核心观点/关键要点/我的疑问/复述），而 Mac 侧技能整理的卡还有「关键内容详解 / 金句 / 与主题概念的关系 / 概念追踪」等段——**只按建模字段渲染就"看得见、读不全"**。本端点按 md 原文返回，两种来源的卡都能完整呈现。
> **v3.69 卡片流批**：响应新增 `pages`——卡片 md 的 `## 卡片页` 段（LLM 消化时产出的「一页一单元」呈现：一句结论 + 表格/数字/对照/结构图）。**没有该段的卡（老卡、Mac 侧技能产物）→ `pages: []` → 前端按原形态渲染**，零迁移。

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `type` | String | ✅ | ai/trading/other |
| `title` | String | ✅ | 卡片标题（精确匹配） |

**Response** `200`：

```json
{ "type": "ai", "title": "Harness 到底是什么？", "topic": "harness-engineering",
  "writable": false, "content": "---\ntitle: ...\n---\n\n## 核心观点（一句话）\n...",
  "body": "## 核心观点（一句话）\n...",
  "pages": [
    { "kind": "diagram", "title": "三层递进", "claim": "研究范围一圈圈变大",
      "nodes": [{ "text": "Prompt Engineering", "note": "怎么问", "tone": "neutral" }] },
    { "kind": "numbers", "title": "账单", "claim": "贵 20 倍",
      "numbers": [{ "v": "6 小时", "l": "$200" }] }
  ] }
```

`pages[].kind` 六种，载荷字段**都可空**（前端按存在的字段渲染）：

| kind | 载荷 | 用在哪 |
|:-----|:-----|:-------|
| `points` | `bullets: [String]` | 要点页 |
| `table` | `table: { headers: [String], rows: [[String]] }` | 对照表 |
| `numbers` | `numbers: [{ v, l }]` | 规模/成本/倍率 |
| `compare` | `left` / `right`: `{ title, tone: good\|bad\|neutral, items: [String] }` | 正反、争议、失败vs正确 |
| `diagram` | `nodes: [{ text, note, tone: good\|bad\|neutral\|info }]` | 结构/流程（顺序即走向） |
| `quote` | `bullets: [String]` | 原文引述 |

- `400`：`type` 非法（仅 ai/trading/other）/ 卡片不存在（人话）；`403`：learn 插件未启用
- 页段解析失败**不影响**本端点：`content`/`body` 照常返回、`pages` 降级为 `[]`（呈现层问题绝不阻断读卡）

### `GET /api/v1/learn/find` — 找卡片（「打开那篇」+ 学习页搜索，v3.60）

> 纯**规则打分**（标题 > 主题 > 标签 > 核心观点/要点），**不烧 AI**；服务端已排序，前端直接用。

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `q` | String | ✅ | 关键词（整句也可以，按子串 + 分词匹配）；空白 → 空数组 |
| `limit` | Integer | 否 | 返回上限（默认 5，最大 20） |

**Response** `200`：LearnCard 数组（相关度降序；同分按 created 倒序）。空命中 → `[]`（前端自行兜底话术）。

### `POST /api/v1/learn/digest/image` — 图片喂入（书页/PPT/截图，v3.60）

> 图片源（RFC 20260912 §3.5「复用现有图片上传通道 + VLM」）：**视觉模型只做「忠实提取」**（逐字抄录 + 图表含义 + 存疑标注），结构化交给文本模型——与链接/素材**同一条消化流水线**（提交式 + 同一套轮询）。

**Request**：`multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `files` | File[] | ✅ | 1~3 张图片（png/jpg/webp，单张 ≤ 5MB）；**非图片类型 → 400**；>3 张 → 400 |
| `type` | String | 否 | ai/trading/other（缺省由 LLM 判定） |
| `note` | String | 否 | 给读图模型的补充说明（如「这是我拍的板书，第 3 页」） |

**Response** `200`：`{ "status": "pending" }`（同 `POST /learn/digest`，随后轮询 `GET /learn/digest/status`，此时 `stage=reading`）

- 原图**先落** `data/{userId}/learn/_raw/image-N-{hash}.{ext}`（源必留痕：原图丢了不可重建），消化成功后随卡**归位**到 `{type}/{topic}/_raw/`
- `400`：没有图片 / 非图片类型 / 单张超 5MB / 超过 3 张 / type 非法 / 空图；`403`：learn 插件未启用
- 读图失败（模型异常）→ 任务 `failed` + 人话（原图已留存，可重试）；读出的内容为空 → 人话「换一张清楚的，或者把文字粘进来」

### `POST /api/v1/learn/migrate` — 老式扁平卡一次性迁移（v3.60）

> **为什么需要**：V1/V2 的产品卡落在 `{type}/{yyyy-MM-dd}_{title}.md`，与 Mac 侧技能的主题目录契约不同构。迁移后两个写入方才真正同一契约。老卡**不迁也能照常用**（原地可读可写），所以这是**可选的一次动作**，不是强制的启动改造。

**Response** `200`：

```json
{ "migrated": 1,
  "items": [ { "type": "ai", "from": "learn/ai/2026-09-12_老卡.md", "to": "learn/ai/未归类/01-老卡.md" } ] }
```

- **幂等**：已迁过 → `{"migrated":0,"items":[]}`
- 主题取该卡 frontmatter 的 `topic`（缺省 → `未归类`）；正文**一字不动**（只补 `origin: product` + `topic` 两个键并挪位置）
- 安全：目标写成功但老文件删不掉 → **回滚目标**（宁可不迁，也不留两张同名可写卡）；认不出是卡的文件（无 frontmatter）**原地不动**
- `403`：learn 插件未启用

### `GET /api/v1/learn/card` — 单篇卡片全文（v3.48）

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `type` | String | ✅ | ai/trading/other |
| `title` | String | ✅ | 卡片标题（精确匹配） |

**Response** `200` LearnCard（同列表项字段）。`type` 非法 / **卡片不存在** → 400（人话「卡片不存在：type/title」）。

### `PATCH /api/v1/learn/cards/status` — 复习状态流转（v3.49）

**Body**

| 字段 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `type` | String | ✅ | ai/trading/other |
| `title` | String | ✅ | 卡片标题（精确匹配） |
| `status` | String | ✅ | new/review/done |

**Response** `200` 更新后的 LearnCard（仅 frontmatter status 变更，正文与手写「复述」段原样保留）。进入 review（含 done→review 重进）时卡片附 `reviewAt`（= 当次进入日，S-learn1 复习提醒计时起点）；离开 review（→new/done）时 `reviewAt`/`remindedAt` 清空。

- `400`：type/status 非法、**流转不被允许（v3.53：仅 new↔review、review→done、done→review；new→done、done→new 跳变拒绝）**、卡片不存在（人话「卡片不存在：type/title」）
- `403`：learn 插件未启用

### `POST /api/v1/learn/cards/restore-origin` — 认回被抹掉的来源标记（v3.70）

> REVIEW P2-learn21（2026-09-16）：产品卡「可写」的判据就是 frontmatter 里的 `origin: product`——它被别处工具整文件重写抹掉后，卡会**静默退化成只读**（看得见、读得全，就是改不动；旧提示还会冤枉成「在 Mac 上整理的」）。本端点把标记认回来。

- **定位**：`?type=&title=`
- **不是无条件的「盖章」**：只在这张卡**看起来确实是本产品写的**时才认——判据 = 正文含 `## 卡片页` 段，或 frontmatter 带 `review_at` / `reminded_at`（**刻意不认 `status`**：Mac 技能卡模板本身就带 status，认它等于每张只读卡都能被「认回」成可写——2026-09-17 深审修复）。判据不成立 → **400 人话**「这张《X》看着不是我写的（正文里没有我用的段落），我不敢给它盖我的章…」（否则用户一句话就能把别处整理的只读卡「升级」成可写，等于废掉只读保护）
- **幂等**：已经是产品卡 → 直接返回，不重复写盘
- `200` — `{"type","title","writable":true}`

### `PATCH /api/v1/learn/cards` — 编辑卡片正文（v3.49）

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `type` | String | ✅ | ai/trading/other |
| `title` | String | ✅ | 卡片标题（精确匹配） |

**Body**（部分字段补丁：缺省 = 保留原值，提供 = 覆盖/清空）：

| 字段 | 类型 | 说明 |
|:-----|:-----|:-----|
| `coreView` | String | 核心观点 |
| `keyPoints` | String[] | 关键要点列表 |
| `questions` | String[] | 我的疑问列表 |
| `retell` | String | 复述段（自己写 100-200 字，消化关键）|
| `tradeRelated` | Boolean | 是否涉及可执行规则（仅 type=trading 生效，非 trading 强制 false）|
| `tradeNote` | String | 交易备注（仅 type=trading 保留）|
| `tags` | String[] | 标签列表 |

**Response** `200` 更新后的 LearnCard。`type`/`title`/`created` **不可修改**（修改 = 移动文件，拒绝——如需改名请新建卡片）。

- `400`：type 非法、卡片不存在、编辑内容为空
- `403`：learn 插件未启用

### `GET /api/v1/learn/push-settings` — 复习提醒开关读（v3.53，S-learn2）

learn 插件门控。返回 learn 域推送开关：`{"learn-review": true}`（缺失默认开）。

- `403`：learn 插件未启用

### `PUT /api/v1/learn/push-settings/learn-review` — 复习提醒开关写（v3.53，S-learn2）

**Body**：`{"enabled": true|false}`

**Response** `200` 更新后 `{"learn-review": bool}`。

- `400`：开关值缺失
- `403`：learn 插件未启用

> **归属说明（2026-09-07 审查 S-learn2 拍板）**：learn-review 开关同时保留在 `GET/PUT /trading/push-settings`（交易用户双入口同键同文件）；learn 侧端点让**纯 learn 用户（无 trading 插件）也能自关**——此前入口全在交易设置页，纯 learn 用户「可关」落空。

### `POST /api/v1/learn/cards/candidate` — trading 卡片反哺成规则候选（v3.50）

**Body**

| 字段 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `type` | String | ✅ | 卡片类型（仅 trading 可反哺）|
| `title` | String | ✅ | 卡片标题（精确匹配）|

**Response** `200` 落盘的候选（建议卡）：

```json
{
  "title": "回调一半的判定",
  "learnCardId": "learn/trading/2026-09-06_回调一半的判定",
  "sourceType": "trading",
  "created": "2026-09-07",
  "coreView": "回调到一半才是买点，几何口径 (high+low)/2",
  "keyPoints": ["02:31 回调一半=(high+low)/2"],
  "tradeNote": "与 R66 止损互补",
  "tags": ["止损", "回调"]
}
```

候选落 `data/{userId}/trading/candidates/`（只存提炼建议 + learn_card_id 回链，不复制整卡），**不自动入库**——需在交易知识库工作流（os/trading-engine）审核后融合归正式目录并重建 knowledge/context（规则改动守人工审核闸）。

- `400`：type 非 trading（人话）、卡片不存在、**卡片未标 trade_related**（先确认内容再反哺）、同名候选已存在（任意日期，v3.53；列表/删除同名歧义时 400 列 created 日期）
- `403`：learn 插件未启用

### `GET /api/v1/learn/cards/candidates` — 候选列表（v3.50）

**Response** `200` LearnTradingCandidate 数组（created 倒序；损坏/异型文件跳过）。需 learn 插件（403）。

### `DELETE /api/v1/learn/cards/candidates` — 删除候选（v3.50）

**Query Parameters**：`title`（候选标题）

**Response** `200` `{"deleted":true}`（不存在也幂等返回）。需 learn 插件（403）。

### `GET /api/v1/learn/tree` — 资产树（v3.48）

**Response** `200`：按 type 分组的卡片清单（只含已落盘规范卡片）：

```json
{ "ai": [ {LearnCard} ], "trading": [ {LearnCard} ] }
```

---

## 19. 推送设备与链路自检（APNs，RFC 20260913）

> 定位：把「阿呆的消息送到你的手机」这件事打通。推送**内容**仍由各推送生产方决定
> （时段/买点/止损/行情异动/收盘小结/复习提醒），本节只管**推到哪台设备**与**链路通不通**。
>
> 与 `GET/PUT /api/v1/trading/push-settings`（§5）的分工：那个管「哪些**类型**要推」（用户偏好开关），
> 本节管「推到**哪台设备**」（通道与目标）。两者正交，互不覆盖。
>
> 鉴权：需登录（Bearer）。**无插件门控**——推送跨 feed/trading/learn 三域，按插件门控会把
> 纯 learn 用户的通知挡掉（先例：learn-review 开关归属 trading 门控的教训）。
> 数据落 `data/{userId}/push/devices.json`（X-User-Id 隔离）。

### `POST /api/v1/push/devices` — 登记/刷新推送设备（v3.63）

iOS 客户端拿到 APNs deviceToken 后上报；**同 token 幂等**（重复上报只刷新 `lastSeenAt`，
保留首次注册时间与 token 原写法）。

**Request Body**

| 字段 | 类型 | 必填 | 说明 |
|:----|:----|:----|:----|
| `token` | String | ✅ | APNs deviceToken（**十六进制 32~200 位**；会被拼进 APNs 出站 URL 路径，故严格校验） |
| `platform` | String? | — | 默认 `ios` |
| `environment` | String? | — | `sandbox` / `production`（客户端读包内 `aps-environment` 得出；`development`/`dev` 归一为 sandbox，未知一律回落 sandbox） |
| `bundleId` | String? | — | 一般**不要传**：apns-topic 以后端配置 `adai.push.apns.bundle-id` 为单一事实源 |
| `label` | String? | — | 设备备注（如「iPhone」） |

**Response** `200`

```json
{
  "token": "a1b2...（64 位十六进制）",
  "platform": "ios",
  "environment": "sandbox",
  "bundleId": null,
  "label": "iPhone",
  "registeredAt": "2026-09-13T04:30:00Z",
  "lastSeenAt": "2026-09-13T04:30:00Z"
}
```

- `400`：`{"error":"缺少设备推送标识"}` / `{"error":"设备推送标识不合法（应为十六进制 token）"}`
- `500`：存量文件损坏 → `{"error":"推送设备文件已损坏，本次写入已取消（避免覆盖其它设备）"}`（**拒绝写回而非覆盖**）

### `GET /api/v1/push/devices` — 已登记设备（v3.63）

**Response** `200`：PushDevice 数组（无设备 → `[]`）。App 侧「本机是否已登记」自检用。

### `DELETE /api/v1/push/devices/{token}` — 注销设备（v3.63）

**Response** `200` `{"removed":true|false}`（幂等：不存在返回 false，不报错）。
登出时调用，避免换账号后推送发到已登出的设备；后端在 APNs 回 `410 Unregistered` 时也会自动清理。

### `GET /api/v1/push/status` — 推送链路自检（v3.63）

**Response** `200`

```json
{
  "channels": [
    {"name": "feed", "enabled": true},
    {"name": "bark", "enabled": true},
    {"name": "apns", "enabled": true, "configured": true, "keyId": "ABCD123456",
     "teamId": "4G3D37YKSB", "bundleId": "com.adaiadai.adaiApp",
     "typeAllowlist": "close-summary,learn-review"}
  ],
  "deviceCount": 1,
  "devices": [ {"token": "...", "environment": "sandbox", "label": "iPhone"} ]
}
```

用途：配完 `.p8`（`ADAI_PUSH_APNS_KEY_PATH` + `ADAI_PUSH_APNS_KEY_ID`）后用这一条确认
`apns.enabled=true`，不必等下一次定时推送。`typeAllowlist` 为「全部」表示未设灰度白名单。
