---
title: 项目资产：adai-app（移动端）
description: adai-app 项目资产卡——模块划分/功能清单/职责边界/API 依赖/与其他端关系；任何改 app 前先读本卡
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 70
depends-on:
  - ../frontmatter-spec.md
  - ../conventions.md
  - ../boundaries.md
related:
  - ../projects/adai-web.md
  - ../projects/adai-admin.md
  - ../../../apps/adai-app/DESIGN.md
tags: [ai, assets, project, app]
---

# 项目资产：adai-app（移动端）

> **定位**：个人日常使用端（手机）——「记录今天，理解过去，帮助未来」。核心价值是**记录**，所有功能服务「记录→理解→帮助」闭环。改 app 前先读本卡 + boundaries.md + conventions.md。

## 模块划分（当前结构）

```
adai-app（Flutter，单页哲学 → 已演化为双世界）
├── World A（MainPage）：今日对话流——Feed 卡片流 + 输入栏 + 对话态
├── World B（LauncherPage）：导航中枢——功能入口 + 概览 + 标签宇宙
├── 页面：account_select / profile / memory / timeline / search
│        project_status / project_task / trading
└── 支撑：input_bar / feed_card / timeline_modal / api_service
```

## 功能清单（12 页面）

| 功能 | 归属 | 目的 | 状态 |
|:-----|:-----|:-----|:----:|
| Feed 主页面 | Kernel 核心 | 记录今天：统一卡片流 + 一次输入一事件 | ✅ |
| 输入栏 | Kernel 核心 | 文字 + 多图（≤3）+ 附件 | ⚠️ 文件/链接占位 |
| 切换账号 | Kernel 基础 | 多账号选择 | ✅ |
| 关于我（profile）| Kernel 基础 | 个人档案查看/编辑 | ⚠️ 保存无反馈 |
| 记忆 | Kernel 核心 | 理解过去：kind 分类 + actionable | ⚠️ 待办只读 |
| 时间线 | Kernel 核心 | 理解过去：日历月视图 | ⚠️ 双实现 |
| 搜索 | Kernel 能力 | 全文检索 + 高亮 | ⚠️ 无防抖/分页 |
| 标签宇宙 | Kernel 能力 | 标签浏览 + 入搜索 | ✅ |
| 任务 | Kernel 基础 | 个人待办 CRUD | 🔴 编辑=新建（P-app-08）|
| 交易 | trading 插件 | 持仓/记录/复盘反哺 | ⚠️ 价格未校验 |
| 阿呆系统 | ~~project 插件~~ | 系统仪表盘 | 🔴 移入 admin（待执行）|
| 生活快速记录 | life 方向 | 预设模板速记 | 🔴 死代码（待定去留）|

## 职责边界（app 属权）

| 属权 | 内容 |
|:-----|:-----|
| **app 拥有（写）** | 个人记录/对话/档案/记忆完成/任务 CRUD/交易记录（个人数据写）|
| **app 拥有（读）** | Feed/时间线/搜索/标签/记忆/持仓（个人数据读）|
| **不属于 app** | 账号创建/禁用/删除、插件门控、数据清理/重建、知识正式入库、系统配置（→ admin）|
| **app 无越界（已验证）** | 仅调 accounts/available + me/plugins，未调管理端点 ✅ |

## API 依赖（与 web 共用 40 产品端点）

输入：POST /records（唯一入口）/ media / ask-batch / conversations/end
读取：feed / brief / timeline / search / tags / memory / identity / positions / portfolio / project
管理：**无**（账号/系统/数据治理全部在 admin）

## 与其他端关系

- **adai-web**：同一产品桌面形态，共用产品端点——app/web 是两形态非两产品
- **adai-admin**：治理端——app 不调用其管理端点；admin 不得编辑 app 用户个人内容（收敛中）

## 已知问题（来自 app-polish 审查，待修）

- 🔴 P0：任务编辑=新建（P-app-08）；admin 维护端点鉴权（P-be-01 后端侧）
- ⚠️ P1：死代码 life_quick_entry / filterTag 无入口 / 待办记忆闭环 / 档案双入口
- ⚠️ P2：时间线双实现 / 搜索三缺 / 错误处理碎片化 / 方向进展硬编码 / 价格校验 / 保存无反馈

---
**变更规则**：新增/移除功能 → 更新本卡 + 对应 _index.md；边界变化 → 先过 boundaries.md（原则级走 RFC）。
