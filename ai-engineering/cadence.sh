#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 协作默契执行器（cadence）— 2026-09-26 用户「我们需要某种默契」落地
#
# 一句话：把「每日巡检 / 收工 / 每周 / 待办」四件事，从**靠人记**变成
#         **有游标、能接着上次走、AI 自己读得到**。
#
# 为什么不是又一个脚本（此前已有 guard-prod / weekly-audit / ship）：
#   那些是**动作**，缺的是**记忆**——上次巡检看到哪天、上次收工是哪个 commit。
#   本脚本 = 游标（lib/cadence-lib.sh）+ 四件事的调度，把散落的动作串成节奏。
#   动作本体不变，本脚本只负责「从上次到现在」+「做完记账」。
#
# 用法:
#   bash ai-engineering/cadence.sh                 # 默契状态总览（开工第一眼，秒回）
#   bash ai-engineering/cadence.sh daily           # 每日巡检：自动补看「上次巡检 → 今天」
#   bash ai-engineering/cadence.sh ship            # 收工：本批 diff + 刷快照 + 成本入账
#   bash ai-engineering/cadence.sh weekly          # 每周：跑每周审查 + 本周待办
#   bash ai-engineering/cadence.sh todo            # 当前待办（task-log 进行中）
#   bash ai-engineering/cadence.sh mark <key> [日] # 手工补记游标（key: inspection|ship|weekly）
#
# 触发协议（用户说的话 → 跑什么）见 ai-engineering/process/cadence.md 与 AGENTS.md 规则 8/9/10。
# ─────────────────────────────────────────────────────────────
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
# shellcheck source=lib/cadence-lib.sh
source "${ROOT}/ai-engineering/lib/cadence-lib.sh"

BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; CYN=$'\033[36m'; RST=$'\033[0m'
hr() { printf '\n%s── %s ──%s\n' "$BOLD" "$1" "$RST"; }

CMD="${1:-status}"; shift 2>/dev/null || true

# ── 工具：某日期区间 (last, today] 的每一天 ──────────────────────────────
days_between() {  # <last> <today>  → 每行一个日期（不含 last，含 today）
    python3 - "$1" "$2" <<'PY'
import datetime, sys
try:
    last, today = datetime.date.fromisoformat(sys.argv[1]), datetime.date.fromisoformat(sys.argv[2])
except Exception:
    sys.exit(0)
d = last + datetime.timedelta(days=1)
while d <= today:
    print(d.isoformat())
    d += datetime.timedelta(days=1)
PY
}

# ── status：默契总览 ────────────────────────────────────────────────────
cmd_status() {
    local today; today="$(date +%F)"
    printf '%s═══ 协作默契状态（%s）═══%s\n' "$BOLD" "$(date '+%F %H:%M')" "$RST"

    local insp insp_at insp_days ship_at ship_head wk_at wk_week wk_txt
    insp="$(cadence_get inspection.covered_through)"
    insp_at="$(cadence_get inspection.last_at)"
    insp_days="$(cadence_days_since "$insp")"
    ship_at="$(cadence_get ship.last_at)"
    ship_head="$(cadence_get ship.head)"
    wk_at="$(cadence_get weekly.last_at)"
    wk_week="$(cadence_get weekly.week)"

    printf '  %-10s %s\n' "每日巡检" \
        "$([ -n "$insp" ] && echo "已覆盖到 ${insp}（${insp_days} 天前）· 累计 $(cadence_get inspection.runs 0) 次" || echo "${YEL}未建立游标${RST}")"
    printf '  %-10s %s\n' "收工" \
        "$([ -n "$ship_at" ] && echo "上次 ${ship_at:0:16} · 基线 ${ship_head:-?}" || echo "${YEL}未建立游标${RST}")"
    if [ -n "$wk_at" ]; then
        wk_txt="上次 ${wk_at:0:16}（${wk_week:-?}）· 周一 09:00 自动"
    elif [ -n "$wk_week" ]; then
        wk_txt="已覆盖 ${wk_week} · 周一 09:00 自动"
    else
        wk_txt="${YEL}未建立游标${RST}"
    fi
    printf '  %-10s %s\n' "每周审查" "$wk_txt"

    # 欠账（这才是「默契」要防的东西：别让任何一条静默过期）
    local owe=0
    if [ -z "$insp" ]; then
        printf '  %s欠账：尚未巡检过 → %sbash ai-engineering/cadence.sh daily%s\n' "$YEL" "$CYN" "$RST"; owe=1
    elif [ "$insp" != "$today" ]; then
        printf '  %s欠账：上次巡检在 %s，欠 %s 天 → %sbash ai-engineering/cadence.sh daily%s\n' \
            "$YEL" "$insp" "$(days_between "$insp" "$today" | wc -l | tr -d ' ')" "$CYN" "$RST"; owe=1
    fi
    [ "$owe" = 0 ] && printf '  %s✅ 今日已巡检，无欠账%s\n' "$GRN" "$RST"

    hr "当前待办"
    printf '  %s（task-log 进行中 + REVIEW P1，跑 %sbash ai-engineering/cadence.sh todo%s 看全）\n' "$DIM" "$CYN" "$RST"
}

# ── daily：每日巡检（增量：上次巡检 → 今天）────────────────────────────
cmd_daily() {
    local max_days=7
    if [ "${1:-}" = "--max-days" ]; then max_days="${2:-7}"; fi

    local today; today="$(date +%F)"
    local last; last="$(cadence_get inspection.covered_through)"

    printf '%s═══ 每日巡检（%s）═══%s\n' "$BOLD" "$(date '+%F %H:%M')" "$RST"

    local days=() d n gap=0
    if [ -z "$last" ]; then
        printf '  %s▸ 首次巡检（无游标）：本次只看今天，此后自动接着上次走%s\n\n' "$YEL" "$RST"
        days=( "$today" )
    else
        while IFS= read -r d; do [ -n "$d" ] && days+=( "$d" ); done < <(days_between "$last" "$today")
        n=${#days[@]}
        if [ "$n" -eq 0 ]; then
            printf '  %s▸ 游标已覆盖到今天（%s）：今日已巡过，本次为复查%s\n\n' "$DIM" "$last" "$RST"
            days=( "$today" )
        else
            printf '  ▸ 上次巡检覆盖到 %s → 本次补看 %s 天：%s\n\n' "$last" "$n" "${days[*]}"
        fi
    fi

    # 兜底：一次欠太多天（长期没巡）不硬塞，只跑最近 max_days 天并显式声明
    n=${#days[@]}
    if [ "$n" -gt "$max_days" ]; then
        gap=$(( n - max_days ))
        printf '  %s▸ 欠 %s 天超过上限 %s：只巡最近 %s 天，中间 %s 天未看（补看：guard-prod.sh --date <日>）%s\n\n' \
            "$YEL" "$n" "$max_days" "$max_days" "$gap" "$RST"
        local trimmed=() i
        for (( i = n - max_days; i < n; i++ )); do trimmed+=( "${days[$i]}" ); done
        days=( "${trimmed[@]}" )
    fi

    for d in "${days[@]}"; do
        if [ "$d" = "$today" ]; then
            bash ai-engineering/guard-prod.sh
        else
            printf '\n%s════════ 补看 %s ════════%s\n' "$BOLD" "$d" "$RST"
            bash ai-engineering/guard-prod.sh --date "$d"
        fi
    done

    # 记账（只前进）
    cadence_set inspection.last_at "$(date '+%Y-%m-%dT%H:%M:%S%z')"
    cadence_advance_day inspection.covered_through "$today"
    cadence_set inspection.runs "$(( $(cadence_get inspection.runs 0) + 1 ))"
    hr "游标"
    printf '  ✅ 已覆盖到 %s%s\n' "$today" "$([ "$gap" -gt 0 ] && echo "（中间跳过 ${gap} 天，未计入下次）")"
}

# ── ship：收工（本批 diff + 收尾两步）──────────────────────────────────
cmd_ship() {
    local head last
    head="$(git rev-parse --short HEAD 2>/dev/null || echo '?')"
    last="$(cadence_get ship.head)"

    printf '%s═══ 收工（%s）═══%s\n' "$BOLD" "$(date '+%F %H:%M')" "$RST"

    hr "① 本批改动"
    if [ -n "$last" ] && git cat-file -e "${last}^{commit}" 2>/dev/null; then
        printf '  %s自上次收工（%s）以来已提交：%s\n' "$DIM" "$last" "$RST"
        git --no-pager log --oneline "${last}..HEAD" | head -20 || true
        printf '\n  %s累计 diff（%s → 工作区）：%s\n' "$DIM" "$last" "$RST"
        git --no-pager diff --stat "$last" | tail -25 || true
    else
        printf '  %s无收工基线（首次）：显示当前工作区改动%s\n' "$DIM" "$RST"
        git --no-pager diff --stat HEAD | tail -25 || true
    fi

    hr "② 未提交（工作区 / 暂存区）"
    local dirty; dirty="$(git status --porcelain)"
    if [ -z "$dirty" ]; then
        printf '  %s✅ 工作区干净%s\n' "$GRN" "$RST"
    else
        printf '%s\n' "$dirty" | head -30
        printf '\n  %s提交纪律（ship.md §7）：按路径显式 git add，禁止 git add -A%s\n' "$YEL" "$RST"
        printf '  %s范围守卫：ADAI_BATCH_PATHS="<本批路径>" git commit -m "..."%s\n' "$DIM" "$RST"
    fi

    hr "③ 收尾两步（AGENTS.md 规则 0b，强制）"
    bash ai-engineering/guard-context.sh --write-local | tail -2 || true
    bash ai-engineering/guard-cost.sh --record | tail -6 || true

    cadence_set ship.last_at "$(date '+%Y-%m-%dT%H:%M:%S%z')"
    cadence_set ship.head "$head"
    cadence_set ship.subject "$(git log -1 --pretty=%s 2>/dev/null | head -c 120)"
    hr "游标"
    printf '  ✅ 收工基线 → %s\n' "$head"
}

# ── weekly：每周（跑审查 + 本周清单）──────────────────────────────────
cmd_weekly() {
    printf '%s═══ 每周（%s）═══%s\n' "$BOLD" "$(date '+%F %H:%M')" "$RST"
    bash ai-engineering/weekly-audit.sh
    cadence_set weekly.last_at "$(date '+%Y-%m-%dT%H:%M:%S%z')"
    cadence_set weekly.week "$(date '+%G-W%V')"
    hr "本周你还要亲自做的（routine.md §三）"
    printf '  · TDX 盘后行情包同步（admin「系统 → 维护」上传 .zip）\n'
    printf '  · 盘一次账：交易页账实自检 + 资金快照\n'
    printf '  · 未修项过一遍：bash ai-engineering/guard-unfixed.sh\n'
    printf '  · 到期红线：python3 scripts/check_deadlines.py\n'
}

# ── todo：当前待办 ─────────────────────────────────────────────────────
# 源取 guard-context.sh 的 C2 段（REVIEW 未修项，已统一格式化且带条数上限）。
# 不自己解析 REVIEW.md：那是历史流水文档（条目嵌在引用块里），另写一套解析＝造第二个真相源。
cmd_todo() {
    printf '%s═══ 当前待办（%s）═══%s\n' "$BOLD" "$(date +%F)" "$RST"
    printf '  %s源：REVIEW.md 未修项 · 全量看 %sbash ai-engineering/guard-unfixed.sh%s\n\n' "$DIM" "$CYN" "$RST"
    bash ai-engineering/guard-context.sh 2>/dev/null \
        | awk '/^## C2 未修项/{f=1;next} /^## C[0-9]/{f=0} f' \
        | grep -v '^$' | cut -c1-150 | head -16
    local insp; insp="$(cadence_get inspection.covered_through)"
    hr "节奏"
    printf '  巡检游标 %s · 任务表 docs/reference/task-log.md · 产品蓝图 docs/architecture/product-roadmap.md\n' \
        "${insp:-未建立}"
}

# ── mark：手工补记游标 ─────────────────────────────────────────────────
cmd_mark() {
    local key="${1:-}" val="${2:-}"
    case "$key" in
        inspection) cadence_advance_day inspection.covered_through "${val:-$(date +%F)}" ;;
        shipment|ship) cadence_set ship.head "${val:-$(git rev-parse --short HEAD)}" ;;
        weekly) cadence_set weekly.week "${val:-$(date '+%G-W%V')}" ;;
        *) printf '用法: cadence.sh mark <inspection|ship|weekly> [值]\n' >&2; exit 2 ;;
    esac
    hr "游标"
    cadence_json
}

case "$CMD" in
    status|"") cmd_status ;;
    daily)    cmd_daily "$@" ;;
    ship)     cmd_ship "$@" ;;
    weekly)   cmd_weekly "$@" ;;
    todo)     cmd_todo "$@" ;;
    mark)     cmd_mark "$@" ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) printf '未知命令: %s（可选 status|daily|ship|weekly|todo|mark）\n' "$CMD" >&2; exit 2 ;;
esac
