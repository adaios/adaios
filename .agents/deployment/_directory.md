---
title: deployment/ 目录契约
description: .agents/deployment/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 53
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# deployment/ 目录契约

**职责**：**运维与发布规则**——回答「怎么部署 / 怎么发布 / 怎么合规」。

## 职责边界
- **放**：后端部署 · iOS 发布与 TestFlight · ICP / 公安备案 · 运维前置约束（如 learn 插件单实例）
- **不放**：部署**门禁脚本** → `../scripts/code-deploy-gate.sh` · 部署**事故记录** → `../../docs/records/` · 构建/测试命令 → `../guides/development.md`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 被依赖 | `../scripts/code-deploy-gate.sh` | 门禁与部署后 smoke 的规则出处 |
| 被依赖 | `../guards/ai-guard-release.sh` · `../guards/ai-guard-prod.sh` | 发版体检与生产日报引用本区路径 |
| 引用 | `../records/change-log.md` | 历次部署的批次记录 |
| 引用 | `../reference/status.md` | 当前发布态真相源 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 发布 / 发版 | 人 + AI | `ai-guard-release.sh` 判定 → `code-deploy-gate.sh` |
| 后端上线 | 人 | `backend-deployment.md` |
| iOS 发版 | 人 | `ios-release.md` → `testflight-external-testing.md` |
| 备案办理 / 到期 | 人 | `icp-filing.md` · `gongan-filing.md` · `../guides/routine.md` 到期红线 |

## 约束
- **外向动作须人确认**（原则 B8）：本区文档描述的部署/发布动作，AI **不自行执行**
- **路径引用必须全仓一致**：本区路径被多个守卫脚本引用，改名必须同步改脚本
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 守卫（谁保证这里不腐烂）
- `ai-guard-meta`：frontmatter / lines / 断链 / 孤儿
- `ai-guard-structure`：两件套齐备 · 清单⇄实际 · 契约依赖
- `ai-guard-release` · `ai-guard-prod`：引用本区路径的机制脚本

## 维护动作
1. 新增运维规则 → 补 `_index.md` → 跑 `bash .agents/guards/ai-guard-structure.sh --fix`
2. **改路径必须全仓修引用**（含 `../guards/` 与 `../scripts/` 里的脚本常量）
3. 规程失效 → `status: superseded`，或转入 `../../docs/archive/`
