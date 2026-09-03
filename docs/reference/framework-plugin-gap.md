---
title: 框架+插件——现状差距与迁移路径（Gap）
description: 对账「框架+插件」总纲与现状的对账清单——G-1~G-6 动刀点、落地证据、执行记录、验证结果；回答"会不会打击式重构"
version: 1
created: 2026-08-16
updated: 2026-08-16
status: active
lines: 81
depends-on:
  - ../architecture/framework-plus-plugin-model.md
related:
  - ../rfc/20260816-trading-agent-plugin-model.md
  - ../review/REVIEW.md
tags: [reference, plugin, framework, gap]
---

# 框架 + 插件——现状差距与迁移路径（Gap）

> **目的**：对账「框架 + 插件」总纲（正式架构文档 `../architecture/framework-plus-plugin-model.md`，FP-P2g 修正指向）与现状，回答**「会不会打击式重构」**——结论：不会。大部分已就位，动刀点有限且明确，本文逐一列明，每步可验证。
> **配套**：交易 Agent 三阶段模型 RFC `20260816-trading-agent-plugin-model`；本文件是总纲的「对账清单」，不替代 RFC。
> **状态（2026-08-16）**：G-1~G-6 **全部完成** ✅（详见 §二/§三）。

---

## 一、已就位（不动，证据）

| # | 能力 | 证据 | 判定 |
|:-:|:-----|:-----|:----:|
| S-1 | 插件门控全通道 | `Account.plugins` + `PluginService.hasPlugin` + `requireTradingPlugin(403)` + ContextEngine 过滤 + `/me/plugins` + 三端显隐 | ✅ |
| S-2 | 门控延伸到数据消费 | `MarketAlertService` 按插件过滤用户、`FeedAppService` 按插件构造 | ✅ |
| S-3 | trading-engine 引擎化 | git mv 改名 + definition 重写 + 构建/依赖分离（`build-engine.md`）| ✅ |
| S-4 | 行情数据源独立接口 | `MarketDataSource` 接口 + `TencentMarketDataSource` 实现（归 trading 插件域，G-1 拨正）| ✅ |
| S-5 | 数据分层（用户提供 vs 可查询）| 止损位/买点/入场日期用户填，现价/K线查询注入（RFC `20260816-trading-data-model`）| ✅ |
| S-6 | 建议引擎机制（已建能力，非模块定位——定位见 RFC 20260902 交易记忆）| `/trading/advice` 硬约束 R66-R95 + 止损硬判定，无执行按钮 | ✅ |
| S-7 | 大模型 = 基础设施 | `infrastructure/ai` 归属正确 | ✅ |
| S-8 | 无第三视角 | boundaries B1 原则级 + product P4 检查项 | ✅ |

## 二、缺口与处置（G-1~G-6 全部完成，2026-08-16）

| # | 缺口 | 处置 | 落地证据 | 风险 |
|:-:|:-----|:-----|:---------|:----:|
| G-1 | **行情服务跟插件走** | ✅ 已拨正 | `kernel/market` → `domain/trading/market/`（git mv 保留历史），消费侧本就全门控 | 低 ✅ |
| G-2 | **插件隔离补漏** | ✅ 已补漏 | 交易**读端点**（positions/portfolio/trades/review/reviews）补 `requireTradingPlugin(403)`（has-activity 保留产品路径）；`BriefAppService` 交易活动信号只注入 trading 插件用户 | 低 ✅ |
| G-3 | **交易插件 jar 化（能力抽离）** | ✅ 已抽离 | 新建 `domain/trading/engine/`：`TradingRuleEngine` 接口（evaluateStopLoss R66 / evaluatePosition R81 / matchRules）+ `DefaultTradingRuleEngine`；`TradingAdviceAppService` 改调用，硬判定信号进 prompt；规格真相源 `os/trading-engine/engine/rules-api.md` | 中 ✅ |
| G-4 | **知识层内聚** | ✅ 已内聚 | `11-context/` → `knowledge/context/`（git mv）；中间层 01-10 **文档化归档**（不物理搬移——物理搬移会破坏 Step 1-5 流水线，违反 os/ 独立性）；`09-scripts/update-current.sh` 半自动刷新 current.md（择时停更修复）；Java/文档全引用同步 | 低 ✅ |
| G-5 | **Agent 独立形态** | ✅ 样板就绪 | `os/trading-engine/engine/`（能力层规格）+ `output/agent-skill.md`（Skill 包，Coze/Dify 可导入）+ `output/mcp-server.md`（MCP 资源/工具映射，FastMCP 指引）——新增不动现有 | 低 ✅ |
| G-6 | **多用户组合验证** | ✅ 测试就绪 | 补：无插件用户交易读/写端点 403（5 读+4 写）、`marketContext_gatedByTradingPlugin`（行情注入按插件门控）、既有知识注入/D5/Feed 门控测试全绿 | 低 ✅ |

## 三、执行记录（2026-08-16，文档先行 → 小步动刀 → 收敛）

```
Step 0  文档定方向：总纲提升为正式架构文档 + 交易三阶段 RFC + 本 Gap 文档
   ↓
Step 1  G-1 ✅ 行情归属拨正（git mv 归 domain/trading/market/）
        G-2 ✅ 读端点门控 + Brief 门控（+5 测试）
   ↓
Step 2  G-3 ✅ engine/ 规则接口抽取（+6 引擎测试，537 → 全绿）
        G-4 ✅ knowledge/context 内聚 + current.md 自动化（全引用同步）
   ↓
Step 3  G-5 ✅ 形态样板：engine 规格 + Skill 包 + MCP 映射（新增，不动现状）
   ↓
Step 4  G-6 ✅ 组合验证测试（行情门控 + 读端点 403，全绿）
        收敛：VISION/product-architecture 增量加「形态总纲」（不重写五层）
```

**每步验证**：`./gradlew test` 全绿 + `guard-meta.sh` PASS + pre-commit 四层拦截自动把关（本批全部通过）。

## 四、验证结果（G-6 组合矩阵，测试化）

| 组合 | 期望行为 | 测试 |
|:-----|:---------|:-----|
| 无插件用户 | 7 项基础能力；无交易页/无行情注入/无知识注入；URL 直连交易接口 403（读+写）| `PluginIsolationTest`（知识/D5）+ `TradingControllerTest`（读端点 403×5、写端点 403×4）+ `marketContext_gatedByTradingPlugin` |
| +交易（完整，adai）| 数据 + 纪律建议（止损硬判定/仓位对照/买点），复盘闭环，行情注入 | `PluginIsolationTest` + `TradingAdviceAppServiceTest`（R66/R81 硬判定）|
| +行情（无交易）| **当前不成立**：行情载体跟 trading 插件绑定，无独立 market 插件 | 阶段二用户待行情插件独立化时验证（三阶段模型，属 G-5 后续，不过度设计）|

## 五、结论

- **不是打击式重构**：8 项已就位 + 6 个动刀点全部完成（2026-08-16 一天内），测试全绿、门禁全过。
- **动刀原则落地**：G-1/G-2 零风险先做 → G-3/G-4 测试护航 → G-5 新增不动现状 → G-6 验证组合。
- **VISION 收敛**：总纲为正式架构文档（active），VISION/product-architecture 增量加「形态总纲」，五层架构一行未动（体验视角 vs 形态视角并存）。
- **遗留（非缺口，属后续演进）**：行情插件独立化（阶段二用户）、MCP/Skill 实际部署（样板已就绪）、真实第二账号连调（机制已测试）。
