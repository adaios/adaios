---
title: TestFlight 外部测试（邀请外人使用）
description: 把阿呆通过 TestFlight 发给非团队成员使用的完整流程——内测/外测怎么选、Beta 审核备注模板（含测试账号说明）、可直接转发的测试员须知、邀请与续期
version: 2
created: 2026-09-17
updated: 2026-09-21
status: active
lines: 186
depends-on:
  - ios-release.md
related:
  - ../reference/status.md
  - ../guides/routine.md
tags: [deployment, ios, testflight]
---

# TestFlight 外部测试（邀请外人使用）

> **分工**：`ios-release.md` 管「怎么把包传上去」（签名/构建/上传/查状态），本文管「怎么把它发给别人用」。
> **一句话结论**：邀请邮箱可以随便填，**但对方必须有 Apple ID**；给外人用走**外部测试**，首次要过苹果 Beta 审核。

## 1. 先选路：内部测试 vs 外部测试

| | 内部测试（Internal） | 外部测试（External） |
|:---|:---|:---|
| 人数 | ≤ 100 | ≤ 10000 |
| 谁能加 | 只能是 **App Store Connect 团队成员**（必须 Apple ID 邮箱） | 任意人，**邮箱能收信即可** |
| Beta 审核 | **不用**，构建处理好就能邀 | **首次必须过 Beta App Review**（24~48h） |
| 给外人用 | ❌ 等于给人开后台权限，别这么干 | ✅ 正确做法 |

> 结论：给自己/极信任的人 → 内部测试；**给朋友、用户、群里的人 → 外部测试**。

## 2. 前置准备（只做一次）

| # | 事项 | 状态（2026-09-21 核查） |
|:--|:-----|:---|
| 1 | App Store Connect 有 App 记录 | ✅ `6812370456`（阿呆阿呆 / `com.adaiadai.adaiApp`） |
| 2 | **隐私政策 URL**（外测「测试信息」必填） | ✅ **`https://adaiadai.com/privacy`（2026-09-21 已发布，公网 200）**——正文 `docs/legal/privacy-policy.md` + 页面 `apps/adai-app/web/privacy.html`（Caddy `handle /privacy`）；已填进 TestFlight 测试信息与 App Store 资料 |
| 3 | 出口合规 | ✅ 已由 `ITSAppUsesNonExemptEncryption=false` 固化，不再卡 processing |
| 4 | 专用测试账号 `applereview` | ✅ 已建（`role=user` · `plugins=['learn']` · 已设密码，见 3.0）；**2026-09-21 复核：生产 `data/applereview/` 有实际数据 = 确已登录过，密码可用**；密码**不入库** |
| 5 | 构建已上传且状态 **VALID** | ✅ **1.0.0+9**（2026-09-19 上传，过期 2026-12-18）；用 `python3 apps/adai-app/scripts/testflight_status.py` 查 |
| 6 | 外部测试组 | ✅ **「阿呆外测」（2026-09-21 建，公开链接关）** |
| 7 | 审核联系人 + 测试账号（苹果必填，不公开） | ✅ 已填（Kangda Wang / `+8618610915614` / rottokaka@gmail.com，账号 `applereview`） |
| 8 | 提交 Beta 审核 + 构建入组 | ✅ **构建 9 于 2026-09-21 23:20 提交 → 2026-09-22 20:43 实测 `APPROVED`**（首次外测审核约 21 小时通过）；构建已加入外部组 |

> **当前状态（2026-09-22）：外测通道已打通，可以发邮箱邀请了。**
> 剩余动作：① 给每位外部测试员建独立账号（admin 后台）② `--invite` 发邮箱邀请
> ③ 把 §5「测试员须知」转发给他们。
> ⚠️ 测试账号 `applereview` 的密码在审核期间不可改；**审核已过 → 现在可以改强**
> （改完用 `--review-info --demo-password` 同步苹果侧字段，免得日后重新审核时审核员登不进去）。

## 3. 审核备注要写什么（最容易被拒的一步）

苹果审核员必须能**独立登录并把功能走通**。阿呆是登录制 App，因此：

- **必须提供测试账号 + 密码**，否则大概率被拒（理由：无法评估功能）；
- **不要把主账号密码写进本仓库**——备注正文里用占位符，提交时再从密码管理器粘贴；
- **已采用专用账号**（2026-09-17 决定）：`applereview`，与自己的数据隔离——审核员看不到 `adai` 账号下的真实数据。

### 3.0 专用账号 `applereview` 怎么建（adai-admin，人操作一次）

建号端点只认 **role=admin** 的会话，所以必须用 `admin` 账号进后台（**不是** `adai`）：

1. 打开 `https://adaiadai.com/admin/`，用 **admin** 账号登录
2. 进「账号」页 → 右上「**+ 新建**」展开表单
3. `账号 ID` 填 **applereview** · `初始密码` 填 ≥8 位强密码 · 角色保持「**普通用户**」（不要给管理员）
4. 点「创建账号」→ 顶部出现「已创建账号」
5. 在这张新账号卡上，把「插件」里的 **学习** 打开（`applereview` 2026-09-17 实际就是只开了学习）。
   **交易**属可选：开了审核员能多看到一个模块（新账号下是空态），不开更简洁——
   但**开了什么就得在 3.1 里写什么**，别让审核员照着「测试内容」找不到入口。

> **提交审核前必须自查一次**：用 applereview 登录 `https://adaiadai.com`，能进主页、能看到学习入口即可。
> （2026-09-21 复核：生产 `data/applereview/` 下已有实际数据（09-17 创建、09-20 仍有写入）——
> **账号确已登录过、密码可用**，不再是 09-17 时「目录尚未生成 = 从未登录」的状态。）
> 密码填进审核备注（3.2），**不要写进本仓库**。

### 3.1 「测试内容」（What to Test）模板

```
本版重点验证三条主链路：
1. 登录：用下方测试账号登录（冷启动若出现生物识别提示，可直接取消）。
2. 记录与问答（核心链路）：在主页输入任意一句话（如「今天心情不错」），
   观察阿呆的回复与卡片生成。
3. 学习模块：进入学习页查看卡片列表与详情（新账号为空态属正常）。

说明：本 App 为个人 AI 助手，所有数据属于该测试账号，无社交与支付。
```

### 3.2 「审核备注」（App Review Information）模板

> 提交时把 `<...>` 换成真实值（**密码不要落盘**）。

```
测试账号 / Test account
  用户名 Username: <账号名>
  密码   Password: <密码>

登录路径：启动 App → 登录页输入上述账号密码 → 进入主页。
无短信验证码 / 无二次验证（No OTP, no 2FA）。
启动时若出现 Face ID / 生物识别提示，可直接取消，不影响登录。
App 需要 iPhone；主要功能为个人记录与 AI 对话，所有数据属于该测试账号，
不涉及用户生成内容的公开传播。

English summary:
  Test account: <username> / <password>. Launch the app, sign in on the
  login screen. No OTP required. A Face ID prompt may appear on launch and
  can be safely cancelled. The app is a personal AI assistant for a single
  test account; there is no social or payment functionality.
```

## 4. 外测流程（六步）

1. **上传构建**：`sh apps/adai-app/scripts/release_testflight.sh --build-number N`（构建号必须递增，重复会被拒）
2. **等状态 VALID**：`python3 apps/adai-app/scripts/testflight_status.py --wait`
3. **建外部测试组**：App Store Connect → 我的 App → TestFlight → 外部测试 → ＋
4. **填「测试信息」**：测试内容（3.1）+ 审核备注（3.2）+ 隐私政策 URL + 联系人；勾选构建 → **提交审核**
5. **等 Beta 审核**：首次 24~48h（最长 72h）；**已上架过的 App 后续构建免审核**；同版本后续构建通常只走快速检查
6. **邀请测试员**：审核通过后
   - **邮箱邀请**：填邮箱 → 发送（邮箱不要求是 Apple ID，但对方要用 Apple ID 登录 TestFlight）
   - **公开链接**：生成后谁都能加，**上限 10000，不想外流就别开**

### 4.1 一条命令做完（`testflight_external.py`，2026-09-21 新增）

`apps/adai-app/scripts/testflight_external.py` 把第 3~6 步的网页点击封成命令
（凭据口径同 `testflight_status.py`，自动发现 `~/.appstoreconnect/`；
**测试账号密码只经 `ASC_DEMO_PASSWORD` 环境变量传入，不落盘、不打印**）：

| 命令 | 作用 |
|:--|:--|
| `--status` | 只读现状：外部组 / 测试员 / 审核信息填了没 / 审核状态 / 构建（必填项为空显式打 ❌，**提交前自查推荐入口**） |
| `--fill` | 填测试信息：隐私政策 URL（TestFlight 测试信息 + App Store 资料两处）+ 反馈邮箱 + Beta App 描述 + What to Test |
| `--review-info` | 填审核信息：联系人（`--contact-first/last/phone/email`）+ 测试账号（`--demo-account`） |
| `--submit-review` | 提交 Beta App Review（自动取最新 VALID 构建；已提交过的不重复提交） |
| `--invite a@b.com …` | 邮箱邀请（账号里已有的测试员直接加入外部组，不重复建号） |

```bash
cd apps/adai-app
python3 scripts/testflight_external.py --status
ASC_DEMO_PASSWORD='…' python3 scripts/testflight_external.py --submit-review
python3 scripts/testflight_external.py --invite friend@example.com
```

## 5. 测试员须知（可直接复制转发）

```
【阿呆 TestFlight 测试邀请】

你需要：一台 iPhone + 一个 Apple ID（就是平时在 App Store 下 App 的那个账号）

四步：
1. 在 App Store 搜索并安装「TestFlight」
2. 打开 TestFlight，用你的 Apple ID 登录
3. 打开我发给你的 TestFlight 邀请邮件，点里面的「View in TestFlight / 兑换」
   （也可以把邮件里的邀请码填进 TestFlight）
4. 安装「阿呆阿呆」，用我给你的测试账号登录即可

几个提醒：
· 这是测试版，功能在改，遇到闪退/卡住请直接在 TestFlight 里截图反馈
· 测试版有 90 天有效期，到期后需要我发新版本才能继续用
· 图标名前的黄点是 TestFlight 的正常标记，不是出错
· 只支持 iPhone / iPad，安卓手机用不了
```

## 6. 邀请之后：续期与维护

- **构建 90 天过期**（到期所有测试员都打不开）→ 上传新构建加入同一测试组即可，**不用重新邀请**
- 到期红线已登记在 `docs/guides/routine.md`（由 `scripts/check_deadlines.py` 提前 30 天告警）
- 同一个外部测试组可长期复用；换了 Bundle ID / 能力才需要重建组

## 7. 常见被拒原因与处置

| 被拒原因 | 处置 |
|:---|:---|
| 没给测试账号 / 账号登不进去 | 按第 3 节补齐，**自查一遍能登录再提交** |
| 隐私政策 URL 打不开或与 App 不符 | 换可公开访问的页面（第 2 节第 2 项） |
| 构建崩溃或明显未完成 | 先本机冒烟（登录 / Feed / 交易 / 学习）再提交 |
| 描述含价格、"即将推出"等承诺性语言 | 改成描述当前功能 |
| 要求用户跳出 App 完成核心功能 | 说明最短完整路径，或改掉该引导 |

> 被拒不影响正式 App；去 App Store Connect 的 Resolution Center 看原因，改完重新提交即可。

## 8. 相关

- 上传与签名细节、故障排查：`docs/deployment/ios-release.md`
- 到期红线与例行动作：`docs/guides/routine.md`
- 构建与描述文件到期时间：`docs/reference/status.md`
