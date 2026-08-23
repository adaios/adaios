---
title: state 状态层索引
description: AI 工程状态层目录治理——完成度/测试数/未修项动态真相（指针化，物理文件在 docs/；聚合问答用 guard-* 命令现算）
version: 2
created: 2026-08-15
updated: 2026-08-23
status: active
lines: 70
depends-on: []
related:
  - ../README.md
  - ../../docs/architecture/product-roadmap.md
tags: [ai, meta, index, state]
---

# state 状态层索引

**职责**：AI 工程动态真相——「做到哪了」。物理文件在 `docs/`（单一事实源），本层是指针视图，不复制内容（ADR-002）。

## 状态真相源（指针）

| 状态 | 物理文件 | 维护时机 |
|:-----|:---------|:---------|
| 测试数/端点数/运行环境 | `docs/reference/status.md` | /ship 时更新 |
| 未修项（战略/P0-P2）| `docs/review/REVIEW.md` | /review /audit 后滚动 |
| 批次历史 | `docs/reference/change-log.md` | 每批落地后追加 |
| 产品蓝图 | `docs/architecture/product-roadmap.md` | 路线变更时 |
| 待办（P2/P3）| `docs/reference/task-log.md` | 走查后迁移 |
| 成本日志 | `state/cost-log.jsonl` | guard-cost --record |

## 聚合问答命令（现算不复制，2026-08-23 补齐「三问」）

> 原理：不违反 ADR-002——不复制数据，每次从真相源实时计算，零漂移。

| 你问 | 命令 | 回答 |
|:-----|:-----|:-----|
| 目前进度如何 | `bash ai-engineering/guard-context.sh` | 状态/未修/边界/坑/规范/待办（C1-C6 开工上下文）|
| 已知问题进度 | `bash ai-engineering/guard-unfixed.sh` | 未修全量 + audits 游离 + 对账矛盾（REVIEW/task-log/audits 四源）|
| 未来规划如何 | `bash ai-engineering/guard-roadmap.sh` | 规划新鲜度 + 版本状态 + 漂移检查（S-5 对拍）|
| 成本如何 | `bash ai-engineering/guard-cost.sh` | 按天/会话算钱 + 阈值告警 |
| 工具接入如何 | `bash ai-engineering/guard-tools.sh` | 跨工具互通自检（hook/快照/技能/注册/入口）|

## 漂移检测（状态层自动体检）

| 守护 | 检测什么 |
|:-----|:---------|
| `guard-meta.sh` | frontmatter 图谱/断链/lines/孤儿 |
| `guard-align.sh` | 端点契约 ↔ api-spec（git pre-commit）|
| `guard-unfixed.sh` ④ | 已修复区 vs 表状态矛盾 |
| `guard-roadmap.sh` | 规划 ↔ 实现证据漂移 |
| `guard-cost.sh` | 成本超阈值告警 |

## 本层自身验收状态（RFC 20260815 投影）

| RFC 验收标准 | 状态 |
|:-------------|:----:|
| #1 ai-engineering/ 存在且 guard-meta 覆盖 PASS | ✅ |
| #2 工作流六段都有定义文件（discuss/design/develop + ship/review/audit）| ✅（develop 已补）|
| #3 pitfalls 覆盖 checklists 上次发现 + adr 首批 5-10 条 | 🔄（pitfalls 部分 + adr 5 条）|
| #4 跨项目方法论边界明确 | ✅ |
| #5 任一功能从想法到验收可循完整工作流且有决策痕迹 | ✅ |

> 未过项（#3）→ 后续批次继续补 ADR。本表是 state 层"做到哪了"的自投影。

## 过期判断

- 指针指向的文件不存在 → guard-meta M1 断链（立即修）
- 状态与代码不一致（status.md 数字 vs 实测）→ P1 级待修（D20 测试数三方对拍）
- 快照过期（AGENTS.local.md 早于 REVIEW/status 更新）→ guard-unfixed 会提示 `--write-local` 刷新
- 本层只是视图：**不要在 state/ 复制数字/状态**（违反 ADR-002）
