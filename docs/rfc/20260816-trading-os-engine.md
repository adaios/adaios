---
title: trading-os 领域引擎化——从插件到独立可复用的交易引擎（实施时改名 trading-engine）
date: 2026-08-16
status: draft
---

> **改名记录（2026-08-16 实施）**：本 RFC 实施时 `os/trading-os/` 正式改名 `os/trading-engine/`（交易引擎独立身份）。正文已同步新名；历史 RFC 中 trading-os 为当时记录不改。

> **实施记录（2026-08-16，G-3/G-4/G-5 落地，见 `docs/reference/framework-plugin-gap.md`）**：
> - **Phase 2 能力抽离 ✅**：`engine/` 规则接口已实现——Java 侧 `services/adai-core/.../domain/trading/engine/`（`TradingRuleEngine` + `DefaultTradingRuleEngine`：evaluateStopLoss R66 / evaluatePosition R81 / matchRules），规格真相源 `os/trading-engine/engine/rules-api.md`；`TradingAdviceAppService` 已改调用，硬判定信号进 prompt。
> - **Phase A 知识层内聚 ✅**：`11-context/` → `knowledge/context/`（git mv 保留历史），Java/文档全引用同步；中间层 01-10 文档化归档（物理保留，防破坏 Step 1-5 流水线）；`09-scripts/update-current.sh` 半自动刷新 current.md（择时停更修复）。
> - **Phase 3 形态输出（样板就绪）**：`os/trading-engine/output/agent-skill.md`（Skill 包，Coze/Dify 可导入）+ `output/mcp-server.md`（MCP 资源/工具映射，FastMCP 指引）——实际部署待后续。
> - 验收：后端 551 测试全绿，guard-meta/align PASS。

# trading-engine 领域引擎化

> **方向 RFC**：把 trading-engine 从"阿呆项目内的交易知识插件"抬高为**独立的交易领域引擎（Domain Engine）**——知识 + 能力自洽，可作插件被阿呆集成，也可独立暴露为 Agent/Skill/服务供其他用户/项目使用。交易是这套"领域引擎"模式的**第一个样板**，跑通后可复制到其他领域（SLG 引擎等）。

---

## 一、背景（现状与问题，实测证据）

| # | 现状 | 问题 |
|:-:|:-----|:-----|
| A | trading-engine 415 文件 / 14 目录，01-04 每层 87 个（课程管道中间产物）| 90%+ 是"构建过程"中间层，AI 实际只消费 11-context/ 6 个文件——**知识仓库混入大量 AI 不该读的** |
| B | `definition/`（concepts/workflow）写的是**量化交易系统**（订单状态机/回测/夏普比），rules/strategy 是**纪律交易**（择时→选股→B1→止损→止盈→收队）| **定义与实际错位**——AI 读 definition 以为这是量化系统，读 rules 发现是纪律交易，前后矛盾 |
| C | 能力（建议引擎/复盘/规则判定）**焊死在 adai-core 代码里** | trading-engine 只有知识没有能力，**无法独立暴露**——只有阿呆能用 |
| D | current.md（择时状态 OAMV）2026-07-11 后停更 | R1-R20 择时规则无法执行 |
| E | 06-processed / 08-review / 10-prompts 空目录 | 构建工作流没走完，管道遗留 |

## 二、目标（用户确认的方向）

```
trading-engine = 交易领域引擎（Domain Engine）
  ├─ 知识层：领域定义 + 规则/策略/教训（真相源）
  ├─ 能力层：规则执行 / 建议生成 / 复盘（引擎内核）
  └─ 输出形态：可切换，一个内核多种出口
       ├─ ① 阿呆插件（集成，现状延续）
       ├─ ② 独立 Agent/Skill（coze 式：人设+技能+记忆）
       └─ ③ REST/MCP 服务（其他应用调）
```

**两个都要达成**：插件模式（阿呆依赖引擎）+ 独立模式（引擎单独可用）。

**核心原则**：构建与依赖分离——
- **构建**（trading-engine 内部）：课程 → 规则提炼 → 收敛（它是怎么把课变成规则的）
- **依赖**（外部消费）：读规则 → 生成建议/复盘（别人怎么用规则）

## 三、方案：三区重组 + 能力抽离

### 3.1 目录重组（按"领域引擎"三区）

```
os/trading-engine/
├── definition/        领域定义（概念/工作流/边界）——重写对齐纪律交易+引擎定位
├── knowledge/         知识层（AI 消费的真相源，唯一入口）
│   ├── context/         rules/strategy/mistakes/current/identity（11-context 迁入）
│   └── README.md        导航：AI 只读这层
├── engine/            能力层（规则执行/建议/复盘）——新建
│   ├── rules/           R1-R120 判定逻辑（接口定义）
│   ├── advice/          建议生成
│   └── review/          复盘生成
├── pipeline/          构建过程（课程处理）——01-10 收敛归档
│   ├── raw/             01-raw（原始课程）
│   ├── cleaned/         02-cleaned
│   ├── rules-src/       04-rules（规则提炼源）
│   └── archive/         空目录+历史归档
├── research/          12-research（研究）
├── inbox/             99-inbox（待消化）
└── scripts/           09-scripts（工具）
```

### 3.2 能力抽离（核心：让引擎可独立）

```
现在：建议引擎在 adai-core（TradingAdviceAppService 读 rules.md + LLM）
目标：规则引擎能力进 engine/（trading-engine 内），adai-core 改为调用方

engine/rules 接口设计（第一版）：
  matchRules(scene, position, market) → List<RuleHit>   # 场景→匹配规则
  evaluateStopLoss(position, price) → StopLossVerdict    # R66 止损判定
  evaluatePosition(position, market) → Advice[]          # 建议生成
```

**隐私边界**：引擎吃"传入的持仓/行情"产出建议，**不持有个人数据**——独立暴露时天然安全。

## 四、决策点（需用户拍板）

| # | 决策 | 选项 | 我的建议 |
|:-:|:-----|:-----|:---------|
| 1 | 知识层命名 | `knowledge/context/` vs 保留 `11-context/` | `knowledge/context/`（编号是管道遗留，语义化）|
| 2 | 中间层去留 | 归档到 `pipeline/archive/`（git mv 保历史）vs 原位保留 | 归档（AI 消费边界清晰，git 历史不丢）|
| 3 | 能力层语言 | Java（复用 adai-core 栈）vs Python/TS（引擎自洽）| **Java 先做**（复用现建议引擎逻辑，抽离成本低）|
| 4 | 独立形态优先级 | 先 REST 服务 vs 先 Skill/Agent 包 | 先 REST（最通用、可测），Skill/Agent 后做 |
| 5 | 迁移范围 | 一次性搬（pre-commit 拦断链）vs 分阶段 | 分阶段（definition → knowledge → engine）|

## 五、风险与对策

| 风险 | 对策 |
|:-----|:-----|
| 目录迁移断链（adai-core RULES_PATH、TradingKnowledgeSource、CLAUDE.md、文档）| pre-commit M4 正文路径检查拦截 + 分阶段迁移 |
| 能力抽离破坏建议引擎 | 先抽"规则查询"薄接口，阿呆继续用现有实现，逐步替换 |
| 独立部署成本 | 先"能力层在阿呆内跑通"（逻辑独立但同进程），再谈独立进程 |
| definition 重写偏离 | 以 rules/strategy 实际内容为基准（纪律交易），量化愿景降为扩展方向 |

## 六、落地路径（分阶段，每阶段可验收）

| 阶段 | 内容 | 验收 |
|:-----|:-----|:-----|
| **1. 定义对齐** | definition 重写（纪律交易+引擎定位）；knowledge 层导航 | definition/rules/strategy 语言一致；AI 消费边界明确 |
| **2. 知识层内聚** | 11-context → knowledge/context；中间层归档；current.md 自动化 | guard-meta/align PASS；adai-core 消费路径更新 |
| **3. 能力抽离** | engine/ 规则接口；建议/复盘抽到引擎；阿呆改调用 | 引擎逻辑独立可测；阿呆插件模式仍跑通 |
| **4. 独立形态** | REST 服务（第一版）；Skill/Agent 包（第二版）| 外部能调规则引擎；隐私边界验证 |

## 七、验收标准

1. `definition/` 描述"纪律交易系统 + 领域引擎"，与 rules/strategy 一致（无量化系统错位）
2. `knowledge/context/` 是 AI 唯一消费入口，01-10 中间层归档不参与
3. `engine/` 规则接口可独立调用（不吃阿呆内部状态）
4. 阿呆插件模式不变（建议引擎仍工作，533+ tests 全绿）
5. 独立形态（REST）能返回规则匹配/止损判定，且不持有个人数据
6. 方法论沉淀：领域引擎模式（规则+内容+输出分离）入 `ai-engineering/method/`

## 八、不做（本 RFC 范围外）

- 量化交易系统（订单执行/回测/风控）——definition 降为"远期扩展方向"，不在本 RFC 实现
- 选股能力（用户明确"现在还帮不了"）
- 多市场/币种（P2）
- 完整 Agent 人设/记忆框架（先出规则引擎，Agent 后做）
