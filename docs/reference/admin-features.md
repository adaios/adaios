---
title: adai-admin 管理后台功能手册
description: AdaiOS 管理后台（adai-admin）的完整功能参考——定位与边界、登录鉴权与会话、四区页面清单、每页功能与操作、端点总表、已知缺陷；开发与审查的基准对照
version: 1
created: 2026-09-06
status: active
depends-on:
  - ../architecture/api-spec.md
  - feature-reference.md
  - status.md
  - ../../ai-engineering/assets/projects/adai-admin.md
related:
  - feature-reference.md
  - trading-features.md
  - ../rfc/20260802-adai-admin.md
  - ../rfc/20260901-auth-login.md
tags: [admin, reference]
---

# adai-admin 管理后台功能手册

> **定位：** AdaiOS「管理后台」（`adai-admin`，Flutter Web，深色主题）的完整功能参考——纯系统治理端，非个人使用端（个人用 app/web）。
> **用途：** 问题定位、新功能开发、重构与审查时的基准对照（代码实测口径，页面/功能以本文件为准；模块职责与边界以 `ai-engineering/assets/projects/adai-admin.md` 为准）。
> **真相源：** 端点契约以 `docs/architecture/api-spec.md` 为准；本手册以 `apps/adai-admin/lib/` 实测为准。生产访问：`https://adaiadai.com/admin/`。

## 〇、模块定位与边界

- **一句话**：管理后台 = AdaiOS 的「账号 + 数据 + 系统治理端」，面向本人（开发者/拥有者），不是日常使用端。
- **账号矩阵（2026-09-04）**：`admin` = 内置管理员（role=admin，纯后台管理）；`adai` = 产品主账号（role=user，app/web 用）；`family` = 普通受限账号。控制台仅 role=admin 可进。
- **职责红线**：个人数据**写**只属用户端（app/web）；admin 只做**治理**——账号 CRUD、插件门控、数据清理/重建/重补、复盘反哺入库确认、行情数据包导入、治理只读浏览。任何个人内容编辑入口新增前先问「这该在 app/web 还是 admin」（B 边界原则）。
- **技术形态**：独立 Flutter Web 工程（`apps/adai-admin/`），真实后端 API（无 mock），构建带 `--base-href=/admin/`。

## 一、登录与会话（REVIEW #178 / RFC 20260901-auth-login）

| 环节 | 行为 |
|:---|:---|
| 登录页 | 账号 + 密码登录；**账号框不留默认值**（2026-09-06 起，此前残留预填 adai 已移除）；成功且 role=admin → 进控制台 |
| role 门禁 | 普通账号（role≠admin）登录 → 提示「不是管理员账号」，不入控制台（前端前置；后端 AuthFilter role=admin 双保险）|
| 首访引导 | 系统未初始化（全系统无任何账号设过密码）时登录 401「尚未设置密码」→ 页内切换「设置密码」模式（POST /auth/setup，一次性）|
| 会话持久化 | token 存 localStorage（键 `admin_auth_token`，与产品端 `auth_token` 区分防同源互顶）；启动读 token → `/auth/me` 校验（含 role=admin）→ 有效进控制台，失效/非 admin 清 token 回登录页 |
| 401 全局处理 | 控制台内任意请求 401（token 被重置/过期/会话被踢）→ 清会话回登录页（2026-09-04 补传 onUnauthorized 修复漏传）|
| 顶栏会话菜单 | 显示「登录：@{userId}」+ 修改密码（POST /auth/password，原+新+确认，踢除其他会话）+ 退出登录（POST /auth/logout 幂等 + 清本地）|
| 登录限流 | 连续 5 次失败按 IP+账号锁 15 分钟（后端）|

## 二、主壳与导航（admin_shell.dart）

- **顶栏**：标题「阿呆控制台」+ 右侧**用户选择器**（跨账号治理浏览的核心）+ 会话菜单（见上）。
  - 用户选择器：`?userId=xxx` 前门 query 优先 → 回落登录账号 → `default`；下拉切换数据/系统两区的浏览视角（admin 会话后端透传 X-User-Id，可跨账号治理浏览）。
- **四主区**：账号 / 数据 / 系统 / 知识（IndexedStack 保活切页）。宽屏（≥720px）左侧导航 rail，窄屏底部导航。
- **数据/系统为 per-user**：随顶栏用户切换重建 store 与页面（ValueKey 对齐）；账号区、知识区为全局（账号=系统级数据，知识=os 资产全局）。
- 底部 ICP 备案栏（京ICP备2026056893号）。

| 主区 | 页面文件 | 视角 | 功能摘要 |
|:---|:---|:---|:---|
| 账号 | `pages/accounts/accounts_page.dart` | 全局 | 账号 CRUD + 插件门控 |
| 数据 | `pages/data/data_page.dart` | per-user | 治理只读浏览（6 tab）|
| 系统 | `pages/system/system_page.dart` | per-user | 运维操作台（5 tab）|
| 知识 | `pages/knowledge/knowledge_page.dart` | 全局 | os 知识资产浏览（2 tab）|

## 三、账号页（accounts_page）

> 账号卡列表 + 建号表单；内置管理员 `admin` 带「内置」徽章且受保护（不可删/禁/降级——代码 `AccountStore.protectedAdminId`；后端 `Account.SEED_ADMIN_ID` 同保护双保险）。

| 操作 | 说明 |
|:---|:---|
| 建号 | 登录名（`[a-zA-Z0-9_-]+`，保留字 `default` 禁建）+ 可选**初始密码**（≥8 位，不设则无法登录，事后可重置）|
| 启用/禁用 | 账号卡开关（Switch）→ PATCH；禁用后该账号登录被拒；内置 admin 无启用开关（受保护）|
| 插件开关 | 每账号 trading/project 插件开关 → `PATCH /accounts/{userId}/plugins`（服务端合并语义 add/remove，防并发互覆）；决定该账号 app/web 模块显隐 |
| 重置密码 | 每账号卡按钮（当前登录账号自身隐藏，引导用顶栏改密）→ 弹窗输入新密码 ≥8 位 → 后端踢除该账号全部会话 |
| 删除账号 | 确认弹窗「此操作不可撤销」→ 删前踢会话 + 删数据目录；内置 admin 删除被拒 |
| 角色/状态徽章 | 卡上显示 role（admin/user）、状态（启用/禁用/内置）|

## 四、数据页（data_page，per-user 治理只读）

> **只读收敛**（2026-08-16 P-role 系列）：记录删除、任务 CRUD、档案编辑、记忆修正已移回用户端，admin 一律只读；全部 tab 带加载失败人话 + 重试。

| Tab | 文件 | 内容 |
|:---|:---|:---|
| 记录 | `records_tab.dart` | 所选用户记录列表（只读；无删除）。⚠️ **只看今天**（P3 #163 待修：管理端应覆盖历史，Feed/记录契约只今天会隐性截断）|
| 记忆 | `memory_tab.dart` | 记忆列表（只读）|
| 档案 | `identity_tab.dart` | 个人档案查看（只读）|
| 任务 | `tasks_tab.dart` | 任务列表（只读；CRUD 归用户端）|
| 持仓 | `positions_tab.dart` | 持仓查看（只读治理；✅ 治理视角正确）|
| 文件 | `data_tree_tab.dart` | data/ 目录树浏览 + 文件内容预览（治理浏览）|

## 五、系统页（system_page，per-user 运维）

| Tab | 文件 | 内容 |
|:---|:---|:---|
| Feed | `feed_tab.dart` | 所选用户今日 Feed 预览（只读）|
| 行情 | `market_tab.dart` | 持仓行情快照查看（只读）|
| 复盘 | `reviews_tab.dart` | 复盘日期列表 + 查看 / **生成**（未生成时）/ **反哺入库**（确认弹窗带备注 → POST /trading/reviews/{date}/promote → os/trading-engine/99-inbox/ 候选）|
| 反哺 | `feedback_tab.dart` | 规则冲突检测（`/admin/trading/knowledge/conflicts`，持仓 vs 规则）——双栏展示冲突两侧 + 「标记已处理/撤销」切换；空态引导「反哺入库在复盘页签」|
| 维护 | `maintenance_tab.dart` | **记忆重建**（POST /admin/memory/rebuild）、**数据清理**（POST /admin/cards/cleanup）、**行情数据导入**（MD17：file_picker 选通达信盘后 .zip → 长超时上传 POST /admin/market/tdx-import → 结果摘要含失败清单）|

## 六、知识页（knowledge_page，全局）

| Tab | 文件 | 内容 |
|:---|:---|:---|
| os/ 资产 | `os_tree_tab.dart` | os/ 知识资产目录树（trading-engine / life-os / project-os 下拉切换，懒加载）+ 文件内容预览 |
| 术语/规则 | `terms_tab.dart` | 术语列表 + 规则列表（只读）|

## 七、端点总表（admin 前端实际调用）

> 全部需 `Authorization: Bearer`；管理端点另需会话 role=admin。契约详见 `docs/architecture/api-spec.md`。

| 方法 | 路径 | 用途 |
|:---|:---|:---|
| POST | `/api/v1/auth/login` | 登录 |
| POST | `/api/v1/auth/setup` | 首访一次性设密码 |
| POST | `/api/v1/auth/logout` | 注销当前会话 |
| GET | `/api/v1/auth/me` | 启动会话校验 |
| POST | `/api/v1/auth/password` | 修改本人密码（踢其他会话）|
| GET/POST | `/api/v1/accounts` | 账号查询 / 建号（含初始密码）|
| PATCH | `/api/v1/accounts/{userId}` | 启用/禁用 / 重置密码 |
| PATCH | `/api/v1/accounts/{userId}/plugins` | 插件合并（add/remove）|
| DELETE | `/api/v1/accounts/{userId}` | 删除账号 |
| GET | `/api/v1/admin/files` | data/ 文件树 |
| GET | `/api/v1/admin/files/content` | 文件内容 |
| GET | `/api/v1/admin/knowledge` | os/ 知识资产树 |
| GET | `/api/v1/admin/knowledge/content` | 知识文件内容 |
| POST | `/api/v1/admin/memory/rebuild` | 记忆重建（维护）|
| POST | `/api/v1/admin/records/retry` | 记录重补（维护，代码保留）|
| POST | `/api/v1/admin/cards/cleanup` | 数据清理（维护）|
| GET | `/api/v1/admin/trading/knowledge/conflicts` | 规则冲突检测 |
| POST | `/api/v1/admin/market/tdx-import` | 行情数据包导入（MD17）|
| GET | `/api/v1/feed` | Feed 预览 |
| GET | `/api/v1/memory` `/api/v1/memory/dates` | 记忆列表 |
| GET | `/api/v1/identity` | 档案查看 |
| GET | `/api/v1/project/tasks` `/api/v1/project/tasks/stats` | 任务列表 |
| GET | `/api/v1/trading/positions` | 持仓查看 |
| GET | `/api/v1/trading/has-activity` | 是否有交易活动 |
| GET | `/api/v1/trading/reviews` | 复盘日期列表 |
| GET | `/api/v1/trading/review` | 复盘详情/生成 |
| POST | `/api/v1/trading/reviews/{date}/promote` | 复盘反哺入库 |

## 八、已知缺陷（REVIEW 关联，未修）

| 条目 | 说明 |
|:---|:---|
| P3 #163 | 记录页只看当天（Feed/记录契约只今天，管理端应覆盖历史）——checklist F16 |
| P-role-10 | `api_service` 保留 POST /records 无调用（休眠越权面，建议删）|
| P-role-11 | 顶栏用户选择器可切任意 userId 浏览其个人数据——单 owner 成立；多用户化后是隐私越权面 |
| #178 历史 | 登录页默认账号残留 `adai`（2026-09-06 已修：账号框留空自输）|
| 2026-09-06 UI/UX 审查 | 专项审查 + 修复进度完整追踪见 `docs/review/audits/2026-09-06-admin-uiux-review.md`（P1×4/P2/P3 已修 30+ 项）；剩余**待视觉验收/重构**（对比度 token 化、tab 保活、共享组件等）与**另排**（记录/记忆历史浏览、删除数据语义）均登记于该报告进展表 |

## 九、变更规则

- 新增治理功能 → 更新本手册 + `docs/reference/feature-reference.md`（§15）+ `ai-engineering/assets/projects/adai-admin.md` 资产卡 + `_index.md`。
- **任何个人内容编辑入口新增前先问「这该在 app/web 还是 admin」**（边界原则，见 §〇）。
- 部署：admin 前端重建走 `serve_web.sh`（`--base-href=/admin/` + API_BASE_URL）+ tar 原子替换 `/opt/adaios/admin`（详见 `docs/deployment/backend-deployment.md`）。
