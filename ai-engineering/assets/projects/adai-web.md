---
title: 项目资产：adai-web（桌面端）
description: adai-web 项目资产卡——模块划分/职责边界/与 app 的关系；改 web 前先读本卡
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 48
depends-on:
  - ../frontmatter-spec.md
  - ../conventions.md
  - ../boundaries.md
related:
  - ../projects/adai-app.md
  - ../projects/adai-admin.md
tags: [ai, assets, project, web]
---

# 项目资产：adai-web（桌面端）

> **定位**：同一产品（AdaiOS）的桌面形态——独立工程、两栏壳 + 8 模块（参考元宝电脑端），**与 adai-app 是两形态非两产品**。深色模式、Web 优先。改 web 前先读本卡 + boundaries.md。

## 模块划分（9 页面）

```
adai-web（Flutter Web 独立工程）
├── 两栏壳：左侧模块导航 + 右侧内容
├── 页面：feed / memory / timeline / search / profile
│        project / task / trading / account_select
└── 与 app 共用后端 40 产品端点（非适配，独立实现）
```

## 功能清单

| 模块 | 职责 | 与 app 关系 |
|:-----|:-----|:-----------|
| Feed 对话流 | 记录/对话主界面 | 同功能桌面形态 |
| 记忆 / 时间线 / 搜索 / 档案 | Kernel 查看类 | 同功能桌面形态 |
| 项目 / 任务 | project 域 | 同功能桌面形态 |
| 交易 | trading 插件 | 同功能桌面形态 |
| 账号选择 | 多账号 | 同 app |

## 职责边界

| 属权 | 内容 |
|:-----|:-----|
| **web 拥有（写）** | 个人记录/对话/档案/记忆完成/任务/交易记录（个人数据写，与 app 同权）|
| **不属于 web** | 账号创建/禁用、数据清理/重建、知识正式入库、系统配置（→ admin）|
| **web 注意** | promote 反哺写 os/ 缺治理环节（P-role-09）——生成可做，入库确认归 admin |

## API 依赖

与 adai-app **完全一致**（40 产品端点共用）：records / media / conversations / feed / timeline / search / memory / identity / positions / project / trading。

## 与 app 的差异（有意为之）

- 布局形态不同（两栏 vs 单页双世界）——非适配，独立实现
- 桌面端历史可达性（Feed 契约只今天，web 有分页/历史入口）

## 已知问题（来自 app-polish 审查）

- ⚠️ 档案编辑 PUT /identity 与 admin 重复（admin 收敛后归 web+app）
- ⚠️ promote 直写 os/ 缺治理确认环节

---
**变更规则**：新增/移除模块 → 更新本卡 + _index.md；与 app 的功能对拍（F25/F37 双端一致）是 web 特有检查点。
