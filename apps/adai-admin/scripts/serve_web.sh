#!/bin/bash
# Build Flutter Web + apply local Font patches + serve via Python
# Usage: sh scripts/serve_web.sh [API_BASE_URL]
# 渲染模式：JS + CanvasKit（不用 --wasm）——2026-08-22 线上白屏根因修复：
#   wasm 双模式（skwasm）产物带 --import-shared-memory，依赖 SharedArrayBuffer，
#   而浏览器只在 HTTPS（或 localhost）下才信任 COOP/COEP 头 → 纯 IP/HTTP 访问
#   wasm 永远无法实例化 → 白屏 + 页面重载。JS 模式无需 COOP/COEP，纯 IP 可跑。
# 下方 bootstrap.js 补丁注入 canvasKitBaseUrl 让 canvaskit 从本地加载（CDN gstatic 被墙）。

set -e

cd "$(dirname "$0")/.."

# 可选参数：API_BASE_URL（连生产后端时传入，如 https://api.adaiadai.com）
# REVIEW #178：X-Admin-Token / ADMIN_TOKEN 已退役——管理口并入统一登录（登录页账号密码 + Bearer 会话）。
API_BASE_URL="${1:-}"

echo "=== Building Flutter Web (JS + CanvasKit) ==="
DEFINES=""
if [ -n "$API_BASE_URL" ]; then DEFINES="$DEFINES --dart-define=API_BASE_URL=$API_BASE_URL"; fi
flutter build web --no-tree-shake-icons $DEFINES

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
#   Roboto → Roboto.woff2（拉丁）；中文（Noto Sans SC 等）→ NotoSansSC-Subset.woff2
#   （2026-08-22 修复：原 HiraginoSansGB-Subset.woff2 是 CFF 轮廓，skwasm 引擎 FreeType
#   解析失败 → 中文全框（Flutter issue #128485 同类）；Noto Sans SC 为 TrueType(glyf) 轮廓
#   + OFL 开源协议可分发。63KB GB2312 子集，由 fonttools 从 Google Fonts 完整版子集化生成）
INDEX="build/web/index.html"
perl -i -pe 's{<script src="flutter_bootstrap.js" async></script>}{<script>var origFetch=window.fetch.bind(window);window.fetch=function(url,opts){if(typeof url==="string"&&url.includes("fonts.gstatic.com")){if(url.includes("roboto"))return origFetch("\/fonts\/Roboto.woff2");return origFetch("\/fonts\/NotoSansSC-Subset.woff2");}return origFetch(url,opts);};<\/script>\n  <script src="flutter_bootstrap.js" async><\/script>}' "$INDEX"

echo "=== Starting server at http://localhost:8083 ==="
cd build/web && python3 -m http.server 8083
