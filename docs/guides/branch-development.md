---
title: AdaiOS 分支开发规范（AI 上下文工程视角）
description: 开一条分支开发时，AI 上下文工程目录怎么建、怎么改、什么不能动——三层归属（真相源随分支 / 出口各自注册 / state 与快照全局唯一）、建四类资产的硬约束、分支上的冲突面（含 REGISTER 数组这种 AI 上下文特有的）、禁令、自检顺序、合并后必须重建出口。与 git-workflow.md（版本控制操作）和 ai-context-layer-spec.md（资产布局机制）分工。
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 131
depends-on:
  - ../reference/change-log.md
related:
  - ./git-workflow.md
  - ./worktree-workflow.md
  - ./development.md
  - ../../ai-engineering/assets/ai-context-layer-spec.md
tags: [guide, git, workflow, ai-tooling, context]
---

# AdaiOS 分支开发规范（AI 上下文工程视角）

> **三份文档的分工**：`git-workflow.md` 管**版本控制操作**（分支 / 提交 / 合并 / 推送 / 发布）· `ai-context-layer-spec.md` 管**资产布局与出口机制**（结构性）· **本文件**管「**在一条分支上开发时，AI 上下文工程目录怎么建、怎么改、什么不能动**」（流程性）。

## 一、三层归属：什么随分支、什么全局唯一

| 层 | 内容 | 随分支？ | 谁写 |
|:--|:--|:--:|:--|
| **真相源** | `AGENTS.md` ×7 · `ARCHITECTURE.md` · `ai-engineering/**` · `docs/**` | ✅ | 各分支可改（共享账本按 `git-workflow.md` §五合）|
| **出口**（本机状态，**不进 git**）| `.dsh/skills` · `.agents/skills` · `.claude/skills` · `.qoder/skills` · `.qoder/agents/*` · `.codex/agents/*` | ✅ **各自注册** | **只由脚本生成**——手改必被覆盖 |
| **全局唯一**（link 主仓库）| `ai-engineering/state/` · `AGENTS.local.md` | ❌ | **只在主仓库写**（`ship`/`mark` 已被守卫拒绝）|
| 工具私有 | `.idea/` · `.obsidian/` | — | 项目不碰 |

## 二、开一条线（三条命令）

```bash
git worktree add ../adaios-<任务> -b feat/<任务> main
cd ../adaios-<任务> && bash scripts/worktree-prep.sh
bash scripts/worktree-prep.sh --check      # 应报 24 项齐备 · 0 缺失
```

之后**在该目录开会话**——技能与审查官**跟着这条分支走**（出口指向本 worktree 的真相源）。

## 三、在分支上建 AI 资产（四类，各有硬约束）

### 3.1 新技能（`ai-engineering/skills/<name>/SKILL.md`）

- **必须目录布局**（`<name>/SKILL.md`）——官方 Agent Skills 规范；`guard-skills` 的 S3 会查 `name` 等于**父目录名**
- **五段结构**：触发条件 / 执行步骤 / 约束与规则 / 输出要求 / 参考资料（S5）
- `description` 1–1024（S4）
- **注册**：若属"**用户直触发**"→ 把 `<name>` 加进 `scripts/link-skills.sh` 的 `REGISTER`，跑该脚本
- ⚠️ **成本纪律**：**只注册直触发**的技能——其余常驻 catalog 要花钱，还可能被误触发

### 3.2 新审查官（`ai-engineering/roles/<name>.md`）

- **保持扁平**，**刻意不目录化**（理由：不进技能出口；目录化要付 44 处引用的代价而零收益——见 `ai-context-layer-spec.md` §三）
- 五段结构（同技能）
- **要能在工具里派** → 把 `<name>` 加进 `scripts/sync-agents.sh` 的 `REGISTER`，跑该脚本
- ⚠️ **预算线**：12 个审查官的 `description` 合计 **≈1.3k token**（Claude Code 官方告警线 **15k**）。新增时**接近这条线就缩短 description，不要砍审查官**

### 3.3 新规范 / 资产（`ai-engineering/assets/*`）

- frontmatter **10 字段**（`guard-meta` 查）
- **登记进 `ai-engineering/_index.md`**（否则报 **M3 孤儿**）
- 文档内引用用**相对本文件**的路径（`guard-meta` **M1** 查断链）

### 3.4 接一个新工具

按 `ai-context-layer-spec.md` **§五 四步**：① 确认真相源已有内容 → ② 建出口（软链或生成）→ ③ 注册 → ④ **加进 `guard-tools.sh` T4 的扫描清单**（否则新出口无人检查）。
**子代理出口同理**：加进 `sync-agents.sh` 的 `TARGETS` + 一种格式分支。

## 四、分支上的冲突面（★ 含 AI 上下文特有的一类）

| 文件 | 冲突形态 | 怎么合 |
|:--|:--|:--|
| **`scripts/link-skills.sh` 的 `REGISTER`** | ⚠️ **两条线各加技能名** | **取并集**（顺序无关，别二选一）|
| **`scripts/sync-agents.sh` 的 `REGISTER`** | 同上（审查官名）| **取并集** |
| `ai-engineering/_index.md` | 各加行 | 手工合 |
| `ai-engineering/assets/*`（规范）| 同文件不同段落 | 先合一条，另一条 rebase 后调整 |
| `.gitignore`（出口忽略规则）| 各加规则 | 手工合（注意**锚定根** `/skills/` 那类坑，见 pitfalls 二十三）|
| `AGENTS.md` 审查体系表 | 各加行 | 手工合 |
| `change-log` / `REVIEW` / `status` / 两个 `_index` | 共享账本 | 见 `git-workflow.md` §五（顺序合并 + 门禁兜底）|

**☞ `REGISTER` 数组是 AI 上下文特有的冲突点**——它不是"账本"，但**行为等价于共享清单**：合并时**取并集**即可。

## 五、分支上的禁令（做了会静默出错）

1. **不手改出口**——`.dsh/skills/*` 等是软链或生成物；手改会被脚本覆盖，且**不进 git**（改了也白改）
2. **不在分支上写 `state/` 或 `AGENTS.local.md`**——它们 link 主仓库；`ship`/`mark` 已被守卫**硬拦**
3. **不在分支上跑巡检 / 收工 / 发布**——同上（`daily`/`weekly`/`release` 会警告）
4. **不把 `data/` 内容带进分支**（边界 B3；隐私闸门会挡）
5. **不绕范围守卫**（`git add -A`）——分支有独立 index，但纪律不变
6. **不动 `ai-engineering/state/`**——那是本机账本（游标与成本账）

## 六、自检顺序（分支上）

```bash
# 开工
bash scripts/worktree-prep.sh --check           # 外挂 + 出口：24 项齐备
bash ai-engineering/guard-context.sh            # 上下文基线（可加 --topic 过滤）
# 改了 AI 资产之后
bash ai-engineering/guard-skills.sh             # 技能包合规（S3/S4/S5/S7）
bash scripts/link-skills.sh --check             # 技能出口齐不齐
bash scripts/sync-agents.sh --check             # 子代理生成物新不新
bash ai-engineering/guard-tools.sh              # 工具接入自检（含 T4 出口真身）
# 提交前（pre-commit 会自动跑大部分）
bash ai-engineering/cadence.sh check            # 交付门禁一键
```

**收工回主仓库**跑 `cadence.sh ship`——**分支上只能提交，不能收工**（守卫会拒）。

## 七、★ 合并回主仓库后必须做的一件事：重建出口

合并带来了**新技能 / 新审查官**，但**出口是本机状态、不在 git 里**——**合并不动它**：

```bash
cd <主仓库>
bash scripts/link-skills.sh           # 新技能进 4 个出口
bash scripts/sync-agents.sh           # 新审查官进 subagent 定义
bash ai-engineering/guard-tools.sh    # T4 验证出口真身（会抓出过期出口）
bash ai-engineering/cadence.sh check  # 全门禁
```

**这是最容易漏的一步**——真相源合了，工具里却看不到新资产（"我明明加了技能，怎么没有？"）。

## 八、反模式

1. **手改 `.dsh/skills/xxx`** 想把技能"顺手调一下"——你改的是链接，且下次注册就被覆盖
2. **在分支上跑 `cadence.sh ship`**——会被拒；要是没这道守卫，会**污染全局游标**
3. **合并后忘了重建出口**——"我加了技能，工具里没有"（本节 §七）
4. **两条线的 `REGISTER` 冲突时二选一**——应**取并集**（两边的新资产都要在）
5. **把 `state/` 复制进分支**——账本与游标被劈成两半
6. **在分支上改 `AGENTS.local.md`**——它是 link 的，改的是**主仓库那份快照**
