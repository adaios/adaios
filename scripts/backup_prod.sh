#!/usr/bin/env bash
#
# 生产数据定期备份脚本（服务器迁移/到期用，2026-08-14 创建，2026-08-20 更新，
#                                2026-09-14 修复登录用户：root@ → ubuntu@ + sudo）
#
# 背景：生产服务器 82.156.111.146（2026-08-19 从 49.235.37.220 迁移），
#       定期快照到本地，防服务器故障丢数据。新服务器就绪后解压还原即可。
#
# 备份内容：
#   - data/（个人数据：records/memory/ai-logs/identity/trading/learn/index/accounts）— 不可重建
#     ⚠️ 快备**排除 data/market**（2026-09-14 实测 315MB / data 总 343MB）：那是全 A .day 行情包，
#        属全局公共资产、非用户隐私，且 admin「系统→维护」可重新上传（scripts/sync_tdx_data.sh 同源）。
#        含它会让「快备」从秒级变成 4~5 分钟、14 天占 3GB+。真正不可重建的个人数据只有 ~28MB。
#        需要连行情一起备 → 用 --full。
#   - os/（Domain OS 知识资产：trading-engine/life-os/project-os）— git 可重建但含服务器运行时副本
#   - backend/.env（DEEPSEEK_API_KEY / ADAI_SMOKE_ACCOUNT / ADAI_SMOKE_PASSWORD，只存在服务器）— 不可重建（ADAI_ADMIN_TOKEN 已于 2026-09-02 #178 退役）
#   - backend/adai-core.jar（线上精确版本，可从仓库重建但便宜）
#   - web/ admin/（前端静态产物，可重建）→ 仅 --full 时包含
#   - .deploy-token / .last_build_id（部署状态）
#
# 模式：
#   默认（快备）：个人 data（不含行情包）+ os + .env + jar，约 10~30 秒（每天 21:10 定时跑）
#   --full（全量）：再加 data/market + web/admin 静态产物，约 5-8 分钟（首次/必要时）
#
# 校验：两端 checksum 一致 + 归档可完整读取，任一失败退出非 0。
# 安全：备份在仓库外（$HOME/backups/adaios-prod/），因含密钥，绝不进 git。
#
# 用法：
#   bash scripts/backup_prod.sh            # 快备（每天定期用）
#   bash scripts/backup_prod.sh --full     # 全量（首次/含前端产物）
#
# 免密前提（勿改）：服务器只允许 **ubuntu@** + ~/.ssh/id_ed25519 登录，读 /opt/adaios 需 sudo。
#   （2026-09-14 实测：root@ 登录被拒 → 脚本此前完全跑不通；这是「26 天没备份」的第二个死因）
# 定时：由 LaunchAgent `com.adai.adaios-backup` 每天 21:10 触发
#   （2026-09-14 加，此前 crontab 被 macOS TCC 拦截，从未真正跑过）

set -euo pipefail

SERVER="82.156.111.146"
REMOTE="/opt/adaios"
SSH_USER="ubuntu"
# BatchMode=yes：无人值守时不要卡在密码提示上，直接失败（launchd 下没有 tty）
SSH_OPTS=(-o ConnectTimeout=15 -o BatchMode=yes)
DEST_ROOT="${BACKUP_ROOT:-$HOME/backups/adaios-prod}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$DEST_ROOT/$STAMP"
REMOTE_TAR="/tmp/adaios-prod-$STAMP.tar.gz"

FULL=0
if [ "${1:-}" = "--full" ]; then FULL=1; fi

mkdir -p "$BACKUP_DIR"

# ── 服务器打包 ──
EXCLUDES=(--exclude='*/.bak-*' --exclude='data.bak-*' --exclude='data-backup-*.tar.gz' --exclude='._*' --exclude='.DS_Store')
INCLUDE=(data os backend/.env backend/adai-core.jar .deploy-token .last_build_id)
if [ "$FULL" = 1 ]; then
  INCLUDE+=(web admin)
  echo "==> 全量备份 | $SERVER → $BACKUP_DIR"
else
  # 快备排除可重建的行情包（见文件头说明）——315MB → 实际个人数据 ~28MB
  EXCLUDES+=(--exclude='data/market' --exclude='data/market/*')
  echo "==> 快备（个人 data + os + .env + jar，不含行情包）| $SERVER → $BACKUP_DIR"
fi

echo "  1/3  服务器打包..."
# tar 以 sudo 读 /opt/adaios（属主 adaios，ubuntu 直读不到），打包后 chown 给 ubuntu 供 scp 拉取
ssh "${SSH_OPTS[@]}" "$SSH_USER@$SERVER" \
  "sudo tar czf $REMOTE_TAR -C $REMOTE ${EXCLUDES[*]} ${INCLUDE[*]} && sudo chown $SSH_USER:$SSH_USER $REMOTE_TAR"

echo "  2/3  拉回本地..."
scp "${SSH_OPTS[@]}" "$SSH_USER@$SERVER:$REMOTE_TAR" "$BACKUP_DIR/"

echo "  3/3  校验..."
REMOTE_MD5="$(ssh "${SSH_OPTS[@]}" "$SSH_USER@$SERVER" "md5sum $REMOTE_TAR | cut -d' ' -f1")"
LOCAL_MD5="$(md5 -q "$BACKUP_DIR/adaios-prod-$STAMP.tar.gz")"
if [ "$REMOTE_MD5" != "$LOCAL_MD5" ]; then
  echo "!! checksum 不一致（本地 ${LOCAL_MD5} ≠ 服务器 ${REMOTE_MD5}），备份中止"
  # 关键：删掉这次的不完整副本。否则「失败的备份」会以「一份新鲜备份」的样子躺在
  # ~/backups 里，新鲜度自检（setup-launchd.sh --check / guard-tools T7）反而报绿灯——
  # 那是比「没备份」更危险的假信号（2026-09-14 实测踩到）。
  rm -rf "$BACKUP_DIR"
  echo "   已移除不完整副本: ${BACKUP_DIR}"
  exit 1
fi
echo "  ✓ checksum 一致: $LOCAL_MD5"

FILE_COUNT="$(tar tzf "$BACKUP_DIR/adaios-prod-$STAMP.tar.gz" | wc -l | tr -d ' ')"
tar tzf "$BACKUP_DIR/adaios-prod-$STAMP.tar.gz" > /dev/null
echo "  ✓ 归档可完整读取（$FILE_COUNT 文件）"

# 服务器侧临时包清理（best-effort：清了失败不影响本次备份结论，也不该让脚本非 0 退出）
ssh "${SSH_OPTS[@]}" "$SSH_USER@$SERVER" "rm -f $REMOTE_TAR" 2>/dev/null || true

echo ""
echo "✅ 备份完成: $BACKUP_DIR/adaios-prod-$STAMP.tar.gz"

# ── 只报告不清理（刻意不自动删：备份是唯一不可重建的副本，
#    本项目已有「机制静默失效」先例，自动删除会把「没备份」升级成「备份被删」）──
COUNT="$(ls -1d "$DEST_ROOT"/*/ 2>/dev/null | wc -l | tr -d ' ')"
SIZE="$(du -sh "$DEST_ROOT" 2>/dev/null | cut -f1)"
echo "   本地备份累计: ${COUNT} 份 / ${SIZE}（${DEST_ROOT}）"
if [ "$COUNT" -gt 30 ]; then
  echo "   ⚠️ 备份份数偏多（>30），建议人工清理 ${DEST_ROOT} 旧目录（脚本不会自动删）"
fi
