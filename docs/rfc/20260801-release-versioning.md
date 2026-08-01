---
title: 产品发布版本机制（Release Versioning）
date: 2026-08-01
status: draft
---

# 产品发布版本机制

## 一、背景

AdaiOS 单人开发已推进到**功能积累期**：

- 审核修复三批已闭环（`cce0b1a`/`af530f0`/`c37e251`），REVIEW.md 待办收敛到 3 条
- 记忆系统进化方案已定（`20260801-memory-system-evolution.md`），待落地为功能
- 当前**无版本边界**：改动直接进 main，无发布节点、无 Release Notes
- `deploy.sh` 历史遗留未建立，部署流程待随版本机制规范化

**目标**：给开发节奏一个**版本容器**——功能/修复有归属、发布有节点、历史有 Release Notes。适配 File First + 单人开发的轻量流程，不引入重型发布管理。

## 二、版本号规则

**SemVer**：`MAJOR.MINOR.PATCH`（可选 `-rc.N` 候选）

| 位 | 何时递增 | 例子 |
|:---|:---------|:-----|
| MAJOR | 架构级变化 / 破坏性数据迁移 | `v1.0.0` |
| MINOR | 新功能（Feature） | `v0.2.0` |
| PATCH | 修复 / 小改进 | `v0.1.1` |

**起步**：`v0.1.0`（首个可发布基线）。`0.x` 为正式版前不稳定期，进入 `v1.0.0` 需：核心闭环（记忆/问答/复盘）稳定 + 数据格式冻结。

**数据格式变化**（File First）：改动 `data/` 或 `os/` 文件格式 → 记入 Release Notes"数据/文件格式变更"，并提供迁移说明（如记忆进化 Phase 1-2 的 `kind/topic/superseded` 字段：向后兼容，旧条目默认解析，无需手动迁移）。

## 三、版本生命周期

```
dev（功能累积）→ rc（冻结候选）→ release（发布）
```

| 阶段 | 规则 |
|:-----|:-----|
| **dev** | 功能/修复经 `/ship` 累积进 main，不设版本边界 |
| **rc** | 功能清单达标 → 冻结：不再加新功能，只修阻塞 bug；跑全量验证 |
| **release** | 验证通过 → `git tag vX.Y.Z` → Release Notes → 部署 |

## 四、发布流程（release 节点）

```
1. 确定版本功能清单（收集 rc 冻结时已合并改动）
2. 全量验证：bash docs/review/guard.sh（守护）+ ./gradlew test（全测试）+ /review --full（5 角色全量）
3. 数据迁移检查：File First 格式是否有变化 → 写迁移说明
4. 写 Release Notes → docs/releases/vX.Y.Z.md
5. 标记：git tag vX.Y.Z（annotated）
6. 部署：规范化后的 deploy.sh（见部署衔接）
```

**验证不通过 → 不发布**：问题记为 P0/P1 修复后再冻结。

## 五、Release Notes 结构

`docs/releases/vX.Y.Z.md`：

```markdown
# v0.1.0 — 首个可发布基线

发布日期：2026-08-XX

## 功能
- 记忆进化 Phase 1-3（类型/主题合并/actionable 闭环）

## 修复
- 审核修复三批（记忆沉淀断裂/复盘走 ContextEngine/规则冲突真实解析…）

## 数据/文件格式变更
- Memory 条目新增 kind/topic/superseded 字段（向后兼容，无需迁移）

## 已知问题
- REVIEW.md #22 kernel 反向依赖（有意搁置）
```

## 六、与现有流程的关系

| 机制 | 角色 |
|:-----|:-----|
| `/ship` | 版本内单个功能/修复的收尾（上游）|
| `/review --full` | **发布前全量验证节点**（下游）|
| `guard.sh` | 发布前守护检查（G1-G7，防 P0 复发）|
| 记忆进化 RFC | v0.1.0 的功能项 |
| `docs/review/REVIEW.md` | 未修复项跟踪，发布前清零 P0/P1 |

**分工**：ship 管"单个功能是否完整"，版本机制管"一批功能是否可发布"，review 管"发布前是否无遗漏"。

## 七、v0.1.0 规划（首个版本）

```
v0.1.0 — 首个可发布基线
├── 功能：记忆进化 Phase 1-3（类型 + 主题合并 + actionable 闭环）
├── 质量：审核修复三批（已提交 cce0b1a/af530f0/c37e251）
├── 重构：#13 interfaces 编排消重复（并入）
├── 部署：deploy.sh 规范化
└── 验证：guard.sh + 全测试 + /review --full → 冻结发布
```

## 八、落地步骤

1. **本 RFC 确认**（draft → accepted）
2. 建 `docs/releases/` 目录 + Release Notes 模板
3. 实施记忆进化 Phase 1-3（v0.1.0 功能，承接 `20260801-memory-system-evolution.md`）
4. 实施 #13 消重复（可选重构）
5. **部署规范化**：建立 `deploy.sh`（构建 bootJar → scp 生产 → 重启），补 CLAUDE.md 部署命令（历史遗留）
6. 全量验证 → 冻结 → 发布 `v0.1.0`（tag + Release Notes）

## 九、约定

- **不引入重型流程**：单人开发，版本机制是"节奏容器"不是"官僚流程"——功能照常 /ship 累积，只在 rc 节点收敛
- **数据格式变化必须进 Release Notes**：File First 下文件即数据契约，格式变更要可追溯
- **发布前 REVIEW.md P0/P1 清零**：未解决项（#13/#19/#22）需明确"发布前处理 or 记录为已知问题"
