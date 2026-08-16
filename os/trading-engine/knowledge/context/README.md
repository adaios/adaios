# knowledge/context — 交易系统上下文接口层（交付层）

> **G-4（2026-08-16）**：本目录由 `11-context/` 改名而来（git mv 保留历史），语义化命名——`knowledge/` = 知识层（AI 唯一入口），`context/` = 上下文接口。

## 定位

`knowledge/context/` 不是新的知识目录，而是"交易系统对 AI / 外部系统暴露的认知接口层"。

```
01-raw  ~  05-system   →  交易系统如何形成（内部知识库 · 构建过程）
08-review              →  交易系统如何学习（运行日志）
knowledge/context      →  交易系统如何被理解（对外投射 · 交付层）
```

类比 Linux `/proc`：不存新数据，只暴露内核当前状态。

## 文件清单

| 文件 | 类比 /proc | 内容 |
|:----|:-----------|:-----|
| `identity.md` | `/proc/version` + `/proc/cpuinfo` | 我是谁，我用什么方式交易 |
| `current.md` | `/proc/meminfo` + `/proc/uptime` | 当前交易状态（市场阶段/仓位/聚焦点） |
| `strategy.md` | `/proc/sys/` | 当前有效交易体系（少妇战法六步结构体） |
| `rules.md` | 系统调用表 | 核心交易规则（可执行/可引用） |
| `mistakes.md` | 异常向量表 | 高频错误模式（快速识别与修正） |

## 消费方式

### 1. 单次 AI 会话注入

在提示词中包含对应文件内容：

```markdown
请根据以下交易系统来回答：
====== identity.md ======
...
====== current.md ======
...
====== rules.md ======
...
```

### 2. MCP 挂载（未来）

每个文件作为 MCP 资源，按路径引用：

```
context://identity
context://current
context://rules
```

### 3. 作为 RAG 知识片

每个文件可直接向量化，供外部系统检索。

## 维护原则

1. **不新增知识**：knowledge/context/ 不包含 01-raw ~ 05-system 之外的新交易知识
2. **只做重组**：内容来自 05-system/trading-system.md + 03-glossary/current/ + 04-rules/
3. **版本同步**：每次系统收敛后（第二层 Phase C），同步更新 knowledge/context/
4. **current.md 半自动刷新**：`09-scripts/update-current.sh` 可自动重组规则/信号表述部分（从 05-system + 08-review），持仓和市场状态由你按 `08-review` 手动确认后写入（G-4）

## 构建过程归档（G-4 文档化）

`01-raw/` ~ `10-prompts/`（含 `12-research/`、`99-inbox/`）是**构建过程**（课程 → 规则 → 系统），由 `CLAUDE.md` 工作流维护，**AI 对话不读**——AI 只消费本目录。物理结构保持不变（流水线单向依赖，编号从低到高），归属 `pipeline/` 语义（见 `pipeline/build-course.md`）。

## 来源映射

```
identity.md    ← 05-system/trading-system.md 总纲 + 底层认知
current.md     ← 05-system outline 当前市场判断 + 08-review 复盘（update-current.sh 半自动）
strategy.md    ← 05-system/trading-system.md 六步结构体
rules.md       ← 04-rules/ + 05-system 各层的规则声明
mistakes.md    ← 05-system A5 常见错误 + 各课认知警告
```
