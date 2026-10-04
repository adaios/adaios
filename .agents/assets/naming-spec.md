---
title: AI 资产命名规范
description: skill / subagent / 脚本的统一命名体系——六个域 + 封闭角色词表 + 硬规则；含本项目的历史名与新名映射表（B 方案：全域改名）
version: 1
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 186
depends-on:
  - skills-spec.md
related:
  - ai-context-layer-spec.md
  - ../roles/_directory.md
  - ai-context-engineering.md
tags: [ai, spec, naming]
---

# AI 资产命名规范

> **为什么要有这个名字规范**：名字是**最便宜的文档**。`code-backend-reviewer` 一眼告诉你「**域=代码 · 对象=后端 · 角色=审查**」；`docs-design-writer` 什么都没说。命名一乱，`ls` 出来就是一堆看不出关系的东西。

## 一、总式

```
<域> - <对象> - <角色>          ← 角色类（subagent / skill）
<域> - <动作>                   ← 命令类（task 族 / 脚本）
```

- **从粗到细**：域（哪一块）→ 对象（具体谁）→ 角色（干什么）
- **同族相邻**：`ls` 时 `code-backend-reviewer` 与 `code-frontend-reviewer` 排在一起

## 二、六个域

| 域 | 管什么 | 归属位置 | 成员 |
|:--|:--|:--|:--|
| **`task-`** | **任务生命周期**（横向，跨阶段）| `workspace/tasks/` | `task-init` · `task-status` · `task-done` · `task-review` |
| **`docs-`** | **文档产出与审查** | `workspace/designs/` · `roles/` | `docs-design-writer` · `docs-design-reviewer` · `docs-contract-reviewer` |
| **`code-`** | **代码产出与审查** | 代码库 · `roles/` | `code-writer` · `code-backend-reviewer` · `code-frontend-reviewer` |
| **`ux-`** | **用户体验与外部视角** | `roles/` | `ux-interaction-reviewer` · `ux-visual-reviewer` · `ux-stranger-reviewer` · `ux-social-reviewer` · `ux-support-reviewer` |
| **`data-`** | **知识与数据** | `roles/` | `data-knowledge-reviewer` |
| **`ai-`** | **AI 工程自身（机制）** | `.agents/guards/` · `roles/` | `ai-context-reviewer` · `ai-adversarial-reviewer` · `ai-guard-*` · `ai-setup-hooks` |

## 三、角色词表（**封闭**——只许用这几个）

| 词 | 含义 | 形态 |
|:--|:--|:--|
| **`-writer`** | **产出者**（写）| 角色 |
| **`-reviewer`** | **审查者**（审）| 角色 |
| `-init` · `-status` · `-done` · `-review` | 任务命令 | 动作 |
| `-guard` | 检查器（机制）| 动作 |
| `-setup` · `-link` · `-sync` · `-prep` · `-gate` | 环境操作 | 动作 |

**成对性（硬性）**：**有 `-writer` 就必须有一个 `-reviewer` 对家** —— 这正是「双角色真对打」写进名字的方式。

## 四、硬规则（6 条）

1. **全小写 kebab-case**，匹配 `^[a-z0-9]+(-[a-z0-9]+)*$`
2. **≤32 字符**（官方上限 64，我们内部收紧）
3. **`name` 与目录名 / 文件名严格一致**（官方硬约束；`ai-guard-skills` S3 已在校验）
4. **域前缀必选**（六选一），不允许裸名（历史名见第六节映射表）
5. **角色词只能取自第三节的封闭词表**
6. **`writer` / `reviewer` 成对**；新增一方时同时规划另一方

## 五、与业界的关系

| 来源 | 我们采用的 |
|:--|:--|
| [Agent Skills 官方规范](https://agentskills.io/specification) | kebab-case · ≤64 · **name 必须与目录名一致**（规则 1/3）|
| [agency-agents（184 skills）](https://github.com/iTzFaisal/agency-agents/blob/main/AGENTS.md) | **「类别前缀 + 名称」** 的 `<域>-<对象>-<角色>` 三段式（规则 4/5）——样本：`engineering-code-reviewer` |
| [Claude Code Commands](https://code.claude.com/docs/en/commands) | 命令类走**极简动作词**（`task-init` 一族）|

**⇒ 一句话**：**域分段学 agency-agents，字符约束学官方规范，命令命名学 Claude Code。**

## 六、映射表（历史名 → 体系名）—— **已按 B 方案全域执行（2026-10-03）**

> 本节是**执行结果**，不是计划。四批共改名 **62 个文件、替换约 1343 处引用**。

### 6.1 `roles/`（13）

| 历史名 | 体系名 |
|:--|:--|
| `backend-reviewer` | `code-backend-reviewer` |
| `frontend-reviewer` | `code-frontend-reviewer` |
| `product-arch` | `docs-product-reviewer` |
| `docs-reviewer` | `docs-contract-reviewer` |
| `ux-reviewer` | `ux-interaction-reviewer` |
| `ui-reviewer` | `ux-visual-reviewer` |
| `stranger-reviewer` | `ux-stranger-reviewer` |
| `social-reviewer` | `ux-social-reviewer` |
| `support-reviewer` | `ux-support-reviewer` |
| `knowledge-reviewer` | `data-knowledge-reviewer` |
| `context-reviewer` | `ai-context-reviewer` |
| `adversarial-reviewer` | `ai-adversarial-reviewer` |
| `design-author` | `docs-design-writer` |

### 6.2 `checklists/`（14，与角色一一对应）

| 历史名 | 体系名 |
|:--|:--|
| `review-backend` | `code-backend-reviewer` |
| `review-frontend` | `code-frontend-reviewer` |
| `review-product` | `docs-product-reviewer` |
| `review-docs` | `docs-contract-reviewer` |
| `review-ux` | `ux-interaction-reviewer` |
| `review-ui` | `ux-visual-reviewer` |
| `review-stranger` | `ux-stranger-reviewer` |
| `review-social` | `ux-social-reviewer` |
| `review-support` | `ux-support-reviewer` |
| `review-knowledge` | `data-knowledge-reviewer` |
| `review-context` | `ai-context-reviewer` |
| `review-perf` | `code-perf-reviewer` |
| `guard` | `ai-guard-checklist` |
| `cost` | `ai-cost-checklist` |

### 6.3 `skills/`（4）

| 历史名 | 体系名 | 形态 |
|:--|:--|:--|
| `learn-digest/` | `data-learn-writer/` | 目录（官方布局，有工具出口） |
| `new-api.md` | `code-api-writer.md` | 扁平（按需加载） |
| `new-domain.md` | `code-domain-writer.md` | 扁平（按需加载） |
| `ship.md` | `task-ship.md` | 扁平（按需加载） |

### 6.4 `guards/`（13，含原先漏计的两个）

| 历史名 | 体系名 |
|:--|:--|
| `meta.sh` | `ai-guard-meta.sh` |
| `structure.sh` | `ai-guard-structure.sh` |
| `skills.sh` | `ai-guard-skills.sh` |
| `tools.sh` | `ai-guard-tools.sh` |
| `align.sh` | `ai-guard-align.sh` |
| `feature.sh` | `ai-guard-feature.sh` |
| `context.sh` | `ai-guard-context.sh` |
| `sediment.sh` | `ai-guard-sediment.sh` |
| `cost.sh` | `ai-guard-cost.sh` |
| `release.sh` | `ai-guard-release.sh` |
| `prod.sh` | `ai-guard-prod.sh` |
| `roadmap.sh` | `ai-guard-roadmap.sh` |
| `unfixed.sh` | `ai-guard-unfixed.sh` |

### 6.5 `scripts/`（18）

| 历史名 | 体系名 |
|:--|:--|
| `cadence.sh` | `task-cadence.sh` |
| `noon-task.sh` | `task-noon.sh` |
| `weekly-audit.sh` | `task-weekly-audit.sh` |
| `check_deadlines.py` | `task-check-deadlines.py` |
| `setup-hooks.sh` | `ai-setup-hooks.sh` |
| `setup-launchd.sh` | `ai-setup-launchd.sh` |
| `link-skills.sh` | `ai-link-skills.sh` |
| `sync-agents.sh` | `ai-sync-agents.sh` |
| `worktree-prep.sh` | `ai-worktree-prep.sh` |
| `lint-shell-vars.py` | `ai-lint-shell-vars.py` |
| `scan-secrets.py` | `ai-scan-secrets.py` |
| `deploy-gate.sh` | `code-deploy-gate.sh` |
| `backup_prod.sh` | `code-backup-prod.sh` |
| `migrate-data-to-user-layer.sh` | `data-migrate-user-layer.sh` |
| `sync_tdx_data.sh` | `data-sync-tdx.sh` |
| `prewarm-adj-factors.py` | `data-prewarm-adj-factors.py` |
| `sync-stock-names.py` | `data-sync-stock-names.py` |
| `verify-tdx-data.py` | `data-verify-tdx.py` |

### 6.6 **明确不在改名范围**（保持原名）

| 位置 | 说明 |
|:--|:--|
| `process/*.md`（`audit` `review` `ship` `cadence` `review-driven`）| **流程文档名**是动作语义，加域前缀反而绕（且 `task-ship` 已被技能占用）|
| `state/*.json` / `*.log` | **本机状态文件名**（`cadence.json` 等）——不是脚本、不该带域前缀 |
| `lib/cadence-lib.sh` | 库文件；等出现第二个库时再统一 |
| `workspace/{tasks,requirements,designs}/` 内文件 | 由流程命名（`<分支名>.md` · `<需求id>.md` · `design-v<N>-<日期>.md`）|
| 根 `AGENTS.md` / `ARCHITECTURE.md` / `README.md` | 工具与人类约定，**动不得** |

## 七、落地步骤（B 方案）

1. **落盘本规范**（`naming-spec.md`）+ 登记到 `assets/_index.md`
2. **`roles/` 改名**（13 个文件 + 全仓库引用替换 + `ai-sync-agents.sh` 的 `REGISTER` + 生成物）
3. **`skills/` 改名**（4 个 + `ai-link-skills.sh` 的 `REGISTER` + 出口软链）
4. **`guards/` 改名**（11 个 + `pre-commit` 的调用 + 所有流程文档引用）
5. **`scripts/` 改名**（逐个判断 + launchd plist 重装）
6. **每步验证**：`ai-guard-meta` / `ai-guard-structure` / `ai-guard-skills` / `ai-guard-tools` 全绿 + **反例可用**
7. **收尾**：`.agents/assets/ai-context-engineering.md` 等文档同步；`AGENTS.md` 审查体系表更新

> **纪律**：**一步一提交、一步一验证**；每步都跑 `ai-guard-structure.sh --fix` 刷清单。
> **风险控制**：改名是纯机械替换 + `git mv`，**由守卫兜底**（断链 / 孤儿 / 清单不一致都会被拦）。
