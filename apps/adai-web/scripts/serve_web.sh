#!/bin/bash
# Build Flutter Web + apply local Font patches + serve via Python
# Usage: sh scripts/serve_web.sh [API_BASE_URL] [--build-only]
#   --build-only（2026-09-13 补）：只构建 + 打补丁，不起本地服务器——
#     部署流程（tar 到 /opt/adaios/web）需要一个「构建完就退出」的入口；
#     没有它时部署只能把下面两步 perl 补丁复制进部署命令，等于补丁两份（会漂移）。
#     放在第 1 或第 2 个位置都可（下面解析时先摘掉它）。
# 渲染模式：JS + CanvasKit（不用 --wasm）——2026-08-22 线上白屏根因修复：
#   wasm 双模式（skwasm）产物带 --import-shared-memory，依赖 SharedArrayBuffer，
#   而浏览器只在 HTTPS（或 localhost）下才信任 COOP/COEP 头 → 纯 IP/HTTP 访问
#   wasm 永远无法实例化 → 白屏 + 页面重载。JS 模式无需 COOP/COEP，纯 IP 可跑。
#   代价：main.dart.js 3.1M + canvaskit.wasm 6.9M（gzip 后合计 ~4M）。
# 下方 bootstrap.js 补丁注入 canvasKitBaseUrl 让 canvaskit 从本地加载（CDN gstatic 被墙）。

set -e

cd "$(dirname "$0")/.."

# 摘出 --build-only（可在任意位置）
BUILD_ONLY=0
ARGS=()
for a in "$@"; do
  if [ "$a" = "--build-only" ]; then BUILD_ONLY=1; else ARGS+=("$a"); fi
done

# 可选参数：API_BASE_URL（连生产后端时传入，如 https://api.adaiadai.com）
API_BASE_URL="${ARGS[0]:-}"

# 2026-10-05（生产事故防复发）：`--build-only` 是生产构建路径，而 API_BASE_URL 是可选参数——
# 不传时脚本**完全不带 --dart-define**，Flutter 会静默用代码默认值 http://localhost:8080，
# 构建照常成功、部署后**用户端登录直接 ERR_CONNECTION_REFUSED**（2026-10-05 真实事故）。
# 故在生产构建路径上 fail-closed：要么显式给生产地址，要么显式声明本地。
if [ -z "$API_BASE_URL" ] && [ "${BUILD_ONLY:-0}" = "1" ]; then
  echo "❌ --build-only 未传 API_BASE_URL：产物会连 http://localhost:8080（部署到生产＝用户登录不了）" >&2
  echo "   生产构建：sh scripts/serve_web.sh https://api.adaiadai.com --build-only" >&2
  echo "   本地预览：sh scripts/serve_web.sh http://localhost:8080 --build-only" >&2
  exit 1
fi

echo "=== Building Flutter Web (JS + CanvasKit) ==="

# 2026-10-07 补（本地起不来过一次的真实原因）：`web/fonts/` 是**不入库的手工资产**
# （见 .gitignore `/web/fonts/ # Local fonts (需手动放置，不入库)`）。下面的 index.html 补丁会把
# 所有 fonts.gstatic.com 请求改写到本地 /fonts/*.woff2（国内访问不了 Google 字体 CDN）——
# 字体文件不在时，改写后**必然 404**：页面能开，但中文全缺 + 控制台一排 404，
# 很容易被误判成"代码坏了"。这里显式拦住，并给出从生产补齐的命令。
FONT_DIR="web/fonts"
for f in Roboto.woff2 NotoSansSC-Subset.woff2; do
  if [ ! -f "$FONT_DIR/$f" ]; then
    echo "❌ 缺本地字体 $FONT_DIR/$f —— 它是手工资产、不入库。" >&2
    echo "   不补的话：index.html 的字体改写补丁会把 fonts.gstatic.com 指向本地，改完 404（中文全缺）。" >&2
    echo "   从生产补齐（需 ssh 到 82.156.111.146）：" >&2
    echo "     mkdir -p $FONT_DIR && ssh ubuntu@82.156.111.146 'sudo tar -C /opt/adaios/web -cf - fonts' | tar -xf - -C web/" >&2
    exit 1
  fi
done
if [ -n "$API_BASE_URL" ]; then
  flutter build web --no-tree-shake-icons --dart-define=API_BASE_URL=$API_BASE_URL
else
  flutter build web --no-tree-shake-icons
fi

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

if [ "$BUILD_ONLY" = "1" ]; then
  echo "=== --build-only：构建 + 补丁完成，产物在 $(pwd)/build/web（未起服务器）==="
  exit 0
fi

echo "=== Starting server at http://localhost:8082 ==="
cd build/web && python3 -m http.server 8082
