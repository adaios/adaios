---
title: 项目审核全量状态报告
updated: 2026-08-30
last-review: 2026-08-30
baseline: c9c2918..fec30bf（案例库批 1-4，36 文件；前值：RFC 20260825 批次跟踪批 34 文件）
mode: deep 增量审查（案例库批次——**降级：子代理环境故障，主会话四视角顺序审**；前值：deep 批次审查 四官隔离并行）
---

> **结构（RFC `20260815-docs-governance` 减负）**：本文件只留「战略 + P0-P2 未修复 + 最近审核摘要 + 执行成本」；已修复详情见 `docs/reference/change-log.md` + git log；P3/观察项已迁移 `docs/reference/task-log.md`。

> 2026-08-24 方案文档深审（`docs/architecture/ai-calling-governance.md` AI 调用治理方案稿 + `_index.md` 登记，docs/backend/frontend/adversarial 四官隔离并行）：守护 G1-G7 7 PASS / 0 HIT + META PASS。**P0×1 + 战略×7 + P1×11 + P2×13（合并去重）**。⭐⭐⭐⭐ **超时矩阵自相矛盾**（前端 90s < 后端最坏 120s，四官全中）→ S-9；⭐⭐ 路由键 scene() 不可行（parse/advice/review/push 全 scene="trading"，应改用 AiTraceContext.source）→ S-10；P0-1 流式→非流式降级重试数据一致性全文未定义（落库/幂等/RecordRetryService 双写）；⭐⭐ SseEmitter async timeout 未配置（Tomcat 默认 ~30s 掐断流式）；⭐⭐ 调用点计数 13 vs 实际 12。方案方向可行（backend 核实 9 项事实属实），修订后实施。报告 `audits/2026-08-24-ai-calling-governance-doc-review.md`。

> 2026-08-23 未归口对账（`ai-engineering/guard-unfixed.sh` 聚合 audits 游离 + 用户视觉批）：**新登记 4 项**——误触搜索 P2-UI6 / launcher 行排序 P2-UI7 / 触达 44pt P2-UI8 / 硬编码色值 P2-UI9（用户视觉批）；小字号 14→27 处并入 P2-UI5；双端 Feed 方向 → S-8 待拍板。**闭环确认 6 条**（D 批已修未归口：切 World 丢输入 D1 / `_loadMore` 去重·切回 page0·时间线最早日期·「刚刚」恒显 D2 / 任务编辑走 PUT D4 / 错误文案人话 D7）。**对账回填 5 处**（P0-交易A / P1-交易18 / P2-UI2 / P2-UI3 / P2-UX3 表状态与已修复区一致化）。

<!-- unfixed-gate
audits/2026-08-30-case-library-review.md → **S1 已修**（save index 写失败回滚案例文件，939 全绿）；P1-案例1 / P2-案例1~5 / P3-案例1（REVIEW 新增）
audits/2026-08-30-trading-rule-layer-review.md → **已全部修复出表**（2026-08-30 审查修复批：P0×2/P1×6/P2×8/P3×6 + 文档×13；降级语义 P1-5 定稿默认值兜底；D-14 current.md 脱敏登记遗留待用户确认；后端 874 全绿，见 change-log）
audits/2026-08-25-lot-tracking-review.md → 已全部闭环（P2-批次1/2/3 均已修出表）；其余全部修复（对抗 P0-1~P1-5 / 后端 P1×2·P2×3·P3 / 前端 P1·P2 / 文档 P1×3·P2×2，见 change-log 2026-08-25）
audits/2026-08-24-ai-calling-governance-doc-review.md → S-9,S-10（REVIEW 新增，方案修订待办；P0-1 及 P1 清单见报告）
audits/2026-08-23-ai-engineering-meta-audit.md → P1-3,P1-5,P1-6,P1-A3,P1-A4,P1-A5,P2-1,P2-2,P2-3,P2-4,P2-A2,P2-A3（REVIEW 新增）；S-A1 残留→#179 依赖；修复批 072dcee 见 change-log。P1 批出表：P1-3/P1-5/P1-6/P2-1/P2-4/P2-A2/P2-A3 ✅；P2 批出表：P1-A3/P1-A5 ✅；G6 守卫批出表：P1-G6-1 ✅（timeline_modal 守卫×2 + 回归×2，app 122）；剩 P1-A4/P2-2/P2-3
audits/2026-08-20-app-health-check.md → P2-UI6,P2-UI7,S-8,闭环(D1切World丢输入/D2排序四实锤/D4任务编辑PUT/D7错误文案)
audits/2026-08-16-ai-engineering-workflow.md → task-log(FL-04/06 审查跟进机制)
-->

> 2026-08-23 元审核（AI 上下文建设工程体系全量，主审核 + adversarial-reviewer 独立子代理隔离复核 + 5 项实证实验）：**P0 无。战略×1 + P1×6 + P2×6（本体系自伤自查）**。核心：S-A2 实证修正（干净 clone M4 必 FAIL——AGENTS.local.md gitignore 快照不入库，非对抗官预测的 M2 lines）；S-A1 门禁绕过三重路径（--no-verify 提示自印 + .claude allowlist + hooksPath 不入库）；P1-A1 隐私闸门类型绕过（.txt/.json 在触发条件外 exit 0，实证 commit 成功）。**修复批 `072dcee`**（M4 白名单 / 新脚本入库 + 登记 / 隐私闸门前移 + gitignore 复核 / cost 追加式 / guard-tools.sh 接入自检）+ **P1 批**（P1-3/P1-5/P1-6/P2-1/P2-4/P2-A2/P2-A3 出表）+ **P2 批**（P1-A3 guard-cost 增量缓存 5.35s→0.044s 121× / P1-A5 G6 逐点化——首战实锤 timeline_modal.dart await 后 setState 无守卫，新登记 P1-G6-1）。剩余 P1-A4（smoke 鉴权依赖 #179）+ P2-2/P2-3（本机旧 IP/cron TCC）。报告 `audits/2026-08-23-ai-engineering-meta-audit.md`。

> 2026-08-23 隔离审查演示（交易归集批**修复后残留**，backend-reviewer + adversarial-reviewer ×2 独立子代理按新规范隔离执行 + 主会话逐条核实）：守护未跑（纯 diff 读审）。**P0×1 + 战略×2 + P1×9 + P2×10（合并去重）**。交叉命中 4 处（⭐⭐ 全属实）：①`update()` 返回 null 全链路未消费——写失败静默账目分裂；②`dedupeKey` 桶 `volume/10*10` ≠ 注释 ±10%（10 vs 19 股同桶差 90%、100 vs 110 不对称）——两官从**相反方向**命中同一函数（过窄吞笔/过宽吞笔兼有）；③closeAdvice 434 行 `md.price()` 无判空（464/481 有）→ NPE；④`change=null` 流入文案。对抗官独有 P0-A：`MarketPushRepository.append` 损坏防护只验语法不验结构（`[123]`/`{"a":1}` 仍空列表+新事件覆盖，B5-5 半修残留）。复发信号 2 条（confirm 双 now()、keepAlive 陈旧）。backend 独有：api-spec §607/§612 契约漂移（guard-align 会拦）、存储层修复零测试（B8/B47）。报告 `audits/2026-08-23-reviewer-isolation-demo.md`。
> 2026-08-20 app 全面体检（用户反馈「排序乱 / World B 切回误触搜索 / 输入框上滑·搜索下滑翻页」，ui/ux/frontend/product ×4 官并行 + 主会话独立核实）：守护 G1-G7 7 PASS / 0 HIT + META PASS。**P0 无。战略×5 + P1×17 + P2/P3×24（合并去重）**。三大体感核实：①排序**部分属实**——实现无 bug 且符合 DESIGN（最新在底），但 4 处实锤（`_loadMore` 无 id 去重 / 切回重置 page0 丢已加载页 / 时间线默认最早日期 / `updatedAt=now`「刚刚」恒显）+ 双端方向相反产品口径待拍板；②误触搜索**属实**——搜索栏是「下滑返回」手势区内的 tap 大目标（竞技场 tap 赢 + 300-400 双阈值死区 + 18px 返回箭头 + AnimatedSwitcher 过渡期可点），结构性必然；③输入框上滑**可行推荐**、搜索下滑**暂不建议**（同区同向已双绑「返回」）——前置需统一「单区域单下滑语义」（现壳层 400/TopBar 200/Launcher 300/RefreshIndicator 四语义叠加）。报告 `audits/2026-08-20-app-health-check.md`。
> 2026-08-19 full 模块审查（app 交易 UI/UX + 推送链路，ui/ux/frontend ×3 官并行 + 主会话交叉印证）：守护未跑（纯 UI 读审）。**P0 无。P1×4 + P2×12 + P3×17（合并去重）**。头号：**推送标题契约断裂（P1-推送1）**——`FeedPushChannel` 落库丢标题 → `toPushEntry` 按 type 重映射（session→「阿呆的交易提醒」）→ 前端按标题 switch 的徽章配色与「确认并入账」按钮判定全部落空：交易日志归集确认闭环 UI 断裂 + 早盘蓝/午间紫/尾盘橙徽章失效（测试 mock 掩盖契约漂移）；**P1-推送2** 左滑删除仅本地刷新复现（web 无删除入口）；**P1-推送3** app 推送设置入口 self-lock（无卡即不可达）；**P1-前端1** app 静默刷新失败整页错误态（web P1-7 同类复发）。新增检查点 V9-8~10 / U30-32 / F58-60 已入清单。
> 2026-08-18 生产日志审查（P0-1 已修复）：**P0-1 `<think>` 壳泄漏**——图片记录 summary 落 `<think>` 思考原文（5+2 条，用户可见 + 交易归集中断）。修复：`GlmResponseParser` 降级路径剥壳 + 未闭合 think/answer 处理 + `max_tokens` 1024→2048；生产 7 条脏记录 glm-4v-flash 重识别清洗 + memory 重建（备份 .bak-20260818-p0 保留）。报告 `audits/2026-08-18-production-log.md`，详情 change-log。
> 2026-08-18 P1-1 已修复（交易归集 unknown 污染）：`TradeLogCollectService.collect` 不再落 `"unknown"` 占位（symbol+name 全无拒绝归集）、complete 判定补 symbol、`dedupeKey` name 兜底防互吞、summarize 显示 name。TradeLogCollectServiceTest +4，后端 674 全绿。
> 2026-08-18 P2-1 已修复（东财被限刷屏）：`KlineService` 熔断——连续失败 3 次熔断 5 分钟直接走腾讯（不再打东财），冷却后半开探测恢复。单日 1154 条 WARN → 熔断后每 5 分钟至多 1 条。KlineServiceTest +3，后端 677 全绿。
> 2026-08-17 deep 增量（范围 `c5aea47..HEAD`，13 commits / 121 文件，交易自理批1-5 A-E 全部优化）：守护 7 PASS / 0 HIT；派 backend/frontend/docs/knowledge ×4。**P0 无。战略×3 + P1×9 + P2×16 + P3×24（合并去重后）**。核心：账户账目无单一真源（现金 3 处独立推导，战略）；C2 买点 5 参数「待用户确认」却已硬编码上线每日推送（战略，K40）；D3 自称「完美图匹配」实为规则近似（战略，K43）；**切入自动刷新是死代码**（`entry.label=='交易'` 判 `=='trading'` 恒 false，P1-前端，功能从未生效）；recordTrade 只动现金不动市值（P1-后端）；closeAccountUpdate 残缺市值覆盖总资产（P1-后端）；CURRENT_MD 硬编码相对路径（3487b00 只修一半，P1×2 官交叉印证）；positionPercent 分母不含现金（R81 bug 复发）；importCashQuery 解析失败静默落零；打分按 symbol `.first` 同代码多笔错挂（P1-前端）；_loadAll 六请求 Future.wait 任一端点失败整页丢数据（P1-前端）；buy-points 响应示例与实现不符（P1-文档）。新增检查点 B49-B54 / F45-F52 / D47-D53 / K40-K43。
> 2026-08-16 修复批（app-polish 审查落地）：**P-be-01 维护端点迁入 /admin/** 鉴权（安全）+ admin 收敛为纯治理（P-role 系列）+ app 补记忆修正/待办完成（P-role-02/P-app-03）+ 带图发图即对话 + 交易建议引擎**。后端 499 · app 112 · admin 34，全部出表。
> 2026-08-15 deep 审核（范围 `7b0a527..HEAD`，33 commits / 181 文件）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**P0 无。战略×2 + P1×5 + P2×7 + P3×21（P3 迁移 task-log）**。**修复批 S + S2 共 12 项已出表**（P1-B1-B4/D1 + P2-B1/B2/R1/R2/R3/D1/D2，后端 440 · app 94 · admin 33）；**战略剩余仅 #179（v1.0.1 登录体系）**；已沉淀检查点 B34-36 / F33-36 / D27-29。
> 2026-08-15 上午 deep 审核（范围：工作树未提交改动——第二步插件系统 T2.1-T2.10 + 第一步遗留，47 文件）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**战略×2 + P1×6 + P2×7 + P3×15**。P0 无。战略 S-3（重补路径 domain 未收敛）+ S-4（行情推送写侧未门控）；P1 六项；已沉淀检查点 B31-33 / F30-32 / D23-26。S-3/S-4/P1 全部出表（批 Q/R）。
> 2026-08-14 deep 审核（范围 `7b0a527..HEAD`，18 commits，带图 ask / 删除残留 / 图片交互批）：**P0×1 + 战略×2 + P1×2 + P2×2 + P3×14**。P0-1 + P1-1 + P1-2 + P2-1 + S-1 已修复出表；S-2 展示层已修（层 2 数据层另立 v1.0.1）。

> 2026-08-30 案例库批次审查（第四阶段环 1-4 + 二期，`c9c2918..fec30bf` 36 文件 +4209 行；**降级：子代理环境故障全线启动失败（7 次尝试），主会话四视角顺序审**）：守护 G1-G7 7 PASS / 0 HIT + META PASS + ALIGN PASS。**战略×1（已修）+ P1×1 + P2×5 + P3×1**。**S1 已修**：`TradingCaseFileRepository.save` index 写失败被 StorageException catch 提前重抛跳过回滚 → 文件残留 + 重试 409 卡死——统一 catch 先回滚再抛 + 回归测试（939）。**P1-案例1**：腾讯 `klineRange` 日期格式未实测（部署前必须验证）；P2-案例1~5（窗口<60根 MA 失真 / web 未适配 buyPoint="case" / 扫描案例库无缓存 / 东财 beg/end+lmt 同传未实测 / index plus5d null→0.0）；P3-案例1（insight source 模型路由登记）。报告 `audits/2026-08-30-case-library-review.md`。
> 2026-08-25 批次审查（RFC 20260825 逐笔批次跟踪与行为纠偏，工作树 34 文件；backend/frontend/docs/adversarial ×4 隔离并行）：守护 G1-G7 7 PASS / 0 HIT + META PASS。**P0×1 + P1×9 + P2×10 + P3×8（合并去重）**。**P0-1 实锤：手动记录（无 orderId）与收盘导入（有 orderId）同笔成交零交集去重 → 重复入账**——主场景「白天手动记 + 收盘导」直接命中；已修（importSync 有 orderId 行也查指纹交叉防重 + fingerprint 价格 stripTrailingZeros 归一化 + 回归测试）。全部 P1 已修：初始批次前置（底仓快照+卖出正确扣减）、sync 透传 fee、混合窗口拆组（不再整批降级 append）、追高 10 日时间窗口、默认 −7% 止损文案分级（不审判未设止损用户）、状态类行为仅当天注入（历史复盘不错位）、行情类推送 15:30→次日 09:30（收盘后仍可看）、web 状态列 closed 优先、reconcile 按 symbol 过滤、api-spec v3.27 行恢复。**3 条 P2 已全部修复出表**（P2-批次1 web 弹窗双击守卫 / P2-批次2 多日导入行为标注逐日合并 / P2-批次3 app 批次行字号）；**用户 2026-08-25 反馈新增 3 条待办**：P2-批次4 多文件批量导入、P2-批次5 导入进度反馈（看不出卡住）、P2-批次6 历史成交完全尊重源文件（股息入账/红利税类型展示）。报告 `audits/2026-08-25-lot-tracking-review.md`。
# 项目审核状态报告

> **2026-08-17 全维度走查（8 官独立并行 + 交叉印证）**：守护 7 PASS / 0 HIT。P0 无。**已修**（批 c98daf7）：buy-points score 量纲 100 倍（⭐前端/契约双官）、promote/Admin 硬编码路径（⭐产品/后端双官）、app _loadAux 早退吞锁、app 清仓打分 .first 错挂、positionPercent 现金分母、closeAdvice 节假日守卫、brief 降级记忆原文、TagIndex RMW 锁、CardMigration now() 回退、app totalPnl 兜底、R85 假引用。**已修**（2026-08-17 前端 8 项批）：web _loadDegradable 锁+代际、web Dialog 日期竞态、web 图片回执系统标签（自然化）、web 交易成功无反馈（补 toast）、首屏失败伪装空态（错误态+重试）、admin 涨跌色相反（darkRed）、web _extractApiError 丢人话、App Feed 卡意图/领域徽章（第一原则）。**未修**（需用户拍板/后续批）：S7 完美图（用户搁置）、P1-9 B1 口径（课程细节搁置）、#179 鉴权、roadmap 缺插件/数据智能条目、RFC draft 已上线、glossary 术语重复 6 处、复盘闭环断链（S9）、行为模式无回写（S10）、P3 迁移登记断裂（S11）。检查点：P23-28/U22-29/B55-61/D54-60/K44-50/V9-1..7 **已入清单**（2026-08-17 前端 8 项修复批）。

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-30 | 交易插件规则层实施批（第三阶段，用户拍板「按建议来」+「继续」）| 工作树（收盘小结批后续）| 主会话 | 0 新问题（实施蓝图）| 规则层 9 步全落地（Step2-9：TradingRuleSettings/Repository/引擎按用户/买点/打分/建议/知识注入/降级/adai 规则包/规则 UI），后端 842 · web 127 · 端点 88，详见 change-log |
| 2026-08-29 | B 类技术债清理批（用户拍板）| 工作树（晚间批后续）| 主会话 + 3 子代理（UI5/UI8/UI9 并行）| 0 新问题（纯修复）| 8 出表（P2-UX4/UX2/UI5/UI6/UI7/UI8/UI9/P2-3）|
| 2026-08-29 | 晚间自主修复批（用户睡觉期间，明早复查）| 工作树（上次收尾遗留 5 文件 + 本批）| 主会话 | 0 新问题（纯修复）| 14 出表（P2-批次4/5/6、P1-前端1、P2-UX1/UI4、P2-交易24/25/27/28/31/33、P2-A3）+ 残留核实 5 项（26/29/30/32/P2-2）|
| 2026-08-27 | 用户反馈核查（楚天龙五连板仍推「买点」，用户质疑选股条件）| 生产服务器 pushes/2026-08-27.json + journalctl 8/24-8/27 + 腾讯 K 线逐日复算 | 主会话 | P1-交易20（买点规则缺 KDJ/追高防护/新鲜度三重校验：8/27 15:10 推送的楚天龙 B2 信号基于 8/26 K 线滞后一日，推送时已五连板；8/21 启动点反而抓不到）+ 佐证（8/26 15:10 扫描 0 命中 vs 23:05 手动 2 命中=当日 K 未更新；8/25 晚腾讯熔断+东财兜底全挂；B2 无 KDJ 条件 vs 课程「KDJ勾明显往上拐头」）| 0（只登记；修复方向「严格卡条件」用户已确认，三处参数待拍板，见 P1-交易20）|
| 2026-08-26 | 用户场景修复（白天发委托截图 → 0 候选）| 工作树（截图表格归集批，3 文件 + 部署）| 主会话 | 截图表格归集缺口：`parseLoose` 单笔一句话解析拆不出表格文字（多笔委托截图）→ 0 候选 | 4（parseLooseBatch 表格批量解析 + collect 批量归集 + 生产验证 4 笔已成归集/跨图去重 + 测试 +8；REVIEW 本条出表）|
| 2026-08-26 | 用户反馈登记（使用频率下降访谈：App 打不开 / 交互慢 / 交易没感觉）| —（纯讨论无代码改动）| 主会话 | P2-用户1/2/3（iOS 签名 7 天过期「打不开」设计缺陷、AI 交互慢、交易价值不可感知）| 0（只登记，方案待用户拍板后开工）|
| 2026-08-24 | deep 方案文档审查（AI 调用治理方案稿）| 工作树（ai-calling-governance.md 新增 + _index 登记，2 文件）| docs/backend/frontend/adversarial ×4 隔离并行 | P0×1 + 战略×7 + P1×11 + P2×13（⭐⭐⭐⭐ 超时矩阵四官全中）| 0（审查只报告，报告见 `audits/2026-08-24-ai-calling-governance-doc-review.md`）|
| 2026-08-23 | 隔离审查演示（新规范首次实战：上下文隔离 + 对抗官）| 工作树（交易归集修复批 1-5 后，32 文件）| backend + adversarial ×2 独立子代理 + 主会话核实 | P0×1 + 战略×2 + P1×9 + P2×10（合并去重，⭐交叉 4 处全属实）| 0（审查只报告，报告见 `audits/2026-08-23-reviewer-isolation-demo.md`）|
| 2026-08-23 | full 模块审查（web 交易前后端 + 契约）+ 修复批 | adai-web trading 全量 + adai-core trading 全量 + api-spec §5 | backend/frontend/contract ×3 + 主会话交叉印证 → **用户确认后修复 21 项** | P0×2 + P1×9 + P2×12 | 21（批次 1-5：P0-1/2、P1-1/2/3、B3-1~5、B4-1~5、B5-1~6，见已修复区）|
| 2026-08-20 | full app 体检（用户体感导向）| apps/adai-app 全量 | ui/ux/frontend/product ×4 + 主会话独立核实 | 战略×5 + P1×17 + P2/P3×24（合并去重）| 0（审查只报告，报告见 `audits/2026-08-20-app-health-check.md`）|
| 2026-08-19 | full 模块审查（app 交易 UI/UX + 推送链路）| 工作树（交易模块 app/web 全部 UI 文件）| ui/ux/frontend ×3 + 主会话交叉印证 | P1×4 + P2×12 + P3×17 | 0（审查只报告）|
| 2026-08-18 | 生产日志审查（journalctl 当日 2986 行）| 49.235.37.220 运行日志 | 主会话直接核读 | P0×1 + P1×2 + P2×4 + P3×4 | 0（审查只报告，报告见 `audits/2026-08-18-production-log.md`）|
| 2026-08-17 | deep 增量（交易 A-E 批1-5）| c5aea47..HEAD（13 commits）| backend/frontend/docs/knowledge ×4 | 战略×3 + P1×9 + P2×16 + P3×24 | 0（审核不直接修）|
| 2026-08-16 | deep 增量（框架+插件 G-1~G-6）| 7734d99..HEAD（2 commits）| backend/knowledge/docs ×3 | 战略×4 + P1×4 + P2×9 | 0（审核不直接修）|
| 2026-08-15 | deep 增量（批 Q/R + 展示层聚合 + 发布核对）| 7b0a527..HEAD | backend/frontend/docs ×3 | 战略×2 + P1×5 + P2×7 + P3×21 | 0（审核不直接修）|
| 2026-08-15 | deep 增量（Domain=插件模型 + step-1）| 7b0a527 + 工作树 | backend/frontend/docs ×3 | 战略×2 + P1×6 + P2×7 + P3×15 | 0（审核不直接修）|
| 2026-08-14 | deep 增量（带图 ask + 删除残留 + 图片交互批）| 7b0a527..HEAD | 主会话 + docs/frontend agent×2 | P0×1 + 战略×2 + P1×2 + P2×2 + P3×14 | 5（P0-1 + P1-1 + P1-2 + P2-1 + S-1）|

> 更早审核（08-01 ~ 08-12）见「执行成本」表 + git 历史。

## 🔴 战略缺口（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 179 | 用户层 X-User-Id 零鉴权（任何人传任意 userId 即可读对应数据）；数据访问靠 header 注入无认证。真正收紧需登录体系 | `AccountController` / `WebConfig` | 📋 v1.0.1 立项 |
| S5 | 账户账目无单一真源：总资产/现金/本金被四处独立推导（snapshot.cash、positions.md cashBalance、转账推导、recordTrade 现金），现金有 3 个真源只更新其一 → R81 分母过期 | `TradingAppService` / `AccountSnapshot` | ✅ 已修（2026-08-17 S5 批：现金唯一真源=account.json AccountSnapshot.cash；importCashQuery 不再写 positions.md cashBalance；advice/portfolio/review 全走 AccountSnapshot；642 测试全绿）|
| S6 | C2 买点 5 参数（回调50%/缩量0.7/KDJ20/放量1.5/前高20日）标注「待用户确认」却已硬编码上线每日 15:10 推送 + web 信号列 + D3 打分——实现替用户做了决定，无门禁 | `BuyPointDetector` / `buy-point-rules.md` | ✅ 已确认（2026-08-17 用户：无「买点5」一说，默认值即最终值）|
| S7 | D3 自称「完美图匹配度」，实际是规则阈值 + 硬编码分数映射（无完美图样本库/归一化相似度）；「三维打分」总分实为二维（选股维度未接入） | `SoldScoreService` | ⏸ 已搁置（2026-08-17 用户决策）|
| S-8 | **双端 Feed 阅读方向相反**（app 聊天式最新在底 vs web 流式最新在顶）——产品口径待拍板并记入 frontend-reference（2026-08-23 归口 audits/2026-08-20，⭐⭐ 两官命中）| `main_page.dart:1092-1096` vs `feed_page.dart:130,837` | ✅ 已拍板（2026-08-26 用户：**最新在底部**）+ web 已改（feed_page reverse:true + 更早页插头部，待打包），app 本就一致；frontend-reference 口径待同步 |
| S-9 | **AI 调用治理方案超时矩阵自相矛盾（⭐⭐⭐⭐ 四官全中）**：症状 B 自证「前端 90s vs 后端最坏 120s（60s×2）」，治理表同步档却仍写「90s+」——旧同步端点（在网旧客户端唯一路径）照旧超时，方案核心卖点未兑现。修复：前端 ≥120s 或后端去重试/降单次（45s×2=90s 自洽），二选一写死推导 | `docs/architecture/ai-calling-governance.md` §四③ | ✅ 已修（2026-08-26：后端单次 60s→45s（45×2+0.6=90.6s）、前端 AI 90s→120s（app+web 双端代码已改，web/app 待打包部署）；配套重发去重幂等防超时重发重复卡片）|
| S-10 | **方案路由键 scene() 不可行**：TradingParse/Advice/Review/SessionPush 全 `scene="trading"`（代码实锤），快/深模型无法靠 scene 区分，「不改 16 个调用方」不成立；应改用已存在的 `AiTraceContext.source`（trading_parse/trading_advice/trading_review/trading_session_*）| `docs/architecture/ai-calling-governance.md` §四① | ✅ 已修（2026-08-26：DeepSeekAiClient 双模型按 AiTraceContext.source 路由——trading_review 走 v4-pro，其余走 v4-flash；新增 `adai.ai.model-flash` 配置 + AiTraceContext.source() 读取；生产实测复盘=pro/问答=flash）|
> **FP-S1/S2/S3/S4 已出表**（2026-08-16 框架+插件审查修复批，见已修复区）：总纲 §五 现状表刷新全 ✅（S1）；引擎口径契约测试 `RuleKnowledgeContractTest`（S2，B44）；R81 分母规格同步总资产（S3）；update-current.sh 声明修正为注记刷新器（S4）。
> S-R1（app 插件失败 SnackBar+重试，双端对拍）与 S-R2（服务端合并插件端点，竞态根治）已出表（2026-08-15，见已修复区）。S-2（展示层聚合）已出表；数据层整体化 RFC `20260815-media-event-unification` approved 排 v1.0.1；S-3/S-4 已出表（批 Q）。

## 🔴 P1（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P1-案例1 | **腾讯 klineRange 日期格式未实测**：URL `param=%s,day,%s,%s,320,qfq` 传 `LocalDate.toString()`（yyyy-MM-dd），腾讯 fqkline 接口日期参数格式未真网络验证——格式不符 → 主源空转走东财兜底（功能可用但主源失效）| `TencentMarketDataSource.klineRange` | 部署前 bootRun 实测一次真接口；不被接受则改 BASIC_ISO_DATE |
| P1-交易1 | **切入自动刷新是死代码**：`_NavEntry('交易',...,'trading',...)` label=中文'交易'，`_buildPage` 判 `entry.label=='trading'` 恒 false → 切到交易页从不触发刷新（253a35e/37d4b52 核心卖点从未工作）| `desktop_shell.dart:115` / `trading_page.dart:59-65` | 改判 `entry.plugin=='trading'`，补壳层 widget 测试 | ✅ 已修（2026-08-17 R4：改判 entry.plugin=='trading' + 壳层测试）
| P1-交易2 | recordTrade 只动现金不动市值：BUY 少计成交额、SELL 多计成交额 → 账户卡 15:05 前账目错误，快照现金滞后时 cash 可被推成负值 | `TradingAppService.java:137-148` | 买卖同步更新 marketValue | ✅ 已修（2026-08-17 R4：现金↔市值转移，总资产只差手续费 + 2 测试）
| P1-交易3 | closeAccountUpdate 部分行情缺失即用残缺市值覆盖总资产（旧值不可恢复）| `TradingSessionPushService.java:155,168-171` | 行情不全时跳过或保留旧市值 | ✅ 已修（2026-08-17 R4 缺行情跳过 + **2026-08-23 补 yesterdayClose 残缺同样跳过**，见已修复区）|
| P1-交易4 | positionPercent 分母只算持仓不含现金（注释称含现金）→ 单仓+大现金每日误发「超 R81 减仓」（FP-P2 已修 bug 复发）| `TradingSessionPushService.java:327-341` | ✅ 已修（2026-08-17：分母 = 持仓市值 + AccountSnapshot.cash（S5 真源），SessionData 注入现金 + 回归测试；643 测试全绿，见已修复区）|
| P1-交易5 | importCashQuery 解析失败（CASH_HEAD 未命中）静默落零覆盖 account.json + cashBalance 置零 | `TradingAppService.java:519-553` | ✅ 已修（2026-08-17 R3：headerMatched + 抛错 + web toast，见已修复区）|
| P1-交易6 | CURRENT_MD 硬编码 `../../os/...` 相对路径（3487b00 只修了 TradingAdviceAppService，漏了第二个知识消费者）→ 生产择时状态恒「未知」| `TradingSessionPushService.java:60` | ✅ 已修（2026-08-17 R1：配置注入 + 不可读 warn 日志 + 2 测试；见已修复区）|
| P1-交易7 | `_loadAll` 六请求合并 `Future.wait`：任一端点失败（如 buy-points K线抖动）→ 整页替换为错误页丢弃已展示数据（含静默刷新路径）| `trading_page.dart:74-81,98-104` | 致命/可降级请求分离（F41）| ✅ 已修（2026-08-17 R4：致命/可降级分离 + 测试）
| P1-交易8 | 清仓三维打分按 symbol `.where(...).first`：同代码多笔交易分数错挂（两行显示第一笔分数）| `trading_page.dart:774-775` | 按列表顺序索引匹配或 (symbol,buyDate) 复合键（F42）| ✅ 已修（2026-08-17 R4：按序索引匹配）
| P1-交易9 | B1 判定「回调一半」几何语义漂移：课程=回撤到涨幅一半位置 (high+low)/2，代码=距前高回撤 50% close≤high/2（更严）；且支撑/白线条件未实现 | `BuyPointDetector.java:63-64` vs glossary:899 | ⏸ 已搁置（2026-08-17 用户：课程细节先搁置）|
| P1-交易10 | api-spec buy-points 响应示例与实现不符：`score:0.8` 量纲错（实际 0-100 约 87）、signals 文案与代码实际输出不同 | `api-spec.md:513-514` | 示例=真实输出（D49）| ✅ 已修（2026-08-17 R4：示例=真实输出）
| P1-推送1 | ✅ 已修（2026-08-23 B9：MarketPushEvent 透传 title + toPushEntry 用原标题 + 双端确认按钮/徽章回归；见已修复区）。**推送标题契约断裂（⭐ 2026-08-19 三官交叉印证 + 主会话独立验证）**：`FeedPushChannel` 落库丢弃原标题（MarketPushEvent 无 title 字段）→ `FeedAppService.toPushEntry` 按 type 重映射（session→「阿呆的交易提醒」/gain→「放飞提示」/break-cost→「行情提醒」）→ 前端按标题字符串 switch 的徽章配色与 `e.title=='今日操作确认'` 判定全部落空：①「确认并入账」按钮两端永不渲染（交易日志归集 15:15 确认闭环 UI 断裂，候选滞留服务端）②早盘蓝/午间紫/尾盘橙徽章失效，session 全落灰「行情」③「今日操作确认」徽章错标「尾盘建议」永不达。测试 mock 了不存在的标题（feed_state_machine_test 掩盖契约漂移）| `FeedAppService.java:381-391` / `FeedPushChannel.java:41-47` / `feed_card.dart:388-395,421` / `main_page.dart:126,154,977` / `feed_page.dart:79,123` | 持久化透传原标题（MarketPushEvent 加 title）+ 删 toPushEntry 重映射；或前端改按 type 判定 + 两端共享标题常量表；tradeLogConfirm 用独立 type |
| P1-推送2 | ✅ 已修（2026-08-23 B10：DELETE /trading/pushes/{id} + app 左滑删持久化 + web 忽略按钮；见已修复区）。**推送删除无持久化**：app 左滑删除仅本地 `removeWhere`（`_dismissPush`），后端无 dismiss 端点 → 30 分钟自动刷新/下拉后同卡复活；web push 卡无任何删除/忽略入口（只能关整类）；「今日操作确认」卡的唯一"忽略"路径同失效 | `main_page.dart:860-863` / `feed_card.dart:448-461` / `desktop_feed_card.dart:191-249` / `MarketPushRepository`（无删除方法）| 后端补单条已读/删除端点或前端本地持久化 dismissed id；web 补删除按钮，两端对齐 |
| P1-推送3 | ✅ 已修（2026-08-23 B11-1：app 交易页常驻推送设置铃铛，空仓/全关仍可达；见已修复区）。**app 推送设置入口 self-lock**：设置入口仅右滑 push 卡（`onPushSettings` 仅挂 push 类型）→ 空仓日无推送卡 / 8 类全关后 push 卡不再出现 → 设置永久不可达（web 交易页有铃铛入口，app 无）| `main_page.dart:130,158,981` / `trading_page.dart:403-429` | app 交易页补推送设置铃铛（对齐 web）或 Feed 空态加入口 |
| P1-前端1 | ✅ 已修（2026-08-29 晚间批：_loadData 已有数据时刷新失败保留旧数据不整页错误态，仅首载失败显示错误页——web P1-7 同类在 app 复发根治，+1 回归测试）。** **app 静默刷新失败整页错误态（web P1-7 同类在 app 复发）**：`_loadData` catch 直接 `setState(_error)`，body 分支 `_error != null` 优先于列表 → 30 分钟自动刷新/交易后 `_refresh()` 网络抖动即整页「请求失败」，已展示持仓/账户数据全部丢弃 | `trading_page.dart:100-116,427-429` | 区分首屏/刷新：已有数据时刷新失败保留旧数据 + 非阻塞提示（陈旧角标）；全页错误态仅限首载 |
| P1-交易11 | **update() 返回 null 全链路未消费（⭐⭐ 2026-08-23 两官交叉，S5 直接相关）**：recordTrade/recordTransfer/setPrincipal/closeAccountUpdate/confirm 忽略写失败——account.json 未落盘仍按成功继续（setPrincipal 还 200 空响应）| `TradingAppService:156,677` / `TradingSessionPushService:257` | 写失败抛 TradingException 或返回结果对象，调用方提示/补偿 | ✅ 已修（2026-08-23 B6-4：写失败抛 StorageException + GlobalExceptionHandler 500 人话 + recordTrade/closeAccountUpdate catch 告警；见已修复区）|
| P1-交易12 | **dedupeKey 桶语义 ≠ ±10%（⭐⭐ 两官从相反方向命中同一函数）**：`volume/10*10` 固定 10 股向下取整——10 vs 19 股同桶（差 90% 吞真实两笔）、100 vs 110 不对称（差 10% 反分开）；同笔 OCR 波动跨桶不去重 → confirm 双落库（BUY 现金双扣静默/SELL 报超持仓）| `TradeLogCandidate.dedupeKey` | 按 ±10% 相对比例归一化 + 边界测试（当前零测试）| ✅ 已修（2026-08-23 B6-2：`sameTrade` ±10% 区间判定替代字符串桶 + 5 边界测试；见已修复区）|
| P1-交易13 | **closeAdvice 判空缺失（⭐⭐）**：quote 缺失回退 `p.currentPrice()` 亦 null → NPE；同文件 434 行 `md.price()` 无判空 vs 464/481 行有（判空不一致）；单用户异常可能中断 forEachTradingUser 整批 | `TradingSessionPushService.java:434` | price 判空跳过该持仓 + 单用户隔离（B59）| ✅ 已修（2026-08-23 B6-3：buildCloseTemplate/buildMiddayTemplate/buildDataText 三处 changePercent 判空，缺行情显 '-'；见已修复区）|
| P1-交易14 | **confirm 双 `LocalDate.now()` 跨午夜（复发信号：now() 推导路径）**：候选昨日残留 + 今日副本 | `TradeLogCollectService.confirm` | 单次取 now 贯穿 | ✅ 已修（2026-08-23 B6-5：confirm 单次取 now 贯穿收集/落库/保存；见已修复区）|
| P1-交易15 | change=null 流入 addIfNew（⭐⭐ 待验证）：price 有、changePercent 缺失时 R66 类预警触发 → 「null%」文案/签名碰撞 | `MarketAlertService` B5-2 | 拼接前判空 | ⚠️ 复核（2026-08-23 B7-1）：**不成立**——`fmt(null)` 返回 '-' 非 'null%'，且 loss/gain 有 change!=null 守卫；R66 类 message 不用 change。维持登记防回归 |
| P1-交易16 | 前端 R66 文案 10%→5%，后端判定阈值本批未同步——文案与行为不符 | `apps/adai-web/trading_page.dart` | 双端对拍阈值 | ⚠️ 复核（2026-08-23 B7-4）：**误报**——后端 `SoldTradeVerdict` 阈值早已 -5%（P2-交易5），前端「扛单超5%」与后端 verdict「扛单超 5%」一致。移除 |
| P1-交易17 | 历史成交 Tab keepAlive 切 Tab 不再刷新（复发信号：保活页陈旧，U31）——收盘/他端变更后陈旧 | `trading_page.dart` | 保活页加可见性刷新 | ✅ 已修（2026-08-23 B6-5：`_TabHistoryRefreshListener` 切回历史成交 Tab 静默刷新；见已修复区）|
| P1-交易18 | 保留候选钉子户：前端无失败明细、无丢弃入口——15:05 推送反复提醒（关联 P1-交易12）| `trading_page.dart` | 失败明细 + 丢弃入口 | ✅ 已修（2026-08-23 B11-4：失败候选保留+明细透出 + 双端丢弃入口接线 `DELETE /trade-log`，见已修复区）|
| P1-交易19 | 存储层关键修复无同批测试（B8/B47）：update 锁/写失败 null/损坏拒写回/save 锁/dedupeKey 桶全在 mock 层，真实仓储并发/失败路径零覆盖 | `AccountSnapshotFileRepository` / `MarketPushRepository` / `TradeLogRepository` / `TradeLogCandidate` | 补仓储级并发 RMW + 写失败用例 | ✅ 已修（2026-08-23 B7-2：MarketPush 结构损坏×3、TradeLogRepositoryTest 并发 append/dedupe/discard×5、AccountSnapshot 并发无丢失；见已修复区）|
| P1-3 | **audit.md 官表未纳入对抗官（08-23 元审核归口）**：全维度走查（里程碑级最贵流程）8 官无对抗官，而 deep review 默认 +1——最高风险场景缺最狠视角；full 定义两套口径（review.md 8+1 vs audit.md 8）打架 | `ai-engineering/process/audit.md:40-49` | audit 官表 +1 对抗官，full 口径统一 8+1 | ✅ 已修（2026-08-23 P1 批：audit 官表 +1 对抗官行，标题/成本描述/README/AGENTS 计数统一 8+1）|
| P1-5 | **init-ai-engineering.sh 声称存在实为设计稿（08-23 元审核归口）**：method/README.md:76「跑 init-ai-engineering.sh」脚本不存在；M4 正则只扫 `docs/ai-engineering/AGENTS` 前缀，裸名结构性不可检测 | `method/README.md:76` / `method/scaffold.md` | 标「待建」或补脚手架脚本 | ✅ 已修（2026-08-23 P1 批：method/README 标注「⏳ 待建，现为设计稿」）|
| P1-6 | **README 引用不存在的仓库外目录（08-23 元审核归口）**：README.md:60 声称同级 `ai-engineering-method/` 存在（实无）；M4 白名单显式豁免该路径——守卫固化错误假设 | `ai-engineering/README.md:60` | 删行或改指仓库内 `method/` | ✅ 已修（2026-08-23 P1 批：改指仓库内 method/ + 修正注记）|
| P1-A3 | **guard-cost 全量解压所有会话（08-23 对抗官独立发现）**：`glob(SESS_DIR,'*','*',...)` 全扫 + 每 zstd timeout 60s，会话膨胀后 guard-context 25s 调用超时 → 成本提醒静默降级「调用失败」| `guard-cost.sh:110-139` / `guard-context.sh:191` | 增量/按天索引/缓存，免全量解压 | ✅ 已修（2026-08-23 P2 批：按会话文件 mtime 增量缓存 cost-cache.json——未变文件跳过解压，聚合从缓存桶计算；实测 113 文件 5.35s → 0.044s（121×），结果一致）|
| P1-A4 | **deploy-gate smoke 用零鉴权漏洞验证部署（08-23 对抗官独立发现）**：`-H "X-User-Id: adai"` 打六端点 = 战略 #179 漏洞利用方式——「最硬闸门」把零鉴权常态化 | `deploy-gate.sh` | 依赖 #179 登录体系后改真鉴权；`sleep 10` 固定等待对慢启动 JVM 是竞态 | ⚠️ 保留（依赖战略 #179 登录体系）|
| P1-A5 | **G1-G7 是仓库级模糊启发式非逐点检测（08-23 对抗官独立发现）**：G6 只统计全仓 mounted 守卫总数、G3 只查 catch 块内 delete——「防 P0 复发」头衔与拦截能力不匹配，weekly-audit「守护 7 PASS」是低信号结论 | `docs/review/guard.sh` | 逐点化（每调用点断言）；命中与自坏区分 | ✅ 已修（2026-08-23 P2 批：G6 改为逐点断言——解析 async 方法内 await 后 setState，无 mounted 守卫即 HIT 定位文件:行号；同步方法/await 前同步段豁免（实测 18 误报→0）；**首战实锤真实缺陷** timeline_modal.dart:57/66 await 后 setState 无守卫（旧版总数统计永远 PASS）→ 新登记 P1-G6-1）|
| P1-G6-1 | **timeline_modal.dart 全文件无 mounted 守卫（2026-08-23 G6 逐点化实锤）**：`_loadTimeline` await getTimeline() 后 setState（57 行成功路径 + 66 行 catch 路径）均无守卫——Modal 关闭后响应到达即 setState 崩溃（复发信号：await 后空值/守卫只包异步）| `apps/adai-app/lib/widgets/timeline_modal.dart:40-69` | 两处 setState 前补 `if (!mounted) return;` | ✅ 已修（2026-08-23 G6 守卫批：成功路径 + catch 路径各补 1 处守卫；回归测试×2——modal 关闭后响应到达不崩溃（反向验证：移除守卫测试红 `setState after dispose`）/正常路径渲染；app 120→122 全绿）|
| P1-交易20 | **买点规则缺 KDJ/追高防护/信号新鲜度三重校验，连板股高位误报（2026-08-27 生产实锤，用户质疑选股条件）**：楚天龙(003040) 8/21 首板起五连板（8/27 收 18.73，自 8/19 低点 10.84 +73%），15:10 仍推送「🚀 放量突破，B2 右侧」——推送「量能 2.0x」精确复现 8/26 量比 2.04，**信号基于 8/26 K 线滞后整整一个交易日**；同日北方铜业(000737) 同类（量能 1.6x=8/26 量比 1.56）。根因三层：①**B2 无 KDJ 条件**——课程原文「B2：KDJ勾明显往上拐头」（glossary B1/B2/B3 三件套），代码只查「量比>1.5 + 收盘>20日前高」，8/26 判定时 KDJ.J=120.6 深度超买仍命中；②**无追高防护**——前高=滚动 20 日窗口，连板日天天创新高天天「突破」，无涨幅/连板数/乖离上限；③**无信号新鲜度校验**——15:10 腾讯当日 K 未更新时拿昨日数据当今日信号（实证：8/26 15:10 扫描 0 命中 vs 同日 23:05 手动扫描 2 命中；8/27 15:10 若含当天则 8/27 量比 1.43<1.5 不会命中）。反向缺陷：**8/21 首板启动点（J=61.6 低位拉起、量比 1.88）反而因未破 8/7 高点 13.53 不命中——启动点抓不到、高位乱推**。关联 P1-交易9（B1 回调口径漂移，同文件）| `BuyPointDetector.java:69-73` / `TradingSessionPushService.java:289-313` / `WatchlistBuyPointService.java:57` | 严格卡条件（2026-08-27 用户确认方向，三处待拍板：①B1 回调口径（50% vs 课程 (high+low)/2）②B2 放量 1.5→2.0 对齐课程倍量柱 ③B2 是否加 B1/J 前置）：B2 加 KDJ.J 拐头向上 + J<90 超买排除；信号新鲜度（最新 K 线日期≠今日不推/补扫）；距 20 日低点涨幅>30% 或 ≥2 连板不推 | ⏸ 已登记待做（2026-08-27 用户：先记录，不着急做）|
> **FP-P1~P4 已出表**（2026-08-16 框架+插件审查修复批，见已修复区）：yml 路径 11-context→knowledge/context（P1）；R81 分母改总资产（现金纳入，P2）；update-current.sh 幂等+时间戳语义（P3）；R66 现价口径注明（P4）。**注意：P1 表仍有 P1-交易4/P1-交易9 未修（2026-08-17 走查确认，见下表）**。
> **P1 当前清零**（2026-08-15 修复批 S + S2 全部出表：P1-B1/B2/B3/B4 + P1-D1，见已修复区）。2026-08-16 框架+插件审查新增 FP-P1~P4（未修）。

## 🔴 P2（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P2-案例1 | 窗口不足 60 根（停牌/新股/标注日近窗口起点）→ `ma()` 用可用根数近似 → MA60/黄白线态/距 60 日线失真（已知取舍，特征可算但不精确）| `CaseFeatureExtractor.ma` | 注明已知限制（设计文档 §4.1）；可加 `windowComplete` 标记 |
| P2-案例2 | **web 自选 Tab 未适配 `buyPoint="case"`**：二期开关开时规则未命中但案例相似 → 返回 `case` 项，前端信号列渲染「case 0%」异常 | `WatchlistBuyPointService` / web 自选 Tab | 前端适配 case 类型显示（「形态接近历史完美买点」）；开关默认关，随部署批适配 |
| P2-案例3 | 二期开关开时 `scanWatchlist` 每跑全量 `caseRepository.list`（index+逐文件读），案例多时拖慢扫描（无缓存）| `WatchlistBuyPointService` | 案例 >50 后加 TTL 缓存（对齐 TradingKnowledgeSource 模式）|
| P2-案例4 | 东财 `klineRange` 同时传 `lmt=320` + `beg/end`——同传截断行为未实测（可能忽略 lmt 或 beg 前截断）| `EastMoneyKlineDataSource.klineRange` | 与 P1-案例1 一并实测 |
| P2-案例5 | `indexEntry` 的 `plus5dReturnPct`：`verify()==null` 时存 0.0 → 列表前端显示「+5d 0.0%」而非「—」| `TradingCaseFileRepository.indexEntry` | 改存 null 或列表端 0 兜底 |
| P2-交易1 | SoldScoreService 16 线程池无 @PreDestroy shutdown；单笔 30s 超时产空 symbol 占位行 | `SoldScoreService.java:35,52-57` | 线程池 shutdown + 无空行占位（B53）| ✅ 已修（2026-08-17 R5：shutdown + 超时保留 symbol）
| P2-交易2 | scanWatchlist 串行拉 K 线（仅打分并行化，买点扫描未并发）且无按标的异常隔离 | `WatchlistBuyPointService.java` | 同 SoldScoreService 并发化（B54）| ✅ 已修（2026-08-17 R5：8 并发 + 异常隔离）
| P2-交易3 | 腾讯 K 线兜底无缓存（东财被限时每请求都打腾讯）| `TencentMarketDataSource` | 加按日缓存 | ✅ 已修（2026-08-17 R5：按日缓存）
| P2-交易4 | 现金双源不同步（snapshot.cash vs positions.md cashBalance）| `TradingAppService` | ✅ 已修（2026-08-17 S5 批：现金单一真源=account.json，见已修复区）|
| P2-交易5 | SoldTradeVerdict 自造阈值 -10% 挂 R66 名下（课程止损幅度 3-5%，R67/R72）→ 亏 8% 扛单被判「非违反」| `SoldTradeVerdict.java:30-32` | 阈值改 -5% 或标注近似待确认（K42）| ✅ 已修（2026-08-17 R5：-5% 用户确认）
| P2-交易6 | KDJ「大负值」阈值漂移：课程锚点 J<13，代码默认 J<20 偏松 | `KdjIndicator.java:17` / `BuyPointDetector.java:76` | 建议值改 13 或注明待确认 | ✅ 已修（2026-08-17 R5：J<13 用户确认）
| P2-交易7 | B1? 候选信号与正式 B1 同通道推送（「不硬推」声明违背）| `TradingSessionPushService.java:187` | 仅 B1/B2 推送，B1? 灰显候选 | ✅ 已修（2026-08-17 R5：B1? 不推送）
| P2-交易8 | `_loadAll` 入口 setState 无 mounted 守卫 + 多处 await 后直接 _loadAll | `trading_page.dart:69,838,946...` | 入口守卫 + await 前置守卫（F43）| ✅ 已修（2026-08-17 R6：mounted 守卫）
| P2-交易9 | buy-points 留在 _loadAll 致命路径（K线重计算阻塞首屏），与打分异步化自相矛盾 | `trading_page.dart:80` | 移出 Future.wait 异步化（F41）| ✅ 已修（2026-08-17 R4+R6：buy-points 已移出致命路径）
| P2-交易10 | _loadSoldScore 无去重/无空列表短路：每次 _loadAll 都触发全量 K 线打分可重叠 | `trading_page.dart:96-97,107-116` | _sold 空短路 + 代际令牌 | ✅ 已修（2026-08-17 R6：空列表短路 + 在途去重）
| P2-交易11 | 「纪律遵守率」实为胜率（profit/total 且 >=0 计盈），与纪律无关 | `trading_page.dart:707-711` | 改 verdict 口径或改名胜率 | ✅ 已修（2026-08-17 R6 verdict 口径 + **2026-08-23 B3-5 补：久持小亏 verdict 标 R53 延展，遵守率不再虚高**）|
| P2-交易12 | D2 行为模式单字 contains 误配（「不贪」「着急」）+ 重叠计数与「已标 N 笔」口径不一致 | `trading_page.dart:670-677` | 词组/否定排除 + 区分标注数/模式命中数（F45）| ✅ 已修（2026-08-17 R6：双字词组 + 否定排除）
| P2-交易13 | 快捷导入/删自选/心理标注无错误处理（失败静默+未处理异步异常）| `trading_page.dart:606-609,742-745...` | 统一 try/catch → toast（F46）| ✅ 已修（2026-08-17 R6：确认框 + 失败反馈）
| P2-交易14 | 账户总览 8 卡同行大数值溢出（22px 粗体 RenderFlex）| `trading_page.dart:318-341` | FittedBox/万单位 | ✅ 已修（2026-08-17 R6：FittedBox + 千分位）
| P2-交易15 | 打分列颜色与红涨绿亏冲突（绿色=高分 vs 全局绿色=亏损；'—' 渲染橙色）| `trading_page.dart:787-798` | 中性色阶 + 空值固定灰（F44）| ✅ 已修（2026-08-17 R6：中性色阶 + '—' 固定灰）
| P2-交易16 | 买点参数「可配」无配置接线（三处硬编码 0.5/0.7/20/1.5/20）+ RFC/feature-reference 状态漂移（待做列全是已实现项）| `BuyPointDetector` 调用点 ×3 / `data-intelligence.md` | yml 配置化（K40）+ RFC 滚动（D47）| ✅ 已修（2026-08-17 R5+R6：参数接线待用户确认，RFC 滚动见 R6）
| P2-交易17 | buy-point-rules.md 状态声明矛盾（「待用户确认后实现」vs 已实现）+ 参数 5 语义错位（写「前20日最低点/白线均线」，代码是**前高**窗口）| `buy-point-rules.md:5,56,64` | 改「已按建议值实现，待用户校准后冻结」；参数 5 如实描述（D51/K42）| ✅ 已修（2026-08-17 R6 曾虚标——文件未动；**2026-08-23 B4-1 真修**：按代码事实重写参数表 KDJ13/前高窗口，见已修复区）|
| P2-交易18 | api-spec 变更记录缺 v3.22（15 个交易端点 2026-08-16 全部落地无版本行）| `api-spec.md:5,32-33` | 补 v3.22 行 + 升头部版本号（D48）| ✅ 已修（2026-08-17 R6 半修——头部仅升 v3.22；**2026-08-23 B4-2 真修**：头部 v3.24，补 08-18/08-22 批次版本行，见已修复区）|
| P2-交易19 | api-spec account 节「每日定时任务收市后更新为后续」过时——批1 已实现收盘 15:05 自动更新 | `api-spec.md:574` | 改「收盘 15:05 自动更新行情字段；现金/本金保持券商导入+转账推导」（D53）| ✅ 已修（2026-08-17 R6：account 节修订）
| P2-交易20 | guard-align A1 盲区：正则只匹配括号内带路径的映射，11 个裸 @GetMapping（类级路径继承）不计入 → A1 报 60 vs 真相源 71 | `guard-align.sh:33-37` | ✅ 已修（2026-08-17：补裸注解分支 `@GetMapping`/`@GetMapping()` 继承类级 base；A1 61→72 全对齐 endpoints.txt 真相源，见已修复区）|
| P2-交易21 | TradingAdviceAppService 输出侧硬判定未过 r81Applicable：OVER_WEIGHT && buy → reduce 覆盖未检查总资产超 100 万前提，与 FP-P2b 语义矛盾（prompt 段尊重前提、输出段没有）| `TradingAdviceAppService.java:194-198` | 输出侧复用 r81Applicable 判定 | ✅ 已修（2026-08-17 R5 建议引擎侧 + **2026-08-23 B3-2 补尾盘推送模板侧 r81Applicable，双输出侧口径一致**）|
| P2-交易22 | importPositions 缺 avgCost/quantity 校验：body 无 avgCost → Position.avgCost null → PortfolioSnapshot.of / closeAccountUpdate / 建议引擎 NPE 500 | `TradingController.java:159` / `TradingAppService.java:340` | controller 校验或 domain 兜底 | ✅ 已修（2026-08-17 R5：avgCost/quantity 校验）
| P2-交易23 | **持仓编辑端点从未实现**：前端/测试一直在调 PUT /positions/{symbol}，后端只有 GET/POST——web 点「编辑」保存必 404（功能形同虚设）| `TradingController`（2026-08-17 已补端点 ✅）| ✅ 已修（2026-08-17 R1 续：updatePositionMeta + PUT 端点，见已修复区）|
| P2-批次1 | ✅ 已修（2026-08-25 P2 批次修复批：`_showLots` 加 `_lotsDialogOpen` 在途守卫，弹窗关闭后复位）。**web 批次弹窗连点/双击无幂等守卫（RFC 20260825 审查）**：`_showLots` 慢响应逐个 showDialog 叠两层（F20/F22 同类）| `apps/adai-web/lib/pages/trading_page.dart:_showLots` | loading 守卫或弹窗前判重 |
| P2-批次2 | ✅ 已修（2026-08-25 P2-批次2 修复批：buildDailySummary 逐日分析行为标注再合并，同 标的+类型+日期 去重；回归测试多日导入两天亏损加仓都标注，见 change-log）。**行为标注只分析导入文件最大日期（RFC 20260825 审查）**：10 天窗口内多日成交导入，只有最后一天的买入被判亏损加仓/追高，前几日行为漏标进总结 | `TradingAppService.buildDailySummary` | 按导入覆盖的每个交易日分别分析合并 |
| P2-批次3 | ✅ 已修（2026-08-25 P2 批次修复批：批次文本/徽标/警示 9-10px → 11px）。**app 批次行 9px/10px 超小字号（RFC 20260825 审查，P2-UI5 红线复发）**：含底仓徽标 9px / 批次文本 10px | `apps/adai-app/lib/pages/trading_page.dart:1039,1052` | 随 P2-UI5 全局字号提到 11-12 |
| P2-批次4 | ✅ 已修（2026-08-29 晚间自主修复批：一次选/粘贴多份文件，逐份处理 + 聚合结果，onImported 回调聚合导入数）。** **历史成交导入支持多文件批量（2026-08-25 用户反馈）**：web「导入历史成交」一次选/粘贴多份文件，后端顺序处理，每份独立结果（当前一次一份，连续导易挤锁）| web `_HistoryImportDialog` + `importHistoricalTrades` | 多文件队列 + 逐份处理/结果 |
| P2-批次5 | ✅ 已修（2026-08-29 晚间批：队列逐份状态行——第 N/共 M、处理中、耗时、成功/跳过/失败，超时可见）。** **导入过程进度反馈（2026-08-25 用户反馈「看不出是否卡住」）**：每份文件处理状态实时可见（第 N/共 M、处理中、耗时、成功/跳过/失败），超时提示卡住 | web 导入 Dialog | 逐份状态行 + 耗时 + 超时提示 |
| P2-批次6 | ✅ 已修（2026-08-29 晚间批：股息入账/红利税类型标签取代「买入 0 股」，发生金额 ±、统计口径排除股息事件单列计数）。** **历史成交完全尊重源文件（2026-08-25 用户反馈）**：股息入账/红利税前端显示为「股息入账/红利税」类型（不是"买入 0 股"）；字段按源文件原样呈现（成交金额/发生金额/委托编号/备注），系统计算的附加字段（费用等）放后面 | web 历史成交 Tab + 导入 | 股息类型标签 + 源文件字段原样 |
| P2-推送4 | ✅ 已修（2026-08-23 B9-5：标题透传后 session 四类徽章恢复 + gain/break-cost 专属徽章；见已修复区）。8 类型徽章 6/8 退化为灰色「行情」通用样式：session 4 子类（P1-推送1 根因）+ gain「放飞提示」+ break-cost「行情提醒」语义错位 | `feed_card.dart:388-395` / `desktop_feed_card.dart:193-200` | 为 gain/break-cost 补专属徽章；前后端标题表收敛为同一常量源 |
| P2-推送5 | ✅ 已修（2026-08-23 B11-2：双端开关失败透出原因，不再假阳性；web 侧 B5-6 已修，本批 app 对齐）。推送设置反馈假阳性：app 对话框「完成」恒 pop(true) 报「已更新」（读取失败全默认开、单开关 PUT 失败无提示）；web 关闭后零反馈 | `main_page.dart:889-899` / `trading_page.dart:243-266` | 记录真实变更与失败，按实际成功提示；两端同口径 |
| P2-推送6 | ✅ 已修（2026-08-23 B11-3：session 文案注明含 15:15 收盘操作确认，双端）。session 开关文案「时段节奏（早盘/午间/尾盘）」术语化，且连带控制 15:15「今日操作确认」——关时段节奏会无意关掉收盘确认 | `main_page.dart:1603-1613` / `trading_page.dart:2426-2436` | 文案改「早盘计划/午间跟踪/尾盘建议/收盘操作确认」或注明含收盘确认 |
| P2-UI1 | 买点提醒徽章 darkGreen 与「红涨绿亏」硬规则冲突（买=该买=涨→红；绿=亏）：RFC 20260817 定「买点绿」——设计冲突待用户拍板 | `feed_card.dart:392` / `desktop_feed_card.dart:197` | 改 darkRed 或 RFC 修订（用户决策）|
| P2-UI2 | 账户次级数据失败（静默）后可用/可取显示伪「0」、当日盈亏「+0」红色——伪数据冒充真实值 | `trading_page.dart:830-834` | ✅ 已修（2026-08-23 D9：无账户快照显示「—」+ 中性灰，见已修复区）|
| P2-UI3 | 确认卡方向选择器/「确认记录」按钮恒绿 + 买入成功 toast darkGreen——与买=红卖=绿、绿=亏语义冲突（成功色混用）| `trading_page.dart:321,661-663,774-792` | ✅ 已修（2026-08-23 D9：方向 chip/确认按钮/成功 toast 按买红卖绿，见已修复区）|
| P2-UI4 | ✅ 已修（2026-08-29 晚间批：自选买点信号列 ConstrainedBox maxWidth 170 + ellipsis；清仓统计标题行 Row→Wrap 窄窗自动换行）。** 自选买点文本多条件 '、' 拼接无 ellipsis、清仓复盘标题统计串未包 Expanded——窄屏 RenderFlex 溢出 | `trading_page.dart:497-499,519-525` | Flexible + TextOverflow.ellipsis 或拆行 |
| P2-UI5 | ✅ 已修（2026-08-29 B 类批：双端全局字号下限提到 11px——app 36 处 9 个文件 / web 全量，9/10/10.5px 残留 0，未误伤 11.5/12.5 等）。** 多处 9-10px 超小字号低于移动端可读下限（2026-08-23 用户视觉批清点 **27 处**，原登记 14 处；2026-08-25 批次行 2 处已随 P2-批次3 修复）| `feed_card.dart:442,624,749,755,1021` / `trading_page.dart:452,478,522-560` 等 | 全局最小字号提到 11-12 |
| P2-UX1 | ✅ 已修（2026-08-29 晚间批：NL 解析回显 _fmtPriceInput ≤5 位去尾零，4 位成本价不失真，与小键盘输入能力一致）。** NL 解析回显价格截 2 位小数（`toStringAsFixed(2)`），与「小键盘 5 位小数」设计矛盾——4 位成本价回显失真需手改 | `trading_page.dart:224` | 回显按后端精度（≤5 位）与输入能力一致 |
| P2-UX2 | ✅ 已修（2026-08-29 晚间批：web 自选/清仓区块标题下加术语图例——B1=回调缩量低吸/B2=放量突破右侧、R66=亏超5%扛单没走/R53=短持久持亏损、买点分=入场时机/执行分=纪律执行；清仓统计行本有人话「扛单超5%（R66）」「违反 R53」）。** 自选 B1/B2、清仓 R66/R53 等规则术语零解释（「B1 回调缩量 87%」「扛单 2·短亏 1」「买点 78·执行 62」）——移动端只读展示不可理解、无图例 | `trading_page.dart:497-502,516-524,551` | 首次出现给自然解释/图例；清仓区用通俗名（扛单/短亏）|
| P2-UX3 | 账户总览：本金未设（principal=0）时总盈亏=总资产失真且无提示；卡片无更新时间戳，收盘 15:05 后无陈旧感知 | `trading_page.dart:799-836` | ✅ 已修（2026-08-23 D9 快照时间戳 + B5 totalPnl getter principal=0 兜底，见已修复区）|
| P2-UX4 | ✅ 已修（2026-08-29 晚间批：确认流反馈——回执改阿呆口吻（「好，N 笔已经记进账了」，app/web/trading 三处）、按钮提交中 loading + 禁用（_ActionButton 组件）、确认后灰态「已确认 ✓」本地兜底）。** 今日操作确认流反馈（关联 P1-推送1，按钮修复后生效）：回执「已确认 N 笔并入账」系统语违 B1、按钮无 loading/禁用、确认后无已确认态兜底 | `main_page.dart:866-875` / `feed_card.dart:421-437` | 回执改阿呆口吻；提交中态；确认后本地灰态兜底 |
| P2-交易24 | ✅ 已修（2026-08-29 晚间批：closeAccountUpdate 残余窗口注释如实化，跨文件窗口无原子手段已文档化 trading-features §8）。** closeAccountUpdate 跨文件残余窗口：positions/quotes 在 update() 锁外读取，与 recordTrade 并发 → 快照=新现金+旧市值（P0-2 只锁单文件）| `TradingSessionPushService.closeAccountUpdate` | 持仓读取纳入同一锁或注释残余窗口 |
| P2-交易25 | ✅ 已修（2026-08-29 晚间批：TradeLogRepository.saveMerging 锁内原子「读最新→合并保留集→写回」——残余窗口（latest 读后 save 前）闭合，+2 仓储测试）。** confirm 读取在锁外：todayCandidates 先读、save(remaining) 后写——确认期间新 append 候选仍被覆盖（B5-4 注释未完全成立）| `TradeLogCollectService.confirm` | 锁内重读+合并 |
| P2-交易26 | ✅ 已修（2026-08-23 B7-3 已同步 api-spec；2026-08-29 核实 §662 ±10% 去重 + §667 confirm 响应 failed/skipped/failures 语义，出表）。** **api-spec 契约漂移（guard-align 门禁会拦）**：§612 confirm 仍写「确认后清空当日候选/`{"confirmed":2}`」、§607 去重口径未含数量桶 | `api-spec.md:607,612` | 同步本批行为（D49）|
| P2-交易27 | ✅ 已修（2026-08-29 晚间批：MarketPushRepository 写失败 warn→error，与账目落盘同级别）。** append 写失败 log.warn vs 账目落盘 error——同为持久化数据级别不一致 | `MarketPushRepository.append` | 统一 error |
| P2-交易28 | ✅ 已修（2026-08-29 晚间批：AccountSnapshot/TradeLog/MarketPush 三处 ConcurrentHashMap 锁池改固定 16 条带——#179 任意 userId 撑爆内存从根上消除）。** locks map 无清理 + X-User-Id 任意值（#179 零鉴权）→ map 无限增长 | `AccountSnapshotFileRepository` | 锁池封顶/复用 |
| P2-交易29 | ✅ 已修（2026-08-23 C5 锁收敛 userId 同一 map+key；2026-08-29 核实 append/save/discard/updateTradeDate 同锁，出表）。** save/append「同一把锁」仅注释声明，需确认同一锁 map + key（若 append 用独立 appendLocks 则 B5-4 无效）| `TradeLogRepository` | 确认或统一 |
| P2-交易30 | ✅ 已修（2026-08-23 C2 修正 2027 中秋 9/15 不在国庆；2026-08-29 核实代码 + holiday_2027 测试，出表）。** 2027 节假日预测表中秋日期存疑（2026 中秋在 9 月）| `trading-calendar` 知识 | 核对 2027 中秋 |
| P2-交易31 | ✅ 已修（2026-08-29 晚间批：principal=0 总盈亏 null 不给误导数值——双端「—」+ 未设本金提示，U32 满足；不再回落浮盈漏已实现盈亏）。** B2-2 总盈亏 principal=0 回落浮盈漏已实现盈亏——换一种误导（U32 未满足）| `trading_page.dart` | 口径补已实现盈亏 |
| P2-交易32 | ✅ 已修（2026-08-23 C3/C4 已修 batch 空 400 人话 + name ≤32 行级校验；2026-08-29 核实，出表）。** 批量导入空 trades 静默 200 成功 0；name 超长不校验 | `TradingController` / `TradingAppService` | 空列表拒绝 + name 长度校验 |
| P2-交易33 | ✅ 已修（2026-08-29 晚间批：缺行情收盘跳过时推送「账户今日未自动更新」通知——新股/停牌无昨收不再长期无感，受推送开关门控）。** B3-3 新股无昨收 → 收盘账户更新长期跳过、账户卡陈旧无通知 | `closeAccountUpdate` | 跳过时通知/占位 |
| P2-UI6 | ✅ 已修（2026-08-29 B 类批：下滑返回手势只挂拖拽条 44pt 热区，不再覆盖搜索栏 tap 大目标——四官命中结构性根因消除；搜索栏下滑不再误触返回，tap 正常打开搜索，+1 回归测试）。** **World B 误触搜索（08-20 体检 ⭐⭐⭐⭐ 四官命中）**：搜索栏是「下滑返回」手势区内的 tap 大目标（竞技场 tap 赢 + 300-400 双阈值死区 + 18px 返回箭头 + AnimatedSwitcher 过渡期可点）——结构性必然 | `launcher_page.dart:170-183,442-465` / `main.dart:239-269` | 下滑语义只挂拖拽条；搜索栏 tap 加位移判定或延迟消歧；过渡期 IgnorePointer（2026-08-23 归口 audits/2026-08-20）|
| P2-UI7 | ✅ 已修（2026-08-29 B 类批：launcher 插件行稳定槽位——加载完成前渲染「加载中…」等高位占位，加载后原地更新，插件行显隐不再跳位，+1 回归测试）。** launcher 行排序（切换账号置顶等；08-20 体检 product P2-2 + 用户视觉批）| `launcher_page.dart` | 行序稳定规则（切换/更新不跳位）|
| P2-UI8 | ✅ 已修（2026-08-29 B 类批：app 拖拽条 44pt 热区/日历格 32×36→44/搜索返回箭头/图片删除钮 12px→44 触达；web feed 选图钮 34→44；其余默认 IconButton 48pt 达标）。** 触达目标 <44pt（iOS HIG 最小触达 44×44；用户视觉批）——小按钮/行内点击区过窄 | 双端多处 | 关键交互触达区扩到 ≥44pt |
| P2-UI9 | ✅ 已修（2026-08-29 B 类批：双端硬编码 `Color(0x...)` 全量收敛——代码块背景 0xFF2A2826→darkBorder ×4、darkGreen@15%→withValues 写法 ×1，lib/ 下 hex 字面量残留 0）。** 硬编码色值散落未走 token（用户视觉批；如代码块背景 `0xFF2A2826`、圆角/边框硬编码）| 双端多处 | 收敛到 `app_colors.dart` token |
| P2-1 | **audit.md 归口机制内部自相矛盾（08-23 元审核归口，对抗官弱化裁定）**：58 行旧机制「REVIEW.md 新增走查区」vs 59 行新机制（audits/ 落盘 + unfixed-gate）同文件并存 | `ai-engineering/process/audit.md:58-59` | 58 行改指现行机制 | ✅ 已修（2026-08-23 P1 批：58 行改「报告落盘 audits/ + 登记 _index」，与 review.md 现行机制统一）|
| P2-2 | ✅ 已清（2026-08-29 核实 .claude/settings.local.json 已无 49.235.37.220 残留，出表）。** **.claude/settings.local.json 残留旧 IP 49.235.37.220 ×5（08-23 元审核归口）**：allowlist 是对已下线服务器的 curl/ssh/deploy 免确认放行——误跑静默失败；文件不入 git，影响仅本机 | `.claude/settings.local.json:47-52` | 手动清理 5 处旧 IP | ⚠️ 保留（本机工具配置，用户手动清）|
| P2-3 | ✅ 已修（2026-08-29：weekly-audit cron 已挂载——每周一 9:00 `--auto` 追加 /tmp/weekly-audit.log，脚本 dry-run 验证 W1-W5 跑通；与 guard-cost 日账同 crontab）| `weekly-audit.sh:3` | — | ✅ 2026-08-29（cron 实测挂载）|
| P2-4 | **frontmatter「9 字段」实为 10（08-23 元审核归口，命中数字漂移复发信号）**：skills-spec.md:40/42 与 AGENTS.md 均写 9，guard-meta REQUIRED 实 10 项 | `skills-spec.md` / `AGENTS.md` | 统一为 10 字段 | ✅ 已修（2026-08-23 P1 批：skills-spec/AGENTS/_index 全部 9→10；ADR-005 与 change-log 属历史记录不改）|
| P2-A2 | **方法论文档自身漂移（08-23 对抗官独立发现）**：method/README.md:89 引用不存在的 research-notes/（_index 自标待建）；64-71 行状态表把已存在的 deploy-gate/weekly-audit 标「❌ 缺」 | `method/README.md:64-71,89` | 状态表按实对拍 | ✅ 已修（2026-08-23 P1 批：状态表部署门禁/smoke 改 ✅ 已做、定时 audit 脚本已做 cron 待确认；research-notes 标注待建勿引用）|
| P2-A3 | ✅ 已修（2026-08-29 晚间批：deploy-gate.sh:34 同款吞输出修复——HIT 显示摘要、脚本自坏单独报错，与 pre-commit 同款；pre-commit 本批 2026-08-23 已修）。**pre-commit 吞掉 guard.sh 输出（08-23 对抗官独立发现）**：`>/dev/null 2>&1`（pre-commit:48、deploy-gate:34 同）——脚本自坏（如 services/ 缺失）与「有 HIT」无法区分，误拦/误放都无从排查 | `.githooks/pre-commit:48` | 输出降级为摘要行，HIT 与 ERROR 区分 | ✅ 已修（2026-08-23 P1 批：pre-commit 捕获输出——HIT 显示摘要、自坏单独报错可区分；deploy-gate:34 同款待下批）|
| P2-用户1 | **iOS App 周期「过期打不开」（2026-08-26 用户反馈「今天想记录，打不开」）**：免费 Apple ID 签名 **7 天过期**（backend-deployment.md §9 明示），失效后 App 直接打不开、无法自助修复——**设计缺陷必然复发，非偶发故障**；阻断用户记录关键场景，信任损耗最大 | iOS 签名/部署（`backend-deployment.md` §9/§10）| 零成本兜底：配 adaiadai.com DNS+HTTPS + 手机浏览器/PWA 应急通道（域名已注册未配，§10 有现成方案）；长期正解：付费开发者账号（$99/年，签名一年有效 / TestFlight 90 天）——成本需用户拍板 |
| P2-用户2 | ✅ 全落地（2026-08-30 批 2：`POST /records/ask-stream` SSE 端点——text 增量/meta 定稿/[DONE]/error 事件 + 后端内降级（无增量回退同步 understand）+ 同卡同问 5 分钟去重 + JsonTailFilter 防 JSON 跨块 + UTF-8 byte[] 透传防乱码；adai-app/adai-web 双端 SseClient（IO http.send 流式 / Web fetch ReadableStream 条件导入）+ 90ms 节流草稿 + meta 定稿 + 未收增量自动降级同步端点（intent 同构）；api-spec v3.34，端点 88→89，后端 890/app 143/web 132 全绿。批 1：S-9 超时矩阵 + S-10 模型分层 + StreamingAiClient 已于 2026-08-29 落地） | AI 调用链路 / `ai-calling-governance.md` | ✅ 2026-08-30（三端全绿；已随 2026-08-30 全量生产部署批上线，smoke 通过） |
| P2-用户3 | ✅ 已修（2026-08-29：每日收盘小结 15:30 推送到手机——当日成交（过滤股息流水）+ 破止损持仓 + 待确认候选 + 一句话收尾，模板聚合不耗 AI；新推送类型 close-summary 入 PushSettings + 双端开关；+3 测试）。** **交易「帮不到忙、没有感觉」（2026-08-26 用户反馈，使用频率下降主因；用户最在意交易）**：功能已堆 36 端点但价值不可感知——规则术语无解释（P2-UX2）、打分虚标（S7 三维实为二维）、建议只输出不落地、收盘推送未送到眼前；用户原话「目前还帮不到我啥」 | 交易模块双端 + 推送链路 | 术语人话化 + 去虚标 + 每日收盘「今日该注意什么」推送到手机（Bark 已接）+ 建议贴合真实操作；**优先级最高** |
> **FP-P2a~i 已出表**（2026-08-16 P2 清尾批，见已修复区）：输出侧校验 / R81 100万前提 / 测试补断言 / gap frontmatter / docs/README 登记 / 三阶段 RFC 滚动 / gap 指向 / 脚本相对路径 + CLAUDE.md 收录 / 编号对拍。**P2 表当前清零（P2-交易4/P2-交易20 均已出表，见已修复区）**。
> 历史观察项已迁移 task-log。

## 🔴 P0 / P3

- **P0-交易A ✅ 已修（2026-08-23 B6-1）**：`MarketPushRepository.append` 损坏防护升级为结构校验（数组且元素含 id 才放行，`[123]`/`{"a":1}` 不覆盖历史）+ MarketPushRepositoryTest ×3；原审查登记见 `audits/2026-08-23-reviewer-isolation-demo.md`
- **P0 其余当前清零**
- **P3-案例1（2026-08-30 案例库审查）**：`generateInsight` 用新 `AiTraceContext.source="trading_case_insight"`——模型路由表对该新值未显式登记（预期落默认 flash，下次 AI 治理文档同步时登记）
- **P3 打磨项**：交易 A-E 批 24 项**已全部修复出表**（R8-R11，见 change-log + 已修复区：转账金额提示/isTdxExport/R120-R85 引用/止损提醒/孤儿规格/DTO 测试/Timer 守卫等）；剩余低价值 P3（FilePicker 压缩/os 数据卫生/图片摘要居中）见 task-log
- **P3 打磨项（2026-08-19 UI/UX 审查 17 项）**：成功回执不提自动 -7% 止损 / 精确表单价格无 ≤5 位小数限制 / 持仓卡整卡可点无视觉提示（发现性差）/ 自选清仓空列表整区隐藏无引导 / 「无法连接服务器，请确认后端已启动」开发者术语 / 推送设置「放飞提示」语义不明 / Dismissible confirmDismiss 内同步 setState 非惯用法 / 账户卡 22px 双大数无 FittedBox 极端值溢出 / 零值 ± 显示与着色（+0/-0/±0.0%）/ `_fmtMoney` 万单位舍入跳变（9999.6→10000）/ 行情卡 -0.00% 判跌为绿 / 代码块背景硬编码 0xFF2A2826 未走 token / 提示文案对比度不足（darkGrey6@10px）/ 持仓信息行 11px 过暗·未设止损整行橙偏重 / 圆角不统一（8/10/12/16 vs 主题 14）/ 次级数据异步到达跳变无骨架占位 / 推送卡长内容无折叠 + 买点文本无单位标签 + frontend-reference 徽章配色表与实现漂移

## ✅ 已修复区（最近 10 条，一行摘要；详情见 `docs/reference/change-log.md` + git log）
- **2026-08-30 案例库批次审查 S1（save 双文件回滚）**：`TradingCaseFileRepository.save` index 写失败被 StorageException catch 提前重抛跳过回滚 → 文件残留 + 重试 409 卡死——统一 catch 先回滚删案例文件再抛 + 回归测试 `save_indexWriteFailure_rollsBackCaseFile`（后端 939 全绿；详见 audits/2026-08-30-case-library-review.md）
- **2026-08-26 截图入账 + 复盘卡点批**（用户拍板：交易闭环——截图入账一等入口 + 复盘与真实成交绑定；后端 800 / app 129 全绿；详见 change-log）
- **2026-08-25 RFC 20260825 批次跟踪批**（对抗 P0-1 手动/导入交叉防重 + P1×5 + 后端 P1×2·P2×3 + 前端 P1·P2 + 文档 P1×3·P2×2 全部修复；后端 758 / web 121 / app 125 全绿，guard-meta/align PASS；详见 audits/2026-08-25-lot-tracking-review.md + change-log）

| # | 摘要 | 修复 |
|:-:|:-----|:----:|
| 2026-08-29 收盘小结 + 流式基础设施批 | **①P2-用户3 每日收盘小结**——15:30 推送「今日成交（过滤股息流水 volume=0）+ 破止损持仓 + 待确认候选 + 一句话收尾」到手机（Bark 已接，模板聚合不耗 AI）；新推送类型 `close-summary` 入 PushSettings.ALL_TYPES + app/web 双端开关；+3 测试。②**P2-用户2 流式后端基础设施**——`StreamingAiClient` 端口（kernel）+ `DeepSeekAiClient.streamGenerate`（body stream:true + SSE `data:` 行解析 delta 逐块回调 + 非 200/空内容抛错供降级）；本地 SSE stub 测试 ×2（逐块拼接/HTTP 500 抛错/请求带 stream）。SSE 端点与双端前端流式渲染下一批。测试：后端 835→840 | ✅ 2026-08-29（后端全绿，本地 commit 未部署）|
| 2026-08-29 B 类技术债清理批 | **REVIEW B 类 8 项纯技术债全部清理（用户拍板）**：①P2-UX4 确认流反馈（回执阿呆口吻三处 + _ActionButton 提交中 loading/禁用/成功灰态「已确认 ✓」）；②P2-UI7 launcher 插件行稳定槽位（加载中占位防跳位）；③P2-UI6 World B 下滑返回只挂拖拽条 44pt 热区（搜索栏不再误触，四官根因消除）；④P2-UX2 web 自选/清仓术语图例（B1/B2、R66/R53、三维打分）；⑤P2-UI5 双端 9/10/10.5px 全局提到 11（app 36 处 + web 全量，残留 0）；⑥P2-UI8 关键触达区扩到 ≥44pt（拖拽条/日历格/返回箭头/删除钮/选图钮）；⑦P2-UI9 双端硬编码色值收敛 token（hex 残留 0）；⑧P2-3 weekly-audit cron 挂载（每周一 9:00 + 脚本验证）。测试：app 135→137（+2 回归）· web 125 全绿 | ✅ 2026-08-29（双端全绿，本地 commit 未部署）|
| 2026-08-29 晚间自主修复批 | **用户睡觉期间自主修复 REVIEW 未修项（14 项出表 + 残留核实 5 项）**：①历史成交导入三连（P2-批次4 多文件批量 / P2-批次5 逐份进度反馈 / P2-批次6 股息入账·红利税类型标签取代「买入 0 股」+ 统计口径排除）；②P1-前端1 app 静默刷新失败保留旧数据（web P1-7 同类复发根治）；③P2-UX1 NL 回显 ≤5 位去尾零（4 位成本价不失真）+ P2-UI4 买点信号 ellipsis/清仓标题 Wrap；④后端技术债——P2-交易25 confirm 锁内原子 saveMerging（残余窗口闭合）、P2-交易28 三处锁池改固定 16 条带（map 增长根治）、P2-交易27 推送写失败 warn→error、P2-交易33 缺行情收盘跳过推通知、P2-交易24 残余窗口注释、P2-交易31 principal=0 总盈亏 null 不给误导数值（双端，U32）；⑤P2-A3 deploy-gate 吞输出修复；⑥REVIEW 残留核实出表（P2-交易26/29/30/32、P2-2 均已修）。测试：后端 833→835 · app 132→135 · web 122→125，三端全绿 | ✅ 2026-08-29（三端全绿，本地 commit 未部署）|
| 截图入账 + 复盘卡点批 2026-08-26 | **用户三问落地**：①持仓转圈（AI 建议生成中）保留；②复盘没卡点（has-activity 关键词扫描对话记录——聊到"买/仓/股"即误报、导入成交后反而不报）→ 改「当日真实成交 > 0」才可生成复盘；③交易页「📷 截图入账」一等入口（用户核心工作流是发截图，对话框发图太绕）。**实现**：后端 `POST /trading/screenshots`（multipart 1-3 张 → VLM → 归集候选，不建记录/不落原图/不沉淀记忆）+ hasTradingActivity 真实成交口径；app 交易页截图入口 + 当日候选内嵌列表（逐笔丢弃 + 全部确认入账）+ 复盘按钮无成交引导先导入。测试：后端 +14 · app +4 | ✅ 2026-08-26（后端 786→800 · app 125→129 · 端点 84→85，全绿）|
| app 体感修复批 2026-08-23（D1-D9）| **P1**：D1 切 World 丢输入现场（IndexedStack 保活 + Launcher refreshTick 刷新）；D2 排序四实锤（_loadMore id 去重 / 切回保留更早页 / updatedAt 透传「刚刚」修复 / 时间线默认日期复核正确）；D3 首载 Feed 失败错误态+重试（原伪装空态）；D4 任务编辑走 PUT（原恒新建，P-app-08）；D5 launcher 计数失败显「—」（原伪 0）；D6 ProjectStatus 三态（spinner+人话+重试）；D7 task/trading 错误文案提取后端人话（原状态码/英文 network error）。**P2**：task tasks+stats Future.wait 分离（stats 可降级）；search/timeline 代际令牌。**UI/UX**：P2-UI2 账户无快照显「—」；P2-UI3 方向 chip/确认按钮/成功 toast 按买红卖绿（原恒绿）；P2-UX3 快照时间戳（原无陈旧感知）；P2-UX2 术语图例 app 已不适用（自选/清仓区 08-22 移除）。P2-UI1 买点徽章色待用户拍板跳过 | ✅ 2026-08-23（app 120 全绿）|
| 剩余可修项批 2026-08-23（C1-C6）| **C1**（隔离审查 P2-2）confirm 读取锁外——save 前合并处理期间新归集候选（防覆盖清空）；**C2**（P2-7）2027 中秋修正——农历八月十五=9/15 不在国庆，国庆 10/1-10/7 无 8 天长假（10/8 开市）；**C3**（P2-9）batch 空 trades → 400 人话（原静默 200 成功 0）；**C4**（P2-10）batch name 超 32 字符行级人话失败（与单笔同口径）；**C5**（P2-5）TradeLogRepository 锁 key 收敛 userId（date 维度无限增长根治）；**C6**（战略）双锁注释如实化（单实例内）+ trading-features §8 补双锁/跨文件窗口注意点 | ✅ 2026-08-23（后端 728→731 全绿）|
| 推送链路修复批 2026-08-23（B9-B11，12 项）| **B9（P1-推送1 根因 + P2-推送4）**：MarketPushEvent 加 title 字段 + FeedPushChannel 透传原标题 + MarketPushRepository 序列化（旧文件兼容 null）+ toPushEntry 改用原标题（旧数据 type 兜底）+ 双端「确认并入账」按钮/徽章回归 + gain/break-cost 专属徽章（原落灰）；**B10（P1-推送2）**：`DELETE /trading/pushes/{id}` 删除持久化端点 + app 左滑删调后端 + web 推送卡「忽略」按钮（双端幂等 404）；**B11**：app 交易页推送设置铃铛入口（P1-推送3 self-lock 修复）+ app 推送设置失败透出原因（P2-推送5）+ session 文案含收盘确认（P2-推送6）+ confirm 返回 {confirmed,failed,skipped,failures} + 失败候选丢弃入口接线（P1-交易18，`DELETE /trade-log` 双端调用）。测试：后端 +4（title round-trip/旧文件兼容/dismiss×2）· web +5（确认按钮/忽略/徽章/并存）· app 回归 120 全绿 | ✅ 2026-08-23（后端 724→728 · web 107→112 · app 120 全绿；P1-推送1/2/3、P2-推送4/5/6、P1-交易18 出表）|
| 隔离审查残留修复批 2026-08-23（B6-B7，9 项）| **P0-A** MarketPush 结构校验（数组+元素含 id 才放行，[123]/{"a":1} 不覆盖历史）；**B6-2** dedupeKey `sameTrade` ±10% 区间判定替代字符串桶（10/19 分开、100/110 合并）；**B6-3** closeAdvice/midday/dataText 三处 changePercent 判空（缺行情显 '-'）；**B6-4** AccountSnapshot 写失败抛 StorageException（不再静默）+ GlobalExceptionHandler 500 人话 + recordTrade/closeAccountUpdate catch 告警；**B6-5** confirm 单 now 贯穿 + `_TabHistoryRefreshListener` 切 Tab 静默刷新 + `DELETE /trade-log` 丢弃端点；**B7-2** MarketPush/TradeLog 真实仓储并发/损坏/dedupe 测试；**B7-3** api-spec trade-log 契约（去重 ±10%/confirm 响应/discard）；**B7-1/4** P1-15/P1-16 复核（前者不成立维持登记、后者误报移除）| ✅ 2026-08-23（后端 703→720 · web 107 全绿；P1-交易11/12/13/14/17/19 出表，P1-交易18 半修待推送批，P1-交易15 复核登记，P1-交易16 移除）|
| 交易走查修复批 2026-08-23（B1-B5，21 项）| **P0-1** confirm 失败候选保留+返回明细（`{confirmed,failed,skipped,failures}`）；**P0-2** account.json per-user 写锁（`update` 原子 RMW，5 写路径统一，并发无丢失测试）；**P1-1** direction @NotNull 400；**P1-2** batch 逐行字段校验；**B3-2** 尾盘模板 r81Applicable；**B3-3** 收盘 yesterdayClose 残缺跳过；**B3-4** AccountSnapshot 写失败 error 告警+返回 null；**B3-5** 久持小亏 verdict 标 R53 延展；**B4-1** buy-point-rules.md 按代码事实重写（虚标纠偏）；**B4-2/3/4** api-spec 头部 v3.24/变更记录补全/has-activity 例外标注/KDJ13 参数；**B4-5** feature-reference §9 同步；**B5-1** 节假日 2026 官方+2027 预测；**B5-2** MarketAlert 止损与涨跌判定拆开；**B5-3** dedupeKey 加 volume 桶；**B5-4** TradeLog save 加锁；**B5-5** MarketPush 读失败拒写回；**B5-6** web 历史成交 Tab keepAlive + 推送设置失败提示；前端资金区块总盈亏 principal=0 失真（totalPnl getter）；历史成交导入错误透出人话（顶层 extractApiErrorMessage）| ✅ 2026-08-23（后端 690→703 · web 104→107，全绿）|
| RFC 20260817 三项批 | 推送卡专属样式（类型徽章+结构化内容）+ 左滑删/右滑设置 + per-user 推送开关（写/读双侧门控）；图片对话卡图置顶 turns 跟随（刷新后对话流形态）；交易日志自动归集（截图/文字识别→当日候选去重→收盘 15:15 确认推送→确认落库 recordTrade）；后端 644→654（+parseLoose/不完整跳过）· app 118→120 | ✅ 2026-08-17 |
| Memory 正文分隔符截断批 | ENTRY_SPLIT 正则：正文含裸 `---` 提前截断 → 后半正文误当 frontmatter → createdAt 缺失记忆丢失（生产 110 告警/17 条）；新正则要求 `---` 后紧跟键值行才截断 + 回归测试；后端 643→644 | ✅ 2026-08-17 |
| 走查前端 8 项批 | web 可降级锁/日期竞态/图片回执自然化/交易成功 toast/首载失败错误态/错误人话；app+web Feed 徽章移除（第一原则）；admin 涨跌色 darkRed；web 98 · app 118 · admin 34 全绿 | ✅ 2026-08-17 |
| P2-交易20 guard-align 盲区批 | A1 正则补裸注解分支（`@GetMapping`/`@GetMapping()` 继承类级 base）：11 个裸注解不再漏数，A1 61→72 全对齐 endpoints.txt 真相源；P2-交易20 出表，P2 表清零；G1-G7 全 PASS | ✅ 2026-08-17 |
| P1-交易4 占比分母含现金批 | TradingSessionPushService.positionPercent 分母 = 持仓市值 + AccountSnapshot.cash（S5 真源）：SessionData 注入现金 + serviceWithCash 测试 helper + 回归测试（现金 100 万不再误发 R81）；P1-交易4 出表；后端 642→643 | ✅ 2026-08-17 |
| S5 现金单一真源批 | 现金唯一真源=account.json AccountSnapshot.cash：importCashQuery 不再写 positions.md cashBalance（saveCashBalance 调用移除，读取方全走 AccountSnapshot）；TradingAdviceAppService R81 分母 / TradingAppService getPortfolioSnapshot / TradingReviewAppService 复盘快照均改 AccountSnapshot.cash；测试补 AccountSnapshot mock 与断言（P2-交易4 + S5 出表）| ✅ 2026-08-17 |
| P2 批 C（文档四连）| roadmap 状态修正 + feature-reference 补端点 + api-spec 403 契约 + os 空文件删 | ✅ 2026-08-17 |
| P2 批 B（前端六连）| 记忆页守卫 + 缩略图降采样 + 图片 caption + toggle catchError；核实 3 项已闭环 | ✅ 2026-08-17 |
| P2 批 A（后端六连）| 原子写 + TagIndex 锁 + 交易流水线 + Memory 三缺陷 + delete 门控 + 插件缓存 | ✅ 2026-08-17 |
| Review 修复批 R11（admin + 收尾）| admin 静默刷新 + setPlugins 死接口删 + PromoteResultDto message；核实 2 条已闭环 | ✅ 2026-08-17 |
| Review 修复批 R10（P3 深水区）| _closeChat indexWhere + emoji 截断 + stripUserPrefix 兜底 + 脱敏千分位 + RFC 勾销 + feed 示例 domain；核实 W-P3-3 已消失 | ✅ 2026-08-17 |
| Review 修复批 R9（P3 尾尾批）| web SnackBar 队列/ValueKey 保活 + app superseded 标记 + ANALYSIS system 收敛注明 + CLAUDE.md 去重拆行 + README 断链 + T1.3 superseded + PATCH 契约；核实 6 条旧 P3 已闭环 | ✅ 2026-08-17 |
| Review 修复批 R8（P3 尾批）| isValidPlugins 查重 + gateDomain 白名单收敛 + HEIC MIME + api-spec 关键词同步 + ask-batch 登记；核实 5 条旧 P3 已闭环 | ✅ 2026-08-17 |
| Review 修复批 R7（P3 收尾）| 八端点 controller 测试 + web DTO 测试 + 降级日志 warn + WeChat interrupt + buy-point-rules 登记 + gap lines；后端 629→640 · web 92→98 | ✅ 2026-08-17 |
| Review 修复批 R6（P2/P3 web）| mounted 守卫/打分去重/纪律遵守率口径/否定词/删除确认/千分位/打分列色/lookup 防抖/NaN 校验等；web 89→92 | ✅ 2026-08-17 |
| Review 修复批 R5（P2 后端）| 线程池 shutdown/并发扫描/腾讯缓存/R66-5%/KDJ-13/B1? 不推/r81Applicable/导入校验/节假日/调度器 4 线程/Feed 标题；后端 626→629 | ✅ 2026-08-17 |
| Review 修复批 R4（P1 五连）| P1-1 切入自动刷新死代码（plugin 判等）；P1-2 买卖同步市值（总资产不变式）；P1-3 收盘缺行情跳过；P1-7 可降级请求分离；P1-8 打分按序匹配；P1-10 api-spec 示例；后端 622→626 · web 87→89 | ✅ 2026-08-17 |
| Review 修复批 R3（导入落零防护）| importCashQuery 解析失败禁止落零（P1-交易5 出表，B51）：headerMatched + TradingException 400 人话 + web 导入失败 toast；后端 620→622（+2）| ✅ 2026-08-17 |
| Review 修复批 R1（择时路径 + 推送文案 + 持仓编辑）| CURRENT_MD 配置注入（生产择时状态恢复，P1-交易6 出表）；loss 文案如实化；**持仓编辑 404 修复**（PUT /positions/{symbol} 补端点，P2-交易23 出表）；web 记录交易默认止损 -7%（用户设定）；生产 5 只持仓按成本×0.93 补止损；后端 612→619（+7）· 端点 71→72 | ✅ 2026-08-17 |
| 框架+插件审查 P2 清尾批（FP-P2a~i）| parseLlmAdvice 输出侧校验（BREACHED→强制 clear、OVER_WEIGHT→buy 保守改 reduce，B45）；R81 100万前提（总资产超 100 万不强制，参考 R82-R95）；测试补断言（硬信号段 + currentPrice≤0）；gap 补 frontmatter（D44）；docs/README 登记新文档；三阶段 RFC 升 approved + 实施记录 + §三同步；gap 指向正式总纲；update-current.sh 相对路径 + CLAUDE.md 收录（09-scripts 行）；编号对拍（CLAUDE.md R1-R120/E1-E30 + agent-skill E1-E30，K39）；后端 556（+1）| ✅ 2026-08-16 |
| 框架+插件审查修复批（FP-S1-S4 + FP-P1-P4）| yml 路径 11-context→knowledge/context（运行时断链根治，三官交叉印证）；R81 分母改总资产（现金纳入，+测试）；update-current.sh 幂等+时间戳语义+声明修正；R66 现价口径注明；总纲 §五 刷新全 ✅；引擎口径契约测试 RuleKnowledgeContractTest（B44）；rules-api.md §2/§3 同步；后端 555（+4）| ✅ 2026-08-16 |
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

> 审查官独立并行走查，交叉印证（同一问题多官命中 = ⭐ 优先级高）。走查日期 + 摘要滚动保留。

> 2026-08-20 app 全面体检（用户体感导向，4 官：ui/ux/frontend/product）

> 📄 完整发现清单见 `docs/review/audits/2026-08-20-app-health-check.md`。

> 守护 G1-G7 7 PASS / 0 HIT + META PASS。**P0 无。战略×5 + P1×17 + P2/P3×24**。**核心（4 官 ⭐⭐⭐⭐）**：World B 误触搜索=手势语义冲突（搜索栏在返回手势区内是 tap 大目标 + 300-400 双阈值死区 + 18px 返回箭头 + AnimatedSwitcher 过渡期可点）；切 World 丢输入现场（草稿/对话/上传，P-app-15 升 P1）。**排序（⭐⭐⭐）**：实现无 bug 符合 DESIGN（最新在底），实锤在 4 处（`_loadMore` 无 id 去重 / 切回重置 page0 / 时间线默认最早日期 / `updatedAt=now`「刚刚」恒显）+ 双端方向相反待拍板。**战略**：双主页形态违背「一个页面」、阿呆系统页第一原则泄漏、下滑手势四语义叠加、roadmap 漂移。检查点建议 6 条（C-app-* 系列）。

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
| 2026-08-30 | deep 增量（案例库批次，**降级主会话审**）| —（子代理故障）| 0 | ~25min | 战略×1 + P1×1 + P2×5 + P3×1（去重后）| 1（S1 回滚）|
| 2026-08-23 | 隔离审查演示（交易归集批修复后残留）| backend + adversarial ×2 独立子代理（隔离子上下文）| 2 | ~20min | P0×1 + 战略×2 + P1×9 + P2×10（去重后）| 0（审查只报告）|
| 2026-08-20 | full app 体检（用户体感导向）| ui/ux/frontend/product ×4 + 主会话 | 4 | ~40min | 战略×5 + P1×17 + P2/P3×24（去重后）| 0（审查只报告）|
| 2026-08-24 | deep 方案文档审查（AI 调用治理）| docs/backend/frontend/adversarial ×4 | 4 | ~25min | P0×1 + 战略×7 + P1×11 + P2×13（去重后）| 0（审查只报告）|
| 2026-08-19 | full 模块审查（app 交易 UI/UX + 推送链路）| ui/ux/frontend ×3 + 主会话 | 3 | ~30min | P1×4 + P2×12 + P3×17（去重后）| 0（审查只报告）|
| 2026-08-17 | deep 增量（交易 A-E 批1-5）| backend/frontend/docs/knowledge ×4 | 4 | ~30min | 战略×3 + P1×10 + P2×16 + P3×24（去重后）| 0（审核只报告）|
| 2026-08-17 | 修复批 R11（admin + 收尾）| — | 0 | ~20min | 0 新 | admin×3 |
| 2026-08-17 | 修复批 R10（P3 深水区）| — | 0 | ~30min | 0 新 | W-P3×8 |
| 2026-08-17 | 修复批 R9（P3 尾尾批）| — | 0 | ~30min | 0 新 | P3×12 + 核实 6 闭环 |
| 2026-08-17 | 修复批 R8（P3 尾批）| — | 0 | ~30min | 0 新 | P3×11 |
| 2026-08-17 | 修复批 R7（P3 收尾）| — | 0 | ~30min | 0 新 | P3×11 |
| 2026-08-17 | 修复批 R6（P2/P3 web）| — | 0 | ~40min | 0 新 | P2×8 + P3×7 |
| 2026-08-17 | 修复批 R5（P2 后端）| — | 0 | ~40min | 0 新 | P2×9 + P3×8 |
| 2026-08-17 | 修复批 R4（P1 五连）| — | 0 | ~40min | 0 新 | P1-1/2/3/7/8/10 共 6 |
| 2026-08-17 | 修复批 R1 | — | 0 | ~20min | 0 新 | P1-交易6 + loss 文案 2 |
| 2026-08-16 | 修复批（框架+插件审查发现）| — | 0 | ~20min | 0 新 | FP-S1-S4 + FP-P1-P4 共 8 |
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
