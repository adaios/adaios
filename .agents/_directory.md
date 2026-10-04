---
title: .agents/ 目录契约
description: 规则与机制层的职责边界 · 依赖 · 触发 · 约束 · 守卫 · 维护——判据＝按性质（回答「怎么做」的进本层）；工具中立、真相源进 git、出口不入库
version: 2
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 30
depends-on: []
related: [./_directory.md]
tags: [meta, directory, ai]
---

# .agents/ 目录契约

**职责**：AdaiOS 的 **AI 运行时容器**（工具中立）——凡 **AI 干活时会读 / 会写**的资产都归这里。

**判据（2026-10-04 二批定格）**：**这份文档 AI 在干活时会读 / 写它吗？**
- **会 → 本容器**：**① 方向**（`direction/`）· **② 事实**（`reference/` `features/`）· **① 决策**（`rfc/` `assets/adr/`）· **③ 规则**（`assets/` `guides/` `deployment/` `process/` `workflow/` `roles/` `skills/` `checklists/` `method/`）· **④ 活账本**（`records/`）· **⑤ 机制**（`guards/` `scripts/` `lib/` `tests/`）· 协作状态（`state/` `workspace/`）
- **不会 → `../docs/`**（档案馆：史 / 存档 / 对外 / 研究）

## 职责边界
- **放**：一切**规则类**真相源——规范 / 边界 / 决策 / 坑 / 流程 / **工程规则（`guides/`）** / **运维规则（`deployment/`）** / 技能 / 审查官 / 守卫 / 脚本 / 契约 / 状态
- **不放**：代码本体（`services/` `apps/` `os/`）；**① 方向 / ② 事实 / ④ 记录 / 对外材料**（→ `../docs/`）；工具私有（`.idea/` `.obsidian/`）

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 入口 | `../AGENTS.md` | 任何工具的第一入口，指向这里 |
| 被依赖 | 工具出口 | `.dsh/skills` · `.claude/skills` · `.qoder/skills`（软链）· `.agents/skills`（本目录自身）· `.qoder/agents/` `.codex/agents/`（生成） |
| 工具 | `../.githooks/pre-commit` | 提交门禁调用 `guards/` |
| 工具 | `scripts/` | 换机与 worktree 时重建出口 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 任何会话开工 | `AGENTS.md` 规则 0 | `guards/ai-guard-context.sh` |
| 提交 | `.githooks/pre-commit` | `guards/` 的六层门禁 |
| 收工 / 巡检 / 发布 | 用户触发词 | `scripts/task-cadence.sh` |
| 换机 / 新 worktree | 人 | `scripts/ai-setup-hooks.sh` · `ai-link-skills.sh` · `ai-sync-agents.sh` · `ai-worktree-prep.sh` |
| 工具加载技能/子代理 | 工具自身 | `skills/`（直连）· `roles/`（经生成物） |

## 约束
- **真相源全部进 git**；只有两类不入库：**工具出口**（`.dsh/` `.claude/` `.qoder/` `.codex/` 与生成物）与**本机状态**（`state/`）
- **`.gitignore` 规则必须带前缀锚定**（写 `skills/` 会连真相源一起忽略——pitfalls 二十三）
- **每个子目录必须同时有 `_index.md`（清单）与 `_directory.md`（契约）**
- **每个 md 必带 frontmatter 10 字段**（见 `frontmatter-spec.md`）
- **层级敏感**：脚本里的 `cd "$(dirname "$0")/../.."` 是本目录结构的硬假设（迁层必须同改）

## 守卫（谁保证这里不腐烂）
- `guards/ai-guard-structure.sh`：**目录级自洽**（S1 两件套齐备 / S2 清单⇄实际 / S3 契约依赖存在 / S4 契约提到的守卫存在）
- `guards/ai-guard-meta.sh`：**文件级**（M1 断链 / M2 lines / M3 孤儿 / M4 正文路径）
- `guards/ai-guard-skills.sh`：技能包合规（S3/S4/S5/S7）
- `guards/ai-guard-tools.sh`：工具接入自检（T1–T7，含出口真身）

## 维护动作
1. **新增子目录** → 建 `_index.md` + `_directory.md` + 在根 `_index.md` 登记
2. **新增文件** → 写文件（带 10 字段 frontmatter）→ 跑 `bash guards/ai-guard-structure.sh --fix` 刷清单
3. **移动文件** → 同 2，并检查 `_directory.md` 里的依赖路径
4. **改动结构（层级）** → 全仓库搜 `$(dirname "$0")/..` 与旧路径（pitfalls 二十三四条）

## 边界
- vs `../docs/`：**本容器放「AI 运行时需要的一切」**；`docs/` 是**档案馆**（AI 运行不需要的：史 / 存档 / 对外 / 研究）。完整判定表见 `../docs/README.md`——**判据演进：按读者（旧）→ 按性质（一批）→ AI 运行时是否需要（二批，定格）**
- vs `../services/` `../apps/` `../os/`：那是**代码本体**，本容器只放"关于它们的事实与约束"
