---
title: AdaiOS worktree 并行工作手册（外挂三件套与沙箱边界）
description: 在本项目用 git worktree 开并行线时的全部额外动作——worktree 是「空壳」（data/.env/state 都不在版本库内）必须补外挂、DSH 沙箱只能写工作区、端口与构建锁冲突、提交与合并纪律、干净构建发布用法、验证清单；通用形态见同目录 Qoder 手册
version: 1
created: 2026-10-01
updated: 2026-10-03
status: active
lines: 143
depends-on: []
related:
  - ./development.md
  - ./qoder-parallel-workflow.md
  - ./git-workflow.md
  - ./branch-development.md
tags: [guide, workflow, ai-tooling]
---

# AdaiOS worktree 并行工作手册

> **和通用手册的分工**：worktree 的机制、权限模式、看板、通用坑清单见 [qoder-parallel-workflow.md](./qoder-parallel-workflow.md)（跨项目通用，不含项目信息）。**本文只写 AdaiOS 特有的那部分**——因为本项目 `git worktree` 建出来的是一个「空壳」，不补外挂就会静默出错。

## 一、结论先：什么时候值得开

| 用法 | 值得吗 | 原因 |
|:--|:--:|:--|
| **干净 HEAD 构建 / 发布** | ✅ | 防「本地 jar 夹带并发会话未提交的改动」上生产（已实战，见 `ai-engineering/assets/pitfalls.md`） |
| 改动面**不重叠**的并行线（一条动 app、一条动后端） | ✅ | 目录隔离，A 的改动对 B 不可见 |
| 大改实验 / 随时丢弃的尝试 | ✅ | 不动 main 工作区，删掉即可 |
| 多条线**都要改收尾文档** | ❌ | `docs/reference/change-log.md`、`docs/review/REVIEW.md`、`docs/reference/status.md` 每批必写 → 冲突接近 N² |
| 需要人反复试错的 UI 调试 | ❌ | 注意力才是瓶颈，并行无收益 |

## 二、worktree 是空壳：三件外挂

`git worktree add` 给出的是**干净检出**：git 跟踪的都有，**git 之外的家当全没有**。本项目有三样关键家当在版本库外，缺了**不报错，只静默出错**：

| 外挂 | 规模 / 内容 | 缺了会怎样 |
|:--|:--|:--|
| `data/` | 337M；git 只跟踪 `data/adai/identity/profile.sample.md` 一个文件 | 后端 `adai.data.base-path` 默认 `../../data` 正好指向这个空壳 → 记忆/交易/行情全空，**且不报错**（market 314M 行情尤其明显） |
| `services/adai-core/.env` | 6 个密钥（DEEPSEEK_API_KEY / GLM_API_KEY / ADAI_ADMIN_TOKEN / ADAI_PUSH_WECHAT_SENDKEY / ADAI_SMOKE_ACCOUNT / ADAI_SMOKE_PASSWORD） | spring 配的是 `optional:file:.env` → 读不到即静默降级（AI、推送、smoke 全受影响） |
| `ai-engineering/state/` | 巡检游标 `cadence.json`、成本账 `cost-log.jsonl`、心跳缓存、各定时任务日志 | 每个 worktree 一份独立账本 → 巡检游标分叉、成本记错本、发布判定失真 |
| **工具出口** | 技能 4 个（`.dsh/skills` · `.agents/skills` · `.claude/skills` · `.qoder/skills`）＋ 子代理 2 组（`.qoder/agents/*.md` · `.codex/agents/*.toml`）；都是 gitignore 的本机状态 | 新 worktree 里 AI 工具**看不见技能与审查官**——DSH 没技能、Qoder/Codex 连 12 个审查官都没有；**且不报错**（工具只是「没有可用技能」）|

**出口跟另三样不同：它不 link 主仓库，而是「各自注册」。** `worktree-prep.sh` 会自动跑 `scripts/link-skills.sh`（**相对软链**）＋ `scripts/sync-agents.sh`（**生成**），两者都指向 **本 worktree 的真相源** ⇒ **技能与审查官随分支走**。若图省事 link 主仓库的出口，你在 `feat/a` 加的技能会**漏进** `feat/b`——分支隔离在 AI 上下文层直接失效。

**一条命令补齐**（在 worktree 目录里跑）：

```bash
bash scripts/worktree-prep.sh --check     # 只检查（0 = 齐备，1 = 有缺）
bash scripts/worktree-prep.sh --dry-run   # 打印计划，不落盘
bash scripts/worktree-prep.sh             # 默认 link：共享真实数据，几乎不占磁盘
bash scripts/worktree-prep.sh --copy      # 要写数据的实验用（APFS 写时复制，实测 1.4s）
```

脚本会自动分辨**混合目录**：`data/adai/identity/` 里既有 git 跟踪的 `profile.sample.md`（保持检出文件）又有真实的 `profile.md`（补链接）；`ai-engineering/state/` 同理（`_index.md` 是跟踪文件，其余 7 个运行时文件逐条补）。

**内置四道「防删穿」防护**（2026-10-01 对抗审查发现 P0 后加固，见 change-log）：

1. **顶层目录永不建整目录符号链接**（`data/` 与 `state/` 强制逐子项补）——否则 worktree 里一旦出现 `data -> 主仓库/data`，之后任何 `rm -rf <worktree>/data/xxx` 都会**顺着链接删掉主仓库的真实数据**（`rm` 只对**最后一个**路径组件不跟随符号链接，中间组件一律被解析）
2. 删除前断言「目标在 worktree 内的所有父级都不是符号链接」，命中即拒绝退出（exit 3）
3. `--force` 遇到符号链接只 `rm -f` 删链接本身，绝不 `rm -rf`
4. 每次落盘检查返回码：失败即报错并非 0 退出（防「半份拷贝被当完成」，磁盘满是常见形态）

### link 还是 copy：这是安全选择，不是省事选择

| 模式 | 用途 | 代价 |
|:--|:--|:--|
| `link`（默认） | 只读联调、跑测试、看真实数据 | **写入会直接落到 337M 真实数据上**——要写数据的实验别用这个 |
| `copy` | 改数据流、跑会写 records/trading 的实验 | 盘上多一份（APFS clonefile → 写时复制，实测 337M 用时 1.4s，写入彼此隔离） |

## 三、起一条线

```bash
# 1) 从主仓库开（分支名随任务）
git worktree add ../adaios-<任务短名> -b feat/<任务短名> main

# 2) 进新目录，补外挂
cd ../adaios-<任务短名>
bash scripts/worktree-prep.sh

# 3) 自检（应 23 项齐备 / 0 缺失）
bash scripts/worktree-prep.sh --check
```

## 四、两条硬纪律

1. **state / AGENTS.local.md 恒 link**（脚本已固化，不可选）。账本与开工快照必须唯一——复制一份等于把成本账与巡检游标劈成两半。同时：**巡检 / 收工 / 发布 / 每周只在主仓库跑**（launchd 的备份 / noon-task / weekly-audit 三个定时任务的 WorkingDirectory 也钉在主仓库，正本在那儿）。**已机制化（2026-10-03）**：`cadence.sh` 的 **`ship` / `mark`** 在 worktree 里会**直接拒绝**（rc=2，附原因与处置）；`daily` / `weekly` / `release` 只给警告（它们是全局操作，结果与主仓库一致）。
2. **工具出口「各自注册」，绝不 link 主仓库**（`worktree-prep.sh` 落盘时自动跑 `link-skills.sh` + `sync-agents.sh`）。技能与审查官是**项目资产、应随分支走**——link 主仓库会让 `feat/a` 的技能漏进 `feat/b`。
3. **要写数据就先 `--copy`**。`data/` 是真实个人资产（边界 B3），link 模式下的实验写入没有隔离。

## 五、目录方案与沙箱边界（DSH 特有）

DSH 的文件策略是 workspace-write：**AI 只能写当前会话 workspace 内**。所以：

| 方案 | 好处 | 代价 |
|:--|:--|:--|
| **仓库外 `../adaios-<任务>` + 一条线一个会话**（推荐） | 沙箱与信任边界最干净；与通用手册 §4.3「CWD 即信任目录」同构 | 每条线要单开会话（workspace 指到那个目录） |
| 仓库内 `.worktrees/<任务>`（需加 .gitignore） | 当前会话直接能干 | 全仓库搜索会命中重复副本（Flutter/gradle 在各自子目录工作，不受影响） |

## 六、端口与运行环境

- 后端端口写死 8080（`services/adai-core/src/main/resources/application.yml`）→ 第二条线起服务加 `--args='--server.port=8180'`
- 前端 API 地址可配：`flutter run -d chrome --dart-define=API_BASE_URL=http://127.0.0.1:8180` → 指到那条线自己的后端
- Gradle 依赖（`~/.gradle`）与 pub 缓存是**共享**的 → **别同时构建**，错峰
- 每个 worktree 各自一份 `target/`、`.dart_tool/`、`build/`（本仓库一端构建产物就有几百 MB，别同时开太多条）

## 七、提交与合并纪律

- 每个 worktree 有**独立 index**，但仍按**显式路径**提交 + 声明 `ADAI_BATCH_PATHS`（`pre-commit` 第 0 层范围守卫；`ai-engineering/process/ship.md` 记的那次「9 个文件被无关提交带走」跨 worktree 同样成立）
- **短命分支**：做完就合回 main 并清掉 worktree。长期挂着的线必然让文档与账本漂移
- 收尾文档同一时间只让**一条线**写

## 八、第二种用法：干净构建 / 发布

```bash
git worktree add /tmp/adaios-build HEAD --detach
cd /tmp/adaios-build/services/adai-core && ./gradlew bootJar
# 用这份 jar 走部署门禁；发完 git worktree remove /tmp/adaios-build
```

这是本项目已有的实战做法（2026-09-23 起）：它保证**构建产物只含 HEAD**，不含工作区里别人写到一半的代码。

## 九、验证清单（实测通过，2026-10-01）

- [x] 补之前 `--check` 退出码 1、补齐后 0；重复跑幂等（23 项跳过）
- [x] 链接后 worktree 内可穿透读到真实数据（market 314M 可读）
- [x] `copy` 模式写探针不污染主仓库（写时复制隔离）
- [x] 在主仓库执行被拒绝（退出码 2），不会自己链自己
- [x] 危险状态（worktree 里 `data` 已是整目录符号链接）下 `--force` 被拒绝（退出码 3），主仓库数据完好（2026-10-01 P0 修复后回归）
- [x] 补齐后 `data/` 是真实目录、只有子项是链接（顶层不建整目录链接）
- [x] **工具出口各自注册**：空壳 `--check` 报 24 项缺失（含出口 2 项）→ 补齐后 **24 项齐备 · 0 缺失**，6 个出口全建（2026-10-03 探针实测）
- [ ] 你的第一次实跑：worktree 内 `./gradlew test` 全绿 + bootRun 日志里的 data 路径指向真实数据
- [ ] worktree 内 `bash ai-engineering/cadence.sh`（无参数）读到与主仓库**同一份**游标

## 十、收尾清理

```bash
git worktree list                        # 看现在摊开了哪些
git worktree remove <路径> --force       # 收掉一条（外挂是链接/副本，一并随目录删除）
git worktree prune                       # 目录被手工删过时清元数据
git branch -d <分支>                     # 合回 main 后删分支
```

> ⚠️ 不要手工 `rm -rf` worktree 目录——用 `git worktree remove` / `prune`，否则 `.git/worktrees/` 留残骸。
