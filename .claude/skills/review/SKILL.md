---
name: review
description: 项目审核 — 增量优先（按 git diff 路由角色），更新 docs/review/REVIEW.md 全量状态报告
---

# /review — 项目审核

对 AdaiOS 仓库执行可复用审核。范围：**增量**（默认，审上次审核后的 git diff）或 **全量**（`--full`，深扫全仓库）。

目标产出只有一处：`docs/review/REVIEW.md`（常驻全量状态报告）。每次审核**更新它**，不新建报告。

## 执行步骤

### 1. 确定范围

```bash
git log --oneline -20          # 找上次 review 的 commit 作为增量基线
```

- **增量模式（默认）**：基线 = 上次审核提交。读取 `docs/review/REVIEW.md` 头部"上次审核基线 commit"。若找不到，用最近一次 `docs/review/` 相关提交或 last 10 commits 内的最大边界。diff = `git diff <基线>..HEAD`。
- **全量模式（`--full`）**：扫全仓库，不设 diff 边界。

### 2. 守护检查（每次必跑，grep 级）

读取 `docs/review/checklists/guard.md`，逐条执行其中的检查命令。这层防 P0 复发（数据丢失/契约破坏），成本极低，任何模式都不跳过。发现命中即记录为问题。

### 3. 按 diff 路由派角色

**增量模式**：用 `git diff --stat <基线>..HEAD` 看改动触及哪些目录，只派被触动的角色（每角色一个并行 Agent）：

| 改动位置 | 派发角色 |
|:---------|:---------|
| `services/adai-core/**` | `review-backend` |
| `apps/adai-app/**` | `review-frontend` |
| `docs/**`、`*.md`、`ai/**` | `review-docs` |
| 前端视觉/交互/文案 | `review-product` |

跨多目录则派多个角色并行。`docs/**` 与前端改动同时出现时，`review-product` 与 `review-docs` 可并派。

**全量模式**：4 个角色全派。

派 Agent 时，prompt 中必须包含：
- 审核范围（diff 内容或全量说明）
- 指向 `docs/review/checklists/<角色>.md` 的清单路径
- 要求输出：按 **P0（数据丢失）→ 战略缺口 → P1 → P2/P3** 排序的问题清单，每条含 `位置` + `问题` + `建议修复`

### 4. 汇总排序

收集所有角色结果，去重合并，统一排序。守护检查命中项并入同表。

### 5. 滚动更新 REVIEW.md

读当前 `docs/review/REVIEW.md`：
- **未修复项**：逐条核对，若本次已修 → 标 ✅ 移到已修复区；未修 → 保留
- **新发现问题**：追加到对应优先级区
- **更新头部**：`最近审核日期` + `本次基线 commit` + `扫描模式`
- 已修复区只保留最近 10 条（旧的直接删，git 历史可查）

### 6. 沉淀新检查点

各角色 Agent 在报告中附"本次新增检查点建议"。把确认有价值的模式补进对应 `docs/review/checklists/*.md`（追加一行，格式同清单）。这是"不断更新优化"的机制。

### 7. 记录成本

在 REVIEW.md 末尾成本表追加一行：`日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增问题 | 已修复`。

## 约定

- 发现问题**不直接修复**（除非 P0 数据丢失且修复路径明确，可与用户确认后修）。review 的职责是**报告**，修复是后续动作。
- 报告用中文，问题描述带 `文件:行号` 可点击定位。
- 守护检查命中即使上次已知也要报告，因为它是"复发信号"。
