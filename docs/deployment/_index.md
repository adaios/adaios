---
title: docs/deployment 目录索引
description: deployment 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-10-01
status: active
lines: 34
depends-on: []
related:
  - ../_index.md
tags: [meta, index, deployment]
---

# docs/deployment 目录索引

**职责**：部署文档区——后端/前端部署操作说明。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| backend-deployment.md | 后端部署（deploy.sh + 生产环境） | active |
| ios-release.md | iOS 发布（TestFlight）：绕开云签名的分发签名方案 + 一条命令发版 | active |
| testflight-external-testing.md | TestFlight 外部测试：邀请外人使用流程 + Beta 审核备注模板 + 测试员须知 | active |
| icp-filing.md | 域名备案（ICP）资料整理与填报指引（adaiadai.com） | active |
| gongan-filing.md | 公安联网备案办理清单：材料 / 六步流程 / 通过后挂载点（**✅ 2026-09-24 已通过并挂载**；原法定截止 2026-09-30） | active |
| testflight-beta-contract-missing.md | TestFlight Beta 合同缺失（全线不可用）处置：2026-09-29 复核取证 + **2026-09-30 复核（§1.8：合同仍缺失 · 构建 13 已被作废 · 仅构建 14 有效）+ 2026-10-01 工单回信 + 电话确认与复核（§1.9：Apple 要求 48h 后传新构建、实测合同仍 null、决定照做且到点先验；§1.10：「为什么是 48 小时后传新构建」的人话解释）** + 苹果客服路径 + 可直接粘贴的中英文工单材料（对应 REVIEW P1-发布1） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
