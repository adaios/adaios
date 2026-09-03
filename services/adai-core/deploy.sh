#!/usr/bin/env bash
# ============================================================
# AdaiOS 后端一键部署脚本
# 用法: ./deploy.sh <服务器IP> <JAR路径>
# 示例: ./deploy.sh 82.156.111.146 build/libs/adai-core-0.0.1-SNAPSHOT.jar
# ============================================================
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "用法: $0 <服务器IP> <JAR路径>"
    echo "示例: $0 82.156.111.146 build/libs/adai-core-0.0.1-SNAPSHOT.jar"
    exit 1
fi

SERVER=$1
JAR=$2
REMOTE_DIR="/opt/adaios"
DATA_DIR="${REMOTE_DIR}/data"
BACKEND_DIR="${REMOTE_DIR}/backend"

echo "▸ 上传 JAR 到 ${SERVER}:/tmp/adai-core.jar..."
# 2026-08-23：生产 SSH 用 ubuntu@（root 直连被拒），jar 先传 /tmp 再 sudo 装入 backend 目录
scp "$JAR" "ubuntu@${SERVER}:/tmp/adai-core.jar"

echo "▸ SSH 部署..."
ssh "ubuntu@${SERVER}" sudo bash -s << 'SSH_SCRIPT'
set -euo pipefail

echo "  0/6  装入新 JAR（/tmp/adai-core.jar → /opt/adaios/backend/adai-core.jar）..."
install -o adaios -g adaios -m 644 /tmp/adai-core.jar /opt/adaios/backend/adai-core.jar
rm -f /tmp/adai-core.jar

echo "  1/6  停止服务..."
systemctl stop adai-core || true

echo "  2/6  检查数据目录完整性..."
mkdir -p /opt/adaios/data/adai/identity
mkdir -p /opt/adaios/data/records
mkdir -p /opt/adaios/data/records/cards
mkdir -p /opt/adaios/data/memory
mkdir -p /opt/adaios/data/index
mkdir -p /opt/adaios/data/trading
mkdir -p /opt/adaios/data/project

# ── 默认个人档案（多账号分层：data/{userId}/identity/，seed 账号 adai）──
if [ ! -f /opt/adaios/data/adai/identity/profile.md ]; then
    echo "  → 创建默认个人档案（data/adai/identity/）..."
    cat > /opt/adaios/data/adai/identity/profile.md << 'EOF'
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

echo "  3/6  配置环境变量..."

# ── .env — 供 DeepSeek API Key 等配置 ──
if [ ! -f /opt/adaios/backend/.env ]; then
    echo "  → 创建 .env 模板（请编辑填入 DEEPSEEK_API_KEY）..."
    cat > /opt/adaios/backend/.env << 'EOF'
# AI Provider（deepseek 或 mock）
ADAI_AI_PROVIDER=deepseek

# DeepSeek API（必填，生产模式使用 deepseek provider）
DEEPSEEK_API_KEY=sk-your-key-here

# REVIEW #178（2026-09-02）：X-Admin-Token / ADAI_ADMIN_TOKEN 已退役——管理端点并入统一登录
# （登录 + role=admin 门禁）。下方为部署 smoke/自动维护用的账号密码（须是系统内已设过密码的 admin 账号）：
ADAI_SMOKE_ACCOUNT=adai
ADAI_SMOKE_PASSWORD=

# REVIEW #127 CORS 来源白名单（逗号分隔 origin pattern；默认 localhost）。
# 生产前端若在服务器上，追加：http://82.156.111.146:*,http://<前端域名>:*
ADAI_ALLOWED_ORIGIN_PATTERNS=http://localhost:*,http://127.0.0.1:*,http://82.156.111.146:*

# 数据目录（保持默认即可）
ADAI_DATA_DIR=/opt/adaios/data

# 知识目录（生产服务器无 monorepo，留空 = 不加载领域知识）
# 如需加载，指向 scp 上来的知识文件目录
ADAI_TRADING_KNOWLEDGE_PATH=
ADAI_LIFE_KNOWLEDGE_PATH=
ADAI_PROJECT_KNOWLEDGE_PATH=

# 外部推送渠道（留空 = 仅 Feed 推送）：
# 微信（Server酱，sct.ftqq.com 扫码获取；免费版每天 5 条——2026-08-25 起生产已停用，改用 Bark）
ADAI_PUSH_WECHAT_SENDKEY=
# iOS 原生推送（Bark，推荐：iPhone 装 Bark App 拿设备 key，免费无限条数，直达系统通知）
ADAI_PUSH_BARK_KEY=
# Bark 自托管服务器地址（可选，默认公共服务器 https://api.day.app）
ADAI_PUSH_BARK_BASE_URL=
EOF
    echo "  ⚠ 请登录服务器编辑 /opt/adaios/backend/.env 填入 DEEPSEEK_API_KEY"
    echo "     然后手动执行: systemctl restart adai-core"
fi

echo "  4/6  修正目录权限..."
chown -R adaios:adaios /opt/adaios

echo "  5/6  启动服务..."
systemctl start adai-core || true

# 检查服务是否真的起来了（带就绪重试：服务启动需 5-15 秒，sleep 3 不够）
READY=0
for i in 1 2 3 4 5 6; do
    # #179/#178：产品端点需登录（无 token 401）——就绪探测走 /auth/me，200（有会话）或 401（服务活着但未登录）都算服务已响应
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/auth/me 2>/dev/null | grep -qE "200|401"; then
        READY=1
        break
    fi
    echo "  → 等待服务就绪 ($i/6)..."
    sleep 5
done
if [ $READY -eq 1 ]; then
    echo "  → 服务已运行，重建记忆（/admin/memory/rebuild；REVIEW #178 后需登录 + role=admin）..."
    # REVIEW #178：X-Admin-Token 退役。如 .env 配了 ADAI_SMOKE_ACCOUNT/ADAI_SMOKE_PASSWORD
    # （系统内已设密码的 admin 账号），登录拿 Bearer 后执行；否则跳过（首次部署先 setup 设密码，
    # 之后可在 adai-admin 控制台「系统 → 维护」手动重建）。
    # 2026-09-03 修复：旧 .env（#178 前模板）无这两行 → set -euo pipefail 下 grep 无匹配即退出
    # （部署已装 jar 却报 FAIL）——加 `|| true` 容错，无配置走「跳过重建」分支正常完成。
    SMOKE_ACCOUNT=$(grep '^ADAI_SMOKE_ACCOUNT=' /opt/adaios/backend/.env 2>/dev/null | cut -d= -f2 || true)
    SMOKE_PASSWORD=$(grep '^ADAI_SMOKE_PASSWORD=' /opt/adaios/backend/.env 2>/dev/null | cut -d= -f2 || true)
    if [ -n "$SMOKE_PASSWORD" ]; then
        TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
            -H "Content-Type: application/json" \
            -d "{\"account\":\"${SMOKE_ACCOUNT:-adai}\",\"password\":\"$SMOKE_PASSWORD\"}" \
            | grep -oE '"token":"[a-f0-9]+"' | head -1 | cut -d'"' -f4)
        if [ -n "$TOKEN" ]; then
            curl -s -X POST "http://localhost:8080/api/v1/admin/memory/rebuild?userId=${SMOKE_ACCOUNT:-adai}" \
                -H "Authorization: Bearer $TOKEN" || true
            echo ""
        else
            echo "  → 登录失败（账号密码未设/错误）：跳过自动重建，可后续在控制台手动重建"
        fi
    else
        echo "  → .env 未配 ADAI_SMOKE_PASSWORD：跳过自动重建（首次部署请先 setup 设密码，控制台手动重建）"
    fi
    echo "✅ 部署完成！验证（需登录态）："
    curl -s -o /dev/null -w "  /auth/me HTTP %{http_code}\n" http://localhost:8080/api/v1/auth/me || true
else
    echo "  ⚠ 服务未就绪（6 次探测失败），请检查:"
    echo "    1. /opt/adaios/backend/.env 中的 DEEPSEEK_API_KEY 是否已填写"
    echo "    2. journalctl -u adai-core -n 50 --no-pager"
fi

SSH_SCRIPT

echo ""
echo "✅ 远程部署完成！"
echo "   验证: curl http://${SERVER}:8080/api/v1/identity"
