---
title: AdaiOS 档案馆索引（docs/）
description: docs/ 的定位与导航——档案馆（历史 / 存档 / 对外 / 未定型）；AI 运行需要的文档一律在 .agents/；含归属判定表与两批归纳的迁移映射
version: 4
created: 2026-08-15
updated: 2026-10-04
status: active
lines: 1
depends-on:
  - _index.md
related:
  - ../AGENTS.md
tags: [meta, index, docs]
---

# AdaiOS 档案馆（`docs/`）

> **一句话**：**`docs/` = AI 上下文运行时不需要的部分**——历史、存档、对外材料、未定型想法。
>
> **要认识项目 / 参与开发 / 找规则，请看根 [`AGENTS.md`](../AGENTS.md)**（任何工具的第一入口，它指向两个容器）。

---

## 🧭 归属判定表（唯一判据）

**这份文档，AI 在干活时会读 / 写它吗？**

| 答案 | 去向 | 内容 |
|:--|:--|:--|
| **会** | **`.agents/`** | **① 方向**（VISION · roadmap）· **② 事实**（api-spec · status · 架构设计 · 手册 · 功能主轴）· **① 决策**（RFC · ADR）· **③ 规则**（规范 · 边界 · 流程 · 指南 · 运维）· **④ 活账本**（REVIEW · change-log · task-log）· **⑤ 机制**（guards · scripts） |
| **不会** | **`docs/`（本区）** | **史**（archive · records）· **研究材料**（research）· **未定型想法**（ideas）· **对外材料**（legal） |

**判定示例**：

| 你手上的东西 | AI 干活时读吗 | 去向 |
|:--|:--:|:--|
| 端点契约表 | ✅ 写接口必查 | `.agents/knowledge/reference/api-spec.md` |
| 未修项清单 | ✅ 每次开工读 | `.agents/records/REVIEW.md` |
| 项目愿景 | ✅ 每次会话首读 | `.agents/direction/VISION.md` |
| Git 工作规范 | ✅ 提交前查 | `.agents/rules/guides/git-workflow.md` |
| 某次走查的报告 | ❌ 考古时才看 | `docs/records/audits/` |
| 竞品调研 | ❌ 一次性 | `docs/research/` |
| 隐私政策正文 | ❌ 对外提交用 | `docs/legal/` |
| 一个还没定的想法 | ❌ 未定型 | `docs/ideas/` |

---

## 📚 本区内容（5 个区）

| 区 | 说明 |
|:--|:--|
| [**archive/**](archive/_index.md) | 🗄 退役文档——3 份 `superseded` RFC + 早期 AI Context 模板 + project-os-usage |
| [**records/**](records/_index.md) | 🗃 历史存档——走查存档 27 份 · 事故记录（TestFlight 合同缺失）· 发布史 · 归档问题清单 |
| [**research/**](research/_index.md) | 🔬 研究 · 调研 · 诊断材料（记忆方案借鉴 / 记忆保真诊断 / 交易日志竞品 / 风险计划） |
| [**ideas/**](ideas/_index.md) | 💡 未定型但有价值的想法（成熟后升级为 `.agents/direction/rfc/`） |
| [**legal/**](legal/_index.md) | ⚖️ 对外材料——隐私政策正文 |

---

## 🗺 2026-10-04 两批归纳 · 迁移映射

### 一批：判据由「按读者」改为「按性质」

| 旧路径 | 新路径 |
|:--|:--|
| `docs/guides/`（7 份工程规则） | `.agents/rules/guides/` |
| `docs/deployment/`（5 份运维规则） | `.agents/rules/deployment/` |
| `docs/deployment/serve_static.py` | `.agents/mechanism/scripts/serve_static.py` |
| `docs/architecture/ai-context-engineering.md` · `ai-calling-governance.md` | `.agents/rules/assets/` |
| `docs/inbox/` | 已删除（空壳） |

### 二批：判据收紧为「AI 上下文运行时是否需要」

| 旧路径 | 新路径 |
|:--|:--|
| `docs/VISION.md` | `.agents/direction/VISION.md` |
| `docs/architecture/product-roadmap.md` | `.agents/direction/product-roadmap.md` |
| `docs/architecture/`（13 份：api-spec · data-format-freeze · product/system-architecture · framework-plus-plugin-model · frontend-reference · memory-os-design · trading-* 5） | `.agents/knowledge/reference/` |
| `docs/reference/`（6 份：status · feature-reference · trading-features · admin-features · framework-plugin-gap · task-plugin-model） | `.agents/knowledge/reference/` |
| `docs/features/`（功能主轴） | `.agents/knowledge/features/` |
| `docs/records/{change-log,task-log}.md` · `docs/review/REVIEW.md` | `.agents/records/` |
| `docs/rfc/`（66 份生效决策） | `.agents/direction/rfc/` |
| `docs/rfc/`（3 份 `superseded`） | `docs/archive/` |
| `docs/review/audits/`（27 份走查存档） | `docs/records/audits/` |
| `docs/review/guard.sh` | `.agents/mechanism/guards/guard.sh` |
| `docs/releases/`（2 份） | `docs/records/`（release-v1.0.0 · release-template） |
| `docs/architecture/{memory-frameworks-borrow,memory-fidelity}.md` · `docs/reference/{trading-journal-benchmark,trading-risk-plan}.md` | `docs/research/` |
| `docs/records/issue-log.md` · `docs/deployment/testflight-beta-contract-missing.md` | `docs/records/`（存档） |

---

**最后更新：2026-10-04（二批：`docs/` 定为档案馆 · 12 区 → 5 区）**
