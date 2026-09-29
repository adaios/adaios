---
title: TestFlight Beta 合同缺失（全线不可用）处置
description: Apple 侧 BETA_CONTRACT_MISSING 导致全部 12 个构建同一秒作废——2026-09-29 复核取证、苹果客服路径（工作日 9:30 后可电话）、可直接粘贴的中英文工单材料、以及修复后的复核与发版动作
version: 1
created: 2026-09-29
updated: 2026-09-29
status: active
lines: 220
depends-on:
  - ios-release.md
  - testflight-external-testing.md
related:
  - ../review/REVIEW.md
  - ../reference/status.md
tags: [deployment, ios, testflight, incident]
---

# TestFlight Beta 合同缺失（全线不可用）处置

> **一句话**：App 侧一切正常（12 个构建产物 `VALID`、测试组与审核信息在位），卡点在 **Apple 后台缺 Beta 合同**（`betaLicenseAgreement.agreementText = null`）→ 12 个构建被**同一秒**集体作废、送审返回 422。**项目侧无代码可修**，只能由 Apple 修复。
> **对应条目**：`REVIEW.md` **P1-发布1**（发布阻塞）· 状态表 `status.md` TestFlight 段。

## 1. 现状（2026-09-29 复核，非 09-28 的旧数据）

| 项 | 实测值 | 判读 |
|:--|:--|:--|
| 构建有效期 | **12/12 `expired=true`** | 全部不可安装 |
| `expirationDate` | 12 个**去重后仅 1 种**：`2026-09-27T12:17:51-07:00`（= 北京 **2026-09-28 03:17:51**） | 同一秒 = **非 90 天自然到期**（构建 1/12 的正常到期日应为 12-14 / 12-26） |
| `processingState` | 12 个全 **VALID** | **产物本身没问题**，是生命周期被作废 |
| `betaLicenseAgreement.agreementText` | **null ⚠️** | **根因未消除** |
| 最新构建 v12 送审记录 | `betaReviewState=APPROVED` 但 `submittedDate=null` | 后端数据不一致的旁证 |
| 测试组 | 「阿呆内测」（internal）+「阿呆外测」（external）完好 | 与测试组无关 |
| 审核联系信息 | Kangda Wang / rottokaka@gmail.com / `applereview`（demoRequired=true） | 信息齐备，不是缺料 |

**结论**：截至 09-29 复核，**问题仍在，Apple 侧未自行恢复**——必须主动联系支持。

## 2. 今天就能做的三步

### 第一步：查协议（唯一可能自助的路径，2 分钟）

- App Store Connect → **Business**（协议、税务和银行业务）→ 看是否有**待接受**的协议（Free Apps / Paid Apps / 开发者计划许可协议更新），有则接受；
- `developer.apple.com/account` 首页与 TestFlight 页**是否有红色横幅**；
- 顺手核对：Apple Developer Program 会员资格是否在有效期（本项目 2026-09-13 落地，正常到 2027-09）。

> 社区经验里「开发者账户过期」是同名错误的另一成因，值得一并排除。

### 第二步：联系苹果客服（**工作日上午 9:30 后可电话**）

| 项 | 内容 |
|:--|:--|
| 入口 | `developer.apple.com/contact/` 或 App Store Connect 右上角 **?** → Contact Us |
| 类别 | **App 设置和分发（App Setup and Distribution）→ TestFlight** |
| 渠道 | **工作日 9:30 之后可选电话**（中文沟通，最快）；其余时间只有邮件 |
| 材料 | 客服通常要求**截图 + 操作录屏**（见第三步） |
| 预期时长 | 社区案例：**约 5~7 天**（CSDN 案例 8-18 提交 → 8-23 恢复；SO 已采纳答案「约一周」） |

### 第三步：准备证据（截图 + 录屏）

1. **截图 A**：App Store Connect → TestFlight → iOS 构建列表（12 个构建全部显示已过期）；
2. **截图 B**：尝试「提交 Beta 版 App 审核」时的报错页，并用浏览器 DevTools 的 Network 面板抓到 `betaAppReviewSubmissions` 的 **422 响应原文**（含 `ENTITY_UNPROCESSABLE.BETA_CONTRACT_MISSING`）；
3. **录屏**：从 TestFlight 页面 → 选中构建 → 添加到外部测试组 → 提交审核 → 报错的完整过程（客服明确要过「操作视频」）。

## 3. 工单材料（可直接粘贴）

### 3.1 英文版（邮件/工单正文）

```text
Subject: TestFlight "Beta contract is missing" — all 12 builds expired at the same second, no build installable

Team ID: 4G3D37YKSB
App Name: 阿呆阿呆
Apple ID (app): 6812370456
Bundle ID: com.adaiadai.adaiApp
Account Apple ID: rottokaka@gmail.com
Platform: iOS

Summary
All 12 TestFlight builds of our app became expired at the exact same second, and submitting any build
for Beta App Review fails with ENTITY_UNPROCESSABLE.BETA_CONTRACT_MISSING. No build is installable by
internal or external testers. This started without any change on our side.

Observed (2026-09-29)
- builds 1..12: expired = true (12/12), all sharing expirationDate 2026-09-27T12:17:51-07:00
  (= 2026-09-28 03:17:51 Beijing time). Normal 90-day expiry for build 1 / 12 would be 2026-12-14 / 2026-12-26,
  so this is not natural expiration.
- every build has processingState = VALID — the binaries themselves are fine.
- POST /v1/betaAppReviewSubmissions returns HTTP 422:
    {"status":"422","code":"ENTITY_UNPROCESSABLE.BETA_CONTRACT_MISSING",
     "title":"Beta contract is missing for the app.","detail":"Beta Contract is missing."}
- GET /v1/apps/6812370456/betaLicenseAgreement returns agreementText = null.
- build 12's betaAppReviewSubmission shows betaReviewState = APPROVED while submittedDate = null.
- Beta groups ("阿呆内测" internal, "阿呆外测" external) and betaAppReviewDetail (contact, demo account)
  are all still in place.

What we already verified on our side
- Our automation only performs: build, export, altool upload, status query, assign-build, submit-review.
  It contains no action that expires or removes builds.
- Re-uploading a new build does not help (community-confirmed: it becomes VALID but is not installable,
  and submission still returns 422) — so we have deliberately stopped incrementing build numbers.

Request
1. Please repair/regenerate the Beta License Agreement for this app so TestFlight works again.
2. Please restore availability of the existing builds if possible, or confirm that a new upload will be usable.
3. Please confirm the root cause on the account/app side, so we can rule out anything on our side.

Screenshots and a screen recording reproducing the 422 error are attached.
```

### 3.2 中文版（电话/中文邮件用）

```text
主题：TestFlight「Beta contract is missing」——12 个构建同一秒全部过期，无任何构建可安装

Team ID：4G3D37YKSB
App 名称：阿呆阿呆
App Apple ID：6812370456
Bundle ID：com.adaiadai.adaiApp
账号 Apple ID：rottokaka@gmail.com
平台：iOS

问题
我们 App 的全部 12 个 TestFlight 构建在同一秒被置为过期，且任何构建提交 Beta 版 App 审核都返回
ENTITY_UNPROCESSABLE.BETA_CONTRACT_MISSING，内测与外测均无法安装。我方无任何相关改动。

实测（2026-09-29）
1. 构建 1~12 全部 expired=true，expirationDate 全部为 2026-09-27T12:17:51-07:00
   （即北京时间 2026-09-28 03:17:51）——同一秒，排除 90 天自然到期
   （构建 1 / 12 的正常到期日应为 2026-12-14 / 2026-12-26）。
2. 12 个构建 processingState 均为 VALID，说明构建产物本身正常。
3. 调用 POST /v1/betaAppReviewSubmissions 返回 HTTP 422：
   ENTITY_UNPROCESSABLE.BETA_CONTRACT_MISSING / "Beta contract is missing for the app."
4. 查询 betaLicenseAgreement 返回 agreementText = null。
5. 构建 12 的审核记录出现 betaReviewState=APPROVED 但 submittedDate=null 的不一致。
6. 内测组「阿呆内测」、外测组「阿呆外测」、审核联系信息与测试账号均完好。

我方已排除
- 我方脚本只做：构建、导出、altool 上传、状态查询、分配测试组、提交审核；不含任何「移除/置过期构建」动作。
- 重新上传构建无用（社区实证：能变 VALID 但装不上、送审仍 422），故我方已停止递增构建号。

请求
1. 请在后台修复/重建该 App 的 Beta 合同，使 TestFlight 恢复可用；
2. 如有可能，请恢复现有构建的有效期；否则请确认新上传的构建可用；
3. 请告知根因是否在账号/App 侧，以便我方彻底排除自身因素。

已附复现 422 的截图与录屏。
```

### 3.3 电话话术（30 秒版）

> 「你好，我是开发者，Team ID `4G3D37YKSB`，App 叫阿呆阿呆（Apple ID 6812370456）。9 月 28 日凌晨，我全部的 12 个 TestFlight 构建在同一秒被置为过期，现在内测外测都装不了，提交审核返回 422，错误是 `Beta contract is missing for the app`。我这边脚本和构建产物都没问题，构建状态都是 VALID。社区里同类问题都是后台修复的，能不能帮我转一下 TestFlight 相关的支持，或者帮我开一个工单？」

## 4. 证据

### 4.1 构建清单（全 12 个，2026-09-29 实测）

| 构建 | 上传（UTC） | processingState | expired | expirationDate |
|:--|:--|:--|:--|:--|
| 12 | 2026-09-27 11:00:40 | VALID | **true** | 2026-09-27T12:17:51 |
| 11 | 2026-09-23 09:35:58 | VALID | **true** | 2026-09-27T12:17:51 |
| 10 | 2026-09-22 08:43:51 | VALID | **true** | 2026-09-27T12:17:51 |
| 9 | 2026-09-19 10:07:11 | VALID | **true** | 2026-09-27T12:17:51 |
| 8 | 2026-09-17 09:10:04 | VALID | **true** | 2026-09-27T12:17:51 |
| 7 | 2026-09-16 10:18:30 | VALID | **true** | 2026-09-27T12:17:51 |
| 6 | 2026-09-16 08:00:44 | VALID | **true** | 2026-09-27T12:17:51 |
| 5 | 2026-09-15 09:59:35 | VALID | **true** | 2026-09-27T12:17:51 |
| 4 | 2026-09-15 09:31:38 | VALID | **true** | 2026-09-27T12:17:51 |
| 3 | 2026-09-15 08:53:03 | VALID | **true** | 2026-09-27T12:17:51 |
| 2 | 2026-09-15 07:42:26 | VALID | **true** | 2026-09-27T12:17:51 |
| 1 | 2026-09-15 06:33:46 | VALID | **true** | 2026-09-27T12:17:51 |

> 12 行的 `expirationDate` **完全相同**（同一秒），这是「集体作废」而非「自然到期」的决定性证据。

### 4.2 关键 API 证据

- `GET /v1/apps/6812370456/betaLicenseAgreement` → **`agreementText: null`**
- `POST /v1/betaAppReviewSubmissions` → **HTTP 422 `ENTITY_UNPROCESSABLE.BETA_CONTRACT_MISSING`**（2026-09-28 实测；本次复核**刻意未重复 POST**——它是写操作，且已知必失败）
- `GET /v1/builds/{v12}/betaAppReviewSubmission` → `betaReviewState=APPROVED` · `submittedDate=null`（数据不一致）
- `GET /v1/betaGroups` → 「阿呆内测」(internal) / 「阿呆外测」(external) 均在
- `GET /v1/apps/6812370456/betaAppReviewDetail` → 联系人/测试账号信息完整

### 4.3 为什么可以断定不是项目侧问题

| 已排查 | 证据 |
|:--|:--|
| 构建产物 | 12 个 `processingState` 全 **VALID** |
| 自动化脚本 | `testflight_external.py` 只有 status / fill / review-info / submit-review / assign-build / invite，**无任何移除或置过期动作**；`release_testflight.sh` 只做签名导出 + `altool` 上传 |
| 签名与描述文件 | 分发证书 id `2M8DTF4TPM`（到期 2027-09-15），上传均 `UPLOAD SUCCEEDED` |
| 测试组 / 审核信息 | 组与联系人信息完好（见 4.2） |
| 时间相关性 | 作废时刻与任何一次我方部署/上传都不对应（构建 1 上传于 09-15，构建 12 上传于 09-27，同一秒一起过期） |

## 5. 本机复核方法（只读）

```bash
# 官方脚本（⚠️ 当前环境跑不起来：本机 python3 3.9.6 与 homebrew python3.13 均未装 PyJWT）
cd apps/adai-app && python3 scripts/testflight_status.py

# 2026-09-29 实际使用的零安装探针（临时文件，不入库）：
#   /tmp/asc_probe.py —— 用系统 python3 user site 里已有的 Cryptodome 直签 ES256 JWT，
#   GET 上述端点；输出留档 /tmp/asc-probe-20260929.txt
python3 /tmp/asc_probe.py
```

> ⚠️ **副产品待办**：`testflight_status.py` 依赖 `PyJWT`，本机两个解释器都没装 → **脚本当前不可用**（本次靠临时探针取证）。要么装 `pyjwt`，要么把脚本的 JWT 签名换成零依赖实现（Cryptodome 或 `openssl`）。**未修**（不属本次处置范围）。

## 6. 修复后的动作（Apple 回复或恢复后）

1. **先复核**：确认 `expired=false`、`agreementText != null` —— 不要直接开始上传；
2. 上传新构建：`cd apps/adai-app && sh scripts/release_testflight.sh --build-number 13`（构建号必须递增）；
3. 上传后：`--status` 等 VALID → `testflight_external.py --assign-build`（外测组）+ `--submit-review`；
4. **登记闭环**：把 `REVIEW.md` **P1-发布1** 标记为已修（附日期与 Apple 侧凭据），同步 `status.md` TestFlight 段；
5. 社区反复强调：**在 Apple 修好前不要靠反复递增构建号「刷可见」**——白费构建号且仍装不上。

## 7. 外部依据

- [Apple 论坛 thread 848236 — BETA_CONTRACT_MISSING，无构建可安装](https://developer.apple.com/forums/thread/848236)（同名主帖）
- [Apple 论坛 thread 822027 — "Beta app contract is missing" + 422，所有 App 受影响](https://developer.apple.com/forums/thread/822027)
- [Apple 论坛 thread 814565 — TestFlight Beta Contract Missing](https://developer.apple.com/forums/thread/814565)
- [Stack Overflow 69822285 — 已采纳答案：联系苹果开发者支持，约一周](https://stackoverflow.com/questions/69822285/app-store-connect-submit-for-review-error-entity-unprocessable-beta-contract-mi)
- [CSDN 案例 — 走「联系我们 → App 设置和分发 → TestFlight」，提交截图+录屏，约 5 天后恢复](https://blog.csdn.net/woashizhangsi/article/details/132376323)
- [fastlane #29673 — 跨 4 个 App、同一 2 秒窗口过期，Apple 确认需后台反向修复](https://github.com/fastlane/fastlane/issues/29673)
