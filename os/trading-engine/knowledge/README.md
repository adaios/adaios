# knowledge — 交易引擎知识层（AI 唯一入口）

> **G-4（2026-08-16）**：知识层内聚——AI 只消费本层，构建过程（01-10）不读。

## 结构

```
knowledge/
├── README.md       ← 本文件（入口导航）
└── context/        交付层（AI 上下文接口，原 11-context，git mv 保留历史）
    ├── identity.md    我是谁，我用什么方式交易
    ├── current.md     当前交易状态（市场阶段/仓位/聚焦点）
    ├── strategy.md    当前有效交易体系（少妇战法六步结构体）
    ├── rules.md       核心交易规则（R1-R120，可执行/可引用）
    └── mistakes.md    高频错误模式（快速识别与修正）
```

## AI 消费规则

1. **只读 `context/` 下 5 个文件 + 本 README**——这就是交易系统对 AI 的全部接口。
2. `01-raw/` ~ `10-prompts/`、`12-research/`、`99-inbox/` 是**构建过程**（课程→规则→系统），由 `CLAUDE.md` 工作流维护，AI 对话不读、不改（见 `CLAUDE.md` 目录权限表）。
3. **不新增知识**：本层只重组 `05-system + 04-rules + 03-glossary`，不包含新知识。
4. **版本同步**：系统收敛（Phase C）时重建本层；`current.md` 用 `09-scripts/update-current.sh` 半自动刷新。

## 消费方

| 消费方 | 方式 |
|:-------|:-----|
| adai-core `TradingKnowledgeSource` | 读 `context/` 5 文件注入 Context Engine（知识门控按 trading 插件）|
| adai-core `TradingAdviceAppService` | 读 `context/rules.md` + `strategy.md` 抽取 R66-R95 硬约束（建议引擎）|
| adai-core `TradingRuleEngine`（G-3）| 判定口径与 `rules.md` 一致（引擎实现，knowledge 为真相源）|
| 外部 Agent / MCP（G-5）| `context://` 资源挂载（未来）|

> 历史：本层原为 `11-context/`（编号来自构建管道），2026-08-16 语义化改名为 `knowledge/context/`。
