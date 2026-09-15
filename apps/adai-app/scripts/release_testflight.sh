#!/usr/bin/env bash
# ============================================================
# AdaiOS iOS → TestFlight 发布脚本（2026-09-15 首版）
#
# 用法：
#   sh scripts/release_testflight.sh                  # 构建 + 导出 IPA + 上传
#   sh scripts/release_testflight.sh --skip-build     # 复用已有 archive，只导出+上传
#   sh scripts/release_testflight.sh --build-number 5 # 指定构建号（默认读 pubspec.yaml 的 +N）
#   sh scripts/release_testflight.sh --export-only    # 只导出 IPA 不上传（先自检签名）
#
# 凭据（App Store Connect API Key = Team Key，权限需 App Manager）：
#   export ASC_KEY_ID=XXXXXXXXXX
#   export ASC_ISSUER_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
#   export ASC_KEY_PATH=~/.appstoreconnect/private_keys/AuthKey_XXXXXXXXXX.p8
#   三者都可省：脚本会在 ~/.appstoreconnect/private_keys/ 自动发现唯一 .p8，
#   并尝试从文件名推断 KEY_ID；ISSUER_ID 无法推断，必须显式给。
#
# ── 为什么走 API Key 而不是「在 Xcode 登录 Apple ID」 ──
# 本机 Xcode 账号列表为空（archive 能成是因为本地有 Apple Development 证书，
# 而 exportArchive 需要分发签名 → 报 `No Accounts` / `No signing certificate "iOS Distribution"`）。
# API Key 认证让 xcodebuild 在**无人值守**下自动签发 Apple Distribution 证书 +
# App Store 描述文件（主 App 与 ShareExtension 两个 App ID 都要），并顺带完成上传。
#
# ── 前置（缺一不可，否则上传会被 Apple 拒）──
#   1. App Store Connect 已签署 Free Apps Agreement（Agreements, Tax, and Banking）
#   2. 已创建 App 记录（My Apps → +），Bundle ID 选 com.adaiadai.adaiApp
#   3. 两个 App ID 都开启了 App Groups 能力（分享扩展用 group.com.adaiadai.adaiApp）
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."
APP_DIR="$(pwd)"

BUNDLE_ID="com.adaiadai.adaiApp"
TEAM_ID="4G3D37YKSB"
API_BASE_URL="https://api.adaiadai.com"

ARCHIVE_PATH="build/ios/archive/Runner.xcarchive"
IPA_DIR="build/ios/ipa"
EXPORT_OPTIONS="build/ios/ExportOptions.plist"

SKIP_BUILD=0
EXPORT_ONLY=0
BUILD_NUMBER=""

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build)   SKIP_BUILD=1 ;;
    --export-only)  EXPORT_ONLY=1 ;;
    --build-number) BUILD_NUMBER="${2:-}"; shift ;;
    -h|--help)      sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数：${1}（-h 看用法）"; exit 1 ;;
  esac
  shift
done

# ── 1. 凭据准备 ──
KEYS_DIR="$HOME/.appstoreconnect/private_keys"
if [ -z "${ASC_KEY_PATH:-}" ]; then
  found=$(ls "$KEYS_DIR"/AuthKey_*.p8 2>/dev/null | head -2 || true)
  count=$(echo "$found" | grep -c . || true)
  if [ "$count" = "1" ]; then
    ASC_KEY_PATH="$found"
    echo "▸ 自动发现密钥：$ASC_KEY_PATH"
  elif [ "$count" -gt 1 ]; then
    echo "❌ $KEYS_DIR 下有多个 .p8，请用 ASC_KEY_PATH 指定用哪个："; echo "$found"; exit 1
  fi
fi
if [ -z "${ASC_KEY_PATH:-}" ] || [ ! -f "$ASC_KEY_PATH" ]; then
  cat << 'EOF'
❌ 找不到 App Store Connect API Key（.p8）

生成步骤（一次性）：
  1. App Store Connect → Users and Access → Integrations → App Store Connect API
  2. Team Keys → ＋ ，权限选 **App Manager**
  3. 下载 .p8（**只能下载一次**），放到 ~/.appstoreconnect/private_keys/
  4. 记下 Key ID；Issuer ID 在该页面顶部（所有 Key 共用）
  5. export ASC_KEY_ID=… ASC_ISSUER_ID=…

说明：没有 Key 也能构建，但本机 Xcode 未登录 Apple ID，
     exportArchive 无法创建分发证书 → 导出 IPA 必失败。
EOF
  exit 1
fi
if [ -z "${ASC_KEY_ID:-}" ]; then
  ASC_KEY_ID="$(basename "$ASC_KEY_PATH" | sed -E 's/^AuthKey_([A-Z0-9]+)\.p8$/\1/')"
  echo "▸ 从文件名推断 Key ID：$ASC_KEY_ID"
fi
if [ -z "${ASC_ISSUER_ID:-}" ]; then
  echo "❌ 缺 ASC_ISSUER_ID（在 App Store Connect → Integrations 页面顶部，所有 Key 共用）"
  exit 1
fi
echo "▸ 凭据就绪：Key ID=$ASC_KEY_ID  Issuer=${ASC_ISSUER_ID:0:8}…"

# ── 2. 构建 archive ──
if [ "$SKIP_BUILD" = "0" ]; then
  echo ""
  echo "▸ 构建 archive（flutter build ipa --release）..."
  if [ -n "$BUILD_NUMBER" ]; then
    echo "  构建号：${BUILD_NUMBER}（覆盖 pubspec.yaml）"
    flutter build ipa --release --build-number "$BUILD_NUMBER" \
      --dart-define=API_BASE_URL="$API_BASE_URL"
  else
    echo "  构建号：读 pubspec.yaml（$(grep '^version:' pubspec.yaml | awk '{print $2}')）"
    flutter build ipa --release --dart-define=API_BASE_URL="$API_BASE_URL"
  fi
  # flutter build ipa 会在 export 阶段失败（本机无分发证书）——那是预期内的：
  # archive 已经产出，导出由下面带 API Key 认证的 xcodebuild 接管。
else
  echo "▸ 跳过构建，复用 $ARCHIVE_PATH"
fi

if [ ! -d "$ARCHIVE_PATH" ]; then
  echo "❌ 没有 archive：${ARCHIVE_PATH}（去掉 --skip-build 重新构建）"
  exit 1
fi

# ── 3. 导出 IPA（带 API Key 认证 → 自动签发分发证书与描述文件）──
mkdir -p "$IPA_DIR"
cat > "$EXPORT_OPTIONS" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>teamID</key>
	<string>${TEAM_ID}</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>uploadSymbols</key>
	<true/>
	<key>destination</key>
	<string>export</string>
</dict>
</plist>
EOF

echo ""
echo "▸ 导出 App Store IPA（自动创建分发证书 + 描述文件）..."
set +e
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE_PATH" \
  -exportOptionsPlist "$EXPORT_OPTIONS" \
  -exportPath "$IPA_DIR" \
  -allowProvisioningUpdates \
  -authenticationKeyPath "$ASC_KEY_PATH" \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID" 2>&1 | tail -30
EXPORT_RC=${PIPESTATUS[0]}
set -e

if [ "$EXPORT_RC" != "0" ]; then
  cat << 'EOF'

❌ 导出失败。常见原因与处置：
  · `No Accounts` + `No signing certificate "iOS Distribution"`：
      说明 API Key 未生效或权限不足 → 回 App Store Connect 把 Key 权限改为 App Manager 重建
  · `No profiles for 'com.adaiadai.adaiApp' were found`：
      App 记录还没建 → My Apps → + 建记录（Bundle ID 选 com.adaiadai.adaiApp）
  · `Cloud signing permission error` / 协议未签：
      Agreements, Tax, and Banking 里签 Free Apps Agreement
  · App Groups 报错：主 App 与 ShareExtension 两个 App ID 都要开启 group.com.adaiadai.adaiApp
EOF
  exit 1
fi

IPA=$(ls -t "$IPA_DIR"/*.ipa 2>/dev/null | head -1)
echo ""
echo "✅ 导出成功：$IPA"
echo "   签名校验："
codesign -dvv "$IPA" 2>/dev/null | grep -E "Authority|Identifier" | head -3 || true

if [ "$EXPORT_ONLY" = "1" ]; then
  echo ""
  echo "（--export-only：跳过上传）"
  exit 0
fi

# ── 4. 上传到 App Store Connect ──
echo ""
echo "▸ 上传到 App Store Connect..."
xcrun altool --upload-app -f "$IPA" -t ios \
  --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID" 2>&1 | tail -20

cat << 'EOF'

✅ 上传动作已完成（Apple 仍需 processing，通常 5~30 分钟）。

接下来：
  1. App Store Connect → TestFlight → 等构建状态从「正在处理」变为可测试
  2. 若卡在处理中：多为出口合规问题（本仓库已在 Info.plist 声明
     ITSAppUsesNonExemptEncryption=false，正常不会再卡）
  3. 内部测试（自己用）**不需要 Apple 审核**，把自己加入 Internal Testing 即可装
  4. 构建 90 天过期；再次上传必须递增构建号（--build-number N）
EOF
