---
title: 分享扩展凭据方案——从「手动签发一次」到「登录即用」（对齐业界：登录自动写入 + 最小权限 + 自动续期）
description: 现状是分享扩展的限权令牌必须由用户在学习页手点一次「签发」才写进 App Group 容器，入口还是一个无提示的 44pt 图标——2026-09-15 TestFlight 首次实装后暴露：别人装完、登录、然后用不了分享，且不知道原因。方案把「签发」从用户动作改成登录后自动完成并自动续期（保留最小权限设计），并可选把凭据通道从 App Group 明文容器升级为共享 Keychain。
date: 2026-09-15
status: draft
decided-by: 待 adai 拍板（决策点见 §六）
tags: [ios, share-extension, credentials, keychain, testflight, 用户体验]
related:
  - ./20260914-login-credential-experience.md
  - ../deployment/ios-release.md
  - ../review/REVIEW.md
---

# 分享扩展凭据方案——从「手动签发一次」到「登录即用」

> **触发来源**：2026-09-15 TestFlight 首次实装后真机验证。分享扩展报「**没有拿到钥匙**」；
> 用户追问：「为什么别的 App 不要拿密钥？阿呆给别人用，还得让人家这么操作一下么？」
>
> **本方案只做整理，未改任何代码**（AGENTS.md 规则 7：讨论与实施分离）。

---

## 一、现状事实（已核实，2026-09-15 读代码 + 生产日志）

| 项 | 现状 | 证据 |
|:---|:-----|:-----|
| 分享扩展的进程模型 | iOS 分享扩展是**独立进程**，读不到主 App 的 `shared_preferences`，也读不到主 App 默认 access group 的钥匙串 | `lib/services/share_extension_service.dart` L48-51 |
| 会话 token 存哪 | `flutter_secure_storage` → iOS **Keychain**，`accessibility: first_unlock_this_device`，**未设置 `accessGroup`**（＝默认组，**扩展读不到**） | `lib/services/user_store_io.dart` L22-24 |
| 扩展用的凭据 | 单独签发的**限权令牌**（`scopes=[learn:digest]`），不是会话 token —— 只能提交链接去整理 | `lib/services/api_service.dart` L1249-1254 |
| 凭据怎么过去 | 签发成功后经 MethodChannel `adai/share` → `ShareBridge.swift` → 写入 **App Group UserDefaults**（`group.com.adaiadai.adaiApp`），原生侧**回读校验** | `share_extension_service.dart` L68-82；`ios/Runner/ShareBridge.swift` |
| 扩展怎么读 | `UserDefaults(suiteName: group.com.adaiadai.adaiApp)` 取令牌 | `ios/ShareExtension/ShareAuth.swift` L22 |
| **签发动作由谁触发** | **用户手动**：学习页顶栏一个 **44pt 分享图标**（Tooltip「把分享接到阿呆」）→ `ShareTokenDialog` | `lib/pages/learn_page.dart` L287-302 |
| 令牌有效期 | **90 天**（`ApiToken.DEFAULT_TTL_DAYS`），到期即拒；撤销端点为 `DELETE /api/v1/auth/tokens/{idOrPrefix}` | `ApiTokenService.java` L114-121；`AuthController.java` L162/184/202 |
| entitlement 现状 | 主 App：`aps-environment=development` + `com.apple.security.application-groups`；扩展：只有 App Groups。**两个 target 都没有 `keychain-access-groups`** | `ios/Runner/Runner.entitlements`、`ios/ShareExtension/ShareExtension.entitlements` |

**生产日志侧的证据**（这是"不是猜的"那一步）：TestFlight 版装好后 3 小时内，
`journalctl -u adai-core` 中「签发外部令牌」记录 **0 条** → 共享容器里没有令牌 → 扩展必然报「没拿到钥匙」。

## 二、问题定性

**不是 bug，是设计缺口**：

- **做对的部分**：刻意不给分享扩展「登录会话 token」（那是全权凭据：能读所有记录、能删、能改账本），
  而是单签一把只有 `learn:digest` 一个权限的令牌 —— 符合最小权限原则，**这个取舍应保留**。
- **缺的部分**：把「签发」这一步**留给了用户**，而且入口是学习页右上角一个**没有任何提示的图标**。

**后果**（用户这句话点中的正是这里）：

| 场景 | 表现 |
|:---|:-----|
| 自己侧载时 | 2026-09-15 13:49 手点过一次 → 钥匙一直在容器里 → **问题被掩盖** |
| 换 TestFlight 全新安装 | 容器是新的 → 分享失败 |
| **给别人装（家人/朋友）** | 装完 → 登录 → 分享失败 → **完全不知道要干什么** |

## 三、业界成熟做法（回答「别的 App 怎么做的」）

**先纠正一个前提：别的 App 不是「不需要密钥」，而是「不让你操心」** —— 扩展要替你提交内容就必然要有凭据，
区别只在**凭据怎么进到扩展手里、以及谁来完成这一步**。

### 三种主流模式

| 模式 | 做法 | 安全级别 | 典型 |
|:-----|:-----|:---------|:-----|
| ① **共享 Keychain**（Apple 推荐） | 两个 target 开 **Keychain Sharing**、声明同一 access group；主 App 登录后写入，扩展用 `kSecAttrAccessGroup` 读 | **高**（钥匙串加密、受设备锁保护） | 绝大多数正式 App |
| ② **App Group 共享容器** | 主 App 写 `UserDefaults(suiteName:)` 或共享文件，扩展直读 | **中低**（容器是**明文 plist**，未加密备份可读） | 图省事，常用于**短期/低权**凭据 |
| ③ **扩展不碰凭据** | 扩展只把内容写共享队列，主 App 下次启动时上传 | 高 | 离线收藏类（不要求即时提交） |

### 四条业界共识

1. **凭据在主 App 登录成功那一刻自动写入共享位置** —— 用户不知道有「钥匙」这回事（这条是绝对主流）
2. **优先共享 Keychain 而非 App Group 容器** —— 后者是明文，只适合放"非敏感标志"
3. **扩展只拿最小权限凭据** —— 阿呆**已符合**
4. **短期凭据 + 自动续期**，且**失效要给可操作的出路**（不能只报「没拿到钥匙」）

> **「让用户手动去某页面点一次签发」不是业界的任何一档，是阿呆独有的。**

## 四、方案对比

| 方案 | 用户操作 | 安全性 | 改动量 | 结论 |
|:-----|:---------|:-------|:-------|:-----|
| **A 现状**：保持手动签发，只优化入口可见性 | 仍要自己点一次 | 最高 | 极小 | ❌ 没解决"给别人用" |
| **B 登录后自动签发 + 自动续期**（推荐） | **零** | 高（仍是限权令牌） | 小（纯 app 侧） | ✅ **推荐** |
| C 扩展直读主 App 钥匙串的**会话 token** | 零 | **低**（全权 token 暴露给扩展进程） | 中（要配 keychain sharing） | ❌ 与既有安全取舍冲突 |
| D 扩展内自己登录一次 | 要再登一次 | 中 | 大 | ❌ 体验更差 |

**结论：走 B。** 它保住了「扩展只拿最小权限」的设计，同时把手动步骤彻底去掉。

## 五、推荐方案（B）详细设计

分两阶段，**阶段 1 就足以解决用户痛点**，阶段 2 是安全加固、可独立决定。

### 阶段 1：自动签发 + 自动续期（解决「别人装完不能用」）

**5.1 触发时机**（三处，均为"静默、失败不打扰"）

| 时机 | 动作 |
|:-----|:-----|
| **登录成功后** | 若共享容器无令牌 → 签发一把 `learn:digest` 令牌并写入容器（**核心**：这一步让新用户零操作） |
| **App 启动时**（已登录态） | 读容器状态：无令牌 → 补签；**距过期 < 7 天 → 续签** |
| **换账号时** | 撤销旧账号那把 + 为新账号签发（避免"上一个账号的钥匙"） |

**5.2 续签与旧令牌回收（必须一起做，否则令牌无限累积）**

流程固定为「**先签新、写容器成功、再撤销旧**」：

1. `POST /api/v1/auth/tokens`（scopes=`learn:digest`）→ 拿到新明文 + 新 id
2. `adai/share` 写入容器，**以原生回读校验为准**（现有 `saveToken` 已返回 bool）
3. 写入成功后才 `DELETE /api/v1/auth/tokens/{旧 id}`；写入失败则**不撤销旧的**（宁可多一把，不能两把都失效）
4. 幂等：同一账号同一时刻只应有一把「分享令牌」，重复触发要能识别（按写入容器里的 id 比对）

> 这一步顺带推进了 REVIEW 里登记的 **S-凭据1**（外部凭据生命周期只有手动撤销一条出口）——
> 自动续签如果不管旧令牌，反而会把这个问题放大。

**5.3 失败兜底**

- 签发失败（网络/后端异常）**绝不阻塞登录**，静默记日志，下次启动再试
- 学习页的手动入口**保留**（作为人工兜底与排障通道），但不再是必经之路
- 扩展侧话术改造：读不到令牌时从「阿呆还没拿到钥匙」改为
  「**分享还没接通：打开阿呆一次就好**」（给动作，不给困惑）

**5.4 多账号**

容器是单一槽位。切账号时必须「撤销旧 + 写新」；容器里记录的 token id 要能对上"当前账号"，
否则会出现「A 账号的分享落到 B 账号」（此坑在 2026-09-14 分享扩展批已因"登出不清容器"栽过一次，
见 pitfalls 十七族与 REVIEW 相关条目）。

### 阶段 2（可选加固）：通道从 App Group 明文容器升级为**共享 Keychain**

- 两个 target 加 `keychain-access-groups` entitlement，声明同一 access group
  （如 `$(AppIdentifierPrefix)com.adaiadai.shared`）
- 主 App 写入、扩展读取都带 `kSecAttrAccessGroup`
- **收益**：令牌从"明文 plist"变为"钥匙串加密存储"，对齐业界模式①
- **代价**：需在 Apple 侧为**两个 App ID** 开启 Keychain Sharing 能力
  （可用 `apps/adai-app/scripts/asc_signing.py` 的同类 API 操作完成，但要重新生成 profile 并重签）
- **风险**：引入新的签名能力 = 新的失败面（App Groups 当初也是这么过来的）

> **建议**：阶段 1 先上（纯 app 侧、无 Apple 侧能力变更、当天可验）；
> 阶段 2 作为独立小批，等阶段 1 在真机稳定后再做。

## 六、关键决策点（待拍板）

1. **是否做阶段 2**（共享 Keychain）：现在一起做 / 阶段 1 稳定后另开批 / 不做（接受 App Group 明文容器）
2. **续签阈值**：距过期 **7 天**触发（推荐）／ 30 天 ／ 每次启动都续（最激进，令牌轮换最勤）
3. **学习页手动入口**：保留（推荐，排障用）／ 隐藏（更干净）
4. **旧令牌回收策略**：写成功即撤销旧的（推荐）／ 保留最近 N 把（可回溯，但攻击面更大）
5. **是否同时修「扩展报错话术」**（推荐一起改，成本极低）

## 七、安全边界与风险

| 项 | 说明 |
|:---|:-----|
| **不做什么** | 绝不把会话 token（全权）交给扩展或快捷指令；这条红线不因体验而让步 |
| 限权范围 | 分享令牌只有 `learn:digest`：能提交链接去整理，**读不了任何个人数据** |
| 令牌落地形态 | 阶段 1 仍是 App Group 明文容器（与现状一致，风险不变）；阶段 2 才升级为钥匙串 |
| 自动签发的新风险 | App 在后台"替用户"签凭据 —— 需明确：仅在**已登录态 + 容器缺失/临期**时触发，且用户可随时在学习页撤销 |
| 续签竞态 | 两端/多次触发同时续签 → 可能签出两把。用"先写容器成功再撤销旧"的顺序 + 幂等判断收敛（不要只靠"点一次"测试） |
| 90 天过期 | 自动续期后，理论上用户永不感知；若长期不开 App 导致过期，扩展话术要能引导打开 App |

## 八、影响面（预估文件，未动手）

| 文件 | 改动性质 |
|:-----|:---------|
| `apps/adai-app/lib/services/share_extension_service.dart` | 新增「确保令牌存在/有效」的编排入口（判断 → 签发 → 写入 → 撤销旧） |
| `apps/adai-app/lib/services/api_service.dart` | 复用既有 `POST/GET/DELETE /api/v1/auth/tokens`（无需新端点） |
| `apps/adai-app/lib/main.dart` 或登录流程 | 挂"登录成功后自动确保令牌"的钩子（失败不阻塞） |
| `apps/adai-app/lib/widgets/share_token_dialog.dart` | 复用其签发逻辑；入口从"必经"降级为"排障入口" |
| `apps/adai-app/lib/pages/learn_page.dart` | 可选：状态提示条（若保留图标则不必须） |
| `apps/adai-app/ios/ShareExtension/ShareAuth.swift` | 失败话术改为可操作引导（若做阶段 2 则改为读共享钥匙串） |
| `apps/adai-app/ios/Runner/Runner.entitlements`、`ShareExtension.entitlements` | **仅阶段 2**：加 `keychain-access-groups` |
| `apps/adai-app/test/` | 新增：自动签发触发条件、续签顺序（先写后撤）、换账号、失败不阻塞登录 |
| `apps/adai-app/AGENTS.md`、`docs/reference/status.md`、`docs/reference/change-log.md` | 契约与登记 |

**后端：无需改动**（令牌端点、90 天 TTL、按 id 撤销都已具备）。

## 九、落地节奏（建议）

1. **阶段 1**（半天）：自动签发 + 续签 + 撤销旧 + 话术 → 单测 → **全新安装真机复验**（关键验收）
2. 真机稳定后，提交并按 TestFlight 发一版（`--build-number 2`）
3. **阶段 2**（另开小批，可选）：共享 Keychain 升级 + 重签 profile + 真机复验

## 十、验收标准（必须是"真机 + 全新安装"，不能只看单测）

1. **零操作可用**：卸载 App → TestFlight 重装 → **只做登录** → 直接去 B站分享 → **成功提交给阿呆**
2. 登录后学习页分享图标的状态显示「已就绪」（而不是「还差一步」）
3. 人为把容器里的令牌删掉 → 重启 App → 自动补签（日志可见签发记录）
4. 续签路径：把令牌有效期改到临期（或调小阈值）→ 启动 App → 观察到「先签新、再撤旧」，且旧令牌撤销后 `GET /auth/tokens` 只剩一把
5. 换账号：A 登出 → B 登录 → 分享落到 B 账号（不串号）
6. 签发失败（断网）时**登录仍成功**，不弹错误

## 十一、不做清单（明确边界）

- ❌ 不给扩展/快捷指令会话 token（C 方案）
- ❌ 不在扩展内做独立登录（D 方案）
- ❌ 不引入 Passkey / Sign in with Apple（属登录体验线，见 RFC 20260914 的 L3）
- ❌ 不改后端令牌契约（现有端点足够）
- ❌ 不为"多做一步"而保留现状（用户已明确：给别人用不能这样）

## 十二、遗留风险（接受）

1. 阶段 1 期间令牌仍以**明文**存在于 App Group 容器（与现状一致，不因本批变差）
2. 自动续签增加了「App 替用户签凭据」的行为面 —— 以"仅限限权 scope + 用户可见可撤销"约束
3. 长期不开 App 仍可能过期（扩展话术负责引导，不追求 100% 无感）
