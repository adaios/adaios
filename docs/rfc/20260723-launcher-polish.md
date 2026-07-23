# UI 增强：导航幽默化 + 标签云点击 + 色彩

> 日期：2026-07-23
> 涉及：前端 Flutter
> 状态：已实现

---

## 改动清单

### 1. 导航去重

去掉标签云功能行（已直接展示在页面底部），只保留 3 行：

```
👤 关于我            → 身份页
🧠 脑瓜子正在装...    → 记忆页
📅 时间都去哪了       → 时间线页
```

### 2. 标题幽默化 + 右侧数据

| 原标题 | 新标题 | 右侧数据 | 数据来源 |
|:-------|:-------|:---------|:---------|
| 身份 | 👤 关于我 | 阿呆 · 偏好摘要 | `GET /api/v1/identity` |
| 记忆 | 🧠 脑瓜子正在装... | 已存 N 条理解 | `GET /api/v1/memory` |
| 时间线 | 📅 时间都去哪了 | 已记 N 条记录 | `GET /api/v1/timeline` |

注意：年龄因 profile 无出生年份字段，改为展示偏好摘要（风格 · 专注领域）。

### 3. 色彩 + emoji

每行前面用 emoji 替代 Material icons，右侧数据用绿色：

```
👤 关于我                 阿呆 · 简洁直接 · 半导体    >
🧠 脑瓜子正在装...        已存 47 条                 >
📅 时间都去哪了           已记 128 条                >
```

- emoji 用 `Text` widget 直接显示
- 右侧预览文字用 `AppColors.darkGreen`
- 标题用 `AppColors.darkGrey1`

### 4. 标签云点击 → Feed 筛选

点击标签云任意标签 → 回到 World A → Feed 按标签筛选。

实现方式：
- `main.dart` `DualWorldShell` 维护 `_filterTag`
- `LauncherPage` 的 `onTagTap` 传 tag 名到 shell
- `MainPage` 接受 `filterTag` + `onClearFilter`
- `_loadFeed` 同时保存 `_allCards` 和 `_cards`（过滤后）
- `didUpdateWidget` 监听 `filterTag` 变化重新过滤
- Feed 顶部显示 `🏷️ 标签名 ×` 可点击清除

### 5. 标签云标题亮色

"标签云"标题改为绿色 + emoji 🏷️，右侧显示总数。

### 涉及文件

| 文件 | 操作 |
|:-----|:------|
| `main.dart` | 改 — 加 `_filterTag`、`_onTagTap`、`_clearFilter` |
| `main_page.dart` | 改 — 加 `filterTag`、`onClearFilter`、过滤逻辑、筛选横幅 |
| `pages/launcher_page.dart` | 改 — 去标签云行、幽默化标题、emoji、绿色右侧、搜索跳转 |

### 不做的事

- 年龄推算：等 profile 增加出生年份后再做
