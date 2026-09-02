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

# REVIEW #127 管理端点令牌（admin/accounts 端点鉴权，未配置时 503 fail-closed）
ADAI_ADMIN_TOKEN=<随机生成>
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

> ⚠️ 将 `sk-your-deepseek-api-key-here` 替换为真实 DeepSeek API Key；`ADAI_ADMIN_TOKEN` 用 `openssl rand -hex 16` 生成，adai-admin 前端须 `--dart-define=ADMIN_TOKEN=<同值>`。

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
| `adai.security.admin-token` | 管理端点令牌 | 空（503 fail-closed）| `ADAI_ADMIN_TOKEN` |
| `adai.os-base-path` | os/ 知识资产根路径 | `../../os`（⚠️ 相对 cwd 解析，systemd 下会错）| `/opt/adaios/os`（必须显式 `ADAI_OS_BASE_PATH`）|
| CORS 白名单 | 允许来源 | localhost | `ADAI_ALLOWED_ORIGIN_PATTERNS` |

所有配置在 `.env` 文件中管理，JAR 启动时自动读取。

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
# adai-admin 额外: --dart-define=ADMIN_TOKEN=<ADAI_ADMIN_TOKEN 同值>
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

> ⚠️ **前端产物烧录 IP 陷阱**：`flutter build web` 不带 `--dart-define=API_BASE_URL` 会静默烧录 `localhost:8080`，浏览器打开即白屏（请求自己电脑）。构建必须显式传生产地址。同理 iOS：`--dart-define=API_BASE_URL=http://82.156.111.146:8080`。

## 9. iOS 部署（adai-app → iPhone）

USB 连 Xcode 直装（免费 Apple ID，7 天有效）：

```bash
# ATS 明文 HTTP 例外已配（Info.plist NSAllowsArbitraryLoads，后端为 http）
cd apps/adai-app
flutter build ios --release --dart-define=API_BASE_URL=http://82.156.111.146:8080
# 或用 Xcode 打开 ios/Runner.xcworkspace，选择真机，点 Run
```

> ⚠️ 免费 Apple ID 签名 7 天过期；TestFlight 需付费开发者账号（90 天）。

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
