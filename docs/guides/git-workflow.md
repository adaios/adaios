---
title: AdaiOS Git 工作规范（单人 + 多 AI 工具 + GitHub）
description: 分支怎么开、怎么合、怎么推、怎么发——为「单人但并行（同日多会话 + worktree）」这一形态定规矩。与 worktree-workflow.md（并行线机制）、process/ship.md（收尾）、deploy-gate.sh（部署门禁）配合；含 GitHub 侧的分支保护与最小 CI 建议。
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 146
depends-on:
  - ../reference/change-log.md
related:
  - ./branch-development.md
  - ./worktree-workflow.md
  - ./qoder-parallel-workflow.md
  - ./development.md
  - ../../.agents/process/ship.md
tags: [guide, git, workflow, release]
---

# AdaiOS Git 工作规范

> **适用**：单人在本仓库开发，但同时开多个 AI 会话 / 多个 worktree。**不适用**团队协作场景（那需要更重的评审与保护策略）。

## 一、为什么单人也需要规范

单人 ≠ 单线程。本项目的真实形态是：

- **同日多会话**（DSH / Qoder / Codex 各开一个）——共享同一工作区时靠 `ADAI_BATCH_PATHS` 范围守卫防互卷（已实战两次）
- **worktree 并行线**——每条线一个分支一个工作区（见 `worktree-workflow.md`）
- **本地常是唯一工作副本**——未推送 = 没有备份
- **生产是手工部署**——必须始终能回答「生产跑的是哪个 commit」（`deploy-gate.sh` 把 `DEPLOYED` 写到生产，就是这个锚点）

**仓库现状基线**（2026-10-03）：远端 `github.com:adaios/adaios`（SSH）· 唯一常驻分支 `main` · tag 仅 `v1.0.0` · 尚未配 CI。

## 二、分支模型：GitHub Flow 精简版

| 分支 | 性质 | 说明 |
|:--|:--|:--|
| **`main`** | **唯一常驻** | **任何时刻都可发布**——它是"已验收"的集合，不是"正在写"的地方 |
| `feat/<短名>` `fix/<短名>` `docs/<短名>` `chore/<短名>` | **短命** | worktree 并行线用；**合回 main 即删** |
| ~~`develop`~~ ~~`release/*`~~ ~~`hotfix/*`~~ | **不设** | 单人 + 不维护多版本，不背 Git Flow 的账 |

**分支命名**：小写 kebab、≤4 词、看得出做什么（`feat/learn-plugin`、`fix/trading-anchor`）。
**例外**：真需要维护旧版本（线上 v3.x 与开发版并行）时，才开 `release/v3.x` 常驻分支——**到那时再定**。

## 三、走分支还是直接 main

| 场景 | 做法 |
|:--|:--|
| 小批（单一主题、几个文件） | **直接 `main`**（现状，最快）|
| 大改 / 实验 / 想随时丢弃 | worktree 分支（`feat/*`）|
| **两条互不重叠的并行线** | 各开 worktree 分支（目录隔离，A 的改动对 B 不可见）|
| **干净构建 / 发布** | `git worktree add /tmp/adaios-build HEAD --detach`（已有实践，防产物夹带未提交改动）|

## 四、提交

1. **显式路径 + `ADAI_BATCH_PATHS`**——铁律，已机制化（`pre-commit` 第 0 层范围守卫）。**永不 `git add -A`**。
2. **提交信息**：`type(scope): 摘要`
   - `type`：`feat` / `fix` / `docs` / `chore` / `refactor` / `test` / `perf` / `ci`
   - `scope`：模块名（`trading` / `ai-engineering` / `ios` / `deploy` / `review` / `pre-commit`…）
   - **正文写「为什么」不写「改了什么」**（diff 已说明）：起因、关键取舍、影响面、未做的事
3. **单批单一主题**——混装会让 diff 无法回滚、让审查判定失真。
4. `pre-commit` 多层门禁必须过（对齐 / 结构 / 功能索引 / 技能包 / 防复发 / shell / 隐私 / 密钥）。

## 五、合并

- **目标：`main` 保持线性、可读、可 bisect。**
- **合并前**：`git fetch origin && git rebase origin/main`（把 main 的新提交垫到下面；worktree 线尤其要）。
- **合并方式**：
  - worktree 分支 → **squash 进 main 一个提交**（保持 main 整洁）✅
  - ⚠️ 但**不要把本来有意义的多个逻辑单元压掉**（"实现 + 修 bug + 补测试"是三个单元时，就保留三个）
- **已推送的提交不要 rebase**（单人也不改写历史，避免与 GitHub 上的记录打架）。
- **冲突高发文件**——约定「**同一时间只让一条线写**」：

| 文件 | 为什么 |
|:--|:--|
| `docs/reference/change-log.md` · `docs/review/REVIEW.md` · `docs/reference/status.md` | 每批必写，N 条线 → 冲突接近 N² |
| `docs/rfc/_index.md` · `docs/review/_index.md` | **索引类高频共写点**——今天已实测过一次：两个会话都往 `rfc/_index.md` 加行，先提交的一方带走了对方的行 |

## 六、推送（GitHub）

**现状问题：常积压 4–6 个 commit 未推** ⇒ 本地是唯一副本，磁盘故障即全丢。

**规范**：

1. **每批收工（`cadence.sh ship`）之后推一次**——GitHub 既是备份，也是回顾与（将来的）CI 触发点。
2. **推送 ≠ 发布**：`git push` 只上代码，**不上生产**；部署始终走 `deploy-gate.sh`。
3. ⚠️ 按边界 **B8「外向动作须人确认」**，push 仍需你点头 → 建议把它并入**收工流程的默认项**（收工时一并同意，而不是每次单独问）。
4. **永不** `git push --force` 到 `main`（GitHub 分支保护可挡）。

## 七、发布（tag + GitHub Release）

**版本号**：`v3.x`（与生产版本对齐，见 `docs/reference/status.md`）。

**铁律：tag 必须打在「生产实际部署的那个 commit」上。** 这是版本号与代码唯一的对齐点；`deploy-gate.sh` 写到生产 `DEPLOYED` 里的 commit 就是它。

**标准流程**：

```bash
# 1) 只判定（不部署）：欠什么、要发哪几端
bash .agents/scripts/cadence.sh release
# 2) 你点头后 —— 门禁 + 部署 + smoke
bash .agents/scripts/deploy-gate.sh
# 3) 部署成功后 —— 打 tag 并推送
git tag -a v3.95 -m "第二十一次部署：<一句话>"
git push origin v3.95
# 4) GitHub Release：用 docs/releases/ 的内容作 Release Notes
```

**多端不同步是常态**（例：2026-10-03 第二十次部署是「后端 + Web 先上、iOS 攒批」）：
→ tag 打**已发布的最小公共 commit**，Release Notes 里逐端写明各自状态。

**iOS 的两套编号**：TestFlight 用**构建号**（N → N+1），与 git tag **不是一回事**；Release Notes 里两者都要写清楚（描述文件到期 2027-09-13，见 `status.md`）。

## 八、GitHub 侧建议配置

| 项 | 建议 | 理由 |
|:--|:--|:--|
| **分支保护** | `main`：禁 force push、禁删除 | 防 AI 误操作——单人仓库同样值得 |
| **最小 CI**（Actions） | push / PR 时跑后端 `./gradlew test`（+ 前端 `flutter analyze` 可选） | **解决本地真实痛点**：并发会话会污染本地构建（`pitfalls.md` 记过「构建产物夹带另一个会话的未完成改动」）——**GitHub 的干净检出是唯一可信验证** |
| 仓库可见性 | private | 代码虽不含 `data/`，但 `os/` 里有交易知识资产 |
| Actions 额度 | 私有仓库每月 2000 分钟（免费额度） | 后端测试耗时是主要开销，按需只跑后端 |
| **CI 不做的事** | **不部署** | 部署仍走 `deploy-gate.sh` 手工门禁（B8：外向动作须人确认）|

## 九、与现有流程的衔接

```
开发（main 或 worktree 分支）
  ↓ 提交：显式路径 + ADAI_BATCH_PATHS + pre-commit 多层门禁
  ↓ 推送：随收工一并同意              ← 本规范新增的默认动作（此前常积压）
收工：bash .agents/scripts/cadence.sh ship   （diff + 快照 + 成本入账 + 提交）
发布：bash .agents/scripts/cadence.sh release （只判定）→ 你点头
      → bash .agents/scripts/deploy-gate.sh  （门禁 + 部署 + smoke）
  ↓ 部署成功
打 tag + GitHub Release（tag ＝ 生产那个 commit）
```

## 十、反模式

1. **长期不推**——本地是唯一副本，磁盘挂了全丢。
2. **在 `main` 上多线并行开发**——用 worktree 分支（`main` 永远可发布）。
3. **tag 打在没部署的 commit 上**——版本号与生产失真，事后无法对账。
4. **rebase 已推送的提交**——改写历史，与远端记录打架。
5. **`git add -A`**——已有铁律，跨 worktree 同样成立。
6. **长期挂着的分支**——做完就合回 main 并清掉 worktree（长期线必然让文档与账本漂移）。
7. **把 `data/` 推上去**——边界 B3；`pre-commit` 隐私闸门已挡，但别去绕它。
