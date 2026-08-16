---
title: 切入点详解（pipeline）
description: AI 工程流水线每个切入点的职责/脚本/触发/拦截——从 adaios 实践提炼，可复制到新项目
version: 1
created: 2026-08-16
updated: 2026-08-16
status: active
lines: 60
depends-on:
  - README.md
related:
  - scaffold.md
tags: [ai, method, pipeline]
---

# 切入点详解

> 每个切入点 = 一个自动化插入点，把 AI 的"记得"变成机制的"强制"。下表按开发循环顺序。

## 切入点全景

```
                    ┌────────────── 提交循环 ──────────────┐
                    │                                      │
 开发 → [pre-commit] → commit → [post-commit] → push → [pre-push]
              │                              │            │
          拦 4 类                       自动轻量自审      拦 CI 级
     (对齐/结构/隐私/防复发)           (diff 派官)      (全量检查)
                    │
                    ▼
             [部署前门禁] → 部署 → [部署后 smoke] → [定时 audit]
              强制 review            新端点验证          防休眠
```

## 各切入点职责

### 1. pre-commit（提交前）—— 四层门禁 ✅ adaios 已做

| 层 | 脚本 | 拦什么 | 触发 |
|:---|:-----|:-------|:-----|
| 0 隐私 | inline | data/ 真实数据禁止提交（B3）| 任何 data/ 变更 |
| 1 内容对齐 | guard-align A1-A4 | 端点↔api-spec / 测试数↔status / 端点数↔endpoints.txt | 代码/契约文档 |
| 2 结构 | guard-meta M1-M4 | frontmatter 断链/lines/孤儿/正文路径 | 任何 md/java/dart |
| 3 防复发 | guard.sh G1-G7 | P0 数据安全（catch 删除/now() 推路径）| 代码变更 |

```
关键：可被 --no-verify 绕过 → 需 pre-push/部署门禁补漏
```

### 2. post-commit（提交后）—— 轻量自审（补漏）❌ adaios 缺

```
git commit 成功 → 自动跑"轻量 deep"：
  git diff HEAD~1..HEAD → 按 review.md 路由表派 1-2 官 → 发现即报告（不阻塞）
```
- 价值：提交即自审，不用等人想起来跑 /review
- 不阻塞：只报告（坏提交已入历史，靠 pre-push/部署拦截）

### 3. pre-push（推送前）—— 硬闸门 ❌ adaios 缺（单人项目可延后）

```
git push → 全量测试 + guard 全量 + 最近 N 提交 diff 审查
FAIL → 阻止推送（不提交可绕过，推送绕不过）
```

### 4. 部署前门禁 —— 最硬的一道 ❌ adaios 缺

```
部署命令 → 强制跑 review（增量）+ guard 全量 + RFC 验收核验
不过关 → 拒绝部署（部署是用户确认的动作，最不可绕过）
```

### 5. 部署后 smoke —— 自动验证 ❌ adaios 缺

```
部署完成 → curl 新端点 + 核心链路（feed/advice/memory PATCH…）→ PASS/FAIL 报告
```

### 6. 定时 audit —— 防休眠 ❌ adaios 缺

```
cron 每周 → 自动 audit（8 官/3 视角）+ 检查点沉淀 + 失真扫描
无人触发也跑 → 审查不停摆
```

## 实现优先级（从 adaios 提炼）

| 优先级 | 切入点 | 工作量 | 价值 |
|:------:|:-------|:------:|:-----|
| P0 | 部署前门禁 | 小（包装 deploy.sh）| 最硬闸门，不过关不部署 |
| P0 | 部署后 smoke | 小（curl 脚本）| 部署即验证 |
| P1 | post-commit 自审 | 中（diff 派官）| 提交即自审 |
| P2 | 定时 audit | 中（cron）| 防审查休眠 |
| P3 | pre-push | 中（单人可延后）| 协作才必需 |

## 进攻侧（任务前，补防守之不足）

> 防守侧全在拦截错误（提交前检查）；进攻侧让 AI **开工前自动拿到该知道的上下文**，不用人提醒。

| 切入点 | 脚本 | 产出 | adaios 状态 |
|:-------|:-----|:-----|:-----------|
| **pre-task 上下文注入** | guard-context.sh | C1 状态 / C2 未修项 / C3 边界 / C4 坑 / C5 规范 / C6 待办（可按主题过滤）| ✅ 已做 |
| 自动沉淀 | （待建）| 开发中发现坑/决策 → 自动提请 ADR/pitfalls | ❌ |
| 自动出表 | （待建）| 修复完成 → 自动标 REVIEW ✅ 出表 | ❌ |

```
开工前（进攻）                    提交时（防守）
guard-context.sh  → 上下文清单      pre-commit 四层 → 拦截错误
  C1 状态 C2 未修项 C3 边界          隐私/对齐/结构/防复发
  C4 坑 C5 规范 C6 待办
```

## 关键原则

1. **机制 > 内容**：能挂 hook/CI 就不靠文档约定（文档会漂移，机制不会）
2. **分层拦截**：pre-commit 拦"快错"，部署门禁拦"最终"，中间层补漏
3. **可绕过性递减**：`--no-verify` > 跳过 /ship > 部署（最不可绕过）
4. **人只管三件事**：审核内容 / 做决策 / 指方向——重复执行全交给机制
