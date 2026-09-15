#!/usr/bin/env bash
# ============================================================
# AdaiOS iOS → TestFlight 发布脚本（2026-09-15 打通版）
#
# 用法：
#   sh scripts/release_testflight.sh                  # 构建 + 导出 IPA + 上传
#   sh scripts/release_testflight.sh --skip-build     # 复用已有 archive，只导出+上传
#   sh scripts/release_testflight.sh --build-number 5 # 指定构建号（默认读 pubspec.yaml 的 +N）
#   sh scripts/release_testflight.sh --export-only    # 只导出 IPA 不上传（先自检签名）
#
# 凭据（App Store Connect API Key = **Team Key**）：
#   export ASC_ISSUER_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
#   （ASC_KEY_ID / ASC_KEY_PATH 可省：从 ~/.appstoreconnect/private_keys/ 自动发现）
#
# ── 为什么走「API 建证书 + 本地手动签名」而不是 Xcode 云签名（2026-09-15 实测）──
# 云签名（-allowProvisioningUpdates + API Key）在 Key 为 App Manager 角色时报：
#     Cloud signing permission error
#     「You haven't been given access to cloud-managed distribution certificates.
#       Please contact your team's Account Holder or an Admin.」
# 云托管分发证书要求 **Admin** 角色；但 App Store Connect API **本身**允许
# App Manager 直接创建证书与描述文件 → 于是绕开云签名：
#     asc_signing.py 建证书/profile 并装进钥匙串 → xcodebuild 用 manual 签名导出。
# 全程无需 Admin、无需在 Xcode 里登录 Apple ID。
#
# ── 前置（缺一不可，否则 Apple 侧会拒）──
#   1. App Store Connect 已签 Free Apps Agreement（Agreements, Tax, and Banking）
#   2. 已建 App 记录（My Apps），Bundle ID = com.adaiadai.adaiApp
#   3. 两个 App ID 都开了 App Groups（group.com.adaiadai.adaiApp）——分享扩展需要
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."

TEAM_ID="4G3D37YKSB"
API_BASE_URL="https://api.adaiadai.com"
ARCHIVE_PATH="build/ios/archive/Runner.xcarchive"
IPA_DIR="build/ios/ipa"
EXPORT_OPTIONS="build/ios/ExportOptions-manual.plist"

SKIP_BUILD=0
EXPORT_ONLY=0
BUILD_NUMBER=""

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build)   SKIP_BUILD=1 ;;
    --export-only)  EXPORT_ONLY=1 ;;
    --build-number) BUILD_NUMBER="${2:-}"; shift ;;
    -h|--help)      sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "未知参数：${1}（-h 看用法）"; exit 1 ;;
  esac
  shift
done

# ── 1. 凭据与签名资产 ──
if [ -z "${ASC_ISSUER_ID:-}" ]; then
  cat << 'EOF'
❌ 缺 ASC_ISSUER_ID（App Store Connect → Users and Access → Integrations 页面顶部）

一次性准备：
  1. 生成 Team Key（角色 App Manager 即可，**不需要 Admin**），下载 .p8
  2. 放到 ~/.appstoreconnect/private_keys/（文件名保持 AuthKey_<KEYID>.p8）
  3. export ASC_ISSUER_ID=<Issuer ID>
EOF
  exit 1
fi

echo "▸ 准备分发签名资产（幂等）..."
python3 scripts/asc_signing.py --ensure

# ── 2. 构建 archive ──
if [ "$SKIP_BUILD" = "0" ]; then
  echo ""
  echo "▸ 构建 archive..."
  rm -rf build/ios/archive
  # flutter build ipa 的 export 阶段必然失败（本机 Xcode 未登录账号 → 云签名被拒），
  # 但 archive 已经产出——分发签名由本脚本用 API 建好的证书/profile 接管，故此处容忍失败。
  set +e
  if [ -n "$BUILD_NUMBER" ]; then
    echo "  构建号：${BUILD_NUMBER}（覆盖 pubspec.yaml）"
    flutter build ipa --release --build-number "$BUILD_NUMBER" \
      --dart-define=API_BASE_URL="$API_BASE_URL" 2>&1 | tail -12
  else
    echo "  构建号：读 pubspec.yaml（$(grep '^version:' pubspec.yaml | awk '{print $2}')）"
    flutter build ipa --release --dart-define=API_BASE_URL="$API_BASE_URL" 2>&1 | tail -12
  fi
  set -e
  if [ ! -d "$ARCHIVE_PATH" ]; then
    echo "❌ archive 未产出（${ARCHIVE_PATH} 不存在）——上面 flutter 输出里应有真实原因"
    exit 1
  fi
  echo "  ✓ archive 就绪（export 阶段的失败是预期内的，已由本脚本接管）"
else
  echo "▸ 跳过构建，复用 ${ARCHIVE_PATH}"
  if [ ! -d "$ARCHIVE_PATH" ]; then
    echo "❌ 没有 archive：${ARCHIVE_PATH}（去掉 --skip-build 重新构建）"
    exit 1
  fi
fi

# ── 3. 生成 ExportOptions（手动签名，bundleId → profile 名称取自 asc_signing.py）──
python3 - << 'PYEOF' > "$EXPORT_OPTIONS"
import json, pathlib
m = json.loads((pathlib.Path.home() / ".appstoreconnect/dist/profiles.json").read_text())
print('<?xml version="1.0" encoding="UTF-8"?>')
print('<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">')
print('<plist version="1.0">\n<dict>')
print('\t<key>method</key>\n\t<string>app-store-connect</string>')
print('\t<key>teamID</key>\n\t<string>4G3D37YKSB</string>')
print('\t<key>signingStyle</key>\n\t<string>manual</string>')
print('\t<key>uploadSymbols</key>\n\t<true/>')
print('\t<key>destination</key>\n\t<string>export</string>')
print('\t<key>provisioningProfiles</key>\n\t<dict>')
for ident, name in m.items():
    print(f'\t\t<key>{ident}</key>\n\t\t<string>{name}</string>')
print('\t</dict>\n</dict>\n</plist>')
PYEOF

echo ""
echo "▸ 导出 App Store IPA（手动签名）..."
rm -rf "$IPA_DIR" && mkdir -p "$IPA_DIR"
set +e
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE_PATH" \
  -exportOptionsPlist "$EXPORT_OPTIONS" \
  -exportPath "$IPA_DIR" 2>&1 | tail -15
EXPORT_RC=${PIPESTATUS[0]}
set -e

IPA=$(ls -t "$IPA_DIR"/*.ipa 2>/dev/null | head -1)
if [ "$EXPORT_RC" != "0" ] || [ -z "$IPA" ]; then
  cat << 'EOF'

❌ 导出失败。常见原因：
  · `No profiles for '…' were found` → asc_signing.py 没跑成功，或 profile 未落到
    ~/Library/MobileDevice/Provisioning Profiles/
  · `No signing certificate "iOS Distribution" found` → 证书没导入钥匙串
    （重跑 python3 scripts/asc_signing.py --ensure；注意 p12 必须用 openssl -legacy 导出）
  · entitlements 不匹配 → 确认两个 App ID 都开了 App Groups
EOF
  exit 1
fi

echo ""
echo "✅ 导出成功：$IPA"
codesign -dvv "$IPA" 2>/dev/null | head -1 || true
echo "   主 App 签名："
unzip -q -o "$IPA" -d /tmp/adai_ipa_check 2>/dev/null || true
codesign -dvv /tmp/adai_ipa_check/Payload/Runner.app 2>&1 | grep -E "Authority=iPhone Distribution" | sed 's/^/     /' || true
rm -rf /tmp/adai_ipa_check

if [ "$EXPORT_ONLY" = "1" ]; then
  echo ""
  echo "（--export-only：跳过上传）"
  exit 0
fi

# ── 4. 上传 ──
ASC_KEY_ID="${ASC_KEY_ID:-$(basename "${ASC_KEY_PATH:-}" 2>/dev/null | sed -E 's/^AuthKey_([A-Z0-9]+)\.p8$/\1/')}"
if [ -z "$ASC_KEY_ID" ]; then
  ASC_KEY_ID=$(basename "$(ls ~/.appstoreconnect/private_keys/AuthKey_*.p8 2>/dev/null | head -1)" | sed -E 's/^AuthKey_([A-Z0-9]+)\.p8$/\1/')
fi

echo ""
echo "▸ 上传到 App Store Connect（Key ${ASC_KEY_ID}）..."
xcrun altool --upload-app -f "$IPA" -t ios \
  --apiKey "${ASC_KEY_ID}" --apiIssuer "${ASC_ISSUER_ID}" 2>&1 | tail -12

cat << 'EOF'

✅ 上传动作已完成（Apple 仍需 processing，通常 5~30 分钟）。

接下来：
  1. App Store Connect → TestFlight → 等构建状态变为可测试
  2. 首次使用：TestFlight → Internal Testing 里把自己（Apple ID）加成测试员
  3. 手机装 App Store 的 TestFlight，收到邀请后即可安装
  4. 构建 90 天过期；再次上传必须递增构建号（--build-number N）
EOF
