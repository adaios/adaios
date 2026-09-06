---
title: 审查报告：adai-admin 管理后台 UI/UX 专项审查（2026-09-06）
description: ui-reviewer + ux-reviewer 双官对 adai-admin（管理后台）首次专项 UI/UX 审查——布局/视觉/交互问题清单 + P1 实测复核结论
version: 1
created: 2026-09-06
status: active
lines: 130
depends-on:
  - ../../reference/admin-features.md
  - ../../reference/status.md
related:
  - ../../review/REVIEW.md
tags: [review, audit, admin, ui, ux]
---

# 审查报告：adai-admin 管理后台 UI/UX 专项审查（2026-09-06）

> **背景**：用户提出管理后台「布局上一般般」，此前从未对 adai-admin 做过布局/视觉/交互专项审查（历史全维度走查只顺带查过功能性问题）。本次派 ui-reviewer + ux-reviewer 双官并行审查，仅报告不修改。范围：`apps/adai-admin/lib/` 全量，对照三端 theme 与 api-spec 契约。
> **复核**：P1 结论均经主会话对代码实测/代码阅读复核（2026-09-06），见 §四。

## 一、严重度统计

| 审查官 | P1 | P2 | P3 | 说明 |
|:---|:---:|:---:|:---:|:---|
| ui-reviewer（布局/视觉）| 2（1 条复核为误报）| 12 | 21+ | 骨架成熟、token 化不彻底、灰阶对比度系统欠账 |
| ux-reviewer（交互/流程）| 3 | 13 | 9 | 写/破坏性操作保护口径不齐、长任务无进度、per-user 作用域表达不透明 |

## 二、P1（高优先，复核后成立 4 条 / 排除 1 条）

| # | 问题 | 位置 | 复核 |
|:---|:---|:---|:---|
| P1-A | **改密「原密码错误」401 双义未区分 → 误把管理员踢回登录页**（web 端已区分，跨端不一致）| `services/api_service.dart:194-206` / `admin_shell.dart:328-345` | ✅ 成立 |
| P1-B | **反哺入库日期传参错误**：`review.date.toString()` 产出 `2026-09-04 00:00:00.000`，后端 `@PathVariable LocalDate` 解析必败——反哺主动作不可用（同页其它操作均用 `formatDate`/`_dateOf`）| `reviews_tab.dart:140` / `models/system_models.dart:74-83` | ✅ 成立（代码复核确认 DateTime 类型 + LocalDate 契约）|
| P1-C | **文件树/知识树读内容失败被静默吞掉，伪装成「无内容/空目录」**——管理员会误判治理结论 | `data_tree_tab.dart:75-78` / `os_tree_tab.dart:79-82` / `tree_view.dart:67-70` | ✅ 成立 |
| P1-D | **涨跌幅/盈亏 0 值判红**（`v >= 0 ? red`）+ `formatPercent` 对 0 输出 `+0.00%`——平盘误示为涨 | `market_tab.dart:53-54` / `positions_tab.dart:82-83,163-164` / `format.dart:17-18` | ✅ 成立（对照 web 端 V9-10 已修口径）|
| P1-E | ~~会话菜单 switch 无 break → 改密即登出~~ | `admin_shell.dart:286-291` | ❌ **误报**：Dart 3.12 实测无 break case 不贯通（`f('a')` 只执行自身，analyze 0 issue）；仍建议补 break 提升可读性（P3 级）|

## 三、P2 完整清单（25 条）

| # | 问题 | 位置 |
|:---|:---|:---|
| P2-1 | 全局 401 踢回登录零解释（会话过期无提示，任意页被替换）| `main.dart:79-92` |
| P2-2 | 账号启用/禁用 Switch 无确认无防抖，误触即断用 | `accounts_page.dart:115-125,538-546` |
| P2-3 | 插件开关即时生效无影响说明、成功无 toast | `accounts_page.dart:142-155,602-624` |
| P2-4 | 静默刷新失败整页替换为错误态（应保留旧值+非阻塞提示）| `accounts_page.dart:50-70,216-227` |
| P2-5 | 删除账号确认未说明数据（data/{userId}）连带后果 | `accounts_page.dart:157-181` |
| P2-6 | 账号卡无「治理浏览」跳转，顶栏下拉是唯一入口（任务流断裂）| `accounts_page.dart:497-600` / `admin_shell.dart:219-274` |
| P2-7 | 记录/Feed 只看当天前 50 条，无历史/分页/截断提示 | `data_api_store.dart:44` / `system_api_store.dart:54-65` |
| P2-8 | 记忆页只能看「最近有记忆的一天」，无历史日期导航 | `data_api_store.dart:62-79` / `memory_tab.dart` |
| P2-9 | 维护三操作（重建/重补/清理）无确认、不显示目标用户与影响 | `maintenance_tab.dart:26-37,49-74` |
| P2-10 | 行情 zip 导入最长 10 分钟无阶段进度；失败清单仅瞬时 snackbar | `maintenance_tab.dart:89-118` / `api_service.dart:441-452` |
| P2-11 | 反哺冲突「标记已处理」纯前端本地态，刷新即丢的假操作 | `feedback_tab.dart:10-11,54-58` |
| P2-12 | 反哺页是空壳页，真实操作靠文字指向复盘 tab（入口语义错位）| `feedback_tab.dart:76-91` |
| P2-13 | data/ 文件树是全局 data/ 根却混在 per-user 视图，作用域错位（需产品拍板）| `data_api_store.dart:107-111` / `data_page.dart:46` |
| P2-14 | 时间戳/来源/说明等实义小字用 grey5/grey6（对比 1.58-3.06:1 不可读）| 12+ 处 tab/卡（records/memory/feed/reviews/accounts/tree/terms/market/maintenance…）|
| P2-15 | 圆角/字号未 token 化：6/8/10/12/14/16 七档硬编码并存，textTheme 基本未用 | 全库 |
| P2-16 | 窄屏（树在上内容在下）空态仍写「在左侧选择…」（空间指代错误）| `data_tree_tab.dart:159-176` / `os_tree_tab.dart:212-229` |
| P2-17 | 持仓 7 列表无横滚，数值列窄屏数字折行 | `positions_tab.dart:129-232` |
| P2-18 | 行情徽标 admin 用 darkGreen、web 用 darkBlue，同概念三端不一致（绿易误读为跌）| `feed_tab.dart:57` |
| P2-19 | 账号页头部手工复制 PageHeader 规格未复用组件 | `accounts_page.dart:283-309` |
| P2-20 | 行情名称定宽 76px 无 ellipsis，长名折行 | `market_tab.dart:146-156` |
| P2-21 | 术语 source 路径文本无 flex/ellipsis，窄屏挤列 | `terms_tab.dart:146-168` |
| P2-22 | 内置管理员保护仅 grey6 小锁图标（1.58:1 几乎不可见）| `accounts_page.dart:533-537,548-552` |
| P2-23 | 空档案用户名字卡空白、分区无「暂无」占位 | `identity_tab.dart:64-108` |
| P2-24 | kind 筛选为空仍显示「暂无记忆」（未区分分类无 vs 用户无）| `memory_tab.dart:93-100` |
| P2-25 | 首访提示读 `_accountCtrl.text` 但输入过程不刷新（滞后）| `login_page.dart:211-214` |

## 三·五、P3 完整清单（30 条）

| # | 问题 | 位置 |
|:---|:---|:---|
| P3-1 | 启动校验网络类错误也清 token（应仅 401 清，网络保留+重试）| `main.dart:53-71` |
| P3-2 | 建管理员角色零警示；内置 admin 可被同级重置密码（保护口径不一致）| `accounts_page.dart:396-402,548-558` |
| P3-3 | 内置保护锁与当前账号锁图标相同语义混（一个是不可删一个是改密入口）| `accounts_page.dart:533-552` |
| P3-4 | 切换浏览用户不回写 URL，F5 回跳 query 用户 | `admin_shell.dart:64-71,265-268` |
| P3-5 | 数据/系统 tab 切走重建，筛选/滚动丢失（需 KeepAlive 确认）| `data_page.dart:51-62` |
| P3-6 | 档案/持仓空态文案缺失，与故障态难区分 | `identity_tab.dart` / `positions_tab.dart:72-90` |
| P3-7 | 持仓/行情无数据时点（lastUpdated 未透传）与手动刷新 | `positions_tab.dart:30-53` / `data_models.dart:135-168` |
| P3-8 | 复盘生成可连点重复触发 AI；列表 N+1 串行拉取 | `reviews_tab.dart:54-61,262-273` / `system_api_store.dart:81-95` |
| P3-9 | 建号提交无 busy 禁用，双击可重复提交 | `accounts_page.dart:74-98` |
| P3-10 | 数值列表格左对齐（应右对齐）；表头不吸顶 | `positions_tab.dart:131-228` |
| P3-11 | 各 tab 统计数字字号/字重同质却不统一（建议共享 Stat 组件）| 多 tab |
| P3-12 | 登录按钮「登 录」留空格、设置密码无空格（不一致）| `login_page.dart:203-204` |
| P3-13 | 中文界面残留英文标签（'Domain'/'Conflicts'）| `os_tree_tab.dart:184` / `feedback_tab.dart:76` |
| P3-14 | 顶栏用户下拉原始 userId 可撑宽 AppBar（需 maxWidth+ellipsis）| `admin_shell.dart:219-274` |
| P3-15 | 账号卡 userId 无 ellipsis，超长换行错位 | `accounts_page.dart:570-586` |
| P3-16 | 10px 徽章/元文本已可读下限，grey5 徽章对比不足 | `widgets/badge.dart` / `tree_view.dart:138` |
| P3-17 | 大文件内容 SelectableText 全量渲染无虚拟化（大 md/json 卡顿）| `data_tree_tab.dart` / `os_tree_tab.dart` |
| P3-18 | cardTheme（radius14）与 AppCard（radius10）两套并存，无一处用 Material Card | `theme/app_theme.dart:65-73` |
| P3-19 | 登录页与顶栏两处「阿呆控制台」Logo 双规格硬编码 | `login_page.dart:151-161` / `admin_shell.dart:172-179` |
| P3-20 | 「执行」按钮 96×34 触达偏小（触屏场景需 40-44）| `maintenance_tab.dart:159-187` |
| P3-21 | 主按钮色源混用（darkBlue vs darkGreen@0.2），disabled 回落 M3 灰 | 登录/弹窗/账号页多处 |
| P3-22 | 复盘行内 12px 裸 TextButton 链可点性弱 | `reviews_tab.dart:262-295` |
| P3-23 | ICP 条未处理底部安全区、10px grey5 可读性弱 | `admin_shell.dart:381-405` |
| P3-24 | PopupMenu offset(0,44) 硬编码悬空 | `admin_shell.dart:282` |
| P3-25 | 同色 15% 两种写法（hex 注释 vs withValues）| `theme/app_theme.dart:31,46` |
| P3-26 | 页脚/跨页提示 11-12px grey6/grey5 弱化，发现性差 | `market_tab.dart:93-96` / `feedback_tab.dart:89` |
| P3-27 | 树目录行 darkGreen@0.06 底几乎不可辨，层级视觉密度高 | `tree_view.dart:96-98` |
| P3-28 | 记录 id 以徽章常驻首行（系统内部标识占视觉位）| `records_tab.dart:92-98` |
| P3-29 | 登录错误 13px 文案无错误框/图标，出现时布局跳动 | `login_page.dart:139-141,183-190` |
| P3-30 | Feed 卡右列与左列视觉重心不对齐 | `feed_tab.dart:181-186` |
| P3-31 | 11 处三态（空/加载/错误）内联复制，建议抽共享 TabErrorView/TabLoadingView | accounts/data/system/knowledge 各 tab |

## 四、P1 实测复核方法（2026-09-06）

1. **P1-E（switch 贯通）**：/tmp/darttest 最小复现，Dart 3.12.2 stable 实测 `f('a')` 仅输出 `A`（不贯通到 `case 'b'`），`dart analyze` 0 issue → 排除「改密即登出」，但该 switch 缺 break 仍是隐患（建议 if/else 或补 break，P3）。
2. **P1-B（反哺日期）**：代码复核 `TradingReview.date` 为 `DateTime`、`promoteReview` 拼 `review.date.toString()` 入路径；后端 `TradingController.promoteToInbox(@PathVariable LocalDate date)`——格式不符（带空格毫秒）→ Spring 解析失败，按钮实际不可用。修复：改 `formatDate(review.date)`。
3. **P1-A/P1-C/P1-D**：代码阅读复核确认判定逻辑与调用链成立。

## 五、修复进展（2026-09-06）

| 批次 | 范围 | 状态 |
|:---|:---|:---|
| P1 修复批 | P1-A（改密 401 双义）/ P1-B（反哺 formatDate）/ P1-C（文件树错误态）/ P1-D（0 值中性灰）| ✅ 已修（+5 测试，58 全绿）|
| P2/P3 拍板修复批 | P2-11（移除「标记已处理」本地假操作→冲突只读+引导）· P2-13（文件树标注全局范围）· P2-7（记录/Feed 来源标注）· P3-2（重置内置 admin 警示确认）| ✅ 已修（用户拍板 4 项全按推荐，+5 测试，63 全绿）|
| 保护/反馈批（自动推进）| P2-1（全局 401 提示）/ P2-2（禁用确认）/ P2-3（插件开关反馈+说明）/ P2-4（静默刷新保留旧值）/ P2-5（删除文案会话失效）/ P2-6（账号卡治理浏览直达）/ P2-9（维护三操作确认+目标用户）| ✅ 已修（用户「不需要我决策就开始」，64 全绿）|
| 拍板另排 | P2-7/8 历史日期浏览（后续单独立项）| ⏳ 待规划 |
| UI 打磨批 A（自主推进）| P2-16/18/20/21/22/24/25 + P3-3/8/9/12/13/14/15/20/23/24/25/28（布局/一致性/溢出/busy/触达）| ✅ 已修（用户「你继续」；64 全绿）|
| UI 打磨批 B（自主推进）| P2-10（导入结果对话框 + MaintenanceResult.failures 完整清单）/ P2-23（档案空态：名字/偏好/规则占位）/ P3-6（空持仓占位）/ P3-17（FilePreview 超大文件截断）/ P3-18（删 cardTheme 死 token）/ P3-29（登录错误 AnimatedSize）| ✅ 已修（64 全绿）|
| 待视觉验收 / 结构性重构 | P2-14 对比度（grey5/grey6 实义文本系统化提升——观感大改，需人眼验收）；P2-15 圆角/字号 token 化；P3-5 tab 保活；P3-11 统计共享组件；P3-27 树 hover/选中态；P3-31 三态组件抽取；P2-19 页头复用 PageHeader；P3-21/22/30 观感微调 | ⏳ 待处理（非缺陷、需视觉或重构决策）|
| 另排（已有定案）| P2-7/8 记录·记忆历史浏览（用户拍板另排）；P2-5 删除数据语义（后端 task-log #149 待拍板，前端不臆断）| ⏳ 另排 |

> 用户新增协作规则（2026-09-06）：**代码修改必配测试、测试现行**（每批改动前跑基线、改后全绿）；**可复用经验沉淀为审查清单新检查点**。

## 六、建议修复批次（原案，已部分执行）

1. ✅ 代码级快修：P1-A（改密 401 按 web 口径区分）、P1-B（formatDate）、P1-D（0 值中性灰）——已随 P1 修复批完成（P1-C 一并）
2. ⏳ 保护口径批：P2-2/3/9（禁用/插件/维护确认与影响说明）、P2-5（删除数据连带说明，依赖后端删除语义拍板）
3. ⏳ 反馈批：P2-4（静默刷新保旧值）、P2-10（导入进度）
4. ⏳ 需产品拍板：P2-13（文件树全局语义——✅ 已拍板保持全局+标注）、P2-7/8（记录/记忆历史浏览——另排）
