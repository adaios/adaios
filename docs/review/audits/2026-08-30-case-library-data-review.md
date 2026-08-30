---
title: 案例库数据批次审查（TDX 源/前复权/指标一致/共识/配套脚本）
date: 2026-08-30
baseline: fec30bf..HEAD（12 commits，44 文件 +2771 行：TDX 本地源、前复权、前后端指标一致、共识判定、案例校验、配套脚本、图表演进）
mode: deep 增量审查（**降级：子代理环境故障（probe 再验证仍 fail），主会话四视角顺序审**——后端/前端/对抗/文档，对照 pitfalls；失去官间隔离）
---

# 案例库数据批次审查报告

> 范围：上次审查（audits/2026-08-30-case-library-review.md，c9c2918..fec30bf）之后的所有批次——
> 数据正确性主线（TDX 源 6c9c263 → 前复权 d2af07d → 指标一致 f4ffe0f → 共识 758820c → 案例校验 5ae030d → 配套 4a52b8c）+ 图表交互演进（ae8e3d3/c73aedb/68ce9df）。
> 守护：G1-G7 **7 PASS / 0 HIT** + META PASS + ALIGN PASS（979→980）。

## 发现（主会话审查，合并去重后）

### ✅ 已修（本轮审查修复批）

| # | 级别 | 问题 | 修复 |
|:-:|:---:|:-----|:-----|
| P1-数据1 | P1 | **KdjIndicator.latest 回归（特征语义漂移）**：重构加 series 后 latest 对 <10 根返回 (50,50,50)（原 null）→ 案例特征 kdjJ 从 null 变 50（旧案例 vs 新案例不一致）| 恢复 `candles.size() < 10 → null`（series 全序列保留用于图表前 8 根显示）+ 回归测试（980，+1）|
| P2-数据1 | P2 | **_SymbolSearchField 搜索竞态**：快速输入多次触发防抖请求，旧响应后返回覆盖新候选（无代际令牌）| 加 `_seq` 代际令牌，响应回来时 seq 不匹配丢弃（web 145 全绿）|
| P3-数据1 | P3 | trading-features §10 环 2「web 画图（P2 批）」标注过时（图表已多轮演进完成）| 更新为「通达信风格三副图 + 单日指标 + 窗口移动，已完成」|

### 📋 登记（未修，观察项）

| # | 级别 | 问题 | 位置 | 建议 |
|:-:|:---:|:-----|:-----|:-----|
| P3-数据2 | P3 | **因子拉取失败静默降级**：AdjFactorRepository fetch 失败返回空 → TDX 不复权原样返回（数据错误但功能不坏，无提示）| `AdjFactorRepository.factorsFor` | 记录 warn 已有时序日志；可后续加「复权不可用」标注（当前网络源可用，风险低）|
| P3-数据3 | P3 | match 每次拉 K 线 + buildProfile O(案例数)（标注少可接受；案例 >100 后画像可缓存 TTL）| `TradingCaseAppService.match` | 案例量大后加画像 TTL 缓存（对齐知识源缓存模式）|

## 已核实通过项（对抗视角）

- 前复权算法多事件累计（测试覆盖）与真实茅台校验 ≤0.5%；TDX 前复权 vs 腾讯 qfq 口径一致
- 因子缓存：ConcurrentHashMap + 按日 TTL（updatedAt=今天）+ 不可变列表；拉取失败不污染缓存
- annotateWithCheck：existingBeforeSave 在 save 前捕获（并发标注各自独立）
- 前后端指标同源：前端用后端序列（fromJson），重算仅兜底——hover 值 = 特征值
- TDX 数据抽查脚本实测 10/10 PASS；同步脚本分流/覆盖正确（修 cp -n 不覆盖）
- 日期宽容解析（ISO/BASIC/人话）、Map.of null 修复（HashMap）均验证

## 审查说明

- 子代理环境仍故障（probe：subagent run failed）——与上次同因，降级主会话审
- 守护 7 PASS / 0 HIT；无 P0 / 战略级发现
- 沉淀检查点：指标 latest 语义变更须回归验证（KdjIndicator 教训）；防抖搜索须代际令牌
