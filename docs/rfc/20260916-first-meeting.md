---
title: 第一次见面——新用户冷启动批（学习插件解锁 + 对话式开场 + 档案活起来）
description: 用户「明天见人」前的体检发现三件事：①「学习」插件在 admin 后台根本无法勾选（前端硬编码漏项，后端早已就绪）；②新用户登录后第一眼只有「还没有记录 + 两个点不出结果的冷词 chip」，全仓无任何 onboarding；③「档案」页永远停在自己手填的表单，而阿呆其实一直在 memory 里沉淀 patterns/preferences——数据在长，只是没有出口。本批修掉这三件，并让开场走「问答」这条新用户零配置就能跑通的路径。
date: 2026-09-16
status: implemented
decided-by: adai（2026-09-16 五条拍板，见 §三）
tags: [新用户, 冷启动, onboarding, 插件门控, 个人档案, 记忆, 第一印象]
related:
  - ./20260905-trading-cognition-layer.md
  - ./20260814-domain-plugin-model.md
  - ../review/REVIEW.md
  - ../architecture/product-roadmap.md
---

# 第一次见面——新用户冷启动批

> **触发来源**：2026-09-16 用户出门见人前的体检提问——
> 「针对新用户，我希望登录后的第一眼就感受到 AI 的力量、AI 的魅力……你像你的个人档案，
> 某些数据都没怎么变化过，我以为他是对我了解够深，会越来越真实呢」
> 「突然想到，对话框直接展示聊天卡片的示例」。
>
> 本批按用户「直接开工」授权实施（**不部署、不 push**，等验收）。

---

## 一、现状事实（三官只读调研，逐条钉到行号）

### 1. 账号体系：能用，但不成体系

| 能力 | 现状 | 证据 |
|:-----|:-----|:-----|
| 登录账号（userId） | **不可改**。全系统无 phone/email 字段，无改名端点；admin 也只能删旧建新 | `Account.java:30-31`、`AccountController.java:138-188` |
| 昵称（AI 对你的称呼） | 可改 = 档案的 `name` 字段 | `profile_page.dart:207-213`(web)、`profile_page.dart:395`(app) |
| 改完对话是否生效 | **下一轮立即生效，无缓存**（简报问候语最迟 5 分钟） | `ContextEngine.java:175-187`→`:485`；`BriefAppService.java:71-86` |
| 头像 | 无（那个圆圈是名字首字渲染的色块） | 全仓 `avatar\|头像` 零命中 |
| 自助注册 | 无，只能 admin 建号 | `AccountController.java:95-128` |

**语义串线（本批修）**：`name` 在 UI 上写「姓名 / 你的名字」，默认值却是 **「阿呆」**——那是 AI 自己的名字，
还被当作「称呼」注入 prompt（`- 称呼：阿呆`），模型会以为用户叫阿呆。

### 2. 学习插件不能勾选：前端漏了一行

后端 `PluginRegistry` 早已注册 `trading / project / learn` 三个（`:21-26`），
`PATCH /accounts/{id}/plugins` 接受 `learn`，21 个 learn 端点的门控、web 导航项、app 门控全部就绪。
**唯一原因**：admin 账号页硬编码了两个开关。

```dart
// apps/adai-admin/lib/pages/accounts/accounts_page.dart:691-693（修复前）
_pluginSwitch(account, 'trading', '交易'),
_pluginSwitch(account, 'project', '项目'),   // ← 没有 learn
```

且 `pluginLabels`（`:236`）也只有两项 → 提示语里的「去插件设置里打开」所指的页面**根本不存在**。

### 3. 新用户第一眼：冷

- 登录后落「对话流」，空态 = `✦ ✦ ✦` / 「还没有记录」/「在下方输入你的第一条记录」+ 2 个 chip
  （`feed_page.dart:1370-1389`）；其中「🤔 问个问题」是 `prefillText('')`——**只聚焦空输入框，等于空操作**。
- 右栏三块全空：「暂无摘要 / 暂无标签 / 暂无任务」。
- 全仓 grep `onboarding / 新手 / 教程 / 欢迎 / 示例` **零命中**——不存在任何引导流程。

### 4. 档案数据不变：不是没长，是没出口

`data/adai/memory/2026/08.md` 里实实在在写着 AI 的长期观察：

```yaml
patterns: [{"content":"用户频繁将科幻概念与现实人物进行类比推演","confidence":0.9}]
preferences: [{"content":"对《三体》战略思想及其现实应用有持续兴趣","confidence":0.85}]
```

而「档案」页显示「偏好 0 · 规则 0 · 标签 0」。`MemoryService.findAllPatterns/findAllPreferences`
（`:288`/`:317`）早就写好了，**从未有过前端入口**（REVIEW P2-认知3 登记在案）。

---

## 二、问题定性

**功能不缺，缺的是「第一次成功的体验」**：三件事都不是能力问题，而是「最后一公里」——
插件差一个开关、开场差一个真能跑的问题、画像差一个出口。

---

## 三、用户拍板（2026-09-16）

| # | 决策点 | 拍板 |
|:--|:-------|:-----|
| 1 | 新用户默认开哪些插件 | **都不开**（保持 `plugins=[]`） |
| 2 | 普通用户能否自助开插件 | **暂不做**（只补 admin 后台的 learn 开关） |
| 3 | 开场示例怎么走 | **走问答路径**；提示语不得超出现有插件方位；**最多 3 个开场**；要像对话过程 |
| 4 | 是否允许给 AI 改名 | **不允许**，只允许改用户自己的昵称 |
| 5 | 头像 | **预设**（不做上传） |

**决策 3 的关键推论**：插件默认全关时，唯一能让新用户「零配置、30 秒内看到 AI 真的在思考」的，
只有 Kernel 基础服务（记录 / 问答 / 记忆）。所以开场必须走问答——这不是省事的妥协，是唯一正确的路径。

---

## 四、实施内容

### B1 解锁

| 改动 | 文件 |
|:-----|:-----|
| admin 账号页补 learn 开关（改用 `Wrap` 防三开关溢出）+ `pluginLabels` 补 learn | `apps/adai-admin/lib/pages/accounts/accounts_page.dart` |
| `PUT /api/v1/identity` 放宽 `name` / `tags` 非空（零画像新用户此前**保存不了任何东西**）；name 自动 trim | `services/adai-core/.../interfaces/IdentityController.java` |
| `defaultProfile()` 默认 name 由「阿呆」改空（消除身份串线）；`parseProfile` 缺省名同改 | `.../infrastructure/storage/IdentityFileRepository.java` |
| `ContextEngine.loadIdentitySummary` 空名时不注入空称呼行，改为「（还没告诉我，先别称呼，或自然称呼「你」）」 | `.../kernel/context/engine/ContextEngine.java` |
| 简报问候语空名时不拼称呼（避免 `☀️  早上好！` 双空格 / 英文混进中文） | `.../application/BriefAppService.java` |
| 档案页 label：「姓名」→「阿呆怎么称呼你」；空名头像首字由「阿呆」改「你」 | `apps/adai-web/lib/pages/profile_page.dart`、`apps/adai-app/lib/pages/profile_page.dart` |
| app 编辑区去掉 `*` 必填标记 + 移除空名拦截（此前两边都拦） | `apps/adai-app/lib/pages/profile_page.dart` |

### B2 第一次见面

| 改动 | 文件 |
|:-----|:-----|
| 对话流空态改为**阿呆先开口的欢迎卡** + 3 个可点开场问句（点击**直接发问**，走真实问答链路）；全部前端本地渲染、**不落库**，有了第一条记录自动让位 | `apps/adai-web/lib/pages/feed_page.dart`、`apps/adai-app/lib/pages/main_page.dart` |
| 三个开场：`你能干什么？` / `你有什么特别的能力？` / `我该怎么用你？`——**全部落在 Kernel 基础能力内**，不碰任何插件 | 同上 |
| web 零数据时收起空右栏（不再三连空壳） | `apps/adai-web/lib/pages/feed_page.dart` |
| **开场问答的能力边界 prompt**：把「已开启 / 未开启」如实写进 prompt，已开启的才可说「我可以帮你」，未开启的只能如实说「要单独开启」，且不许编造清单外能力 | `.../kernel/context/engine/ContextEngine.java` `buildCapabilityContext` |
| 时段问候（夜深了/早上好/中午好/下午好/晚上好，与后端同口径） | 两端 feed/main page |

### B3 让档案活起来

| 改动 | 文件 |
|:-----|:-----|
| 新增 `GET /api/v1/memory/insights`——聚合 memory 的 patterns/preferences，两类合并按置信度降序，附 `observedSince`（最早一条记忆日期）。**用 365 天长期窗口**而非聚合默认的 30 天——否则「两个月没来记录」的用户打开档案页看到的还是「我还不认识你」（不是没观察过，是被窗口挡掉了）；同时把聚合从「按天遍历 365 次、每次重解析整个月文件」改为**按月只读一次**（`readAllMemories`） | `.../interfaces/MemoryController.java`、`.../kernel/memory/MemoryService.java` |
| **双端**档案页新增「阿呆对你的了解」区块：N 件事 + 起点 + **两类各取 3 条**（合并排序时「行为模式」常占满前几名，偏好会一条都露不出来）+ 逐条置信度；点「✓ 对」→ 写回 `identity.preferences`（从此进 prompt），形成「观察 → 确认 → 记住」闭环 | `apps/adai-web/lib/pages/profile_page.dart`、`apps/adai-app/lib/pages/profile_page.dart` |
| 零观察时显示引导（「我还不认识你。多聊几句，这里会长出我对你的了解。」）而非空壳；拉取失败静默降级 | 同上 |
| **头像预设**（用户拍板「只做预设，不做上传」）：9 个预设（🌱🌙☕🎧📚🧭🐳🪴 + 回落名字首字），存 `identity.preferences['avatar']`——**后端零改动**，与既有档案同一条写路径；双端展示 + 选择器 | 同上 |
| 顺手修 app 端两处既有缺陷：① Launcher「关于我」在名字/风格全空时拼出「 · 」空壳；② `_sectionCard` / `_editSwitch` 的 ListTile 被中间带背景色的 `DecoratedBox` 遮住背景与水波纹（Flutter 框架据此抛断言，大屏把该区块纳入布局时即触发）→ 背景改挂 `Material`，视觉不变、水波纹回来 | `apps/adai-app/lib/pages/launcher_page.dart`、`apps/adai-app/lib/pages/profile_page.dart` |

---

## 五、验证

| 项 | 结果 |
|:---|:-----|
| adai-admin `flutter test` | **69 passed**（含新增 learn 开关用例；本批 +1） |
| adai-app `flutter analyze` / `flutter test` | **0 issues** / **359 passed**（+3：开场问句直发 1 + 预设头像 2；另改写空态 2 条） |
| adai-web `flutter analyze` / `flutter test` | **0 issues** / **286 passed**（+6：了解区块 4 + 预设头像 2） |
| adai-core `./gradlew test` | **全量 BUILD SUCCESSFUL**（Identity · ContextEngine · Memory 全绿；本批 +10） |
| `guard-align.sh` | **PASS**（152 端点全部登记在 api-spec · 四端测试数对齐） |
| `guard-meta.sh` | **PASS**（142 文件 frontmatter 图谱/行数/孤儿全绿） |
| 部署 | **未部署**（用户授权范围：只做代码；B8 外向动作需另行确认） |
| git | **未提交、未 push**（避免多会话批次混装，C4 已知坑） |

---

## 六、未做（留给后续）

1. **普通用户自助开插件**（`PATCH /api/v1/me/plugins`）——用户拍板暂不做；因此**学习插件现在只能由 admin 在后台勾选**（这也是 B1 必须先修 admin 那行的原因）。
2. **登录账号改名**——userId 即登录名且不可变，无 rename 端点；属独立议题（需数据迁移）。
3. **`POST /auth/setup` 默认账号是 seed admin 而非 adai**——首访设密码时若不手填账号会误给 admin 设密码，待修。
4. **AI 改名**——用户拍板不做（「阿呆」仍硬编码在 `DeepSeekAiClient`）。
5. **`identity.name` 的存量值**——`data/adai/identity/profile.md` 里仍是 `name: 阿呆`（历史数据未动；改的是「文件缺失时的默认值」与 UI 语义）。要不要把这条真实数据也改掉，留给用户决定。
