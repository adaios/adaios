---
title: 文档契约深审——晚间批 + 深夜第二批（api-spec / status / REVIEW / v1.0.0）
description: 2026-09-17 docs-reviewer 只读审查报告——范围 d060841..HEAD 的文档类改动（3dadfd9 + 42dc0b5）；核数字一致性、端点契约逐项对拍、REVIEW「已修」声明诚实性、发布核对差距表；含 P1×1/P2×6/P3×7 逐条位置与证据
version: 1
created: 2026-09-17
updated: 2026-09-17
status: active
lines: 178
depends-on: []
related:
  - ../REVIEW.md
  - ../../architecture/api-spec.md
tags: [review, docs, audit, api-spec, honesty]
---

# 文档契约深审：晚间批 + 深夜第二批

> 角色：docs-reviewer（`ai-engineering/roles/docs-reviewer.md`）· 性质：**只读审查**（B7，未改任何代码/文档）
> 范围：`git diff d060841..HEAD` 的**文档类改动**，主目标 `3dadfd9`（晚间批）与 `42dc0b5`（深夜第二批）。
> `bc6656b`（另一会话）不在范围内，发现的问题单列于 §四。

## 一、范围与基线

| 项 | 值 |
|:---|:---|
| 基线 | `d060841`（2026-09-16，「把本批 8 项标为已修」） |
| 审查对象 | `3dadfd9`（晚间批）+ `42dc0b5`（深夜第二批）的文档改动 |
| 文件清单 | `docs/architecture/api-spec.md` · `docs/reference/{status,change-log,task-log}.md` · `docs/review/REVIEW.md` · `docs/releases/v1.0.0.md` · `ai-engineering/process/{audit,review,ship}.md` · `ai-engineering/guard-prod.sh` |
| 自动化门禁复核 | `guard-align.sh` **PASS**（A1 154 端点全登记；A2 后端 1939 / app 359 / admin 69 / web 302）· `guard-meta.sh` 审查开始时 **PASS**（143 文件）—— 均为独立重跑，非引用批次声明。⚠️ 三段报告落盘后 guard-meta 变 **3 FAIL**（M3 孤儿），见 P2-6 |
| 代码真相源对拍 | `LearnController` / `LearnCardFileRepository` / `AuthController` / `ApiTokenService` / `AccountController` / `TradingAppService` / `ApnsPushChannel` / `LocalFileStorage` / `GlobalExceptionHandler` 直接读源码 |

## 二、结论（分级计数）

| 级别 | 计数 | 一句话 |
|:----:|:----:|:-------|
| P0 | **0** | 无数据丢失/不可逆破坏 |
| P1 | **1** | `change-log.md:9` 仍含真实股东代码，而同一行自称「9 个文件归零」、REVIEW 标 ✅ 已修 |
| P2 | **6** | REVIEW ✅ 与正文系统性矛盾 · restore-origin 判据与文档/守卫意图三方不一致 · api-spec 图片配额未进正文（D22）· 删号 `purge` 的 admin 括注失真 · rotate 失败分支实返 401 未登记 · 同日三报告未登记致 guard-meta M3 FAIL（本轮自身引入） |
| P3 | **7** | 见 §三 P3 |
| 范围外 | **2** | `bc6656b` 会话产物（routine.md lines 漂移 / ideas 索引缺登记） |

### 先说核对通过的（用户点名的 5 个重点）

1. **数字一致性：四处互相一致，且与代码真相源一致 ✅**
   - api-spec 头部 `v3.71 | 2026-09-17`（:5）+ 变更记录 v3.71 行（:13）**同版**；v3.70 行（:14）齐备。
   - `status.md:23` 端点 **154** / Controller **22**；`change-log.md:8` 写「端点 153→154 · 后端 1939 · app 359」；`REVIEW.md:71` 写「后端 1939 · app 359 · 端点 153→154」——**三处一致**。
   - 独立复算（非引用脚本输出）：`interfaces/` 下 5 类 Mapping 注解原文计数 = **154**（Get 68 / Post 49 / Put 15 / Patch 9 / Delete 13），`*Controller.java` = **22**；测试数两种正则交叉计数 = 后端 **1939**（`@Test` 1914 + 全限定 25）/ app **359** / web **302** / admin **69**。
2. **端点契约逐项对拍 ✅（含用户特别提醒的 cardPath）**
   - `POST /learn/cards/restore-origin`：路径、`@RequestParam type/title`、**无 `cardPath`**、200 响应三字段 `{type,title,writable}`、判据不成立→人话 400、幂等——与 `LearnController.java:251-266` + `LearnCardFileRepository.java:703-728` 一致。**文档未写 cardPath**（`cardPath` 仅出现在 REVIEW P2-learn23「未做」行与 change-log「未做」句里，属正确的未做登记，非失真）。
   - `POST /auth/tokens/{idOrPrefix}/rotate`：路径、label/scopes 继承、有效期重算 90 天（`ApiToken.DEFAULT_TTL_DAYS` + `ApiTokenService.java:117`）、前缀歧义回空→404、找不到 404、200 字段与 `POST /auth/tokens` 同形状——与 `AuthController.java:203-223` + `ApiTokenService.java:195-228` 一致。
   - `DELETE /accounts/{userId}?purge=`：默认 204 无 body 保数据、`purge=true` → 200 `{deleted,purged,purgedFiles}`、递归删文件且删不掉的如实不计入——与 `AccountController.java:226-245/254-268` + `LocalFileStorage.listFiles`（`Files.walk` 递归）一致（细节失真见 P2-4）。
3. **REVIEW 14 条「已修」声明：代码侧全部真实存在，无一条是空头 ✅**
   `P2-交易51`（`TradingAppService:1516-1525,1659-1667` 盘前改判 `quoteIsFromPreviousDay`）· `P2-learn21`（新端点+幂等）· `P2-learn25`（`VisualAiClient.ask(...,Integer)` + yml `image-max-tokens:4096`）· `P2-learn26`（yml `image-daily-limit:30` + `LearnDigestAppService:336-353` fail-closed）· `P2-learn28`（按决策关闭，无需改码）· `P2-分享2`（`ShareBridge.swift:47-58` 共享 Keychain）· `P2-分享3`（`ShareViewController.swift:239-248` 先读共享容器）· `P2-分享5`（`share_token_keeper.dart` 存在）· `P2-工程2`（`ship.md:102-105` 禁 `git add -A`）· `P2-文档2`（`audit.md:19/36`、`review.md:45`、`README.md:39/41` 均已是 12 官口径）· `P3-隐私1`（源码/测试已换 `A000000001`/`0000000000`，**但文档侧漏一处，见 P1-1**）· `P2-APNs1`（`ApnsPushChannel:274-277` root 级 `adaiDeepLink`）· `P2-APNs5`（`guard-prod.sh:348-351` 三项倒数）· task-log #149（`AccountController` purge + `RESERVED_USER_IDS` 禁 `default`，`AccountController.java:51/104`）。
   → **结论：没有「标已修但代码里找不到」的条目；问题出在「标了已修但条目正文没同步」（P2-1）与一条清理不彻底（P1-1）。**
4. **v1.0.0 发布核对差距表：文中写的一列全部与原文一致 ✅**（后端 433/前端 171 → v1.0.0:48；api-spec v3.18 · 51 端点 → :67；「REVIEW.md P1/P2 已清零」→ :73；「#179 … v1.0.1 立项」→ :75；全文无 learn/APNs/TestFlight → grep 零命中）。「实测」列的基线也可复现：`git show d060841:docs/reference/status.md` = 1926 / 357 / 302 / 69 + 端点 152，`api-spec` = v3.69——**与表中数字逐字相符**。（唯一存疑：「未修 20 条」不可复现，见 P3-4。）
5. **frontmatter/门禁 ✅（有盲区）**：`guard-meta.sh` 独立重跑 PASS（143 文件）。本次改动落在 guard-meta 范围外的文件见 P3-5。

## 三、逐条问题

### P1-1 · 真实股东代码仍在 git 追踪文件里，且同批宣称「归零」

- **位置**：`docs/reference/change-log.md:9`（唯一残留）
- **证据**：
  - `git grep -n "A511***384\|0903***313"` → **仅命中 change-log.md:9**（源码、测试、REVIEW 均已换合成值：`TradingImportParser.java:360` = `A000000001`，`TradingAppServiceTest.java:1121+` = `0000000000`）。
  - 同一行（该批自己的记录）写着：「真实股东代码机械脱敏（`A511***384`/`0903***313` → 合成值，**9 个文件归零**，P3-隐私1）」——**该行本身就是第 10 个文件**，声明与事实矛盾。
  - `REVIEW.md:365` 标「**P3-隐私1 ✅ 已修（2026-09-16 晚间批）**」，但正文仍写着「分布在 5 个测试文件 + 主代码注释 + **change-log**」且保留「存量替换建议后续批一次性机械替换」——即条目自己列出的 change-log 这一处**没做**。
- **影响**：属可关联个人标识（B3「个人数据不进 git」的同族）；文件已在 git 历史中，仅改工作区不能消除，但至少不能让 HEAD 继续携带。同时是「✅ 已修」不诚实的一条实例。
- **建议**：把该行的两串替换为合成值（该行无断言/功能依赖，纯文本替换）；REVIEW:365 正文改为「已机械替换 9 个文件 + change-log 行（含），并说明历史提交仍含旧值，如需彻底清除要走 filter-repo/接受留痕」。若历史不可改，就把「归零」改成「HEAD 归零」并注明历史留痕。

### P2-1 · REVIEW 的 ✅ 标记与条目正文系统性矛盾（14 条）

- **位置**：`docs/review/REVIEW.md` L300 / L306 / L310 / L311 / L329 / L336 / L338 / L339 / L343 / L346 / L347 / L349 / L365 / L366
- **证据**：本批在 ID 单元格追加了 `✅`，但**条目正文一字未动**，于是 11 条正文仍在明写「未做/未收紧/待排/待修」：

  | 行 | ID | 正文仍在说 |
  |:--|:---|:---|
  | L306 | P2-learn21 ✅ | 「**未做自动补标/一键恢复**」+ 建议列「可加『补回来源标记』入口」 |
  | L310 | P2-learn25 ✅ | 「**仍未做**：输出上限（默认 2048 token）不可按调用覆盖」 |
  | L336 | P2-文档2 ✅ | 「**仍未做**：`README.md:39,41`、`audit.md:36`、`review.md:45` 的『9 审查官』计数」 |
  | L338 | P2-工程2 ✅ | 「**待用户拍板**：是否写进 ship 流程为硬规则」 |
  | L339 | P2-APNs1 ✅ | 「**待排**」「**不装作已做**」 |
  | L343 | P2-APNs5 ✅ | 「**仍缺**：日历提醒（提前 30 天）…**待办**」 |
  | L346 | P2-分享2 ✅ | 「**待排**（可评估 Keychain + `kSecAttrAccessibleAfterFirstUnlock`）」 |
  | L347 | P2-分享3 ✅ | 「**未收紧**…先如实登记」「**待排**」 |
  | L349 | P2-分享5 ✅ | 「**待拍板后实施**（用户 2026-09-15：『等会再做这个功能』）」 |
  | L365 | P3-隐私1 ✅ | 「存量替换建议后续批一次性机械替换」（且确未替换完，见 P1-1） |
  | L366 | P2-工程5 ✅ | 「**待修**（本批如实登记，未顺手改）」 |

  另 3 条是「✅ 但无处置说明」：L300 P2-learn28（正文「待用户决定」，按用户决策关闭）· L311 P2-learn26（建议列仍写「复用额度机制或加图片整理日配额」）· L329 P2-交易51（正文只有课题描述）。
- **同类第二条证据（自定规则）**：`REVIEW.md:65` 自定「未修复项滚动保留，**已修复标 ✅ 移入已修复区**」——这 14 行标了 ✅ 却仍在「🔴 P2/P1/战略缺口（未修复）」区里，既没移走也没改正文。
- **影响**：REVIEW 是「未修项真相源」（AGENTS.md 声明）。一个 AI 读 L347 会得出「扩展基址仍未收紧」，读 L71 批次日志会得出「已修」——**同一文件同一批次两个相反结论**，正是「文档不一致比缺失更误导 AI」。
- **建议**：给 ✅ 一个统一定义（如 `✅ 已修（日期 + 一句话 + 已移入已修复区）`）；本批 14 条逐条在正文尾部追加处置句并把已闭环的移入「✅ 已修复区」，或反过来去掉 ✅ 只保留批次日志的说明（二选一，不要两存）。建议同步进 `checklists/review-docs.md`（D40/D56 已有同族条目，可加「ID 标记与正文状态必须一致」）。

### P2-2 · restore-origin 判据：文档说 frontmatter、实现扫全文，可绕过只读保护（P1-learnA 同型）

- **位置**：`docs/architecture/api-spec.md:2564` · `services/adai-core/.../infrastructure/storage/LearnCardFileRepository.java:737-742` · `interfaces/LearnController.java:244-249`
- **证据**：
  - api-spec：判据 = 正文含 `## 卡片页` 段，或 **frontmatter 带产品独有键**（`status`/`review_at`/`reminded_at`）。
  - 实现：`content.contains("## 卡片页") || content.contains("review_at:") || content.contains("reminded_at:") || content.contains("\nstatus:")`——**对整份文件匹配，不限于前言块**（`\nstatus:` 只要求行首，正文里一行 `status: 已完成` 同样命中）。
  - `LearnController.java:244-249` 的 javadoc 又只写 `review_at`/`reminded_at`（漏 `status`）→ **三处口径各不相同**。
- **影响**：这正是 2026-09-12 `P1-learnA` 修过的形态——当时 `hasProductOrigin` 扫全文被判为「别处整理的卡可被产品改写」，修法是「只在前言块内匹配」。本批新增的恢复入口又把同一类判据写成全文匹配：一张别处整理的笔记只要正文出现 `review_at:` / 行首 `status:`，就会被「认回」成产品卡 → 之后 PATCH 可改写并注入产品模板空段。同时文档宣称的判据比实现更严，读者会高估只读保护的强度。
- **建议**：把 `looksLikeProductCard` 收敛到前言块（复用 `hasProductOrigin` 的前言块解析），并让 api-spec / Controller javadoc / 仓储注释三处口径统一（含或不含 `status` 都要一致）。（按 B7 本轮只报告，未改。）

### P2-3 · api-spec 变更记录声称的行为变化没进 § 正文（D22 同型）

- **位置**：`docs/architecture/api-spec.md:14`（v3.70 变更记录）vs `docs/architecture/api-spec.md:2499-2515`（`POST /learn/digest/image` 正文）
- **证据**：变更记录写「`POST /learn/digest/image` 加每日张数上限（`adai.learn.image-daily-limit`，默认 30，0=不限），**超限 400 人话**；账本读不出来 → **fail-closed 拒绝整理**」。正文的 400 清单（:2514）仍只有「没有图片 / 非图片类型 / 单张超 5MB / **超过 3 张** / type 非法 / 空图」，全文（除变更记录行）grep `image-daily-limit|日配额|30 张` **零命中**。
- **影响**：前端拿到「今天图片整理额度用完了」这条 400 时，契约里查不到这个分支；下一个会话会以为日配额不存在（正是 checklist D22 记录过的既有 P1 形态）。
- **建议**：在 :2514 的 400 行补「当日图片整理张数超上限（`adai.learn.image-daily-limit`，默认 30，0=不限）」+ 在 :2513 附近补「账本 `learn/_quota.json` 的 `images` 键读不出 → fail-closed 拒绝整理（403/400 择一与实现一致）」。

### P2-4 · 删号 `purge` 的括号注描述了不存在的 admin 流程，且 purge 无任何客户端入口

- **位置**：`docs/architecture/api-spec.md:2069` · `docs/reference/task-log.md:317` · 对照 `apps/adai-admin/lib/pages/accounts/accounts_page.dart:245-284` · `apps/adai-admin/lib/services/api_service.dart:284-286`
- **证据**：
  - 文档：「`true` = 连同该用户目录下的文件一起清理（**不可逆**，adai-admin 侧还要过一次『**输入账号名确认**』）」。
  - admin 实际：`_deleteAccount` 是普通二次确认对话框（文案里插值账号名：「确定删除账号「adai」？…此操作不可撤销」），**没有让人输入账号名**；且 `deleteAccount(userId)` 打的 URL 是 `DELETE /api/v1/accounts/$userId`——**不带 `purge` 参数**。全仓 `apps/adai-admin` grep `purge` **零命中**。
- **影响**：① 契约宣称的安全确认流程不存在；② `purge=true` 这条不可逆路径当前**只有 curl 可达**，文档却读起来像「后台点两下就能清数据」。
- **建议**：二选一——(a) 补齐 admin 的「输入账号名确认 + 显式 purge」入口；(b) 把括注改为「当前无产品端入口（admin 只走默认保数据删除），purge 需显式调用」。task-log:317 同一句话同步。

### P2-5 · rotate 的失败回滚分支实际返回 401，文档未登记且与提示语/客户端行为冲突

- **位置**：`services/adai-core/.../application/ApiTokenService.java:220-224` · `interfaces/GlobalExceptionHandler.java:81-84` · `docs/architecture/api-spec.md:170-178`
- **证据**：旧令牌撤不掉时 `rotate` 抛 `AuthService.AuthException("换钥匙没成功（旧的没撤掉），这次先不动它，稍后再试一次")`；`GlobalExceptionHandler` 对 `AuthException` **一律映射 401**。api-spec 的 rotate 段只登记 `200` / `404`。
- **影响**：401 在本项目是「会话失效」语义——`status.md` 明确 app/web 的 `ApiService` 是「Bearer/401 **全局回登录页**」。于是一次服务端内部失败（撤销写盘失败）会把用户**踢回登录页**，而返回文案说「这次先不动它，稍后再试一次」；且「服务端没能完成动作」用 401 也不符合语义。
- **建议**：回滚失败改用 500/503（或自定义业务异常映射 409），并在 api-spec 段补一行；若坚持复用 AuthException，至少让前端对 rotate 的 401 关掉全局登出。

### P2-6 · 同日三份审查报告未登记 → `guard-meta` M3「孤儿」实测 FAIL（本轮审查自身引入，**非**两个 commit 造成）

- **位置**：`docs/review/_index.md`（audits 清单表，:24 起）；涉及本文件与同日同轮其它审查报告（`docs/review/audits/2026-09-17-deep-review-*.md`——文件名随各会话产出变动，本次两次实测分别出现 backend/adversarial 与 adversarial/frontend）
- **证据**：审查开始时 `bash ai-engineering/guard-meta.sh` → `PASS (143 files)`；三段报告落盘后重跑 → **`META-GUARD: 3 FAIL`**，三条均为 `M3 …: 孤儿（无引用且不在 _index 清单）`（含本文件）。
- **影响**：commit 信息里「guard-meta PASS」是当时的真话，但**当前工作树过不了门禁**；若照现状收尾，`/ship` 的 guard-meta 闸会拦下（或更糟：有人为了过闸而把报告删掉）。
- **建议**：收尾时把三段报告按 `_index.md` 既有格式登记一行（含一句话结论 + status），再跑一次 guard-meta。（按约束本轮**未**代为登记，只报告。）

### P3（7 条）

| # | 位置 | 问题 | 证据 / 建议 |
|:--|:-----|:-----|:-----------|
| P3-1 | `docs/reference/status.md:23` | 端点行有**重复残片**：`**+1（2026-09-16 晚间批）**+1（2026-09-16 晚间批）：POST /learn/cards/restore-origin…`（星号也未闭合） | 3dadfd9 时该行是干净的（`**+1（2026-09-16 晚间批）：…**`），42dc0b5 改写时引入。机械清理重复片段并把 `**` 配对 |
| P3-2 | `docs/architecture/api-spec.md:2559-2566` | 新端点段未登记 `403`（learn 插件未启用）与 `400`（type 非法「type 仅支持 ai/trading/other」） | 同文件其它 learn 端点都写了这两条；`LearnController.java:256-260` 确实会返回 |
| P3-3 | `docs/review/REVIEW.md:1-8` | frontmatter 停在 `updated: 2026-09-16`、`last-review: 2026-09-14`、`baseline/mode` 仍是 09-14 批次，但文件已新增 `2026-09-17` 行 | frontmatter-spec §二：`updated` 由 /ship 回写；guard-meta 范围不含 REVIEW.md，无门禁 |
| P3-4 | `docs/releases/v1.0.0.md:15` | 「未修 **20 条**」不可复现 | 基线 `d060841:REVIEW.md` 未修区「无 ✅ 行」= 25（战略 2 + P1 4 + P2 19）；剔掉自述「复核不成立/误报」的 P1-交易15/16 = 23；+P0/P3 区两条 bullet = 25。20 这个数没写出统计口径。建议改「未修项见 `REVIEW.md`」或写明规则（**待验证**：可能作者另有口径） |
| P3-5 | `docs/architecture/api-spec.md` · `docs/reference/{status,change-log,task-log}.md` · `docs/releases/v1.0.0.md` | 本次均被编辑，但仍**无 frontmatter** | frontmatter-spec §四「渐进：存量 `docs/**` 文档下次编辑时顺手补」→ 本批正是「下次编辑」；guard-meta 范围不含这五个文件（D44/D52 已登记的盲区），故无提示。建议至少给这五个文件补最小 frontmatter |
| P3-6 | `ai-engineering/guard-prod.sh:349` | 把「iOS 描述文件 / 付费账号」合成一条硬编码 `2027-09-13` | 两者到期机制不同（描述文件由 Xcode 重签、账号按购买周年续费），合并成一条后任一变化都会给出错误倒数。建议拆两行或注明「以较早者为准 + 来源」。**待验证**：实际两个日期是否真同为 2027-09-13 |
| P3-7 | `docs/architecture/api-spec.md:591` | `/trading/positions/daily` 的 `notes` 字段说明仍只写「未计入项的人话说明」，未登记 v3.70 新增的第二种语义 | 实现（`TradingAppService.java:1521-1524`）会在盘前/非交易日把「今天还没开盘…下面是 X 的当日盈亏」写进 `notes` 首行——同一字段两种含义，消费端（app 用它决定是否显示橙色「有几笔我没算进去」）会误判 |

## 四、范围外单列（`bc6656b` 会话产物，只标注不改）

| # | 位置 | 问题 | 证据 |
|:--|:-----|:-----|:-----|
| O-1 | `docs/guides/routine.md:1-14` | frontmatter `lines: 174` vs 实际 `wc -l` = **182** | `git log d060841..HEAD -- docs/guides/routine.md` → 仅 `bc6656b` 改过；guard-meta 范围不含 `docs/guides/`，无人回写 |
| O-2 | `docs/ideas/_index.md:23-27` | 「文件清单」未登记新增的 `20260916-plugin-and-cold-start-discussion.md`（也不含存量的 `20260816-personal-data-import.md`） | `docs/ideas/README.md` **已登记**新文件，`_index.md` 未登记；`_index.md` 自己写着「新增文档：补本索引 + frontmatter」。新文件本身 frontmatter 完整（`lines: 305` = `wc -l` 305 ✓，related 链接可解析 ✓），`docs/review/audits/2026-09-17-wiring-audit.md` 亦已在 `docs/review/_index.md:24` 登记、frontmatter `lines: 192` ✓ —— **两份产物的 frontmatter/断链治理是干净的，唯一缺口是 ideas 索引漏登** |

## 五、覆盖面声明

**已覆盖**
- `3dadfd9` / `42dc0b5` 触及的全部 9 个文档文件 + `guard-prod.sh`，逐 hunk 读完。
- 数字一致性：api-spec 头部版本 × 变更记录 × status.md × change-log × REVIEW × 批次日志 × 代码真相源（注解计数、两种测试正则）七方对拍。
- 三个新/改端点与 5 个源文件逐项对拍（路径、参数、无 `cardPath`、状态码、响应字段名、默认值、递归删文件语义、TTL、回滚顺序）。
- REVIEW 本批 14 条 ✅ 逐条在源码/脚本/文档里找落点；task-log #149 逐项（purge + `RESERVED_USER_IDS`）。
- v1.0.0 差距表「文中写的」列逐字回原文核；「实测」列回 `d060841` 快照核。
- `guard-align.sh` / `guard-meta.sh` 独立重跑（非引用批次结论）；`guard-meta` 的 scope 读源码确认（含哪些目录、不含哪些）。
- 范围外产物只做**治理面**检查：frontmatter、`lines`、`_index.md`/`README` 登记、related 断链。

**未覆盖 / 未证实**
- **未运行任何测试套件**：测试数是「声明计数」（注解/正则），与 `guard-align` 同源但有共同盲区（如 `@ParameterizedTest`、`group()` 包裹的计数口径）；未执行 `gradlew test` / `flutter test` 验证「1939 全绿」的"全绿"。
- **未验证 web/app/admin 的具体代码改动**（本批未触三端逻辑；app 侧 357→359 只核了数量）。
- **未做 iOS 侧实测**（无 Xcode/flutter 运行）：`Xcode` 重签、Keychain 跨 target 实际可读性、真实 401 迁移路径均未验证。
- **未连生产**：`guard-prod.sh` 倒数三项、`P2-工程5` 的 `deploy.sh` 真实执行、公安备案/描述文件真实到期日均未取证（P3-6 属待验证）。
- **未审 `bc6656b` 的脚本内容**（`noon-task.sh` / `setup-launchd.sh` / `guard-prod.sh` 的午间部分），只看了它对文档治理的影响。
- **未逐条核 REVIEW「已修复区」历史 ✅**（只核本批 14 条 + task-log #149）。
- **未核 `docs/architecture/api-spec.md` 全量 2732 行的存量失真**（只针对本批 hunk 及其直接上下文）。

## 六、不确定项（待验证）

1. **v1.0.0「未修 20 条」的统计口径**（P3-4）——三种算法都得不到 20，可能作者按「不含待拍板的产品定性项」计数；未找到留痕。
2. **`guard-prod.sh:349` 的「描述文件 / 付费账号」是否真同日到期**（P3-6）——`status.md` 只登记了描述文件 2027-09-13，账号购买周年未见记录。
3. **`\nstatus:` 判据的实际触发概率**（P2-2）——已确认代码是全文匹配，但未构造真实外部卡做端到端复现（只做静态判读，未运行探针）。
4. **`purge=true` 是否曾被人工/脚本调用过**（P2-4）——未查生产日志，无法排除「有人用 curl 清过数据」。
