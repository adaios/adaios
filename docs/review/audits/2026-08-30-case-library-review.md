---
title: 完美买点案例库批次审查（第四阶段环 1-4 + 二期）
date: 2026-08-30
baseline: c9c2918..fec30bf（案例库批 1-4 共 4 commits，36 文件 +4209 行）
mode: deep 增量审查（**降级：子代理环境故障（subagent/workflow 全线启动失败 ×2 次尝试），改主会话直接审查**——后端/前端/对抗/文档四视角顺序审，对照 pitfalls.md + 各域 checklist；失去官间隔离交叉印证，发现以主会话独立判断为准）
---

# 完美买点案例库批次审查报告

> 审查范围：`git diff c9c2918..HEAD`（案例库批 1：K线范围直查/特征提取/JSON存储/4端点；批 2：web K线图/案例 Tab；批 3：LLM aiInsight；批 4：归一化/相似度/match + web 匹配；二期：扫描接入/黄白线参数化）。
> 守护：G1-G7 **7 PASS / 0 HIT**（2026-08-30 跑）+ META-GUARD PASS + GUARD-ALIGN PASS（938/143/95/17）。

## 发现（主会话审查，合并去重后）

### ✅ 已修（本轮审查修复批）

| # | 级别 | 问题 | 修复 |
|:-:|:---:|:-----|:-----|
| S1 | 战略 | `TradingCaseFileRepository.save` 写案例文件成功但 `_index.json` 写失败 → `catch (StorageException e) { throw e; }` 提前重抛**跳过回滚** → 文件残留 + 下次标注 `exists(文件)=true` → 409「已标注过」卡死（无路可走）| 统一 catch 结构：所有异常（含 StorageException）先回滚删案例文件再重抛；回归测试 `save_indexWriteFailure_rollsBackCaseFile`（939，+1）|

### 未修（登记 REVIEW）

| # | 级别 | 问题 | 位置 | 建议 |
|:-:|:---:|:-----|:-----|:-----|
| P1-案例1 | P1 | **腾讯 `klineRange` 日期格式未实测**：URL `param=%s,day,%s,%s,320,qfq` 传 `LocalDate.toString()`（yyyy-MM-dd）——腾讯 fqkline 接口日期参数格式（yyyy-MM-dd vs yyyyMMdd）未真网络验证；格式不符 → 主源空转 → 走东财兜底（beg/end yyyyMMdd 标准）功能可用但主源失效 | `TencentMarketDataSource.klineRange` | 部署前本地实测一次真接口（bootRun 打 600519 某历史区间）；若 yyyy-MM-dd 不被接受改 BASIC_ISO_DATE |
| P2-案例1 | P2 | 窗口不足 60 根（停牌/新股/标注日靠近窗口起点）→ `ma()` 用可用根数近似 → MA60/黄白线态/距 60 日线失真（已知取舍，特征仍可算但不精确）| `CaseFeatureExtractor.ma` | 标注为已知限制（设计文档 §4.1 注明「不足用可用根数」）；后续可在特征加 `windowComplete` 标记 |
| P2-案例2 | P2 | **web 自选 Tab 未适配 `buyPoint="case"`**：二期开关开启时规则未命中但案例相似 → 返回 `buyPoint="case"` 项，前端信号列渲染 `{类型} {score}%` → 显示「case 0%」异常 | `WatchlistBuyPointService` / web 自选 Tab | 前端适配 case 类型显示（「形态接近历史完美买点」+ 相似度）；未部署前开关默认关无影响，前端适配随部署批 |
| P2-案例3 | P2 | 二期开关开时 `scanWatchlist` 每跑全量 `caseRepository.list`（读 index + 逐案例读文件），案例多时拖慢扫描（无缓存）| `WatchlistBuyPointService` | 案例少可接受；案例库 >50 后加 TTL 缓存（对齐 TradingKnowledgeSource 时间戳缓存模式）|
| P2-案例4 | P2 | 东财 `klineRange` 同时传 `lmt=320` + `beg/end`——接口对两者同传的截断行为未实测（可能忽略 lmt 或 beg 前截断）| `EastMoneyKlineDataSource.klineRange` | 与 P1-案例1 一并实测 |
| P2-案例5 | P2 | `indexEntry` 的 `plus5dReturnPct`：`verify()==null` 时存 `0.0` → 列表前端显示「+5d 0.0%」而非「—」（正常路径 verify 恒非 null，仅手工构造/异常数据触发）| `TradingCaseFileRepository.indexEntry` | 改存 null（Jackson 写 null 字段）或列表端判 0 兜底 |
| P3-案例1 | P3 | `generateInsight` 用新 `AiTraceContext.source="trading_case_insight"`——模型路由表（flash/pro 按 source）对该新值的行为未显式登记（预期落默认 flash，观察确认）| `TradingCaseAppService` | 下次 AI 治理文档同步时登记该 source |

## 已沉淀检查点

- backend checklist：save 双文件写入（案例 + index）失败必须**回滚已写文件**再抛（S1 复发信号：index 写失败不删 case 文件）
- backend checklist：新增「数据源 URL 参数格式需实测」检查点（P1-案例1/P2-案例4 同源）

## 审查说明（降级记录）

- 原计划：backend/frontend/docs/adversarial ×4 官隔离并行（review.md deep 规范）
- 实际：subagent / workflow agent **全线启动失败**（4 官后台 + 1 前台 + 1 诊断 + 1 workflow probe，7 次尝试均「run failed」）——环境级故障
- 降级：主会话独立审查（四视角顺序），发现以主会话判断为准，未获多官交叉印证
- 修复：S1 已修（939 全绿）；P1/P2/P3 登记 REVIEW 待后续批
