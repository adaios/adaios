---
title: 多 Agent 协作与通信模式（行业实践 + 本项目对照）
description: 从一次真实的「通道全通、前端整块未交付」案例出发，梳理多 agent 协作的通信介质 / 拓扑 / 协议三个切面与工程落地共识，提炼可借鉴项与本项目缺口
version: 1
created: 2026-10-06
updated: 2026-10-06
status: draft
lines: 89
depends-on: []
related:
  - ../../.agents/records/REVIEW.md
  - ../../.agents/rules/process/review-driven.md
tags: [research, agent, collaboration]
---

# 多 Agent 协作与通信模式

> **缘起**：2026-10-06 交易插件重做批——主仓库与 `feat/trading-plugin` 分支之间**三条通道全通**，
> 但「前端整块未交付」无声穿过深审 / 合并 / 部署，直到用户追问「web 端就这点变化？」才暴露。
>
> **标注约定**：行业部分尽量标「事实 / 记忆」；本文的归纳与主张标「判断」。
> 撰写当日网络受限（`raw.githubusercontent` 抓取失败），行业细节以搜索命中标题 + 既有知识为准，**未逐条核实**。

## 一、案例：三条通道全通，信息仍然丢失

| 通道 | 载体 | 传的是什么 | 当时表现 |
|:--|:--|:--|:--|
| **代码同步** | git merge / fast-forward | 产物与历史 | ✅ 通畅（一个 commit 即达） |
| **定向交接** | `LEDGER.md`「待交接给主会话」 | 分支 → 主会话的任务 | ⚠️ 通畅，但**没有「必报字段」** |
| **广播账本** | `REVIEW` / `change-log` / `status` | 真相源 | ⚠️ **只记「已发生的」，不记「没发生的」** |

**诊断（判断）**：通道不缺，缺的是**契约的完备性**。所有通道都在传「我做了什么」，
**没有一条要求传「我没做什么」**——而后者恰恰是那次丢的东西。
审查侧同理：深审判据是「改动对不对」，前端整块没动 ⇒ **不在审查视野内**（覆盖正确性 ≠ 覆盖完备性）。

## 二、行业实践的三个切面

### 2.1 按介质

| 模式 | 代表 | 特点 |
|:--|:--|:--|
| 消息传递 | AutoGen 对话循环 · OpenAI Agents SDK 的 handoff | 灵活，状态散在消息里 |
| 共享状态 | LangGraph 的 state graph · 经典 **blackboard 架构**（Hearsay-II 一脉） | 显式、可中断可恢复 |
| **文件即黑板** | `AGENTS.md` · memory 文件 · 本项目 `.agents/` | LLM 时代的实际主流：上下文有限、需持久化、需可审计 |

### 2.2 按拓扑（编排模式）

Supervisor / Manager-Worker（**本项目在用**）· Hierarchical · Swarm / handoff · **Debate / 对抗**（本项目有对抗官）· Blackboard。
参考整理：[collaboration-patterns](https://github.com/kaduoxzero/AI-Agent/blob/main/06-multi-agent/02-collaboration-patterns.md)（搜索命中，未读全文）。

### 2.3 按协议（跨系统互操作）

- **MCP**：agent ↔ 工具 / 资源。2024-11 Anthropic 起，已成事实标准。
- **A2A**：agent ↔ agent。Google 2025-04 发布；**2026-08 加入 Agentic AI Foundation、与 MCP 并列**
  （[官方公告](https://a2a-protocol.org/latest/blog/2026/08/27/a-new-chapter-for-a2a-joining-the-agentic-ai-foundation/)，搜索命中）。
  核心抽象三件套：**Agent Card（能力发现）+ Task（生命周期）+ Artifact（产物）**。
- **ACP**：IBM / BeeAI 路线（记忆，未核实）。

> **对本项目的直接启发（判断）**：A2A 把 agent 间通信拆成「发现 / 任务 / **产物**」，
> 而本项目的 `LEDGER.md` 只有「任务」层，**没有「产物契约」层**——交付物清单在行业协议里是一等公民，我们靠散文。

## 三、工程落地层：隔离 + 汇合已收敛

并行 agent 的共识做法 = **隔离工作区 + git 汇合 + 人做最终仲裁**（本项目 `.worktrees/trading` 即此模式）。
2026 年已长出专门编排器把 worktree 生命周期自动化，汇合点仍交给 git：
[swarmgit](https://pypi.org/project/swarmgit/0.1.0/)（multi-agent git worktree orchestrator）· [opencode-worktree](https://pkg.go.dev/github.com/danhenton/opencode-worktree)（搜索命中）。

## 四、可借鉴清单（对本项目）

1. **需求追踪矩阵（RTM）+ Definition of Done**——传统软工对「完备性」的成熟答案：需求有 ID，逐层追踪（需求→设计→实现→测试），门禁查「**未覆盖 = FAIL**」。本项目已有 `api-spec ↔ 代码` 的 align 守卫（= 实现 ↔ 契约的 RTM），**缺的正是「设计 ↔ 实现」这一层**。
2. **子 agent 输出要有固定格式**——行业共识是父 agent 必须能解析子 agent 的输出，故 objective + **output format** + boundaries 要显式给（[subagent best practices](https://raw.githubusercontent.com/rodrigorjsf/agent-engineering-toolkit/refs/heads/development/docs/analysis/analysis-research-subagent-best-practices.md)，搜索命中标题，正文未取到）。⇒ 交接清单要有 **schema 且含负向字段**，不能靠散文。
3. **负向信息优先**——「我没做什么 / 我做不到什么」最难传、最值钱；对抗审查之所以有效，正因专找「你没说的」。本项目对抗官**只审已存在的改动，不审缺失的交付**——加一条判据（「设计要求的，哪些没有对应产物？」）成本极低。
4. **成本纪律**——多 agent 的 token 开销远高于单 agent（Anthropic multi-agent 文章里常被引用的量级是 ≈ chat 的 15 倍，**记忆数字，未核实**）。本项目已有 `ai-guard-cost`，此点走在前面。

## 五、核心判断

**agent 团队的通信瓶颈不是带宽，也不是协议，而是契约里有没有「必报项」。**

三条通道全通、人也在场，整块前端仍然无声消失——因为**没有任何一条通道要求说「我没做」**。
行业讨论多在前者（更快的总线、更标准的协议），后者（把「必须说什么」写死成 schema）反而被低估。

一句话：**可靠性 = 通道 × 契约完备性**——做乘法前，先保证契约那几项不为零。

## 六、本文边界

- **未做**：未把结论落成机制（「设计 ↔ 实现」守卫 / 交接 schema 固化），也未改动 `review-driven.md`——
  那属另一件事，需人点头（见 `REVIEW P1-交易101` 建议列）。
- **未核实**：ACP 现状、15× 数字、两个编排器与两份整理的实际内容（网络受限）。
- 时效：`status: draft`；行业部分超 3 个月宜复核（与本目录「过期判断」一致）。
