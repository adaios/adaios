#!/bin/bash
# Build Flutter Web + apply local Font patches + serve via Python
# Usage: sh scripts/serve_web.sh
# Note: Flutter 3.44+ no longer supports --web-renderer flag.
#       CanvasKit is built-in; the bootstrap.js patch below
#       sets canvasKitBaseUrl so WASM loads from local dir
#       instead of from CDN (blocked in China).

set -e

cd "$(dirname "$0")/.."

echo "=== Building Flutter Web ==="
# --source-maps: 生成 main.dart.js.map，浏览器报错堆栈映射回 Dart 源码行号（release minify 后也能定位）
flutter build web --no-tree-shake-icons --source-maps

echo "=== Applying local patches ==="
# Patch flutter_bootstrap.js: add canvasKitBaseUrl to load local WASM
perl -i -pe 's/(_flutter\.loader\.load\(\{)/$1\n  config: {\n    canvasKitBaseUrl: "canvaskit\/"\n  },/' build/web/flutter_bootstrap.js

# Patch index.html: add fetch interceptor for blocked font CDN
# Routes fonts.gstatic.com requests to local VALID fonts：
#   Roboto → Roboto.woff2（拉丁）；中文（Noto Sans SC 等）→ Hiragino Sans GB.ttc
INDEX="build/web/index.html"
perl -i -pe 's{<script src="flutter_bootstrap.js" async></script>}{<script>var origFetch=window.fetch.bind(window);window.fetch=function(url,opts){if(typeof url==="string"&&url.includes("fonts.gstatic.com")){if(url.includes("roboto"))return origFetch("\/fonts\/Roboto.woff2");return origFetch("\/fonts\/Hiragino%20Sans%20GB.ttc");}return origFetch(url,opts);};<\/script>\n  <script src="flutter_bootstrap.js" async><\/script>}' "$INDEX"

echo "=== Starting server at http://localhost:8083 ==="
cd build/web && python -m http.server 8083
