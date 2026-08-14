#!/usr/bin/env bash
#
# 生产数据定期备份脚本（服务器到期迁移用，2026-08-14）
#
# 背景：生产服务器 49.235.37.220 于 2026-08-19 左右到期，到期前数据仍可能增长，
#       定期快照到本地，防服务器提前故障丢数据。新服务器就绪后解压还原即可。
#
# 备份内容：
#   - data/（个人数据：records/memory/ai-logs/identity/trading/index/accounts）— 不可重建
#   - backend/.env（DEEPSEEK_API_KEY / ADAI_ADMIN_TOKEN，只存在服务器）— 不可重建
#   - backend/adai-core.jar（线上精确版本，可从仓库重建但便宜）
#   - web/ admin/（前端静态产物，可重建）→ 仅 --full 时包含
#   - .deploy-token / .last_build_id（部署状态）
#
# 模式：
#   默认（快备）：data + .env + jar，~13M，约 30-60 秒（每天定期跑）
#   --full（全量）：再加 web/admin 静态产物，~137M，约 5-8 分钟（首次/必要时）
#
# 校验：两端 checksum 一致 + 归档可完整读取，任一失败退出非 0。
# 安全：备份在仓库外（$HOME/backups/adaios-prod/），因含密钥，绝不进 git。
#
# 用法：
#   bash scripts/backup_prod.sh            # 快备（每天定期用）
#   bash scripts/backup_prod.sh --full     # 全量（首次/含前端产物）

set -euo pipefail

SERVER="49.235.37.220"
REMOTE="/opt/adaios"
SSH_OPTS=(-o ConnectTimeout=15)
DEST_ROOT="${BACKUP_ROOT:-$HOME/backups/adaios-prod}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$DEST_ROOT/$STAMP"
REMOTE_TAR="/tmp/adaios-prod-$STAMP.tar.gz"

FULL=0
if [ "${1:-}" = "--full" ]; then FULL=1; fi

mkdir -p "$BACKUP_DIR"

# ── 服务器打包 ──
EXCLUDES=(--exclude='*/.bak-*' --exclude='data.bak-*' --exclude='data-backup-*.tar.gz' --exclude='._*')
INCLUDE=(data backend/.env backend/adai-core.jar .deploy-token .last_build_id)
if [ "$FULL" = 1 ]; then
  INCLUDE+=(web admin)
  echo "==> 全量备份 | $SERVER → $BACKUP_DIR"
else
  echo "==> 快备（data + .env + jar）| $SERVER → $BACKUP_DIR"
fi

echo "  1/3  服务器打包..."
ssh "${SSH_OPTS[@]}" "root@$SERVER" "cd $REMOTE && tar czf $REMOTE_TAR ${EXCLUDES[*]} ${INCLUDE[*]}"

echo "  2/3  拉回本地..."
scp "${SSH_OPTS[@]}" "root@$SERVER:$REMOTE_TAR" "$BACKUP_DIR/"

echo "  3/3  校验..."
REMOTE_MD5="$(ssh "${SSH_OPTS[@]}" "root@$SERVER" "md5sum $REMOTE_TAR | cut -d' ' -f1")"
LOCAL_MD5="$(md5 -q "$BACKUP_DIR/adaios-prod-$STAMP.tar.gz")"
if [ "$REMOTE_MD5" != "$LOCAL_MD5" ]; then
  echo "!! checksum 不一致（本地 $LOCAL_MD5 ≠ 服务器 $REMOTE_MD5），备份中止"
  exit 1
fi
echo "  ✓ checksum 一致: $LOCAL_MD5"

FILE_COUNT="$(tar tzf "$BACKUP_DIR/adaios-prod-$STAMP.tar.gz" | wc -l | tr -d ' ')"
tar tzf "$BACKUP_DIR/adaios-prod-$STAMP.tar.gz" > /dev/null
echo "  ✓ 归档可完整读取（$FILE_COUNT 文件）"

echo ""
echo "✅ 备份完成: $BACKUP_DIR/adaios-prod-$STAMP.tar.gz"
echo "   服务器副本保留: root@$SERVER:${REMOTE_TAR}（可自行清理）"
