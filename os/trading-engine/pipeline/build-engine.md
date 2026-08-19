# Trading Engine — Build 产出流程（知识 → 输出形态）

> **定位**：这是"点 build 生成成熟输出口"的流程——把 trading-engine 的**知识层**（knowledge/context）编译成各种**可消费形态**。构建流程（课程→知识）见 `build-course.md`（AGENTS.md 维护），本文件是**引擎对外输出的 build**。

## 核心：一个内核，多种出口

```
                    ┌──► 形态 A：插件（被 AdaiOS 集成）——现状
knowledge/context/ ──┼──► 形态 B：独立 Agent/Skill（coze 式智能体）
  (rules/strategy/   ├──► 形态 C：REST 服务（任何应用调）
   mistakes/current) └──► 形态 D：MCP server（AI 工具直接调）
       │
       ▼
   engine/（能力层：规则执行/建议/复盘）
```

## Build 流程（三阶段）

### Phase 1：知识就绪检查（build 前置门禁）

```
[ ] knowledge/context/ 完整：rules + strategy + mistakes + current + identity
[ ] current.md 最新（择时状态未过期 >30 天 → 阻断）
[ ] definition/ 与 knowledge 一致（无量化错位）
[ ] 规则可解析（R1-R120 编号连续，无断号）
```

**门禁脚本**（可加）：`pipeline/check-knowledge.sh` — 检查上面 4 项，FAIL 阻断 build。

### Phase 2：能力编译（知识 → 引擎内核）

```
知识层 → engine/ 能力实现：
  - rules/：R1-R120 解析 + 匹配逻辑
  - advice/：持仓×规则 → 建议（suggestion/reason/rules）
  - review/：交易记录 → 复盘（计划 vs 实际）
```

**编译产物**：`engine/` 是**语言无关的规则执行内核**（接口定义），宿主语言（Java/Python）实现。

### Phase 3：形态输出（内核 → 出口）

| 形态 | 产出 | 消费者 | 状态 |
|:-----|:-----|:-------|:----:|
| **A 插件** | AdaiOS 集成（建议引擎已在 adai-core）| 阿呆用户 | ✅ 现状 |
| **B Skill/Agent** | 技能包：人设 + 规则知识 + 建议流程 | 其他 AI 工具 | 📋 |
| **C REST** | HTTP API：/advice /rules /stoploss | 任何应用 | 📋 |
| **D MCP** | MCP server：resources(规则) + tools(建议) | AI 客户端 | 📋 |

## 形态 A：插件（现状，第一消费者）

```
AdaiOS ──► trading-engine 插件模式
  ├─ adai-core 读 knowledge/context/rules.md（建议引擎已接入 R66-R95）
  ├─ 用户记录交易（带止损/买点）→ 引擎有数据可判
  └─ 建议引擎输出：现价<止损 → clear（R66）
```

## 形态 B/C/D：独立输出（build 目标）

```
点 build（跑本流程）→ 生成指定形态：
  C REST：暴露 POST /advice（吃持仓+行情 → 建议），不持有个人数据
  B Skill：打包 rules/strategy + 建议流程 → 其他 AI 可加载
  D MCP：resources(规则) + tools(生成建议/止损判定)
```

**隐私边界**：所有形态**不持有个人数据**——吃传入的持仓/行情，产出建议。独立暴露无隐私风险。

## Build 验收

1. Phase 1 门禁全过（知识就绪）
2. 形态 C：POST /advice 返回建议（无持仓数据泄漏）
3. 形态 B：Skill 包可被外部 AI 加载执行
4. 形态 A 不回归：阿呆建议引擎仍工作（533+ tests 全绿）

## 迭代

每次知识层收敛（Phase C 重建 knowledge/context）后，**重新 build 输出形态**——保证出口与知识同步。
