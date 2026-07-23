#!/usr/bin/env bash
# ============================================================
# AdaiOS 后端一键部署脚本
# 用法: ./deploy.sh <服务器IP> <JAR路径>
# 示例: ./deploy.sh 49.235.37.220 build/libs/adai-core-0.0.1-SNAPSHOT.jar
# ============================================================
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "用法: $0 <服务器IP> <JAR路径>"
    echo "示例: $0 49.235.37.220 build/libs/adai-core-0.0.1-SNAPSHOT.jar"
    exit 1
fi

SERVER=$1
JAR=$2
REMOTE_DIR="/opt/adaios"
DATA_DIR="${REMOTE_DIR}/data"
BACKEND_DIR="${REMOTE_DIR}/backend"

echo "▸ 上传 JAR 到 ${SERVER}:${BACKEND_DIR}/adai-core.jar..."
scp "$JAR" "root@${SERVER}:${BACKEND_DIR}/adai-core.jar"

echo "▸ SSH 部署..."
ssh "root@${SERVER}" bash -s << 'SSH_SCRIPT'
set -euo pipefail

echo "  1/5  停止服务..."
systemctl stop adai-core

echo "  2/5  检查数据目录完整性..."
mkdir -p /opt/adaios/data/identity
mkdir -p /opt/adaios/data/records
mkdir -p /opt/adaios/data/memory
mkdir -p /opt/adaios/data/index

if [ ! -f /opt/adaios/data/identity/profile.md ]; then
    echo "  → 创建默认个人档案..."
    cat > /opt/adaios/data/identity/profile.md << 'EOF'
---
name: 阿呆
preferences:
  language: 中文
  style: 简洁、直接
  focus: 半导体、国产替代、成长股投资
rules:
  confirmation: 交易类操作需确认
  auto: 日常记录可自动处理
tags:
  - 投资
  - 半导体
  - 科技
  - 个人成长
---
# 个人档案

阿呆的个人 AI 协作档案。
EOF
fi

echo "  3/5  修正目录权限..."
chown -R adaios:adaios /opt/adaios

echo "  4/5  启动服务..."
systemctl start adai-core
sleep 2

echo "  5/5  重建记忆..."
curl -s -X POST http://localhost:8080/api/v1/memory/rebuild

echo ""
echo "✅ 部署完成！验证:"
curl -s http://localhost:8080/api/v1/identity | head -c 100
echo ""
SSH_SCRIPT

echo ""
echo "✅ 远程部署完成！"
echo "   验证: curl http://${SERVER}:8080/api/v1/identity"
