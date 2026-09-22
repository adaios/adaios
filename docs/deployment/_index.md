---
title: docs/deployment 目录索引
description: deployment 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-09-21
status: active
lines: 33
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
| gongan-filing.md | 公安联网备案办理清单：材料 / 六步流程 / 通过后挂载点（法定截止 2026-09-30） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
