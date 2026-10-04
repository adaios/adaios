---
title: infra/ 说明
description: 基础设施配置目录——当前仅 docker-compose.yml（本地编排）；与生产部署文档的分工，以及"何时该删掉本目录"
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 1
depends-on: []
related:
  - ../.agents/deployment/backend-deployment.md
tags: [infra]
---

# infra/

**职责**：基础设施**编排配置**（既非文档，也非业务代码）。

> **为何有这个 README**：2026-10-04 文档体检发现本目录此前**孤立、无说明、无人引用**——这正是"凌乱"的一种形态。补上定位，或明确它该死。

## 现状

| 文件 | 用途 |
|:--|:--|
| `docker-compose.yml` | 本地容器编排定义 |

## 与生产部署的分工

- **生产部署规程**（服务器 / systemd / 端口 / 配置真值表）见 [`../.agents/deployment/backend-deployment.md`](../.agents/deployment/backend-deployment.md)
- 本目录只放**编排定义本身**，**不复制部署步骤**（单一权威来源）

## 处置建议（待定）

若本项目已不再使用容器编排（当前形态是**单机 systemd 部署**），应**连同本目录一并删除**——而不是留一个无人引用的空壳。此判断需人拍板。
