---
title: 后端深度审查报告：晚间批 + 深夜第二批（d060841..HEAD）
description: backend-reviewer 对 3dadfd9 / 42dc0b5 两个 commit 的后端改动逐条核证——盘前当日盈亏口径、令牌轮换回滚、删号 purge、图片日配额、restoreOrigin 判据、推送深链
version: 1
created: 2026-09-17
updated: 2026-09-17
status: active
lines: 253
depends-on:
  - ../../architecture/api-spec.md
related:
  - ../REVIEW.md
tags: [review, backend, audit]
---

# 后端深度审查报告：晚间批 + 深夜第二批

> 审查官：`ai-engineering/roles/backend-reviewer.md`（只报告不修改，B7）
> 审查方式：读 diff + 读实现上下文 + 跑测试 + 跑守护脚本；每条结论带位置与证据

## 一、范围与基线

| 项 | 值 |
|:---|:---|
| 基线 | `d060841..HEAD`（51 文件，+1327 / −131），HEAD = `42dc0b5` |
| 本轮目标 commit | `3dadfd9`（晚间批）、`42dc0b5`（深夜第二批） |
| 范围外 commit | `bc6656b`（午间谷时任务壳 + LaunchAgent，另一会话）→ 见附录 A 单独标注 |
| 后端文件 | 9 个 main + 7 个 test（`services/adai-core/`） |
| 测试基线 | 后端 1939（status.md）；本批 +10 用例 |

## 二、守护检查结果

| 检查 | 命令 | 结果 |
|:---|:---|:---|
| 相关测试 | `./gradlew test --tests "*DailyPnlComputeTest*" …`（7 类） | **BUILD SUCCESSFUL**：DailyPnlCompute 18 · LearnDigestAppService 51 · LearnCardFileRepository 68 · AccountController 39 · ApiTokenService 27 · ApnsPushChannel 28 · TradingAppService 65 —— **296 用例 0 失败 0 错误 0 跳过** |
| 工具链守护 | `bash ai-engineering/guard-tools.sh` | 6 通过 / 1 警告 / 0 失败（警告＝每周审查尚无日志，与本批无关） |
| 契约对齐 | `bash ai-engineering/guard-align.sh` | **PASS**：154 端点全部在 api-spec.md；测试数 1939/359/69/302 一致 |
| 元数据治理 | `bash ai-engineering/guard-meta.sh` | **PASS**（143 files，edges/lines/orphans 均 ok） |
| 分层依赖（C7） | 人工核对 diff 全部 import | 无新增违规；`AccountController` 直接用 `kernel.storage.FileStorage` 端口（kernel 为共享内核，不构成 infrastructure 反向依赖） |

## 三、结论

| 级别 | 数量 | 摘要 |
|:---|:---:|:---|
| P0（数据丢失） | **0** | 未发现数据丢失路径 |
| 战略 | **1** | 「行情所在交易日」这个口径只活在 `dailyPnlDetail` 内部，返回值不带「生效日/是否盘前」；消费方各自决定展示 → 同一错误已在另一出口复发（P1-2） |
| P1 | **2** | ①`restoreOrigin` 判据扫全文且 `status:` 对 A 形态卡同样成立 → 只读保护可被一句话绕过（P1-A 同类复发）②`pnl-periods` 盘前把上一交易日当日盈亏当 today，并把差额全量回填周/月 → 双计且无标注 |
| P2 | **4** | ③轮换回滚失败抛 AuthException → 401 → 前端全局登出（契约未记）④purge 全失败仍报 `purged:true` ⑤配额记账在受理前、执行器拒绝路径不回退（违反接口自身声明的回退语义）⑥盘前 `refreshTodayPnl` 被新 notes 挡住且日志原因说错 |
| P3 | **6** | ⑦`deepLink()` default 猜 `trading:today` ⑧节假日表无覆盖边界自检（15 天回退够用）⑨盘前口径无端到端测试且测试自我同义⑩`dayChangePct` 标签口径未标注⑪purge 留空目录 ⑫配额两次 `now()` 跨午夜 |

## 四、逐条

### 战略-1（机制根因）：口径切换没有随返回值一起交付给消费方

- **问题**：`dailyPnlDetail(userId, date)` 内部静默把 `date` 换成「行情所在交易日」，但返回值 `DailyPnlDetail(todayPnl, notes, bySymbol)` **没有任何字段说明「这个数属于哪一天 / 是否盘前」**。它现在有 3 个消费方，各自行为不同：账户卡落盘（`refreshTodayPnl`）、持仓列表（透传 notes，用户能看到标注）、`pnl-periods`（丢掉 notes，看不到标注）。于是「把上一交易日当今天」这个错误只在**一个**出口被消灭，换到另一个出口照旧发生（见 P1-2）。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/application/TradingAppService.java:1507-1527`
- **证据**：record 定义只有 3 个组件（同上 1507-1508 行）；`DailyPnlResult`（1706 行）更只透传 `todayPnl + notes`；P1-2 的消费方 `EquityCurveController:92` 只取 `.todayPnl()`。
- **建议**：把 `DailyPnlDetail` 增一个 `effectiveDate`（并与 `date` 相等时也为该日）或 `asOf` 字段，由消费方显式决定标题/是否展示；`notes` 不再是唯一的口径信号。

### P1-1：`restoreOrigin` 判据扫全文、且 `status:` 对 A 形态卡同样成立 → 只读保护可被一句话绕过

- **问题**：判定「这张卡看起来是本产品写的」用的是**整篇正文 contains**，其中 `content.contains("\nstatus:")` 命中「任意位置出现行首 `status:`」。而本项目的 **Mac 技能（A 形态手工卡）frontmatter 模板本身就含 `status`** —— 也就是说：**只读的外部卡默认就能满足判据**，调用 `POST /learn/cards/restore-origin` 会给它追加 `origin: product`，只读保护失效，此后产品侧编辑/流转/反哺都会落到「别人整理的文件」上。这与 2026-09-12 对抗审查 **P1-A 是同一个坑**：那次修的正是「origin 判据扫全文致外部卡可被改写」，`hasProductOrigin` 现在是限定前言块的（见证据 2），新代码却退回扫全文。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/infrastructure/storage/LearnCardFileRepository.java:737-744`（`looksLikeProductCard`）；调用点 `:703-730`（`restoreOrigin`）
- **证据（1）代码**：
  ```java
  private static boolean looksLikeProductCard(String content) {
      return content.contains("## 卡片页")
              || content.contains("review_at:")
              || content.contains("reminded_at:")
              || content.contains("\nstatus:");      // ← 扫全文、且 status 非产品独有
  }
  ```
- **证据（2）同类修复的正解就在同文件**（`:161-171`）：
  > `hasProductOrigin`：**对抗审查 P1-A（2026-09-12）修复：只在前言块里找**——原先扫全文，外部卡正文/代码块里只要出现一行 `origin: product` …就会被误判成「产品卡」进而被产品改写。正文一概不算。
- **证据（3）A 形态卡模板确含 status**：`ai-engineering/skills/learn-digest.md:79`
  > **frontmatter**：learn 卡片模板（title/type/source/created/**status**/trade_related/tags）
  同文件 24 行进一步明确：「**产品卡带 `origin: product` 标记（用于区分可写性）**」——区分键是 origin，不是 status。
- **证据（4）文档把错判据写成「产品独有键」**：`docs/architecture/api-spec.md`（v3.70 restore-origin 段）
  > 判据 = 正文含 `## 卡片页` 段，或 frontmatter 带产品独有键（`status` / `review_at` / `reminded_at`）
- **证据（5）测试反例恰好回避了这条**：`LearnCardFileRepositoryTest.java:1043-1062` 的「别人的卡」样本 frontmatter 只有 `title/type/topic/created`，**故意没有 status** → 测绿，但真实 A 形态卡带 status 的路径无覆盖。
- **建议**：判据改为与 `hasProductOrigin` 同款「只在 `---…---` 前言块内匹配」，并**删掉 `status:` 这一条**（它对两类卡都成立，无区分力）；`review_at/reminded_at` 也限定前言块。反例测试补一条「frontmatter 带 `status: new` 但没有产品痕迹」的外部卡必须被拒。

### P1-2：`pnl-periods` 在盘前把「上一交易日」的当日盈亏当成 today，并把差额全量回填周/月 → 双计且无标注

- **问题**：`GET /trading/pnl-periods`（app「收益日历 / 日周月盈亏」数据源）用 `dailyPnlDetail(userId, LocalDate.now())` 覆盖 `today`，再把 `realToday − p.today().pnl()` 作为 delta 加进 `week`/`month`。本轮改动后，盘前（<9:30）与非交易日 `dailyPnlDetail` 返回的是**上一交易日**的值，于是：①`today.pnl` 显示昨天的数；②`p.today()` 的守卫 `!= null` **恒真**（`window()` 永远返回非 null 对象，无点时 pnl=0），所以差值 = 上一交易日的全额被加进周/月——而昨天那笔**已经在周/月窗口里**，构成重复计入；③`notes`（含新加的「下面是 X 的当日盈亏」标注）在这个出口被丢弃，用户看不到任何口径提示。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/interfaces/EquityCurveController.java:90-100`；`services/adai-core/src/main/java/com/adaiadai/core/application/EquityCurveService.java:385-401`；`TradingAppService.java:1518-1527`
- **证据（1）覆盖 + 回填**：
  ```java
  java.time.LocalDate today = java.time.LocalDate.now();
  BigDecimal realToday = tradingAppService.dailyPnlDetail(userId, today).todayPnl();  // 丢弃 notes
  if (realToday != null && p.today() != null) {
      BigDecimal adjust = realToday.subtract(p.today().pnl());
      p = new EquityCurveService.PnlPeriods(
              setPnl(p.today(), realToday), withPnl(p.week(), adjust), withPnl(p.month(), adjust), …);
  ```
- **证据（2）`window` 恒非 null、无点即 0**：`EquityCurveService:391-401` 中 `pnl` 从 `BigDecimal.ZERO` 起累加，末尾 `return new PeriodPnl(key, pnl, pct, base, from, partial)`——没有「窗口内无数据 → null」的分支。
- **证据（3）盘前切换**：`TradingAppService:1519-1527`——`quoteIsFromPreviousDay(date)` 为真时 `resolvedDate = previousTradingDay(date)`，并把提示只加进 `notes`。
- **触发条件**：交易日 9:30 前，或周末/节假日访问该端点（前端收益日历进页即调）。数值后果：today 错位 1 个交易日；week/month 把上一交易日的盈亏**多算一次**。
- **建议**：`pnl-periods` 也接 notes/effectiveDate —— 若 `dailyPnlDetail` 的有效日 ≠ today，则**不要覆盖 today、不要回填**（today 显示「还没开盘」），或把 effectiveDate 透出给前端；同时给 `window()` 加「窗口内无点 → null」语义，让「今天还没有数」可表达。

### P2-1：令牌轮换回滚失败抛 `AuthException` → 401 → 前端全局登出；api-spec 未记该状态码

- **问题**：`rotate` 在「旧令牌撤不掉」时回滚新令牌并 `throw new AuthService.AuthException(...)`。`GlobalExceptionHandler` 把 `AuthException` 一律映射为 **401**，而客户端（app/web）对 401 的既定行为是**清 token 回登录页**。用户只是换钥匙失败，却被登出；同时该失败没有任何区分于「会话失效」的表达。api-spec 的 rotate 段只声明了 200/404。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/application/ApiTokenService.java:220-224`；`services/adai-core/src/main/java/com/adaiadai/core/interfaces/GlobalExceptionHandler.java:81-84`；`docs/architecture/api-spec.md`（`POST /auth/tokens/{idOrPrefix}/rotate` 段）
- **证据**：
  ```java
  if (!revoke(userId, old.tokenHash())) {                      // ApiTokenService:220
      revoke(userId, fresh.token().tokenHash());
      throw new AuthService.AuthException("换钥匙没成功（旧的没撤掉），这次先不动它，稍后再试一次");
  }
  …
  @ExceptionHandler(AuthException.class)                       // GlobalExceptionHandler:81
  public ResponseEntity<Map<String, String>> handleAuth(AuthException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
  ```
- **建议**：该失败用 409/500 + 业务错误码（不要用认证类异常）；api-spec 补 409。前端只在「会话失效」语义上回登录页。

### P2-2：`purge=true` 全部/部分失败仍回 `{"purged":true}`，无失败出口

- **问题**：`purgeUserData` 内层 `catch` 只 `log.warn` 并继续、外层 `catch` 只 `log.warn`；`listFiles` 抛异常时返回 `deleted=0`，接口仍回 **200 + `purged:true`**（且账号已删、会话已踢，不可逆）。调用方无法区分「清了 N 个」与「一个都没清掉」。这是对**不可逆个人数据删除**的谎报方向，命中 pitfalls「失败只 log.warn」的一族。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/interfaces/AccountController.java:243-265`
- **证据**：
  ```java
  int purged = purgeUserData(userId);
  log.info("账号已删除并清理数据 | userId={} | 清理文件 {} 个", userId, purged);
  return ResponseEntity.ok(Map.of("deleted", true, "purged", true, "purgedFiles", purged));  // :245
  …
  } catch (Exception e) {                       // :262 外层：列举失败 → deleted 保持 0
      log.warn("账号数据清理：目录列举失败 | userId={} | {}", userId, e.getMessage());
  }
  return deleted;                                // :265
  ```
  测试只覆盖全成功路径：`AccountControllerTest.java:581-592`（`when(storage.listFiles(...)).thenReturn(List.of("records/a.md","memory/b.json"))` → 断言 `purgedFiles=2`），**无部分失败/列举失败的反例**。
- **建议**：返回体加 `failedFiles` / `complete` 布尔（或失败时 207/500 + 人话），admin 侧据此提示「没清干净，请重试」；补两条反例测试。

### P2-3：图片日配额「先检查后记账」且执行器拒绝路径不回退 → 配额被误扣（违反接口自身声明的回退语义）

- **问题**：`consumeImages` 在 `learnSubmitExecutor.execute(...)` **之前**调用；若 `execute` 抛 `RejectedExecutionException`（执行器满），`catch` 只清理暂存并抛「消化任务繁忙」，**已扣的张数不回退**——用户没得到任何整理，当日配额却被消耗。`LearnQuotaRepository.consumeImages` 的 javadoc 明确写了「允许负数 = 回退，**如受理失败时回收占位**」，即领域契约要求这个回退，调用方一处也没实现。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/application/LearnDigestAppService.java:382-390`；契约声明 `services/adai-core/src/main/java/com/adaiadai/core/domain/learn/LearnQuotaRepository.java:33-40`
- **证据**：
  ```java
  // LearnDigestAppService:382-390
  if (quotaRepository != null && imageDailyLimit > 0) {
      quotaRepository.consumeImages(userId, LocalDate.now(), images.size());
  }
  learnSubmitExecutor.execute(() -> runImageJob(...));
  } catch (RejectedExecutionException e) {
      cleanupStaged(userId, rawNames);   // 只清素材，不回退配额
      jobs.remove(userId, current);
      throw new LearnException("消化任务繁忙，请稍后重试");
  ```
  ```java
  // LearnQuotaRepository:33-40
   * @param count 本次受理的图片张数（允许负数 = 回退，如受理失败时回收占位）
   * 写盘失败抛 StorageException（fail-visible…）
  ```
- **建议**：把记账移到 `execute` 成功之后（或两个 catch 里 `consumeImages(userId, day, -images.size())`）。注意回退自身写盘失败时只能告警（不能把用户的原始错误换成记账错误）。

### P2-4：盘前 `refreshTodayPnl` 被新加的 notes 首行挡住「永不写回」，且日志把原因说成「有实质未计入」

- **问题**：`refreshTodayPnl` 用 `notes` 是否含「实质未计入」当作写回闸门，过滤规则只排除以「今日无成交记录」开头的条目。本轮在 `dailyPnlDetail` 里把「今天还没开盘…」作为**首行**加入 notes → 盘前每次重算都被判定为「有实质未计入」而拒绝写回，日志固定输出 `当日盈亏未写回（有实质未计入，保留原值）`——**原因与事实不符**（不是未计入，是口径切换导致的主动放弃）。两条闸（`refreshTodayPnl` 的非交易日闸 / `dailyPnlDetail` 的交易日切换）的这次交互既没有写在设计注释里，也没有测试。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/application/TradingAppService.java:1524-1527`（新 notes 首行）与 `:1741-1748`（过滤 + WARN）
- **证据**：
  ```java
  notes.add("今天还没开盘（或今天不是交易日），下面是 " + effectiveDate + " 的当日盈亏…");   // :1525
  …
  List<String> actionable = r.notes().stream()
          .filter(n -> !n.startsWith("今日无成交记录"))                                  // :1742
          .toList();
  if (!actionable.isEmpty()) {
      log.warn("当日盈亏未写回（有实质未计入，保留原值）| userId={} | 算出={} | {}", …);    // :1745
      return;
  ```
- **行为后果**：盘前（8:00 导入昨日成交等路径）`account.todayPnl` 不会被更新——本身比修前更安全（修前会写错值），但**是被字符串前缀意外挡住的，不是设计**；一旦有人修过滤规则，错值写回会立刻回来。
- **建议**：把「口径切换」与「实质未计入」分成两类 notes（或给 notes 一个带类型的结构），写回闸只对后者生效；盘前写回策略显式决定（写 effectiveDate 的值 or 不写）并加注释 + 测试。

### P3-1：`deepLink()` 的 default 分支替未知类型猜 `trading:today`

- **问题**：`deepLink()` 对「无 symbol 且类型不是 learn-review」一律返回 `trading:today`。我枚举了全部 4 个构造点（`TradingSessionPushService:219/231/244/276/337/392/421/461` 传 `session`/`close-summary`/`buy-point`/`market`，`LearnReviewPushService:114` 传 `learn-review`，`MarketAlertService:274/291/331` 传 `stop-loss`/`near-stop-loss`/`loss`/`gain`），**现存类型不会给出错目标**；但类型字符串是自由文本，未来任何非交易类通知都会被静默导向交易页，且「猜一个具体目标」比「不定位」更难发现错。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/kernel/push/PushChannel.java:119-126`
- **证据**：`default -> "trading:today"`（:124）；客户端据 `link.split(':').last` 取 key 在 Feed 里找卡片（`apps/adai-app/lib/main_page.dart:1546-1549`），`today`/`review` 都不像标的 → 实际退化成「最新一条推送卡」。
- **建议**：`default -> null`，只对显式登记的 trading 类型映射；或改为按 type 前缀（`trading-*`）判定。

### P3-2：`previousTradingDay` 15 天回退够用，但节假日表无「覆盖边界」自检

- **问题**：「15 天上限够不够」——**够**（最长连续休市为春节 9 天 + 周末；`HOLIDAYS` 含 2026 春节 2/16–2/23 共 6 个工作日）。真正的风险是表本身的覆盖：`HOLIDAYS` 硬编码到 **2027-10-07** 为止，`isTradingDayStrict` 只做 `!HOLIDAYS.contains(date)`，**没有「查询日超出表覆盖范围」的判断**。2028 年起所有法定假日都会被当交易日：`previousTradingDay` 会返回春节假期中的某一天（静默给错口径），`refreshTodayPnl` 的闸 1 也会失效。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/application/TradingAppService.java:1670-1678`；表与判定 `services/adai-core/src/main/java/com/adaiadai/core/application/TradingSessionPushService.java:142-183`、`:196-210`
- **建议**：给表加 `LAST_COVERED_YEAR` 常量并在 `isTradingDayStrict` 里对超出范围记 ERROR（或抛），配一条「下一年表未更新」的测试/自检端点；把「每年更新节假日表」登记为待办。

### P3-3：盘前口径没有端到端测试；新测试用「实现自己认定的当日」造数据＝自我同义

- **问题**：新增的 3 个用例只测了两个 `static` 工具方法，**没有任何用例验证 `dailyPnlDetail` 在盘前/非交易日真的改用上一交易日、notes 首行真的出现、bySymbol 跟着切换**。同时 `DailyPnlComputeTest` 的改法是先问实现「你认为今天是哪天」（`TradingAppService.quoteIsFromPreviousDay(effectiveDay)`）再据此造数据——**用被测实现构造测试前提**，实现口径错了测试照样绿，只是不再在凌晨变红。`dailyPnlDetail` 仍固定读 `LocalDate.now()`（`quoteIsFromPreviousDay(date)` 单参重载），无法注入时钟。
- **位置**：`services/adai-core/src/test/java/com/adaiadai/core/application/DailyPnlComputeTest.java`（`effectiveDay` 构造段与 3 个新用例）；`TradingAppService.java:1659-1667`
- **建议**：给 `dailyPnlDetail` 一个 `(userId, date, today, now)` 的可测重载；补「非交易日查询 → 有效日=上一交易日 + notes 首行 + 成交流水按有效日过滤」的端到端断言；测试造数据用**写死的日期**而非实现方法。

### P3-4：`dayChangePct` 在盘前也是上一交易日口径，但只有当日盈亏被标注

- **问题**：`getPositionsDailyView` 的「今日涨跌幅」= (实时现价 − 实时昨收)/昨收；盘前实时现价=上一交易日收盘、昨收=再前一日收盘 → 值等于**上一交易日的涨跌幅**。用户可见标签是「今日涨跌幅」，而 notes 首行只解释「当日盈亏」。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/application/TradingAppService.java:653-666`
- **建议**：notes 文案覆盖「当日盈亏与涨跌幅同为 X 日口径」，或把 effectiveDate 透出给前端改标签。

### P3-5：purge 只删文件、不删目录，`data/{userId}/` 空目录树残留

- **问题**：`purgeUserData` 注释说明「逐个删文件而不是删目录」是有意的（不猜底层实现）；但结果是 `data/{userId}/**` 的空目录树留在磁盘上（含 `learn/`、`trading/`、`_trash/` 等）。若运维/后续核对以「目录是否存在」为判据，会误判「没清干净」或反之。
- **位置**：`services/adai-core/src/main/java/com/adaiadai/core/interfaces/AccountController.java:254-265`，底层 `services/adai-core/src/main/java/com/adaiadai/core/infrastructure/storage/LocalFileStorage.java:132-140`
- **建议**：在响应里区分 `deletedFiles` 与「残余目录」，或在文档里写明「purge 后目录壳保留」的语义。

### P3-6：配额检查与记账两次取 `LocalDate.now()`，跨午夜不匹配

- **问题**：`:340` 用 `LocalDate.now()` 读当日用量，`:384` 再取一次写账——跨午夜执行时会出现「按 09-16 检查、记到 09-17」。概率极低，但同一请求内两次取时钟是同类问题的标准形态（pitfalls「时间边界」族）。
- **位置**：`LearnDigestAppService.java:340` 与 `:384`
- **建议**：请求开始时取一次 `LocalDate day = LocalDate.now()` 并复用。

## 五、覆盖面声明（核实过、确认没问题）

| 项 | 核实方式 | 结论 |
|:---|:---|:---|
| 令牌轮换不留两把同时有效 | 读 `ApiTokenService.rotate:195-228` + `ApiTokenFileRepository.deleteByTokenHash:123-132`（全局 `FILE_LOCK` + 按 hash 精确 `removeIf` + `writeAll`，返回 boolean） | **成立**：旧撤不掉 → 回滚新并抛错；成功路径旧的一定被删 |
| 并发两个 rotate | 推演交错（A/B 同时匹配同一把旧钥匙）：`revoke` 是原子的 hash 删除，后者必返回 false → 回滚自己的新钥匙并抛错，**任一时刻至多一把有效** | **成立**（进程内崩溃窗口未消除，见「不确定项」） |
| `revoke` 按 hash 的语义复用 | `rotate:202-211` 与 `revoke:242-253` 同口径（`isFullHash` 判定 + owner 校验 + 前缀歧义拒绝） | **正确复用** |
| 跨账号轮换 | `rotate` 的 `mine = repository.findByUserId(userId)` 过滤 + 测试 `rotate_unknownOrAnotherAccount_isEmpty` | **成立**（跨账号失败且不动原钥匙） |
| purge 会否误删别的账号 | `LocalFileStorage.resolve:160-172`：userId 白名单 `[a-zA-Z0-9_-]+` + `normalize()` 后 `startsWith(userRoot)` 校验；purge 与 delete 用同一 userId | **不会**（路径穿越被拒；`data/accounts/` 等共享目录不在 userId 层内） |
| kickSessions / delete / purge 顺序 | `AccountController:232-245`：先踢会话 → 删账号 → 仅在 `removed` 后 purge | **顺序合理**（先断访问再删数据；404 时已踢会话属既有行为） |
| 图片配额按张 vs 按次 | 检查 `usedToday + images.size()`（:347）与记账 `consumeImages(..., images.size())`（:384） | **口径一致（按张）**；`images.size() ≤ 3` 由 `IMAGE_MAX_COUNT` 前置保证 |
| 账本损坏 fail-closed 是否挡死用户 | `LearnQuotaFileRepository.readRoot:148-162`：文件不存在 → null → 0（新用户不受影响）；存在但解析失败 → 抛 StorageException → 上层转人话拒绝 + 指路「粘文字」 | **不挡死**：有替代路径（粘正文），且仅影响图片源 |
| `images` 键与转写月键互不干扰 | `consume:71-77` 与 `consumeImages:109-116` 都是 `root.deepCopy()` 后 `set` 单键；`extract` 只读 `root.path("YYYY-MM")` | **互不抹除** |
| 视觉 maxTokens 按次覆盖 | `VisualAiClient:44-47` default 忽略覆盖；`GlmVisualAiClient.effectiveMaxTokens:162-171`（null/非正数回落全局） | **老实现零改动兼容**，单测/服务测试已按三参签名更新 |
| `restoreOrigin` 幂等与无 frontmatter 保护 | `:709-712` 已是产品卡直接返回；`replaceFrontmatterKey:632-635` 无前言块 → 抛 LearnException（400 人话） | **成立**（不会给无 frontmatter 的文件盖章） |
| restoreOrigin 门控 | `LearnController.restoreOrigin` 先 `requireLearnPlugin` + type 白名单校验 | **有门控** |
| 深链 target 正确性 | 枚举全部推送构造点的 type/symbol（见 P3-1 证据） | **现存类型无错目标**；风险在未来类型（P3-1） |
| 深链是否污染 aps | `ApnsPushChannel:269-278` root 级 `adaiDeepLink` + 测试 `payload_carriesDeepLink_forPushTapNavigation`（断言 `aps.adaiDeepLink` 为 null） | **正确** |
| 新端点契约登记 | `guard-align.sh` A1：154 端点全在 api-spec | **通过**；`status.md` 有一处重复拼接文本（见下） |
| 测试与守护 | 见第二节 | **全绿**；三件套 PASS |

补充（文档瑕疵，非代码问题）：`docs/reference/status.md` 端点行出现重复拼接 `**+1（2026-09-16 晚间批）**+1（2026-09-16 晚间批）：POST /learn/cards/restore-origin`，且 09-17 的 commit 被标为「09-16 晚间批」（时间标注与 commit 日期不一致）。

## 六、不确定项（无法证实 / 待验证）

1. **生产 JVM 时区**（影响本次新增的 `LocalTime.now()` 开盘判定）：仓库内**没有任何** `-Duser.timezone` / `TZ=` / `spring.jackson.time-zone` 配置（`grep -rn "user.timezone\|TimeZone.setDefault\|time-zone" services/adai-core/src services/adai-core/deploy.sh` 无命中；仅 `WechatFetcher:143`、`BilibiliFetcher:305` 在业务逻辑里显式用 `Asia/Shanghai`）。若生产 JVM 默认时区不是 `Asia/Shanghai`，`quoteIsFromPreviousDay(..., LocalTime.now())` 的 9:30 判定会整体偏移（UTC 下北京时间 17:30 前都算「没开盘」），`dailyPnlDetail` 将整天返回上一交易日的数。**验证命令**（只读）：生产机上 `date` + `systemctl show adai-core -p Environment` + `ps -o args= -C java`，或临时 `GET /actuator/env`（若无端点则看启动脚本）。**旁证倾向正常**：15:30 收盘小结与 20:00 学习提醒按北京时间实测可达（cron 未显式配 zone），说明 JVM 时区大概率为 `Asia/Shanghai`。
2. **`refreshTodayPnl` 在盘前被调用的真实频率**：调用点 `TradingAppService:1981/1988`（成交确认/记录后）。我未在生产日志中验证「早于 9:30 的调用」是否真实存在；P2-4 的行为后果因此按「盘前导入/记录」场景陈述。
3. **进程在 `issue` 与 `revoke` 之间崩溃**：会留下新旧两把同时有效（注释声称「绝不留下两把同时有效」只对异常路径成立，对 kill -9 不成立）。无法用测试证实，属设计窗口，未定级。
4. **配额检查-再动作的非原子性**：单进程内被 `jobs.compute` 占位挡住（第二个请求在 `:367-372` 就返回 running，不走到记账），故我判为**无可利用竞态**；多实例部署时该结论不成立（当前生产单实例）。
5. **`admin` 是否能触发 purge**：`grep -rn "purge" apps/adai-admin/lib/` **无命中**，`ApiService.deleteAccount`（`apps/adai-admin/lib/services/api_service.dart:284`）不带 query → **purge 目前无 UI 入口**；api-spec 里「adai-admin 侧还要过一次『输入账号名确认』」的表述与现状不符（现有确认框只写「会话将立即失效」）。需用户/前端确认是否要接（属范围内的产品决策，不是后端缺陷）。

## 附录 A：范围外 commit `bc6656b`（午间谷时任务壳 + LaunchAgent）单独标注

- 该 commit 属**另一会话**的 AI 工程工具链改动（`ai-engineering/noon-task.sh` 新增、`guard-prod.sh`、`scripts/setup-launchd.sh`、`docs/guides/routine.md`、`guard-tools.sh` T7 已认它）。本轮不评判其设计，仅记两条观察：
  1. **时区偏移只告警不阻断**：`ai-engineering/noon-task.sh:44-47` 检测到本机 `%z ≠ +0800` 时只打印 `⚠️ 本机时区偏移…峰谷判定不可信`，随后**照常执行**。若本机时区被改，闸门会按本机钟放行高峰时段（2 倍价计费）。建议非 +0800 时 fail-closed（需 `--force` 才继续）。
  2. `scripts/setup-launchd.sh` 修掉了「重装即把历史日志截断清零」的真实事故（改为仅在文件不存在时创建）——这条是有价值的修复，已记入 `pitfalls.md` 的候选（未确认是否已沉淀）。
- 守护脚本对本 commit 覆盖良好：`guard-tools.sh` T6（`$VAR` 紧跟非 ASCII）PASS、T7 显示 `com.adai.adaios-noon-task` 已加载且「午间谷时 0 天前跑过」。

---
**报告性质**：只报告不修改（B7）；所有结论可由上列「位置 + 证据」复核。
