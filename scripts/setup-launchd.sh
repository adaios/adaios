#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 安装/自检 定时任务（LaunchAgent）— 2026-09-14 建
#
# 为什么不用 crontab：macOS TCC 拦截 crontab（本机 `crontab -l` 直接
#   "Operation not permitted"），2026-08-29 声称「weekly-audit cron 已挂载」，
#   实际 /tmp/weekly-audit.log 从未出现 —— 定时任务静默失效了 16 天没人知道。
#   launchd 不受该限制，且「错过就在唤醒后补跑」。
#
# 换机 clone 后执行一次：bash scripts/setup-launchd.sh
# 自检：                bash scripts/setup-launchd.sh --check
# 卸载：                bash scripts/setup-launchd.sh --uninstall
#
# 装了哪两个：
#   com.adai.adaios-backup        每天 21:10  生产 data/os/.env/jar 快备到 ~/backups
#   com.adai.adaios-weekly-audit  每周一 09:00  weekly-audit.sh W1-W6（防审查休眠）
# ─────────────────────────────────────────────────────────────
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENTS="$HOME/Library/LaunchAgents"
STATE="${ROOT}/ai-engineering/state"
BACKUP_LOG="${STATE}/backup.log"
AUDIT_LOG="${STATE}/weekly-audit.log"

BACKUP_LABEL="com.adai.adaios-backup"
AUDIT_LABEL="com.adai.adaios-weekly-audit"

uid_num="$(id -u)"

# ── 生成单个 plist ────────────────────────────────────────────
# 参数: 标签 脚本绝对路径 额外参数(可空) 日志路径 若干 StartCalendarInterval 键值
write_plist() {
  local label="$1" script="$2" extra="$3" log="$4"; shift 4
  local out="${AGENTS}/${label}.plist"
  {
  cat <<PLIST_HEAD
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>${label}</string>
	<key>ProgramArguments</key>
	<array>
		<string>/bin/bash</string>
		<string>${script}</string>
PLIST_HEAD
  if [ -n "${extra}" ]; then
    printf '\t\t<string>%s</string>\n' "${extra}"
  fi
  cat <<PLIST_TAIL
	</array>
	<key>WorkingDirectory</key>
	<string>${ROOT}</string>
	<key>StartCalendarInterval</key>
	<array>
		<dict>
PLIST_TAIL
  # 剩余参数成对：键 值 键 值 …
  while [ "$#" -gt 0 ]; do
    printf '\t\t\t<key>%s</key>\n\t\t\t<integer>%s</integer>\n' "$1" "$2"
    shift 2
  done
  cat <<PLIST_END
		</dict>
	</array>
	<key>EnvironmentVariables</key>
	<dict>
		<key>PATH</key>
		<string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
		<key>LANG</key>
		<string>en_US.UTF-8</string>
	</dict>
	<key>LimitLoadToSessionType</key>
	<string>Aqua</string>
	<key>StandardOutPath</key>
	<string>${log}</string>
	<key>StandardErrorPath</key>
	<string>${log}</string>
</dict>
</plist>
PLIST_END
  } > "${out}"
  echo "  ✓ 写入 ${out}"
}

# ── 安装 ─────────────────────────────────────────────────────
do_install() {
  mkdir -p "${AGENTS}" "${STATE}"
  echo "── 安装定时任务（LaunchAgent）──"

  # 日志文件必须先能创建：launchd 打不开 StandardOutPath 时任务会直接起不来（静默）
  : > "${BACKUP_LOG}" 2>/dev/null || true
  : > "${AUDIT_LOG}" 2>/dev/null || true

  write_plist "${BACKUP_LABEL}" "${ROOT}/scripts/backup_prod.sh" "" "${BACKUP_LOG}" \
    Hour 21 Minute 10
  write_plist "${AUDIT_LABEL}" "${ROOT}/ai-engineering/weekly-audit.sh" "--auto" "${AUDIT_LOG}" \
    Weekday 1 Hour 9 Minute 0

  for label in "${BACKUP_LABEL}" "${AUDIT_LABEL}"; do
    launchctl bootout "gui/${uid_num}/${label}" 2>/dev/null || true
    if launchctl bootstrap "gui/${uid_num}" "${AGENTS}/${label}.plist" 2>/dev/null; then
      echo "  ✓ 已加载 ${label}"
    else
      echo "  ⚠️ 加载失败 ${label} → 手工: launchctl bootstrap gui/${uid_num} ${AGENTS}/${label}.plist"
    fi
  done

  echo ""
  echo "✅ 安装完成（日志在 ai-engineering/state/）"
  echo "   立即验证: bash scripts/setup-launchd.sh --check"
  echo "   手动触发: launchctl kickstart -k gui/${uid_num}/${BACKUP_LABEL}"
}

# ── 自检 ─────────────────────────────────────────────────────
# 判据不是「plist 文件在不在」，而是「launchd 真的加载了 + 最近真的跑过」
do_check() {
  local rc=0
  echo "── 定时任务自检 ──"
  for label in "${BACKUP_LABEL}" "${AUDIT_LABEL}"; do
    if launchctl print "gui/${uid_num}/${label}" >/dev/null 2>&1; then
      echo "  ✅ ${label} 已加载"
    else
      echo "  ❌ ${label} 未加载 → bash scripts/setup-launchd.sh"
      rc=1
    fi
  done

  # 备份新鲜度：超过 2 天没成功备份 = 红灯（2026-09-14 就是 26 天没人发现）
  # 判据：不只是「目录在」，而是「目录里真有归档」——失败的备份会留下空目录。
  # 2026-09-14 再修：找「最近一次**真有归档**的目录」。原实现只看最新目录，于是一次偶发
  # 网络抖动（21:16 ssh unreachable 留下空目录）就把同日 02:41 的成功备份读成「无成功备份」
  # ——把「最新一次失败」误报成「从来没成功过」，红得没有信息量。
  local bdir="$HOME/backups/adaios-prod"
  local latest_ok="" _d _f
  for _d in $(ls -1dt "${bdir}"/*/ 2>/dev/null || true); do
    _f="$(ls -1 "${_d}"*.tar.gz 2>/dev/null | head -1 || true)"
    if [ -n "${_f}" ]; then latest_ok="${_d}"; break; fi
  done
  if [ -z "${latest_ok}" ]; then
    echo "  ❌ 无成功备份（${bdir} 下没有任何含归档的目录）→ 看 ${BACKUP_LOG}"
    rc=1
  else
    local age=$(( ( $(date +%s) - $(stat -f %m "${latest_ok}") ) / 86400 ))
    if [ "${age}" -le 2 ]; then
      echo "  ✅ 最近成功备份 ${age} 天前（$(basename "${latest_ok}")）"
    else
      echo "  ❌ 最近成功备份 ${age} 天前 → 定时任务可能没跑，看 ${BACKUP_LOG}"
      rc=1
    fi
  fi
  # 最新一次尝试没产出归档 → 提示（不判红：同日已有成功备份时属偶发；连续失败由上面的 age 兜住）
  local newest
  newest="$(ls -1dt "${bdir}"/*/ 2>/dev/null | head -1 || true)"
  if [ -n "${newest}" ] && [ "${newest}" != "${latest_ok}" ]; then
    echo "  ⚠️ 最近一次尝试未产出归档（$(basename "${newest}")）→ 看 ${BACKUP_LOG}"
  fi

  # 每周审查新鲜度：超过 8 天没跑 = 红灯
  if [ -s "${AUDIT_LOG}" ]; then
    local aage=$(( ( $(date +%s) - $(stat -f %m "${AUDIT_LOG}") ) / 86400 ))
    if [ "${aage}" -le 8 ]; then
      echo "  ✅ 每周审查 ${aage} 天前跑过"
    else
      echo "  ❌ 每周审查已 ${aage} 天未跑 → 看 ${AUDIT_LOG}"
      rc=1
    fi
  else
    echo "  ⚠️ 每周审查尚无日志（下周一 09:00 首次跑；可 kickstart 立刻验证）"
  fi

  echo ""
  if [ "${rc}" -eq 0 ]; then echo "── 结果: PASS ──"; else echo "── 结果: FAIL ──"; fi
  return "${rc}"
}

# ── 卸载 ─────────────────────────────────────────────────────
do_uninstall() {
  for label in "${BACKUP_LABEL}" "${AUDIT_LABEL}"; do
    launchctl bootout "gui/${uid_num}/${label}" 2>/dev/null || true
    rm -f "${AGENTS}/${label}.plist"
    echo "  ✓ 已卸载 ${label}"
  done
}

case "${1:-}" in
  --check)     do_check ;;
  --uninstall) do_uninstall ;;
  *)           do_install ;;
esac
