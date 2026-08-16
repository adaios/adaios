# 框架 + 插件——现状差距与迁移路径（Gap）

> **目的**：对账「框架 + 插件」总纲（RFC `20260816-framework-plus-plugin-model`）与现状，回答**「会不会打击式重构」**——结论：不会。大部分已就位，动刀点有限且明确，本文逐一列明，每步可验证。
> **配套**：交易 Agent 三阶段模型 RFC `20260816-trading-agent-plugin-model`；本文件是总纲的「对账清单」，不替代 RFC。

---

## 一、已就位（不动，证据）

| # | 能力 | 证据 | 判定 |
|:-:|:-----|:-----|:----:|
| S-1 | 插件门控全通道 | `Account.plugins` + `PluginService.hasPlugin` + `requireTradingPlugin(403)` + ContextEngine 过滤 + `/me/plugins` + 三端显隐 | ✅ |
| S-2 | 门控延伸到数据消费 | `MarketAlertService` 按插件过滤用户、`FeedAppService` 按插件构造 | ✅ |
| S-3 | trading-engine 引擎化 | git mv 改名 + definition 重写 + 构建/依赖分离（`build-engine.md`）| ✅ |
| S-4 | 行情数据源独立接口 | `MarketDataSource` 接口 + `TencentMarketDataSource` 实现（归 trading 插件域，2026-08-16 拨正）| ✅ |
| S-5 | 数据分层（用户提供 vs 可查询）| 止损位/买点/入场日期用户填，现价/K线查询注入（RFC `20260816-trading-data-model`）| ✅ |
| S-6 | 建议引擎（非记账）| `/trading/advice` 硬约束 R66-R95 + 止损硬判定，无执行按钮 | ✅ |
| S-7 | 大模型 = 基础设施 | `infrastructure/ai` 归属正确 | ✅ |
| S-8 | 无第三视角 | boundaries B1 原则级 + product P4 检查项 | ✅ |

## 二、缺口（动刀点，明确标注）

| # | 缺口 | 现状 | 目标 | 动刀范围 | 风险 |
|:-:|:-----|:-----|:-----|:---------|:----:|
| ~~G-1~~ | **行情服务跟插件走** ✅ 已拨正（2026-08-16）| ~~行情数据源是全局 bean~~ → 已 git mv 归 `domain/trading/market/`（`MarketDataSource`/`TencentMarketDataSource`），消费侧本就全门控（ContextEngine/`MarketAlertService`/`FeedAppService`）| 行情 = 数据载体插件，随交易插件启用而可用 | 后端：包移动 + import 更新（`git mv` 保留历史），537 测试全绿，零行为变化 | 低 ✅ |
| G-2 | **插件隔离补漏** | MarketAlert / Feed 已门控；检查剩余消费点（复盘/知识读取/搜索）是否全通道 | 数据消费 + 知识读取全通道按插件门控 | 后端：消费点逐个核对（对照 20260814 门控边界表）| 低 |
| G-3 | **交易插件 jar 化（能力抽离）** | 规则执行（matchRules / evaluateStopLoss / evaluatePosition）焊在 adai-core `TradingAdviceAppService` | 抽到 `engine/` 规则接口，adai-core 改调用（trading-os-engine RFC Phase 2）| 后端：接口抽取 + 测试保持 537 全绿 | 中 |
| G-4 | **知识层内聚** | `11-context/` 是 AI 入口但混在构建管道里；current.md 择时停更 | `knowledge/context/` + 中间层归档 + current.md 自动化（RFC Phase A）| trading-engine 内部目录 | 低 |
| G-5 | **Agent 独立形态** | 未实现（build-engine 已定义 Phase 3：Skill/Agent/MCP）| 一个内核多出口：阿呆插件 + 独立 Agent + MCP | 新形态输出，不动现有 | 低（新增为主）|
| G-6 | **多用户组合验证** | 插件机制在，但真实第二账号组合（有无行情 × 有无交易）未连调验证 | 不同插件组合用户能力确实不同 | 测试 + 连调 | 低 |

## 三、迁移路径（文档先行 → 小步动刀 → 最后收敛）

```
Step 0  文档定方向（本批）：总纲 RFC + 交易三阶段 RFC + 本 Gap 文档
   ↓
Step 1  零风险动刀 ✅（2026-08-16 完成 G-1）
        G-1 ✅ 行情归属拨正（git mv 归 domain/trading/market/，537 全绿）
        G-2 隔离补漏核对（对照门控边界表逐点检查）
   ↓
Step 2  低风险动刀（能力抽离，测试护航）
        G-3 engine/ 规则接口抽取（adai-core 改调用，537 测试保绿）
        G-4 knowledge/ 内聚 + current.md 自动化
   ↓
Step 3  新增形态（不动现状）
        G-5 Skill / Agent / MCP 输出（从 engine/ 出发）
   ↓
Step 4  最后收敛（打击式重构的「唯一可能点」，放最后且只做增量）
        VISION + product-architecture 加「形态总纲」段，不重写五层
```

**每步验证**：改后 `./gradlew test` 全绿 + `bash ai-engineering/guard-meta.sh` PASS + pre-commit 四层拦截自动把关。

## 四、验证方式（G-6 验收）

| 组合 | 期望行为 |
|:-----|:---------|
| 无插件用户 | 7 项基础能力；无交易页/无行情注入/无知识注入；URL 直连交易接口 403 |
| +行情（无交易）| 能查行情/问答有数据；无交易规则建议 |
| +交易（完整）| 数据 + 纪律建议（止损硬判定/仓位对照/买点），复盘闭环 |

## 五、结论

- **不是打击式重构**：8 项已就位，动刀点 6 项，其中 3 项是新增、2 项是低风险拨正、1 项是最后增量收敛。
- **动刀进度**：G-1 ✅ 已完成（2026-08-16，行情载体归 trading 插件域，零行为变化）；G-2 待做（零风险），G-3/G-4 次之（测试护航），G-5 新增不动现状，G-6 最后验证组合。
- **VISION 收敛已完成增量**：总纲提升为正式架构文档 `architecture/framework-plus-plugin-model.md`（active），VISION/product-architecture 加「形态总纲」理念条 + 形态视角说明，未重写五层架构（五层是体验视角，框架+插件是形态视角，并存）。
