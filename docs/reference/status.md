# 状态快照（Status）

> **测试数/端点数/运行环境的唯一事实源**（RFC `20260815-docs-governance` 单一事实源化）。
> **更新规则：每批 /ship 时更新本文件**——AGENTS.md 及子项目 AGENTS.md 一律不复制数字，只指回本文件。

---

## 测试状态

| 端 | 测试数 | 失败 | 备注 |
|:---|:------:|:----:|:-----|
| 后端 adai-core | **690** | 0 | 16 Controller 接口测试全覆盖 + 多模态 + 鉴权 + 交易建议引擎（止损硬判定）+ 一句话交易解析 + 交易数据模型（逐笔流水/止损/买点）+ 带图 S-2 聚合修复 + G-1~G-6 框架+插件 + 真止损预警 + 时段节奏推送 + 通达信持仓导入/代码查名 + 持仓行情注入 + 导入文件上传留存（GBK 转码）+ C2 自选股买点判定（KDJ/B1回调缩量/B2放量破前高）+ D3 清仓复盘三维打分（买点回溯/执行纪律）+ 择时状态配置驱动读取 + 持仓元信息更新端点（PUT /positions/{symbol}，止损/角色）+ 简报注入真实持仓快照（防 LLM 算错盈亏）+ 导入解析失败禁止落零（P1-交易5）+ 买卖同步市值（P1-2）+ 收盘缺行情跳过（P1-3）+ P2 批（线程池/缓存/阈值-5%/KDJ-13/并发扫描/节假日）+ 八端点 controller 测试 + P2 批（原子写/插件缓存/任务删除门控/记忆三缺陷）+ S5 现金单一真源 + P1-交易4 占比分母含现金（回归测试）+ Memory 正文 `---` 分隔不截断（回归测试）+ RFC 20260817（推送开关/结构化推送/交易日志归集，PushSettings 4 + TradeLogCollect 5）+ 20260818 批次（历史成交导入解析/幂等/对账 + /trades/batch 补实现 + 持仓 replace 全量覆盖 + BUY 止损/买点放开 + 本金设置 PUT /principal）+ P1-1 交易归集 unknown 污染修复（拒绝归集/complete 补 symbol/dedupeKey name 兜底）+ P2-1 东财熔断（连续失败 3 次熔断 5 分钟 + 半开探测）+ 首屏提速（brief/cached 端点 2 测试）+ RFC 20260823（历史成交导入回填：幂等命中且缺成交时间 → 回填 updated 计数，回填 round-trip ×3 + 重传回填 + 已有时间不重填） |
| 前端 adai-app | **120** | 0 | Feed 状态机 12 + 6 页面 + 选号/切换链路 + 插件门控 + 带图发图即对话 + 交易建议 UI + 交易止损/买点 P0 + 交易对齐 web（账户总览卡/止损显示/默认-7%止损/自选买点/清仓打分）+ 30分钟自动刷新 + 次级数据代际守卫 + 上传批次锁 + 第一原则徽章移除（无记录/提问/领域系统标签）+ 20260818 简化（纯买卖记录去止损/买点 + 价格小键盘小数点）|
| 前端 adai-web | **104** | 0 | 桌面壳 + 交易管理端（持仓编辑/批量导入/复盘历史）+ 通达信导入解析 + 自选/清仓/资金区块 + 自选买点信号列（C2）+ 清仓三维打分列（D3）+ 行为模式统计（D2）+ 切入自动刷新修复（P1-1）+ 打分按序匹配（P1-8）+ 可降级请求分离（P1-7）+ P2 批（mounted守卫/胜率口径/否定词/千分位/打分列色/删除确认）+ DTO 测试补齐 + P1-5 回归 + 走查 8 项（可降级代际/历史日期竞态/图片回执自然化/交易成功 toast/首载失败错误态/错误人话/Feed 徽章移除）+ 20260818 批次（历史成交/持仓/CSV 三格式识别测试）+ RFC 20260823（历史成交 Tab 分组全字段渲染 / 空态 + 导入入口 / 非历史成交格式人话拒绝）|
| 前端 adai-admin | **34** | 0 | 账号/数据/系统/知识页（治理只读收敛）+ 插件开关 + 涨跌色红涨绿亏（darkRed token）|

## 端点/控制器计数（Gradle `endpoints.txt` 单一口径，REVIEW #228）

- Controller：**16**（`interfaces/*Controller.java`，含 MeController；GlobalExceptionHandler 非 Controller）
- 端点：**80**（`build/resources/main/META-INF/endpoints.txt` 实测；含 /trading/advice、/trading/trades/parse、GET /trading/trades 流水、/admin/** 维护端点迁移、/trading/lookup 代码查名、/trading/positions/import 持仓导入、PUT /trading/positions/{symbol} 持仓编辑、/trading/imports/save 文件留存、/trading/watchlist* 自选、/trading/buy-points 买点信号、/trading/sold* 清仓、/trading/sold/score 复盘打分、/trading/imports/cash 资金、/trading/account 账户快照、/trading/transfer 转账、/trading/transfers 流水、GET/PUT /trading/push-settings、/trading/trade-log、/trading/trade-log/confirm、POST /trading/trades/batch 批量记录、POST /trading/trades/import 历史成交导入、PUT /trading/principal 本金设置、/brief/cached 缓存简报 v3.24）
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
