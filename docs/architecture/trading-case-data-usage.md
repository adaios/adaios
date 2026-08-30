---
title: 案例库数据使用方案（双轨画像 · 后验回灌 · 负样本）
description: 完美买点案例库从「沉淀」到「判定」的数据使用定稿方案——双轨画像、后验回填闭环、负样本入库、权重可配置（2026-08-31 定稿，当晚实施）
version: 1
created: 2026-08-31
updated: 2026-08-31
status: active
tags: [trading, cases, data, architecture]
---

# 案例库数据使用方案

> 定稿：2026-08-31 深夜（用户授权当晚全部实施，明早验收）。
> 背景：案例库已 32 案例（B1=14 / B2=18），将随标注持续增长。本文是「数据如何使用」的可执行设计。

## 1. 现状与问题（2026-08-31 实测）

**数据资产**：32 案例（14 B1 + 18 B2），9 维特征 + 后验 verify + AI 理解（1/32 有）。

**现有消费点**：`CaseConsensus`（25-75 分位画像）、`CaseSimilarityEngine`（加权欧氏 TopN）、
`scan-match` 开关（默认关）、web 匹配弹窗共识卡。

**三个核心问题**（本方案要解决的）：
1. **单轨混合**：Consensus 与相似度引擎不区分 B1/B2——14 个 B1 + 18 个 B2 混算画像，
   区间被拉宽、判别力稀释（B1 KDJ 中位 2.6 vs B2 29.9 混成一个区间）。
2. **权重手调**：相似度权重表 `{0.25,0.20,0.15,0.10,0.15,0.10,0.05}` 硬编码，不随数据变。
3. **后验没回灌**：verify（+5d/+10d/最大回撤/止损）仅在标注当天算一次，之后静态——
   中国稀土 8-26 标注时 +5d 未到窗口 → 永远 null（index 存 0.0 占位，P2-案例5）。
4. **无负样本**：案例库只有「完美买点」，没有「失败案例」——判别模型缺另一半信息。

## 2. 方案总览：四层递进

```
第 1 层  双轨画像（先修混合问题）     → 数据自校准的前提
第 2 层  负样本入库（补全判别信息）   → 判定开始"不像什么"
第 3 层  后验回灌（让数据好坏说话）   → 画像统计用后验过滤
第 4 层  权重可配置（替代手调）       → 样本够了就自动学
```

**核心原则**：案例库是「会生长的参照系」，不是「固定规则」——所有阈值/区间/权重
从案例库算出，案例增多 → 画像自动变精确，人不需要改任何参数。

## 3. 第 1 层：双轨画像

### 3.1 CaseConsensus 双轨化

`buildProfile(cases)` → `buildProfile(cases, type)`：
- `buildProfile(cases, "B1")` / `buildProfile(cases, "B2")` 两组独立 25-75 分位画像
- type 为 null/空 → 全量（向后兼容旧调用）
- 负样本（见 §4）不参与画像统计

### 3.2 CaseSimilarityEngine 类型过滤

- `topN(candidates, query, n, type)`：只与同类型案例比相似度
- type 为 null → 全量（向后兼容）
- 权重表从 `static final` 改为可注入（§6 可配置化）

### 3.3 match 响应升级（双轨）

```json
{
  "symbol": "000831",
  "type": "B1",
  "matches": [ ... ],
  "consensus": { ... },
  "tracks": {
    "b1": {"hits": 5, "total": 6, "similarity": 82.0, "profile": [...]},
    "b2": {"hits": 1, "total": 6, "similarity": 55.0, "profile": [...]}
  }
}
```

- `tracks.b1/b2`：双轨独立命中数 + 最高相似度
- `type`：命中多的一轨（B1/B2）；两轨都低 → "none"
- 排序语义：命中 6/6 > 5/6 > 4/6，天然可排序、可设推送阈值（≥5/6 才推）

## 4. 第 2 层：负样本入库

**方向已确认**（用户 2026-08-31 拍板：失败案例之后也会有的）。

### 4.1 数据模型

`CaseRecord.buyType` 扩展：
- `B1` / `B2`：正样本（完美买点）
- `FAILED`：负样本（失败案例——形态看似买点但走坏的案例）
- `unknown`：未标注类型（旧数据/占位）

失败原因放 `description`（如「破位不收回」「追高被套」），labels 可打标签。

### 4.2 判定语义

- 负样本**不参与** Consensus 画像统计、不参与相似度匹配（正样本参照系保持纯净）
- 负样本**单独成画像**：`CaseConsensus.buildProfile(cases, "FAILED")` 得「失败形态画像」
- match 响应加 `failedSimilarity`：当前形态与失败画像相似度高（如 >70%）→ 警示
  「⚠ 形态接近你的历史失败案例（相似度 78%），注意风险」——负样本的判别价值在这里

### 4.3 标注入口

现有 POST /cases 的 buyType 传 "FAILED" 即可（无需新端点）。web 标注弹窗加
「失败案例」选项（类型下拉：B1/B2/失败案例）。

## 5. 第 3 层：后验回灌

### 5.1 问题

verify 只在标注时算一次。中国稀土 8-26 标注 → +5d 未到窗口 → null，之后永远 null。

### 5.2 方案：每日回填任务

新增 `CaseVerifyBackfillScheduler`（@Scheduled，每日收盘后 15:35 跑，与 buy-points 扫描错峰）：
- 遍历案例库，对 `verify.plus5dReturnPct == null` 的案例重拉 K 线（buyDate 后 45 日历日窗口）
- 用 `CaseFeatureExtractor.verify()` 重算 → 非 null 则回填落盘（save 幂等）
- 日志：`案例后验回填 | userId=adai | 检查 32 | 回填 +5d 3 / +10d 5`
- 降级：K 线失败跳过该案例（不中断），次日再试

### 5.3 画像统计用后验过滤

Consensus 统计时：
- 正样本中 `stopLossHit=true` 或 `maxDrawdownAfterBuyPct < -5` 的 → 标记「失败倾向」，可降权/单独统计
- 未来：样本 ≥50 后，用 +10d 收益作标签学权重（§6）

### 5.4 index 摘要修复

P2-案例5：`plus5dReturnPct` null 存 0.0 的占位——回填后自然变真实值；未到窗口仍
显示「—」（前端判断 null），不再显示 0.0%。同时修复 `_index.json` 与文件类型不一致
（本次重标后 index 摘要过期——回填任务顺带重建 index）。

## 6. 第 4 层：权重可配置化

- `CaseSimilarityEngine.WEIGHTS` 从 `static final` 改为实例字段，构造注入
- 配置键：`adai.trading.case.sim-weights`（逗号分隔 7 个 double，默认
  `0.25,0.20,0.15,0.10,0.15,0.10,0.05`）
- Spring Bean 从配置读取；测试直接 new 时用默认值
- 远期：样本 ≥50 用 logistic 学权重（本方案只预留架构，不实现——32 样本会过拟合）

## 7. 消费场景矩阵（升级后）

| 场景 | 现状 | 升级后 |
|---|---|---|
| 选股 | scan-match 默认关 | 开：自选股附双轨命中 + 类型判定 |
| 买点判定 | match 单轨 Top5 | 双轨命中 N/M + 类型 + 失败相似警示 |
| 推送 | 15:10 跳过 case 类型 | 双轨命中 ≥5/6 才推 |
| 复盘反哺 | 无 | 清仓复盘对照画像（远期） |
| 知识沉淀 | aiInsight 1/32 | 案例语料反哺课程知识库 |

## 8. 实施清单（2026-08-31 当晚）

1. `CaseRecord`：buyType 支持 FAILED（负样本），注释更新
2. `CaseConsensus`：buildProfile(cases, type) 双轨 + 负样本排除
3. `CaseSimilarityEngine`：topN 类型过滤 + 权重可配置（构造注入）
4. `CaseVerifyBackfillScheduler`：每日后验回填 + index 重建
5. `TradingCaseAppService.match`：双轨响应 tracks + 失败相似警示
6. `WatchlistBuyPointService`：scan-match 用双轨（类型过滤）
7. Controller：match 响应透出（记录结构不变，JSON 自动带出）
8. web：匹配弹窗双轨卡（B1/B2 各自命中）+ 标注弹窗类型加「失败案例」
9. 测试：双轨/负样本/回填/权重配置
10. 文档：api-spec、feature-reference、REVIEW、change-log 登记
11. 部署：bootJar 重建 + 生产部署（用户明早验收）

## 9. 数据治理

- 异常值：昂利康 002940 距 60 日线 71.8%（新股窗口失真，P2-案例1）——统计标记不排除
- `_index.json` 与文件一致性：回填任务顺带重建
- verify null 占位：前端「—」而非 0.0%
