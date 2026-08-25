# 状态快照（Status）

> **测试数/端点数/运行环境的唯一事实源**（RFC `20260815-docs-governance` 单一事实源化）。
> **更新规则：每批 /ship 时更新本文件**——AGENTS.md 及子项目 AGENTS.md 一律不复制数字，只指回本文件。

---

## 测试状态

| 端 | 测试数 | 失败 | 备注 |
|:---|:------:|:----:|:-----|
| 后端 adai-core | **758** | 0 | 16 Controller 接口测试全覆盖 + 多模态 + 鉴权 + 交易建议引擎（止损硬判定）+ 一句话交易解析 + 交易数据模型（逐笔流水/止损/买点）+ 带图 S-2 聚合修复 + G-1~G-6 框架+插件 + 真止损预警 + 时段节奏推送 + 通达信持仓导入/代码查名 + 持仓行情注入 + 导入文件上传留存（GBK 转码）+ C2 自选股买点判定（KDJ/B1回调缩量/B2放量破前高）+ D3 清仓复盘三维打分（买点回溯/执行纪律）+ 择时状态配置驱动读取 + 持仓元信息更新端点（PUT /positions/{symbol}，止损/角色）+ 简报注入真实持仓快照（防 LLM 算错盈亏）+ 导入解析失败禁止落零（P1-交易5）+ 买卖同步市值（P1-2）+ 收盘缺行情跳过（P1-3）+ P2 批（线程池/缓存/阈值-5%/KDJ-13/并发扫描/节假日）+ 八端点 controller 测试 + P2 批（原子写/插件缓存/任务删除门控/记忆三缺陷）+ S5 现金单一真源 + P1-交易4 占比分母含现金（回归测试）+ Memory 正文 `---` 分隔不截断（回归测试）+ RFC 20260817（推送开关/结构化推送/交易日志归集，PushSettings 4 + TradeLogCollect 5）+ 20260818 批次（历史成交导入解析/幂等/对账 + /trades/batch 补实现 + 持仓 replace 全量覆盖 + BUY 止损/买点放开 + 本金设置 PUT /principal）+ P1-1 交易归集 unknown 污染修复（拒绝归集/complete 补 symbol/dedupeKey name 兜底）+ P2-1 东财熔断（连续失败 3 次熔断 5 分钟 + 半开探测）+ 首屏提速（brief/cached 端点 2 测试）+ RFC 20260823（历史成交导入回填：幂等命中且缺成交时间 → 回填 updated 计数，回填 round-trip ×3 + 重传回填 + 已有时间不重填）+ **2026-08-23 走查修复批（B1-B5，21 项）**：confirm 失败候选保留 + account.json 原子 RMW（per-user 锁）+ direction @NotNull + batch 逐行校验 + 尾盘 r81Applicable + 收盘昨收残缺跳过 + 快照写失败告警 + verdict R53 延展 + 节假日 2026 官方/2027 预测 + MarketAlert 判定拆开 + dedupeKey volume 桶 + TradeLog save 锁 + MarketPush 读失败拒写回（+11 测试）+ **隔离审查残留批（B6-B7）**：MarketPush 结构校验 + sameTrade ±10% 区间去重 + 三处 changePercent 判空 + 快照写失败抛 StorageException + confirm 单 now + 切 Tab 静默刷新 + DELETE /trade-log 丢弃端点 + 真实仓储并发/损坏测试（+17 测试）+ **C 批**：confirm 处理期新候选合并保留 + 2027 中秋修正 + batch 空 400 + name 超长校验 + 锁收敛 userId（+3 测试）+ **2026-08-24 行情主源切换批**：KlineService 主源/兜底配置化（`adai.market.kline-primary` 默认 tencent——腾讯主源 + 东财探测兜底，生产东财被限不再刷 500+/日 WARN；Tencent 补 KlineSource 实现，+1 配置切换回归）+ **2026-08-25 推送渠道切换批**：BarkPushChannel（iOS 原生推送，PushChannel 第二外部实现，POST JSON，base-url 可配自托管；微信 Server酱 停用——免费 5 条/天不够，代码保留未配置即禁用；+4 测试）+ **2026-08-25 RFC 20260825 批次跟踪批**：批次推导（按日合并/LIFO/回合/初始批次/对账，TradingLotService）+ GET /trading/lots 批次视图 + 导入双模式（sync 同步持仓/append 补录，orderId 透传幂等）+ 每日操作总结（行为标注六类：亏损加仓/追高/短线新开/破止损未走/浮盈回吐/短线超期）+ 批次级止损推送 + 推送 expiresAt 定时消失（行情类次日 09:30/汇总类次日 23:59）+ recordTradeWithOrderId + 手动/导入交叉防重 + 卖出费用入回合（+22 测试） |
| 前端 adai-app | **125** | 0 | Feed 状态机 12 + 6 页面 + 选号/切换链路 + 插件门控 + 带图发图即对话 + 交易建议 UI + 交易止损/买点 P0 + 交易对齐 web（账户总览卡/止损显示/默认-7%止损/自选买点/清仓打分）+ 30分钟自动刷新 + 次级数据代际守卫 + 上传批次锁 + 第一原则徽章移除（无记录/提问/领域系统标签）+ 20260818 简化（纯买卖记录去止损/买点 + 价格小键盘小数点）+ **2026-08-23 app 体感批**（IndexedStack 保活/排序四修/Feed 错误态/任务编辑 PUT/launcher 伪0/三态/人话/代际令牌/方向色/时间戳）+ **P1-G6-1 守卫批**（timeline_modal await 后 setState 补 mounted 守卫×2 + 回归测试×2：关闭后响应到达不崩溃/正常路径渲染）+ **2026-08-25 批次简版批**（持仓卡第三行批次信息：批次数/最近买入/含底仓徽标/破止损警示，getLots 静默降级，+3 测试）|
| 前端 adai-web | **121** | 0 | 桌面壳 + 交易管理端（持仓编辑/批量导入/复盘历史）+ 通达信导入解析 + 自选/清仓/资金区块 + 自选买点信号列（C2）+ 清仓三维打分列（D3）+ 行为模式统计（D2）+ 切入自动刷新修复（P1-1）+ 打分按序匹配（P1-8）+ 可降级请求分离（P1-7）+ P2 批（mounted守卫/胜率口径/否定词/千分位/打分列色/删除确认）+ DTO 测试补齐 + P1-5 回归 + 走查 8 项（可降级代际/历史日期竞态/图片回执自然化/交易成功 toast/首载失败错误态/错误人话/Feed 徽章移除）+ 20260818 批次（历史成交/持仓/CSV 三格式识别测试）+ RFC 20260823（历史成交 Tab 分组全字段渲染 / 空态 + 导入入口 / 非历史成交格式人话拒绝）+ **2026-08-23 走查修复批**：资金区块 principal=0 总盈亏失真（totalPnl getter）+ 历史成交导入错误透出人话（extractApiErrorMessage）+ 久持小亏遵守率 R53（+3 测试）+ **2026-08-25 批次视图批（RFC 20260825）**：持仓行「批次」弹窗（批次明细/初始底仓/回合盈亏/对账警告）+ 导入结果每日操作总结卡片（syncMode + summary 行为标注，红涨绿亏）+ getLots DTO（+9 测试）|
| 前端 adai-admin | **34** | 0 | 账号/数据/系统/知识页（治理只读收敛）+ 插件开关 + 涨跌色红涨绿亏（darkRed token）|

## 端点/控制器计数（Gradle `endpoints.txt` 单一口径，REVIEW #228）

- Controller：**16**（`interfaces/*Controller.java`，含 MeController；GlobalExceptionHandler 非 Controller）
- 端点：**83**（`build/resources/main/META-INF/endpoints.txt` 实测；含 /trading/advice、/trading/trades/parse、GET /trading/trades 流水、/admin/** 维护端点迁移、/trading/lookup 代码查名、/trading/positions/import 持仓导入、PUT /trading/positions/{symbol} 持仓编辑、/trading/imports/save 文件留存、/trading/watchlist* 自选、/trading/buy-points 买点信号、/trading/sold* 清仓、/trading/sold/score 复盘打分、/trading/imports/cash 资金、/trading/account 账户快照、/trading/transfer 转账、/trading/transfers 流水、GET/PUT /trading/push-settings、/trading/trade-log、/trading/trade-log/confirm、POST /trading/trades/batch 批量记录、POST /trading/trades/import 历史成交导入、PUT /trading/principal 本金设置、**GET /trading/lots 批次视图（RFC 20260825）**、/brief/cached 缓存简报 v3.24）
- 端点计数规则：以 Gradle 生成的 `endpoints.txt` 为准，禁止扫源码回退（REVIEW #228/#187）

## 运行环境

- 后端：`localhost:8080`（DeepSeek 模式 + GLM 视觉——`.env` 需配 `GLM_API_KEY` 才有真 VLM 理解，无 key 时上传降级不丢数据）
- 前端：adai-app `localhost:8081`（移动端入口，Web 形态）· adai-web `localhost:8082`（桌面端入口）· adai-admin `localhost:8083`（产品后台）（均 Flutter Web + CanvasKit 补丁）
- **生产服务器**：`82.156.111.146`（北京 · 腾讯云轻量 · 2核4G · Ubuntu 24.04 LTS · 2026-08-19 从 49.235.37.220 迁移，旧服务器到期下线）
- **生产域名**：`adaiadai.com`（已注册；DNS 解析 + HTTPS 待配置，规划见 `docs/deployment/backend-deployment.md` §10）
- 生产访问：web `http://82.156.111.146:8082` · admin `http://82.156.111.146:8083` · 后端 `http://82.156.111.146:8080`
- 生产目录：`/opt/adaios`（backend/data/web/admin/os；`.env` 含密钥不进 git）
- 数据路径：`data/{userId}/...`（本机账号 = `data/adai/`，default 已迁移移除；测试可用 `data/default/`）

## 发布状态

- **v1.0.0**：Release Notes + 数据冻结 + 插件门控就绪；tag + 部署待用户确认触发（2026-08-15 用户决策：**先治理流程，发布顺延**）
- 后续候选：多账号前端选号（v1.0.1）、登录体系（#179，v1.0.1 立项）

## 未修项

- 见 `docs/review/REVIEW.md`（战略 + P0-P2 唯一滚动区）
- 待办/观察项：见 `docs/reference/task-log.md`
