# 11-context / 交易系统上下文接口层

## 定位

`11-context/` 不是新的知识目录，而是"交易系统对 AI / 外部系统暴露的认知接口层"。

```
01-raw  ~  05-system  →  交易系统如何形成（内部知识库）
08-review              →  交易系统如何学习（运行日志）
11-context             →  交易系统如何被理解（对外投射）
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

1. **不新增知识**：11-context/ 不包含 01-raw ~ 05-system 之外的新交易知识
2. **只做重组**：内容来自 05-system/trading-system.md + 03-glossary/current/ + 04-rules/
3. **版本同步**：每次系统收敛后（第二层 Phase C），同步更新 11-context/
4. **current.md 按需刷新**：配合 08-review 和当前市场判断手动更新当前状态。收敛时同步更新其术语引用部分（持仓和市场状态不动）

## 来源映射

```
identity.md    ← 05-system/trading-system.md 总纲 + 底层认知
current.md     ← 05-system outline 当前市场判断 + 08-review 复盘
strategy.md    ← 05-system/trading-system.md 六步结构体
rules.md       ← 04-rules/ + 05-system 各层的规则声明
mistakes.md    ← 05-system A5 常见错误 + 各课认知警告
```
