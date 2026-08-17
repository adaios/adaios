# 批次变更日志（Change Log）

> 根 CLAUDE.md「当前焦点·已完成」历史条目归档（RFC `20260815-docs-governance`：状态与历史分离）。
> **规则：新批次合入时，在顶部追加一行**（日期 | 批次 | 一句话摘要 | 测试数变化）。详情以 git log / REVIEW 已修复区为准。

| 日期 | 批次 | 摘要 | 测试数变化 |
|:-----|:-----|:-----|:-----------|
| 2026-08-17 | Review 修复批 R10（P3 深水区）| **W-P3-4** _closeChat firstWhere→indexWhere（防 StateError）；**W-P3-13** GlmResponseParser 截断不劈 emoji（surrogate pair）；**W-P3-17** LocalFileStorage stripUserPrefix 与 resolve 同款 default 兜底（路径剥不净）；**W-P3-20** promote 脱敏正则兼容千分位（1,400 漏脱敏）；**W-P3-24/25/26** status 数字核对 + multimodal RFC 待确认勾销 + feed 示例补 domain + _index 补 memory-os-design；核实 W-P3-3（最后记录文案已消失）；后端 640 · web 98 全绿 | — |
| 2026-08-17 | Review 修复批 R9（P3 尾尾批）| **web 插件重试 SnackBar**：失败先 clear 再 show（不队列堆积）+ 重试成功 hide（W-P3-2）；**IndexedStack ValueKey 保活**（插件中部插入不重置 state，W-P3-1）+ _select 传 label + fallback 空列表防御；**app superseded 记忆隐藏待办标记**（172）；**ANALYSIS system 越界 domain 注明会被修正**；CLAUDE.md 目录树去重 + 表格拆行；docs/README 断链修正；task-plugin-model T1.3 标 superseded；api-spec PATCH 契约明确「清空传空数组」；核实 6 条 P3 已闭环（全图 Dialog errorBuilder/卡片删除精确匹配/RFC frontmatter/存量 domain 合法/首帧 await 设计权衡/max_tokens 已修）；后端 640 · web 98 · app 118 全绿 | — |
| 2026-08-17 | Review 修复批 R8（P3 尾批）| **isValidPlugins 查重**（["trading","trading"] 拒绝落盘）；**gateDomain 未知 domain 收敛 life**（白名单防脏数据）；**HEIC/HEIF 正确 MIME**（此前误标 png）；api-spec domain 关键词同步代码（trading +4/project +1）；frontend-reference 补 ask-batch 行；核实 5 条 P3 已随演进闭环（emoji 截断/limit toast/ImagePicker limit/max_tokens/多图回答展开）；后端 640 · app 118 全绿 | — |
| 2026-08-17 | Review 修复批 R7（P3 收尾批）| **八端点 controller 测试补齐**（watchlist/sold/score/transfer/transfers/buy-points/account/imports-cash）；**web DTO 测试补齐**（SoldScore/SoldTrade/Watchlist/Account 6 个）；KlineService 降级日志 debug→warn（生产可观测）；WeChatPushChannel InterruptedException 单独处理（不污染调度线程）；buy-point-rules 登记 engine/README（孤儿规格）；gap lines 修正；change-log 两行补端点增量；后端 629→640（+11）· web 92→98（+6）| — |
| 2026-08-17 | Review 修复批 R6（P2/P3 web 批）| **P2-8** _loadAll 入口 mounted 守卫；**P2-10** 打分空列表短路 + 请求在途去重；**P2-11** 「纪律遵守率」从胜率改 verdict 口径（胜率单独展示）；**P2-12** 行为模式单字误配改双字词组 + 否定排除；**P2-13** 删自选带确认 + 心理标注错误反馈；**P2-14** 账户卡 FittedBox 防溢出 + 千分位；**P2-15** 打分列中性色阶（不借红涨绿亏）+ '—' 固定灰；P3：lookup 300ms 防抖、转账金额 NaN 校验 + 提交反馈、本金 ¥ 千分位、上次更新文案如实（30 分钟自动刷新）、行为模式 Wrap、isTdxExport 认「成本」表头；web 89→92（+3 P2 口径测试）| — |
| 2026-08-17 | Review 修复批 R5（P2 后端 9 连）| **P2-1** 打分线程池 @PreDestroy shutdown + 超时不产空行；**P2-2** 自选扫描 8 并发 + 按标的异常隔离；**P2-3** 腾讯 K 线按日缓存（东财被限时兜底不再每请求打腾讯）；**P2-5** R66 阈值 -10%→-5%（用户确认贴合课程 R67/R72 3-5%）；**P2-6** KDJ 阈值 J<20→J<13（课程锚点）；**P2-7** B1? 候选不推送（「不硬推」）；**P2-21** advice 输出侧过 r81Applicable（超100万不强制）；**P2-22** importPositions avgCost/quantity 校验（防 NPE）+ name 单次 lookup + missingStopLoss 失真修复；P3：B2 推送补止损提醒、节假日守卫（2026-27 主要休市）、调度器 4 线程、Feed 标题按 type 映射、near-stop-loss 文案用可配值、R120/R85 javadoc 修正；后端 626→629（+3 verdict/节假日测试）| — |
| 2026-08-17 | app 稳定性批（自动刷新 + 竞态守卫 + 上传锁）| app 交易页 **30 分钟自动刷新**（对齐 web B3，Timer dispose 清理）；**次级数据代际令牌**（_loadAux 响应乱序旧代不覆盖新代）；**图片上传批次锁**（P3-8：上传期间再次发送提示等待，进度条不互相覆盖）；核实 P3-4 盲弹/P3-6 看图占位已随演进闭环；app 117→118（+1 批次锁测试）| — |
| 2026-08-17 | Review 修复批 R4（P1 五连）| **P1-1 切入自动刷新死代码修复**（web 判 label=='交易' 恒 false，改判 plugin=='trading'，+壳层测试）；**P1-2 recordTrade 买卖同步市值**（总资产=现金+市值不变式，只差手续费，+2 测试）；**P1-3 收盘缺行情跳过保存**（不全覆盖总资产，+2 测试）；**P1-7 web 可降级请求分离**（buy-points 失败不再整页白屏，+1 测试）；**P1-8 清仓打分按序索引匹配**（同代码多笔不错挂）；**P1-10 api-spec buy-points 示例修正**（score 0-100/signals 实际文案）；后端 622→626（+4）· web 87→89（+2）| — |
| 2026-08-17 | app 交易对齐 web（①必对齐三件 + ②自选/清仓只读）| app 交易页对齐：①账户总览卡（总资产/可用/可取/市值/当日盈亏/总盈亏=资产-本金/本金标注，替代旧 3 项卡）、持仓卡显示止损位（未设橙色提示）、记录交易默认 -7% 止损（价格自动带出可改，对齐 web）；②自选股 + B1/B2 买点信号、清仓股复盘 + 三维打分（只读，最近 20 笔提示用桌面端）；app 116→117（+1 对齐渲染测试）| — |
| 2026-08-17 | Review 修复批 R3（导入解析失败禁止落零 P1-交易5）| **资金查询导入落零风险修复**：此前首行格式对不上「余额/可用/可取/参考市值/资产/盈亏」时静默全 0 覆盖 account.json + cashBalance（贴错文件即毁数据）；修：parseCash 返回 headerMatched，未命中抛 TradingException（400 + 人话「无法识别资金股份查询格式」），绝不落零（B51 检查点）；web 导入 Dialog 失败统一 toast 反馈（此前静默）；后端 620→622（+2）| — |
| 2026-08-17 | Review 修复批 R2（简报盈亏失真 + loss 文案按止损区分）| **简报盈亏失真修复**：简报 LLM 拿昨天记录「买入京东方 1000股@5.2」自算 (5.81-5.2)/5.2=11.73% 说「浮盈11.73%」——实际持仓 5300股@6.0421 亏 3.8%。修：简报注入真实持仓快照（positionSummaryLines，权威口径禁从旧记录推算）；**loss 推送文案按是否设止损区分**（已设止损 →「盯紧止损位 X（R66）」；未设 →「你还没设止损位」——之前山西汾酒已补止损却仍说没设）；后端 619→620（+1）| — |
| 2026-08-17 | Review 修复批 R1 续（持仓编辑补端点 + 默认 -7% 止损）| **持仓编辑 404 修复**：前端/测试一直在调 PUT /positions/{symbol} 但后端从未实现（web 点编辑保存必 404）——补 updatePositionMeta + PUT 端点（role/止损位，只更新非空字段，404/400/403 齐全）；**默认 -7% 止损**：记录交易表单填价格自动带出止损=价格×0.93（可改，用户 2026-08-17 设定）；后端 612→619（+7）· 端点 71→72 | — |
| 2026-08-17 | Review 修复批 R1（择时路径 + 推送文案）| **择时状态生产盲区修复**：TradingSessionPushService 的 current.md 从硬编码 `../../os/...` 改配置注入 `adai.knowledge.trading-engine-path`（3487b00 只修了建议引擎、漏了时段推送——生产解析成 /opt/os/... 缺 adaios 级 → 择时状态恒「未知」）；loss 推送文案如实化（「触发止损预警」→「单日大跌，留意风险（你还没设止损位，想好怎么走）」——原文案在未设止损时误导）；后端 610→612（+2 择时路径测试）| — |
| 2026-08-16 | 交易自理批5 续（D2 行为模式）| 清仓心理标注按关键词归类显示行为模式（追高/恐慌/贪心/死扛/犹豫/急躁，标注后自动聚合）；web 87 全绿 | — |
| 2026-08-16 | 交易自理批5（D3 复盘三维打分）| 清仓复盘打分：买点维度（买入日回溯 K 线 → B1/B2 完美图匹配度，追高无形态=25 分）+ 执行维度（verdict 纪律：盈利 90 / R53 45 / R66 15）+ 选股维度预留（数据积累后）；`GET /trading/sold/score` 端点；web 清仓 Tab 三维打分列；后端 605→610（+5）· 端点 70→71 | — |
| 2026-08-16 | 交易自理批4（C2 自选股买点）| 自选股买点判定：东财 K 线主源 + 腾讯降级（KlineService）+ KDJ(9,3,3) + BuyPointDetector（B1 回调≥50%+缩量0.7+KDJ.J<20 / B2 放量1.5+破前高20日，5 参数建议值可配）；`GET /trading/buy-points` 端点；收盘 15:10 定时扫描 + 命中推送「到买点了」；web 自选 Tab 信号列；后端 602→605（+3）· 端点 69→70 | — |
| 2026-08-16 | 交易自理批3（E2/B3/D2）| 静默刷新（已有数据不闪整页 loading）；每 30 分钟自动刷新（跟随交易节奏）；清仓纪律统计（盈/亏/违R66/违R53/遵守率）；web 82 全绿 | — |
| 2026-08-16 | 交易自理批2（D1/C3/E1）| 清仓股 verdict（R53 没涨=错 / R66 止损，162 笔批量判）；接近止损预警（距止损 ≤2% 可配推送）；web Tab 工作区四分区（持仓/自选/清仓/资金替代纵向堆叠）+ 清仓表 verdict 列；后端 596→602 · web 82 | — |
| 2026-08-16 | 交易自理批1（A+B）| 手续费自动计算（佣金万0.854四舍五入/印花税万5去尾/过户费沪万0.1四舍五入，四笔交割实例验证）；转账表单（净投入跟踪 principal/cash）；收盘 15:05 账户自动更新（市值/当日盈亏/浮盈）；顶部上次更新时间戳 + 点击更新；后端 583→593（+10）· web 82 | 端点 67→69 |
| 2026-08-16 | 账户总览卡 + 账户快照 | `GET /trading/account` 账户快照（资产/可用/可取/市值/盈亏/当日盈亏，资金查询导入存 account.json）；web 顶层账户卡（总资产为主 + 6 项）；组合快照行情注入修复（总盈亏 15235.55 与券商一致）；解析器正则 6 组扩展 | 端点 66→67 |
| 2026-08-16 | 交易数据智能——自选/清仓/资金三块落地 | 自选股（WatchlistItem + 通达信自选导入，形态/指标提示=买点原料）；清仓股（SoldTrade + 导入 + 心理标注，复盘闭环）；资金股份查询（现金 292.88 + 精确成本 4 位，R81 分母修正）；Java 解析器 TradingImportParser（表头定位三格式）；web 交易页三区块 + 通用导入 Dialog（选择文件/粘贴）；7 新端点；后端 576→579（+3 解析器测试）· web 82 | 端点 59→66 |
| 2026-08-16 | 导入文件上传留存 + 交易页自动刷新 | 后端 `POST /trading/imports/save`（multipart 留存 data/imports/ + GBK 自动转 UTF-8）；web 导入 Dialog 加「选择文件」（上传→转码→自动解析导入，免复制粘贴）；交易页切入/点记录交易自动刷新（保活缓存不再显示旧数据）；盈亏实时注入（getPositions 行情） | 后端 576（+2）· web 82 | 端点 58→59 |
| 2026-08-16 | 通达信持仓导入 + 代码带名称 | `POST /trading/positions/import` 持仓初始化导入（通达信导出快照，按 symbol upsert，name 缺失行情补全，返回未设止损列表 R68 提示补设）+ `GET /trading/lookup` 代码查名；web 批量导入自动识别通达信格式（表头定位列，制表符/空格）+ 记录交易输入 6 位代码自动带出名称（二次确认可改）；api-spec 登记 2 端点 | 后端 572（+4）· web 81（+4）| 端点 56→58 |
| 2026-08-16 | 生产部署（时段推送 + 知识层上线）| deploy-gate 全过（三关 + smoke 6 端点）；os/trading-engine/knowledge/ 上传生产 + .env 配 `ADAI_TRADING_KNOWLEDGE_PATH`（G-4 路径生产生效）+ `ADAI_PUSH_WECHAT_SENDKEY`（微信渠道生产就绪）；生产实测：京东方建议引擎引用 R81 真实规则（占比 100% 超 25% 上限 → reduce），知识加载 rules=11KB 全通 | — |
| 2026-08-16 | 交易时段节奏推送（RFC 两阶段全做）| `PushChannel` 渠道插件化（kernel/push 接口 + Feed 默认 + 微信 Server酱，未配置 key 自动禁用）；`TradingSessionPushService` 三节点（早盘计划 9:15 / 午间跟踪 12:00 / 尾盘建议 14:50，cron 可配）+ 内容两阶段（LLM 自然语言生成，失败降级模板）；`MarketAlertService` 异动改走渠道（Feed+微信同发，真止损/早盘/午间/尾盘全链路）；择时状态读 `current.md`；feature-reference 补主动推送章节 | 后端 568（+8）|
| 2026-08-16 | 真止损预警（A 最小闭环）| `MarketAlertService` 新增 `stop-loss` 检测：现价跌破用户预设止损位 → R66 硬判定（复用 G-3 引擎口径，与建议引擎一致）→ 主动推送 Feed「已跌破你的止损位 X，按纪律该清仓了」；未设止损持仓跳过（R68）；当日去重沿用；文案无第三视角；api-spec 补 push 类型说明 | 后端 560（+4）|
| 2026-08-16 | 框架+插件审查 P2 清尾批 | FP-P2a~i：parseLlmAdvice 输出侧校验（BREACHED→强制 clear、OVER_WEIGHT→buy 保守改 reduce）；R81 100万前提（超 100 万不强制，参考 R82-R95）；测试补断言；gap 补 frontmatter（D44）；docs/README 登记新文档；三阶段 RFC 升 approved + 实施记录；gap 指向正式总纲；update-current.sh 相对路径 + CLAUDE.md 收录；编号对拍（R1-R120/E1-E30，K39）| 后端 556（+1）|
| 2026-08-16 | 框架+插件审查修复批 | 三官 deep 审查（backend/knowledge/docs）后发现修复：yml 路径 11-context→knowledge/context（运行时断链，FP-P1）；R81 占比分母改总资产（现金纳入，FP-P2 + 测试）；update-current.sh 幂等 + 时间戳语义 + 声明修正（FP-P3/S4）；R66 现价口径注明（FP-P4）；总纲 §五 刷新全 ✅（FP-S1）；引擎口径契约测试 RuleKnowledgeContractTest（FP-S2/B44）；rules-api.md §2/§3 同步（FP-S3）| 后端 555（+4）|
| 2026-08-16 | 框架+插件形态（G-1~G-6 全落地）| 总纲提位正式架构文档（`architecture/framework-plus-plugin-model.md`）+ 交易 Agent 三阶段 RFC + gap 对账；G-1 行情载体归 trading 插件域（git mv）；G-2 交易读端点门控 + Brief 门控；G-3 `domain/trading/engine/` 规则引擎（R66 止损/R81 仓位/matchRules，规格 `os/trading-engine/engine/rules-api.md`）；G-4 `11-context`→`knowledge/context` + `update-current.sh` 半自动刷新；G-5 形态样板（Skill 包 + MCP 映射）；G-6 组合验证测试（读端点 403×5 + 行情注入门控）| 后端 551（+14）|
| 2026-08-16 | 带图发图即对话 + 交易建议引擎 | 带图：发图即分流（ask 直进对话/log 自然回执）+ S-2 聚合卡修复；交易：/trading/advice 建议引擎（R66-R95 硬约束）+ /trades/parse 一句话解析 + 资产卡+建议展示（无执行按钮）| 后端 499（+12）· app 112（+18）· admin 34（+1）|
| 2026-08-16 | P-be-01 安全 + admin 收敛 | 5 维护端点迁 /admin/** 鉴权（X-Admin-Token）+ 用户端恢复 PATCH /memory/{id}；admin 移除个人内容编辑（P-role 系列）；app 补记忆修正/待办完成 | 端点 55 · admin 34 |
| 2026-08-16 | 文档自动对齐门禁 | guard-align（端点/测试数/端点数 A1-A4）+ guard-context（任务上下文注入）+ guard-sediment（沉淀检查）+ pre-commit 四层（隐私/对齐/结构/防复发）+ 方法论放回仓库 | — |
| 2026-08-15 | 走查修复批 W2 前端续 | P1-W5 失败伪装空态五处修复（web search/memory/timeline + app timeline_modal/launcher——错误态+重试，失败不再显示「无数据」）；P1-W7 切 World 不中断上传（dispose 后继续传，UI 由 mounted 守卫）；P1-W9 全图 Dialog 3 处加 errorBuilder/loadingBuilder（防 404 白框）；P1-W11 空态/元信息文案对比度提亮 17 处（darkGrey6→4）| app 94 · web 47 |
| 2026-08-15 | 走查修复批 W2 前端 | P1-W6 三端请求超时 15s（_TimeoutClient 包装，waiting 不再无限转圈）；P1-W8 app 删除确认弹窗（与 web 对拍，DELETE 不可逆）；测试适配 | app 94 · web 47 · admin 33 |
| 2026-08-15 | 走查修复批 W2 后端 | P1-W14 STATEMENT prompt 闭合引号；P1-W12 parseDateTime 不再回退 now()（脏 createdAt 跳过）；P1-W13 门控旁路三修（PATCH domain gateDomain + project tasks 403 + cards/cleanup intent/日期收紧）；P1-W15 启动时全量重建标签索引（TagIndexBootstrap）；+403/round-trip 测试 | 后端 454（+1）|
| 2026-08-15 | 全维度走查修复批 W1 | P0-W1 卡片多行 turn 单行化（对话丢失根治 + round-trip 测试）；P1-W10 LauncherPage SafeArea（误触）；P1-W1 web 图片重试重走 uploadImage（F37）+ P1-W2 app 文本重试复用 cardId（幂等）；P1-W3/W4 搜索+时间线自然化（第一原则漏面）；P1-W16 基建自伤（docs/ai frontmatter 断链 + 审查官 8 + status 端点 52）| 后端 453（+3）· app 94 · web 47 |
| 2026-08-15 | image 记录展示自然化 | 第一原则覆盖 image 记录：标题=VLM 总结、正文逐行去【备注】/【图片文字】标签（用户看到的是自己的话+自然内容，无第三视角）；生产验证 image_qa 自然化生效 | 后端 450（+1）|
| 2026-08-15 | image_qa 展示自然化 + 图片预览修复 | 第一原则「无第三视角」落地：Feed/时间线 image_qa 转自然对话（标题=问句、正文=问/答两行、去问：/答：/图片记录：标签）；输入栏图片预览缓存 Uint8List + cacheWidth 降采样 + gaplessPlayback（打字 rebuild 不再闪烁/大图解码失败）；VISION 沉淀原则 | 后端 449（+3）|
| 2026-08-15 | S-R1/S-R2（deep 战略项）| S-R1 adai-app launcher 插件失败 SnackBar 反馈 + 重试（双端对拍 web）；S-R2 服务端合并插件端点 `PATCH /accounts/{id}/plugins`（账号级锁原子 add/remove，根治 PATCH 全量并发互覆）+ admin 前端改走合并语义 + 内置 admin 插件服务端保护；api-spec v3.20 | 后端 446（+6）|
| 2026-08-15 | deep 审核修复批 S2 | P2-B2 Account 拒绝 null userId（脏 JSON 全局中断）+ findById Objects.equals + MarketAlert filter 防护；P2-R3 admin 内置插件开关 isProtected 门控（禁用 + Tooltip）；P2-R2 launcher 测试补「仅 trading」+「500 降级」分支；P1-D1 review-context 断链引用更新；P2-D1/D2 RFC/docs 状态同步 | 后端 440（+1）· app 94（+2）|
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
