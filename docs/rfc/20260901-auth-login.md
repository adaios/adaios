---
title: 用户认证登录体系——根治 X-User-Id 零鉴权（REVIEW #179）
description: 账号密码登录 + 服务端会话，userId 由会话推导、客户端伪造无效；Controller 层 92 处 X-User-Id 读取零改动（拦截器强制覆盖 header）
date: 2026-09-01
status: implemented
decided-by: 用户（2026-09-01 拍板：全部决策点按推荐 + 一次性实施完）
tags: [security, auth, 登录, 鉴权, 会话]
---

# 用户认证登录体系——根治 X-User-Id 零鉴权

> **问题（P0，2026-09-01 域名上线后实测确认裸奔）**：REVIEW #179「用户层 X-User-Id 零鉴权」——全部产品端点只凭 `X-User-Id` header 认人，而 `adai` 这个值在 README/部署文档/测试代码里公开。公网实测：零凭证即可读取真实持仓、Feed、个人档案、记忆，且 POST 写入（伪造记录/篡改交易）同样无拦截。`/api/v1/admin/**` 与 `/api/v1/accounts/**` 有 `X-Admin-Token` 保护（未受影响；当时现状，2026-09-02 #178 后并入统一登录）。
>
> **本 RFC 定义根治方案**：账号密码登录 + 服务端会话，userId 由会话推导，客户端伪造 header 无效。核心设计约束：**Controller 层 92 处 `@RequestHeader("X-User-Id")` 一行不改**——通过拦截器 + RequestWrapper 强制覆盖，把风险面收敛到新增的鉴权层，便于测试与回滚。

> **2026-09-02 更新（REVIEW #178）**：本 RFC 列为「二期可选」的 admin 管理台并入统一登录已落地——`/admin/**`、`/accounts/**`（例外 `/accounts/available` 仅需登录）统一走 Bearer 会话 + role=admin 门禁（admin 会话保留 X-User-Id 供跨账号治理浏览）；`AdminAuthInterceptor` / `X-Admin-Token` / `ADAI_ADMIN_TOKEN` 全部退役删除；adai-admin 改账号密码登录（登录页 + 401 回登录 + 顶栏改密 + 账号页重置密码 + 建号初始密码）。下文「一期不动 / 二期」表述均为当时决策，已被 #178 落地取代。

---

## 一、现状与暴露面（实测）

| 项 | 现状 | 实测 |
|:---|:-----|:-----|
| 产品端点鉴权 | 无，仅 X-User-Id header 隔离 | 伪造 `X-User-Id: adai` 读持仓/Feed/档案全部 200 |
| 写入端点 | 同无鉴权 | POST /records 仅参数校验（400），非鉴权（403）|
| admin 端点 | X-Admin-Token（fail-closed，常量时间比较；2026-09-02 #178 后退役并入统一登录）| ✅ 安全 |
| 账号体系 | accounts.json（userId/role/enabled/plugins），admin 后台建号，无注册 | `GET /accounts/available` 无鉴权返回 userId 列表 |
| userId 来源 | 19 个 Controller × 92 处 `@RequestHeader`，默认值 `default` | 前端（web: URL ?userId= / app: 选号）直接传 |

## 二、目标与边界

- **目标**：产品端（web/app）数据只能由「登录成功的本人账号」读写；伪造 X-User-Id 一律 401。
- **非目标（本批次不做）**：admin 管理台并入统一登录（保持 X-Admin-Token，二期可选——**2026-09-02 #178 已落地**）；第三方 OAuth；多因子。
- **安全模型**：HTTPS（已就绪）+ 密码 bcrypt + 会话 token 服务端校验 + 登录限流。Web 前端 token 存 localStorage 的 XSS 风险接受（个人站点无第三方脚本；根治需 HttpOnly cookie + CSRF 治理，二期评估）。

## 三、方案设计

### 3.1 认证与会话

| 项 | 设计 |
|:---|:-----|
| 密码存储 | bcrypt（spring-security-crypto `BCryptPasswordEncoder`，cost 10，零新依赖）|
| 会话 token | 登录成功签发 32 字节随机 hex；**落盘只存 SHA-256 哈希**（防 sessions.json 泄露直接可用）|
| 会话存储 | File First：`data/accounts/sessions.json`（`{tokenHash: {userId, createdAt, lastSeenAt, expiresAt}}`），多会话天然支持（多设备同时在线）|
| 有效期 | 30 天滑动续期（每次请求刷新 expiresAt；活跃即不过期，个人使用免频繁重登）|
| 登出 | DELETE 会话；`POST /auth/logout` |
| 登录限流 | 内存按 (IP+account) 计数：连续 5 次失败锁 15 分钟（防爆破；重启清零可接受）|

### 3.2 鉴权层（Controller 零改动）

```
AuthInterceptor（新，仿 AdminAuthInterceptor 注册方式）
  Authorization: Bearer <token> → SHA-256 → 查 sessions.json
  ├─ 无效/缺失/过期 → 401（fail-closed）
  └─ 有效 → RequestWrapper 强制重写 X-User-Id = 会话 userId → 放行
```

- 92 处 `@RequestHeader("X-User-Id")` **一行不改**：客户端传什么都被覆盖为会话 userId。
- 免鉴权白名单（WebConfig 注册时 exclude）：`POST /api/v1/auth/login`、`POST /api/v1/auth/setup`、`OPTIONS`（CORS preflight 由现有 CorsFilter 处理）。
- `GET /api/v1/accounts/available`（选号用）**从免鉴权移除**：登录页即账号页（输入账号名+密码），无需公开枚举 userId。如保留会继续泄露账号枚举面。

### 3.3 数据模型

- `Account` record 加字段 `passwordHash`（String，可空）：老 JSON 无此字段 → null = 未设密码；紧凑构造器归一（沿用现模式）。
- 新 `SessionRepository`（kernel/account 或新 kernel/auth）：sessions.json 原子读写（沿用现有文件仓储的原子 RMW + per-user/全局锁模式）。
- `data/accounts/` 属 data/ 隐私目录（已 gitignore），无需额外处理。

### 3.4 端点总表（新增 5 个）

| 方法 | 路径 | 鉴权 | 说明 |
|:----|:-----|:----|:-----|
| POST | `/api/v1/auth/login` | 免 | `{account, password}` → `{token, userId, role, plugins, expiresAt}`；失败 401 + 限流 |
| POST | `/api/v1/auth/logout` | 会话 | 注销当前会话 |
| GET | `/api/v1/auth/me` | 会话 | 当前会话信息（userId/role/plugins），前端启动校验 token 有效性 |
| POST | `/api/v1/auth/setup` | 免（一次性）| 首访引导：仅当**全系统无任何账号设过密码**时可用一次，为指定账号（默认 adai）设密码；之后 404 |
| POST | `/api/v1/auth/password` | 会话 | 改密：`{oldPassword, newPassword}`（bcrypt 重哈希 + 踢掉其他会话，保留当前）|

### 3.5 前端改动

| 端 | 改动 |
|:---|:-----|
| adai-web | 新增登录页（无 token 时强制跳转；首访检测 setup）；token 存 localStorage；`ApiService` 统一带 `Authorization: Bearer`；401 全局拦截 → 清 token 回登录页；`main.dart` 的 `?userId=` 移除（userId 来自会话）；改密入口 |
| adai-app | 登录页（账号+密码，替代选号）；token 存 shared_preferences；请求带 Bearer；401 → 登录页；setup 引导（iOS 首装）|
| adai-admin | **一期不动**（保持 X-Admin-Token）；二期可选并入统一登录（**2026-09-02 #178 落地：登录页账号密码 + role=admin 门禁，X-Admin-Token 退役**）|

### 3.6 迁移与上线顺序

1. **后端批次**：AuthController + AuthInterceptor + SessionRepository + Account.passwordHash + 限流 + 测试（核心回归：无 token 401 / 伪造 X-User-Id 被覆盖 / 登录注销 / setup 一次性 / 限流锁 / 会话续期 / 改密踢会话）→ 部署 + `curl setup` 为 adai 设密码。
2. **web 批次**：登录页 + token 接入 + 401 处理 → 构建部署。
3. **app 批次**：登录页 + token → 随下次重签（7 天周期）发布。
4. REVIEW #179 关闭；REVIEW P1-A4（deploy-gate smoke 零鉴权验证）同步改为带 token 验证。
5. 上线后旧 IP 直连（:8080 公网）已由安全组/域名收敛，若仍暴露需收紧。

### 3.7 回滚

- 后端：AuthInterceptor 可配置开关（`adai.security.auth-enabled`，默认 true）；紧急关停 = 恢复 X-User-Id 直通（回旧行为，但仅用于故障回滚，数据在公网裸奔风险重现）。
- 前端：保留 X-User-Id 发送逻辑兜底版本（灰度切换用）。

## 四、关键决策点（待用户拍板）

| # | 决策点 | 推荐默认 |
|:--|:-------|:---------|
| 1 | 密码初始化 | `POST /auth/setup` 一次性端点（首访引导设 adai 密码），不搞服务器命令 |
| 2 | 会话有效期 | 30 天滑动续期（活跃不过期）|
| 3 | admin 管理台 | 一期保持 X-Admin-Token，二期并入统一登录（**2026-09-02 #178 已按二期落地**）|
| 4 | `/accounts/available` | 从免鉴权移除（登录页手输账号名），封死 userId 枚举 |
| 5 | 登录限流 | 5 次失败锁 15 分钟（按 IP+账号）|

## 五、工作量预估

| 项 | 新增/改动 | 测试 |
|:---|:----------|:-----|
| 后端 | AuthController、AuthInterceptor(+RequestWrapper)、SessionRepository、Account+passwordHash、LoginRateLimiter、WebConfig 注册 | ~20-25 个（Controller 契约 + 拦截器矩阵 + 仓储）|
| web | 登录页、ApiService token/401、setup 引导、main.dart userId 移除 | ~8-10 个 |
| app | 登录页、token 存储、401 处理 | ~5-8 个 |

## 六、遗留风险（接受或二期）

- Web token 存 localStorage：XSS 可窃取（无第三方脚本，接受）；HttpOnly Cookie 方案二期评估。
- 登录页本身可被爆破：限流兜底（5 次/15 分钟），无验证码（个人站点接受）。
- `sessions.json` 明文哈希 token：文件泄露需 SHA-256 原像攻击才可用（已接受）。
- 老版本 app/web（未带 token）在切换窗口期 401：与重签/部署节奏对齐即可。
