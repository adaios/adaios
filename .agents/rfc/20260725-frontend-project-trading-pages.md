---
title: 前端项目状态页 + 交易页
date: 2026-07-25
status: implemented
---

## 背景

后端已完成 Project OS Status API 和 Trading API（持仓/复盘/反哺），前端未接。所有 DTO 已就绪，只缺页面消费。

## 方案

### 纳入现有导航体系

不改导航架构。LauncherPage 已有 6 个入口（Profile/Memory/Timeline/Tags/Search/WorldA），新增 2 个入口：

```
LauncherPage (World B)
  ├── 👤 关于我 → ProfilePage           (已有)
  ├── 🧠 脑瓜子... → MemoryPage          (已有)
  ├── 📅 时间都去哪了 → TimelinePage      (已有)
  ├── 📊 阿呆系统 → ProjectStatusPage    ★ 新增
  ├── 📈 交易 → TradingPage             ★ 新增
  ├── 🏷️ 标签宇宙 (内联)                 (已有)
  └── 🔍 搜索栏 → SearchPage            (已有)
```

### ProjectStatusPage

只读仪表盘。调用 `GET /api/v1/project/status`，展示：
- Kernel 六大组件状态（✅ / 🏗）—— 高密度信息一行展示
- Domain OS 状态
- 统计数据（commit / RFC / API 端点）

**不需要**：编辑、交互、刷新按钮（数据本身是实时查询的）

### TradingPage

分三个 tab/sub-section：

| 区域 | 数据源 | 说明 |
|:-----|:------|:-----|
| 持仓概览 | `GET /api/v1/trading/positions` | 表格：代码/名称/数量/成本/现价/盈亏/盈亏% |
| 组合快照 | `GET /api/v1/trading/portfolio` | 总市值 + 总盈亏 + 现金，一行卡片 |
| 记录交易 | `POST /api/v1/trading/trades` | 简单表单：代码/名称/方向(买/卖)/单价/数量 |

**不做**：复盘查看页面（数据量小，先不建专门的 Markdown 渲染页）

---

## 改动清单

| 文件 | 改动 |
|:-----|:-----|
| **新建** `pages/project_status_page.dart` | ProjectStatusPage — 仪表盘 |
| **新建** `pages/trading_page.dart` | TradingPage — 持仓+交易 |
| **修改** `pages/launcher_page.dart` | 加 2 个 _buildRow 入口 |

---

## 不做的

- 不复盘查看页面（先积累数据）
- 不改导航架构（Launcher + Push 模式足够）
- 不加交易图表（那是 Layer 5 的事）
