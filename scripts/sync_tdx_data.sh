#!/usr/bin/env bash
# =============================================================================
# sync_tdx_data.sh — 通达信盘后数据同步（2026-08-30 建议 #6）
#
# 用途：用户在 Windows 通达信「盘后数据下载」后，把 vipdoc/sh/lday + sz/lday
#       打包（7z/zip）→ 本脚本解压并按文件名前缀分流到 data/market/tdx/。
#
# 用法：sh scripts/sync_tdx_data.sh <打包文件...>
#       例：sh scripts/sync_tdx_data.sh ~/Desktop/sh_lday.7z ~/Desktop/sz_lday.7z
#
# 分流规则：解压后把 lday/ 下的文件按前缀归位——sh*.day → tdx/sh/lday/、
#           sz*.day → tdx/sz/lday/（兼容包内 `lday/` 混装 或 `vipdoc/sh/lday/` 嵌套）。
# 依赖：7z 用 py7zr（pip install py7zr -i 清华源），zip 用系统 unzip。
# 同步后：复权因子表按日 TTL 自动刷新；无需重启后端。
# =============================================================================
set -euo pipefail

TDX_DIR="data/market/tdx"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [ "$#" -eq 0 ]; then
  echo "用法: sh scripts/sync_tdx_data.sh <sh包.7z|zip> [sz包.7z|zip] ..."
  exit 1
fi

for pkg in "$@"; do
  if [ ! -f "$pkg" ]; then
    echo "❌ 文件不存在: $pkg"
    exit 1
  fi
  echo "=== 解压 $pkg → 临时目录 ==="
  case "$pkg" in
    *.zip) unzip -o -q "$pkg" -d "$TMP_DIR" ;;
    *.7z)
      python3 - "$pkg" "$TMP_DIR" <<'PY'
import py7zr, sys
with py7zr.SevenZipFile(sys.argv[1]) as z:
    z.extractall(path=sys.argv[2])
PY
      ;;
    *)
      echo "❌ 不支持的格式（需 .7z 或 .zip）: $pkg"
      exit 1
      ;;
  esac
done

# 按文件名前缀分流（sh/sz）到对应市场目录
mkdir -p "$TDX_DIR/sh/lday" "$TDX_DIR/sz/lday"
MOVED=0
for f in $(find "$TMP_DIR" -name "*.day"); do
  base=$(basename "$f")
  case "$base" in
    sh*.day) cp "$f" "$TDX_DIR/sh/lday/$base" ;;
    sz*.day) cp "$f" "$TDX_DIR/sz/lday/$base" ;;
    *) continue ;;
  esac
  MOVED=$((MOVED + 1))
done

COUNT=$(find "$TDX_DIR" -name "*.day" | wc -l | tr -d ' ')
echo "=== 同步完成：新增/覆盖 $MOVED 个 .day，当前共 $COUNT 个 ==="
echo "提示：复权因子按日 TTL 自动刷新；抽查可用 python3 ai-engineering/09-scripts/verify-tdx-data.py"
