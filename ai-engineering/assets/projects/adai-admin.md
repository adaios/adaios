---
title: 项目资产：adai-admin（管理端）——用户视角功能全景
description: 从管理员使用角度，具体到每个页面每个功能地描述 adai-admin——四区模块/每个 tab 能做什么/职责边界/与 app-web 关系
version: 1
created: 2026-08-15
updated: 2026-08-16
status: active
lines: 92
depends-on:
  - ../../frontmatter-spec.md
  - ../conventions.md
  - ../boundaries.md
related:
  - ../projects/adai-app.md
  - ../projects/adai-web.md
tags: [ai, assets, project, admin, user-view]
---

# 项目资产：adai-admin（管理端）——用户视角功能全景

> **定位**：**纯系统治理端**（类企业管理系统）。管理员通过它管理账号/数据/系统/知识——不是个人使用端（个人用 app/web）。**数据全部真实后端**（经 ApiService 调 api/v1，带 X-Admin-Token），无 mock（2026-08-16 已清误导 MOCK 徽标）。

---

## 一、进入 admin

- 打开 `localhost:8083` → 选用户（admin 顶栏可切换 userId，按用户维度看数据）
- 管理操作需 `X-Admin-Token`（构建时 `--dart-define=ADMIN_TOKEN` 注入，与后端 `ADAI_ADMIN_TOKEN` 一致）

## 二、四个区（管理员视角）

### ① 账号区（accounts_page）— 管理谁能用

| 功能 | 管理员操作 | 结果 |
|:-----|:----------|:-----|
| 账号列表 | 查看全部账号（userId/角色/启用/插件）| 真实 /accounts |
| 创建账号 | 填 userId → 建号 | 新增账号（无注册，后台建）|
| 启用/禁用 | toggle | 账号可登录/不可登录 |
| 插件门控 | 开关 trading/project 插件 | 用户 app/web 模块显隐 |
| 删除账号 | 确认弹窗 → 删 | 不可撤销（内置管理员保护）|

### ② 数据区（data_page）— 按用户看数据（治理视角）

| Tab | 管理员能做什么 | 备注 |
|:----|:--------------|:-----|
| 文件树 | 浏览 data/ 目录结构、看文件内容 | 治理浏览 |
| 记录 | 看某用户记录列表、**删除记录** | ⚠️ 删除属治理（用户端已能删，admin 收敛中）|
| 记忆 | 看记忆、**修正记忆内容** | ⚠️ P-role-02：修正应属用户端（app 补上中）|
| 档案 | 看/编辑个人档案 | ⚠️ P-role-01：编辑应属用户端 |
| 任务 | 看/建/删任务 | ⚠️ P-role-04：CRUD 应属用户端 |
| 持仓 | 看持仓（只读治理）| ✅ 治理视角正确 |

### ③ 系统区（system_page）— 系统运维

| Tab | 管理员能做什么 | 备注 |
|:----|:--------------|:-----|
| Feed 预览 | 看某用户今日 Feed | 只读预览 |
| 行情 | 看行情快照 | 只读 |
| 复盘 | 日期列表 + **生成/查看/反哺入库** | ✅ 治理（生成属用户业务，入库确认属 admin）|
| 维护 | **记忆重建 / 记忆重补 / 卡片清理** | ✅ 已迁入 /admin/** 鉴权（P-be-01 已修）|

### ④ 知识区（knowledge_page）— 知识资产治理

| Tab | 管理员能做什么 | 备注 |
|:----|:--------------|:-----|
| os 资产树 | 浏览 os/trading-os 等知识文件 | 只读治理 |
| 术语规则 | 看术语/规则 | 只读 |

## 三、职责边界（admin 属权）

| 属权 | 内容 |
|:-----|:-----|
| **admin 拥有（治理写）** | 账号 CRUD、插件门控、数据清理/重建/重补、复盘反哺入库确认、系统配置 |
| **admin 拥有（读）** | data/ 文件树、os/ 知识资产、Feed/记忆/持仓浏览（治理视角）|
| **admin 已收敛（→用户端）** | ✅ 档案编辑/记忆修正/任务 CRUD/记录删除已移除（2026-08-16）；app 已补记忆修正 |
| **admin 无 mock** | 全部真实后端（2026-08-16 清除 MOCK 徽标）|

## 四、与其他端关系

- **app/web**：个人数据写在用户端；admin 只做治理。**边界原则**：个人数据写只属用户端；系统级写只属 admin
- **core**：admin 调管理端点（/admin/**、/accounts/** 有令牌）；⚠️ 6 个维护端点（rebuild/retry/cleanup 等）不在令牌保护下（P-be-01 安全待修）

## 五、已知问题（待修）

- ✅ **P-be-01（已修 2026-08-16）**：5 维护端点已迁入 /admin/**（X-Admin-Token + userId 查询参数）；has-activity 保留产品路径
- ✅ **P-role-01/02/03/04（已收敛 2026-08-16）**：个人内容编辑已从 admin 移除；app 补记忆修正（P-role-02 用户端能力）
- ⚠️ **P-role-10**：admin api_service 保留 POST /records 无调用（休眠越权面，删）
- ⚠️ **P-role-11**：顶栏可切任意 userId 操作其个人数据——单 owner 成立，多用户化是隐私越权面

## 六、变化规则

新增治理功能 → 更新本卡 + _index.md；**任何个人内容编辑入口新增前先问「这该在 app/web 还是 admin」**（边界原则）。admin 改动走 serve_web.sh `<API_BASE_URL> <ADMIN_TOKEN>` 构建。
