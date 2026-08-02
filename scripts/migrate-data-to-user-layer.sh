#!/usr/bin/env bash
#
# 数据迁移脚本：data/ → data/default/（多用户架构预留，2026-08-02）
#
# 背景：多用户统一加层后，所有数据路径变为 data/{userId}/...，
#       单用户数据从 data/ 顶层迁入 data/default/。
# 适用：升级 v0.2.0+（含多用户预留）时，本地 data/ 和部署服务器各跑一次。
#
# 安全：幂等（data/default 已存在则跳过）；先自动备份 tar.gz 再迁移。
#
# 用法：cd <monorepo 根> && bash scripts/migrate-data-to-user-layer.sh
#       或传数据根： bash scripts/migrate-data-to-user-layer.sh <data-dir>

set -euo pipefail

# ── 参数 ──
DATA_DIR="${1:-data}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONOREPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DATA_PATH="$MONOREPO_ROOT/$DATA_DIR"

# 需迁移的顶层目录（identity/index/memory/project/records/trading）
DIRS=("identity" "index" "memory" "project" "records" "trading")

echo "==> 数据迁移 | data 根: $DATA_PATH"

if [ ! -d "$DATA_PATH" ]; then
  echo "!! data 目录不存在（$DATA_PATH），跳过"
  exit 0
fi

# ── 幂等检查 ──
if [ -d "$DATA_PATH/default" ]; then
  echo "!! data/default 已存在，跳过迁移（可能已迁移过）"
  exit 0
fi

# 确认没有目录尚未迁移（防御）
for dir in "${DIRS[@]}"; do
  if [ -e "$DATA_PATH/default/$dir" ]; then
    echo "!! data/default/$dir 已存在但 default 根不存在，状态异常，中止"
    exit 1
  fi
done

# ── 备份 ──
BACKUP_FILE="$DATA_PATH-backup-$(date +%Y%m%d-%H%M%S).tar.gz"
echo "==> 备份到: $BACKUP_FILE"
tar -czf "$BACKUP_FILE" -C "$MONOREPO_ROOT" "$DATA_DIR" 2>/dev/null || {
  echo "!! 备份失败，中止迁移（不冒险动数据）"
  exit 1
}

# ── 迁移 ──
mkdir -p "$DATA_PATH/default"
migrated=0
for dir in "${DIRS[@]}"; do
  if [ -d "$DATA_PATH/$dir" ]; then
    mv "$DATA_PATH/$dir" "$DATA_PATH/default/$dir"
    echo "  ✓ data/$dir → data/default/$dir"
    migrated=$((migrated + 1))
  else
    echo "  - data/$dir 不存在，跳过"
  fi
done

echo "==> 迁移完成 | 移动 $migrated 个目录"
echo "    备份文件保留在: ${BACKUP_FILE}（确认无误后可删除）"
echo ""
echo "    验证："
echo "      ls $DATA_PATH/default/records   # 应看到 YYYY/MM 记录"
echo "      git status --short              # data/default 下隐私文件应被 .gitignore 忽略"
