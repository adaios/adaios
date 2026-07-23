# AdaiOS 后端服务部署方案

## 1. 环境信息

| 项目 | 值 |
|------|-----|
| 服务器 OS | CentOS 8.5 (root) |
| 部署方式 | IP + 端口直接访问 |
| 服务端口 | 8080 |
| 运行方式 | systemd 服务，开机自启 |
| 数据目录 | `/opt/adaios/data` |
| 安装目录 | `/opt/adaios/backend` |

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
EOF

chown adaios:adaios /opt/adaios/backend/.env
chmod 600 /opt/adaios/backend/.env
```

> ⚠️ 将 `sk-your-deepseek-api-key-here` 替换为你的真实 DeepSeek API Key

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
# 示例: ./deploy.sh 49.235.37.220 build/libs/adai-core-0.0.1-SNAPSHOT.jar
```

> 脚本自动完成：上传 JAR → 停服务 → 补全 data 目录 → 修权限 → 启服务 → 重建记忆。
> 不再需要手动检查 data 文件完整性。

## 6. 配置说明

| 配置项 | 说明 | 默认值 | 生产值 |
|--------|------|--------|--------|
| `server.port` | 服务端口 | 8080 | 8080 |
| `adai.storage.base-path` | 数据文件存储路径 | `../../data` | `/opt/adaios/data` |
| `adai.ai.provider` | AI 提供商 | `deepseek` | `deepseek` |
| `adai.ai.model` | AI 模型 | `deepseek-chat` | `deepseek-chat` |

所有配置在 `.env` 文件中管理，JAR 启动时自动读取。
