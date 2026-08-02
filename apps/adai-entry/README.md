# adai-entry — AdaiOS 账号选择入口（产品「前门」）

「登录=选择」落地：拉取后端账号列表（enabled），点选后按角色分流：

| 角色 | 跳转目标 |
|:-----|:---------|
| admin | adai-admin（`:8083/?userId=xxx`）|
| 普通用户 | adai-app（`:8081/?userId=xxx`）|

跨端口浏览器 localStorage 不共享，故选号用 query 参数 `?userId=` 传递，
目标端（adai-app / adai-admin）读取后注入 `X-User-Id` header 请求后端。

## 运行

```bash
# 后端 8080 需运行（GET /api/v1/accounts）
cd services/adai-core && ./gradlew bootRun

# 构建 + 启动（:8082）
cd apps/adai-entry && sh scripts/serve_web.sh
```

## 配置（--dart-define）

| 变量 | 默认 | 说明 |
|:-----|:-----|:-----|
| `API_BASE_URL` | `http://localhost:8080` | 后端地址 |
| `APP_URL` | `http://localhost:8081` | adai-app 地址 |
| `ADMIN_URL` | `http://localhost:8083` | adai-admin 地址 |

## 测试

```bash
flutter test && flutter analyze
```

> 重定向用 `@JS` 绑定 `window.location.assign`（`lib/web_redirect*.dart` 条件导入）：
> 不用 `package:web`（其 helpers/http.dart 与当前 SDK 的 js_interop 不兼容），
> `dart:html` 已弃用。测试注入 `onNavigate` 走 VM stub，不触真跳转。
