#!/bin/sh
# install_ios.sh — 把已构建的 iOS 产物装到真机（RFC 20260914 分享扩展批配套）。
#
# 为什么单独有这个脚本：分享扩展的验收标准是「**真机上**在 B站分享面板里点一下」，
# 而这一步依赖手机可达（USB 连接或同一网络且已解锁）。`devicectl` 在设备不可达时的
# 报错是 `unable to locate a device matching the requested device identifier`（error 1011），
# 看起来像「设备 ID 写错了」，实际只是没连上——这个脚本用一句人话把它翻译出来。
set -e

cd "$(dirname "$0")/.."
APP="build/ios/iphoneos/Runner.app"

if [ ! -d "$APP" ]; then
  echo "还没构建。先跑："
  echo "  flutter build ios --release --dart-define=API_BASE_URL=https://api.adaiadai.com"
  exit 1
fi

UDID=$(xcrun devicectl list devices 2>/dev/null \
  | awk '/iPhone|iPad/ && $0 !~ /unavailable/ {print $3; exit}' || true)

if [ -z "$UDID" ]; then
  echo "没有可达的 iPhone。请把手机用 USB 连上 Mac、解锁、并在弹窗里点「信任」。"
  echo "（现状：devicectl 里那台显示 unavailable。设了密码锁屏状态下也算不可达。）"
  xcrun devicectl list devices 2>/dev/null | grep -i iphone || true
  exit 1
fi

echo "装到设备：$UDID"
xcrun devicectl device install app --device "$UDID" "$APP"
xcrun devicectl device process launch --device "$UDID" com.adaiadai.adaiApp || true

cat <<'TIP'
装好了。真机验收三步：
  1. 打开阿呆 → 学习页页头「把分享接到阿呆」→ 点「给我一把钥匙」（弹窗应显示「分享面板已就绪」）
  2. B站（或抖音）→ 分享 → 找「阿呆阿呆」（⚠️ 新装的扩展可能先落在「更多」里，不是失败）
  3. 点一下 → 应看到「已交给阿呆，正在读…」约 1 秒自动关，且**主 App 没有被拉起**
看看学习页里有没有出现正在读/新卡片。
TIP
