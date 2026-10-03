---
title: TestFlight Beta 合同缺失（全线不可用）处置
description: Apple 侧 BETA_CONTRACT_MISSING 导致全部 12 个构建同一秒作废——2026-09-29 复核取证、苹果客服路径（工作日 9:30 后可电话）、可直接粘贴的中英文工单材料、2026-10-01 工单回信与复核（§1.9）、2026-10-03 复核「手机内测恢复可用但合同仍 null」（§1.11）、2026-10-03 到点复核「48h 到期后仍 null · 用户决定不传构建、等 Apple 修」（§1.12）、以及修复后的复核与发版动作
version: 1
created: 2026-09-29
updated: 2026-10-03
status: active
lines: 391
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

### 1.5 🆕 构建 13 实测（2026-09-29，已上传并复现）

用户拍板「试试么」后做的完整实测。结论：**外测死路被钉死；且合同缺失连内测也堵**。

| 环节 | 实测值 |
|:--|:--|
| 上传 | `UPLOAD SUCCEEDED` · Delivery UUID **`f6cde1d3-1981-4b6e-8c6d-ca7f4546e407`** · 23,778,657 B · 40.8 秒（09:46:41 完成）|
| Apple 处理 | 09:47:30 出现 → **09:49:03 `VALID`**（约 2.5 分钟）|
| 有效期 | **`expired=false`** · `expirationDate=2026-12-27` —— **新构建没有被作废**（旧 12 个仍全废）|
| 送审 | **仍 HTTP 422 `BETA_CONTRACT_MISSING`**（新错误 id `27d1f2a0-b444-4fb5-9e42-391cb4f5b7ac`）|
| **iPhone 端实测（用户本人）** | **TestFlight 里能看到「1.0.0 (13)」，但点「更新」无法下载** ⚠️ |

**判读**：① **内测同样被堵**——构建可见、VALID、未过期，但**客户端下载被阻断**，不是只有外测受影响；② 社区「重传无用」在本机得到实证；③ **构建 13 不是白传**：它未被作废，Apple 一修复合同即可直接使用，无需再构建再传。（**⚠️ 此判读已于 2026-09-30 被推翻，见 §1.8**）

> **🆕 构建 14 复现（2026-09-29 23:34，用户「帮我发个 14 么」授权）**：Delivery UUID `932bf0d1-2f2e-44cd-aee0-be9955d2db92` → 23:34:06 `VALID` · **`expired=false`**（到期 **2026-12-28**）· 送审**仍 422**（id `42d0936e-6f0f-41e5-81b2-422e64f5608e`）· 已加入外测组。**与构建 13 完全同一形态** → 换构建号对结果**零影响**，唯一变量是 Apple 侧合同。~~**现有 13、14 两个干净构建在外测组待命**，合同恢复后对最新者送审即可，**不必再传**。~~ **⚠️ 2026-09-30 更正**：构建 13 已于 09-30 00:41:29（北京）被同一机制作废 → **只剩构建 14 一个**在外测组待命（见 §1.8）。
>
> 采坑记录（本轮）：构建 14 首次尝试时，后台任务里的 `danger-full-access` **未落到描述文件写入步骤**（`~/Library/MobileDevice/Provisioning Profiles` 被拦）；第二次前台重试又遇 Apple API **60 秒读超时**；第三次才成功。**教训**：该脚本必须在前台以更宽权限运行，且不要用 `grep` 过滤其输出（会把错误一起滤掉，只剩「看起来跑完了」的假象）。

### 1.6 账号级合规排查（2026-09-29 傍晚，用户操作 + 实测）

怀疑根因在账号级监管/协议状态后做的专项排查：

| 排查项 | 结果 |
|:--|:--|
| 欧盟《数字服务法》(DSA) 合规 | ✅ **已完成**——用户在 App Store Connect **Business（商务）页**操作，界面提示「**你目前已完成所有监管要求**」|
| 账号协议状态 | ✅ **全部有效**（用户核对 Business 页：无待接受、无过期）|
| 触发重建动作 | 已试且均 HTTP 200：`testflight_external.py --fill`（重提 TestFlight 测试信息 + 隐私政策 URL + 构建 13 的 What to Test）· `--assign-build`（**构建 13 已成功加入外测组「阿呆外测」**）→ **合同仍 null、送审仍 422**（id `52306c1b-…`）|

**结论**：**账号级监管与协议层已完全干净**，Beta 合同依然未重建 → **自助路径全部穷尽**（协议 / DSA / 重传 / 触发动作四类都试过），唯一出路是 **Apple 后台修复**。

### 1.7 📮 工单已提交（2026-09-30 00:40，用户本人操作）

| 项 | 内容 |
|:--|:--|
| **Case ID** | **`102980309269`** ← 追踪 / 催单 / 对照的唯一锚点 |
| 渠道 | `developer.apple.com/contact` → 邮件表单（App Store Connect → TestFlight 类目；深夜无电话选项）|
| 提交内容 | §3.1 英文正文（含 12 构建同一秒作废 + 构建 13/14 实测 + DSA 合规与触发动作均无效）+ 附件 |
| 表单字段 | 应用 Apple ID `6812370456` · 版本版号 `1.0.0 (14)` · 问题时间 `2026-09-29 23:34 UTC+8` |
| 后续 | Apple 首次回复通常 **24~48h**；**下一个工作日 9:30 后打电话报 Case ID 催单**；修复后按 **§6** 动作清单收尾 |

### 1.8 🔁 只读复核（2026-09-30 13:24，AI 经 ASC API 实测；全程 `GET`）

工单提交后 **12.7 小时**做的复核（未做任何写操作——**刻意不再 POST 送审探针**，已知必失败）：

| 项 | 实测值 | 判读 |
|:--|:--|:--|
| `betaLicenseAgreement.agreementText` | **`null`** | ★ **Apple 未修**，根因仍在 |
| **构建 14**（最新） | `VALID` · **`expired=false`** · 到期 2026-12-28 | **唯一可用筹码** |
| **构建 13** | **`expired=true`** · 到期 `2026-09-29T09:41:29-07:00` = 北京 **2026-09-30 00:41:29** | ⚠️ **已被吞掉**（自上传存活 **≈15 小时**）→ §1.5 ③ 作废 |
| 构建 1–12 | 全 `expired=true`，12 个同一秒 | 未恢复 |
| 构建 14 的 `betaAppReviewSubmission` | `data: null` | 与 422 一致（从未提交成功） |
| 测试组 | 「阿呆内测」/「阿呆外测」完好 | 与测试组无关 |

**新认识（比 09-29 更准）**：**`expired=false` 只是暂态、不是终态**——构建 13 在 09-29 实测明明是未作废，15 小时后仍被吞掉（构建 12 也只活了约 8 小时）。**推论**：不要指望「先传一个放着、等 Apple 修好直接用」；合同一恢复应**立即**对当时仍有效的构建送审（现在就是构建 14）。

**时序如实登记**：工单 00:40 提交 · 构建 13 于 00:41:29 作废（相距 1 分半）——**不可据此认定因果**（Apple 不会在收单 90 秒内去作废构建），更合理的解释是同一条自动清理逻辑定期扫；可用的判据仍只有 `agreementText`。

**回复面**：截至复核，工单 **12.7 小时**，仍在 Apple 首次回复的常规 **24~48h** 窗口内（尚不算异常）。**邮件回复 AI 读不到**——本机 `~/Library/Mail` 受 macOS 权限保护（`Operation not permitted`），Apple 工单也没有免登录查询接口 → **需用户本人查邮箱**（发件人常为 `developer@email.apple.com` / `no_reply@email.apple.com`），或把回信内容交 AI 判读。

### 1.9 📬 Apple 首次回复 + 只读复核（2026-10-01 15:14）

用户收到开发者支持 **Jermy** 的邮件回复（Case **`102980309269`**），全文要点：**「我们已经提交了你的案例，请在 48 小时后上传新的构件版本再次尝试」**；办公时间周一至周五 9:00–17:00，有疑问时报 Case ID。

> **判读：这是「已受理 / 已上报」的模板回复，不是修复确认**——信中**没有**说 Beta 合同已重建，也没给生效时间。

同日 **15:14 只读复核**（AI 经 ASC API 实测，全程 `GET`，未做任何写操作）：

| 项 | 实测值 | 判读 |
|:--|:--|:--|
| `betaLicenseAgreement.agreementText` | **仍 `null`** | ★ **Apple 后台尚未修复**，根因仍在 |
| 构建 14（最新） | `VALID` · **`expired=false`** · 到期 2026-12-28 | **唯一有效构建**；自上传（09-29 23:34 北京）已存活 **≈40 小时** |
| 构建 13 | `expired=true`（09-30 00:41 作废，存活 ≈15 小时） | 未恢复 |
| 构建 1–12 | 全 `expired=true`（12 个同一秒） | 未恢复（**13/14 已作废**）|
| 构建 14 的 `betaAppReviewSubmission` | **`data: null`** | 从未提交成功（与 422 一致）|
| 测试组 | 「阿呆内测」/「阿呆外测」完好 | 与测试组无关 |

**「48 小时后传新构建」这条路，本机已经实证两次无效**：构建 13（09-29 09:46 上传）与构建 14（09-29 23:34 上传）都是**新构件 + `VALID` + `expired=false`**，结果送审**仍 422**、手机端**仍「看得到、下不动」**。故这句话应当按**流程口径**理解（Apple 已把案例提交上去），**不能当作「传了新构建就会好」**。

**到点动作（不要一到点就传）**：

1. **先验 `agreementText`**（§6 步骤 1；一条 `GET`）——**这是唯一判据**，`expired=false` 不算（§1.8）；
2. **若已恢复** → **直接用构建 14** `--assign-build` + `--submit-review`，**不必再传**（§6 步骤 2）；
3. **若仍为 `null`** → 按 Apple 明确指引**传一次新构建（15）**再试一次，并把「新构建仍 422」回信给 Case `102980309269`（这条是「照官方口径执行了」的证据）；**不要连传多个构建号**（§6 步骤 5）；
4. 若构建 14 已被同一机制吞掉 → 走步骤 3（此时传 15 是被迫的）。

**时间窗口**：**48 小时**（自 Apple 回信 / 电话确认起算，2026-10-01 下午）→ 到点 ≈ **10-03（周六）15:25**。两个窗口都合规：

- ① **严格照做** → **10-03（周六）15:25 之后**执行；缺点：Apple 支持不上班，若仍失败只能等周一；
- ② **要人兜底** → **10-05（周一）9:30 之后**执行（同样晚于 48h，且支持在班）。

**推荐做法（两个窗口下通用）**：到点**先只读复核 `agreementText`（不传构建）**——恢复就立刻对有效构建送审；没恢复就先别白费构建号，等支持在班时电话/回信。这样既不早于 48 小时，也不会重演构建 13 / 14「传了也白传」。

**☎️ 电话确认（2026-10-01 下午，用户本人）**：电话联系 Apple 开发者支持后，**口径与回信完全一致**——**等 48 小时后再试**；**未给修复时间、也未确认后台是否已改动**。**决定（用户）**：**照做**，48 小时后（10-03 15:25 之后）再动，到点按上面的顺序执行。

### 1.10 为什么 Apple 会说「48 小时后传个新构建」——用人话讲

这不是推诿，也不是「新构建能治好」，而是**一线支持的能力边界 + 一个合理的工程习惯**（⚠️ 以下是对 Apple 行为的推断，不是他们明说的，登记时如实标注）：

| 疑问 | 解释 |
|:--|:--|
| **Beta 合同是什么**？ | Apple 后台给这个 App 记的「TestFlight 测试协议」记录。正常账号自动就有；你们这份**丢了**（`agreementText = null`），所以凡是需要它的动作全都 422。 |
| **客服为什么不能当场改**？ | 一线支持**没有后台数据权限**——他能做的只有「收单 + 转给工程团队」（所以他说「我们已经提交了你的案例」）。 |
| **为什么要 48 小时**？ | 工程团队处理这类后台数据修复的常规周期；48 小时是他们给的一线等待口径（社区同类案例约 **5~7 天**，见 §7）。 |
| **为什么要「新的构件版本」**？ | 合同坏掉期间上传的构建（12 / 13，**可能连同 14**）都被同一机制打上作废标记；**在修好之后重新走一遍「上传 → 处理 → VALID」，是最干净的验证方式**——这也是 Apple 处理这类问题的固定话术。 |

**对我们的实际影响**：① 先只读验合同，恢复了就不必非得传新的（**构建 14 若仍有效可直接送审**）；② 合同没恢复，传新构建确实治不好，但**按对方口径做一次、并把结果回给他们**，是把 Case 往前推的最省事方式（比反复打电话更有效）；③ 无论哪种情况，**48 小时这个等待都是真实存在的**，急不来。

### 1.11 🔁 只读复核（2026-10-03 01:30 —— 手机内测恢复可用，合同仍未重建）

**起因**：用户反馈「手机 TestFlight 自动更新了」。**这不等于合同修好**——本轮把「装得上」和「合同恢复」这两件事彻底分开，并给下次留一条判据（AI 经 ASC API 实测，全程 `GET`，无任何写操作）。

| 项 | 实测值 | 判读 |
|:--|:--|:--|
| `betaLicenseAgreement.agreementText` | **仍 `null`** | ★ **合同仍未重建**（唯一判据，§6 步骤 1） |
| 构建 14 · 内测态 | **`internalBuildState = IN_BETA_TESTING`** · `expired=false` · 到期 2026-12-28 | **内测可下载安装**——用户手机自动更新的就是它（真机实证） |
| 构建 14 · 外测态 | `externalBuildState = READY_FOR_BETA_SUBMISSION` · `betaAppReviewSubmission = data: null` | **外测仍堵**：从未成功送审，外测员拿不到任何构建 |
| 构建 1–13 | 全 `expired=true`（内测/外测双 `EXPIRED`；构建 13 内测态亦已是 `EXPIRED`） | 未恢复 |
| 外测组「阿呆外测」 | 关联构建 9–14（**14 已在组内**） | 组与配置无问题，**只差送审** |
| 构建 14 存活时长 | 09-29 23:32 上传 → 本次复核 **≈3.1 天**（对比：构建 13 ≈**15 小时** ✅；构建 12 旧记「≈8 小时」是**把 `uploadedDate` 当 UTC 读**所致——按其带 `-07:00` 偏移重算，**实际 ≈1 小时 17 分**） | 仍是**唯一有效筹码**，但不保证不被吞 |

**本轮新增的判据（防下次误判）**：

1. **内测 ≠ 外测**：合同缺失挡的是「提交 Beta App Review」与外测分发；**内测下载当前是通的**（构建 14 已被 TestFlight 自动更新到真机）。故——
2. **「手机装上了新构建」不能当作合同恢复的证据**；判据永远只有 `agreementText`（§6 步骤 1）。
3. 09-29 构建 13「看得到、下不动」当时同样是 `VALID` + `expired=false`，其内测态现已无从回溯（`EXPIRED`）——**该次失败原因不明，不与本轮结论混用**。

**构建 14 的能力边界（用户须知）**：构建 14 ＝ **09-29 23:34 的代码**，落后本地 App 侧两批——`bc0449c8`（对话模式上下文工程批 1）· `139a7076`（D4 路由口径），二者**均未上手机**。→ 若到点仍需传新构建（合同未恢复），**这两批正好构成构建 15 的内容，不算白费构建号**（§6 步骤 3）。

**到点动作（承接 §1.9，只补一条前置）**：**第 0 步：不要被「手机能自动更新」误导** → 仍按 §1.9 顺序执行：先只读验 `agreementText` → 已恢复则对构建 14 送审（不必再传）→ 仍为 `null` 则按官方口径传**一次**构建 15（含上述两批）并回信 Case `102980309269`。

### 1.12 ✅ 到点复核（2026-10-03 16:06 —— 48h 到期后，合同仍未重建；用户决定「什么都不传」）

**触发**：Apple 回信口径的 **48 小时**窗口（自 2026-10-01 下午回信 / 电话确认起算）→ 到点 ≈ **10-03（周六）15:25**；实际复核 **16:06**（过点约 40 分钟）。

**实测（全程 `GET`，无任何写操作）**：

| 检查项 | 结果 | 判读 |
|:--|:--|:--|
| `GET /v1/apps/6812370456/betaLicenseAgreement` → `agreementText` | **仍 `null`** | **合同未重建**（第 4 次复核：09-29 / 09-30 / 10-01 / 10-03 四次一致）——**唯一判据** |
| 构建 **14** | `VALID` · `expired=false` · 到期 **2026-12-28** | **唯一可用筹码仍在**，未被同一机制吞掉 |
| 构建 1~13 | 全 `expired=true` | 不可安装 |

**决定（用户，2026-10-03 16:1x）**：**什么都不传**——不传构建 15、不送审，**等 Apple 自行修复**。

**依据**：① 合同 `agreementText` 仍为 `null` 时传新构建已被**构建 13 / 14 两次实证**「送审仍 422」，第三次大概率同形；② 当日为**周六**，Apple 开发者支持不在班（工作日 9:00–17:00，周一 9:30 后可电话），传了也无处催单；③ §1.8 的「**不要为刷可见反复递增构建号**」仍然适用。

**下次动作（顺序不变）**：任一时点先只读复核 `agreementText` → **已恢复**：对构建 **14** 直接 `--assign-build` + `--submit-review`（**不必再传**，§6 步骤 2）→ **仍为 `null`**：按 §6 步骤 3 传**一次**构建 **15**（正好含本地未上手机的两批：`bc0449c8` 上下文工程批 1 · `139a7076` D4 路由口径）并回信 Case `102980309269`；**若届时构建 14 已被吞掉**，传 15 成为必经路径。

**AI 边界**：本次未构建、未上传、未送审、未发布——全部为只读 `GET`。

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
- we then uploaded a NEW build (build 13, Delivery UUID f6cde1d3-1981-4b6e-8c6d-ca7f4546e407) as a test:
  it processed to VALID with a normal expiry (expired = false, expirationDate 2026-12-27), yet
    * Beta App Review submission still returns exactly the same 422, and
    * on the device, TestFlight lists "1.0.0 (13)" but tapping Update fails to download.
- our account's regulatory and agreement side is now fully clean: the EU Digital Services Act (DSA)
  compliance has just been completed (App Store Connect -> Business: "you have met all regulatory
  requirements"), and all agreements show as active. The problem persists unchanged.
- we also re-submitted the TestFlight test information and assigned build 13 to the external group
  (both HTTP 200) — the Beta contract is still missing and submission still returns 422.
- we uploaded yet another build (build 14, Delivery UUID 932bf0d1-2f2e-44cd-aee0-be9955d2db92):
  it is VALID with expired = false (expirationDate 2026-12-28), yet submission still returns 422.
  Builds 13 and 14 are both assigned to the external group and waiting.

What we already verified on our side
- Our automation only performs: build, export, altool upload, status query, assign-build, submit-review.
  It contains no action that expires or removes builds.
- Re-uploading is now empirically confirmed useless (builds 13 and 14 above): visible in TestFlight,
  VALID, not expired — yet not downloadable and still 422. Please do not suggest another re-upload.

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
7. 事故后我方实测新传了构建 13（Delivery UUID f6cde1d3-1981-4b6e-8c6d-ca7f4546e407）：
   它能处理为 VALID、expired=false（到期 2026-12-27），但送审仍返回完全相同的 422；
   并且在 iPhone 的 TestFlight 里**能看到「1.0.0 (13)」但点「更新」无法下载**。
8. 我方账号的监管与协议侧**已完全合规**：刚完成欧盟《数字服务法》(DSA) 合规
   （App Store Connect → 商务页，界面提示「你目前已完成所有监管要求」），且所有协议均显示有效；
   另外重新提交了 TestFlight 测试信息、并把构建 13 加入外部测试组（均返回 200）。
   **在上述状态下问题依旧**（合同仍缺失、送审仍 422）。
9. 我方又上传了构建 14（Delivery UUID 932bf0d1-2f2e-44cd-aee0-be9955d2db92）：
   同样 VALID、expired=false（到期 2026-12-28），送审仍返回同样的 422；
   构建 **13、14 均已加入外部测试组待命**。请不要再建议重传新构建。

我方已排除
- 我方脚本只做：构建、导出、altool 上传、状态查询、分配测试组、提交审核；不含任何「移除/置过期构建」动作。
- 重新上传已实测无用（构建 13：可见、VALID、未过期，但下不动、送审仍 422）——请不要再建议重传。

请求
1. 请在后台修复/重建该 App 的 Beta 合同，使 TestFlight 恢复可用；
2. 如有可能，请恢复现有构建的有效期；否则请确认新上传的构建可用；
3. 请告知根因是否在账号/App 侧，以便我方彻底排除自身因素。

已附复现 422 的截图与录屏。
```

### 3.3 电话话术（30 秒版）

> 「你好，我是开发者，Team ID `4G3D37YKSB`，App 叫阿呆阿呆（Apple ID 6812370456）。9 月 28 日凌晨，我全部的 12 个 TestFlight 构建在同一秒被置为过期，现在内测外测都装不了，提交审核返回 422，错误是 `Beta contract is missing for the app`。**我今天还专门重传了一个新构建 13，TestFlight 里能看到，但点更新下不动，送审还是一样 422**——所以不是我们构建或上传的问题。我这边脚本和构建产物都没问题，构建状态都是 VALID、新构建也没过期。社区里同类问题都是后台修复的，能不能帮我转一下 TestFlight 相关的支持，或者帮我开一个工单？」

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

### 4.4 构建 13 实测原始证据（2026-09-29）

| 环节 | 证据 |
|:--|:--|
| Delivery UUID | `f6cde1d3-1981-4b6e-8c6d-ca7f4546e407`（23,778,657 B / 40.8 秒）|
| 上传完成 | 2026-09-29 **09:46:41**（北京）|
| 终态 | 09:49:03 `VALID` · **`expired=false`** · `expirationDate=2026-12-27T17:47:30-08:00` |
| 送审探针 | `POST /v1/betaAppReviewSubmissions` → **HTTP 422** `ENTITY_UNPROCESSABLE.BETA_CONTRACT_MISSING`（id `27d1f2a0-b444-4fb5-9e42-391cb4f5b7ac`）|
| 客户端 | iPhone TestFlight **可见「1.0.0 (13)」，点「更新」无法下载**（用户 2026-09-29 实测）|

> **这组数据是工单的核心弹药**：新构建**不再被作废**，却**依然不可下载、不可送审** → 一次性排除了「构建产物 / 构建号 / 上传方式 / 测试组配置」的全部可能，把根因唯一地指向 Apple 侧的 Beta 合同。

## 5. 本机复核方法（只读）

```bash
# 官方脚本（需临时 shim，见下方待办 1）
cd apps/adai-app && PYTHONPATH=/tmp/jwtshim python3 .agents/scripts/testflight_status.py

# 2026-09-29 实际使用的零安装探针（临时文件，不入库）：
#   /tmp/asc_probe.py        —— 只读复核（构建列表 / betaLicenseAgreement / 测试组 / 审核信息）
#   /tmp/asc_submit_probe.py —— 送审探针（对最新构建 POST 一次，判合同是否恢复）
#   /tmp/asc_v13_watch.py    —— 盯构建 13 终态 + 自动送审
#   均用 Cryptodome 直签 ES256 JWT；★ Apple 要求 exp-iat ≤ 1200 秒（写 1800 会直接 401）
python3 /tmp/asc_probe.py
```

> ⚠️ **两个脚本缺陷（均未修，2026-09-29 实测暴露）**：
> 1. **缺依赖 → 整条发布链跑不起来**：`asc_signing.py` / `testflight_external.py` / `testflight_status.py` **三个都 `import jwt`**，而本机 `python3`(3.9.6) 与 `python3.13` 都**没装 PyJWT**。本次用 `/tmp/jwtshim/jwt.py`（Cryptodome 直签 ES256）临时注入 `PYTHONPATH` 打通，**仓库脚本一字未改**。正式修法：装 `pyjwt`，或把 JWT 签名改为零依赖实现（Cryptodome / `openssl`）。
> 2. **误报「可测试」（更危险）**：`testflight_status.py` 只看 `processingState`、**不看 `expired`** → 对已被集体作废的构建仍打印「✅ 可测试 —— 去 TestFlight 就能装了」（2026-09-29 实测两次误导）。应读 `expired`，为 true 时输出「❌ 已被 Apple 作废」并置退出码 1。

## 6. 修复后的动作（Apple 回复或恢复后）

> ★ **2026-10-01 更新**：Apple 已于 10-01 回信（Case `102980309269`：已提交案例，**请在 48 小时后上传新构建再试**）——**这句话不等于已修复**：同日 15:14 实测 `agreementText` **仍 `null`**。**到点先做第 1 步复核，不要直接传构建**，详见 §1.9。
> ★ **2026-10-03 更新**：手机 TestFlight **已自动更新到构建 14（内测可装）**，但 `agreementText` **仍 `null`**、构建 14 **外测态仍是 `READY_FOR_BETA_SUBMISSION`（从未送审）** → **「装得上」≠「合同好了」**，第 1 步复核的口径不变，详见 §1.11。

1. **先复核**：确认 `agreementText != null`（合同已重建）——**不要只看到 `expired=false` 就开始动手**（§1.8：`expired=false` 只是暂态）；
2. **优先用现成构建**：构建 **14** 已在外测组、`expired=false`（到期 2026-12-28；**2026-10-01 15:14 复核时仍有效**）→ 直接 `testflight_external.py --assign-build` + `--submit-review`，**不必再传**（构建 13 已于 09-30 00:41 被作废）；
3. 仅当构建 14 也被作废时才重新构建上传：`cd apps/adai-app && sh .agents/scripts/release_testflight.sh --build-number 15`（构建号必须递增）→ `--status` 等 VALID → `--assign-build`（外测组）+ `--submit-review`；
4. **登记闭环**：把 `REVIEW.md` **P1-发布1** 标记为已修（附日期与 Apple 侧凭据），同步 `status.md` TestFlight 段；
5. 社区反复强调：**在 Apple 修好前不要靠反复递增构建号「刷可见」**——白费构建号且仍装不上。

## 7. 外部依据

- [Apple 论坛 thread 848236 — BETA_CONTRACT_MISSING，无构建可安装](https://developer.apple.com/forums/thread/848236)（同名主帖）
- [Apple 论坛 thread 822027 — "Beta app contract is missing" + 422，所有 App 受影响](https://developer.apple.com/forums/thread/822027)
- [Apple 论坛 thread 814565 — TestFlight Beta Contract Missing](https://developer.apple.com/forums/thread/814565)
- [Stack Overflow 69822285 — 已采纳答案：联系苹果开发者支持，约一周](https://stackoverflow.com/questions/69822285/app-store-connect-submit-for-review-error-entity-unprocessable-beta-contract-mi)
- [CSDN 案例 — 走「联系我们 → App 设置和分发 → TestFlight」，提交截图+录屏，约 5 天后恢复](https://blog.csdn.net/woashizhangsi/article/details/132376323)
- [fastlane #29673 — 跨 4 个 App、同一 2 秒窗口过期，Apple 确认需后台反向修复](https://github.com/fastlane/fastlane/issues/29673)
