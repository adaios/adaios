#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 部署门禁 + 部署后验证（触发侧：部署前强制 review，部署后自动 smoke）
#
# 用法:  bash ai-engineering/deploy-gate.sh <服务器IP> <JAR路径>
# 说明:  包装 services/adai-core/deploy.sh：
#         GATE-BEFORE  部署前：guard 全量 + 增量 review（不过关拒绝部署）
#         GATE-AFTER   部署后：自动 smoke（核心端点验证）
# 设计:  部署是用户确认的动作（最不可绕过）→ 这是最硬的一道闸门
# ─────────────────────────────────────────────────────────────
set -u

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
SERVER="${1:-}"
JAR="${2:-}"

if [ -z "$SERVER" ] || [ -z "$JAR" ]; then
    echo "用法: bash ai-engineering/deploy-gate.sh <服务器IP> <JAR路径>"
    exit 1
fi

echo "═══ 部署门禁（触发侧·最硬闸门）═══"

# ── GATE-BEFORE：部署前检查（不过关拒绝部署）──
echo ""
echo "▸ GATE-BEFORE 1/3 结构门禁（guard-meta）..."
bash ai-engineering/guard-meta.sh || { echo "❌ 结构门禁 FAIL——修复 frontmatter 后重试（禁止带 FAIL 部署）"; exit 1; }

echo "▸ GATE-BEFORE 2/3 内容对齐（guard-align）..."
bash ai-engineering/guard-align.sh || { echo "❌ 内容对齐 FAIL——同步 api-spec/status 后重试"; exit 1; }

echo "▸ GATE-BEFORE 3/3 防复发（guard.sh G1-G7）..."
bash docs/review/guard.sh >/dev/null 2>&1 || { echo "❌ 守护检查有 HIT——确认非 P0 复发后重试"; exit 1; }

echo ""
echo "▸ GATE-BEFORE 增量 review 提示："
echo "  部署前建议跑一次增量深审（有未修项会在这暴露）："
echo "    bash ai-engineering/process/review.md  ← 流程文档（派官执行）"
echo "  未修项真相源：docs/review/REVIEW.md"
echo ""
echo "✅ 部署前检查全部通过，开始部署..."

# ── 执行部署（jar 转绝对路径，deploy.sh 内部 cd 到 adai-core 也能解析）──
JAR_ABS="$(cd "$(dirname "$JAR")" 2>/dev/null && pwd)/$(basename "$JAR")"
if [ ! -f "$JAR_ABS" ]; then
    echo "❌ jar 不存在：$JAR_ABS"
    exit 1
fi
echo "▸ 部署 jar：$JAR_ABS"
(cd services/adai-core && ./deploy.sh "$SERVER" "$JAR_ABS")
DEPLOY_OK=$?
if [ $DEPLOY_OK -ne 0 ]; then
    echo "❌ 部署失败（deploy.sh exit $DEPLOY_OK）"
    exit 1
fi

# ── GATE-AFTER：部署后自动 smoke ──
echo ""
echo "▸ GATE-AFTER 部署后验证（smoke）..."
sleep 10
BASE="http://${SERVER}:8080"
FAILED=0

check() {
    local desc="$1" method="$2" path="$3" expect="$4"
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" "$BASE$path" -H "X-User-Id: adai" 2>/dev/null)
    if [ "$code" = "$expect" ]; then
        echo "  ✅ $desc → $code"
    else
        echo "  ❌ $desc → $code（期望 $expect）"
        FAILED=1
    fi
}

check "feed 核心链路"   GET  "/api/v1/feed"                  200
check "记忆查询"        GET  "/api/v1/memory?date=2026-08-16" 200
check "交易建议引擎"    POST "/api/v1/trading/advice"         200
check "一句话解析"      POST "/api/v1/trading/trades/parse"   200
check "时间线"          GET  "/api/v1/timeline"               200
check "标签统计"        GET  "/api/v1/tags"                   200

if [ $FAILED -eq 1 ]; then
    echo "❌ 部署后 smoke 有失败项——请检查后端日志"
    exit 1
fi
echo "✅ 部署后 smoke 全部通过——部署完成且验证 OK"
