---
title: docs/review 目录索引
description: review 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-09-13
status: active
lines: 47
depends-on: []
related:
  - ../_index.md
tags: [meta, index, review]
---

# docs/review 目录索引

**职责**：审核结果区——未修项滚动区 + 走查存档。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| REVIEW.md | 审核全量状态报告（未修项滚动区） | active |
| audits/2026-09-13-trading-ledger-dirty-rows.md | 交易账本存量脏流水逐笔核对存档（全量 1707 笔重判：真重复 6 组非 4 笔 + 负持仓同根因 + 粗暴去重反例 25 只负持仓；只报告未改数据，处置待拍板） | active |
| audits/2026-09-05-cognition-layer-review.md | 交易⑤认知层批三官深审存档（backend/docs/adversarial 隔离并行：P1×5 + 💥×6 + docs×6 全修出表，守护 G2 一并修） | active |
| audits/2026-09-06-admin-uiux-review.md | adai-admin 管理后台 UI/UX 专项审查存档（ui/ux 双官隔离并行：P1×4 成立 + P1×1 误报排除 + P2×25 + P3×31，修复待拍板） | active |
| audits/2026-09-09-daily-pnl-review.md | 当日盈亏精确计算三官深审存档（backend/frontend/adversarial 隔离并行：⭐⭐ 交叉命中 T+1 成本配比算术缺陷，修复批已落地 audits 同日期 + REVIEW 头部登记；未修三项排后续） | active |
| audits/2026-09-07-learn-v2-review.md | learn V2 消化闭环增量深审存档（backend/frontend/docs/adversarial 四官隔离：战略×2 + P1×4 + P2×11 + P3×3，0 修复只报告） | active |
| audits/2026-09-05-memory-fidelity-audit.md | 记忆失真审计基线（只读：kind 失衡/无卡对话原话缺失/抽样加料过半 → memory-fidelity.md 修订 + 写侧保真立项） | active |
| audits/2026-08-30-case-library-data-review.md | 案例库数据批次审查存档（降级主会话：KDJ latest 回归/搜索竞态已修 + P3 登记） | active |
| audits/2026-08-30-case-library-review.md | 完美买点案例库批次审查存档（降级主会话审：S1 save 双文件回滚已修 + P1×1/P2×5/P3×1 登记） | active |
| audits/2026-08-30-trading-rule-layer-review.md | 交易插件规则层审查存档（四官 + 对抗官隔离，P0×2 信任炸弹 + 硬约束 fail-open，待拍板降级语义） | active |
| audits/2026-08-25-lot-tracking-review.md | RFC 20260825 批次跟踪批审查存档（四官隔离，对抗 P0-1 交叉防重实锤修复） | active |
| audits/2026-08-23-ai-engineering-meta-audit.md | AI 上下文建设工程体系元审核存档（主审核 + 对抗官复核 + 实证实验，修复批 072dcee） | active |
| audits/2026-08-23-reviewer-isolation-demo.md | 隔离审查演示存档（对抗官首战 + 上下文隔离，交易归集批残留） | active |
| audits/2026-08-20-app-health-check.md | app 全面体检走查存档（用户体感导向，4 官） | active |
| audits/2026-08-15.md | 首轮全维度走查存档（7 官） | active |
| audits/2026-08-15-ai-engineering-self.md | AI 工程层自伤自查存档（8 官） | active |
| audits/2026-08-16-ai-engineering-workflow.md | AI 工程工作流自伤自查存档（第二轮，三视角） | active |
| audits/2026-08-18-production-log.md | 生产日志审查存档（2026-08-18 journalctl 当日问题分级） | active |
| audits/2026-08-24-ai-calling-governance-doc-review.md | AI 调用治理方案文档深审存档（docs/backend/frontend/adversarial 四官隔离，S-9/S-10 登记） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
