---
title: 技能使用指南（Skills Usage Guide）
description: 人看的技能使用说明——AdaiOS 技能体系是什么、11 个技能各何时用、怎么触发、怎么维护
version: 1
created: 2026-08-20
updated: 2026-08-20
status: active
lines: 100
depends-on: []
related:
  - ../../ai-engineering/assets/skills-spec.md
  - ../README.md
tags: [guide, skills]
---

# 技能使用指南

> 给**人**看的说明。AI 执行细节见 `ai-engineering/assets/skills-spec.md`（规范）和各技能文件本身。

## 一、技能是什么（一句话）

技能 = 把 AdaiOS 的**高频工作流程**（建 API、建 Domain、收尾、审查）固化成标准文件。AI 接到对应任务时自动加载并**按流程执行**——不再每次临场发挥、不再靠"记不记得"。

## 二、技能清单（12 个）

### 建设技能（进攻侧：你做东西时，AI 按流程做）

| 技能 | 什么时候用（人话）| 你会得到 |
|:--|:--|:--|
| `new-api` | 说"加个接口 / 改个端点" | 代码 + 测试 + api-spec/status 同步 + 门禁全过，不会漏同步 |
| `new-domain` | 想加一个新功能域 / 大能力 | RFC（含六维需求）+ 插件模型 + 数据流设计 + 落地闭环 |
| `ship` | 说"收尾 / ship" | 五件套核对 + 契约同步 + 登记 + 门禁 + 规范提交 |

### 审查技能（防守侧：做完东西后，AI 帮你检查）

| 技能 | 什么时候用 | 检查什么 |
|:--|:--|:--|
| `product-arch` | 大改动后 | 功能归属、五层架构、路线对齐、第一原则 |
| `ux-reviewer` | 交互/流程改动后 | 操作路径、异常流、反馈完整性、跨端一致 |
| `ui-reviewer` | 页面改动后 | 布局触达、视觉层级、三端一致、深色模式 |
| `backend-reviewer` | 后端改动后 | 数据安全（P0）、分层、健壮性、测试覆盖 |
| `frontend-reviewer` | 前端改动后 | 状态管理、生命周期、DTO 契约、跨端对拍 |
| `docs-reviewer` | 文档/契约改动后 | api-spec 一致性、断链、数字漂移、frontmatter |
| `knowledge-reviewer` | 知识/数据改动后 | os/ 与 data/ 健康、隐私面、格式契约 |
| `context-reviewer` | 上下文/AI 模板改动后 | Purpose/Trigger/Action/Consistency 四问 |
| `adversarial-reviewer` | 任何改动后（deep 默认附加）| 对抗找茬：哪里会炸 / 用户哪里会骂 / 边界哪里漏 |

## 三、怎么用（三种方式）

### 方式一：直接说需求，AI 自动匹配（推荐）

- "帮我加一个查询持仓的接口" → AI 自动加载 `new-api` 技能
- "这个功能做完了，收尾吧" → AI 自动走 `ship` 流程
- "帮我看看这次后端改动" → AI 自动派 `backend-reviewer`

### 方式二：显式点名

- "用 new-api 技能做"
- "加载 ship 技能"
- "派 docs-reviewer 审查这个改动"

### 方式三：走标准流程

- `/review` → 按改动自动派对应审查官
- `/ship` → 收尾闭环（就是 ship 技能）

## 四、对话示例

你说：**"帮我加一个 `GET /api/v1/trading/positions` 接口"**

AI 加载 `new-api` 技能后会按 8 步走：
1. 确认归属 trading 域 → 2. 写 Controller/Service（分层合规）→ 3. 检查插件门控 → 4. 配套测试 → 5. 同步 api-spec（升版+变更记录）→ 6. 更新 status.md 端点数 → 7. 跑 guard-align / guard-meta → 8. 登记 feature-reference

——不会出现"接口能用了但文档没同步"（P2-交易18 的教训）。

## 五、技能怎么维护

- **新增技能的条件**：同一件工作**重复 ≥2 次** → 值得封装（例：你发现"每周都要做 X"）
- **怎么写**：按 `ai-engineering/assets/skills-spec.md`（五段结构 + name 字段），建设类放 `ai-engineering/skills/`、审查类放 `ai-engineering/roles/`，登记 `ai-engineering/_index.md`
- **技能会长大**：每次用技能踩到新坑，往该技能「约束与规则」补一行——技能质量 = 你项目真实经验的沉淀
- **两边都别偏废**：建设技能保证产出完整（上游），审查技能检查遗漏（下游），互补

## 六、工具侧配置（一次性）

- 技能文件在项目内（`ai-engineering/roles/` + `ai-engineering/skills/`），随 Git 走，**换机/换工具零迁移**
- 各 AI 工具（DSH / Qoder 等）在自己的设置里把技能目录指向 `ai-engineering/`，即可识别全部技能
- 项目内不用改任何东西（AGENTS.md「工具接入」原则）

## 七、FAQ

**Q：技能会自动执行吗？**
A：触发后 AI 按步骤执行，但代码/判断仍由 AI 完成——技能是"流程说明书 + 检查门"，不是一键脚本。

**Q：技能质量不行怎么办？**
A：看它有没有把你项目的真实坑写进去。缺经验 → 在「约束与规则/参考资料」补真实教训。

**Q：和原来的 review 流程冲突吗？**
A：不冲突。审查官技能就是原 review 角色换成了标准格式，行为不变。

**Q：这个指南和 skills-spec 什么关系？**
A：本指南给你看（怎么用）；`skills-spec.md` 给 AI/维护者看（怎么写、规范是什么）。
