# 形态 D：MCP Server（样板）

> **G-5（2026-08-16）**：build-engine.md 形态 D——交易引擎暴露为 MCP server，AI 客户端（Claude/DSH/其他）直接调。**不持有个人数据**：吃传入的持仓/行情，产出建议/判定。

## 资源映射（knowledge 只读）

| MCP 资源 | 文件 | 用途 |
|:---------|:-----|:-----|
| `context://identity` | `knowledge/context/identity.md` | 我是谁，我用什么方式交易 |
| `context://current` | `knowledge/context/current.md` | 当前交易状态（择时/持仓/聚焦）|
| `context://strategy` | `knowledge/context/strategy.md` | 当前有效交易体系 |
| `context://rules` | `knowledge/context/rules.md` | 核心交易规则 R1-R120 |
| `context://mistakes` | `knowledge/context/mistakes.md` | 高频错误模式 |

## 工具（引擎 rules-api.md 实现）

| MCP tool | 规格 | 说明 |
|:---------|:-----|:-----|
| `evaluate_stop_loss` | `rules-api.md` §2 | 现价 vs 止损位 → BREACHED（R66）|
| `evaluate_position` | `rules-api.md` §3 | 持仓占比 → OVER_WEIGHT（R81）|
| `match_rules` | `rules-api.md` §1 | 规则解析/区间抽取 |
| `generate_advice`（可选）| 建议引擎 | 持仓×行情 → 逐票建议（LLM 结构化，需配置模型）|

## 技术栈建议

```
Python + FastMCP（推荐：行情生态强，FastMCP 声明式最简）
  pip install fastmcp
  server = FastMCP("adai-trading-engine")
  @server.resource("context://rules")
  def rules(): return Path("knowledge/context/rules.md").read_text()
  @server.tool()
  def evaluate_stop_loss(current_price: float, stop_loss_price: float) -> dict: ...
```

或 TypeScript SDK（若团队栈偏 JS）。

## 隐私与安全

1. **无状态**：不存储持仓/行情，每次调用传入。
2. **只读知识**：`context://` 资源只读 `knowledge/context/`，不暴露 `01-10` 构建过程与 `07-manual`。
3. **判定不执行**：工具返回 verdict，交易动作由调用方（人）决策。
4. **行情接入**：工具内接行情源（websearch/券商 api）——行情是数据载体插件，跟着 Agent 走（三阶段模型阶段二/三）。
