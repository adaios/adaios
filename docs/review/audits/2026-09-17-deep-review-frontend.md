---
title: 前端深度审查报告：晚间批 + 深夜第二批（d060841..HEAD）
description: frontend-reviewer 对 3dadfd9 / 42dc0b5 前端改动的逐条核证——iOS 分享凭据 Keychain 迁移与老装机路径、扩展基址同源、通知点击深链定位；含 P2-learn23「前端只读卡写入口」证伪
version: 1
created: 2026-09-17
updated: 2026-09-17
status: active
lines: 223
depends-on:
  - ../../architecture/api-spec.md
related:
  - ../REVIEW.md
  - 2026-09-17-deep-review-backend.md
tags: [review, frontend, audit]
---

# 前端深度审查报告：晚间批 + 深夜第二批

> 审查官：`ai-engineering/roles/frontend-reviewer.md`（只报告不修改，B7）
> 审查方式：读 diff → 读实现上下文 → **跑 analyze/test（app + web）** → 读产物里的 `embedded.mobileprovision` 实证跨 target 契约；每条结论带位置 + 证据
> 审查对象是**已提交状态**（HEAD = `42dc0b5`），工作区仅 `docs/` 有他人未提交改动，不影响本次结论

## 一、范围与基线

| 项 | 值 |
|:---|:---|
| 基线 | `d060841..HEAD`（51 文件，+1327 / −131） |
| 本轮目标 commit | `3dadfd9`（晚间批）、`42dc0b5`（深夜第二批） |
| 范围外 commit | `bc6656b`（午间谷时任务壳 + LaunchAgent，另一会话）→ 见 §七.3 单独标注 |
| 前端文件（本批） | `apps/adai-app`：`ios/Runner/AppDelegate.swift`、`ios/Runner/ShareBridge.swift`、`ios/Runner/Runner.entitlements`、`ios/ShareExtension/ShareAuth.swift`、`ios/ShareExtension/ShareExtension.entitlements`、`ios/ShareExtension/ShareViewController.swift`、`lib/main.dart`、`lib/main_page.dart`、`lib/services/push_service.dart`、`lib/services/share_extension_service.dart`、`test/push_service_test.dart`；`apps/adai-web`：仅 `test/trading_page_test.dart`（股东代码脱敏，4 处样本值） |
| `apps/adai-admin` | 本批零改动（且无 learn 卡片页面，见证伪节） |
| 测试基线 | app 359（status.md）/ web 302 |

## 二、可执行检查结果（已跑，非推断）

| 检查 | 命令 | 结果 |
|:---|:---|:---|
| app 静态分析 | `cd apps/adai-app && flutter analyze` | **No issues found!**（0 issue）✔ |
| app 测试 | `cd apps/adai-app && flutter test` | **All tests passed!** `+359`（0 失败 0 跳过）✔ 与 status.md 声明一致 |
| web 静态分析 | `cd apps/adai-web && flutter analyze` | **No issues found!** ✔ |
| web 测试 | `cd apps/adai-web && flutter test` | **All tests passed!** `+302` ✔ |
| 跨 target 契约（access group 的 TeamID 来源） | 解析构建产物 `build/ios/Release-iphoneos/{Runner.app,ShareExtension.appex}/embedded.mobileprovision` 的 `<?xml…</plist>` 段 | 两处 **`TeamIdentifier: ['4G3D37YKSB']`**；两处 entitlements 含 **`keychain-access-groups: ['4G3D37YKSB.*', 'com.apple.token']`** → `4G3D37YKSB.group.com.adaiadai.adaiApp` 落在通配内，扩展**能**读到主 App 写的共享钥匙串 ✔（详见 §九 残留项：这是 development 描述文件） |
| TeamID 兜底一致性 | `grep DEVELOPMENT_TEAM ios/Runner.xcodeproj/project.pbxproj` | 主 App 与扩展三个 configuration 全是 `4G3D37YKSB`，与 `fallbackTeamId` 字面量一致 ✔ |
| entitlements 挂载 | 同上 pbxproj | 两 target 的 `CODE_SIGN_ENTITLEMENTS` 均挂到各自 3 个 configuration ✔ |
| 依赖解析 | 上述两条 flutter 命令 | 有 15 个包有更新版本（与 flutter_secure_storage 9.x→11.x 等大版本不兼容约束），非本批引入 |

## 三、结论（分级计数）

| 级别 | 数量 | 摘要 |
|:---|:---:|:---|
| P0（数据丢失 / 崩溃） | **0** | 未发现 |
| 战略 | **0** | — |
| P1（安全 / 主流程不可用） | **0** | — |
| P2 | **3** | ①Keychain 迁移 + 覆盖写非原子 → 存在「新旧钥匙都没有」的窄窗口（可自愈）②迁移清旧明文不受「扩展能否读 keychain」约束（部署中间态风险；当前产物已核实能力在位）③深链定位在 `learn:review`/`trading:today` 上必然走兜底 → 高亮「最新一张推送卡」而非被点的那条，与代码注释承诺相反 |
| P3 | **8** | 同一深链二次点击不再定位 · `storage` 字段到不了 UI · `clearToken` 把「读不出来」当「已清空」+ `available` 语义漂移 · 迁移路径无回读校验 · 高亮切换重挂卡片子树 · 点击深链触发双份刷新 · 命中屏幕外卡片无反馈 · 新参数/新通路无测试 |

**核心判断**：本批前端改动**方向正确、无 P0/P1**，`analyze`/测试与 status.md 声明完全对得上；所有问题集中在「Keychain 迁移的失败窗口」与「深链定位的匹配质量」两处，都是**可小幅加固**的健壮性问题，不阻塞发布。**证伪任务结论：未能证伪**（详见 §六）——但「触发面为 0」这句话有一个必须写进 REVIEW 的隐性依赖。

---

## 四、P2 逐条

### P2-F1 迁移成功即清旧明文，而覆盖写是「先删后加」非原子 → 存在「新旧钥匙都没有」的窗口

- **位置**：`apps/adai-app/ios/Runner/ShareBridge.swift:129-135`（迁移）、`:112-119`（write）、`:212-217`（saveToken）
- **证据**：
  1. `migrateLegacyIfNeeded()`：`write(payload)` 成功即 `clearLegacy()`（旧明文被抹掉）；
  2. `write()` 是 `SecItemDelete(baseQuery())` → `SecItemAdd(add)`，**两句之间没有事务**，`SecItemAdd` 失败时旧 keychain item 已被删；
  3. 自动续期通路 `ShareTokenKeeper._run()`（`lib/services/share_token_keeper.dart:62-67`）在 `status()` 判定「老数据没记到期时间」后必调一次 `saveToken` 覆盖写。
- **失效链**：老装机旧明文 T1 → 启动时 `status()` 迁移成功（**旧明文已清**）→ `needsIssue`=true（老数据无 `expiresAt`）→ `saveToken(T2)` 的 `SecItemAdd` 失败 → 返回 false、不撤旧令牌 → **Keychain 空 + 旧容器空**，扩展提交时只能给人话「没拿到钥匙」。
- **缓解（决定严重度的事实）**：该状态**可自愈**——下次打开 App，`status()` 得 `hasToken=false`、`available`（容器在）仍 true → `needsIssue=true` → 重新签发并写入；扩展自己的话术也已是「打开阿呆一次，会自动换一把新的」（`ShareViewController.swift:262`）。故不是「永久砖」，而是「本该无缝自愈的一段时间内静默失效」。
- **对比旧实现**：旧 `saveToken` 是 `defaults.set(...)` 覆盖写 + 回读，**没有先删**这一步，不存在这个窗口——本批是**净新增的破坏性操作**。
- **建议**：`write()` 改为「存在则 `SecItemUpdate`，不存在才 `SecItemAdd`」（无删除窗口）；或在 `SecItemAdd` 失败时回滚重写旧 payload（读旧值 → add 失败 → 再 `SecItemAdd(旧值)`）。
- **是否要改由你定**：概率低（迁移时同一 `write` 刚成功过），但后果是「用户没做错任何事，分享突然不能用」，且修复成本只有几行。

### P2-F2 迁移清旧明文的时机，不受「扩展是否真的能读这把钥匙」约束

- **位置**：`ShareBridge.swift:129-135`（主 App 迁移）+ `:216`（`clearLegacy`）、`ShareAuth.swift:24-31`（扩展回落旧容器）
- **证据**：`migrateLegacyIfNeeded` 只校验**主 App 侧** `SecItemAdd == errSecSuccess` 就清旧明文。若主 App 的 entitlements 有 `keychain-access-groups` 而**扩展 target 的描述文件没有**（本批注释自认：「自动签名下首次构建要去 Apple 侧重签描述文件」，`ShareExtension.entitlements:22`），则：主 App 写入成功 → 旧明文被清 → 扩展 `SecItemAuthKeychain.token` 因 `errSecMissingEntitlement` 读不到 → 回落旧容器也被清 → **扩展从「本来可用（读旧明文）」变成「彻底不可用」**。旧的兜底通路被迁移主动拆掉了。
- **当前构建的实测**：**不会**触发——两个 `embedded.mobileprovision` 的 `keychain-access-groups` 都含 `4G3D37YKSB.*`（见 §二）。风险只在「只重签主 App、没重签扩展」的中间态，或将来某个 distribution profile 不带该通配时。
- **建议（廉价且能彻底关掉这条）**：迁移时**不清**旧明文（保留一个发布周期），或把「清旧明文」推迟到扩展侧上报一次成功读取之后；退一步至少给 `clearLegacy` 打个可回滚的日志（当前是静默 `removeObject`）。
- **说明**：本条的严重度取决于你如何评估「部分重签」的概率；我按「后果严重、修复便宜」记 P2，若你认为中间态不可达可降为 P3。

### P2-F3 深链定位在 `learn:review` / `trading:today` 上必然走兜底，高亮「最新一张推送卡」——与注释承诺相反

- **位置**：`apps/adai-app/lib/main_page.dart:1543-1551`（匹配）、`:1527-1531`（注释承诺）
- **证据**：
  - 后端深链取值只有三种：`trading:<symbol>` / `learn:review` / `trading:today`（`services/adai-core/src/main/java/com/adaiadai/core/kernel/push/PushChannel.java:119-125`）；
  - 前端 `final key = link.contains(':') ? link.split(':').last : link;` → `learn:review` 得 `"review"`、`trading:today` 得 `"today"`，去匹配中文推送正文/标题，**必然匹配不到** → 第 1551 行兜底 `_cards.reversed.where((c) => c.pushTitle != null).firstOrNull`＝**最新一张推送卡**；
  - 而注释写的是「找不到目标卡片时**只刷新 + 滚到底**，不假装定位成功」。实现与注释直接冲突：它确实「假装定位成功」了（给一张可能不相干的推送卡加绿框）。
  - 同族问题：`trading:<symbol>` 走的是 `content.contains(key)` 子串匹配（`FeedCard` 的 `content` = 后端 `p.message()`）。带标的的行情/止损推送正文含 `(600206)`（`MarketAlertService.java:325-354`），**能**命中；但这是「**取最新一条提到该串的卡**」（`for (final c in _cards.reversed) … break`），不区分推送卡与普通记录卡——用户随后自己记过一条提到该代码的记录，就会被高亮成错的卡。
- **建议**：①后端深链补上可匹配的载荷（例如 `trading:today` → 直接给 push id / 日期，或推 `push:<id>`）；②短期先让 `pushTitle` 参与：`learn:review` 对 `pushTitle == '学习复习提醒'`、`trading:today` 对 `pushTitle != null` 且 `date == 今天`，并且**优先推送卡**（分两轮：先 only-push 匹配，再全卡匹配）；③删掉「不假装定位成功」的注释，或删掉第 1551 行的兜底改成「只滚到底」。

---

## 五、P3 逐条

### P3-F4 同一条深链第二次点击不再定位（`ValueNotifier` 同值不通知 + 从不复位）
- **位置**：`apps/adai-app/lib/main.dart:361`（`ValueNotifier<String?> _pushDeepLink`）、`:389-391`（赋值）、`main_page.dart:123`（监听）
- **证据**：`_pushDeepLink` 只在 `if (deepLink != null && deepLink.isNotEmpty)` 时被赋值，**全仓无任何位置把它置回 null**（`grep -rn "_pushDeepLink" apps/adai-app/lib` 仅 3 处：声明 / 赋值 / 透传）。`ValueNotifier` 的 setter 在 `==` 相等时不通知 → 同标的的第二次预警（如另一笔仓位的同票止损、或同日重复点同一通知）→ 监听器不触发 → **不高亮**（Feed 仍刷新）。
- **建议**：处理完置 `null`（`_onPushDeepLink` 末尾），或改 `ValueNotifier<int>` 计数器 / `Stream`。

### P3-F5 `status()` 新增的 `storage` 字段到不了任何 UI（注释承诺落空）
- **位置**：原生 `apps/adai-app/ios/Runner/ShareBridge.swift:238-244`（写入 `storage`，注释「UI/排查能看出这把钥匙存在哪儿」）；Dart 投影 `apps/adai-app/lib/services/share_extension_service.dart:8-52`（`ShareExtensionStatus` 无 `storage` 字段）、`:118-127`（解析时不取 `storage`）
- **证据**：Dart 侧只读 `available/hasToken/id/savedAt/expiresAt/appGroup`；`grep -rn "storage" apps/adai-app` 在 Dart 侧 0 命中。
- **影响**：迁移失败（仍是 legacy）时，排查的人**在 App 里看不到**，只能靠 `strings`/日志；该字段目前是死数据。
- **建议**：要么解析并在分享令牌弹窗/调试文案里显示，要么删掉字段与注释（避免「声称有 UI」的失真）。

### P3-F6 `clearToken` 把「读不出来」当成「已清空」（假成功），同族 `available` 语义已漂移
- **位置**：`ShareBridge.swift:222-226`（clearToken）、`:98-108`（read：任何非 `errSecSuccess` 都返回 nil）、`:240`（`available`）
- **证据**：`ShareKeychain.delete()` 的返回值被丢弃；`return ShareKeychain.read() == nil` —— 而 `read()` 在 `errSecMissingEntitlement`（能力没配好）时**同样返回 nil** → `clearToken()` 返回 `true`，用户/弹窗被告知「已撤销」，实际 keychain item 可能还在。当前靠「扩展此时也读不到」掩盖了后果，但返回值语义是假的。
- **同族语义漂移**：`available` 由旧口径「App Group 容器是否可用」变为 `container || payload != nil`（`:240`），而 Dart 侧文档仍写「App Group 容器是否真的可用」（`share_extension_service.dart:9-13`），消费方 `ShareTokenKeeper._run` 用 `if (!status.available) return;`（`share_token_keeper.dart:51`）当作「容器不可用就别签了」——两个口径不再是同一个东西，将来 keychain 有值但容器坏了时会走进「继续签发」分支。
- **建议**：`clearToken` 用 `delete()` 的结果（或 `SecItemCopyMatching` 区分 `errSecItemNotFound` 与其它错误）；`available` 拆成两个字段或在 Dart 侧注释同步真实语义。

### P3-F7 迁移路径没有回读校验，与 `saveToken` 的口径不一致
- **位置**：`ShareBridge.swift:112-119` + `:129-135`（迁移只看 `SecItemAdd` 返回值）对比 `:212-217`（saveToken 有回读 `read()?["token"] == token`）
- **证据**：`saveToken` 立了「写 API 没报错 ≠ 写进去了」的规矩（历史 P1），迁移却没照做，`write` 返回 true 就 `clearLegacy()`。
- **建议**：迁移后加一次回读确认再清旧明文（与 saveToken 同构）。

### P3-F8 高亮切换会重挂被高亮卡片的子树（入场动画重放 + 卡内 State 复位）
- **位置**：`apps/adai-app/lib/main_page.dart:1780-1802`
- **证据**：`Container(decoration: highlighted ? BoxDecoration(...) : null, child: TweenAnimationBuilder(key: ValueKey('card_${card.id}'), …))`。`Container` 只在 `decoration != null` 时才在 `build()` 里插入一层 `DecoratedBox`，否则直接返回 child → 该单子槽的 runtimeType 在 `TweenAnimationBuilder` ↔ `DecoratedBox` 之间**来回切换**，`canUpdate` 为 false → `ValueKey('card_${card.id}')` 拦不住，卡片子树被重新 inflate。表现：`_highlightCardId` 置位时与 2.5 s 后清零时，**各播放一次 400ms 的 Opacity 0→1 + translate(0,20)→0**，且卡内 State 复位（对话态输入等）。
- **建议**：恒包一层 `DecoratedBox`（未高亮时用 `Border.all(color: Colors.transparent, width: 2)`），或把 `highlighted` 作为一个普通参数传进 `FeedCard`（不改变组件树形状）。

### P3-F9 点击深链会触发双份 `_refreshFeed`（冷启动时三份 locate）
- **位置**：`main.dart:389-392`（先 `_pushDeepLink.value=`，后 `_feedRefreshTick.value++`）→ `main_page.dart:1532-1539`（`_onPushDeepLink` 内 `_refreshFeed()`）+ `:259-262`（`_onRefreshTick` 内 `_refreshFeed()`）
- **证据**：两条信号都会落到 `_refreshFeed()`（`_refreshFeed` 重置 `_currentPage = 0` 并重新 `getFeed(page:0)`，`:264-271`）。冷启动时 `_locateAndHighlight` 首次必然 miss（`_cards` 还空），再叠加 `.then` 的第二次 locate。
- **影响**：多一次（冷启动多两次）网络请求与 `_cards` 并发替换；本项目历史上出现过「并发刷新互相覆盖」类问题（F53）。
- **建议**：`_onPushDeepLink` 不再自己刷新，改为复用 `_feedRefreshTick` 的刷新完成后回调（或加 `_refreshGen` 代际令牌）。

### P3-F10 命中屏幕外的卡片时，用户看不到任何反馈（只滚到底）
- **位置**：`main_page.dart:1542-1558`（在**全部已加载** `_cards`，含更早分页里找）+ `:1561-1575`（`_scrollToBottom`＝`animateTo(0)`，列表 `reverse: true`）
- **证据**：命中更早的卡（例如用户曾记录过该代码）时，`_highlightCardId` 指向视口外，页面滚到底 → 高亮 2.5 s 内无人看见，随后自然熄灭。
- **建议**：命中后按 `card.id` 用 `Scrollable.ensureVisible`/`itemScrollController` 滚到该卡；命中更早分页时先说明「那条在更早的记录里」。

### P3-F11 新参数 / 新通路没有测试
- **位置**：`apps/adai-app/lib/services/share_extension_service.dart:79-99`（新 `apiBaseUrl` 参数）；`test/share_token_keeper_test.dart` 的假容器（不校验送参、不回 `storage`）；`test/push_service_test.dart:248-270`
- **证据**：`grep -rn "apiBaseUrl\|storage" apps/adai-app/test/*.dart` → **0 命中**。深链 2 例覆盖了 `type|deep` 与「无 pipe → null」，但**没有覆盖本次特意保持「零改动」的 pendingTap（冷启动补投）带深链**这条通路（`:272` 的既有用例仍只用 `adai-stop-loss`），也没有覆盖 type 里含 `|` 的边界。
- **建议**：加 ①`saveToken` 断言送参与默认值（`ApiConfig.baseUrl`）；②`PushStatus.pendingTap = 'adai-stop-loss|trading:600206'` → 回调拿到 deepLink；③（可选）`_locateAndHighlight` 的匹配优先级 widget 测试。

---

## 六、【证伪任务】P2-learn23「前端已隐藏只读卡写入口，实际触发面为 0」

**结论：未能证伪。** 在「**前端 UI 能触发的写操作**」这个范围内，web 与 app 的写入口**全部**在 `writable == false` 时被挡掉了；web **没有**残留能对只读卡发起写操作的路。但「触发面为 0」依赖一个隐性前提（见限定 2），建议把它一并写进 REVIEW，而不是只写 0。

### 6.1 逐条核对（写入口 → 拦截证据）

**adai-web / `lib/pages/learn_page.dart`**

| # | 写入口 | API | 唯一调用点 | 对 `readOnly` 的拦截 |
|:-:|:---|:---|:---|:---|
| 1 | 编辑正文 / 写复述 | `PATCH /learn/cards` | `_openRetellDialog:1033`，入口 `_actionButtons:901` | 入口整体 `if (card.readOnly) return const [];`（**:894**）+ 函数内 `if (card.readOnly) return;`（**:996**）✔✔ |
| 2 | 流转 status（去复习/标记完成） | `PATCH /learn/cards/status` | `_changeStatus:967`，调用点仅 `:897`/`:899`（都在 `_actionButtons` 内） | 入口被 `:894` 挡住 ✔；**函数内 `:963` 无 readOnly 判断** ⚠（见限定 1） |
| 3 | 反哺候选 | `POST /learn/cards/candidate` | `_createCandidate:984`，调用点仅 `:910`（在 `_actionButtons` 内） | 入口被 `:894` 挡住 ✔；**函数内 `:980` 无判断** ⚠ |
| 4 | 改主题 | `PATCH /learn/cards/topic` | `_openMoveTopicDialog:1100`，入口 `:904` | 函数内 `if (card.readOnly) return;`（**:1050**）✔✔ |
| 5 | 删卡 | `DELETE /learn/cards` | `_confirmDeleteCard:1154`，入口 `:906` | 函数内 `if (card.readOnly) return;`（**:1121**）✔✔ |
| 6 | repages | `POST /learn/cards/repages` | **无调用** | 三端 grep 0 命中 → 不可达 ✔ |
| 7 | 删反哺候选 | `DELETE /learn/cards/candidates` | `:1271` | 作用于**候选**不是卡，与只读卡无关 |
| 8 | 写复述兜底 | — | — | `_actionButtons` 是唯一装配点（`:786` 详情页调用） |

**adai-app / `lib/pages/learn_page.dart`（`_LearnDetailPage`）**

| # | 写入口 | API | 入口 | 拦截 |
|:-:|:---|:---|:---|:---|
| 1 | 改主题 | `PATCH /learn/cards/topic` | `_moveTopic:839` ← `_cardActions:909` | `_cardActions()` **只在 `_writable` 分支挂出**（`:978-985`）✔✔；函数内 `:819` 无判断 ⚠ |
| 2 | 删卡 | `DELETE /learn/cards` | `_deleteCard:888` ← `_cardActions:921` | 同上 ✔✔；函数内 `:855` 无判断 ⚠ |
| 3 | 复述/编辑 | — | `_retellEntry:1116` | **app 侧根本没有写入口**（纯展示，文案指「编辑请到桌面端或让阿呆帮你」`:1122`）✔ |
| 4 | 流转 status / 反哺 / repages | — | — | app 未实现（grep 0 命中）✔ |
| 5 | 列表页 | — | `:701 if (!card.writable)` | 只加锁图标，列表项无写动作 ✔ |

**adai-admin**：无 learn 卡片页面，只有账号页插件开关（`lib/pages/accounts/accounts_page.dart:698` `_pluginSwitch(account, 'learn', '学习')`）→ 无写入口。

### 6.2 附带核实：`readOnly` 的取值不会「因为两处口径不一致而漏掉」

- 前端 `readOnly => !writable`（web `services/models/learn_models.dart:306`，字段缺省 `?? true`）—— guard 用的就是列表卡的 `writable`；
- 后端列表/树与全文用的是**同一个** `decorate()` → `isOwn()`（`services/adai-core/src/main/java/com/adaiadai/core/infrastructure/storage/LearnCardFileRepository.java:120-154`；`list():659-666` → `tree():669-675`；`/learn/content` 走 `detail()`），所以 **web 的列表口径不会与全文口径相反**（app 反而更严：`_content?.writable ?? _card.writable`，`learn_page.dart:935`）。
- `locate()` 的同名策略确认如你所述：**本产品卡优先**，同名只有一张产品卡时返回产品卡（`LearnCardFileRepository.java:187-205`）——这正是 P2-learn23 的机制，改动本身**不在前端**。

### 6.3 三条限定（建议写进 REVIEW 的 P2-learn23 条目）

1. **保护只在「入口层」，不在「动作层」**：`_changeStatus`（web:963）、`_createCandidate`（web:980）、`_moveTopic`（app:819）、`_deleteCard`（app:855）这四个函数**内部没有** `readOnly/_writable` 早退，全靠「唯一调用点恰好被 `:894` / `:978` 挡住」。任何新增按钮、快捷入口、批量操作或函数复用，都会立刻把触发面从 0 变成 1，且后端会把它落到同名产品卡上。**加固成本 = 4 行早退**，建议做（这也是把「触发面为 0」从「靠结构巧合」变成「结构自证」的唯一办法）。
2. **`writable` 是 fail-open 缺省**：两端 DTO 都是 `(json['writable'] as bool?) ?? true`（web `learn_models.dart:296`、app `learn_models.dart:291`）。今天后端 tree/list/content 都带该字段（`LearnCard` record 组件，`LearnController.java:139-152`/`323-328`），所以没问题；但只要将来某个新响应/新端点漏掉 `writable`，只读卡就会被渲染成可写，**入口保护当场失效**，写操作就会落到同名产品卡上——即 P2-learn23 描述的现象复活。**这是「实际触发面为 0」唯一的隐性依赖**，建议明确写进条目：「触发面 0 的前提是响应带 `writable`；缺字段时 fail-open 为可写」。
3. **「前端隐藏入口」管不到 AI 侧**：文案本身在鼓励「让阿呆帮你改」（web `:865`、app `:1122`），而对话流写入若将来由后端 AI 工具链执行，不经过前端的 `readOnly` 判断。本次核实：app 的 Feed 对话 learn 分支只有「整理（digest 新建卡）」与「打开那篇（只读）」（`lib/main_page.dart:567` 附近的入口判定；`test/learn_conversation_test.dart` 头部锁的三件事），**当前没有编辑动作**，所以仍不可触发。仅作前瞻提示。

---

## 七、观察项（不构成本批前端缺陷 / 非前端）

1. **`POST /learn/cards/restore-origin` 三端零入口**（本批新增，P2-learn21 的修复）：web / app / admin grep 均 0 命中；用户遇到「`origin` 被别处工具抹掉 → 卡静默变只读」时，前端只能看到只读说明（app `learn_page.dart:1107`、web `learn_page.dart:884`），**没有自助恢复入口**，只能手打 API。若是有意「后端先落地、前端随后」，建议在 change-log / REVIEW 里注明「暂无前端入口」，避免读者以为用户可达。
2. **`POST /learn/cards/repages` 同样零前端调用**（2026-09-15 加的端点，本批未动）——同上，仅作登记。
3. **范围外 commit `bc6656b`（另一会话，午间谷时任务壳）**：非前端，我未做深入审查。仅两点如实转述：① 该 commit message **自曝** `scripts/setup-launchd.sh` 的 `': > 日志'` 实为截断，已把 `backup.log`(2019B) 与 `weekly-audit.log`(4656B) 清零且**无法恢复**（`*.log` 被 gitignore，无副本）—— 属真实的一次性数据丢失（本地日志），已在提交信息里声明，是否需要单独出表由你定；② `ai-engineering/noon-task.sh` 在时区 ≠ +0800 时只打印告警仍按本机钟继续跑峰谷闸门（`MIN_LEFT` 固定按 14:00 计算），属「提醒而非阻止」的设计取舍。
4. **跨 target 契约清单未同步**：`ShareBridge.swift:57` 已把 access group 记为「跨 target 契约（**第五处**）」，而 `apps/adai-app/AGENTS.md:149` 仍只列「四处必须逐字一致」（App Group id + 键名 × 4 文件），**没有**把新增的 `keychain-access-groups` / `service` / `fallbackTeamId` / `teamIdentifier` 取法写进去。将来排查「扩展说没拿到钥匙」的人按 AGENTS.md 核对四处会**正好漏掉本次这批**。建议把 AGENTS.md 那一行扩成五处（纯文档，成本一行）。

## 八、覆盖面声明

**已覆盖（逐条读过实现，不只读 diff）**：
- `apps/adai-app` 本批全部 11 个改动文件（含 Swift 全文：`ShareBridge.swift` 252 行、`ShareAuth.swift` 86 行、`ShareViewController.swift` 的 `apiBaseUrl`/话术段、`AppDelegate.swift` 的 `didReceive` 段、两个 entitlements）；
- Dart 侧完整通路：`main.dart`（`_initPush`/`onTap`/`pushDeepLink` 透传）→ `main_page.dart`（监听注册/注销、`_onPushDeepLink`、`_locateAndHighlight`、高亮渲染、`_refreshFeed`、`_scrollToBottom`）→ `push_service.dart`（`init`/`_dispatchTap`/`pendingTap` 补投）；`share_extension_service.dart` → `share_token_keeper.dart` → `share_token_dialog.dart` 三处调用方；
- 跨 target 契约的**产物级实证**：构建产物里两个 `embedded.mobileprovision` 的 `TeamIdentifier` 与 `keychain-access-groups`、pbxproj 的 `DEVELOPMENT_TEAM`/`CODE_SIGN_ENTITLEMENTS`；
- **P2-learn23 证伪**：`apps/adai-web`（`learn_page.dart` 2297 行逐段 + `services/models/learn_models.dart` + `api_service.dart` 的 learn 段）、`apps/adai-app`（`learn_page.dart` 详情页 + `services/models/learn_models.dart`）、`apps/adai-admin`（确认无 learn 卡页面）、后端 `LearnController` 全部写端点 ↔ 三端调用方 grep 对拍；
- 可执行检查：app/web 的 `flutter analyze` + `flutter test`（全绿，与 status.md 一致）。

**未覆盖 / 未做**：
- **真机验证**：Keychain 的实际读写、老装机升级迁移、分享扩展提交、通知点击高亮均**未在真机/模拟器上跑过**（我只做了静态与产物级的核实）；`flutter build ios` 本批**未重跑**（用的是 9-16 23:49 的既有产物做描述文件取证）。
- **Swift 单测**：项目没有 iOS 单测 target，`ShareKeychain` 的迁移/失败分支只有逻辑推演，没有可执行证据；`ShareViewController` 的网络分支同样未跑。
- DeepSeek/后端、`ai-engineering/` 脚本、`bc6656b` 的实现细节（我只读了 `noon-task.sh` 全文与 `setup-launchd.sh` 的 diff 头部）。
- **未验证**：`SecItemAdd` 在真实失败模式（锁屏/首次解锁前/磁盘满）下的行为；`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` 在 App 后台被唤醒时是否可能 `errSecInteractionNotAllowed`。

## 九、不确定项（待验证 + 验证方法）

| # | 待验证 | 验证方法 |
|:-:|:---|:---|
| U1 | 老装机（旧明文 token）升级后：迁移 → 自动续期覆盖写 → 扩展仍能拿到令牌 | 真机装旧版本写入令牌 → 覆盖安装本批构建 → 打开 App 一次 → 在 B站分享面板分享一条，看扩展是「交出去了」还是「没拿到钥匙」；同时打日志确认 `storage=keychain` |
| U2 | 「主 App 可写、扩展不可读」的中间态是否可达（P2-F2） | 归档/TestFlight 构建后 `codesign -d --entitlements :- Runner.app` 与 `…/PlugIns/ShareExtension.appex`，确认**两个** target 的最终 entitlements 都含 `keychain-access-groups`；当前 development 描述文件已含（§二），但 distribution 描述文件未取证 |
| U3 | 真机通知点击 → 高亮是否落在「被点的那条」 | 发一条带标的的止损/买点推送（`adai.push.apns.types` 白名单内）→ 点通知 → 看绿框在哪张卡；再点同一通知第二次（验证 P3-F4） |
| U4 | 冷启动 + pendingTap 带深链能否定位 | 杀进程 → 点通知冷启动 → 观察是否高亮；或加一条 widget 测试：`pendingTap='adai-stop-loss|trading:600206'` 断言回调收到 deepLink（当前无此用例） |
| U5 | `learn:review` / `trading:today` 在真实数据下是否总能兜底到正确那张 | 造两条数据：先一条「学习复习提醒」推送，再一条更晚的交易推送，然后点学习推送的通知 → 看绿框是否落在学习卡上（按当前实现会落在更晚的交易卡上） |
| U6 | `SecItemAdd` 失败在真机上会不会出现（P2-F1 的概率） | 加一次性日志：`write()` 失败时打印 `OSStatus`（现在完全静默），跑一段时间看有没有非 `errSecSuccess` |

---

> **本次审查未修改任何代码或文档**（B7）。以上所有位置均以 HEAD=`42dc0b5` 为准，行号可直接跳转。
