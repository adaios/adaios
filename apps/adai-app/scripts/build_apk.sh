#!/bin/bash
# Build Flutter Android release APK（正式签名）+ 验签，供侧载分发。
#
# Usage: sh scripts/build_apk.sh [API_BASE_URL]
#   API_BASE_URL  连生产后端时传入（默认 https://api.adaiadai.com）
#
# D6-A（2026-09-13 侧载批）：本脚本是 Android 包的**唯一正规出口**。
#   签名来自 android/key.properties（不进 git），android/app/build.gradle 强制使用它；
#   缺配置时构建直接失败，**不回退 debug 签名**——debug 签名的包不能分发给他人，
#   且一旦发出去，将来换正式签名无法覆盖升级（对方必须卸载重装 = 数据丢失）。
#   ⚠️ keystore（android/adaios-release.jks）+ key.properties 必须离线备份，丢了不可找回。
#
# 侧载流程：把 APK 发给对方 → 手机允许「安装未知应用」→ 安装。
#   以后更新：**必须用同一个 keystore 签名的包**才能覆盖安装，否则要先卸载（数据会丢）。

set -e

cd "$(dirname "$0")/.."

API_BASE_URL="${1:-https://api.adaiadai.com}"
KEY_PROPERTIES="android/key.properties"

# ── 前置①：签名配置在（缺则人话报错，不让构建跑到一半才炸）──
if [ ! -f "$KEY_PROPERTIES" ]; then
  echo "ERROR: 缺少 $KEY_PROPERTIES —— Android 发布包需要正式签名。"
  echo "  请从离线备份恢复 keystore 与 key.properties（两者缺一不可），再重试。"
  echo "  ⚠️ 已有正式 keystore 时**不要重新生成**：签名一变，已分发的旧包再也无法覆盖升级。"
  exit 1
fi

# ── 前置②：keystore 本体在（key.properties 在、密钥没了 = 构建会炸在半路）──
# storeFile 相对 android/app/ 解析，与 build.gradle 同口径；密码只去 \r（不 trim 其它字符）
STORE_FILE=$(grep -E '^storeFile=' "$KEY_PROPERTIES" | cut -d= -f2- | tr -d '\r')
STORE_PASS=$(grep -E '^storePassword=' "$KEY_PROPERTIES" | cut -d= -f2- | tr -d '\r')
KEY_ALIAS=$(grep -E '^keyAlias=' "$KEY_PROPERTIES" | cut -d= -f2- | tr -d '\r')
KEYSTORE="android/app/$STORE_FILE"
if [ ! -f "$KEYSTORE" ]; then
  echo "ERROR: key.properties 指向的 keystore 不存在：$KEYSTORE"
  echo "  从离线备份恢复它；只有在确认**没有任何已分发的旧包**时，才重新生成。"
  exit 1
fi

echo "=== Building Flutter Android release APK (API_BASE_URL=$API_BASE_URL) ==="
flutter build apk --release --dart-define=API_BASE_URL="$API_BASE_URL"

APK="build/app/outputs/flutter-apk/app-release.apk"
if [ ! -f "$APK" ]; then
  echo "ERROR: 未找到产物 $APK"
  exit 1
fi

# ── 验签：产物必须是**正式证书**签的 ──
# 期望指纹从 keystore 直接读（唯一真源），与实际签名对拍。
# 防的是「signingConfig 被改回 debug 却没人发现」——那产出的包现在装得上，
# 但将来换正式签名无法覆盖升级（最坏的失败方式：不是装不上，而是以后更新要卸载）。
# P2-构建1（2026-09-14 晚间批）：原先 `command -v keytool || echo "$JAVA_HOME/bin/keytool"` 在
# JAVA_HOME 未设时退化成 `/bin/keytool`（不存在）→ 指纹读不到 → 走 WARN 分支 exit 0 并照样提示
# 「把该 APK 发给对方」。这与「绝不回退 debug 签名」的承诺相反，且发错签名的包将来只能卸载重装
# （丢数据）。这里改 fail-closed：工具找不到就不产出可分发结论。
KEYTOOL=$(command -v keytool 2>/dev/null || true)
if [ -z "$KEYTOOL" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
fi
if [ -z "$KEYTOOL" ] || [ ! -x "$KEYTOOL" ]; then
  echo "ERROR: 找不到 keytool（JAVA_HOME=${JAVA_HOME:-未设置}）——无法验签。"
  echo "  按纪律 **fail-closed**：本次构建不输出可分发结论。设好 JAVA_HOME 或把 keytool 放进 PATH 后重跑。"
  exit 1
fi
EXPECTED_FP=$("$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass "$STORE_PASS" -alias "$KEY_ALIAS" 2>/dev/null \
  | awk -F'SHA256: ' '/SHA256:/{print $2; exit}' | tr -d ' :' | tr 'A-Z' 'a-z')

find_apksigner() {
  local dirs=() p sdk_dir
  [ -n "$ANDROID_HOME" ] && dirs+=("$ANDROID_HOME")
  sdk_dir=$(grep -E '^sdk\.dir=' android/local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r')
  [ -n "$sdk_dir" ] && dirs+=("$sdk_dir")
  dirs+=("$HOME/Library/Android/sdk")
  for d in "${dirs[@]}"; do
    p=$(ls -1 "$d"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
    [ -n "$p" ] && { echo "$p"; return 0; }
  done
  return 1
}

APKSIGNER=$(find_apksigner || true)
if [ -n "$APKSIGNER" ] && [ -n "$EXPECTED_FP" ]; then
  ACTUAL_FP=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null \
    | awk -F'SHA-256 digest: ' '/SHA-256 digest/{print $2; exit}' | tr -d ' :' | tr 'A-Z' 'a-z')
  if [ "$ACTUAL_FP" != "$EXPECTED_FP" ]; then
    echo "ERROR: APK 签名与 keystore 不一致！"
    echo "  期望 $EXPECTED_FP"
    echo "  实际 ${ACTUAL_FP:-<读不到>}"
    echo "  多半是 build.gradle 的 signingConfig 被改回了 debug。**不要分发这个包**——"
    echo "  它现在装得上，但将来换正式签名无法覆盖升级（对方要卸载重装，数据全丢）。"
    exit 1
  fi
  echo "OK: 签名校验通过（SHA-256 ${EXPECTED_FP}）——正式证书，可覆盖升级"
else
  # P2-构建1：验签不可得 = 不知道这个包是不是正式证书签的。不知道就不能说「可以发给对方」。
  echo "ERROR: 无法验签（apksigner=${APKSIGNER:-未找到} / 期望指纹=${EXPECTED_FP:-读不到}）——"
  echo "  按纪律 **fail-closed**：不输出分发提示，本次 APK 请勿分发。"
  echo "  期望证书 SHA-256: ${EXPECTED_FP:-<读不到>}"
  echo "  手工验签：\$ANDROID_HOME/build-tools/*/apksigner verify --print-certs $APK"
  exit 1
fi

echo "=== Build done（签名已校验，可分发）==="
echo "产物：$(pwd)/${APK}（$(ls -lh "$APK" | awk '{print $5}')）"
echo "sha256：$(shasum -a 256 "$APK" | awk '{print $1}')"
echo "侧载：把该 APK 发给对方 → 手机允许「安装未知应用」→ 安装（以后更新用同一 keystore 的包覆盖）"
