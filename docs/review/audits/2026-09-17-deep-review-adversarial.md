---
title: 对抗审查：晚间批 + 深夜第二批（2026-09-17）
description: 对 d060841..HEAD（51 文件/3 提交）做对抗找茬——构造反例优先，产出 P0×2 / P1×4 / P2×6 / P3×4；含 Keychain 能力缺签、盘前盈亏写坏快照、purge 假成功三条硬证据
version: 1
created: 2026-09-17
updated: 2026-09-17
status: active
lines: 324
depends-on: []
related:
  - ../REVIEW.md
  - ../../../ai-engineering/roles/adversarial-reviewer.md
  - ../../../ai-engineering/assets/pitfalls.md
tags: [review, adversarial, audit]
---

# 对抗审查：晚间批 + 深夜第二批（2026-09-17）

> **性质**：只读对抗审查（B7）。**未改任何代码/文档**，本文件是唯一新增文件。
> **范围**：`git diff d060841..HEAD` = 51 文件 / 3 提交（`3dadfd9` 晚间批 · `42dc0b5` 深夜第二批 · `bc6656b` 午间谷时任务壳〔他会话〕）。
> **方法**：先读角色卡 `ai-engineering/roles/adversarial-reviewer.md` → `assets/pitfalls.md`（复发信号表）→ `assets/boundaries.md` → `AGENTS.md`；再按「哪里会炸 / 用户哪里会骂 / 边界哪里漏」三向攻击，**能写出触发条件的排在前面**。
> **基线**：`docs/reference/status.md` 快照（后端 1939 / app 359 / web 302 / 端点 154）。
> **⚠️ 阅读约定**：本文只报告，不代表已批准修复；每条附可复核命令或文件:行。

---

## 一、结论摘要

**P0×2 · P1×4 · P2×6 · P3×4**（另附「我构造的反例」与「不确定但值得查的」两节）。

| 级 | 数量 | 一句话 |
|:--:|:----:|:-------|
| **P0** | 2 | ① 盘前口径改动**只改了读取端、漏了写回端** → 上一交易日盈亏被写进「今天」的快照，到 15:05 才自愈（正是 P2-交易51 要修的那个数）② Keychain 能力**没签进包**（描述文件无 `keychain-access-groups`，归档 entitlements 实测无该键）→ 分享令牌加固整体为空转 |
| **P1** | 4 | ③ `purge` 部分失败仍回 `purged:true` ④ `purge` 只走文件、空目录永残留 ⑤ 图片日配额**失败/拒绝都不退还**，可被静默耗光 ⑥ 「禁 `git add -A`」是零机制的纯规范 |
| **P2** | 6 | ⑦ 深链 `trading:today` 的兜底会点名**另一条 push 卡** ⑧ 轮换端点三端零入口 + 失败回滚可留孤儿 ⑨ 旧明文永不迁走且 `status().available` 报「已就绪」⑩ 迁移状态 `storage` Dart 侧根本没解析 ⑪ P3-隐私1「归零」不成立（change-log 里仍留真股东代码）⑫ P2-learn21 判据与文案/doc 不一致 |
| **P3** | 4 | ⑬ `previousTradingDay` 15 天找不到就静默按今天算 ⑭ admin 侧无 purge 入口（连带「数据删不掉」）⑮ 深链同值不重发通知 + 2.5 秒高亮 ⑯ 登记回填把「巡检批/昨天批」的项计入本批清单 |

**最反直觉的一条**：本批最贵的两个改动（Keychain 加固 / 盘前口径）**都以「看起来已完成」的形态落地**——前者能力没签进包（实测 entitlements 缺失），后者写回了唯一还没被覆盖的持久化路径。

---

## 二、逐条

### P0-1　盘前口径只改了「读」，没改「写回」——上一交易日盈亏被写进今天的快照

**触发条件（可复现）**
1. 交易日 **09:30 之前**（如 08:50 或 09:20 竞价）；
2. 用户做任一落库动作触发重算：`POST /trading/trade`（手动记录）、`POST /trading/trade-log/confirm`（截图确认）、`/trading/import`（历史成交导入）；
3. 用户**今天**有一笔已记录的成交（打新、隔夜委托、凌晨手动补记）或干脆没有。

**链路**（逐跳可查）
- `TradingController:188 / :352 / :1171` → `TradingAppService.refreshTodayPnl(userId)`（:1713）
- → `refreshTodayPnl(userId, LocalDate.now())`（:1733）：闸 1 只用 `isTradingDayStrict(today)` → **交易日返回 true，放行**
- → `computeDailyPnl(userId, today)`（:1498）→ `dailyPnlDetail`（:1510）：`quoteIsFromPreviousDay(today)` 为 true（:1659/:1663）→ **`resolvedDate` 变成上一交易日**
- → 闸 2 只过滤 `notes` 里以「今日无成交记录」开头的行（:1744-1747）。而本批新加的提示是「**今天还没开盘（或今天不是交易日）**，下面是 09-16 的当日盈亏…」（:1525）→ **不在过滤名单里**
- → `accountSnapshotRepository.update(... r.todayPnl() ... c.snapshotDate() ...)`（:1751）→ **上一交易日的盈亏落进快照的 `todayPnl`**，快照日期仍是今天

**后果**
- 账户卡「今日盈亏」= **上一交易日的数**（`AccountSnapshot.todayPnl` 是卡片取值处），与 P2-交易51 的用户实测症状**同形**（用户：09-15 真实 −503.90 被显示成 −180）。
- 该错值会**持续到当天 15:05**：`TradingSessionPushService.closeAccountUpdate`（`:0 5 15 * * MON-FRI`）里的 `resolveTodayPnl` 才是第一个在盘后重算并覆盖它的路径。
- 期间 `GET /trading/pnl-periods`（`EquityCurveController:92`）同样用 `dailyPnlDetail(today)` 覆盖 today 并把差额回填进 week/month → **周/月盈亏一起偏**。

**另一面（同一处的第二个漏）**：`resolvedDate` 一旦是上一交易日，`dayTrades` 过滤（:1528）按上一交易日取 → **今天盘前/竞价成交的这笔完全不在计算里**；持仓浮动也用「上一交易日的现价 − 再前一交易日的昨收」当基准，等于把更早一天的价差记到「今天」。用户看到的既不是今天的、也不是上一交易日的真值。

**证据**
- `TradingAppService.java:1510-1534`（resolvedDate / notes）、`:1733-1757`（refreshTodayPnl 两道闸）
- `EquityCurveController.java:90-100`
- **缺口证据**：本批新增的 3 条测试（`DailyPnlComputeTest`）全是纯函数（`quoteIsFromPreviousDay` / `previousTradingDay`），**没有一条**覆盖「盘前 → refreshTodayPnl → 快照被写成什么」。`grep -n "refreshTodayPnl" DailyPnlComputeTest.java` = 0 命中。

**建议下一步验证动作**
- 反证命令：`grep -n "refreshTodayPnl" services/adai-core/src/test/java/com/adaiadai/core/application/DailyPnlComputeTest.java`（预期 0 行）。
- 修复方向（供参考，不在本次范围）：`DailyPnlDetail` 带出 `resolvedDate`/`stale` 标记，`refreshTodayPnl` 见到 stale **直接不写**（与「非交易日不重算」同一道闸的延伸）；或把 notes 首行纳入闸 2 的 actionable 过滤。

---

### P0-2　Keychain 升级没签进包：描述文件与归档 entitlements 都缺 `keychain-access-groups`

**三级证据（本机实测，均可复核）**

1. **两个 entitlements 源文件里写了**：`Runner.entitlements` / `ShareExtension.entitlements` 各加 `keychain-access-groups = $(AppIdentifierPrefix)group.com.adaiadai.adaiApp`。
2. **本机 4 份描述文件里一条都没有**（含 09-16 22:56 那份新的）：
   ```
   security cms -D -i ~/Library/MobileDevice/Provisioning\ Profiles/*.mobileprovision | plutil -p -
   → Entitlements 只有 application-identifier / aps-environment / application-groups / team-identifier
   ```
3. **上一次构建的归档里，签名后的真实 entitlements 也没有这个键**：
   ```
   codesign -d --entitlements - --xml build/ios/archive/Runner.xcarchive/.../Runner.app
   → {application-identifier, aps-environment, team-identifier, com.apple.security.application-groups, get-task-allow}
   codesign -d --entitlements - --xml .../PlugIns/ShareExtension.appex
   → 同上（无 keychain-access-groups）
   ```

**时序**（`stat` 实测）：4 份描述文件创建于 **09-15 21:29–21:36**；两个 entitlements 文件改于 **09-16 23:44:11**；`flutter build ios` 通过的归档是 **09-16 22:56**。也就是说：**归档用的还是旧 entitlements**（没测到新能力），而**即便用新 entitlements，签名也会被 Apple 剔掉**——因为 profile 里没有这个能力（Apple 以描述文件的 entitlements 为准，本地多写的键会被丢弃）。

**后果（按确定度排序）**
- **确定**：CodeSign 之后的 App / 扩展都不带该 keychain access group → `SecItemAdd(query 含 kSecAttrAccessGroup)` 返回 `errSecMissingEntitlement` → `ShareKeychain.write()` 恒 `false`。
- **确定**：`saveToken` 恒返回 `false`（`ShareBridge.swift` 的 `guard ShareKeychain.write(payload) else { return false }`）→ `ShareTokenKeeper.ensure` 会在 `if (!saved) return;` 处止步 → **每次启动/登录都会重新签发一把，旧的永不撤销**（它只在 `saved==true` 后才 `revokeExternalToken(oldId)`）。
- **确定**：`status()` 的 `payload` 只能走 `migrateLegacyIfNeeded()` 的 legacy 分支，而 `write` 失败 → `clearLegacy()` 永不执行 → **明文令牌永久留在 App Group 容器里**（升级前它就在那儿，升级后还在那儿）。
- **确定**：`available` 只在「容器可用或读到了 payload」时为 true → 老装机仍显示「已就绪」，**用户与排查者都看不出加固没生效**。

**证据**
- `apps/adai-app/ios/Runner/ShareBridge.swift`：`ShareKeychain.write/read/baseQuery`（新增段），`saveToken` 的 guard，`migrateLegacyIfNeeded`，`status()`
- `apps/adai-app/ios/Runner/Runner.entitlements:37-45`、`ios/ShareExtension/ShareExtension.entitlements:15-27`
- 复核命令（上面三级证据的三条 shell）

**⚑ 复发信号（pitfalls 十二「付费开发者账号 ≠ 能力自动可用」的加强版）**：老坑说的是「能力要三处同时具备」——**本批踩的是第 4 处**：`asc_signing.py:ensure_profile` **只按 bundleId 关联 + 证书关联匹配就复用 profile**（`if cert_id in ids and keep is None: keep = p`），**从不对照「当前 entitlements 声明 vs profile 授权」**。所以只要账号里还有一份旧 profile 且证书没换，profile 永远不会为新能力重签。

**建议下一步验证动作**
- 复核：`security cms -D -i ~/Library/MobileDevice/Provisioning\ Profiles/*.mobileprovision | grep -c keychain-access-groups` → 预期 **0**（本机实测 0）。
- 修复方向：developer.apple.com 给**两个 App ID** 开启 Keychain Sharing → `asc_signing.py` 加「能力对比」判据（声明了但没有 → 删旧 profile 重建）→ 重新归档后**必须**用 `codesign -d --entitlements` 复验（否则又是一次「编译通过 = 生效」）。

---

### P1-1　`purge` 部分失败仍返回 `purged:true`——「删干净了」是假象

**触发条件**：`DELETE /api/v1/accounts/{userId}?purge=true`，其中**任意一个文件**删不掉（权限、被占用、符号链接、并发写入）或**列举本身失败**。

**证据**
- `AccountController.purgeUserData`：单个删除失败 → `log.warn` + 不计数、不抛出（`catch (Exception e) { log.warn(...) }`）；外层列举失败 → 整体 `catch` + 返回已删计数。
- 返回：`ResponseEntity.ok(Map.of("deleted", true, "purged", true, "purgedFiles", purged))` —— **`purged` 是无条件 `true`**，`purgedFiles` 只是「成功删掉的个数」，**响应里没有失败数/失败清单**。

**后果**：调用方看到 `{purged:true}` 的下意识结论是「数据清干净了」，实际可能只删了 3/200 个。这与本批自己的取舍（「**删不掉的如实计数**，不假装清干净」写在 javadoc 里）**相反**：如实计数了，但**没有如实报告**。属「失败可见性」族（pitfalls 十六「后端把丢行上报了，前端不展示 = 白修」的同型：这次连上报字段都没有）。

**建议下一步验证动作**
- 反证：mock 一个 delete 抛异常的场景（现有 2 条测试只覆盖全成功 / 不 purge），断言响应里应出现失败数——**当前会失败**。
- 修复方向：响应改 `{deleted, purgedFiles, failedFiles, failed: [...]}`，`purged` 改为 `failedFiles == 0`。

---

### P1-2　`purge` 只走 `listFiles`——空目录永远残留，`data/{userId}/` 不会消失

**证据**
- `purgeUserData` 用 `fileStorage.listFiles(userId, "")`；`LocalFileStorage.listFiles`（:68-85）先 `Files.walk(target)` 再 **`.filter(Files::isRegularFile)`** → **只返回文件，不返回目录**。
- 循环里只有 `fileStorage.delete(userId, path)`（逐文件），**没有删目录的那一步**。

**后果**：即使删掉全部文件，`data/{userId}/trading/_raw/`、`learn/`、`records/` … 整棵目录树仍在。`ls data/` 依旧看得见这个账号的骨架，「删干净」在文件系统视角不成立；`purgedFiles=N` 也天然无法回答「还有没有剩下东西」。

**建议下一步验证动作**
- 反证：对 `data/alice`（本机现存测试账号）跑一次 purge 后 `ls -R data/alice`——目录仍在。
- 修复方向：删文件后自底向上清理空目录（或给 `FileStorage` 加一个显式的 `deleteUserRoot`），并把「目录是否清空」纳入返回。

---

### P1-3　图片日配额：失败/拒绝都不退还；并发下还能超卖

**触发条件（两种，都很日常）**
- **A 失败不退**：`consumeImages` 记账发生在**投递执行器之前**（`LearnDigestAppService` 的 `submitImages`：`saveRawBytes` 循环 → `consumeImages` → `learnSubmitExecutor.execute(...)`）。紧随其后的三个 catch（`RejectedExecutionException` / `LearnException` / `Exception`）只做 `cleanupStaged` + `jobs.remove`，**没有一处回退配额**。
- **B 后台失败不退**：`runImageJob` 读图失败（`catch → job.fail`）、读图返回空（`text.isBlank()` → fail）同样**不回退**——用户 3 张图全失败 = 当天额度少 3，而他什么也没得到。
- **C 预检查与记账分离**：配额闸在方法入口用 `quotaRepository.imagesOn(...)` 读一次（`usedToday + images.size() > limit` 才拒），而记账在几十行之后才 `consumeImages`——中间没有任何按用户的原子占位（`jobs.compute` 只保证「同一用户单任务」，不保证配额不超）。

**后果**
- 「花了钱没拿到东西」的另一种形态：用户失败几次就把当天 30 张额度耗光，第二天才恢复；而 ui 上只看到「今天已经整理了 N 张图」——**把系统的失败说成用户的用量**。
- 并发（双端/连点）下两个请求各自通过检查 → 记账累加 → **当天可超上限**（`consumeImages` 内部只做累加与 `Math.max(0,…)`，不校验上限）。
- `LearnQuotaRepository.consumeImages` 的 javadoc 明写「**允许负数 = 回退，如受理失败时回收占位**」——**这个 negative 分支今天没有任何调用者**（`grep -rn "consumeImages" services/adai-core/src/main` 只有预检查后那一处 +1 次）；即「预留了退款的 API，但没接退款」。

**证据**
- `LearnDigestAppService.java`：配额预检查段、`saveRawBytes` → `consumeImages` → `execute` 段、三个 catch 段、`runImageJob` 失败段
- `LearnQuotaRepository.java`：`consumeImages` javadoc 的「允许负数」承诺
- 既有坑（复发信号）：`assets/pitfalls.md`「检查-再动作竞态（并发花钱）」——本批新写的正是「先判断再记账」

**建议下一步验证动作**
- 反证：`grep -rn "consumeImages" services/adai-core/src/main/java` → 预期只有 1 处调用且**无负值调用**。
- 修复方向：所有失败路径 `try { quotaRepository.consumeImages(userId, day, -images.size()); } catch (…) {}`（best-effort 退款，不掩盖原异常）；并发用 `jobs.compute` 的同一把 key 顺带做「预留」而不是事后累加。

---

### P1-4　「禁 `git add -A`」到现在仍是零机制

**证据**
- `ai-engineering/process/ship.md` 只加了一段**说明文字**（「收尾一律按路径显式暂存」+「提交前 `git status --porcelain` 复核」，并明写依靠**人工复核**）。
- 仓库实际生效的门禁是 `.githooks/pre-commit`（`core.hooksPath=.githooks`）：它查隐私（data/ 前缀、gitignore 复核）→ guard-align → guard-meta → guard.sh → shell-lint（`guard-tools.sh --shell-lint`）→ guard-sediment（软提示）。**没有任何一层**检查「暂存区是否含本批声明之外的路径」，也没有「干净工作区」检查。
- 该条目在 `REVIEW.md` 里原状态是「⚠️ 待用户拍板是否写进 ship 流程为硬规则」；本批把**文档**写了，**拍板与机制都还是没有**。

**后果**：下一次并发会话（本仓已知会发生，pitfalls 十「同仓库并发会话收尾」登记过真实事故）复现的概率不变——只是这次规范里多了一句话，而失效环节恰恰是「人记得照做」。

**建议下一步验证动作**
- 反证：`grep -rn "add -A\|ship-scope\|暂存" .githooks/ ai-engineering/guard-tools.sh` → 预期 0 命中机制代码。
- 机制方向（成本很低）：提交信息里声明 `本批路径：a/ b/`（或写 `.git/ship-scope`），pre-commit 用 `git diff --cached --name-only` 对照声明前缀，越界就拒（或 `⚠️` + 要求确认）。这是本仓既有风格（guard.sh G8/G9 都是这种「枚举 + 反例验证」的机械守卫）。

---

### P2-1　深链 `trading:today` 的兜底会「点名另一条 push 卡」

**触发条件**：任意「无 symbol」的通知（收盘小结 / 早盘计划 / 午间 / 尾盘 / 交易日志确认）→ 深链恒为 `trading:today`（`PushChannel.java` 的 `deepLink()`）→ 客户端 `_locateAndHighlight` 的 `key = "today"` 在几乎所有正文里都搜不到 → 落到兜底 `hit ??= _cards.reversed.where((c) => c.pushTitle != null).map((c) => c.id).firstOrNull`。

**后果**：如果**最新一条 push 卡不是用户点的那条**（例：10:30 的「行情提醒」卡比 09:15 的「早盘计划」更新，用户点的是 09:15 那条通知），高亮会罩在**另一条卡**上并滚到底——**「点 A 打开了 B」比不定位更糟**（代码注释里承诺的「找不到就只刷新 + 滚到底，不假装定位成功」被这个兜底**自己打破**了：它确实假装了）。`learn:review` 同理：`key="review"` 在中文复习卡里搜不到 → 兜底可能点到一张**交易**卡。

**证据**
- `apps/adai-app/lib/main_page.dart:1540-1556`（`_locateAndHighlight`，尤其 1551 的兜底行）
- `services/adai-core/src/main/java/com/adaiadai/core/kernel/push/PushChannel.java`（`deepLink()` 的 `default -> "trading:today"`）
- 反例（构造）：`content.contains("today")` 在「📋 今日操作汇总…」里也不命中（中文），必然走兜底。

**建议**：兜底只在 `key == "today"` 且**能按时间/类型对上**时使用（例如用 `deepLink` 换成能唯一定位的 id：push 事件本身有 `p.id()`，payload 里带上 `pushId` 最省事）；否则**如实不定位**。另：高亮只有 2.5 秒（`:1553`），滚到底之后用户视线还在移动，这个时长偏短（见 P3-3）。

---

### P2-2　轮换端点三端零入口；失败回滚可留「用户看不见的有效钥匙」

**证据**
- `grep -rn "rotate" apps/adai-web/lib apps/adai-admin/lib apps/adai-app/lib` → **0 命中**。即 154 个端点里的新端点没有任何 UI 入口，用户要用只能自己敲 curl。
- 回滚分支：`ApiTokenService.rotate` 的 `if (!revoke(userId, fresh.token().tokenHash()))` —— 这里的 `revoke` **没有 try/catch**。若它抛异常（存储异常），`rotate` 直接抛出、**新令牌留在库里**（明文已随异常丢失，用户永远看不到它），就形成一把用户无法识别的有效钥匙——与注释里「绝不留下两把同时有效」的承诺相反。
- 语义错位：这不是「凭据错」，但 `AuthException` 被 `GlobalExceptionHandler:81-84` 统一映射成 **401**。用户在前端会被告知「会话已失效，请重新登录」——**实际上会话好好的，只是旧钥匙没撤掉**。

**建议**：回滚用 `try { revoke(...) } catch (…) { log.error(…带 tokenHash/prefix…) }` 并考虑「先置失效标记再删」；或在 `rotate` 失败时把新令牌 id 一并返回/写审计，别让孤儿不可见。

---

### P2-3　分享令牌迁移永远完不成，而状态仍报「已就绪」（与 P0-2 连锁）

**触发条件**：老装机（明文在 App Group UserDefaults），本次升级后每次启动/登录。

**链路**：`ShareTokenKeeper.ensure` → `status()`（`available=true`，来自 container）→ `needsIssue`：legacy payload 若没记 `expiresAt` → **true** → `issueExternalToken`（**后端真的签了一把新的**）→ `saveToken` → Keychain `write` 失败（P0-2）→ **`if (!saved) return;`** → 新钥匙在服务端成为**孤儿**（不会被撤，因为撤销在 saved 之后），旧明文**继续留在容器里**。

**后果**：① 每日/每次启动累积孤儿令牌（用户看不到）；② 安全目标（不让明文躺在共享容器）**完全未达成**，而 UI 没有任何线索；③ 每次启动多一次签发往返与一次后端写盘。

**建议**：把「Keychain 能力不可用」变成**可观测状态**（见 P2-4），并在这种情况下**不要**再签发新令牌（先修能力，再迁移）——现在的行为是「明知写不进还签」。

---

### P2-4　`status()` 新增的 `storage` 字段，Dart 侧根本没投影——「可排查性」是空的

**证据**
- 原生 `ShareBridge.swift` 的 `status()` 主动加了 `"storage": "keychain" | "legacy" | "none"`，注释写「P2-分享2：**UI/排查能看出**这把钥匙存在哪儿」。
- Dart 的 `ShareExtensionStatus`（`share_extension_service.dart:7-47`）**没有 `storage` 字段**，`grep -rn "'storage'\|\"storage\"\|storage:" apps/adai-app/lib` → **0 命中**。

**后果**：一个**发了没人收**的字段。排查者按文档去找 `storage` 会一无所获；更关键的是**迁移失败（legacy）唯一的机器可读信号被丢掉了**——正是 P0-2/P2-3 沉默至今的原因之一。（同族坑：pitfalls 十六「后端上报了、前端不展示 = 白修」。）

---

### P2-5　「P3-隐私1 九个文件归零」不成立——`docs/reference/change-log.md` 里仍留着真股东代码

**证据**
- `git diff d060841..HEAD -- docs/reference/REVIEW.md` 把条目改成「**P3-隐私1 ✅ 已修（2026-09-16 晚间批）**：…存量替换建议后续批一次性机械替换…」——文字自相矛盾（标题说已修、正文说后续批再做）。
- `grep -c "A511***384" docs/reference/change-log.md` → **1**（且是**本批**新增的那一行：「真实股东代码机械脱敏（`A511***384`/`0903***313` → 合成值，9 个文件归零）」）。`0903***313` 也仍在同文件的历史条目里。
- 提交信息同样宣称「真实股东代码脱敏为合成值（9 文件归零，P3-隐私1）」。

**后果**：**范围声明为假**（9 个文件归零 ≠ 全仓归零），且脱敏这件事的意义被自己写进文档的原文抵消——真正的股东代码仍随 git 历史与当前 HEAD 传播。（`data/` 下的原件属本地资产、已 gitignore，不算入；此处只算入库文件。）

**建议**：文案改为如实范围（「测试夹具与主代码注释已归零；`docs/reference/change-log.md` 的**历史记录原文**按留痕原则保留」），或确实把 change-log 的两处也换掉。

---

### P2-6　`restore-origin` 的判据、文案、文档三处不一致

**证据**（同一功能的三份说法）
- 提交信息 / change-log：「判据 = 正文含 `## 卡片页` 或 frontmatter 带 `status`/`review_at`/`reminded_at`」
- 仓储 javadoc（`LearnCardRepository.restoreOrigin`）：「正文含 `## 卡片页`，或 frontmatter 带产品复习机制独有的 `review_at`/`reminded_at`」（**没提 `status`**）
- 实现 `LearnCardFileRepository.looksLikeProductCard`：`content.contains("## 卡片页") || content.contains("review_at:") || content.contains("reminded_at:") || content.contains("\nstatus:")`

**两个可构造的边界**
1. `"\nstatus:"` 要求**前面有换行**——若 `status:` 恰好是 frontmatter 第一行（`---\nstatus: new`），判据漏判（该卡会被拒）。
2. 判据本身**过宽**：`status:` 是极常见的通用 frontmatter 键。一张**别处工具的卡**只要写了 `\nstatus: draft`，就能被「认回 `origin: product`」→ **正是该端点声称要防的「一句话给别人的只读卡盖章」**——只是把门槛从「一句话」变成了「一个通用字段」。javadoc 里刻意排除「核心观点/关键要点」的理由（A 形态手工卡也用）同样适用于 `status:`。

**建议**：把判据收到「产品独有」集合（`## 卡片页` / `review_at` / `reminded_at`），或要求两个以上信号；同时三处说法对齐。

---

### P3-1　`previousTradingDay` 15 天打底，找不到就**静默**按今天算

**证据**：`TradingAppService.previousTradingDay` 最多回退 15 天，`return null`；调用处 `if (prev != null) resolvedDate = prev;` —— **为 null 时既不记 notes 也不记 WARN**，直接回到「拿今天当基准」的旧行为。

**后果**：春节/长假（>15 天休市）或 `isTradingDayStrict` 数据缺失时，本批的修复**静默失效**，症状与修前完全一致（用户看到的还是那个错数，且没有「今天还没开盘」的提示来解释）。属「防复发机制 fail-open」族——一个只在罕见边界失效的修复，比一个 100% 失效的修复更难发现。

**建议**：`prev == null` 时至少 `log.warn` + 在 notes 里如实说明「找不到上一个交易日，本次按今天口径」。

---

### P3-2　admin 侧删号：既删不掉数据，也从不 purge

**证据**
- `apps/adai-admin/lib/services/api_service.dart:284-286`：`deleteAccount(String userId) => _send('DELETE', '/api/v1/accounts/$userId')` —— **不传 `purge`**，全仓 admin/web 里 `grep -rn "purge"` → 0 命中。
- `accounts_page.dart:245-287`：确认弹窗只有「确定/取消」两个按钮，文案是「…此操作不可撤销」，**没有输入账号名确认**，按钮直接 `_store.delete(...)`。

**后果**：默认语义翻转后（保留数据），admin 面板的删除**从「不可逆地删账号+数据」变成「只删账号、数据永久留下」**——而 UI 文案没变、也没有 purge 入口 → 这个系统里**再也没有一条能清掉某账号数据的正常路径**（只能 SSH 进服务器 `rm -rf`）。文档还把「adai-admin 侧还要过一次『输入账号名确认』」写成既成事实（`AccountController` javadoc + `api-spec.md:2069`），属于**不存在的保护**。

---

### P3-3　深链两处体感细节

- **同值不重发**：`_pushDeepLink` 用 `ValueNotifier<String?>`，`main.dart` 的 `onTap` 里 `if (deepLink != null && deepLink.isNotEmpty) { _pushDeepLink.value = deepLink; }`。**连点同一类通知**（值相同）不触发 `ValueNotifier` 通知 → 第二次点击**不高亮**（但 `_feedRefreshTick` 仍 +1，会刷新，表现像「偶尔点了没反应」）。
- **2.5 秒**（`main_page.dart:1553`）：滚到底有动画、用户视线要跟上再找绿色描边，2.5 秒偏短；极端情况（卡片很长、滚到底耗时 >1s）用户可能一眼都没看到高亮。建议提高到 4-5 秒或改成「点击卡片/交互后消失」。

---

### P3-4　登记回填：把「别批的项」并进本批清单

- `REVIEW.md` 把 **P2-工程5**（`deploy.sh` 记忆重建）与 **P2-分享5**（分享令牌自动化）标为已修，但对**本批 diff 而言它们是 0 改动**（`git diff d060841..HEAD -- services/adai-core/deploy.sh apps/adai-app/lib/services/share_token_keeper.dart` = 空），且条目正文里仍保留「待修」字样（与 P2-5 同型的自相矛盾）。
- 本批自己的 change-log 也写着「**登记滞后回填**」——问题是**滞后回填没有指向落地 commit**，下一个人无法核对「到底哪一批做的」。

**建议**：回填时写清落地 commit/批次（如「已由 `a17cb9b` 落地」），别只翻状态标记。

---

## 三、我构造的反例（按「能触发」排序）

| # | 反例（触发条件 → 期望 vs 实际） | 现状 |
|:-:|:-------------------------------|:-----|
| R1 | 交易日 08:50 手动记一笔今天买入 → 期望「今日盈亏」不被写坏；实际 `refreshTodayPnl` 把**昨天**的盈亏写进快照 `todayPnl`，直到 15:05 才纠正 | 由 `TradingAppService:1733-1757` 逐跳可证；测试无覆盖 |
| R2 | 老装机首启 → 期望令牌迁进 Keychain 并清掉容器明文；实际 `ShareKeychain.write` 必失败（无 entitlement），明文原地不动、状态仍报就绪，还多签一把孤儿令牌 | 归档 entitlements 实测无该键；profile 无该能力 |
| R3 | `?purge=true` 且某文件删除抛异常 → 期望响应里能看到「没删掉几个」；实际 `{purged:true, purgedFiles:N}`，失败数无处可查 | `purgeUserData` 的 catch 只 log.warn |
| R4 | 盘前连发 3 次图片全部读图失败 → 期望额度退还；实际当天 30 张额度 -9，用户零产出 | `consumeImages` 只在受理时 +N |
| R5 | 用户点 09:15「早盘计划」通知（`trading:today`），而 Feed 里更新的一条是 10:30 行情提醒卡 → 期望只滚到底不点名；实际兜底把 10:30 那张**高亮** | `main_page.dart:1551` |
| R6 | 别处工具的卡写了 `\nstatus: draft` → `POST /learn/cards/restore-origin` 期望拒绝；实际通过判据、被盖上 `origin: product` | `looksLikeProductCard` 含 `"\nstatus:"` |
| R7 | 两个并发图片提交（双端）各 3 张、当日已用 28 张 → 期望只放行 2 张；实际两次预检查都过、累计到 34 张 | 预检查与记账分离、无原子占位 |

---

## 四、我不确定但值得查的

1. **归档时间线**：本机归档（09-16 22:56）**早于** entitlements 修改（23:44）。所以「提交信息说 iOS 构建通过」证明的是**旧 entitlements 能编译**，并未证明新 keychain 声明能被签进去。**要查**：`stat -f "%Sm %N"` 两个 entitlements 与 `build/ios/archive`（已实测），以及下一次构建后 `codesign -d --entitlements` 的实际内容。若哪天 Apple 侧补开了能力而 `asc_signing.py` 没重建 profile，签名仍会静默剔除该键。
2. **`purge` 与正在运行的任务**：删号后后台 learn/AI 任务若仍持有 userId，会往已删目录继续写（`FileStorage.write` 会重建父目录）→ 「purge 之后又冒出文件」。未实测，但 `purgeUserData` 的注释承认逐文件删除、`LocalFileStorage.write` 会 `createDirectories`，路径成立。
3. **`refreshTodayPnl` 的调用时机全集**：本报告只核了 5 处调用点；`RecordFlowAppService` / 截图确认链路里是否还有别的重算入口没读。
4. **`consumeImages` 的账本合并写**：`LearnQuotaFileRepository.consumeImages` 用 `next.path(IMAGES_KEY)` 后 `(ObjectNode) next.path(IMAGES_KEY)`——**同一对象既做判据又做写入目标**（`next.path()` 返回的是 `next` 里的同一引用还是缺失节点副本，取决于 Jackson 行为）。若返回副本，`images.put(...)` 改的是副本、`next.set(...)` 有兜住；但读-改-写里「月份键」与「images 键」共用同一把锁的正确性值得单独走一遍（尤其与转写 `consume` 交叉时）。未实测。
5. **`guard-prod.sh` 到期倒数的时间基准**：`date -j -f "%Y-%m-%d"`（macOS）与 `date -d` 的回落写法在非 UTC 环境取的是**本地时区的 00:00**；到期日按北京时区算差一天无实质影响，但 `days < 30` 的边界（正好 30 天）与 `-lt` 的取舍未在文档写死，属口径未登记。

---

## 五、附：本次审查未发现问题的部分（避免过度指控）

- `ApiTokenService.rotate` 的**前缀歧义拒绝 / 跨账号限定 / 空参**三个边界有真实断言（`ApiTokenServiceTest` 2 例），与 `revoke` 同口径；外部令牌也**确实调不到** `/api/v1/auth/**`（`TokenScope` 是精确白名单 + 默认拒绝，注释亦明写这一取舍）。
- `LocalFileStorage.listFiles` 是**全递归无分页**，所以 P1-2 的问题**不是**「只删了第一页」——不要按分页去修，按目录残留去修。
- `resolveTodayPnl`（15:05）与 `closeAccountUpdate` 的重算路径在盘后会把值纠正回来，所以 P0-1 是「盘前到 15:05 的错误窗口」，不是永久污染。
- 深链编码 `<type>|<deepLink>` 对旧版 App 是**向后兼容**的（旧客户端只取前半段并去前缀）；`deepLink()` 用已有字段推导确实避免了「某个构造点漏传」——这条设计判断本身成立，问题只在 `trading:today` 这个兜底值的**消费端**。
