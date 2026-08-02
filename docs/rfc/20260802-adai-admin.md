---
title: adai-admin 管理后台规划
date: 2026-08-02
status: approved
implementedIn: v1.0.0（与多账号功能层合并，2026-08-02 确认方向）
---

# adai-admin 管理后台规划

## 一、背景与动机

AdaiOS 数据积累到一定程度（records / memory / tasks / positions），需要一个管理后台：

- `adai-app` 是日常使用入口（记录 / 问答 / Feed），**不是数据管理工具**
- 单人开发者有时需要直接查看、修正数据（File First 文件直改 vs 界面管理）
- 数据量增长后需要批量操作与可视化管理（记忆进化 5 Phase 落地后，memory 数据值得专门视图）

## 二、定位（已确认）

> **adai-admin = AdaiOS 的"账号 + 数据 + 系统管理端"，面向本人（开发者 / 拥有者），非日常使用端。**

| 维度 | 决策（2026-08-02）|
|:-----|:-----|
| 管理什么 | **账号**（用户 CRUD）+ 数据（records/memory/identity/tasks/positions）+ 系统（行情/复盘/知识反哺）|
| 谁用 | 本人（solo dev）；非终端用户 |
| 与 adai-app 关系 | 互补：app = 日常使用，admin = 账号/数据管理 |
| 与 adai-core 关系 | 复用（Modular Monolith，admin 端点扩展）|
| 技术选型 | **A**：复用 adai-core 端点 + Flutter（复用 adai-app 设计系统/组件）|
| 版本归属 | **v1.0.0**，与多账号功能层合并为一条关键路径 |

**关键定位：adai-admin 是 v1.0.0「账号由后台管理系统管理」的实现载体。** 多账号功能层（简单登录、不做注册）的账号管理界面就是 adai-admin 的一部分——两者一体两面。

## 三、范围（已确认）

### 3.0 账号管理（v1.0.0 关键路径，Phase 0）
- 账号 CRUD：创建 / 禁用 / 删除用户（**不注册**，管理员后台建号）
- 账号与数据分层联动：账号驱动 `data/{userId}/` 数据层（多账号架构预留已就绪）
- 登录状态：前端登录 → 携带 `X-User-Id`（预留全链路已就绪，只需功能层）

### 3.1 数据管理
- records 浏览 / 编辑 / 删除 / 批量
- memory 浏览（kind / superseded / 待办视图）+ 手动修正、标记完成
- identity / tasks / positions 管理
- `data/` 文件树浏览（File First 可视化）

### 3.2 系统状态
- Feed 预览（含 action / market 条目）
- 行情快照 / 复盘列表 / 知识反哺（promote / conflicts）操作台
- 记忆重建 / 重补 / 清理触发（`/memory/rebuild` 等）

### 3.3 知识管理
- `os/` 知识资产浏览（trading-os / life-os / project-os）
- 术语 / 规则查看

## 四、技术选型（已定 A）

| 项 | 决策 |
|:---|:-----|
| 后端 | adai-core 扩展 admin 端点（Modular Monolith 原则，不独立微服务）|
| 前端 | Flutter（复用 adai-app 设计系统/组件，单人维护成本最低）|
| 鉴权 | 本机 / 单管理员（admin 面向本人；账号管理做进去后自然带登录）|

## 五、与现有机制的关系

- 新 API 端点登记 `api-spec.md`（ship 流程保证）
- 归产品路线 **v1.0.0 关键路径**（roadmap §3.4 adai-admin ⬜→📋）
- 不违背"入口统一"原则：admin 是**管理入口**，非日常输入口（`POST /records` 仍唯一日常入口）
- **多账号关联**：adai-admin 账号管理 ↔ v1.0.0 多账号登录，共享 `data/{userId}/` 分层与 `X-User-Id` 链路（`20260802-multi-account-prep.md`）

## 六、落地方案（Phase 顺序已确认）

```
Phase 0：账号管理（v1.0.0 多账号依赖，与登录一体）→ 数据/系统/知识管理的前置
Phase 1：数据管理基础（records / memory 浏览编辑）
Phase 2：系统状态 + 操作台（重建 / 重补 / 复盘）
Phase 3：知识浏览
```

## 七、决策记录（2026-08-02，方向确认）

| 待确认点 | 决策 |
|:---------|:-----|
| 范围 | 纳入账号管理（承担 v1.0.0「后台管账号」载体）✅ |
| 技术选型 | A：复用 adai-core + Flutter ✅ |
| 优先级 | Phase 0 账号管理先行（多账号依赖）✅ |
| 版本归属 | v1.0.0，与多账号一起做 ✅ |
