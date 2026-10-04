---
title: deployment/ 目录索引
description: .agents/deployment/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 38
depends-on: []
related: [./_directory.md]
tags: [meta, index, deployment]
---

# deployment/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**运维与发布规则**——回答「怎么部署 / 怎么发布 / 怎么合规」。与 `.agents/scripts/code-deploy-gate.sh`（部署门禁）同属一个体系。

## 文件清单（5 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `backend-deployment.md` | 后端部署（deploy.sh + 生产环境 / 配置项真值表 / learn 插件单实例硬约束） | active |
| `gongan-filing.md` | 公安联网备案办理清单（材料 / 六步流程 / 通过后挂载点；**✅ 2026-09-24 已通过并挂载**） | active |
| `icp-filing.md` | 域名备案（ICP）资料整理与填报指引（adaiadai.com） | active |
| `ios-release.md` | iOS 发布（TestFlight）：分发签名方案 + 一条命令发版 + 到期与应急 | active |
| `testflight-external-testing.md` | TestFlight 外部测试：邀请外人流程 + Beta 审核备注模板 + 测试员须知 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`ai-guard-structure` S2；新增文件后跑 `bash .agents/guards/ai-guard-structure.sh --fix`）

## 来源

2026-10-04 目录归纳批：自 `docs/deployment` 移入 5 份**运维规则**；同目录的 `serve_static.py` → `../scripts/`（脚本归位）、`testflight-beta-contract-missing.md` → `../../docs/records/`（**它是问题记录，不是规程**）。判据＝**按性质**。
