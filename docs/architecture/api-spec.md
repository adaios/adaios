# AdaiOS API 文档

> 前后端接口契约。前端 Flutter、后端 Spring Boot，所有 API 返回 JSON。

**文档版本：v3.57 | 最后更新：2026-09-12**

---

## 变更记录

| 日期 | 版本 | 变更 |
|:----|:----|:------|
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

请求：`{"account": "adai", "password": "..."}`

响应 200：
```json
{"token": "3ce1...8f", "userId": "adai", "role": "admin", "plugins": ["trading","project"], "expiresAt": "2026-10-01T15:53:54Z"}
```
- 401：账号或密码错误 / 账号未设密码（提示先 setup）/ 连续 5 次失败限流（15 分钟锁，按 IP+账号）
- token 明文只在此响应出现一次；落盘 `data/accounts/sessions.json` 仅存 SHA-256 哈希

### `POST /api/v1/auth/logout` — 登出（会话）

无请求体；删除当前 token 会话。幂等。

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
  "cardId": null                  // optional: 会话卡片 ID，有值则视为对话延续
}
```

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
- 任务、进度、bug、需求、RFC、项目、待办、计划、开发 → `project`（需启用 project 插件）
- 日常、想法、记录、心情、问题 → `life`

> 无插件用户一律 `life`（单一 domain）。即使 AI 输出 `trading`/`project`，若该用户未启用对应插件，后端也会收敛为 `life`（`PluginService.gateDomain`）。插件名见 §16 `GET /me/plugins`。

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
- `400` — domain 非法（仅 `life` / `trading` / `project`）

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
  "totalToday": 28
}
```

> feed 只返回今天的数据，历史数据走时间线（`GET /api/v1/timeline`）。
> 每日摘要单独调用 `GET /api/v1/brief`。
> **时间基准（updatedAt）**：卡片（`type=card`）的 `time`/`date` 按最后更新时间 `updatedAt`，跨日续接的对话归最后活跃日；`findTodayCards` 按 `updatedAt` 过滤。分页（REVIEW #175）：核心条目（record/card）按时间从新到旧切块，page 0 返回完整 `size` 条最新核心，余数放末页；附加条目（ai_note/action/market/push）只在 page 0 末尾附加。
> **插件门控（RFC 20260814）**：`market`（行情条）与 `push`（异动推送）条目仅注入启用 **trading 插件** 的用户；无插件用户 Feed 无行情卡。

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `type` | String | `record` / `card` / `ai_note` / `action`（未完成行动提醒，Phase 3）/ `market`（大盘行情，v0.2.0）/ `push`（行情异动主动推送，Phase 2：止损预警/放飞提示/跌破成本线/真止损 R66（2026-08-16））|
| `time` | String | `HH:mm` 格式（后端已格式化，无小数秒），卡片取首条用户消息时间 |
| `date` | String | `MM-dd` 格式，条目所属日期（每张卡片都带日期，前端展示）|
| `mediaPath` | String? | 媒体记录才有：`type=image`（图片记录原图）与 `type=image_qa`（S-2 展示层聚合：图文事件缩略图取引用首图）——媒体文件相对路径（GET `/api/v1/records/media/{id}` 取文件）；其余类型为 `null` |
| `turns` | TurnDto[] | 仅 `type=card` 时有值，卡片对话轮次 |
| `domain` | String | `life` / `trading` / `project` — AI 按关键词规则判定 |
| `totalToday` | int | **核心输入条数**（record/card，不含 ai_note/action/market/push 附加）；分页终止基准 |

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

### `GET /api/v1/trading/portfolio` — 查询投资组合快照
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。

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
> recordTrade 成功后**同步写逐笔流水**（`data/{userId}/trading/trades/{yyyy-MM}.json`）+ **写一条 domain=trading 记录**（5 分钟窗口去重）——交易进 timeline/记忆 + 复盘提醒闭环。

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

### `POST /api/v1/trading/trades/import` — 历史成交导入（第五份文件，2026-08-18；2026-08-23 加回填；2026-08-25 双模式）
> 需 trading 插件（403）。

通达信「历史成交查询」导出 → **自动识别双模式**（RFC 20260825 §5）：

- **同步模式（`syncMode="sync"`）**：全部成交在最近 10 个自然日内（覆盖周末/节假日）→ 视为**当日成交导入**，逐笔走正常交易链路（持仓增减 + 现金/手续费推导 + 逐笔流水 + 时间线记录），`orderId` 幂等（同编号重复导入不重复加减），处理完做流水重放对账
- **补录模式（`syncMode="append"`）**：存在更早成交 → 维持原语义**只补流水不重算持仓/现金**（缺窗口前基线，回放重建算不出券商口径；持仓/成本/现金以全量覆盖导入为准），返回对账提示

**body**：`{"content":"通达信历史成交查询导出文本（UTF-8 转码后，表头含 成交日期/证券代码/买卖标志/成交编号）"}`

- 每笔落流水：`entryDate`=成交日期、`fee`=|发生金额−成交金额|（券商实扣）、`orderId`=成交编号（**幂等键**；无编号按 symbol+direction+entryDate+price+volume 指纹去重）；同步模式 `orderId` 透传流水（幂等键不丢）
- **缺失字段回填（2026-08-23）**：补录模式幂等命中的已存在记录，若旧记录 `tradeTime` 为空且新文件带成交时间 → 回填该笔成交时间（计入 `updated`），不落新流水
- 数量 0 行（股息红利税等非交易资金事件）不落流水，计入 `nonTrades`
- **非交易占位代码跳过（2026-08-25 用户反馈）**：明显非股票代码（通达信占位段 `79/80/81/82` 开头 6 位，如 `799999`「登记指定」/配号）一律不落库，计入 `nonTrades`（前端「非交易 N」可见）——此前 `799999 登记指定` 被当真实持仓入库
- **股息类资金事件记账（2026-08-25 用户拍板方案 A）**：备注列含 股息/红利/入账 的数量 0 行（如「股息红利税差异化处理资金下账」「股息入账」）→ **计入现金**：入账（发生金额正）现金 +N、红利税（负）现金 −N；不动持仓、不进批次；落一条 volume=0 流水（amount=发生金额，reason=源文件备注）可回溯；幂等（symbol+日期+发生金额绝对值指纹）；其余数量 0 行（无备注识别）计入 `nonTrades`

**响应**（2026-08-25 扩展）：
```json
{"imported":45,"updated":3,"skipped":1,"nonTrades":1,
 "syncMode":"sync",
 "summary":{"date":"2026-08-25","buyCount":2,"sellCount":1,"buyAmount":10600.0,"sellAmount":3900.0,
   "newLots":1,"deductedLots":1,
   "behaviors":[{"type":"loss-avg-down","label":"亏损加仓","symbol":"600000","name":"浦发银行",
     "date":"2026-08-25","message":"买价 9.2 低于上一买批成本 10.0——越跌越买/补仓摊薄"}]},
 "lines":[{"symbol":"000725","name":"京东方Ａ","count":7,"netVolume":-400,"holdings":4800,
   "note":"当前持仓 4800 ≠ 流水净 -400——存在窗口前基线或未导入成交（持仓快照为准，差额已按初始批次兜底）"}]}
```
- `imported` = 落流水笔数 / `updated` = 回填缺失成交时间笔数 / `skipped` = 幂等去重跳过 / `nonTrades` = 非交易事件
- `syncMode` = `sync`（同步持仓）或 `append`（只补流水）
- `summary` = **每日操作总结**（RFC 20260825 §6，仅 sync 模式存在；不耗 AI 秒出）：买卖笔数/金额 + 批次 diff（`newLots` 新增批次、`deductedLots` 被扣减批次）+ `behaviors` 行为标注（`type`：loss-avg-down 亏损加仓 / chase-high 追高 / short-new 短线新开 / stop-loss-ignored 破止损未走 / giveback 浮盈回吐 / short-overdue 短线超期）
- `lines` = 对账提示：每标的 流水净增减 vs 当前持仓快照，指出基线缺口/已清仓（只报告不改数据）

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
  "skippedDays": 0, "startDate": "2026-08-03", "endDate": "2026-09-04"
}
```
- `netValue` = totalAssets / invested（invested = 期初投入缺口 + 转账累计净投入）；invested ≤0 → `netValue: null`（本金未设不给误导值，P2-交易31 同口径）
- `drawdown` = 历史峰值到当日回落比例（0 = 新高）
- 无账户快照（从未导入资金/无记录）→ `points: []`（静默降级不抛错）

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
- **caseMatches（环 4 二期，2026-08-30 可选）**：开关 `adai.trading.case.scan-match`（**默认 false**）开启时，每只自选股附「与完美买点案例库相似度 Top 3」参考（经验增强，不覆盖规则判定）；默认关 → 字段为空/缺失，行为与现状完全一致；`buyPoint="case"` 表示规则未命中但案例相似度高（参考信号，**15:10 定时推送会跳过该类型**，仅 web 可见）
- **dataDate（v3.44）**：判定所用 K 线最后一根日期（YYYY-MM-DD）——**15:10 定时推送只推 dataDate=当日 的信号**（防昨日 K 冒充今日）；web 手动查询不受限（信号列可据此提示数据日）

- **B1 回调买点（2026-09-04 课程口径校准，P1-交易9 出表）**：回撤到波段**涨幅一半位置**——回撤占波段比例（窗口最高 high − 窗口最低 low 的区间）≥ 回调比例 + 缩量（3 日均量 < 5 日均量 × 缩量阈值）+ KDJ.J < 低位阈值；几何等价 close ≤ (high+low)/2（替代旧「距前高回撤 ≥50%」腰斩口径）
- **B2 突破买点（2026-09-04 三重校验，P1-交易20 出表）**：放量（当日量 > 5 日均量 × 放量倍数，默认 **2.0** = 课程「倍量柱」）+ 收盘破前高 + **KDJ.J 拐头向上 + J 连续 ≥90 高位钝化排除（首日拉起放行）+ 距窗口低点涨幅 ≤30%（防追高）+ 非近 2 日连板（单日 ≥9.8%）**
- **参数按用户规则**（2026-08-30 v3.33，交易插件规则层）：回调 0.5 / 缩量 0.7 / KDJ 13 / 放量 2.0 / 前高 20 日是**默认值**——从 `data/{userId}/trading/rules.yaml` 的 `buyPullbackPct/buyShrinkRatio/buyKdjLow/buyVolumeSurge/buyPriorHighDays` 读取（`PUT /trading/rules` 可配，无规则 → 默认值）；B1/B2 命名语义随 adai 规则包（通用原语：回调/缩量/KDJ/放量/突破）；规格详见 `os/trading-engine/engine/buy-point-rules.md`
- 收盘 15:10 定时任务自动扫描 + 命中推送「到买点了」（`TradingSessionPushService.buyPointScan`）；web 自选 Tab 显示信号列；**B1?（部分满足候选）不推送**（P2-交易7）
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

资金股份查询导入后返回券商口径账户：`{"assets":110504.88,"cash":292.88,"available":292.88,"withdrawable":292.88,"marketValue":110212.00,"pnl":15235.55,"todayPnl":0.0,"principal":150000,"snapshotDate":"2026-08-16"}`。**字段语义（2026-08-16 修正）**：`pnl` = 持仓浮动盈亏（券商口径，非总盈亏）；**总盈亏 = `assets - principal`**（本金由用户提供，累计投入 15 万 → 当前总盈亏 -39,495.12）。顶层展示总资产/可用/可取/参考市值/当日盈亏/总盈亏/本金。数据依赖导入；收盘 15:05 自动更新行情相关字段（参考市值/当日盈亏/浮盈，P2-交易19 修订），现金/本金保持券商导入+转账推导。需 trading 插件（403）。

### `GET /api/v1/trading/push-settings` — 推送开关（RFC 20260817 交易推送体验）
> 需 trading 插件（403）。

返回用户推送类型开关：`{"session":true,"buy-point":true,"stop-loss":true,"near-stop-loss":true,"loss":true,"gain":true,"break-cost":true,"market":true,"close-summary":true,"learn-review":true}`（类型 → 是否开启；未配置默认开）。关闭的类型定时任务不再生成、Feed 不再注入（双侧门控）。`close-summary`（2026-08-29，P2-用户3）= 15:30 收盘小结；`learn-review`（2026-09-07 learn V2 批 4）= 每晚 20:00 学习复习到期卡片汇总提醒。

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

返回当日已归集的交易候选（**未落库，待确认**）：`[{"symbol":"000725","name":"京东方A","direction":"SELL","price":6.1,"volume":5300,"tradeDate":"2026-08-26","source":"text","complete":true}]`。来源：用户发成交截图（VLM 识别）或说「清仓了XX」（文字解析），仅 trading 插件用户触发；**去重口径（B6-2 2026-08-23）**：同 (symbol, direction, 当日) 且数量差 ≤ ±10% 视为同笔（`sameTrade`），超量级分别保留。**tradeDate（2026-08-27）**：截图表格「日期」列提取的成交日期（历史成交截图，如 `2026-08-26`）；当日委托/文字归集无日期 → `null`。**2026-08-27 二修（v3.32）**：截图归集候选（source=image）`tradeDate=null` 时**确认会被拒**——需先补日期（`PUT /trade-log/date`）再确认，防「昨日成交今早确认被记成今天」。

### `POST /api/v1/trading/trade-log/confirm` — 确认交易日志落库
> 需 trading 插件（403）。

当日候选逐笔走 `recordTrade` 链路（持仓增减 + 现金 + 手续费自动算）；**2026-08-27（用户反馈「今日 4 笔其实是昨天」）**：落库 `entryDate` = 候选 `tradeDate`（截图日期列提取，成交日优先）；**v3.32 二修（用户拍板）**：截图归集候选（source=image）**无 `tradeDate` → 禁止落库**（计入 skipped、候选保留、failures 提示「缺少成交日期」）——不再回退确认当天，用户补日期（`PUT /trade-log/date`）后再次确认；文字归集（source=text）无日期仍回退确认当天（当日口语语义）。**B6-5（2026-08-23，P0-1 延伸）**：落库失败的候选（SELL 超持仓等）与不完整候选**回写保留**（不静默清空），用户可补全/修正/丢弃后再次确认。**响应**：`{"confirmed":2,"failed":1,"skipped":1,"failures":["600519 贵州茅台: 未持有 600519，无法卖出","600206 有研新材: 缺少成交日期（截图未识别到日期列），请补充日期后再确认"]}`（confirmed=成功 / failed=失败保留 / skipped=不完整或缺日期保留 / failures=失败人话明细）。阿呆只归集不落库——用户确认后才写交易模块（建议引擎哲学）。**P2-交易36 治本（v3.54，2026-09-09）**：候选携带 `orderId`（成交编号）/`fee`（手续费，可选；`PUT /trade-log/meta` 在确认前补填），confirm 落库改走带 orderId/fee 的链路（`recordTradeWithOrderId`）——编号与费用透传逐笔流水（幂等去重/对账可用，历史成交页不再「—」）；已落库流水仍缺 → `PUT /trading/trades/{tradeId}/meta` 补填。

### `PUT /api/v1/trading/trade-log/date` — 补写候选成交日期（v3.32，2026-08-27）
> 需 trading 插件（403）。

截图归集候选缺日期被 confirm 拒后，用户补日期 → 更新当日候选 `tradeDate` → 再次确认可正常落库。**body**：`{"symbol":"600206","direction":"SELL","tradeDate":"2026-08-26"}`（tradeDate 格式 `yyyy-MM-dd`）。**响应**：`{"updated":true}`；当日无此候选 → 404；参数缺失/格式非法 → 400 `{"error":"..."}`。

### `PUT /api/v1/trading/trade-log/meta` — 候选补成交编号/手续费（P2-交易36 治本，v3.54，2026-09-09）
> 需 trading 插件（403）。

截图入账/手动确认成交缺 orderId/fee 时，在**确认前**给当日候选补填（按 symbol+direction 定位，与 `PUT /trade-log/date` 同口径）。**body**：`{"symbol":"600206","direction":"SELL","orderId":"1234567890","fee":5.5}`（orderId/fee 均可选，只覆盖非空新值；两者皆空 → 400）。**响应**：`{"updated":true}`；当日无此候选/无可写新值 → `{"updated":false}`；symbol/direction 缺失 → 400。

### `DELETE /api/v1/trading/trade-log` — 丢弃一条保留候选（B6-5，2026-08-23，P1-交易18）
> 需 trading 插件（403）。

丢弃失败/不完整保留的「钉子户」候选（15:05 推送反复提醒同一笔时的出口）。**query**：`symbol`（可选）、`direction`（可选，BUY/SELL）。**响应**：`{"discarded":true}`；当日无此候选 → 404。

### `POST /api/v1/trading/screenshots` — 截图入账（2026-08-26，交易闭环第一环）
> 需 trading 插件（403）。

券商「当日委托/历史成交」截图（1-3 张）→ VLM 识别 → 归集为当日候选。**与 `POST /records/media` 的关键差异：不建记录、不落原图、不沉淀记忆**——截图入账是交易动作不是记录动作，候选确认落库后即权威数据，不污染 Feed/时间线。

- **multipart**：`files`（可多文件，字段名固定 `files`；每张 ≤ 5MB，超限/非图片/识别失败逐张降级进 `errors`）
- **响应**：`{"total":2,"processed":2,"candidates":[{"symbol":"002428","name":"云南锗业","direction":"SELL","price":93.48,"volume":100,"tradeDate":"2026-08-26","source":"image","complete":true}],"errors":[]}`
  - `total` 提交张数 / `processed` 成功识别张数 / `candidates` 当日全部候选（跨图 sameTrade ±10% 自动去重，含本次新增）/ `errors` 逐张失败原因（空 = 全成功）
  - `tradeDate`（2026-08-27）：截图表格「日期」列提取的成交日期；确认入账按此日期落 entryDate
- **校验失败**（空/超 3 张）→ 400 `{"error":"请选择截图"}` 等

### `DELETE /api/v1/trading/pushes/{id}` — 删除单条推送（B10-1，2026-08-23，P1-推送2）
> 需 trading 插件（403）。

删除当日一条推送事件（app 左滑删 / web 忽略按钮持久化——刷新/重启不再复活）。**响应**：`{"dismissed":true}`；当日无此事件 → 404（前端幂等成功）。

> **推送定时消失（RFC 20260825 §7，契约同步）**：`pushes/{date}.json` 记录新增 `expiresAt`（ISO LocalDateTime）——行情类（stop-loss / near-stop-loss / loss / gain / break-cost / market / session / buy-point）落盘时设为**次日 09:30**（当天收盘后晚上仍可看，次日开盘前自动清，防「收盘后看 App 推送没了」的误判），汇总类（每日操作总结 / 复盘）设为**次日 23:59**；Feed 读取侧过滤已过期条目（用户无需手动删时效推送）。旧数据无 `expiresAt` → 按类型默认保留期，不误删。

### `POST /api/v1/trading/imports/cash` — 资金股份查询导入（现金 + 精确成本）
> 需 trading 插件（403，W-P2-14 走查补全 2026-08-17）。

### `POST /api/v1/trading/imports/save` — 导入文件上传留存（通达信导出，2026-08-16）

**multipart**：`file`（通达信导出 txt，GBK/UTF-8 均可）

- **留存**：原始文件存 `data/{userId}/trading/imports/{yyyy-MM}/{ts}_{filename}`（可追溯）
- **转码**：GBK 自动转 UTF-8（UTF-8 严格解码失败按 GBK）
- **响应**：`{"path":"trading/imports/...","content":"转码后的 UTF-8 文本"}`——前端填充解析导入
- 需 trading 插件（403）。

### `POST /api/v1/trading/positions/import` — 持仓初始化导入（通达信导出 → 持仓快照，2026-08-16）

**query**：`replace`（可选，默认 `false`）——2026-08-18 确认批次：`replace=true` = **全量覆盖**（以文件为准，导入后移除文件里不存在的持仓，含 0 股残留；web 通达信持仓导入默认传 true）

**body**（数组，可空）：
```json
[{"symbol":"600519","name":"贵州茅台","quantity":100,"avgCost":1400,
  "stopLossPrice":1350,"buyPoint":"B1","role":"基石","entryDate":"2026-08-01"}]
```
- `symbol`/`quantity`/`avgCost` 必填；`name` 缺失时后端按代码行情补全
- `stopLossPrice`/`buyPoint`/`role`/`entryDate` 可选——通达信导出无止损/买点，导入后**必须补设**（R68）建议引擎才按纪律判定

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
      "rules": ["R81", "R66"]
    }
  ],
  "summary": "持仓总览一句话"
}
```

> `suggestion` 取值：buy / hold / reduce / clear。`position_percent` 后端按市值占比计算（确定性）。LLM 失败时降级返回基础数据（无建议字段），不抛错。需 trading 插件（403）。空仓返回空 advice。

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

## 13. 项目状态

### `GET /api/v1/project/status` — 项目状态摘要

返回 AdaiOS 项目的元信息：Kernel 组件、Domain OS 进度、RFC 状态列表等。
**不调用 AI，纯数据聚合，快速响应。**

**Response**

```json
{
  "project": "AdaiOS",
  "architecture": "modular-monolith",
  "kernelComponents": {
    "identity": "done",
    "record": "done",
    "timeline": "done",
    "context": "done",
    "memory": "done",
    "knowledge": "done"
  },
  "domainStatus": {
    "trading": "complete",
    "life": "skeleton",
    "project": "skeleton"
  },
  "rfcItems": [
    {"title": "Context 闭环", "date": "2026-07-18", "status": "implemented"},
    {"title": "双主页设计",  "date": "2026-07-22", "status": "implemented"}
  ],
  "commitCount": 27,
  "apiEndpoints": 21
}
```

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `rfcItems` | RfcItem[] | RFC 状态列表，每项含 title / date / status |
| `rfcItems[].status` | String | `proposed` / `approved` / `implemented` / `deprecated` / `unknown` |
| `apiEndpoints` | Integer? | API 端点总数；`null` = endpoints.txt 资源缺失（REVIEW #247，与「真 0 个」区分），前端显示「未知」 |
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
| 加载项目状态 | `GET /api/v1/project/status` |
| 任务列表 | `GET /api/v1/project/tasks` |
| 创建任务 | `POST /api/v1/project/tasks` |
| 更新任务 | `PUT /api/v1/project/tasks/{id}` |
| 删除任务 | `DELETE /api/v1/project/tasks/{id}` |
| 任务统计 | `GET /api/v1/project/tasks/stats` |
| 卡片迁移 | `POST /api/v1/cards/migrate` |
| 卡片清理 | `POST /api/v1/admin/cards/cleanup` |

---

## 15. 项目任务

轻量任务系统，File First 存储于 `data/project/tasks/YYYY/MM.md`。

### 任务模型

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `id` | String | 自动生成的唯一 ID，格式 `task_YYYYMMDD_HHmmss` |
| `title` | String | 任务标题（必填） |
| `description` | String | 任务描述（可选） |
| `status` | String | `TODO` / `DOING` / `DONE` / `CANCELLED` |
| `priority` | String | `P0` / `P1` / `P2` / `P3` (默认 P2) |
| `tags` | String[] | 标签列表（可选） |
| `rfcRef` | String | 关联 RFC 文件名（可选，如 `20260725-layer6`） |
| `sourceRecordId` | String | 源记录 ID（R2，可选）：记录自动转任务（D1 通用化：任何可执行记录即转，不限 domain=project）时关联的 `rec_xxx`；前端手动建任务为 null |
| `createdAt` | String | 创建日期 `yyyy-MM-dd` |
| `updatedAt` | String | 更新日期 `yyyy-MM-dd` |

### `GET /api/v1/project/tasks` — 获取任务列表

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `status` | String | 否 | 按状态筛选：`TODO` / `DOING` / `DONE` / `CANCELLED` |
| `tag` | String | 否 | 按标签筛选 |

**Response** — `Task[]`

```json
[
  {
    "id": "task_20260726_043000",
    "title": "接入 A 股行情",
    "description": "实现东方财富行情接口",
    "status": "DOING",
    "priority": "P1",
    "tags": ["kernel", "market"],
    "rfcRef": null,
    "createdAt": "2026-07-26",
    "updatedAt": "2026-07-26"
  }
]
```

### `POST /api/v1/project/tasks` — 创建任务

**Request Body**

```json
{
  "title": "接入 A 股行情",
  "description": "实现东方财富行情接口",
  "priority": "P1",
  "tags": ["kernel", "market"],
  "rfcRef": null
}
```

**Response** — 完整的 `Task` 对象（201 Created）

### `PUT /api/v1/project/tasks/{id}` — 更新任务

**Request Body**（所有字段可选，仅传需要更新的字段）

```json
{
  "title": "接入 A 股行情（含缓存）",
  "status": "DOING",
  "priority": "P0"
}
```

**Response** — 更新后的完整 `Task` 对象（200 OK）

### `DELETE /api/v1/project/tasks/{id}` — 删除任务

**Response** — 204 No Content

### `GET /api/v1/project/tasks/stats` — 任务统计

**Response**

```json
{
  "total": 10,
  "todo": 4,
  "doing": 2,
  "done": 3,
  "cancelled": 1
}
```

---

## 16. 账号（多账号功能层）

> v1.0.0 多账号：账号由 adai-admin 后台创建（**不做注册**），adai-app / adai-web 前端登录后从可用账号列表选择/切换（`GET /api/v1/accounts/available`，**需登录**，仅返回 enabled 账号——产品端遗留选号）；前端记住上次账号（web 用 localStorage / io 用 shared_preferences，wasm 下 shared_preferences 插件不注册）+ 随时切换。seed 管理员 `admin` 由后端首次启动自动预置（**2026-09-04 账号矩阵**：内置管理员由 `adai` 迁为 `admin`（后台管理专用）；`adai` 降为产品主账号 role=user——个人数据在 `data/adai/` 不变；再建普通受限账号（无插件）供家庭/他人）。
>
> **管理鉴权（REVIEW #178，2026-09-02）**：管理口并入统一登录——本节除 `GET /api/v1/accounts/available`（**仅需登录**，产品端遗留选号）与 `GET /api/v1/me/plugins`（产品端，仅需登录）外，其余端点（账号 CRUD / 插件合并）与 §17 管理端所有端点均要求 `Authorization: Bearer <token>` 且会话账号 **role=admin**（非 admin → 403「仅管理员账号可访问」）；admin 会话保留客户端 `X-User-Id`（控制台跨账号治理浏览）。`X-Admin-Token` 体系已退役删除（`AdminAuthInterceptor` / `adai.security.admin-token` / env `ADAI_ADMIN_TOKEN` / 前端 `ADMIN_TOKEN` 全部移除）。账号响应一律经 AccountView DTO 过滤，**不含 passwordHash**（bcrypt 哈希不下发）。
>
> **插件模型（RFC 20260814 + RFC 20260829）**：Account 带 `plugins`（`["trading","project"]`；**2026-09-06 learn 插件 V1 注册第三个插件 `learn`**——`trading`/`project`/`learn` 是 adai 拥有并受控开放的插件（Domain/能力），启用载体 = 账号 plugins 字段；Kernel 基础服务（记录/问答/记忆/档案/时间线/搜索/待办）人人都有，不在插件表。seed admin `admin` 默认 `["trading","project"]`（新环境预置兜底）；`adai`（产品主账号）持 `["trading","project"]`；新账号默认空。plugins 决定：知识/行情注入、模块显隐（前端 `GET /me/plugins`）、promote 权限、learn 消化端点（403 门控）。

### `GET /api/v1/me/plugins` — 当前用户启用插件（前端模块显隐）

**需登录**（`Authorization: Bearer`，会话账号 = 当前用户）。返回当前用户启用的插件名列表；账号不存在 → 空列表。adai-app / adai-web 据此显隐插件模块（交易页 / 阿呆系统 / 项目仪表盘），基础服务模块不依赖此端点。

**Request Headers**

- `X-User-Id` — 用户标识（可选，兼容保留；后端以会话 userId 为准，客户端传值被覆盖）

**Response**（`List<String>`）

```json
[ "project", "trading" ]
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
    "plugins": ["trading", "project"]
  },
  {
    "userId": "adai",
    "role": "user",
    "enabled": true,
    "createdAt": "2026-08-02",
    "plugins": ["trading", "project"]
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
- `plugins` 可选，默认 `[]`（新用户只有基础服务）；仅允许 `trading` / `project`，非法 → 400
- `password` 可选（**初始密码**，≥8 位，过短 → 400；不传则账号初始无密码——无法登录，可之后由 admin 用 PATCH 重置，REVIEW #178）
- `400` — userId 已存在 / 格式非法（仅 `[a-zA-Z0-9_-]+`）/ **保留字 `default`（task-log #149：历史遗留测试数据目录名，禁建真实账号）** / role 非法 / plugins 非法 / 初始密码 <8 位

### `PATCH /api/v1/accounts/{userId}` — 更新账号

**Request Body**

```json
{ "enabled": false }
```

- `enabled` / `role` / `plugins` 均可选，缺省保持原值（只改 enabled 不清空 plugins）；**清空插件须显式传空数组 `[]`**（传 null 视为缺省保留，P3 2026-08-17 契约明确）
- `plugins` 传全量列表（如 `["trading"]`），仅允许 `trading` / `project`，非法 → 400
- `password` 可选（**重置密码**，≥8 位，过短 → 400「新密码长度至少 8 位」；REVIEW #178）——重置后踢除该账号**全部**会话（`AuthService.kickSessions`，被重置者需重新登录）；**不携带则保留既有 passwordHash**（修复「只改 enabled/role 即清空密码」bug）
- **内置管理员 `admin`（2026-09-04 前为 `adai`，已迁移）不可禁用、不可降级**（400）
- `404` — 账号不存在

### `DELETE /api/v1/accounts/{userId}` — 删除账号

- **内置管理员 `admin`（2026-09-04 前为 `adai`，已迁移）不可删除**（400）
- `204` — 删除成功；`404` — 账号不存在

### `PATCH /api/v1/accounts/{userId}/plugins` — 合并插件（S-R2 服务端原子语义）

> REVIEW S-R2（2026-08-15）：根治前端全量 PATCH read-modify-write 并发互覆（快速连点两个开关不再丢）。服务端账号级锁内读改写合并。

**Request body**

| 字段 | 类型 | 说明 |
|:-----|:-----|:-----|
| `add` | String[] | 要启用的插件名（`trading`/`project`，可选，默认空）|
| `remove` | String[] | 要停用的插件名（可选，默认空）|

**Response** — `200` 合并后的 `Account`；`400` — 插件名非法 / **内置管理员插件受保护**；`404` — 账号不存在

```json
{
  "userId": "alice",
  "role": "user",
  "enabled": true,
  "createdAt": "2026-08-02",
  "plugins": ["trading", "project"]
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

> **learn 插件 V1（2026-09-06 用户拍板开工）**：外部内容（视频字幕/文章/链接原文）喂入 → AI 结构化卡片（RFC 3.4 渐进式摘要四段）→ File First 落 `data/{userId}/learn/{type}/{yyyy-MM-dd}_{title}.md` → 列表/资产树查询。V1 为**独立端点喂入**（2026-09-06 用户拍板：仿截图入账先例，learn 消化是动作不是记录——不建记录、不沉淀记忆、不污染 Feed/时间线；**不经 POST /records 主链路**）。资产页浏览（目录树+全文渲染）与 LearnKnowledgeSource 问答注入为 L2。
>
> 全部端点需 learn 插件（未启用 403「learn 插件未启用」）；X-User-Id 隔离 `data/{userId}/learn/`。type（ai/trading/other）是**卡片文件分类，非插件 domain 收敛对象**——learn 不进 life/trading/project 收敛（D5 不受影响）；trade_related 仅 type=trading 内容有意义（V1 只记录不联动规则库，防语义漂移走用户审核闸）。
>
> **L2（2026-09-07）问答注入**：新增 `LearnKnowledgeSource`（kernel 知识源，name=learn → PluginRegistry 映射 learn 插件门控）——ContextEngine 按用户 enabledPlugins 注入最近学习笔记（`## 你最近的学习笔记`，标题+type+核心观点，上限 5 篇；无卡片不注入；损坏文件/_raw 跳过；globalContext 注入 + enrich 空防双份）——你问「上次讲 RAG 那篇说了啥」时阿呆能引用自己消化过的卡片作答（RFC 3.7 ③ 价值呈现）。
>
> **V2 消化闭环第一批（2026-09-07 复习流转 + 编辑）**：卡片 `status` 从 V1 固定 new 变为可流转 new→review→done（PATCH 端点）；正文编辑支撑（复述段建模 retell + PATCH 编辑端点）——「对话流让阿呆改」的后端能力就绪（前端对话流接线随 UI 批）。
>
> **V2 审查修复批（2026-09-07，learn V2 增量深审 v3.53）**：流转只允许相邻（new↔review、review→done、done→review，跳变 400）；进入 review 写 `review_at`（提醒计时起点）+ `reminded_at` 节流；同 type+title **任意日期**同名拒绝（跨日同名歧义根治）；learn_card_id = 源卡真实路径（清洗后标题）；复习提醒开关 learn 侧可达（GET/PUT `/learn/push-settings[/learn-review]`，纯 learn 用户可自关）；Feed 类型级门控。
>
> **抓取批（2026-09-12，v3.57，RFC 20260912 D 形态阶段 1 抓取主干）**：**D 形态与 B 形态的分水岭是「抓取进服务端」**——B 要求用户自己搞到字幕/原文再粘贴（最费力的一步留给用户），D 由阿呆完成。喂入支持 `url` 后，用户只丢一个 B站链接或文章地址：服务端抓元数据/字幕/正文；**无字幕则先报价、用户点头再走云端转写**（实测多数视频确实没有可获取的字幕，所以转写是必经路径而非降级）。产物契约、卡片模板、落盘目录沿用既有 learn 契约不变（A 技能与 D 形态共用同一份产物格式，不建第二套）。
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

- `status`：`pending`（受理，后台执行）/ `running`（同 user 已有任务在跑——含等确认期间再次提交，会如实回 `needs_confirmation`，不覆盖待确认任务）/ `needs_confirmation`（**需用户确认转写费用**，见下）
- `400`：`url` 与 `content` 都为空、type 非法、执行器队列满（「消化任务繁忙」）；**平台不支持**也走 400 + 人话（「YouTube 从这台服务器连不上…把字幕或正文粘进来更稳」）
- 抓取/转写/结构化的进行与结果一律走 `GET /learn/digest/status` 轮询
- `403`：learn 插件未启用

**流程（服务端）**：判源类型 → 抓元数据 + 字幕/正文（**源必留痕** `_raw/`）→ 有字幕直接结构化；**无字幕 → 报价 + 等确认**（见 `/digest/confirm`）→ 云端转写 → LLM 六段结构化 → 落卡片 → 轮询回 `done`

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
- 卡片消化完成不在此响应返回，走 `GET /learn/digest/status` 轮询到 `done` 后按 `type/title` 经 `GET /learn/card` 打开全文
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
| `stage` | String? | **v3.57**：进行中阶段 `fetching`（抓取原文）/ `transcribing`（云端转写，分钟级）/ `structuring`（整理成卡片）；任务结束后清空 |
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

### `GET /api/v1/learn/cards` — 卡片列表（v3.48）

**Query Parameters**：`type`（可选 ai/trading/other；缺省返回全部，按 created 倒序）

**Response** `200` LearnCard 数组（元数据 + 正文段字段）。`type` 非法 → 400。

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
