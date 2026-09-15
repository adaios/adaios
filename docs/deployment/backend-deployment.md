# AdaiOS 后端服务部署方案

## 1. 环境信息

| 项目 | 值 |
|------|-----|
| 服务器 OS | **Ubuntu 24.04 LTS**（2026-08-19 从 CentOS 8.5 迁移，旧服务器 49.235.37.220 到期下线）|
| 服务器配置 | 2核4G · 70G SSD · 600G 流量（腾讯云轻量，北京）|
| 服务器 IP | **82.156.111.146** |
| 生产域名 | **adaiadai.com**（2026-09-01 已上线：ICP 备案通过 + DNS + Caddy HTTPS，见 §10）|
| 部署方式 | Caddy 反代（adaiadai.com → 8082/8083，api.adaiadai.com → 8080）|
| 后端端口 | 8080 |
| 前端端口 | 8082（adai-web）/ 8083（adai-admin）|
| 运行方式 | systemd 服务，开机自启 |
| 数据目录 | `/opt/adaios/data`（v1.0.0 起按 `data/{userId}/` 分层）|
| 知识目录 | `/opt/adaios/os`（Domain OS 资产，从 git 仓库同步）|
| 安装目录 | `/opt/adaios/backend` |
| 账号表 | `data/accounts/accounts.json`（多账号）|

## 2. 本地构建

在开发机（Windows）上构建 JAR：

```bash
cd D:\Projects\adaios\services\adai-core
.\gradlew bootJar
```

产物位置：`services\adai-core\build\libs\adai-core-0.0.1-SNAPSHOT.jar`

## 3. 部署步骤

以下操作在 CentOS 服务器上以 root 执行。

### 3.1 安装 Eclipse Temurin 17

选用 Eclipse Temurin（Adoptium）——Spring 官方推荐，生产环境主流选择。

```bash
# 1. 导入 Adoptium GPG key
rpm --import https://packages.adoptium.net/artifactory/api/gpg/key/public

# 2. 添加 Adoptium YUM 仓库
cat > /etc/yum.repos.d/adoptium.repo << 'EOF'
[adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/rhel/$releasever/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF

# 3. 安装 Temurin 17（headless 版本，不带 GUI，省空间）
dnf install -y temurin-17-jdk

# 4. 验证
java -version
# 输出应为：
# openjdk version "17.0.x" YYYY-MM-DD LTS
# OpenJDK Runtime Environment Temurin-17.0.x+9 (build 17.0.x+9)
# OpenJDK 64-Bit Server VM Temurin-17.0.x+9 (build 17.0.x+9, mixed mode, sharing)
```

### 3.2 创建目录和用户

```bash
# 创建专用用户（非 root 运行）
useradd -r -s /sbin/nologin -m -d /opt/adaios adaios

# 创建应用目录
mkdir -p /opt/adaios/backend
mkdir -p /opt/adaios/data

# 设置权限
chown -R adaios:adaios /opt/adaios
```

### 3.3 上传 JAR

从开发机 SCP 到服务器：

```bash
# 在开发机（Windows PowerShell / Git Bash）上执行
scp services/adai-core/build/libs/adai-core-0.0.1-SNAPSHOT.jar root@<服务器IP>:/opt/adaios/backend/adai-core.jar
```

### 3.4 创建环境变量文件

```bash
cat > /opt/adaios/backend/.env << 'EOF'
DEEPSEEK_API_KEY=sk-your-deepseek-api-key-here
ADAI_DATA_DIR=/opt/adaios/data
ADAI_AI_PROVIDER=deepseek

# 管理口鉴权已并入统一登录（REVIEW #178，2026-09-02）：ADAI_ADMIN_TOKEN / X-Admin-Token 退役，无需配置；
# adai-admin 用账号密码登录（会话账号须 role=admin），不再注入任何管理令牌
# CORS 白名单（localhost + 生产服务器 IP；域名就绪后追加 https://adaiadai.com）
ADAI_ALLOWED_ORIGIN_PATTERNS=http://localhost:*,http://127.0.0.1:*,http://82.156.111.146:*
# os/ 知识资产根路径（注意：默认 ../../os 相对 systemd WorkingDirectory 解析为 /opt/os，必须显式配置！）
ADAI_OS_BASE_PATH=/opt/adaios/os
# 交易知识规则目录（AdminController /trading/knowledge 读取）
ADAI_TRADING_KNOWLEDGE_PATH=/opt/adaios/os/trading-engine/knowledge/context
EOF

chown adaios:adaios /opt/adaios/backend/.env
chmod 600 /opt/adaios/backend/.env
```

> ⚠️ 将 `sk-your-deepseek-api-key-here` 替换为真实 DeepSeek API Key。管理口不再需要 `ADAI_ADMIN_TOKEN`（REVIEW #178 已退役：鉴权并入统一登录，adai-admin 用账号密码登录，会话账号须 role=admin）。

### 3.5 创建 systemd 服务

```bash
cat > /etc/systemd/system/adai-core.service << 'EOF'
[Unit]
Description=AdaiOS Backend Service
After=network.target

[Service]
Type=simple
User=adaios
Group=adaios
WorkingDirectory=/opt/adaios/backend
EnvironmentFile=/opt/adaios/backend/.env
ExecStart=/usr/bin/java -jar /opt/adaios/backend/adai-core.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
```

### 3.6 启动服务

```bash
# 重载 systemd
systemctl daemon-reload

# 启用开机自启
systemctl enable adai-core

# 启动服务
systemctl start adai-core

# 查看状态
systemctl status adai-core
```

### 3.7 防火墙开放端口

```bash
# CentOS 默认使用 firewalld
firewall-cmd --add-port=8080/tcp --permanent
firewall-cmd --reload

# 验证
firewall-cmd --list-ports
```

## 4. 常用运维命令

```bash
# 查看服务状态
systemctl status adai-core

# 查看实时日志
journalctl -u adai-core -f

# 查看最近 100 行日志
journalctl -u adai-core -n 100 --no-pager

# 重启服务
systemctl restart adai-core

# 停止服务
systemctl stop adai-core
```

## 5. 部署更新流程

当代码更新后，一键部署：

```bash
# 1. 开发机：重新构建
cd services/adai-core
./gradlew bootJar

# 2. 开发机：一键部署（自动上传 + 重启 + 重建记忆）
./deploy.sh <服务器IP> build/libs/adai-core-0.0.1-SNAPSHOT.jar
# 示例: ./deploy.sh 82.156.111.146 build/libs/adai-core-0.0.1-SNAPSHOT.jar
```

> 脚本自动完成：上传 JAR → 停服务 → 补全 data 目录 → 修权限 → 启服务 → 重建记忆。
> 不再需要手动检查 data 文件完整性。

> ⚠️ **v1.0.0 起（多账号分层）**：升级前需先处理数据迁移——单层 `data/` → `data/{userId}/` + 账号表。见下方「7. 多账号数据迁移（v1.0.0）」。deploy.sh 的目录补全逻辑已按多账号分层（`data/adai/`）。

## 6. 配置说明

| 配置项 | 说明 | 默认值 | 生产值 |
|--------|------|--------|--------|
| `server.port` | 服务端口 | 8080 | 8080 |
| `adai.storage.base-path` | 数据文件存储路径 | `../../data` | `/opt/adaios/data` |
| `adai.ai.provider` | AI 提供商 | `deepseek` | `deepseek` |
| `adai.ai.model` | AI 模型 | `deepseek-v4-pro` | `deepseek-v4-pro` |
| `adai.os-base-path` | os/ 知识资产根路径 | `../../os`（⚠️ 相对 cwd 解析，systemd 下会错）| `/opt/adaios/os`（必须显式 `ADAI_OS_BASE_PATH`）|
| CORS 白名单 | 允许来源 | localhost | `ADAI_ALLOWED_ORIGIN_PATTERNS` |

> **REVIEW #178（2026-09-02）**：`adai.security.admin-token` 配置与 env `ADAI_ADMIN_TOKEN` 已随 `AdminAuthInterceptor` / `X-Admin-Token` 一并退役删除（上表原「管理端点令牌」行移除）——管理口（`/admin/**`、`/accounts/**`）鉴权并入统一登录，adai-admin 用账号密码登录（会话账号须 role=admin），不再配置任何管理令牌。

所有配置在 `.env` 文件中管理，JAR 启动时自动读取。

### 6.1 learn 插件（学习）上线前置（2026-09-12）

| 项 | 要求 | 缺了会怎样 |
|:---|:-----|:-----------|
| `ffmpeg` | 生产服务器需 `sudo apt install -y ffmpeg`（B站音频是 fMP4，必须转 16k 单声道 mp3 才能送云端 ASR）| 无字幕视频走不通，人话提示「服务器上还没装转码工具」；**有字幕视频与文章不受影响** |
| `DASHSCOPE_API_KEY` | `.env` 补阿里云百炼凭证（fun-asr 转写）| 转写链路整体不可用（同样 fail-visible 提示缺凭证）| **（2026-09-13 已配：凭证不入库、`.env` 权限 640；配好后重启服务，`GET /learn/digest/quota` 应报 `asrAvailable:true`）**
| `ADAI_BILIBILI_COOKIE`（**可选**）| B站 登录态 Cookie（至少 `SESSDATA=...`）——**未登录时 B站 字幕接口一律返回空**（2026-09-12 实测 6 个视频全空），于是「字幕优先、免费」这条省钱路径实际走不到，每个视频都落进付费转写。配了 Cookie 才有机会拿到 AI 字幕 → 省转写费 | 不配也能用，但视频基本都要转写；**注意隐私**：这等于把你的 B站 登录态放在服务器上，按需开启、随时可撤 |
> **跑部署门禁的 smoke**：`deploy-gate.sh` 的 GATE-AFTER 从**本机环境变量**读 `ADAI_SMOKE_ACCOUNT` / `ADAI_SMOKE_PASSWORD`（不是服务器 `.env`）；且如果本机挂了代理（本项目开发机默认 `HTTP_PROXY=127.0.0.1:1087`），curl 打 `http://82.156.111.146:8080` 会被代理拦掉 → 登录拿不到 token。完整可用的跑法（2026-09-12 实测通过）：
> ```bash
> export ADAI_SMOKE_ACCOUNT=adai ADAI_SMOKE_PASSWORD=… no_proxy=82.156.111.146 NO_PROXY=82.156.111.146
> bash ai-engineering/deploy-gate.sh 82.156.111.146 services/adai-core/build/libs/adai-core-0.0.1-SNAPSHOT.jar
> ```
| 月度转写配额 | `adai.learn.asr.month-quota-seconds`（**默认 `108000` = 30 小时**，用户 2026-09-13 拍板：前 10 小时走云端免费额度=0 元，超出部分按 0.288 元/小时，最坏 ≈5.76 元/月；想完全不花钱就调回 `36000`）| 用满即拒绝并说明剩余额度，不会静默花钱 |
> **免费额度按模型快照绑定（2026-09-13 用户控制台核对）**：默认模型 `paraformer-v2` 有 **36,000 秒（10 小时）· 每月 1 日重置 · 长期有效** 的免费额度（本产品当前就用它，所以额度内转写 0 元）；而同账号 `fun-asr-flash-2026-06-15` 是另一种带到期日的额度（2026-09-16 到期）且该模型**仅支持 ≤5 分钟短音频**，与长视频场景不通用。**结论：默认保持不变**；`adai.learn.asr.model` 可换模型，但换之前必须确认两件事：① 该模型支持长音频；② 与现有「录音文件异步转写」API 兼容，并同步改 `yuan-per-hour`。

| 转写单价（用于报价估算）| `adai.learn.asr.yuan-per-hour`（默认 `0.288`，对应 `paraformer-v2` 官方价 0.00008 元/秒）；若改用 `fun-asr`（0.00022 元/秒 = 0.792 元/小时）**必须同步改这个值**，否则报价会低估。默认模型有每月 10 小时免费额度，额度内实际 0 元 |
| 防意外扣费（建议在阿里云控制台做）| 百炼控制台 → 免费额度页 → 为目标 ASR 模型开启**免费额度用完即停**（额度耗尽返回 403 `AllocationQuota.FreeTierOnly`，不再按量扣费）| 不开则额度用尽后**自动按量付费**（2026-09-06 那笔 fun-asr 费用就是这种情况）|
| **单实例部署（硬约束）** | **必须单实例/单进程**：learn 的消化任务态（`jobs`）是**进程内 Map**、转写配额靠 **JVM 内** per-user 条带锁做的读-改-写原子 | **多实例/同机多进程会超卖**：同一素材可能被转写两次（重复花钱）、月度额度可能被突破（REVIEW P2-learn18）。将来要横向扩容，必须先把任务态与账本移出进程（或引入分布式锁）|

> learn 的产物是文件（`data/{userId}/learn/{type}/{topic}/NN-{slug}.md` + 主题 `README.md` + `_raw/`），**与 Mac 侧 DSH 技能 `learn-digest` 同契约**——备份/迁移只需拷 `data/`（`backup_prod.sh` 已覆盖）。

## 7. 多账号数据迁移（v1.0.0）

单用户 → 多账号（`data/` → `data/{userId}/`）升级时执行：

```bash
# 1. 备份（必须）
tar -czf /opt/adaios/data-backup-$(date +%Y%m%d-%H%M%S).tar.gz -C /opt/adaios data

# 2. 单层目录迁入 adai 账号层
mkdir -p /opt/adaios/data/adai
for d in identity index memory project records trading; do
  [ -d "/opt/adaios/data/$d" ] && mv "/opt/adaios/data/$d" "/opt/adaios/data/adai/$d"
done

# 3. 账号表（adai = 管理员）
mkdir -p /opt/adaios/data/accounts
cat > /opt/adaios/data/accounts/accounts.json << 'EOF'
[ {
  "userId" : "adai",
  "role" : "admin",
  "enabled" : true,
  "createdAt" : "2026-08-09"
} ]
EOF

# 4. 权限 + 重启 + 重建记忆
chown -R adaios:adaios /opt/adaios
systemctl restart adai-core
curl -s -X POST http://localhost:8080/api/v1/memory/rebuild -H "X-User-Id: adai"
```

> 备份保留到确认无误后删除。账号可在 adai-admin 后台（账号管理）创建更多。

## 8. 前端静态服务（adai-web / adai-admin）

v1.0.0 起生产同时部署 Flutter Web 前端（无 nginx，Python http.server + systemd）：

```bash
# 本地构建（指向生产后端）
cd apps/adai-web && flutter build web --wasm --no-tree-shake-icons --optimization-level=1 --no-strip-wasm --dart-define=API_BASE_URL=http://82.156.111.146:8080
# adai-admin 无需 ADMIN_TOKEN（REVIEW #178：X-Admin-Token / ADMIN_TOKEN 退役，账号密码登录）
# ⚠️ admin 构建必须带 --base-href=/admin/（apps/adai-admin/scripts/serve_web.sh 已内置）：
#   否则 index.html 的 <base href> 保持 "/"，浏览器打开 /admin/ 时按根加载 web 版 bootstrap/main.dart.js
#   → 显示的是 adai-web 桌面壳而非后台（2026-09-04 线上事故根因，已修）
# 构建后必须打 CanvasKit + 字体本地化补丁（见 serve_web.sh）——漏打会白屏（gstatic CDN 被墙）！

# 上传（tar 管道；⚠️ 勿直接 rm 运行中服务的 cwd，先传 admin.new 再停服原子替换）
cd build/web && tar -cf - . | ssh ubuntu@82.156.111.146 'sudo mkdir -p /opt/adaios/web.new && sudo tar -xf - -C /opt/adaios/web.new && sudo chown -R adaios:adaios /opt/adaios/web.new && sudo systemctl stop adaios-web && sudo rm -rf /opt/adaios/web && sudo mv /opt/adaios/web.new /opt/adaios/web && sudo systemctl start adaios-web'

# 服务器：创建 systemd 静态服务（用 serve_static.py，正确 MIME + 多线程 + gzip 压缩 + 分级缓存）
# serve_static.py 已入仓（docs/deployment/serve_static.py，2026-08-22 起）：
#   - gzip 压缩：wasm 压 ~67%、js 压 ~71%（首屏 14MB → ~4MB，Windows 不再白屏 1 分钟）
#   - 分级缓存：/canvaskit/ /fonts/ /icons/ /assets/fonts/ 长缓存（max-age=604800 immutable，
#     中文字体为 HiraginoSansGB-Subset.woff2（GB2312 子集 1.8MB，2026-08-20 起替代 23.5MB ttc）；
#     若 no-store 每次全量重下导致白屏/进度条），其余产物 no-cache + 条件请求
#     （Last-Modified/If-Modified-Since → 304 秒回：刷新不全量重下，改版仍即时生效）
#   - HTTP/1.1 keep-alive（原 HTTP/1.0 每请求新建连接，多文件下载排队）
#   - 压缩结果内存缓存（大文件只压一次）
# 更新部署：scp docs/deployment/serve_static.py → /opt/adaios/serve_static.py → restart adaios-web/admin
cat > /etc/systemd/system/adaios-web.service << 'EOF'
[Unit]
Description=adaios-web static server (:8082)
After=network.target
[Service]
Type=simple
WorkingDirectory=/opt/adaios
ExecStart=/usr/bin/python3 /opt/adaios/serve_static.py 8082 /opt/adaios/web
Restart=on-failure
RestartSec=5
[Install]
WantedBy=multi-user.target
EOF
systemctl enable --now adaios-web
# admin 同模板，端口 8083，目录 /opt/adaios/admin
```

### 8.1 PWA（手机装到主屏）— 2026-09-13 装机批

手机端入口走 **`adai-app` 的 web 构建**（与 iOS 原生 app 同一份代码），托管在 `https://adaiadai.com/m/`，
iPhone Safari「添加到主屏幕」后以独立 App 形式全屏运行。**目的**：终结「免费 Apple ID 签名 7 天过期 → 打不开」的入口断裂（REVIEW P2-用户1）。

```bash
# ① 本地构建（脚本内置 base-href + CanvasKit 补丁 + 字体本地化补丁 + 三条硬校验）
cd apps/adai-app
sh scripts/build_web.sh https://api.adaiadai.com /m/
#   ⚠️ 必须传 /m/ 作为 BASE_HREF：字体补丁路径会跟着子路径走（漏了 → 中文全框）
#   ⚠️ 构建与补丁逻辑在 scripts/build_web.sh（serve_web.sh 只负责本地起服务，不再重复实现）
#   ⚠️ 校验会 FAIL 的三种情况：补丁未注入 / 补丁缺 base-href 前缀 / 字体文件不在产物内

# ② 上传（原子替换，admin 同款 tar 管道）
cd build/web && tar -cf - . | ssh ubuntu@82.156.111.146 \
  'sudo rm -rf /opt/adaios/app-web.new && sudo mkdir -p /opt/adaios/app-web.new && sudo tar -xf - -C /opt/adaios/app-web.new && sudo chown -R adaios:adaios /opt/adaios/app-web.new && sudo rm -rf /opt/adaios/app-web && sudo mv /opt/adaios/app-web.new /opt/adaios/app-web && sudo systemctl restart adaios-app'

# ③ systemd（端口 8084，目录 /opt/adaios/app-web，复用 serve_static.py）
#    单元 adaios-app.service 与 adaios-web 同模板，仅端口/目录/描述不同

# ④ Caddy：/m/ 整段剥前缀转发（⚠️ 必须排在兜底 handle 之前）
#    adaiadai.com {
#        redir /m /m/ permanent
#        handle_path /m/* { reverse_proxy 127.0.0.1:8084 }
#        handle { reverse_proxy 127.0.0.1:8082 }   # 存量桌面 web，不动
#    }
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && sudo systemctl reload caddy
```

> 装机步骤（用户手动，一次性）：iPhone Safari 打开 `https://adaiadai.com/m/` → 分享 → 「添加到主屏幕」→ 主屏出现「阿呆阿呆」图标 → 点开即全屏 App（无需签名、不会 7 天过期）。
> 已验证：`/m/` 全链路 200（index/manifest/图标/main.dart.js/service worker/字体）、`/m` → 301 → `/m/`、CORS 允许 `adaiadai.com` 调 `api.adaiadai.com`、登录 + feed/memory/learn/trading 端点 200。
> 未验证：实机「添加到主屏幕」与 iOS 上首次加载体验（本机无浏览器/手机可代跑，需用户确认）。

> ⚠️ **前端产物烧录 IP 陷阱**：`flutter build web` 不带 `--dart-define=API_BASE_URL` 会静默烧录 `localhost:8080`，浏览器打开即白屏（请求自己电脑）。构建必须显式传生产地址。同理 iOS：`--dart-define=API_BASE_URL=https://api.adaiadai.com`。

## 9. iOS 部署（adai-app → iPhone）

USB 连 Xcode 直装（**付费开发者账号**，profile 1 年有效；Team `4G3D37YKSB`）：

```bash
# 地址用域名（§10：换服务器永不重构建）；ATS 明文例外仍配在 Info.plist（NSAllowsArbitraryLoads），https 域名不依赖它
cd apps/adai-app
flutter build ios --release --dart-define=API_BASE_URL=https://api.adaiadai.com
# 首次装机需在手机信任证书：设置 → 通用 → VPN与设备管理 → 信任「Apple Development: rottokaka@gmail.com」
# 安装 / 启动（UDID 用 `xcrun devicectl list devices` 查）
xcrun devicectl device install app --device <UDID> build/ios/iphoneos/Runner.app
xcrun devicectl device process launch --device <UDID> com.adaiadai.adaiApp
# 或用 Xcode 打开 ios/Runner.xcworkspace，选择真机，点 Run
```

> ✅ **2026-09-13 起签名 1 年有效**：付费开发者账号已落地，实测 `embedded.mobileprovision` = `2026-09-13 → 2027-09-13`（REVIEW P2-用户1 已出表）。**若发现 profile 起止日没变**，是 Xcode 复用了本地尚未过期的旧 profile——删掉 `~/Library/Developer/Xcode/UserData/Provisioning Profiles/*.mobileprovision` 再构建即可强制重签（详见 pitfalls 十二）。
> ⚠️ **最低支持 iOS 15**：Flutter 3.47 构建时自动把 `IPHONEOS_DEPLOYMENT_TARGET` 由 13.0 提到 15.0，iOS 13/14 设备将装不了。
> ⚠️ **debug 装机可能闪退**：Flutter 引擎版本须晚于设备 iOS 版本，否则 debug 构建启动即崩（`-[FlutterViewController createTouchRateCorrectionVSyncClientIfNeeded]` 空指针；release 不受影响）。2026-09-13 已升 Flutter **3.47.4**（引擎 2026-09-03）规避；若再遇到，可先用 `flutter build ios --release` 装机兜底。
> ⚠️ **到期日：2027-09-13**（`embedded.mobileprovision` 实测起止 `2026-09-13 → 2027-09-13`）。到期当天 App 会像免费签名 7 天过期那样**直接打不开**——同型风险只是把周期从 7 天拉长到 1 年（REVIEW P2-APNs5）。**建议自建日历提醒（提前 30 天）**；不续费/证书撤销后同样失效。
> **推送能力（RFC 20260913）**：Runner target 已挂 `Runner/Runner.entitlements`（`aps-environment=development`，三个构建配置都挂）——自动签名下 `flutter build ios` 会自行去 Apple 侧开启 App ID 的 Push Notifications 能力并重签描述文件（2026-09-13 实测：产物 `codesign -d --entitlements` 含 `aps-environment`）。**若构建报 `Provisioning profile doesn't include the aps-environment entitlement`**：用 Xcode 打开 `ios/Runner.xcworkspace` 点一次 Run 完成能力注册，或给 xcodebuild 加 `-allowProvisioningUpdates`。
> **装机信任步骤（仅免费签名需要）**：付费开发者账号的 development 签名通常**不再需要**「设置 → 通用 → VPN与设备管理 → 信任」这一步；若仍被要求信任，按提示操作即可（2026-09-13 未在付费账号下重复验证该差异）。
> TestFlight 待接（需 Apple Distribution 证书 + Archive 上传；内测构建 90 天有效）。
> 历史装机（≤2026-08-30）烧 IP `http://82.156.111.146:8080`；**2026-09-10 起改烧域名** `https://api.adaiadai.com`。
>
> **分享扩展（Share Extension，RFC 20260914）的装机与验证**——⚠️ **这条链路尚未在真机验证过**
> （2026-09-14 构建与 entitlements 已取证，但 `devicectl` 报设备 `unavailable`，装不了机）：
> 1. **App Groups 能力**：`Runner` 与 `ShareExtension` 两个 App ID 都要挂 `group.com.adaiadai.adaiApp`。
>    自动签名下 `flutter build ios` 会自行去 Apple 侧开启并重签描述文件——2026-09-14 实测**已成功**，
>    判据是产物 entitlements 里两个 target 都出现 `com.apple.security.application-groups`：
>    `codesign -d --entitlements :- build/ios/iphoneos/Runner.app`（主 App）
>    `codesign -d --entitlements :- build/ios/iphoneos/Runner.app/PlugIns/ShareExtension.appex`（扩展）。
> 2. **扩展真的嵌进去了**：`ls build/ios/iphoneos/Runner.app/PlugIns/` 应看到 `ShareExtension.appex`。
> 3. **装机**：`xcrun devicectl device install app --device <UDID> build/ios/iphoneos/Runner.app`
>    （设备 `unavailable` 时报 `unable to locate a device matching the requested device identifier`，
>    error 1011 —— 需 USB 连接或同一网络且已解锁）。
> 4. **一次性动作**：打开阿呆 → 学习页页头「把分享接到阿呆」→ 点「给我一把钥匙」——这一步会把限权令牌
>    写进 App Groups 共享容器（弹窗会显示「分享面板已就绪」）。**在此之前分享面板里点了也不会有反应**。
> 5. **真实验收**：B站（或抖音）→ 分享 → 面板里找「阿呆阿呆」→ 点一下 → 应看到「已交给阿呆，正在读…」
>    约 1 秒后自动关闭，且**主 App 没有被拉起**；稍后打开阿呆，学习页能看到正在读/新卡片。
>    ℹ️ 面板「第一排」由系统按使用习惯排序，新装的扩展可能先落在**「更多」**里——不是失败，用一次会浮上来。
>
> **分享扩展排查对照表**（真机验收时按现象对号入座；每条都对应扩展里那句人话或一个可查的事实）：
>
> | 现象 | 最可能的原因 | 怎么办 |
> |:-----|:-------------|:-------|
> | 分享面板里**找不到**「阿呆阿呆」 | ① 新装的扩展被系统排在**「更多」**里；② 分享的内容类型不在激活规则内（本批只收 URL / 网页 / 文本，**图片和文件不在范围**）；③ 扩展根本没装进包 | ① 拉到最后点「更多」找它并置顶；② 确认分享的是链接/文本；③ `ls build/ios/iphoneos/Runner.app/PlugIns/` 应有 `ShareExtension.appex` |
> | 点了「阿呆阿呆」，**转一下就没了 / 没反应** | 共享容器里没有令牌（从未在 App 里点过「给我一把钥匙」） | 打开阿呆 → 学习页页头「把分享接到阿呆」→ 弹窗应显示「**分享面板已就绪**」；若显示「接过一次，但好像已经失效了」就再点一次「给我一把钥匙」 |
> | 扩展里说「阿呆还没拿到钥匙」 | 同上（容器空），或 **App Groups 没签上** | ① 先签发一次；② 仍不行 → `codesign -d --entitlements :- <路径>/ShareExtension.appex` 必须能看到 `group.com.adaiadai.adaiApp` |
> | 扩展里说「钥匙过期或已被收回」 | 令牌 90 天到期 / 在网页端被撤销 / 改密触发了 `revokeAll` | 回 App 重新点「给我一把钥匙」（会覆盖容器里那把） |
> | 扩展里说「我还在读上一条，这条没排上」 | 后端同一时间只跑一个消化任务（`jobs.compute` 抢占失败即复用当前 job，**新输入不入队**） | 等上一条读完再分享。这是**如实告知**，不是 bug；后端丢弃语义待改（REVIEW P2-分享4） |
> | 扩展里说「这次分享来的内容里我没找到链接」 | 分享的是纯文字/图片，或链接是非 http scheme（如 `bilibili://`） | 确认分享内容里带 `http(s)://` 链接 |
> | 扩展里说「学习这件事我这儿还关着」 | learn 插件未启用 | 到插件设置里打开 |
> | 想确认扩展**有没有被系统加载** | — | Xcode → Window → Devices and Simulators → 选设备 → Open Console，过滤 `ShareExtension`；或手机「设置 → 隐私与安全性 → 分析与改进 → 分析数据」搜 `ShareExtension` |
> | 扩展**闪退** | 历史原因之一已修（曾因 xcconfig 继承而链上 Flutter 引擎，2026-09-14 实测已剥离为 0 依赖）；其它崩溃看崩溃日志 | 取「分析数据」里的 `ShareExtension-*.ips` 看栈 |

## 10. 域名 + HTTPS（adaiadai.com，2026-09-01 已上线）

目标：浏览器/app 走 `https://adaiadai.com`，Caddy 自动申请续期 Let's Encrypt 免费证书，反代三个端口。换服务器只改 DNS，前端永不重构建。

> **已上线**（2026-09-01，备案号京ICP备2026056893号）：DNS A 记录 + Caddy 反代 + 证书全部就绪，web/admin 已重构建指向 `https://api.adaiadai.com`（CORS 白名单已加 `https://adaiadai.com`，注意无端口 Origin 需精确值——`:*` 通配匹配不上无端口 Origin）。

```bash
# 1. DNS：adaiadai.com A 记录 → 82.156.111.146（控制台操作）
# 2. 安装 Caddy（apt）
sudo apt install -y caddy
# 3. Caddyfile（自动申请/续期证书，零额外配置）
cat > /etc/caddy/Caddyfile << 'EOF'
adaiadai.com {
    # /admin（无尾斜杠）→ 301 /admin/，否则匹配不上 /admin/* 落到 web 404（2026-09-04）
    redir /admin /admin/ permanent
    handle_path /admin/* {
        reverse_proxy 127.0.0.1:8083
    }
    handle {
        reverse_proxy 127.0.0.1:8082
    }
}
api.adaiadai.com {
    reverse_proxy 127.0.0.1:8080
}
EOF
sudo systemctl restart caddy
# 4. 防火墙放行 80/443（腾讯云控制台）
# 5. 前端重构建指向域名：--dart-define=API_BASE_URL=https://api.adaiadai.com
#    app 写 https://api.adaiadai.com，以后换服务器永不再改 app
# 6. CORS：.env 的 ADAI_ALLOWED_ORIGIN_PATTERNS 追加 https://adaiadai.com（无端口精确值）
```

---

## 11. APNs 推送配置（阿呆 app 自有推送，RFC 20260913）

**目标**：让 10 种推送（盘前/买点/止损/行情异动/收盘小结/复习提醒）由**阿呆自己**弹到手机，不再借第三方 App（Bark）转达。

### 11.1 创建 APNs Auth Key（用户手动，一次性）

> 这步**必须由账号持有人做**（密钥只能下载一次，且需要 Apple ID 登录）。

1. 打开 <https://developer.apple.com/account/resources/authkeys/list>；
2. `+` → Name 填 `AdaiOS APNs` → 勾选 **Apple Push Notifications service (APNs)** → Continue → Register；
3. **Download** 得到 `AuthKey_XXXXXXXXXX.p8`（**只给这一次机会下载**，请立即保存）；
4. 记下页面上的 **Key ID**（10 位，如 `ABCD123456`）；Team ID 是 `4G3D37YKSB`（本项目固定）。

### 11.2 放到服务器并配置

```bash
# 1) 私钥放服务器本地（不进 git、不进仓库）
ssh ubuntu@82.156.111.146
sudo mkdir -p /opt/adaios/backend/secrets
sudo cp AuthKey_XXXXXXXXXX.p8 /opt/adaios/backend/secrets/
sudo chown adaios:adaios /opt/adaios/backend/secrets/AuthKey_XXXXXXXXXX.p8
sudo chmod 600 /opt/adaios/backend/secrets/AuthKey_XXXXXXXXXX.p8

# 2) 写进 /opt/adaios/backend/.env（该文件权限 640，不进 git）
ADAI_PUSH_APNS_KEY_PATH=/opt/adaios/backend/secrets/AuthKey_XXXXXXXXXX.p8
ADAI_PUSH_APNS_KEY_ID=<Key ID>
ADAI_PUSH_APNS_TEAM_ID=4G3D37YKSB
ADAI_PUSH_APNS_BUNDLE_ID=com.adaiadai.adaiApp
# 灰度（可选）：首次接入时先只放少数类型验证链路，例如 close-summary,learn-review；
# 验证通过后清空该行 = 全量（无需改代码）。**2026-09-13 生产已清空 = 10 类全量。**
ADAI_PUSH_APNS_TYPES=

# 3) 重启并自检
sudo systemctl restart adaios-backend
sudo journalctl -u adaios-backend -n 30 --no-pager | grep -i apns
```

### 11.3 验证（不必等下一次定时推送）

```bash
TOKEN=$(curl -s -X POST https://api.adaiadai.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"adai","password":"<密码>"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

curl -s https://api.adaiadai.com/api/v1/push/status -H "Authorization: Bearer $TOKEN"
# 期望：channels 里 name=apns 的 enabled=true / configured=true，deviceCount>=1
```

App 侧：打开一次 App（登录态）即完成设备登记 → `GET /api/v1/push/devices` 应出现本机 token（`environment=sandbox`）。

### 11.4 排查对照表

| 日志/现象 | 含义 | 处置 |
|:---------|:-----|:-----|
| `APNs 渠道不可用：.p8 私钥加载失败` | 路径不对/权限不足/文件不是 PKCS#8 PEM | 核对 `KEY_PATH` 与 `chmod 600` |
| `status=403 reason=InvalidProviderToken` | `.p8` 与 `key-id`/`team-id` 不配套（或 JWT 签名格式错） | 三者必须来自**同一把 key** |
| `status=400 reason=BadDeviceToken` | token 与网关/环境不匹配，或 apns-topic 不对 | 侧载= sandbox、TestFlight/上架= production；`bundle-id` 与 App 一致 |
| `reason=ExpiredProviderToken` | 服务器时钟偏移 | 校时（`timedatectl`）|
| `status=410 reason=Unregistered` | 设备已删 App | 无需处理（渠道会自动清理该登记）|
| 日志「跳过（不在灰度白名单）」 | `ADAI_PUSH_APNS_TYPES` 挡住了该类型 | 验证通过后清空该变量 |
| 手机连一条通知都没有，且 `enabled=true`/`deviceCount>=1` | 系统通知权限没给 | App 内会提示，点「去开启」跳系统设置 |

> ⚠️ **与 Bark 并存会弹两条**：Bark 渠道（`ADAI_PUSH_BARK_KEY`）若仍配置，同一条推送会同时经 APNs 与本机 Bark App 各弹一次。**2026-09-13 生产已注释该键**（原值在 `.env.bak-*`）；要回退放开注释并填回 device key 即可。Feed 渠道不受影响（关 Bark 不丢消息）。
>
> **真实投递冒烟（换 key / 续期后必跑）**：`ADAI_APNS_LIVE_KEY_PATH=… ADAI_APNS_LIVE_KEY_ID=… ADAI_APNS_LIVE_TEAM_ID=4G3D37YKSB ADAI_APNS_LIVE_TOKEN=<GET /push/devices 里的真机 token> ADAI_APNS_LIVE_ENV=sandbox ./gradlew test --tests "*ApnsLiveSmokeTest*"` → 断言 APNs 回 200，且**你手机上会真的收到一条通知**（默认跳过，常规测试不受影响）。
>
> **出网自检（生产服务器，2026-09-13 实测通过）**：
> `curl -i --http2 -X POST https://api.sandbox.push.apple.com/3/device/<64位假token> -H "authorization: bearer bogus" -H "apns-topic: com.adaiadai.adaiApp" -d '{}'`
> → 期望 `HTTP/2 403` + `{"reason":"InvalidProviderToken"}`（假 JWT 的预期结果，**证明网络与 HTTP/2 传输路径通**）。
