---
title: 部署前增量深审（未发布面 dc95599..HEAD）
description: 2026-09-14 部署前增量深审——三批未发布代码（微博/公众号/头条抓取放开 + 锁屏脱敏/Android 签名 + 外部工具令牌）四官隔离并行 + 主会话代码实测交叉印证；P0×2 当场修复出表，P1×8 / 战略×1 / P2×6 登记 REVIEW
version: 1
created: 2026-09-14
updated: 2026-09-14
status: active
lines: 84
depends-on: []
related:
  - ../REVIEW.md
  - ../../../ai-engineering/process/review.md
  - ../../../ai-engineering/roles/backend-reviewer.md
  - ../../../ai-engineering/roles/adversarial-reviewer.md
tags: [review, audit, pre-deploy, security]
---

# 部署前增量深审（未发布面 dc95599..HEAD）

> **为什么审**：用户问「确认是否需要进行生产部署」→ 判定「需要，且有三批后端代码未上线」→ 用户选择先跑 GATE-BEFORE 三门（全 PASS）→ 再跑增量深审，**安全面优先**。
> **范围**：`git diff dc95599..HEAD`（`dc95599` = 生产 v3.63 APNs 批部署记录；其后 12 个提交即「未发布面」）。代码改动集中在三批：`892e05b` learn 抓取放开（微博/公众号/头条 + Web Archive 兜底修复）· `99d4831` 收盘小结锁屏脱敏 + 非 iOS 开关去假 + Android 正式签名 · `2b576e7` 外部工具令牌；另有一组 iOS 外部入口 app 提交。
> **方式**：`ai-engineering/process/review.md` deep 档——**四官隔离并行**（backend / frontend / docs / adversarial，各自独立子代理、材料按域裁剪、互不可见），主会话另做**代码实测复核**（不回灌官报告）。四官全程只读，未改任何文件。
> **结论**：**P0×2（当场修复出表）· 战略×1 · P1×9 · P2×7 · ⭐ 多官独立命中 4 处**。

## 一、交叉印证（⭐ = 材料隔离下多官独立命中 + 主会话实测）

| 结论 | 命中来源 | 定性 |
|:-----|:---------|:-----|
| ⭐⭐⭐ **锁屏脱敏只覆盖「收盘小结」一条路径** | 对抗 P0-1 + backend P1-3 + 主会话实测（8 个调用点传 null）| **P0-1，已修** |
| ⭐⭐⭐ **外部工具令牌「可收回」接不上** | 对抗 P0-2 + backend P1-1/P1-2 + 主会话实测（`revokeAll` 全仓 0 调用）| **P0-2，已修** |
| ⭐⭐ **一次性明文会被自己的 loading 顶掉** | frontend P1-2 + 主会话读码（`_load` 置 `_loading` → build 提前 return）| P1-令牌2 |
| ⭐⭐ **令牌文件「半读」被当正常** | backend P2-4 + 对抗 P2-7 | P2-令牌3 |
| ⭐ **入口先 take 后判 mounted** | frontend P1-4 + 主会话确认（`main_page.dart:144-145`）| P1-入口3 |
| ⭐ **入口单槽串联丢件** | 对抗 P1-6 + frontend P2-8/P3-11 | P1-入口1 |

## 二、P0（当场修复，见 `docs/reference/change-log.md` 2026-09-14 行）

| # | 问题 | 证据 | 修复 |
|:--|:-----|:-----|:-----|
| P0-1 | **锁屏脱敏只关了一扇门**：`lockScreenContent` 仅收盘小结一处传值，早盘/午间/尾盘/买点/今日操作确认/当日盈亏附注/行情异动/批次止损/学习复习全走 6 参构造 → 回落完整正文；行情提醒更把**股票名放进 title**；`MarketAlertService.mergeBySymbol` 重建 `PushMessage` 时把已脱敏字段丢掉；生产 APNs 白名单已清空（10 类全量）| 主会话实测 `TradingSessionPushService:218/228/239/380/406/444` + `MarketAlertService:260/275/302` 全传 null | `PushMessage` 增 `lockScreenTitle` + `notificationTitle()`；三渠道标题与正文同口径；8 个调用点补齐锁屏版；`mergeBySymbol` 同步合并锁屏版（缺则走兜底，不回落完整正文）|
| P0-2 | **钥匙收不回来**：`revokeAll` 注释自称「改密联动用」但全仓 0 调用；改密/管理员重置/禁用/删号只踢会话不撤令牌 → 泄漏的 `adai_` 令牌改密后仍有效，可继续调**付费**的整理确认；`validate` 亦不查账号存在与 `enabled` | 主会话实测 `AuthService:231-236` + `AccountController:178/183/223`；全仓 grep `revokeAll` 仅定义 | `changePassword` 与 `kickSessions` 统一调 `revokeAll`；`validate` 补账号存在 + `enabled` 复核并 fail-closed（读失败抛异常不降级放行）|

## 三、未修项（已归口 REVIEW.md）

| 编号 | 问题（一句话）| 位置 | 状态 |
|:-----|:-----|:-----|:-----|
| P1-安全1 | **`adai://` 是无凭据写入口**：任意 App/网页 `openURL` 即可静默落记录、`adai://digest?url=…` 触发抓取 + 付费，无来源校验无确认——不用偷令牌就能往用户日记塞内容、花用户的钱 | `Info.plist` / `SceneDelegate.swift` / `main_page.dart` | ⏳ 部分修（2026-09-14 晚间批：digest 入口一次点击确认；记录持久化来源字段未做——`ContentRecord.source` 语义变更需单独决策） |
| P1-安全2 | **抓取的网页正文直接进 prompt → 知识底座可被投毒**：恶意页正文里的指令随 learn 卡落盘，再经 `LearnKnowledgeSource` 进后续每轮上下文 | `LearnFetchService` / `LearnDigestAppService` | ✅ 已修（晚间批：prompt 来源隔离——显式不可信边界 + 系统提示词铁律 + 召回侧「资料≠指令」；残留风险=缓解非根除） |
| P1-抓取1 | **`Html.toText` 惰性 DOTALL 正则 O(n²)**：80KB 未闭合 `<head` 实测 2.9s，4MB 为小时级且无超时 → 一个链接钉死 learn 执行线程（生产 2 核）| `infrastructure/fetch/Html.java` | ✅ 已修（2026-09-14 晚间批：线性扫描 + 512K 上限 + HtmlTest 4 例含超时守卫） |
| P1-入口1 | **入口只有一个槽**：UserDefaults 单键 + `ChannelBuffers` 单槽 + 静态单值三处串联 → 冷启动连发两条，第一条静默消失 | `ExternalEntry.swift` / `AppDelegate.swift` / `entry_intent_service.dart:83` | ✅ 已修（晚间批：UserDefaults/AppDelegate/Dart 三处单槽改 FIFO 队列，测试含「两条都不丢」） |
| P1-入口2 | **静态槽跨登录保留 → 多账号串号**：登出期间的 Siri 入口被下一个登录账号消费 | `entry_intent_service.dart:83` | ✅ 已修（晚间批：队列绑定 userId + 登出/401 清空，未登录不排队） |
| P1-入口3 | **入口先 `take()` 后判 `mounted`** → 未挂载时内容被取走即丢（用户亲口说的内容静默蒸发）| `main_page.dart:144-145` | ✅ 已修（晚间批：先判 mounted 再 take，未挂载不消费、内容留队） |
| P1-令牌1 | **令牌无过期 + 按 8 hex 前缀撤销** → 只能手动撤，且同账号前缀碰撞会误撤另一把钥匙 | `ApiToken` / `ApiTokenService.revoke` | ✅ 已修（晚间批：90 天有效期 + 撤销按 hash，前缀非唯一命中拒绝） |
| P1-令牌2 | **「明文只此一次」的丢失路径**：签发后 `await _load()` 让 loading 分支顶掉唯一明文（后端只存哈希，丢了就真丢了）；`_revoke` 成功分支不复位 `_busy`（收回一把后按钮全灰）；barrier/返回键可关窗且无二次确认 | `share_token_dialog.dart:24/84/94-108/187` | ✅ 已修（晚间批：明文先于 loading、busy 两分支复位、未复制即关窗二次确认） |
| P1-文档1 | **文档失真**：api-spec 加 3 个令牌端点却仍写 v3.63、无变更行；`status.md:34` 把 HEAD 的数字摆在「生产当前版本 v3.63」旁却不标「HEAD≠生产」；令牌批与抓取放开批此前零归口 | `api-spec.md:5` / `status.md:34` | ✅ 已修（晚间批：api-spec v3.64 + status.md 新增「HEAD≠生产」行） |
| P2-令牌3 | **令牌文件未开 `FAIL_ON_TRAILING_TOKENS`**，与类注释自称的 fail-fast 相反：半读成功后再写（含 `lastUsedAt` 节流）会把半截列表整体回写 → 静默丢令牌 | `ApiTokenFileRepository.java:47-50` | ✅ 已修（晚间批：FAIL_ON_TRAILING_TOKENS + 尾部垃圾用例） |
| P2-推送1 | **锁屏回退方向是 fail-open**：漏传锁屏版的新调用点默认按完整正文外发（本批已补齐存量调用点，机制未收紧）| `PushChannel.java:52-58` | ✅ 已修（晚间批：改 fail-closed + guard.sh G8 机械守卫） |
| P2-令牌4 | `label.substring(0,40)` 可截断代理对（命中 pitfalls「emoji 代理对」）| `ApiTokenService.java:173` | ✅ 已修（晚间批：按 codePoint 截断 + emoji 用例） |
| P2-令牌5 | 弹窗次级缺陷：`_load` 失败伪装「还没有。」/ `data['token']` 缺失静默置 null / `lastUsed.substring(0,10)` 无守卫可崩 / 未提「这把钥匙能花钱、别分享」/ SnackBar 被 barrier 压住 | `share_token_dialog.dart:80/286-293/335/1172/122-123` | ✅ 已修（晚间批：失败≠空态、缺明文/日期守卫、有效期文案、id 优先撤销、内联提示） |
| P2-构建1 | **`build_apk.sh` 验签闸门 fail-open**：取不到 keytool/apksigner 即 WARN + `exit 0`，仍打印「把 APK 发给对方」| `apps/adai-app/scripts/build_apk.sh` | ✅ 已修（晚间批：验签闸门两处 fail-closed） |
| P2-入口4 | 3 秒 (action,text) 时间窗把合法重复静默吞掉（零日志零反馈）/ learn 入口 19px 无 tooltip 且热区 <44pt / `?? record.rawValue` 兜底不可达 | `ExternalEntry.swift:75/97-100` / `learn_page.dart:288-291` | ✅ 已修（晚间批：去重命中留日志、learn 入口 44pt 热区+Tooltip、删死兜底并明确裸 adai:// 映射） |
| P2-文档2 | `status.md:22` 写 Controller 21，实测 **22**（漏 `PushController`）/ ai-engineering 三处仍写「9 审查官」而 roles/ 实为 12 / `feature-reference.md` 未收锁屏脱敏 | 多处 | 未修 |
| S-凭据1 | **外部凭据生命周期只有「手动撤销」一条出口**（本批已补联动与复核）：无过期/轮换，且付费的 `learn/digest/confirm` 无频控与额度闸 | `ApiToken` / `ApiTokenService` | ⏳ 部分修（晚间批：联动 + enabled 复核 + 90 天有效期 + hash 撤销；仍未做轮换/付费频控/异常告警） |

## 四、主会话复核（独立于官报告，含对官结论的修正）

- **确认成立**：`revokeAll` 零调用（grep 全仓）；`AuthService.changePassword:231-236` 只踢会话；`_load()` 开头置 `_loading=true` 而 build 的 loading 分支先于 `_freshPlain` 返回；`main_page.dart:144-145` 先 `take()` 后判 `mounted`；`TradingSessionPushService` 仅 `:270` 传锁屏正文。
- **纠正一处**：docs 官报「Controller 实测 22」，实测确为 **22**（`interfaces/*Controller.java`）；但若直接 grep `@RestController` 会得到 **23**——第 23 个是 `GlobalExceptionHandler`（`@RestControllerAdvice`，前缀命中），**不能按 grep 计数**。
- **未证实**：`Html.toText` 的 4MB 量级耗时（官报为本地估算，80KB→2.9s 已采信，小时级未复现）；`mergeBySymbol` 丢字段为读码确认，未跑端到端。

## 五、四官一致核实无问题（覆盖面）

1. 令牌仅存 SHA-256 + 12 字符前缀，明文只在签发响应出现一次；32B `SecureRandom`。
2. `TokenScope` 逐条精确匹配 + 默认拒绝 + 未知 id 丢弃；外部令牌打 `/api/v1/auth/**` 得 403，**不能自我提权**。
3. `AuthFilter` 免鉴权前缀未被放宽；外部令牌经 `UserIdHeaderRequestWrapper` 强制覆盖 `X-User-Id`，无跨账号读入口。
4. 三个新抓取器全走 `HopFetch`（禁自动跳转 + 逐跳协议/私网/元数据复检 + 4MB 上限）；抓取 URL 为「配置 base + 纯数字 id」，无用户可控 host（DNS TOCTOU 已在 `OutboundHostPolicy:30-32` 显式记录）。
5. `digest/confirm` 用 `compute` 原子转移，无并发重复付费；非 iOS 推送开关真去假（`onChanged == null` 有测试）；认不出的动作两端都不回落（有测试）。

## 六、处置建议

- **P0 已修，可随批部署**（部署前仍需 `./gradlew bootJar` 重建 jar——现有 `build/libs` 里查不到 `ApiToken*`/`WeiboFetcher`）。
- **P1-安全1 / P1-安全2 需用户拍板口径**（是设计取舍，不是小改），不阻塞部署。
- **P1-抓取1 / P1-入口1~3 / P1-令牌1~2 属小改**，建议紧随一批。
- **P1-文档1 建议无条件先修**：`status.md` 不标「HEAD≠生产」，会让后来者（含 AI）误以为令牌、抓取放开、锁屏脱敏都已在线。
