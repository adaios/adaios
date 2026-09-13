---
title: 首轮外部视角审查（面向身边人之前）
description: 2026-09-13 首轮外部视角审查——只看字不看码 + 三端入口可达性核查（iOS/Android 原生 App + Web）；产出 P0×2 / P1×7 / 决策×6（D1/D2/D6 已拍板），附函数级落地方案
version: 3
created: 2026-09-13
updated: 2026-09-13
status: active
lines: 241
depends-on: []
related:
  - ../../../ai-engineering/roles/stranger-reviewer.md
  - ../../../ai-engineering/roles/social-reviewer.md
  - ../../../ai-engineering/roles/support-reviewer.md
tags: [review, external, audit]
---

# 首轮外部视角审查（面向身边人之前）

> **审查方式**：只看字不看码 + 入口可达性核查。审查官禁读源码实现，只读「用户可见文案清单」与渠道/构建配置事实。
> **范围**（用户 2026-09-13 定）：**三端**——iOS 原生 App · Android 原生 App · Web 网站（`adaiadai.com` + PWA `/m/`）。
> **决策状态**：D1 / D2 / D6 已于 2026-09-13 拍板 **A / A / A**，落地方案见 §七（**待用户下令开工**，本轮未改任何代码）。
> **边界**：未做装机实测、真人反应记录、开号通路——见 §五。

## 一、结论摘要

产品在**自己手里是成熟的**（后端 1731 + app 261 + web 255 测试、143 端点、9 官审查体系、账实一致性闸门）。但在**第一次拿到它的人手里**有这些问题：

1. **Android 两端都收不到通知**（P0-1）
2. **Android App 目前不具备给别人的条件**（P0-2）
3. **第一屏不解释自己、空态不给下一步**（P1-1 / P1-2）
4. **架构与实现术语写在用户脸上，web 端比 app 端更重**（P1-3～P1-6）
5. **安卓/网页端 9 个推送开关全部无效，且无人知道**（P1-7）

共同根因一句话：**产品是照「已经用了几个月的我」长的，不是照「第一次来的他」长的。**
补一条更锋利的规律：**术语密度与「作者自己是否天天用这个界面」负相关**——他天天用的交易页最人话，他偶尔才点开的 learn 管理页最术语化。

## 二、P0

### P0-1 Android 两端零通知能力（陌生人官 E4 FAIL）

- `PushChannel` 实现共 4 个：`ApnsPushChannel`（**仅 iOS**）、`BarkPushChannel`（2026-09-13 已关，key 移出 `.env`）、`WeChatPushChannel`（无配置段 → 未启用）、`FeedPushChannel`（站内）
- **Android 原生 App**：`pubspec.yaml` 无任何推送依赖；`AndroidManifest.xml` **只有 `INTERNET` 一条权限**，无 `POST_NOTIFICATIONS`、无推送 service
- **Android PWA/Web**：无 service worker、无 push 注册，`manifest.json` 无 `gcm_sender_id`

影响：10 类推送全部送不到 Android，含 P2-用户3 的核心修复（收盘小结推手机）与 learn-review 复习提醒。国内 Android 无 FCM，要补须走厂商通道或第三方 SDK——**独立工程量**。

**已拍板 D2-A**：暂不补渠道，明确告知。

### P0-2 Android App 目前不具备分发给别人的条件（陌生人官 E2/E5 FAIL）

- `apps/adai-app/android/app/build.gradle:31` → `signingConfig = signingConfigs.debug`，上一行仍是 Flutter 模板原注释 `// TODO: Add your own signing config for the release build.`
- 无 release keystore 配置（无 `keyAlias` / `storeFile`）
- `scripts/` 只有 `build_web.sh` / `serve_web.sh`——**没有 APK 构建脚本**
- 现存 `build/app/outputs/flutter-apk/app-release.apk`（54 MB，2026-08-20）即 debug 签名的 release 包

后果：① 不能上架任何商店 ② **debug keystore 丢失/换机器 → 签名不一致 → 升级必须先卸载 → 他的数据全丢** ③ 无可复现打包流程。

**已拍板 D6-A**：侧载；前置是先定正式 keystore 并离线备份。

## 三、P1

### P1-1 第一屏不解释自己（陌生人官 W2/Q1/Q3）

`LauncherPage` 是启动后第一屏（`main.dart:233 home: _booting` → `main.dart:425 LauncherPage`）。文案全是模块名，没有一句「这是什么 / 我能干什么 / 从哪开始」：

```
'阿呆系统'   'Kernel · Domain · 数据'   '标签宇宙'   '时间都去哪了'
'持仓 · 记录'   '待办 · 进行中 · 已完成'   '最近学习 · 知识卡片'
```

好的一面：模块名有性格（`'脑瓜子正在装...'`、`'已存 N 条理解'`）。问题不是难听，是**它们都假设你已经知道这些模块是什么**。

### P1-2 空态不给下一步；同一产品里文案水平差一个量级（陌生人官 Q4）

| 页面 | 空态原文 | 判断 |
|:--|:--|:--|
| Feed | `'还没有记录'`（`main_page.dart:1649`）| **FAIL**：无动作、无预期、无解释 |
| 记忆 | `'今天暂无记忆'` | FAIL：同上 |
| 任务 | `'暂无任务'` | FAIL：同上 |
| 学习 | `'还没有学习卡片\n点右上角「＋」丢个 B站 / 文章链接给我，阿呆抓来消化成卡片（没字幕的视频会先问你要不要转写）'` | **PASS**：人话 + 动作 + 预期 |

**标准是存在的，只是没铺开**——learn 页达到的水准，Feed / 记忆 / 任务 / 持仓都没达到。修法不是发明新句式，是把它自己的好句式铺开。

### P1-3 架构与实现术语写在用户脸上（陌生人官 T1）

**app 端**（`adai-app/lib`，557 条文案抽出）：

| 术语性质 | 原文 |
|:--|:--|
| 架构分层 | `'Kernel · Domain · 数据'`（首页）|
| 架构概念 | `'插件加载失败，仅显示基础服务'` |
| 模块名泄漏 | `'学习功能还没开（learn 插件）'`、`'学习功能未启用（learn 插件）'` |
| 技术名词 | `'API 端点'`、`'API 请求失败'`、`'更新 OS 标记失败'` |
| 内部算法 | `'（快照基线 ${snapshotQty} + 锚点后流水 …）'` |
| 第三方术语 | `'活跃市值（指南针）'` |
| 内部动词 | `'解析失败: …'`、`'已写入入库候选'`、`'反哺入库失败: …'` |

**web 端**（`adai-web/lib`，857 条文案抽出）——**密度更高，且有 app 端没有的最糟样例**：

| 术语性质 | 原文 |
|:--|:--|
| **直接把实现讲给用户** | `'），内部标识在 plugin 字段（'` ← 本轮最糟一条：暴露内部字段名，还带英文 `plugin` |
| 解析器视角 | `'第 N 行：字段不足（需要至少 M 列，实际 K 列）'` |
| 自造词 | `'反哺候选'`、`'反哺入库'`、`'还没有反哺候选\n在交易类学习卡片上点「反哺候选」生成建议卡'`、`'未标注涉及可执行交易规则，暂不能反哺候选'` |
| 内部口径 | `'还没拿到券商快照的锚定日：哪些成交已经在券商口径里，我判断不了。'`、`'快照基线未记录，账实对账暂时无法判定（导一次「持仓股」快照即可开始对账）'` |
| 字段措辞 | `'总盈亏 = 资产 − 本金。本金是历史累计投入，只写本金字段，不影响现金/持仓。'` |

### P1-4 错误提示 = 内部动词 + 内部名词 + 异常原文

app 端 `'反哺入库失败: …'`、`'解析失败: …'`、`'补日期失败: …'`、`'丢弃失败: …'`、`'复盘生成失败: …'`；web 端 `'反哺入库失败: …'`（同名同型）；两端都残留裸错误码 `'请求失败 ($code)'`。
（UX 清单 U26「错误提示透出后端人话」在 **web 端已修、app 端未铺全**。）

**出事时他看到的恰恰是最不该出现的词**——这是最伤信任的一类文案。

### P1-5 自造词无解释

`'反哺'`（**web 11 处 / app 0 处**）、`'候选'`（web 15 / app 11）、`'批次'`（web 8 / app 6）、`'快照'`（web 11 / app 3）、`'入库'`（web 5）、`'字段'`（web 4 / app 0）。
这些词在**你的语境里**精确，在陌生人语境里是谜语。

### P1-6 三端文案不同源：术语密度与「作者是否天天用」负相关（陌生人官 T4）

同一功能、同一句话，两端各写一遍，于是修一端漏另一端：

- app：`'去电脑端导一次「持仓股」或「资金股份查询」快照，我就能重新对上了。'`
- web：`'先导一次「持仓股」或「资金股份查询」快照，我就能重新对上了；'`

更值得注意的对比：**`'反哺'` 在 app 端一处都没有、在 web 端 11 处**——因为 learn 管理/规则审核界面只在 web 端，
**这些界面只在你自己偶尔去点的路径上，从没有第二个人读过它们的字**。

> 这解释了全部术语分布的规律，也给出最省力的排查法：**先审「你自己最不常打开的页面」**，那里密度最高。

### P1-7 安卓/网页端「推送设置」9 个开关全部无效，且无人告知（陌生人官 E4 连带）

- `apps/adai-app/lib/services/push_service.dart:115` **已有能力判断**：`static bool get supported => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;`
  —— 但**全工程 0 处使用**（`pages/`、`widgets/` 全域 grep 无命中）
- `pages/trading_page.dart:2342+` 的推送设置对话框 `_PushSettingsDialog` **无任何平台门控**，直接列出 9 个开关：
  时段节奏 / 买点提醒 / 收盘小结 / 止损预警 / 接近止损 / 单日大跌提醒 / 放飞提示 / 跌破成本线 / 大盘行情条
- 服务端会**如实保存**这些开关（`onToggle` → API）

结果：**安卓用户（原生与 PWA）把 9 个开关全打开、服务端全记下，然后一条通知都不会来，而且他不会知道为什么。**

这是"承诺了无法兑现的东西"，比 UX 清单 U30（本地生效、刷新复现）更隐蔽——这次是**服务端真生效、通知永远不来**。
**必须与 D2-A 一起处理**（见 §七）。

## 四、社会性官：决策表

| # | 场景 | 暴露 / 付出什么 | 拍板 |
|:-:|:--|:--|:--|
| D1 | **锁屏通知**：收盘小结正文含持仓逐只名称 + 现价 + 破止损标记（详见 §七）| 手机放桌上，旁边人一眼看到你持有哪些票 | ✅ **A 正文脱敏** |
| D2 | Android 两端无通知（P0-1）+ 9 个无效开关（P1-7）| 他按 iPhone 的预期用安卓 → 什么都没收到，也不知道为什么 | ✅ **A 暂不补，明确告知** |
| D3 | 开号：admin 建号 → 初始密码怎么到他手上 | 他要记住一个密码，或你当面口头告知 | ⏳ 待定 |
| D4 | 他不想用了 | 退出口已存在（`'退出登录'` / `'修改密码'` 在 Launcher），但**注销 / 删数据**未见 | ⏳ 待定 |
| D5 | 数据隔离 | 每人一份 `data/{userId}/`（隔离已实现），但**未实测**第二个账号 | ⏳ 待定 |
| D6 | **Android 分发**：debug 签名不可用于给别人 | P0-2 三条后果（最狠：升级要卸载 → 数据全丢）| ✅ **A 侧载** |

## 五、本轮未做（诚实边界）

| 项 | 为什么没做 | 谁来做 |
|:--|:--|:--|
| 三端装机实测（E1/E2/E3）| 需真机 | 你（iOS 已有包；Android 先解决 P0-2）|
| 开号通路走一遍（E6/W1）| 需第二个账号 | 你（admin 建号）|
| 第一件事能否独立完成（W3/W4）| 需真机上的空账号 | 你 |
| **递手机测试**（真人第一句话）| **不可替代** | 你：5 分钟、闭嘴、不许指、只记录他的第一句话与第一次停顿 |
| 支持台 20+ 问全表归属 | 需逐页找答案 | 下一轮 |
| 三端文案**全量**逐条过 | 本轮只做术语与空态两类抽样 | 下一轮（按 P1-6 排查法：先最不常开的页）|

## 六、建议顺序

1. **生成正式 keystore 并离线备份** + 补 `build_apk.sh`（D6-A 前置，P0-2）
2. **D1-A 锁屏脱敏** + **D2-A 安卓端说明**（连 P1-7 的 9 个开关一起处理）
3. **修 P1-3 / P1-4 术语与错误文案**（三端一起改，避免又不同源）
4. **补 P1-2 空态**到 learn 页水准（照抄它自己的好句式）
5. 三端装机 + 开号 → 走一遍空世界
6. 真人递手机 → 记录第一句话 → 再修「只有真人能暴露的」
7. 给他用

## 七、落地方案（已拍板 A/A/A，**待下令开工**）

### D1-A → 锁屏正文脱敏

**先修正泄漏事实**：成交只报笔数（`今日成交：买 N 笔 · 卖 M 笔`），但**持仓逐只列名 + 现价 + 破止损标记**：

```
持仓 3 只：
· 某某科技 现价 12.34 ⚠️ 破止损
```

`TradingSessionPushService.buildCloseSummaryTemplate()`（:665）逐只 append 名称 + 现价 + 破止损标记——**这才是锁屏真正暴露的内容**。

**关键约束**：`pushToAll(userId, title, content, type, null, null)` 把**同一份 content 分发给全部渠道**（含 `FeedPushChannel`）。所以「改 content」= 站内也变简，「改渠道」= 只有锁屏变简。

| 方案 | 做法 | 代价 |
|:--|:--|:--|
| **a 分层正文**（推荐）| `PushMessage` record 加可选精简字段；`pushToAll` 传「完整 + 精简」；**APNs 用精简、Feed 用完整** | 改 `PushMessage` + `pushToAll` + `ApnsPushChannel`，约 3 处 |
| b 渠道内脱敏 | 在 `ApnsPushChannel` 内做摘要 | 脱敏规则藏在渠道层，渠道不懂语义（不知哪段是隐私）|
| c 直接精简 content | 只改 `buildCloseSummaryTemplate` | 最省事，但站内 Feed 的收盘小结一起变简（详情仍可在交易页看）|

### D2-A → 安卓/网页端明确告知

1. **用上已有的 `PushService.supported`**（当前 0 处使用）：非 iOS 时——
   - 对话框顶部加说明「你现在这台（安卓 / 网页）收不到通知——阿呆目前只能推到 iPhone。这些开关先留着，换到 iPhone 后生效」，或
   - 开关置灰 + 同上说明
2. 检查 9 个开关的 label 是否会诱导误解（如 `'收盘小结（当日成交+破止损+待确认）'`）
3. 交付话术写明「安卓端不会弹通知，要自己打开看」

### D6-A → 侧载

1. **先生成正式 keystore 并离线备份**（丢失不可找回——这是不可逆的一步，先做）
2. `android/key.properties`（**不进 git**）+ `build.gradle` release 改读它
3. `.gitignore` 加 `*.jks` / `key.properties`（B3 隐私边界）
4. 补 `scripts/build_apk.sh`（对齐 `build_web.sh`：内置 API 地址 `--dart-define` + 产物校验）
5. 首次装机验证：安装 → 打开 → 登录 → 记一条 → 重启后数据还在
6. 后续更新：同一 keystore 签名覆盖安装（不卸载）

**风险提示**：今天之前产出的 `app-release.apk`（debug 签名）**不能作为第一个正式包**——先定 keystore 再出包，否则将来无法覆盖升级。

## 八、落地记录（2026-09-13 本批，D1 / D2 / D6 = A / A / A）

| 决策 | 落地内容 | 验证 |
|:--|:--|:--|
| **D6-A 侧载** | 生成正式 keystore `apps/adai-app/android/adaios-release.jks`（别名 `adaios`、RSA 2048、10000 天）+ `key.properties`（不进 git）；`build.gradle` 的 release 改读它（**缺配置则构建失败，绝不回退 debug 签名**）；`.gitignore` 补 `*.jks` / `*.keystore`（此前只 ignore 了 `key.properties`）；新增 `scripts/build_apk.sh`（前置校验 + 验签对拍 + sha256）| `./gradlew signingReport` → **Variant: release → Store: `adaios-release.jks` / Alias: `adaios`**（不再是 `AndroidDebugKey`）|
| **D1-A 锁屏脱敏** | `PushMessage` 加 `lockScreenContent` + `notificationContent()`（空白回落 → 老构造点行为不变）；APNs / Bark / 微信三渠道改用它，**站内 Feed 仍用完整正文**；收盘小结改为**一次遍历产出两版**：完整版逐只列持仓名 + 现价，锁屏版只报「N 笔成交 / M 只破止损 / 账实有出入」+「打开阿呆看详情」| 后端测试 **+4**（锁屏不含任何持仓名与现价 / 两版必须不同字 / 回落语义 / APNs payload 用精简版）|
| **D2-A 明确告知** | 三处推送设置对话框统一：非 iOS 端**开关置灰 + 说明**（「这台收不到通知——阿呆现在只能推到 iPhone…」），并加 `AlertDialog(scrollable: true)` 防小屏溢出；用上了此前**零使用**的 `PushService.supported` | app widget 测试 **+1**（说明存在 + 开关全置灰 + 10 项集合）；两端 `flutter test` 全绿（app **270** / web **255**）|

### 本批新发现（超出 §一~§七）

1. **同一端存在两个同名 `_PushSettingsDialog`，且开关集合不同**——`main_page.dart`（10 项，含 `learn-review`）vs `pages/trading_page.dart`（9 项，**缺** `learn-review`）。于是：从首页右滑进来的用户能关掉「学习复习提醒」，从交易页铃铛进来的**关不掉**。本批已补齐为 10 项并加测试锁定；**两份代码重复，应抽共享组件（未做，登记为后续项）**。
2. **旧包实证**：`build/app/outputs/apk/release/app-release.apk`（2026-08-20）验签结果为 `CN=Android Debug` —— P0-2 的确证，而非推测。
3. **D1 的实现真相（修正 §七 原描述）**：成交只报笔数，锁屏真正暴露的是**持仓逐只名称 + 现价 + 破止损标记**。

### 本批未做（留待下一批）

- P1-2 空态、P1-3 / P1-4 术语与错误文案——其中「反哺」「候选」「批次」「快照」等**自造词的替换用词需用户拍板**；改文案时不碰同名的代码标识符 / API（那是另一件事）
- 三处重复 UI 抽共享组件
- `status.md` 测试数未更新（**同仓库有并发会话**在做 learn 抓取扩展，避免与对方数字冲突）——本批增量：后端 **+4**、app **+1**

---

**审查官**：stranger-reviewer / social-reviewer / support-reviewer（首跑）
**本轮性质**：前半为审查（只报告，未动产品代码，遵守 B7）；后半经用户拍板 D1/D2/D6 后落地，改动见 §八
**配套新增**：`ai-engineering/roles/{stranger,social,support}-reviewer.md` + `ai-engineering/checklists/review-{stranger,social,support}.md`
