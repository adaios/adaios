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
systemctl stop adai-core || true

echo "  2/5  检查数据目录完整性..."
mkdir -p /opt/adaios/data/identity
mkdir -p /opt/adaios/data/records
mkdir -p /opt/adaios/data/records/cards
mkdir -p /opt/adaios/data/memory
mkdir -p /opt/adaios/data/index
mkdir -p /opt/adaios/data/trading
mkdir -p /opt/adaios/data/project

# ── 默认个人档案 ──
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

echo "  3/5  配置环境变量..."

# ── .env — 供 DeepSeek API Key 等配置 ──
if [ ! -f /opt/adaios/backend/.env ]; then
    echo "  → 创建 .env 模板（请编辑填入 DEEPSEEK_API_KEY）..."
    cat > /opt/adaios/backend/.env << 'EOF'
# DeepSeek API（必填，生产模式使用 deepseek provider）
DEEPSEEK_API_KEY=sk-your-key-here

# 数据目录（保持默认即可）
ADAI_DATA_DIR=/opt/adaios/data

# 知识目录（生产服务器无 monorepo，留空 = 不加载领域知识）
# 如需加载，指向 scp 上来的知识文件目录
ADAI_TRADING_KNOWLEDGE_PATH=
ADAI_LIFE_KNOWLEDGE_PATH=
ADAI_PROJECT_KNOWLEDGE_PATH=
EOF
    echo "  ⚠ 请登录服务器编辑 /opt/adaios/backend/.env 填入 DEEPSEEK_API_KEY"
    echo "     然后手动执行: systemctl restart adai-core"
fi

echo "  4/5  修正目录权限..."
chown -R adaios:adaios /opt/adaios

echo "  5/5  启动服务..."
systemctl start adai-core || true
sleep 3

# 检查服务是否真的起来了
if systemctl is-active --quiet adai-core; then
    echo "  → 服务已运行，重建记忆..."
    curl -s -X POST http://localhost:8080/api/v1/memory/rebuild
    echo ""
    echo "✅ 部署完成！验证:"
    curl -s http://localhost:8080/api/v1/identity | head -c 100
    echo ""
else
    echo "  ⚠ 服务未启动，请检查:"
    echo "    1. /opt/adaios/backend/.env 中的 DEEPSEEK_API_KEY 是否已填写"
    echo "    2. journalctl -u adai-core -n 50 --no-pager"
fi

SSH_SCRIPT

echo ""
echo "✅ 远程部署完成！"
echo "   验证: curl http://${SERVER}:8080/api/v1/identity"
