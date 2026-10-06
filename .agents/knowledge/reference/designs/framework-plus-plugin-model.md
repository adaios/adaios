---
title: 框架+插件——AdaiOS 形态总纲（正式架构文档）
description: AdaiOS 的形态定义——一个框架 + 各种插件；框架装「你是谁」，插件装「你能做什么」；能力按用户叠加。五层架构是体验视角，本文是形态视角。
version: 1
created: 2026-08-16
updated: 2026-10-06
status: active
depends-on:
  - ../../../direction/VISION.md
  - product-architecture.md
related:
  - ../../../direction/rfc/20260816-framework-plus-plugin-model.md
  - ../../../direction/rfc/20260816-trading-agent-plugin-model.md
tags: [architecture, model, plugin, framework]
lines: 202
---

# 框架 + 插件：AdaiOS 形态总纲

> **形态定义**：阿呆的价值一句话——**一个框架 + 各种插件**。框架装「你是谁」（方法论/记忆/纪律/流程约定），插件装「你能做什么」（交易/行情/生活/项目/任意未来能力）。大模型只是框架内置的分析引擎，不是稀缺品；稀缺的是框架里的你，和插件里的你的积累。本文是 AdaiOS 的**形态总纲**（正式架构文档，status: active）——阿呆按框架 + 插件描述，不再按模块罗列。

---

## 〇、为什么定型（背景）

| # | 观察 | 结论 |
|:-:|:-----|:-----|
| A | 之前描述阿呆按「模块」罗列（trading / life / project + 五层架构），本质是**能力清单**——回答「阿呆会什么」，回答不了「阿呆是什么、怎么长、凭什么值钱」 | 缺一个**形态描述** |
| B | 三阶段交易 Agent 讨论（裸模型问答 → +行情插件 → +规则插件）时，发现「一个用户有没有行情插件、有没有交易系统插件，能力是不一样的」——这已不是功能开关，是**产品形态本身** | 能力按用户叠加 = 形态特征 |
| C | 用户原话：价值凸现——「一个框架 + 各种插件」 | 形态定型时刻已到 |

## 一、模型：框架 + 插件

```
┌─────────────────────────────────────────────────────────┐
│  框架（一个 · 慢变 · 确定性）                              │
│  装「你是谁」——人人一个，阿呆像不像你，靠这一层             │
│  ├─ 记忆 / 上下文 / 知识      Context Engine（Kernel）     │
│  ├─ 规则 / 纪律 / 流程约定    guard 家族、交易纪律、审核决策 │
│  ├─ 第一原则「无第三视角」     所有展示是「我和阿呆」的对话   │
│  └─ 大模型 = 内置分析引擎     AI 是基础设施（infrastructure）│
└─────────────────────────────────────────────────────────┘
                         ▲ 叠加
┌─────────────────────────────────────────────────────────┐
│  插件（N 个 · 快变 · 按需叠加）                            │
│  装「你能做什么」——每接一个，阿呆多会一件事                │
│  ├─ 交易系统插件   交易知识+决策 = 抽象层 jar（规则/纪律）  │
│  ├─ 行情插件       数据 = 具体载体 jar（行情/持仓/流水）    │
│  ├─ 生活 / 项目     现有 Domain OS                         │
│  └─ 任意未来插件    能力边界开放，jar 概念                  │
└─────────────────────────────────────────────────────────┘
```

**一句话分工**：框架回答「怎么组织」，插件回答「能做什么」。框架定结构，插件定能力，一拆各自演化。

## 二、为什么这个形态成立（历史验证）

| 案例 | 框架 | 插件 | 结果 |
|:-----|:-----|:-----|:-----|
| iOS | 系统 | App | 平台 |
| VS Code | 编辑器 | 扩展 | 生态 |
| 浏览器 | 内核 | 扩展 | 标准 |
| **阿呆** | **你的方法论/记忆/纪律** | **你的领域插件** | **个人 AI OS** |

**三个价值**：

1. **框架给了别人给不了的东西** —— 你的纪律、记忆、审核决策方式都在框架里，这是「我」的复制品，不是通用助手。
2. **插件给了别人抄不走的东西** —— 你的交易系统是你多年的积累，装进 jar 注入上下文，模型再强也复刻不了你的规则。
3. **组合是无限的** —— 框架一个、插件 N 个，阿呆从「一个应用」变成「一个平台」：不再想「阿呆还能做什么」，只想「下一个插件是什么」。

## 三、核心机制：能力按用户叠加

```
用户能力 = 是否接行情插件 × 是否接交易系统插件

 用户          行情插件   交易插件   能力
 ─────────────────────────────────────────────
 纯问答用户       ✗         ✗      通用问答（无行情、无规则）
 +行情用户        ✓         ✗      有数据、无规则（能查能算，不按纪律）
 完整交易用户      ✓         ✓      有数据、有规则（建议引擎完整形态）
```

**每个用户拿到一个定制组合**——这不是技术细节，这是产品形态：能力按需叠加，门控（`Account.plugins` + `PluginService` + ContextEngine 过滤）就是这套形态的落地机制。

## 四、与现有概念的关系（演进兼容，非推翻）

| 现有概念 | 在新模型中的位置 |
|:---------|:----------------|
| Kernel（Context/Memory/Knowledge）| **框架底座**——人人平等、常驻 |
| Domain OS（trading / learn；**project 已撤，2026-09-17 RFC 20260917**）| **插件**——受控开放、按用户启用；`life` 与待办/搜索/时间线/简报是 **Kernel builtin**（默认开，不作插件门控）|
| 五层产品架构 | **体验视角**（用户怎么用）；框架+插件是**形态视角**（阿呆是什么）——两者兼容，不互相替代 |
| trading-engine 引擎化 | **插件作为独立 jar 的雏形**——知识+能力自洽，可独立暴露 |
| 大模型/AI 集成 | **框架内置分析引擎**（infrastructure 层，非业务层）|
| 交易三阶段模型 | **插件叠加的具体实例**（见 `../../direction/rfc/20260816-trading-agent-plugin-model.md`）|

## 五、现状对照（详见 `./framework-plugin-gap.md`）

> 快照 2026-08-16（G-1~G-6 全部落地后刷新，FP-S1 修复——此前与 gap 矛盾）。

| 层面 | 现状 | 判定 |
|:-----|:-----|:----:|
| 插件门控（Account.plugins / PluginService / ContextEngine）| 已全通道 | ✅ 就位 |
| trading-engine 引擎化（知识/能力/形态分离）| 已改名 + definition 重写 | ✅ 就位 |
| 行情数据源独立接口（TencentMarketDataSource）| 已抽象 + 归 trading 插件域（G-1 拨正）| ✅ 就位 |
| 行情服务跟插件走 | 行情载体归 `domain/trading/market/`，注入/预警/Feed 全门控（G-1）| ✅ 完成 |
| 插件隔离补漏（数据消费/知识读取全通道）| 交易读/写端点全门控 + Brief 门控（G-2）| ✅ 完成 |
| 交易插件 jar 边界（engine/ 能力层抽离）| `domain/trading/engine/` 规则引擎 + 规格 `os/trading-engine/engine/rules-api.md`（G-3）| ✅ 完成 |
| Agent 独立形态（Skill/MCP/Coze）| 样板就绪：`os/trading-engine/output/`（agent-skill/mcp-server）（G-5）| ✅ 样板 |
| 多用户组合验证 | 无插件 403 / 行情门控 / 知识注入隔离测试全绿（G-6）| ✅ 完成 |

**结论**：不是打击式重构——8 项就位 + 6 个动刀点全部完成（2026-08-16，见 gap 文档 §二）。

## 六、边界与非目标

- **不推翻五层架构**：五层回答「用户怎么用」，框架+插件回答「阿呆是什么」，两者并存。
- **「框架」不指技术框架**：不是 Spring/Flutter，是阿呆的**确定性层**（组织、约束、记忆、流程）。
- **插件动态加载 / 应用市场 = 过度设计**，不做（沿用 20260814 RFC 结论）。
- **插件所有权**：交易系统插件 = adai 私有 jar，不开放给别人（沿用 20260814 D2）。
- **建议是输出不是执行按钮**：交易插件输出建议（最多建议减仓/清仓），人做决策（沿用交易交互定位）。

## 七、落地顺序（迁移见 gap 文档 §三）

| 步 | 动作 | 说明 |
|:-:|:-----|:-----|
| 1 | 本总纲（形态定义）| 定形态方向 |
| 2 | 交易 Agent 三阶段模型（`../../direction/rfc/20260816-trading-agent-plugin-model.md`）| 总纲在交易的实例化 |
| 3 | 差距与迁移（`./framework-plugin-gap.md`）| 对账现状，列动刀点 |
| 4 | VISION / product-architecture 收敛 | 增量加「形态总纲」，不重写 |

> **决策记录**：本总纲最初以 RFC `20260816-framework-plus-plugin-model`（draft）提出，2026-08-16 用户确认后提升为正式架构文档（本文件）。RFC 保留为决策历史。

---

## 附录：现状对账与迁移路径（G-1~G-6，2026-08-16 全部完成）

> **本节原为独立文档 `framework-plugin-gap.md`**（2026-10-04 并入本总纲）——回答「**会不会打击式重构**」：不会。8 项已就位 + 6 个动刀点全部完成，每步可验证。

## 框架 + 插件——现状差距与迁移路径（Gap）

> **目的**：对账「框架 + 插件」总纲（正式架构文档 `../architecture/framework-plus-plugin-model.md`，FP-P2g 修正指向）与现状，回答**「会不会打击式重构」**——结论：不会。大部分已就位，动刀点有限且明确，本文逐一列明，每步可验证。
> **配套**：交易 Agent 三阶段模型 RFC `20260816-trading-agent-plugin-model`；本文件是总纲的「对账清单」，不替代 RFC。
> **状态（2026-08-16）**：G-1~G-6 **全部完成** ✅（详见 §二/§三）。

---

### 一、已就位（不动，证据）

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

### 二、缺口与处置（G-1~G-6 全部完成，2026-08-16）

| # | 缺口 | 处置 | 落地证据 | 风险 |
|:-:|:-----|:-----|:---------|:----:|
| G-1 | **行情服务跟插件走** | ✅ 已拨正 | `kernel/market` → `domain/trading/market/`（git mv 保留历史），消费侧本就全门控 | 低 ✅ |
| G-2 | **插件隔离补漏** | ✅ 已补漏 | 交易**读端点**（positions/portfolio/trades/review/reviews）补 `requireTradingPlugin(403)`（has-activity 保留产品路径）；`BriefAppService` 交易活动信号只注入 trading 插件用户 | 低 ✅ |
| G-3 | **交易插件 jar 化（能力抽离）** | ✅ 已抽离 | 新建 `domain/trading/engine/`：`TradingRuleEngine` 接口（evaluateStopLoss R66 / evaluatePosition R81 / matchRules）+ `DefaultTradingRuleEngine`；`TradingAdviceAppService` 改调用，硬判定信号进 prompt；规格真相源 `os/trading-engine/engine/rules-api.md` | 中 ✅ |
| G-4 | **知识层内聚** | ✅ 已内聚 | `11-context/` → `knowledge/context/`（git mv）；中间层 01-10 **文档化归档**（不物理搬移——物理搬移会破坏 Step 1-5 流水线，违反 os/ 独立性）；`os/trading-engine/09-scripts/update-current.sh` 半自动刷新 current.md（择时停更修复）；Java/文档全引用同步 | 低 ✅ |
| G-5 | **Agent 独立形态** | ✅ 样板就绪 | `os/trading-engine/engine/`（能力层规格）+ `output/agent-skill.md`（Skill 包，Coze/Dify 可导入）+ `output/mcp-server.md`（MCP 资源/工具映射，FastMCP 指引）——新增不动现有 | 低 ✅ |
| G-6 | **多用户组合验证** | ✅ 测试就绪 | 补：无插件用户交易读/写端点 403（5 读+4 写）、`marketContext_gatedByTradingPlugin`（行情注入按插件门控）、既有知识注入/D5/Feed 门控测试全绿 | 低 ✅ |

### 三、执行记录（2026-08-16，文档先行 → 小步动刀 → 收敛）

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

**每步验证**：`./gradlew test` 全绿 + `ai-guard-meta.sh` PASS + pre-commit 四层拦截自动把关（本批全部通过）。

### 四、验证结果（G-6 组合矩阵，测试化）

| 组合 | 期望行为 | 测试 |
|:-----|:---------|:-----|
| 无插件用户 | 7 项基础能力；无交易页/无行情注入/无知识注入；URL 直连交易接口 403（读+写）| `PluginIsolationTest`（知识/D5）+ `TradingControllerTest`（读端点 403×5、写端点 403×4）+ `marketContext_gatedByTradingPlugin` |
| +交易（完整，adai）| 数据 + 纪律建议（止损硬判定/仓位对照/买点），复盘闭环，行情注入 | `PluginIsolationTest` + `TradingAdviceAppServiceTest`（R66/R81 硬判定）|
| +行情（无交易）| **当前不成立**：行情载体跟 trading 插件绑定，无独立 market 插件 | 阶段二用户待行情插件独立化时验证（三阶段模型，属 G-5 后续，不过度设计）|

### 五、结论

- **不是打击式重构**：8 项已就位 + 6 个动刀点全部完成（2026-08-16 一天内），测试全绿、门禁全过。
- **动刀原则落地**：G-1/G-2 零风险先做 → G-3/G-4 测试护航 → G-5 新增不动现状 → G-6 验证组合。
- **VISION 收敛**：总纲为正式架构文档（active），VISION/product-architecture 增量加「形态总纲」，五层架构一行未动（体验视角 vs 形态视角并存）。
- **遗留（非缺口，属后续演进）**：行情插件独立化（阶段二用户）、MCP/Skill 实际部署（样板已就绪）、真实第二账号连调（机制已测试）。
