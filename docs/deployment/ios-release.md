---
title: iOS 发布（TestFlight）
description: 把 iOS 分发从「数据线侧载」换成 TestFlight 的完整方案——为什么绕开 Xcode 云签名、一次性前置、日常一条命令发版、以及实测踩到的坑
version: 1
created: 2026-09-15
updated: 2026-09-15
status: active
lines: 120
depends-on:
  - ../reference/status.md
related:
  - backend-deployment.md
  - ../../../apps/adai-app/AGENTS.md
tags: [deployment, ios, testflight]
---

# iOS 发布（TestFlight）

> **目标**：不再用数据线 + `devicectl` 侧载，改由 TestFlight 无线分发。
> **状态**：2026-09-15 打通并实测——后端无关，App 侧一次构建上传，构建 `1` 在 App Store Connect 为 **VALID**。

## 1. 结论先行：为什么不用 Xcode 云签名

最直觉的做法是 `xcodebuild -allowProvisioningUpdates` + App Store Connect API Key 让 Xcode
**云签名**自动签发分发证书。**这条路在本项目走不通**，实测报错：

```
error: exportArchive Cloud signing permission error
  You haven't been given access to cloud-managed distribution certificates.
  Please contact your team's Account Holder or an Admin to give you access.
error: exportArchive No signing certificate "iOS Distribution" found
```

云托管的**分发证书**要求 **Admin** 角色；而 API Key 用 **App Manager** 角色时云签名会被拒。

**但绕过方式存在**：App Store Connect API **本身**允许 App Manager 直接创建
证书（`POST /v1/certificates`）与描述文件（`POST /v1/profiles`）。
于是本项目的路线是：

```
asc_signing.py 用 API 建「分发证书 + 两个 App Store 描述文件」并装进钥匙串
        ↓
xcodebuild 用 manual 签名导出 IPA（完全不碰云签名）
        ↓
xcrun altool 上传
```

**收益**：不需要 Admin 权限、不需要在 Xcode 里登录 Apple ID、可完全无人值守脚本化。

## 2. 一次性前置（Apple 侧，只能人做）

| # | 做什么 | 在哪 |
|:--|:-------|:-----|
| 1 | 签 **Free Apps Agreement**（免费 App 也必须签） | App Store Connect → Agreements, Tax, and Banking |
| 2 | 建 **App 记录** | App Store Connect → My Apps → ＋（Bundle ID 选 `com.adaiadai.adaiApp`，SKU `adaios-001`） |
| 3 | 生成 **Team Key**（角色 **App Manager 即可**），下载 `.p8` | App Store Connect → Users and Access → Integrations → App Store Connect API → **Team Keys** |
| 4 | `.p8` 放 `~/.appstoreconnect/private_keys/`（保持 `AuthKey_<KEYID>.p8` 文件名） | 本机 |
| 5 | 导出 `ASC_ISSUER_ID`（页面顶部 UUID，所有 Key 共用） | 同上 |

> ⚠️ **必须选 Team Keys**（不是 Individual Keys），且文件名不能改——`altool` 靠它匹配 Key ID。

**本机已就绪的事实（2026-09-15）**：

| 项 | 值 |
|:---|:---|
| App 记录 | id `6812370456`（阿呆阿呆 / `com.adaiadai.adaiApp`） |
| 分发证书 | id `2M8DTF4TPM`，到期 **2027-09-15** |
| App ID 能力 | 两个 App ID 均已开 `APP_GROUPS`；主 App 另有 `PUSH_NOTIFICATIONS` |
| 描述文件 | `AdaiOS App Store adaiApp 4G3D37YKSB` / `AdaiOS App Store ShareExtension 4G3D37YKSB` |

## 3. 日常发版：一条命令

```bash
cd apps/adai-app
export ASC_ISSUER_ID=<Issuer ID>

sh scripts/release_testflight.sh                  # 构建 + 导出 + 上传
sh scripts/release_testflight.sh --skip-build     # 复用已有 archive（省一次构建）
sh scripts/release_testflight.sh --build-number 5 # 递增构建号（同一版本重复上传会被拒）
sh scripts/release_testflight.sh --export-only    # 只导出不上传（先自检签名）
```

脚本做四件事：① `asc_signing.py --ensure` 幂等准备签名资产 → ② 构建 archive →
③ 手动签名导出 IPA → ④ `altool` 上传。

**签名资产管理**（平时不用单独跑）：

```bash
python3 scripts/asc_signing.py --check    # 只读：看证书/描述文件/本地身份现状
python3 scripts/asc_signing.py --ensure   # 幂等：缺则建、有则复用、重复则清理
```

产物集中在 `~/.appstoreconnect/dist/`（权限 700，**在仓库外，永不入 git**）：
`dist.key`（私钥）、`cert.pem`、`profiles.json`（bundleId → profile 名映射）。

## 4. 上传之后

1. App Store Connect → **TestFlight** 等构建从「正在处理」变为可测试（**5~30 分钟**）
2. **Internal Testing** 里把自己（Apple ID）加成测试员 —— 内部测试**不需要 Apple 审核**
3. iPhone 装 App Store 的 **TestFlight** App，收到邀请后即可安装阿呆
4. 构建 **90 天过期**；再次上传必须递增构建号

命令行查状态（无需开浏览器）：

```bash
python3 - << 'PY'   # 需要 ASC_ISSUER_ID
# GET /v1/builds?filter[app]=<app_id>&sort=-uploadedDate
# processingState: PROCESSING → VALID / INVALID / FAILED
PY
```

## 5. 实测踩到的坑（都已固化进脚本）

| 坑 | 症状 | 根因与处置 |
|:---|:-----|:-----------|
| **云签名要 Admin** | `Cloud signing permission error` | 云托管分发证书需 Admin；本项目改用 API 直接建证书（App Manager 即可） |
| **OpenSSL 3 的 p12 macOS 解不开** | `security: SecKeychainItemImport: MAC verification failed during PKCS12 import (wrong password?)` | OpenSSL 3 默认 AES-256/PBKDF2，`security import` 不认 → 必须 `openssl pkcs12 -export **-legacy**` |
| **`include=bundleId` 不能省** | profile 按关联匹配全部落空 → 同一 App 建出第二个 profile；再跑撞 `409 Multiple profiles found with the name …` | App Store Connect API **不加 `include` 时不返回 relationships**；匹配一律带 `&include=bundleId`。同理：DELETE 成功返回 **204 空 body**，`json.loads` 会崩——空 body 要判空 |
| **`flutter build ipa` 的 export 阶段必失败** | 脚本报错但 archive 已产出 | 本机 Xcode 未登录账号 → Flutter 内置 export 走云签名必挂；**archive 才是我们要的**，导出由本脚本接管（脚本已容忍该失败并检查 archive 存在性） |
| **缺隐私清单会被拒** | 上传报 `ITMS-91053` | 已于本批补 `PrivacyInfo.xcprivacy`（主 App + 分享扩展），声明 `UserDefaults` 的 `CA92.1`/`1C8F.1`；产物内 7 份清单齐备（含 `Flutter.framework` 与 4 个插件自带的） |
| **出口合规卡 processing** | 构建长期停在处理中 | `Info.plist` 补 `ITSAppUsesNonExemptEncryption=false`（两个 target），实测上传后 `usesNonExemptEncryption=false` 且直接 VALID |
| **分享扩展签名与 App Groups** | 导出/上传报 entitlements 不匹配 | 归档时扩展用同一 Team 的 distribution 签名；`group.com.adaiadai.adaiApp` 在主 App 与扩展**两个 App ID** 上都要开（本项目已开）；扩展 `CFBundleVersion` 必须与主 App 一致（走 `$(FLUTTER_BUILD_NUMBER)` + 挂 Flutter xcconfig） |

## 6. 与侧载（devicectl）的关系

侧载路线**保留但不再是分发手段**（`docs/deployment/backend-deployment.md` 与
`apps/adai-app/AGENTS.md` 里的 `flutter build ios --release` + `devicectl` 命令仍可用于调试装机）。
TestFlight 解决的正是侧载的三个硬伤：**要数据线、要手机在场、要本机 Xcode 账号**。
