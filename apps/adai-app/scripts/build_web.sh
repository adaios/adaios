#!/bin/bash
# Build Flutter Web（JS + CanvasKit）+ 打本地补丁，供部署或本地 serve 复用。
#
# Usage: sh scripts/build_web.sh [API_BASE_URL] [BASE_HREF]
#   API_BASE_URL  连生产后端时传入（如 https://api.adaiadai.com）
#   BASE_HREF     托管子路径（默认 /）。PWA 装到主屏走 /m/（2026-09-13 装机批）
#
# 渲染模式：JS + CanvasKit（不用 --wasm）——2026-08-22 线上白屏根因修复：
#   wasm 双模式（skwasm）产物带 --import-shared-memory，依赖 SharedArrayBuffer，
#   而浏览器只在 HTTPS（或 localhost）下才信任 COOP/COEP 头 → 纯 IP/HTTP 访问
#   wasm 永远无法实例化 → 白屏 + 页面重载。JS 模式无需 COOP/COEP。
# bootstrap.js 补丁注入 canvasKitBaseUrl 让 canvaskit 从本地加载（CDN gstatic 被墙）。
#
# 2026-09-13（PWA 装机批）把构建与补丁从 serve_web.sh 抽到本文件，避免「补丁逻辑两份」；
#   同时 base-href 参数化——字体补丁路径必须跟着子路径走（照 adai-admin /admin/ 先例，
#   否则 /m/ 下的中文会去根目录取字体或 404）。

set -e

cd "$(dirname "$0")/.."

API_BASE_URL="${1:-}"
BASE_HREF="${2:-/}"

# base-href 必须前后都带 "/"（Flutter 要求），否则 <base href> 解析异常
case "$BASE_HREF" in
  */) ;;
  *) BASE_HREF="$BASE_HREF/" ;;
esac
case "$BASE_HREF" in
  /*) ;;
  *) BASE_HREF="/$BASE_HREF" ;;
esac

echo "=== Building Flutter Web (JS + CanvasKit) base-href=$BASE_HREF ==="
DEFINES=""
if [ -n "$API_BASE_URL" ]; then DEFINES="--dart-define=API_BASE_URL=$API_BASE_URL"; fi

flutter build web --no-tree-shake-icons --base-href="$BASE_HREF" $DEFINES

echo "=== Applying local patches ==="
# 字体补丁：Flutter 构建自带 NotoSansSC.woff2（web/fonts/），无需显式复制
# （NotoColorEmoji 本仓库未提供，emoji 走系统 fallback）

# Patch flutter_bootstrap.js: add canvasKitBaseUrl to load local WASM
# 相对路径 "canvaskit/" 会按 <base href> 解析 → /m/canvaskit/，无需改写
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
  echo "请检查 scripts/build_web.sh 的 perl 注入逻辑后重试。"
  exit 1
fi
echo "OK: canvasKitBaseUrl 注入唯一（config 块内 $CONFIG_HAS_CANVAS 次 · 顶层 config $CONFIG_COUNT 个）"

# Patch index.html: add fetch interceptor for blocked font CDN
# Routes fonts.gstatic.com requests to local VALID fonts：
#   Roboto → Roboto.woff2（拉丁）；中文（Noto Sans SC 等）→ NotoSansSC-Subset.woff2
#   （2026-08-22 修复：原 HiraginoSansGB-Subset.woff2 是 CFF 轮廓，skwasm 引擎 FreeType
#   解析失败 → 中文全框（Flutter issue #128485 同类）；Noto Sans SC 为 TrueType(glyf) 轮廓
#   + OFL 开源协议可分发。63KB GB2312 子集，由 fonttools 从 Google Fonts 完整版子集化生成）
# 路径必须带 base-href 前缀（$ENV{BASE_HREF}，如 /m/），否则子路径部署下取不到字体 → 中文框框
INDEX="build/web/index.html"
BASE_HREF="$BASE_HREF" perl -i -pe 's{<script src="flutter_bootstrap.js" async></script>}{<script>var origFetch=window.fetch.bind(window);window.fetch=function(url,opts){if(typeof url==="string"&&url.includes("fonts.gstatic.com")){if(url.includes("roboto"))return origFetch("$ENV{BASE_HREF}fonts\/Roboto.woff2");return origFetch("$ENV{BASE_HREF}fonts\/NotoSansSC-Subset.woff2");}return origFetch(url,opts);};<\/script>\n  <script src="flutter_bootstrap.js" async><\/script>}' "$INDEX"

# 补丁落位校验（2026-09-13 装机批）：字体补丁注入过但路径写成根目录 → 子路径部署中文框框，
# 属于「打了补丁却静默失效」，比漏打更难查。此处硬校验（认 origFetch 字面量，不认 base href，
# 否则 `"/` 会被 <base href="/m/"> 命中而假阳性通过）。
if ! grep -q 'fonts.gstatic.com' "$INDEX"; then
  echo "ERROR: index.html 字体补丁未注入——gstatic 字体会被墙 → 中文框框/首屏卡住。"
  exit 1
fi
if ! grep -q "origFetch(\"$BASE_HREF" "$INDEX"; then
  echo "ERROR: 字体补丁路径缺少 base-href 前缀（期望 origFetch(\"${BASE_HREF}fonts/...））——子路径部署下字体 404 → 中文框框。"
  exit 1
fi
# 字体文件本身必须在产物里（web/fonts/ 是 gitignore 的本地资产，换机/清理后易缺；
# 缺了补丁就是 404 → 中文框框。历史上本地就发生过「字体残缺/坏文件」复发）。
# 注意：产物目录不带 base-href 前缀（base href 只是 URL 前缀，文件仍在 build/web 根下）。
for f in fonts/Roboto.woff2 fonts/NotoSansSC-Subset.woff2; do
  if [ ! -f "build/web/$f" ]; then
    echo "ERROR: 字体文件缺失 build/web/$f——补丁会 404 → 中文框框。请从 web/fonts/ 补齐（web/fonts/ 不入库）。"
    exit 1
  fi
done
echo "OK: 字体补丁已注入且带 base-href 前缀（$BASE_HREF）+ 两个字体文件均在产物内"

echo "=== Build done: $(pwd)/build/web ==="
