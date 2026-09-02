---
title: 项目资产：adai-core（后端核心）
description: adai-core 项目资产卡——分层/模块/端点分布/鉴权边界；改 core 前先读本卡
version: 1
created: 2026-08-15
updated: 2026-08-16
status: active
lines: 82
depends-on:
  - ../../frontmatter-spec.md
  - ../conventions.md
  - ../boundaries.md
related:
  - ../projects/adai-app.md
  - ../projects/adai-web.md
  - ../projects/adai-admin.md
tags: [ai, assets, project, core]
---

# 项目资产：adai-core（后端核心）

> **定位**：唯一核心运行时（Java 17 + Spring Boot，Modular Monolith）。Kernel（Context/Memory/Knowledge）+ Domain OS（trading/life/project）。改 core 前先读本卡 + boundaries.md + conventions.md。

## 分层（依赖方向）

```
interfaces → application → domain/kernel ← infrastructure
```

- Kernel 内 Record → Timeline → Context → Memory 数据流水线
- Domain 间**不允许**直接依赖；跨域经 application 编排
- infrastructure 实现 kernel/domain 接口（依赖倒置）

## 模块（包结构）

| 包 | 职责 |
|:---|:-----|
| kernel | 系统域：record/timeline/context/memory/knowledge/identity |
| domain | Domain OS：trading/life/project（挂载插件）|
| application | 编排层：Feed/Trading/Media/Retry 等服务 |
| interfaces | HTTP 层：16 Controller / 55 端点 |
| infrastructure | 文件存储/AI 集成/配置实现 |

## 端点分布（55 个，16 Controller）

| 域 | 端点 | 使用方 |
|:---|:-----|:-------|
| 产品核心（40）| records/media/conversations/feed/timeline/search/memory/identity/positions/project | app+web（共用）|
| 管理（11）| /admin/**、/accounts/** | admin（有令牌）|
| 维护端点（5）| /admin/records/retry、/admin/memory/rebuild、/admin/memory/{id}、/admin/cards/cleanup、/admin/trading/knowledge/conflicts | admin 独有，**已入 /admin/** 鉴权（P-be-01 已修）|
| 死端点（3）| ai-logs/cards-migrate/memory-record | 无调用（待清）|

## 鉴权边界（重要）

| 路径 | 保护 |
|:-----|:-----|
| /admin/**、/accounts/** | AuthFilter 统一登录 + role=admin（REVIEW #178；X-Admin-Token 退役）|
| /accounts/available、/me/plugins | 有意无鉴权（最小集 + 模块显隐，白名单）|
| **其余全部** | 仅 X-User-Id header 隔离，**无服务端身份认证** |

✅ **P-be-01（已修 2026-08-16）**：5 个维护端点已迁入 /admin/**（#178 后登录 + role=admin 门禁，X-Admin-Token 退役）；has-activity 保留产品路径（app 复盘横幅，只读）。

## 职责边界

| 属权 | 内容 |
|:-----|:-----|
| **core 提供** | 统一输入入口 POST /records（唯一）、Context Engine、AI 集成、文件存储 |
| **core 不做** | 前端 UI、账号身份认证（当前仅 header 隔离，多用户化时需服务端认证）|

## 与其他端关系

- app/web 共用 40 产品端点（正确设计）
- admin 调管理端点（/admin/** 鉴权）；has-activity 产品路径（app 用）

## 已知问题

- ✅ P-be-01：已修（维护端点入 /admin/** 鉴权）
- ⚠️ P-be-02：admin 复用 POST/DELETE /records 无管理鉴权
- ⚠️ P-be-06：3 死端点（ai-logs 未接线 / cards-migrate / memory-record）

---
**变更规则**：新增端点 → 更新 api-spec.md + 本卡端点表 + status.md；鉴权变化 → 更新本卡鉴权边界。
