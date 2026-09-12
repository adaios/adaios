#!/bin/bash
# 本地起服务预览 Flutter Web（构建 + 补丁在 scripts/build_web.sh，本文件只负责起服务器）
#
# Usage: sh scripts/serve_web.sh [API_BASE_URL] [BASE_HREF]
#   例：连生产后端并模拟 /m/ 子路径（PWA 同构预览）
#     sh scripts/serve_web.sh https://api.adaiadai.com /m/
#
# 2026-09-13（PWA 装机批）：构建与补丁逻辑抽到 build_web.sh，避免「补丁两份」；
#   本文件只保留本地预览职责。

set -e

cd "$(dirname "$0")/.."

API_BASE_URL="${1:-}"
BASE_HREF="${2:-/}"
sh scripts/build_web.sh "$API_BASE_URL" "$BASE_HREF"

echo "=== Starting server at http://localhost:8081 ==="
echo "    子路径部署请访问： http://localhost:8081$BASE_HREF"
cd build/web && python3 -m http.server 8081
