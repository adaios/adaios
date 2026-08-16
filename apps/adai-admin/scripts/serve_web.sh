#!/bin/bash
# Build Flutter Web + apply local Font patches + serve via Python
# Usage: sh scripts/serve_web.sh
# Note: Flutter 3.44+ no longer supports --web-renderer flag.
#       --wasm 双模式：skwasm 主渲染（根治 CanvasKit wasm 偶发崩溃 PictureRecorder OOM）
#       + canvaskit 自动回退（不支持 WasmGC 的浏览器）；入口按浏览器能力选渲染器。
#       --optimization-level=1 --no-strip-wasm：默认 O2 wasm-opt 会 OOM（SIGKILL exit -9）。
#       下方 bootstrap.js 补丁注入 canvasKitBaseUrl 让 canvaskit/skwasm 从本地加载
#       （CDN gstatic 在中国被墙）。

set -e

cd "$(dirname "$0")/.."

# 可选参数：API_BASE_URL（连生产后端时传入，如 http://49.235.37.220:8080）
#           ADMIN_TOKEN（管理端令牌，与后端 ADAI_ADMIN_TOKEN 一致；REVIEW #127）
API_BASE_URL="${1:-}"
ADMIN_TOKEN="${2:-}"

echo "=== Building Flutter Web (wasm dual-mode) ==="
DEFINES=""
if [ -n "$API_BASE_URL" ]; then DEFINES="$DEFINES --dart-define=API_BASE_URL=$API_BASE_URL"; fi
if [ -n "$ADMIN_TOKEN" ]; then DEFINES="$DEFINES --dart-define=ADMIN_TOKEN=$ADMIN_TOKEN"; fi
flutter build web --wasm --no-tree-shake-icons --optimization-level=1 --no-strip-wasm $DEFINES

echo "=== Applying local patches ==="
# Patch flutter_bootstrap.js: add canvasKitBaseUrl to load local WASM
perl -i -pe 's/(_flutter\.loader\.load\(\{)/$1\n  config: {\n    canvasKitBaseUrl: "canvaskit\/"\n  },/' build/web/flutter_bootstrap.js

# #200 回归校验：canvasKitBaseUrl 必须唯一。
# Flutter 升级若改变 bootstrap 模板（load 调用已带 config 键）→ 注入会与之重复，
# 浏览器可能从 CDN 拉 CanvasKit（被墙白屏）。重复则报错终止，避免静默事故。
# #256 补强：模板若自带顶层 config（无 canvasKitBaseUrl），perl 注入产生重复 config，
# JS last-wins 会让模板 config 覆盖注入块（canvasKitBaseUrl 丢失），但计数仍 1 假阴性通过
# → 校验「顶层 config 键恰好 1 个」也必须有，才不放过模板变化。
BOOTSTRAP="build/web/flutter_bootstrap.js"
CONFIG_COUNT=$(grep -c '^  config:' "$BOOTSTRAP" | tr -d ' ')
# 2026-08-13 修复 #200/#256 校验过时：新 Flutter bootstrap 源码内嵌 canvasKitBaseUrl
# 供 loader 读取 config.canvasKitBaseUrl（E 函数），全文计数不再可靠（源码 2 次 + 注入 1 次 = 3）。
# 校验改为「顶层 config 恰好 1 个」（防 #256 模板自带 config 被 last-wins 覆盖）+「config 块内
# 含 canvasKitBaseUrl」（确认注入真正落位，而非误判）。
CONFIG_HAS_CANVAS=$(grep -A5 '^  config:' "$BOOTSTRAP" | grep -c 'canvasKitBaseUrl')
if [ "$CONFIG_COUNT" != "1" ] || [ "$CONFIG_HAS_CANVAS" != "1" ]; then
  echo "ERROR: flutter_bootstrap.js 注入异常——顶层 config=$CONFIG_COUNT 个（期望恰好 1）、config 块内 canvasKitBaseUrl=$CONFIG_HAS_CANVAS 次（期望 1）。"
  echo "Flutter 版本可能已改变 bootstrap 模板（load 自带 config 键），导致补丁重复注入/丢失 → 将从 CDN 拉 CanvasKit（被墙白屏）。"
  echo "请检查 scripts/serve_web.sh 的 perl 注入逻辑后重试。"
  exit 1
fi
echo "OK: canvasKitBaseUrl 注入唯一（config 块内 $CONFIG_HAS_CANVAS 次 · 顶层 config $CONFIG_COUNT 个）"

# Patch index.html: add fetch interceptor for blocked font CDN
# Routes fonts.gstatic.com requests to local VALID fonts：
#   Roboto → Roboto.woff2（拉丁）；中文（Noto Sans SC 等）→ Hiragino Sans GB.ttc
INDEX="build/web/index.html"
perl -i -pe 's{<script src="flutter_bootstrap.js" async></script>}{<script>var origFetch=window.fetch.bind(window);window.fetch=function(url,opts){if(typeof url==="string"&&url.includes("fonts.gstatic.com")){if(url.includes("roboto"))return origFetch("\/fonts\/Roboto.woff2");return origFetch("\/fonts\/Hiragino%20Sans%20GB.ttc");}return origFetch(url,opts);};<\/script>\n  <script src="flutter_bootstrap.js" async><\/script>}' "$INDEX"

echo "=== Starting server at http://localhost:8083 ==="
cd build/web && python3 -m http.server 8083
