#!/bin/bash
# Build Flutter Web + apply local CanvasKit + font patches + serve
# Usage: sh scripts/serve_web.sh

set -e

cd "$(dirname "$0")/.."

echo "=== Building Flutter Web ==="
flutter build web --no-tree-shake-icons

echo "=== Applying local patches ==="
# Copy CanvasKit
cp -r web/canvaskit build/web/canvaskit

# Copy Chinese font
cp "web/fonts/Hiragino Sans GB.ttc" build/web/fonts/HiraginoSans.ttc
rm -f build/web/fonts/Hiragino\ Sans\ GB.ttc build/web/fonts/NotoSansSC.woff2 build/web/fonts/Roboto.woff2

# Patch flutter_bootstrap.js: add canvasKitBaseUrl
sed -i '' 's/_flutter.loader.load({/_flutter.loader.load({\
  config: {\
    canvasKitBaseUrl: "canvaskit\/"\
  },/' build/web/flutter_bootstrap.js

# Patch index.html: add fetch interceptor for blocked domains
INDEX="build/web/index.html"
# Insert interceptor script before flutter_bootstrap.js
sed -i '' 's/<script src="flutter_bootstrap.js" async><\/script>/<script>\
    var origFetch=window.fetch.bind(window);\
    window.fetch=function(url,opts){\
      if(typeof url==="string"){\
        if(url.includes("fonts.gstatic.com"))return origFetch("\/fonts\/HiraginoSans.ttc");\
        if(url.includes("www.gstatic.com\/flutter-canvaskit"))return origFetch("\/canvaskit\/"+url.split("\x2f").pop());\
      }\
      return origFetch(url,opts);\
    };\
  <\/script>\
  <script src="flutter_bootstrap.js" async><\/script>/' "$INDEX"

echo "=== Starting server at http://localhost:8081 ==="
cd build/web && python3 -m http.server 8081
