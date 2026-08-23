---
title: 项目资产：adai-web（桌面端）
description: adai-web 项目资产卡——模块划分/职责边界/与 app 的关系；改 web 前先读本卡
version: 1
created: 2026-08-15
updated: 2026-08-23
status: active
lines: 78
depends-on:
  - ../../frontmatter-spec.md
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

## 字体资产（本地化，2026-08-20 首建 / 2026-08-22 修复 CFF 框字 / 2026-08-23 修复残缺子集）

- `web/fonts/NotoSansSC-Subset.woff2`：Noto Sans SC **TrueType(glyf) 轮廓 GB2312 全量子集**（**2.4MB，7451 字形**，OFL 开源可分发）
- ⚠️ **2026-08-22 修复**：原 `HiraginoSansGB-Subset.woff2`（CFF 轮廓 1.8MB）在 skwasm 引擎下 **FreeType 解析失败 → 中文全框**（与 Flutter issue #128485「CanvasKit 不支持 WOFF2」同类；Roboto 为 TrueType 轮廓所以英文正常、中文全框）。换 TrueType 轮廓的 Noto Sans SC 后正常。
- ⚠️ **2026-08-23 修复（生产事故）**：线上曾部署 **242 字形残缺版（63KB）**——生成时字符清单过小，`历/史/卖/出` 等大量常用字缺字形 → 「历史成交」等界面文字显示框框（CanvasKit 已加载字体就不回退系统字体）。**重新生成 GB2312 全量（7451 字形，2.4MB）**：枚举全部 GB2312 区位（A1-F7 × A1-FE）解码出 6763 汉字 + 682 符号，再补 `—·…` 等 GB2312 外界面符号。部署替换后正常。
- 中文（Noto Sans SC 等 gstatic 请求）由 `scripts/serve_web.sh` 注入的 fetch 补丁改道到本地 woff2；Roboto → Roboto.woff2
- 重新生成（fonttools + brotli，从 Google Fonts 完整版子集化）：
  `pyftsubset /tmp/NotoSansSC-Regular.ttf --text-file=<GB2312 charset> --flavor=woff2 --layout-features= --no-hinting`
  （charset 生成：Python 枚举 GB2312 全部区位解码；源字体：jsDelivr `google/fonts` 仓库 `NotoSansSC[wght].ttf`——GitHub raw 被墙，用 jsDelivr CDN）
- **校验铁律**：部署前必须 `TTFont().getBestCmap()` 查字形数 ≥ 7000，且 `历史成交买卖` 等 8 个界面高频字在 cmap 内——残缺子集是静默事故（页面不报错只显示框框）
- 不入库（gitignore `/web/fonts/`），部署需手动放置（生产已放 `/opt/adaios/web/fonts/`）；adai-admin 同构（同一子集文件）

## 已知问题（来自 app-polish 审查）

- ⚠️ 档案编辑 PUT /identity 与 admin 重复（admin 收敛后归 web+app）
- ⚠️ promote 直写 os/ 缺治理确认环节

---
**变更规则**：新增/移除模块 → 更新本卡 + _index.md；与 app 的功能对拍（F25/F37 双端一致）是 web 特有检查点。
