#!/usr/bin/env bash
# =============================================================================
# trading-local-up.sh — 一条命令把「交易插件」在本地跑起来（2026-10-08 晨补）
#
# 用途：adai 醒来想直接验证 web 端交易插件（尤其 R-04 的 K 线）时用。
#       起后端 :8080 + 构建并起 web :8082，然后用浏览器打开即可。
#
# 用法：bash .agents/mechanism/scripts/trading-local-up.sh
#       bash .agents/mechanism/scripts/trading-local-up.sh --stop     # 两个都停
#
# 前置（缺了脚本会明确告诉你，不会给你一个看不懂的 404）：
#   - apps/adai-web/web/fonts/ 下两个 woff2（**不入库的手工资产**，见该目录 .gitignore）
#     缺了就从生产补：ssh ubuntu@82.156.111.146 'sudo tar -C /opt/adaios/web -cf - fonts' | tar -xf - -C apps/adai-web/web/
#   - services/adai-core/.env（API Key，不入库）
#
# 备注：本地 data/ 必须是**你自己的数据**（生产快照或本地副本）——没有数据时
#       页面能开但登录不了（账号未设密码），那不是代码问题。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

stop_all() {
  pkill -f "AdaiCoreApplication" 2>/dev/null && echo "已停后端" || echo "后端本来就沒在跑"
  pkill -f "http.server 8082" 2>/dev/null && echo "已停 web" || echo "web 本来就沒在跑"
}

if [ "${1:-}" = "--stop" ]; then
  stop_all
  exit 0
fi

if [ ! -f "apps/adai-web/web/fonts/Roboto.woff2" ] || [ ! -f "apps/adai-web/web/fonts/NotoSansSC-Subset.woff2" ]; then
  echo "❌ 缺本地字体（apps/adai-web/web/fonts/，不入库的手工资产）——不补的话中文全缺、控制台一排 404。" >&2
  echo "   补齐：ssh ubuntu@82.156.111.146 'sudo tar -C /opt/adaios/web -cf - fonts' | tar -xf - -C apps/adai-web/web/" >&2
  exit 1
fi

echo "=== 1/2 起后端（:8080）==="
if curl -s -o /dev/null -m 2 http://localhost:8080/api/v1/identity; then
  echo "    后端已在跑，跳过"
else
  (cd services/adai-core && nohup ./gradlew bootRun --offline >/tmp/adai-core-local.log 2>&1 &)
  for _ in $(seq 1 60); do
    sleep 2
    curl -s -o /dev/null -m 2 http://localhost:8080/api/v1/identity && break
  done
  echo "    后端就绪（日志 /tmp/adai-core-local.log）"
fi

echo "=== 2/2 构建并起 web（:8082，连本机 :8080）==="
if curl -s -o /dev/null -m 2 http://localhost:8082/; then
  echo "    web 已在跑，跳过构建"
else
  pkill -f "http.server 8082" 2>/dev/null || true
  (cd apps/adai-web && sh scripts/serve_web.sh >/tmp/adai-web-local.log 2>&1 &)
  for _ in $(seq 1 90); do
    sleep 2
    curl -s -o /dev/null -m 2 http://localhost:8082/ && break
  done
  echo "    web 就绪（日志 /tmp/adai-web-local.log）"
fi

echo
echo "✅ 打开： http://localhost:8082/   （强刷 Cmd+Shift+R；建议用无痕窗口，避免 Service Worker 缓存旧版本）"
echo "   登录 → 交易 → 持仓 Tab：代码列那个橙色小图标 = 看 K 线"
echo "   停掉： bash .agents/mechanism/scripts/trading-local-up.sh --stop"
