---
title: 登录体验方案——密码被系统记住 + Face ID 免密进入（钥匙串存凭证 + 生物识别本地门禁）
description: 现状是「账号+密码+30 天滑动会话」，登录页无 autofill 语义、token 存 NSUserDefaults、无生物识别。方案分三层：L1 补 autofill 语义让 iCloud 钥匙串记住/填充密码；L2 token 落 Keychain + local_auth Face ID 本地门禁 + 后端会话设备列表与撤销；L3（二期可选）Passkey / Sign in with Apple 真正免密码。生物特征永不出设备，Face ID 只是本地解锁闸，凭证仍是服务端会话 token。
date: 2026-09-14
status: approved
decided-by: adai（2026-09-14 拍板：决策点 1-5 **全部按推荐**——① 做 L1+L2（L3 二期再评估）② 会话**维持 30 天滑动** ③ **做**「登录设备」管理 UI ④ Face ID **冷启动必触发 + 回前台超 5 分钟触发** ⑤ L3 **暂不做**，将来 Passkey 优先于 Sign in with Apple）
tags: [auth, 登录, 生物识别, 钥匙串, Face ID, iOS, 凭证]
related:
  - ./20260901-auth-login.md
  - ../review/REVIEW.md
---

# 登录体验方案——密码被系统记住 + Face ID 免密进入

> **触发来源**：2026-09-14 15:41 生产记录 `rec_20260914_154150201`（life/note）——「目前阿呆阿呆的密码不能被苹果记住 自动填充 包括没有加入生物识别」。阿呆当时只把它归成一条 **P2 待办**（`task_20260914_154157943`），代码侧零动作。
>
> **本 RFC 已拍板（2026-09-14，决策点 1-5 全部按推荐，见 §五）**：把「登录那一次」的体验（系统记住密码、Face ID 填充）与「日常进入」的体验（Face ID 免密）分开解决，并补上凭证生命周期缺的那一环——设备可见、可按设备撤销。

---

## 一、现状事实（已核实，2026-09-14 读代码）

| 项 | 现状 | 证据 |
|:---|:-----|:-----|
| 登录方式 | 账号 + 密码（bcrypt），`POST /api/v1/auth/login` 签发会话 token | `docs/rfc/20260901-auth-login.md` §3.1 |
| 会话 | `data/accounts/sessions.json`，**30 天滑动续期**（活跃即不过期），token 只存 SHA-256，天然多设备 | 同上 §3.1 |
| token 客户端存储 | **`shared_preferences`**（iOS = `NSUserDefaults`，明文 plist；web = `localStorage`） | `apps/adai-app/lib/services/user_store_io.dart` L6/L41 |
| 登录页表单 | 两个裸 `TextField`，**无 `AutofillGroup`、无 `autofillHints`** | `apps/adai-app/lib/pages/login_page.dart` L160-174 |
| 生物识别 | 无。未引入 `local_auth`，`Info.plist` 无 `NSFaceIDUsageDescription` | `apps/adai-app/pubspec.yaml` L9-19；`ios/Runner/` 无相关声明 |
| 安全存储 | 无。未引入 `flutter_secure_storage`（无 Keychain 使用） | 同上 |
| 会话元信息 | 只有 `userId/createdAt/lastSeenAt/expiresAt`，**没有设备信息**，无列表/撤销端点 | RFC 20260901 §3.1/§3.4 |
| 已有相关未修项 | `S-凭据1`：外部凭据生命周期只有「手动撤销」一条出口 | `docs/review/REVIEW.md` |

**两个诉求与三层方案的对应**：

- 「密码不能被苹果记住 / 自动填充」→ **L1**（表单语义）+ 部分 **L3**（不用密码）
- 「没有加入生物识别」→ **L2**（本地门禁）或 **L3**（生物识别即凭证）

**一条容易被忽略的事实（影响决策）**：会话已是 **30 天滑动、活跃即不过期**，也就是说常用设备其实「几乎不用重登」。真正的痛点不在会话长度，而在两个瞬间——**①首次/偶尔登录时系统不帮你记密码**；**②每次打开 App 时没有一道 Face ID 门禁**（现在是直接进）。

---

## 二、目标与非目标

**目标**

1. iPhone 上登录一次后，系统（iCloud 钥匙串）能**提示保存密码**，下次登录**键盘上方给建议、Face ID 确认后自动填入**。
2. 日常进入 App：**Face ID 一按即进**，不必重新输密码；Face ID 不可用/失败时**无损回退**密码登录。
3. 凭证落 **Keychain**（系统级加密）而不是明文 `NSUserDefaults`。
4. 能看到「哪些设备登录着」，并能**单独撤销某一台**（补齐凭证生命周期出口）。

**非目标**

- 第三方 OAuth（Google/GitHub 等）、短信/邮件多因子、企业 SSO——个人系统无收益。
- **服务端**任何形式的生物特征：生物模板**永不出设备**，服务端只认 token。
- 把生物识别当**第二因子**：它是本地解锁闸，不是鉴权因子（见 §六）。
- 不为「网页端也能记住密码」写原生 DOM 登录页（成本高、非主路径，见 L1 备注）。

---

## 三、方案

### L1 让 iOS 钥匙串记住并填充密码（低成本，立刻见效）

**原理**：iOS 的密码自动填充由 `UITextContentType` 驱动——`username` / `password` / `newPassword`。Flutter 侧填 `autofillHints` 即映射到对应 `textContentType`，iCloud 钥匙串才会在提交后提示「存储密码」，并在下次聚焦时给出建议（点建议 → Face ID 确认 → 填入）。

**改动**（app 与 web 的登录页同构，两处都要改）

| 位置 | 改动 |
|:---|:-----|
| 表单容器 | 两个 `TextField` 外包 `AutofillGroup` |
| 账号框 | `autofillHints: const [AutofillHints.username]` |
| 密码框（登录态）| `autofillHints: const [AutofillHints.password]` + `textInputAction: TextInputAction.done` |
| 密码框（`_showSetup` 设密码态）| `AutofillHints.newPassword`（让系统知道这是「新密码」，触发强密码建议） |
| 提交成功后 | 调 `TextInput.finishAutofillContext(shouldSave: true)`——**这一步不能省**，否则 iOS 常常不弹「保存密码」 |

**覆盖范围（要如实预期）**

- **iOS 原生 App**：有效路径，且是当前手机主力形态。
- **Safari 网页（`adaiadai.com`）**：Flutter Web 的文本输入是 canvas + 引擎托管的隐藏 `input`，DOM 属性由引擎生成，密码管理器识别率不可保证——**必须先真机实测再下结论**；实测不生效则记为已知限制，不做原生 HTML 登录页替代（成本与收益不成比例）。

**成本**：前端 0.5-1 天（含真机实测）；测试断言 `autofillHints` 存在（防回归）。

### L2 token 落 Keychain + Face ID 本地门禁（推荐主力）

**原理**：把「凭证」从明文偏好存储搬到系统钥匙串，再在应用启动/回前台时用 `local_auth` 调 Face ID 做一道**本地闸**。服务端鉴权模型**完全不变**——仍然是 `Authorization: Bearer <token>`。

**客户端改动**

| 项 | 设计 |
|:---|:-----|
| 新依赖 | `flutter_secure_storage`（iOS → Keychain；Android → EncryptedSharedPreferences）· `local_auth`（iOS → LocalAuthentication/Face ID） |
| token 存储 | 从 `shared_preferences` **迁移**到 Keychain，key 仍 `auth_token`；accessibility 用 `first_unlock_this_device`（不随 iCloud 备份迁移到别的设备） |
| 首次升级 | 启动时若 Keychain 无 token 而旧偏好有 → 读旧值写入 Keychain 并清除旧值；**读失败一律回退登录页**，不得卡死 |
| Face ID 声明 | `ios/Runner/Info.plist` 加 `NSFaceIDUsageDescription`（中文说明「用于免密进入阿呆阿呆」） |
| 「记住我」 | 登录页新增开关（默认开）。开 → token 写 Keychain + 下次免密；关 → 仅本次会话内存持有 |
| 免密流程 | 启动 → 读 Keychain token → `GET /auth/me` 校验未过期 → **Face ID 确认** → 进主界面 |
| 失败路径 | Face ID 取消/失败/未录入 → 回登录页（**token 不清**，可重试）；token 过期/401 → 清 token 回登录页 |
| 触发时机（可配）| 冷启动必触发；回前台超过 5 分钟再触发（默认值，见决策点 4） |

> **Keychain 级别的说明**：更强的做法是给 Keychain item 加 `SecAccessControl(.biometryCurrentSet)`（**读取即触发 Face ID**，连 App 自己都拿不到明文）。`flutter_secure_storage` 不直接暴露该能力，需要自写 MethodChannel 原生代码。本方案**不采用**，理由：App 层闸门已满足需求，且后者在 Face ID 不可用时的回退更易失控。列为备选（若要上，单独评）。

**后端配套改动**

| 项 | 设计 |
|:---|:-----|
| 会话元信息 | `sessions.json` 每条会话加 `device: {name, platform, appVersion, createdAt, lastSeenAt}`（客户端上报，**仅供展示/识别，不做安全判定**——可伪造，写明在契约里） |
| 设备列表 | `GET /api/v1/auth/sessions` → 当前账号全部会话：短 id（token 哈希前缀 8 位）/设备名/平台/创建时间/最近活跃/是否当前/过期时间 |
| 撤销设备 | `DELETE /api/v1/auth/sessions/{id}` → 撤销指定会话；**前缀非唯一命中则拒绝**（与外部工具令牌撤销同口径，防误撤） |
| 已有的不用动 | 改密踢其他会话、禁用/删号踢全部会话（P1-1 已实现）、限流 5 次/15 分钟 |

**会话时长**：建议**维持 30 天滑动不变**。理由见 §一——真痛点在「登录那一次」与「打开时无门禁」，把会话拉长到 90 天/永久，收益是边际的，代价是撤销面变大。

**成本**：app 2-3 天（Keychain 迁移 + local_auth + 引导 UI + 测试）；后端 1-2 天（会话元信息 + 2 端点 + 测试）。

### L3 真正免密码：Passkey / Sign in with Apple（二期可选）

| 选项 | 机制 | 成本 | 备注 |
|:---|:-----|:-----|:-----|
| **Passkey（WebAuthn）** | iOS 16+ 用 Face ID 创建通行密钥，存在 iCloud 钥匙串，跨设备同步；Safari 与原生 App 均支持 | 后端 3-5 天（`com.yubico:webauthn-server-core` + credential 落盘 + 注册/断言两端点）+ 前端 2-3 天 | 需要「密码作为恢复路径」；个人多设备场景收益中等 |
| **Sign in with Apple** | 本机 Apple ID + Face ID 确认，OIDC 换取身份 | 后端 2-3 天（验 `identityToken` + 首次绑定到既有账号）+ 前端 1-2 天 | 需要 Apple Developer 能力（已有付费账号）；本质是「第三方登录」，多一条外部依赖 |

**建议**：L1 + L2 上线并稳定使用 2-4 周后再评估 L3；若 L2 的 Face ID 体验已足够，L3 可以长期不做。

---

## 四、推荐节奏

| 阶段 | 内容 | 验收 |
|:---|:-----|:-----|
| 1 | L1（两端登录页 autofill 语义 + `finishAutofillContext`）| iPhone 真机：登录一次后系统弹「存储密码」；再次打开登录页，键盘上方出现钥匙串建议、Face ID 确认后填入。Safari 网页端同测，结论如实登记 |
| 2 | L2（Keychain + Face ID + 设备会话）| 杀进程重开 → Face ID → 进 Feed；Face ID 取消 → 回登录页且 token 仍在；`GET /auth/sessions` 看得到本机；撤销后该设备下一次请求 401 |
| 3（可选）| L3（Passkey 或 Sign in with Apple）| 见 §三 L3 |

---

## 五、关键决策点（待拍板）

| # | 决策点 | 推荐 |
|:--|:-------|:-----|
| 1 | 本次做到哪一层 | **L1 + L2**（L1 是 L2 的前置体验，L3 二期）|
| 2 | 会话时长 | **维持 30 天滑动**（痛点不在时长）|
| 3 | 是否做设备管理 UI | **做**：app「设置」页新增「登录设备」；admin 账号页已有治理位（与 S-凭据1 呼应）|
| 4 | Face ID 触发时机 | **冷启动必触发 + 回前台超 5 分钟触发**（避免频繁打断）|
| 5 | L3 选型 | **暂不做**，第二阶段稳定后再评估（Passkey 优先于 Sign in with Apple）|

---

## 六、安全边界与风险

| 项 | 立场 |
|:---|:-----|
| 生物特征 | **永不出设备**；服务端不接收、不存储、不校验。Face ID 只是本地解锁闸 |
| 凭证 | 仍是服务端会话 token；生物识别**不是第二因子**，绕过它只能拿到本机 Keychain（已受系统保护）|
| 撤销路径 | 改密/禁用/删号全量踢（已有）+ 单设备撤销（新增）+ 登出清 Keychain |
| 限流 | 保留 5 次失败锁 15 分钟；Face ID 失败不计入限流，但**回退密码时照常受限**，不放宽 |
| 会话元信息 | 客户端上报的 `device` **不可信**（仅供展示），安全判定一律基于 token 哈希与账号状态 |
| Keychain 读取失败 | 换签名 team / 重装 / 系统策略变化都可能读不到 → **必须无损回退登录页**，禁止卡在启动态 |
| 新隐私面 | 「登录设备」列表本身是敏感信息（设备名/活跃时间）→ 仅本人会话可读，鉴权同其他产品端点 |
| 已知限制 | Flutter Web 上 autofill 支持不确定（阶段 1 实测后如实登记，不承诺）|

---

## 七、影响面（预估文件）

| 端 | 文件 |
|:---|:-----|
| app | `lib/pages/login_page.dart`（autofill + 记住我开关）· `lib/services/user_store_io.dart`（Keychain）· 新增 `lib/services/biometric_service.dart` · `lib/main*.dart`（启动闸门）· `lib/pages/profile_page.dart`（登录设备入口）· `ios/Runner/Info.plist` · `pubspec.yaml` · 测试若干 |
| web | `lib/pages/login_page.dart`（autofill 同步）· `lib/services/user_store_web.dart`（仅语义对齐，不引入原生能力）|
| 后端 | `kernel/auth/SessionRepository`（元信息）· `Session` 模型 · `application/AuthService` · `interfaces/AuthController`（+2 端点）· api-spec / status.md / change-log 登记 |

---

## 八、遗留风险（接受）

- Face ID 属于「便利性安全」：拿到已解锁手机的人可通过 Face ID 进入——这正是产品意图（与系统一致），不是缺陷。
- Keychain 在 iOS 上的行为受签名与描述文件影响，真机形态（侧载/TestFlight）切换时需重测一次。
- L1 的 Web 侧结论可能是「Safari 仍不记密码」——若如此，接受该限制并明确告知，不做架构级改造。

---

## 九、落地记录（滚动）

### 2026-09-14 · 批 1：L1 全部 + L2 后端（本地完成，**未部署**）

| 项 | 落地情况 |
|:---|:---------|
| L1 app | `apps/adai-app/lib/pages/login_page.dart`：`AutofillGroup` + `autofillHints`（username / password / 设密码态 newPassword）；登录与设密码两条成功路径均调 `TextInput.finishAutofillContext(shouldSave: true)` |
| L1 web | `apps/adai-web/lib/pages/login_page.dart`：同上（Safari 实际识别率**待真机实测**） |
| L2 后端 | `Session` 增 `DeviceInfo` 字段（老文件无该键 → 读 null，升级不踢人）；`AuthService.login(..., device)` / `listSessions` / `revokeSession`；`AuthController` 新增 `GET /auth/sessions`、`DELETE /auth/sessions/{idOrPrefix}`；登录响应增 `sessionId` |
| 测试 | 后端 1837→**1851**（+14，全绿）· app **327**（本批 +2）· web 278→**279**（+1）；`flutter analyze` 两端 0 issue |
| 未做（下一批） | L2 客户端：Keychain 存 token + `local_auth` Face ID 门禁 + 「记住我」开关 + 「登录设备」页（见 §三 L2） |

> **上线前必做**（两条真机验收，代码测不到）：
> 1. L1：iPhone 上登录一次 → 系统是否弹「存储密码」；再次打开登录页 → 键盘上方是否出现钥匙串建议、Face ID 确认后能否填入。Safari 端同测，结论如实登记。
> 2. L2 客户端批完成后：杀进程重开 → Face ID → 进 Feed；Face ID 取消 → 回登录页且 token 仍在；`GET /auth/sessions` 看得到本机；撤销后该设备下一次请求 401。

### 2026-09-14 · 批 2：L2 客户端（本地完成，**未部署**）

| 项 | 落地情况 |
|:---|:---------|
| token 存储 | `shared_preferences`（iOS 明文 plist）→ **系统钥匙串**：iOS `KeychainAccessibility.first_unlock_this_device` / Android `EncryptedSharedPreferences`；存量明文**首次读取自动迁移**并清除；VM 测试态短路（否则钥匙串通道会让 widget 测试死等——本批踩到）+ 生产 3 秒超时兜底 |
| Face ID 门禁 | 新增 `lib/services/biometric_service.dart`（`BiometricGate` 接口 + `local_auth` 实现）；`RootApp._validateStoredSession` 校验通过后过闸；**取消/失败 → 回登录页但保留 token**；设备未录入生物识别则直接进；iOS `Info.plist` 加 `NSFaceIDUsageDescription` |
| 「记住我」 | 登录页开关（默认开）→ `UserStore.saveToken(token, remember: …)`；关闭 = token 只留内存 |
| 登录设备 | 新增 `lib/pages/login_devices_page.dart`（列表 / 单台撤销 / 确认弹窗 / 错误人话）；个人档案页入口；`ApiService.listSessions/revokeSession`；登录时上报 `device` |
| 顺手修的既有缺陷 | 启动 401 路径改走 `_handleUnauthorized()`——原内联分支**漏清分享扩展共享容器**（换账号后分享落到上一个账号）+ 补 `_booting=false`（BootScreen 进度圈常转，`pumpAndSettle` 超时） |
| 验证 | app **337 全绿** · 两端 `flutter analyze` 0 issue · `flutter build web --release` 通过（条件导出的 web 实现也编译过）· iOS 集成构建见 status.md |
| **真机验收** | ✅ **2026-09-14 iPhone 装机实测：打开 App（未进主页）即弹 Face ID 启动门禁** → L2 门禁链路在真机生效（钥匙串 token 读取／老值迁移 → `isAvailable` → `authenticate` → 放行，通过后正常进主页）。⏳ 仍待验：①「存储密码」提示（本次没走登录流程，需退出登录后用密码重登一次）②Safari 端 autofill 识别率 ③分享扩展真实分享链路（并行批） |
| **部署** | ✅ **2026-09-14 第六次部署（后端 v3.67 上线，用户授权）**：GATE-BEFORE 三门 PASS + GATE-AFTER smoke **9/9**。**上线后线上实测**：login 含 `sessionId` ✓ · `GET /auth/sessions` 的 `current` 标记与 `device` ✓ · 撤销当前设备 → **400 人话** ✓。**同日数据治理**：生产历史会话 **111 → 0**（110 条无设备信息的旧版/调试/smoke 遗留 + 1 条自检；用户授权）——**代价是各端要重新登录一次**（重登后新会话带设备信息，列表才干净）。**教训（本批自己踩的）**：会话列表首查时把「唯一带 device 的那条」误判成用户手机，其实那是部署自检会话——**判据必须是"带设备信息 + 非自检命名"，不能凭数量猜** |
| 未做 | L3（Passkey / Sign in with Apple）—按决策点 5 暂不做 |
