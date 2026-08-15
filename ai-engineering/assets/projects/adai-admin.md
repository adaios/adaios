---
title: 项目资产：adai-admin（管理端）
description: adai-admin 项目资产卡——纯系统治理端定位/模块划分/职责边界（收敛中）；改 admin 前先读本卡
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 52
depends-on:
  - ../frontmatter-spec.md
  - ../conventions.md
  - ../boundaries.md
related:
  - ../projects/adai-app.md
  - ../projects/adai-web.md
tags: [ai, assets, project, admin]
---

# 项目资产：adai-admin（管理端）

> **定位**：**纯系统治理端**（类企业管理系统）——账号/数据/系统/知识四区管理。**不做个人内容编辑**（收敛中，P-role 系列）。改 admin 前先读本卡 + boundaries.md。

## 模块划分（4 区 17 页）

```
adai-admin（Flutter Web）
├── 账号区（accounts）：账号 CRUD + 插件门控
├── 数据区（data）：文件树 / 记录 / 记忆 / 档案 / 任务 / 持仓（治理视角）
├── 系统区（system）：Feed 预览 / 行情 / 复盘 / 冲突检测 / 维护
└── 知识区（knowledge）：os 资产树 / 术语规则
```

## 功能清单

| 区 | 页面 | 职责 | 属权 |
|:---|:-----|:-----|:----:|
| 账号 | accounts_page | 账号创建/禁用/角色/插件门控 | ✅ 治理（有鉴权）|
| 数据 | data_page + 6 tab | 文件树/记录/记忆/档案/任务/持仓**浏览与治理** | ⚠️ 编辑类应收敛 |
| 系统 | system_page + 5 tab | Feed 预览/行情/复盘/冲突/维护 | ✅ 治理 |
| 知识 | knowledge_page + 2 tab | os 资产树/术语规则浏览 | ✅ 治理 |

## 职责边界（收敛目标）

| 属权 | 内容 |
|:-----|:-----|
| **admin 拥有（治理写）** | 账号 CRUD、插件门控、数据清理/重建/重补、知识正式入库、系统配置、冲突检测 |
| **admin 拥有（读）** | data/ 文件树、os/ 知识资产、Feed/记忆/持仓浏览（只读视角）|
| **admin 应移除（→用户端）** | 档案编辑 PUT /identity、记忆修正 PATCH /memory/{id}（app/web 补上）、任务 CRUD、记录删除——个人内容写归 app/web（P-role-01/02/03/04）|
| **admin 注意** | 复盘反哺入库（promote 后确认入库）是治理职责，生成属用户业务 |

## API 依赖

治理端点（/admin/**、/accounts/** 有令牌保护）：accounts CRUD、admin files/knowledge 浏览。
⚠️ **P-be-01（安全）**：6 个维护端点（records/retry、memory/rebuild、memory/{id}、cards/cleanup、has-activity、knowledge/conflicts）**不在管理鉴权下**——待修（移 /admin/** 或加鉴权）。

## 与其他端关系

- **app/web**：admin 是治理端，不编辑用户个人内容（收敛中）；用户数据写全在 app/web
- **core**：admin 调用 core 治理端点，不应绕过鉴权

## 已知问题（P-role 系列）

- 🔴 P-be-01：维护端点鉴权缺失（安全）
- ⚠️ P-role-01/03/04：档案/记录/任务编辑重复（收敛）
- ⚠️ P-role-10：admin api_service 保留 POST /records 无调用（删）
- ⚠️ P-role-11：admin 顶栏可切任意 userId 操作个人数据（多用户化时越权面）

---
**变更规则**：新增治理功能 → 更新本卡 + _index.md；**任何个人内容编辑入口新增前先问「这该在 app/web 还是 admin」**（边界原则）。
