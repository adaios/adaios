---
title: adai-admin 管理后台规划（草案）
date: 2026-08-02
status: draft
---

# adai-admin 管理后台规划

## 一、背景与动机

AdaiOS 数据积累到一定程度（records / memory / tasks / positions），需要一个管理后台：

- `adai-app` 是日常使用入口（记录 / 问答 / Feed），**不是数据管理工具**
- 单人开发者有时需要直接查看、修正数据（File First 文件直改 vs 界面管理）
- 数据量增长后需要批量操作与可视化管理（记忆进化 5 Phase 落地后，memory 数据值得专门视图）

## 二、定位（候选，待确认）

> **adai-admin = AdaiOS 的"数据 + 系统管理端"，面向本人（开发者 / 拥有者），非日常使用端。**

| 维度 | 候选 |
|:-----|:-----|
| 管理什么 | 数据（records/memory/identity/tasks/positions）+ 系统（行情/复盘/知识反哺）|
| 谁用 | 本人（solo dev）；非终端用户 |
| 与 adai-app 关系 | 互补：app = 日常使用，admin = 数据管理 |
| 与 adai-core 关系 | 复用（Modular Monolith，admin 端点扩展）|

## 三、范围候选

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

## 四、技术选型（候选，待确认）

| 项 | 候选 A（复用，推荐）| 候选 B（独立）|
|:---|:------------------|:------------|
| 后端 | adai-core 扩展 admin 端点（Modular Monolith 原则）| 独立 admin 服务（违反"不提前微服务化"）|
| 前端 | Flutter（复用 adai-app 设计系统/组件）| 轻量 Web（React/Vue）|
| 鉴权 | 本机 / 单用户（无鉴权或简单 token）| 简单登录 |

**建议候选 A**：符合 Modular Monolith、单人使用、最小成本，与现有体系一致。

## 五、与现有机制的关系

- 新 API 端点登记 `api-spec.md`（ship 流程保证）
- 归产品路线 **v1.0.0 工程基建候选**（roadmap §3.4 adai-admin ⬜）
- 不违背"入口统一"原则：admin 是**管理入口**，非日常输入口（`POST /records` 仍唯一日常入口）

## 六、落地方案（Phase 候选）

```
Phase 0：本 RFC 确认方向
Phase 1：数据管理基础（records / memory 浏览编辑）
Phase 2：系统状态 + 操作台（重建 / 重补 / 复盘）
Phase 3：知识浏览
```

## 七、待确认点

1. **范围**是否对（数据 + 系统 + 知识）？
2. **技术选型** A（复用 adai-core + Flutter）还是 B（独立）？
3. **优先级**：先做哪个 Phase（建议 Phase 1 数据管理基础）？
4. 是否作为 **v0.3.0 或 v1.0.0 候选**（roadmap 待定项）？
