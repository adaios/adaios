# AGENTS.md — adai-web

AdaiOS 桌面端（Flutter Web，独立 UI——非 adai-app 适配）。

> 这是 AdaiOS monorepo 的一个子项目。在根目录下有全局 AGENTS.md 和 VISION.md。
> **在本目录工作时，你的上下文限制在桌面端前端，不处理后端、adai-app、交易知识等其他项目。**

---

## 定位

adai-web 是 **产品入口的桌面形态**（参考元宝电脑端：横版、左侧常驻侧栏 + 主内容区）。
与 adai-app（移动端）**各做各的 UI**，不互相适配。两者共享 API 契约与状态机模型（值复制，不跨工程 import）。

| 前端 | 定位 | 形态 |
|:-----|:-----|:-----|
| **adai-web** | 桌面端产品入口（Web） | 两栏壳：左导航 + 主内容区，8 模块桌面原生布局 |
| adai-app | 移动端产品入口（iOS/Android/Web） | 双 World 手势 + 单列 Feed |

## 技术栈

| 层面 | 选型 |
|------|------|
| 框架 | Flutter 3.44.x / Dart 3.12.x |
| 主题 | Material 3，深色模式（dark-only，AppColors 暖灰 6 级 + accent） |
| 状态管理 | StatefulWidget + setState（无第三方状态库） |
| 后端通讯 | HTTP REST → `services/adai-core`（Spring Boot） |

## 构建与运行

```bash
# 构建 + 本地服务（:8082，CanvasKit + 字体本地补丁）
sh scripts/serve_web.sh

# 分析
flutter analyze

# 测试
flutter test

# 构建产物
flutter build web
```

> 本地字体 `web/fonts/`（NotoSansSC-Subset.woff2 + Roboto.woff2）不入库，需手动放置。
> NotoSansSC-Subset.woff2 为 Noto Sans SC 的 TrueType(glyf) 轮廓 GB2312 子集（63KB，OFL 开源）——**必须用 TrueType 轮廓**：原 HiraginoSansGB-Subset.woff2 是 CFF 轮廓，skwasm 引擎 FreeType 解析失败导致中文全框（2026-08-22 修复，Flutter issue #128485 同类）。由 `scripts/serve_web.sh` 的 fetch 补丁指向；重新生成见 `ai-engineering/assets/projects/adai-web.md` 字体资产节。
> 后端需先启动：`cd services/adai-core && ./gradlew bootRun`（:8080）。

## 项目结构

```
lib/
├── main.dart                    # App 入口（?userId= query 解析 → DesktopShell）
├── desktop_shell.dart           # 两栏壳 — 左导航 200 + 主内容区（lazy IndexedStack 保活）
├── services/
│   ├── api_config.dart          # API 配置（后端地址）
│   ├── api_service.dart         # HTTP 客户端（3 项改进：utf8 解码 / 缓存参数感知 / ApiException）
│   └── models/
│       ├── identity_models.dart # 个人档案 DTO
│       └── tag_models.dart      # 标签统计 DTO
├── models/
│   └── feed_models.dart         # Feed 卡片状态机模型（值复制自 adai-app，桌面重绘）
├── theme/
│   ├── app_colors.dart          # 调色板（值复制自 adai-admin，+ darkRed）
│   └── app_theme.dart           # Material 3 dark ThemeData
├── pages/
│   ├── feed_page.dart           # Feed — 主对话流 880 + 右上下文栏 300（简报/标签云/任务快照）
│   ├── trading_page.dart        # 交易 — 快照 stat 卡 + 真 DataTable 持仓 + 记录 Dialog
│   ├── memory_page.dart         # 记忆 — master-detail（左日期列表 + 右内容）
│   ├── timeline_page.dart       # 时间线 — 左月历面板 + 右当月记录
│   ├── task_page.dart           # 任务 — 看板三列 TODO/DOING/DONE + quick-add
│   ├── project_page.dart        # 项目 — 仪表盘双列卡片 grid + RFC 表格
│   ├── search_page.dart         # 搜索 — 顶部全宽搜索栏 + 关键词高亮结果流
│   └── profile_page.dart        # 档案 — 左身份卡 + 右编辑区两栏
├── widgets/
│   ├── page_header.dart         # 桌面页统一页头（标题 + 副标题 + 操作区）
│   ├── desktop_feed_card.dart   # 桌面 FeedCard — 4 态状态机 + 时间竖列 + hover
│   └── hoverable.dart           # 桌面 hover 包装器
└── utils/
    └── text_cleaner.dart        # AI 回复 JSON 残留清理 + \\uXXXX 解码
```

## 当前测试状态

- **测试数唯一事实源：`../../docs/reference/status.md`**（RFC `20260815-docs-governance`，/ship 时更新，本文件不复制数字）

```bash
flutter analyze   # 0 issues
flutter test      # 见 docs/reference/status.md
```

覆盖：DTO JSON 解析、ApiException、缓存参数感知、userId query 解析、桌面壳（导航/懒加载/保活）、桌面 FeedCard 渲染（idle/chatting/ended/回调/action/market，含中文化文案断言）、REVIEW 修复批回归（选号/上传/图片追问 + #234 分页终止口径 + #236 记忆页刷新保位 + #201/#229 溢出/tooltip）、S-1 多图 ask（askBatch 请求契约 imageRecordIds/question + 清标签缓存、AskBatchResponse log 兜底解析）。

## 桌面壳设计

```
DesktopShell({userId})
└─ Scaffold(darkBg) > Row
   ├─ _NavRail(200, darkSurface)   ← 自定义 rail
   │   ├─ logo「阿呆阿呆」+ Divider
   │   ├─ 8 导航项：Feed/记忆/时间线/项目/任务/交易/搜索/档案
   │   └─ 底部 @userId
   ├─ VerticalDivider(1, darkBorder)
   └─ Expanded(lazy IndexedStack)  ← 8 页保活，首次访问才构建
```

- **lazy IndexedStack**：只实例化已访问页面（`_visited` Set），未访问为 `SizedBox.shrink()`，`index` 固定不漂移。已访问页面切换后 **offstage 保活**（Feed 对话态跨页保留）。
- 模块内交互用内部状态 / showDialog，不走 Navigator.push。
- 每个页面接收 `ApiService`（Shell 统一创建，透传 userId）。

## 设计约定

- **桌面原生** — 每模块一个"桌面形态"布局（两栏/表格/看板/master-detail），非移动端放大。
- **值复制** — 复用 adai-app/adai-admin 的模型与主题时复制值，不跨工程 import。
- **深色模式优先**（dark-only）
- **一条卡片一次对话** — idle → waiting → chatting → ended 在同一个卡片内完成（状态机与 adai-app 一致）。
- 对话流居中限宽 880，右上下文栏 300（Feed 页内部布局，不算壳第三栏）。

## 相关文档

| 文档 | 位置 | 说明 |
|:-----|:-----|:-----|
| `frontend-reference.md` | `../../docs/architecture/` | 前端统一参考（含 adai-web 桌面端章节） |
| `api-spec.md` | `../../docs/architecture/` | API 接口契约（全局唯一真相源） |
| `VISION.md` | `../../docs/` | 项目愿景与核心理念 |
