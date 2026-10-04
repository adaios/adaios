---
title: AdaiOS 分支开发规范（AI 上下文工程视角）
description: AI 上下文资产全景（工具层 / 业务层 / 代码都是文件、都走 git；唯一不入库的是出口、state、快照三样本机状态）+ 统一流程（加 skill 也开分支 → 本分支注册即用 → 合并 main → 其他分支 merge main 后重跑出口）+ 建四类资产的硬约束 + 冲突面（含 REGISTER 取并集）+ 禁令 + 自检顺序 + 合并后必须重建出口。与 git-workflow.md（版本控制操作）和 ai-context-layer-spec.md（资产布局机制）分工。
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 180
depends-on:
  - ../../records/change-log.md
related:
  - git-workflow.md
  - worktree-workflow.md
  - development.md
  - ../assets/ai-context-layer-spec.md
tags: [guide, git, workflow, ai-tooling, context]
---

# AdaiOS 分支开发规范（AI 上下文工程视角）

> **三份文档的分工**：`git-workflow.md` 管**版本控制操作**（分支 / 提交 / 合并 / 推送 / 发布）· `ai-context-layer-spec.md` 管**资产布局与出口机制**（结构性）· **本文件**管「**在一条分支上开发时，AI 上下文工程目录怎么建、怎么改、什么不能动**」（流程性）。

## 一、AI 上下文资产全景与统一流程

### 1.1 资产全景：都是文件，都在 git

**本项目所有 AI 上下文资产都是文件**，因此**都能像代码一样用 git 管理**——开分支、提交、合并、回滚。

| 层 | 资产 | 位置 |
|:--|:--|:--|
| **工具层**（机制）| 技能 | `.agents/toolkit/skills/<name>/SKILL.md` |
| | 审查官（subagent 源）| `.agents/toolkit/roles/<name>.md` |
| | 守卫 / 执行器 | `.agents/mechanism/guards/guard-*.sh` · `task-cadence.sh` · `scripts/*.sh` |
| | 契约与规范 | `frontmatter-spec.md` · `assets/skills-spec.md` · `assets/ai-context-layer-spec.md` |
| **业务层**（内容）| 约定 / 边界 / 坑 | `assets/conventions.md` · `boundaries.md` · `pitfalls.md` |
| | 决策 | `assets/adr/*.md` · `.agents/direction/rfc/*.md` |
| | 业务方向 | `.agents/direction/VISION.md` · `.agents/direction/product-roadmap.md` |
| | 领域知识（wiki）| `os/*/11-context/*.md` |
| | 账本 / 索引 | `.agents/records/change-log.md` · `.agents/records/REVIEW.md` · `reference/status.md` · 各 `_index.md` |
| **代码** | 业务代码 | `services/` · `apps/` · `os/` |

**⇒ 既然都是文件，就**不需要**为"AI 上下文"发明特殊流程**——**加一个 skill、加一条坑、改一条守卫，和改一行 Java 是同一种操作**：开分支 → 改 → 提交 → 合并。

### 1.2 唯一不是 git 的三样（本机状态，靠脚本重建）

| 项 | 为什么不在 git | 怎么重建 |
|:--|:--|:--|
| **出口**（`.dsh/skills` · `.agents/skills` · `.claude/skills` · `.qoder/skills` · `.qoder/agents/*` · `.codex/agents/*`）| 软链 / 生成物，机器相关 | `bash .agents/mechanism/scripts/ai-worktree-prep.sh`（内含 `ai-link-skills.sh` + `ai-sync-agents.sh`）|
| **`.agents/records/state/`**（游标 / 成本账 / 心跳缓存）| 本机账本，**必须全局唯一** | `ai-worktree-prep.sh` **恒 link 主仓库** |
| **`AGENTS.local.md`**（开工快照）| 本机缓存，机器生成 | 同上恒 link；主仓库可 `ai-guard-context.sh --write-local` 刷新 |

### 1.3 统一流程：一次改动怎么走、怎么传给别的分支

```
main ────────────────────────────────────────────────────────►  （唯一汇合点）
  │
  │ ① 开分支（AI 上下文改动与其他改动没有区别）
  ├──► feat/add-skill-x
  │        ② 改文件（真相源）+ 在本分支注册出口 → 本分支内立即可用
  │        ③ 自测（见 §六）+ 显式路径提交
  │        ④ 合并回 main（squash 或 merge，见 git-workflow.md §五）
  │
  ├──► feat/trading   ⑤ git merge main  →  ⑥ 重跑 ai-worktree-prep.sh  →  拿到新 skill
  └──► feat/learn     ⑤ git merge main  →  ⑥ 重跑 ai-worktree-prep.sh  →  拿到新 skill
```

**三个关键点**：

1. **"给别的分支用" = 别的分支 `git merge main`**——**标准 git，不是特殊机制**。**不存在"实时共享"**：传播靠合并，**也就是一条命令**。
2. **⑤ 之后必须 ⑥**：**出口不在 git 里**——真相源合过来了，出口还是旧的（工具里看不见新 skill / 新审查官）。**这是全流程最容易漏的一步**（§七 详述）。
3. **不需要"出口指向主仓库"这类机制**：各分支**各自注册**（指向自己的真相源），传播交给 `merge`。好处是**分支隔离彻底、没有任何隐式共享**——你在 `feat/a` 试坏一个 skill，不会污染 `feat/b`。

**按改动规模选路（都是 git 操作，没有第四种）**：

| 规模 | 做法 |
|:--|:--|
| 小改（一条坑、一个错字）| **直接在 main 提交** |
| 中改（加一个 skill / 一条规范）| **开分支**（也可直接 main，取决于你是否要隔离与留痕）|
| 大改（改守卫机制 / 换布局）| **开分支 + 隔离 + 合并前跑全门禁** |

## 二、开一条线（三条命令）

```bash
git worktree add ../adaios-<任务> -b feat/<任务> main
cd ../adaios-<任务> && bash .agents/mechanism/scripts/ai-worktree-prep.sh
bash .agents/mechanism/scripts/ai-worktree-prep.sh --check      # 应报 24 项齐备 · 0 缺失
```

之后**在该目录开会话**——技能与审查官**跟着这条分支走**（出口指向本 worktree 的真相源）。

## 三、在分支上建 AI 资产（四类，各有硬约束）

### 3.1 新技能（`.agents/toolkit/skills/<name>/SKILL.md`）

- **必须目录布局**（`<name>/SKILL.md`）——官方 Agent Skills 规范；`ai-guard-skills` 的 S3 会查 `name` 等于**父目录名**
- **五段结构**：触发条件 / 执行步骤 / 约束与规则 / 输出要求 / 参考资料（S5）
- `description` 1–1024（S4）
- **注册**：若属"**用户直触发**"→ 把 `<name>` 加进 `.agents/mechanism/scripts/ai-link-skills.sh` 的 `REGISTER`，跑该脚本
- ⚠️ **成本纪律**：**只注册直触发**的技能——其余常驻 catalog 要花钱，还可能被误触发

### 3.2 新审查官（`.agents/toolkit/roles/<name>.md`）

- **保持扁平**，**刻意不目录化**（理由：不进技能出口；目录化要付 44 处引用的代价而零收益——见 `ai-context-layer-spec.md` §三）
- 五段结构（同技能）
- **要能在工具里派** → 把 `<name>` 加进 `.agents/mechanism/scripts/ai-sync-agents.sh` 的 `REGISTER`，跑该脚本
- ⚠️ **预算线（2026-10-03 修正参照系）**：真正卡人的是 **skill listing 预算 ≈ context window 的 1%**（Agent Skills 官方），**溢出会丢弃部分 description ⇒ 越多触发越不准**。此前按 Claude Code 的 15k 告警线判断，是**用错了参照系**（见 `ai-context-layer-spec.md` §十一）。**新增审查官前先问：它会不会被真的调用？**（2026-10-03 实测：11/12 零读取）

### 3.3 新规范 / 资产（`.agents/rules/assets/*`）

- frontmatter **10 字段**（`ai-guard-meta` 查）
- **登记进 `.agents/_index.md`**（否则报 **M3 孤儿**）
- 文档内引用用**相对本文件**的路径（`ai-guard-meta` **M1** 查断链）

### 3.4 接一个新工具

按 `ai-context-layer-spec.md` **§五 四步**：① 确认真相源已有内容 → ② 建出口（软链或生成）→ ③ 注册 → ④ **加进 `ai-guard-tools.sh` T4 的扫描清单**（否则新出口无人检查）。
**子代理出口同理**：加进 `ai-sync-agents.sh` 的 `TARGETS` + 一种格式分支。

## 四、分支上的冲突面（★ 含 AI 上下文特有的一类）

| 文件 | 冲突形态 | 怎么合 |
|:--|:--|:--|
| **`.agents/mechanism/scripts/ai-link-skills.sh` 的 `REGISTER`** | ⚠️ **两条线各加技能名** | **取并集**（顺序无关，别二选一）|
| **`.agents/mechanism/scripts/ai-sync-agents.sh` 的 `REGISTER`** | 同上（审查官名）| **取并集** |
| `.agents/_index.md` | 各加行 | 手工合 |
| `.agents/rules/assets/*`（规范）| 同文件不同段落 | 先合一条，另一条 rebase 后调整 |
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
6. **不动 `.agents/records/state/`**——那是本机账本（游标与成本账）

## 六、自检顺序（分支上）

```bash
# 开工
bash .agents/mechanism/scripts/ai-worktree-prep.sh --check           # 外挂 + 出口：24 项齐备
bash .agents/mechanism/guards/ai-guard-context.sh            # 上下文基线（可加 --topic 过滤）
# 改了 AI 资产之后
bash .agents/mechanism/guards/ai-guard-skills.sh             # 技能包合规（S3/S4/S5/S7）
bash .agents/mechanism/scripts/ai-link-skills.sh --check             # 技能出口齐不齐
bash .agents/mechanism/scripts/ai-sync-agents.sh --check             # 子代理生成物新不新
bash .agents/mechanism/guards/ai-guard-tools.sh              # 工具接入自检（含 T4 出口真身）
# 提交前（pre-commit 会自动跑大部分）
bash .agents/mechanism/scripts/task-cadence.sh check            # 交付门禁一键
```

**收工回主仓库**跑 `task-cadence.sh ship`——**分支上只能提交，不能收工**（守卫会拒）。

## 七、★ 合并回主仓库后必须做的一件事：重建出口

合并带来了**新技能 / 新审查官**，但**出口是本机状态、不在 git 里**——**合并不动它**：

```bash
cd <主仓库>
bash .agents/mechanism/scripts/ai-link-skills.sh           # 新技能进 4 个出口
bash .agents/mechanism/scripts/ai-sync-agents.sh           # 新审查官进 subagent 定义
bash .agents/mechanism/guards/ai-guard-tools.sh    # T4 验证出口真身（会抓出过期出口）
bash .agents/mechanism/scripts/task-cadence.sh check  # 全门禁
```

**这是最容易漏的一步**——真相源合了，工具里却看不到新资产（"我明明加了技能，怎么没有？"）。

## 八、反模式

1. **手改 `.dsh/skills/xxx`** 想把技能"顺手调一下"——你改的是链接，且下次注册就被覆盖
2. **在分支上跑 `task-cadence.sh ship`**——会被拒；要是没这道守卫，会**污染全局游标**
3. **合并后忘了重建出口**——"我加了技能，工具里没有"（本节 §七）
4. **两条线的 `REGISTER` 冲突时二选一**——应**取并集**（两边的新资产都要在）
5. **把 `state/` 复制进分支**——账本与游标被劈成两半
6. **在分支上改 `AGENTS.local.md`**——它是 link 的，改的是**主仓库那份快照**
