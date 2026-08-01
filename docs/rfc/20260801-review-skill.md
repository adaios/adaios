---
title: 审核流程 Skill 化（Review Skill）
date: 2026-08-01
status: draft
---

## 一、背景

2026-08-01 第一次项目审查暴露 32 项问题（2 P0 数据丢失 + 1 战略缺口 + 其余）。但这次审查是**手工一次性派 3 路 Agent**，存在两个不可复用问题：

1. **方法没有沉淀**：10 个审查维度埋在 `docs/review/20260801-project-review.md` 里，下次得重新读报告、重新设计
2. **状态不滚动**：32 项跟踪表是死文档，已修复 / 未修复无法自动衔接下一次审查

**目标**：把"审查能力"变成项目资产——一个 `/review` 命令 + 4 个角色 + 可积累的检查点清单，每次审核自动带上一次状态。

## 二、设计决策（已确认）

| 决策点 | 选择 | 理由 |
|:-------|:-----|:-----|
| 审核范围 | **增量为主，支持全量** | 日常审 git diff（聚焦省 token），`--full` 深扫全仓库 |
| 角色数 | **4 个**（UI+产品表达合并） | 文档 / 后端 / 前端 / 产品UI |
| 实现时机 | 先写本 RFC 确认 | — |
| 编排方式 | skill + 并行 Agent | 不引入 Workflow 工具，保持轻量 |
| 报告形态 | **常驻 REVIEW.md 全量状态表** | 报告全量与扫描增量解耦：报告永远全量视角，扫描按需增量 |
| 角色调度 | **增量按 git diff 路由 + 守护检查** | 只派被改动触动的角色，守护检查 grep 级每次必跑防 P0 复发 |
| 成本记录 | **记录** | REVIEW.md 末尾成本表，跑几轮后用数据定全量频率 |

## 三、角色定义

| 角色 | Agent 文件 | 审什么 | 初始检查点来源 |
|:-----|:----------|:-------|:--------------|
| **文档审核** | `review-docs` | RFC、api-spec、CLAUDE.md、docs/ 的一致性；文档-代码契约 | 上次审查维度 8（文档-代码一致性）|
| **后端审核** | `review-backend` | Java 分层依赖、数据安全（ID 唯一性/文件路径/正则健壮性）、AI 集成 | 上次审查维度 1/2/3/4/5 + 发现项 |
| **前端审核** | `review-frontend` | 状态管理、生命周期 mounted、DTO 契约、主题/死代码 | 上次审查维度 6/7 + 发现项 |
| **产品/UI 审核** | `review-product` | 视觉一致性、交互完整性、产品表达（文案/定位/术语）| 前端术语对照 + 布局参考文档 |

## 四、文件结构

```
.claude/skills/review/SKILL.md          ← /review 触发：范围选择 → 派角色 → 汇总 → 滚动状态 → 写报告
.claude/agents/review-docs.md           ← 角色：文档编写审核
.claude/agents/review-backend.md        ← 角色：后端 Java 代码审核
.claude/agents/review-frontend.md       ← 角色：前端 Flutter 代码审核
.claude/agents/review-product.md        ← 角色：UI 设计 + 产品表达审核
docs/review/checklists/review-docs.md   ← 检查点清单（活文档，每次审核可追加）
docs/review/checklists/review-backend.md
docs/review/checklists/review-frontend.md
docs/review/checklists/review-product.md
docs/review/YYYYMMDD-review.md          ← 输出报告（带滚动状态表）
```

**角色与检查点分离**：Agent 定义只写"你是谁、怎么审"，检查点清单单独存文件由 Agent 读取。这样换角色/换模型清单不丢，清单本身可独立演进。

## 五、执行流程

```
/review [--full]
  1. 确定范围：增量 = 上次 review 之后的所有 git diff；--full = 全仓库
  2. 读 docs/review/checklists/*.md 作为各角色检查基线
  3. 读上一份 review 报告 → 提取"未修复项"作为本次必须回查的清单
  4. 并行派 4 个角色 Agent（各自带检查点清单 + 审核范围）
  5. 汇总：按 P0（数据丢失）→ 战略缺口 → P1 → P2/P3 排序
  6. 状态滚动：新问题追加 / 已修复落 ✅ / 未修复延续，逐项更新跟踪表
  7. 写 docs/review/YYYYMMDD-review.md
  8. 沉淀：各 Agent 汇报"本次新增检查点建议"，确认后补进对应 checklists
```

## 六、检查点清单初始内容（从上次审查提炼）

每个清单用**"检查方法 + 上次发现"**格式，便于 Agent 快速执行，也便于持续追加：

- `review-docs`：api-spec ↔ Controller ↔ 前端调用三方对齐；CLAUDE.md 架构图与代码一致；RFC 是否有遗留方向
- `review-backend`：`generateId()` 是否含毫秒；`filePath()` 是否从实体字段推导而非 `now()`；DOTALL 正则是否 `[^\n]*` 而非 `.+`；缓存键是否同规范；scene 路由是否真正触发 Contributor
- `review-frontend`：`setState` 前 `mounted` 守卫；fromJson 期望键 vs 后端序列化；死代码/主题残留；URL 编码
- `review-product`：页面视觉一致性（主题/间距/字体）；交互是否有 stub 占位；产品文案与 `frontend-glossary.md` 术语一致

## 七、如何"不断更新优化"

1. **每次审核结束**，主流程收集各 Agent 的"新检查点建议"，确认后写回对应 checklists（版本号递增）
2. **Checklists 是活文档**：遇到新 bug 类型 → 下次必查，避免同类问题复发
3. **跟踪表即资产**：未修复项会一直滚动直到被修，防止"修了忘、忘了犯"

## 八、已确认（2026-08-01 对话拍板）

- [x] 检查点清单按本 RFC 提炼内容落地，后续可手动补充
- [x] 报告格式 = 常驻 `docs/review/REVIEW.md` 全量状态表（扫描仍增量）
- [x] 记录执行成本到 REVIEW.md 末尾成本表
