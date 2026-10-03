#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 协作默契执行器（cadence）— 2026-09-26 用户「我们需要某种默契」落地
#
# 一句话：把「每日巡检 / 收工 / 每周 / 待办 / 发布」五件事，从**靠人记**变成
#         **有游标、能接着上次走、AI 自己读得到**。
#
# 为什么不是又一个脚本（此前已有 guard-prod / weekly-audit / ship）：
#   那些是**动作**，缺的是**记忆**——上次巡检看到哪天、上次收工是哪个 commit。
#   本脚本 = 游标（lib/cadence-lib.sh）+ 五件事的调度，把散落的动作串成节奏。
#   动作本体不变，本脚本只负责「从上次到现在」+「做完记账」。
#
# 用法:
#   bash ai-engineering/cadence.sh                 # 默契状态总览（开工第一眼，秒回）
#   bash ai-engineering/cadence.sh daily           # 每日巡检：自动补看「上次巡检 → 今天」
#   bash ai-engineering/cadence.sh ship            # 收工：本批 diff + 刷快照 + 成本入账
#   bash ai-engineering/cadence.sh release [--json]# 发版判定：现在欠着什么没发（只读，不部署）
#   bash ai-engineering/cadence.sh check           # 交付门禁一键（meta/align/tools/防复发）
#   bash ai-engineering/cadence.sh weekly          # 每周：跑每周审查 + 本周人肉清单
#   bash ai-engineering/cadence.sh todo            # 当前待办（REVIEW 未修项）
#   bash ai-engineering/cadence.sh cost [--record] # 成本：按天/会话算钱（--record 入账）
#   bash ai-engineering/cadence.sh mark <key> [日] # 手工补记游标（inspection|ship|weekly|release）
#
# 边界：**不自动部署、不自动 push**——发布只做判定，执行须用户点头（AGENTS.md 规则 11）。
# 触发协议（用户说的话 → 跑什么）见 ai-engineering/process/cadence.md 与 AGENTS.md 规则 8–11。
# ─────────────────────────────────────────────────────────────
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
# shellcheck source=lib/cadence-lib.sh
source "${ROOT}/ai-engineering/lib/cadence-lib.sh"

BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; CYN=$'\033[36m'; RST=$'\033[0m'
hr() { printf '\n%s── %s ──%s\n' "$BOLD" "$1" "$RST"; }

CMD="${1:-status}"; shift 2>/dev/null || true

# ── worktree 守卫（2026-10-03）─────────────────────────────────
# 为什么只拦 ship / mark：这两个会**按当前 HEAD 写收工基线**，而 ai-engineering/state/
#   在 worktree 里是 **link 主仓库的** → 在此跑会把主仓库的基线推到**本分支的 HEAD**
#   （全局游标错乱，且 AGENTS.md 规则 9「收工默认含提交」让 AI 极易误踩）。
# 其余子命令（status/daily/weekly/release/todo/cost/check）读写的是全局状态或当前工作区，
#   在 worktree 里跑结果与主仓库一致，故只**提示**、不阻断。
COMMON_DIR="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
MAIN_ROOT=""
[ -n "${COMMON_DIR}" ] && MAIN_ROOT="$(dirname "${COMMON_DIR}")"
if [ -n "${MAIN_ROOT}" ] && [ "${ROOT}" != "${MAIN_ROOT}" ]; then
  case "${CMD}" in
    ship|mark)
      {
        echo "❌ 当前在 worktree，不是主仓库——「${CMD}」被拒绝。"
        echo "   worktree：${ROOT}"
        echo "   主仓库：${MAIN_ROOT}"
        echo "   原因：ai-engineering/state/ 在 worktree 里是 link 主仓库的，而 ${CMD} 会**按当前 HEAD**"
        echo "         写收工基线 → 会把主仓库的基线推到本分支的 HEAD（全局游标错乱）。"
        echo "   处置：回主仓库跑 —— cd ${MAIN_ROOT} && bash ai-engineering/cadence.sh ${CMD}"
        echo "   若只想看本分支差异：git log / git status / git diff（无需 cadence）。"
      } >&2
      exit 2
      ;;
    daily|weekly|release)
      echo "⚠️  当前在 worktree（${ROOT}）——「${CMD}」是全局操作（读写主仓库的 state/），" >&2
      echo "   结果与在主仓库跑一致；按约定这些只在主仓库跑（见 docs/guides/worktree-workflow.md §四）。" >&2
      ;;
  esac
fi
# ─────────────────────────────────────────────────────────────

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

    local insp insp_at insp_days ship_at ship_head wk_at wk_week wk_txt rel_at rel_need rel_prod
    insp="$(cadence_get inspection.covered_through)"
    insp_at="$(cadence_get inspection.last_at)"
    insp_days="$(cadence_days_since "$insp")"
    ship_at="$(cadence_get ship.last_at)"
    ship_head="$(cadence_get ship.head)"
    wk_at="$(cadence_get weekly.last_at)"
    wk_week="$(cadence_get weekly.week)"
    rel_at="$(cadence_get release.last_at)"
    rel_need="$(cadence_get release.need)"
    rel_prod="$(cadence_get release.prod_commit)"

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
    if [ -n "$rel_at" ]; then
        printf '  %-10s %s\n' "发版体检" "上次 ${rel_at:0:16} · 生产 ${rel_prod:-?} · 欠发 ${rel_need:-?}"
    else
        printf '  %-10s %s\n' "发版体检" "${YEL}未做过 → bash ai-engineering/cadence.sh release${RST}"
    fi

    # 欠账（这才是「默契」要防的东西：别让任何一条静默过期）
    local owe=0
    if [ -z "$insp" ]; then
        printf '  %s欠账：尚未巡检过 → %sbash ai-engineering/cadence.sh daily%s\n' "$YEL" "$CYN" "$RST"; owe=1
    elif [ "$insp" != "$today" ]; then
        printf '  %s欠账：上次巡检在 %s，欠 %s 天 → %sbash ai-engineering/cadence.sh daily%s\n' \
            "$YEL" "$insp" "$(days_between "$insp" "$today" | wc -l | tr -d ' ')" "$CYN" "$RST"; owe=1
    fi
    [ "$owe" = 0 ] && printf '  %s✅ 今日已巡检，无欠账%s\n' "$GRN" "$RST"

    hr "到期红线（30 天内告警）"
    python3 scripts/check_deadlines.py --one-line 2>/dev/null | sed 's/^/  /' \
        || printf '  %s（取不到 → python3 scripts/check_deadlines.py）%s\n' "$DIM" "$RST"

    hr "自动任务（LaunchAgent）"
    local pair name logf age
    for pair in "每日备份:ai-engineering/state/backup.log" \
                "每周审查:ai-engineering/state/weekly-audit.log" \
                "午间谷时:ai-engineering/state/noon-task.log"; do
        name="${pair%%:*}"; logf="${pair#*:}"
        if [ -f "$logf" ]; then
            age=$(( ( $(date +%s) - $(stat -f %m "$logf" 2>/dev/null || echo 0) ) / 86400 ))
            printf '  %-8s %s 天前（%s）\n' "$name" "$age" "$(stat -f '%Sm' -t '%m-%d %H:%M' "$logf" 2>/dev/null)"
        else
            printf '  %-8s %s\n' "$name" "${YEL}无日志${RST}"
        fi
    done

    hr "当前待办"
    printf '  %s（REVIEW 未修项，跑 %sbash ai-engineering/cadence.sh todo%s 看全）\n' "$DIM" "$CYN" "$RST"
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
        printf '\n  %s收工默认含提交（2026-10-03 起，规则 9）：按路径显式 git add，禁止 git add -A%s\n' "$YEL" "$RST"
        printf '  %s范围守卫：ADAI_BATCH_PATHS="<本批路径>" git commit -m "..."%s\n' "$DIM" "$RST"
        printf '  %s（收工止步于提交：push 与部署仍须用户点头，原则 B8）%s\n' "$DIM" "$RST"
    fi

    # ── 审查判定（工具层 D7；2026-09-26 用户拍板「按建议实施」）────────────────
    # 收工**必须**先判这一条：本批 diff 含代码文件且触及并发/数据/契约/用户可见行为 → 派独立增量深审
    # （按改动目录派官 + 对抗官）；纯文档/样式 → light（guard-meta + guard-align + 守护快扫）。
    # 结论必须落盘（REVIEW / change-log）——**不许只留在对话里**（2026-09-26 教训：一夜 8 批自测自记，
    # 用户不追问就没有任何独立审查）；有 P0/P1 → 先修再提交。
    local batch_head code_files
    batch_head="${last:-HEAD}"
    code_files="$(git diff --name-only "$batch_head" 2>/dev/null | grep -cE '\.(java|kt|dart|ts|tsx|js|py|sh)$' || true)"
    hr "③ 审查判定（D7：收工前必判）"
    if [ "${code_files:-0}" -gt 0 ]; then
        printf '  %s⚠ 本批含 %s 个代码文件 → 派独立增量深审（按改动目录派官 + 对抗官）%s\n' "$YEL" "$code_files" "$RST"
        printf '  %s  结论落 REVIEW / change-log；P0/P1 先修再提交%s\n' "$DIM" "$RST"
    else
        printf '  %s本批无代码文件 → light：guard-meta + guard-align + 守护快扫%s\n' "$DIM" "$RST"
    fi

    hr "④ 收尾两步（AGENTS.md 规则 0b，强制）"
    bash ai-engineering/guard-context.sh --write-local | tail -2 || true
    bash ai-engineering/guard-cost.sh --record | tail -6 || true

    cadence_set ship.last_at "$(date '+%Y-%m-%dT%H:%M:%S%z')"
    cadence_set ship.head "$head"
    cadence_set ship.subject "$(git log -1 --pretty=%s 2>/dev/null | cut -c1-120)"
    hr "游标"
    printf '  ✅ 收工基线 → %s\n' "$head"
    # 2026-10-03：收工默认含提交（规则 9）——基线必须落在**提交之后**的 commit 上，否则
    # 下次收工会把本批已提交的内容再算一遍（同一批显示两遍）。本命令自身跑在提交之前，
    # 故此处只提示；提交完补一句即对齐（不自动做：提交由 AI 在审查判定之后执行）。
    printf '  %s↳ 本批提交后补推基线：bash ai-engineering/cadence.sh mark ship%s\n' "$DIM" "$RST"
}

# ── release：发版判定（只读；**不部署**）──────────────────────────────
# 用户 2026-09-26：「不主动部署，通过部署动作一键触发，确定是否更新发布」。
# 本命令只回答「现在欠着什么没发 + 要发哪几端」；真正部署仍走 deploy-gate.sh（最硬闸门）
# 且必须用户点头（AGENTS.md 规则 11 / 边界 B8）。判定与部署的分工详见 guard-release.sh 头注。
cmd_release() {
    if [ "${1:-}" = "--json" ]; then
        bash ai-engineering/guard-release.sh --json
        return $?
    fi
    printf '%s═══ 发版体检（%s）═══%s\n' "$BOLD" "$(date '+%F %H:%M')" "$RST"
    local raw
    raw="$(bash ai-engineering/guard-release.sh --json 2>/dev/null)"
    if [ -z "$raw" ]; then
        printf '  %s✗ 取不到发版数据（SSH 到生产不通？）→ 手工跑 bash ai-engineering/guard-release.sh%s\n' "$RED" "$RST"
        return 1
    fi
    RAW="$raw" python3 - <<'PY'
import datetime, json, os
d = json.loads(os.environ["RAW"])
prod = d.get("prod") or {}
head = d.get("head") or {}
bl = d.get("baseline") or {}
need = d.get("needRelease") or []
app = d.get("appBuildNumber") or {}

def bj(ts):
    try:
        t = datetime.datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
        return t.astimezone(datetime.timezone(datetime.timedelta(hours=8))).strftime("%Y-%m-%d %H:%M")
    except Exception:
        return ts or "?"

print("  生产   " + (prod.get("commit") or "?")[:8] + " · 部署 " + bj(prod.get("deployedAt"))
      + "（北京）· 上批 " + (prod.get("artifacts") or "?"))
print("  本地   " + str(head.get("short", "?")) + " · 未推送 " + str(head.get("unpushed", 0)) + " 个 commit")
print("  基线   " + str(bl.get("label", "?")) + "（" + str(bl.get("commits", 0))
      + " 提交 / " + str(bl.get("files", 0)) + " 文件）")
print()
print("  逐端判定：")
for u in d.get("units") or []:
    flag = "要发" if u.get("needRelease") else "不用发"
    tail = ""
    if u.get("unit") == "ios":
        tail = " · 构建号 " + str(app.get("current", "?")) + " → " + str(app.get("next", "?"))
    print("    " + str(u.get("label", "?")) + "  " + flag + "（" + str(u.get("files", 0))
          + " 文件 / " + str(u.get("commits", 0)) + " 提交）" + tail)
print()
if need:
    print("  ⚠ 欠着没发：" + ",".join(need))
    print("    下一步命令（逐端）：bash ai-engineering/guard-release.sh")
else:
    print("  ✅ 没有欠着没发的（生产已含全部改动）")
PY
    # 记账：只记事实（体检时间 / 结论 / 生产 commit），不替代生产 DEPLOYED 这个真相源
    cadence_set release.last_at "$(date '+%Y-%m-%dT%H:%M:%S%z')"
    cadence_set release.need "$(printf '%s' "$raw" | python3 -c 'import json,sys; print(",".join(json.load(sys.stdin).get("needRelease") or []) or "none")')"
    cadence_set release.prod_commit "$(printf '%s' "$raw" | python3 -c 'import json,sys; print(((json.load(sys.stdin).get("prod") or {}).get("commit") or "?")[:8])')"
    hr "边界"
    printf '  %s本命令只判定、不部署。要发 → deploy-gate.sh（门禁 + smoke）+ 你点头（规则 11）%s\n' "$DIM" "$RST"
}

# ── check：交付门禁一键（ship.md §4/§5 三件套 + 防复发）────────────────
# 为什么收进默契：这几条**每次交付都要跑**，散在文档里就靠人记；且 pre-commit 只是兜底，
# ship.md 明确要求「交付前主动跑，不依赖 hook 兜底」——一句话跑完最省事。
cmd_check() {
    printf '%s═══ 交付门禁（%s）═══%s\n' "$BOLD" "$(date '+%F %H:%M')" "$RST"
    local fail=0 pair name script last mark
    for pair in "结构门禁（frontmatter 图谱 / lines / 孤儿）:ai-engineering/guard-meta.sh" \
                "内容对齐（端点↔api-spec / 测试数↔status）:ai-engineering/guard-align.sh" \
                "工具接入（快照 / 技能 / 入口 / shell lint）:ai-engineering/guard-tools.sh" \
                "防复发 G1–G7:docs/review/guard.sh"; do
        name="${pair%%:*}"; script="${pair#*:}"
        last="$(bash "$script" 2>&1 | tail -1)"
        case "$last" in
            *PASS*|*"0 HIT"*|*"0 失败"*) mark="${GRN}✅${RST}" ;;
            *) mark="${RED}✗${RST}"; fail=1 ;;
        esac
        printf '  %s %s\n        %s\n' "$mark" "$name" "$last"
    done
    echo
    if [ "$fail" = 0 ]; then
        printf '  %s✅ 全部门禁通过——可以提交 / 部署%s\n' "$GRN" "$RST"
    else
        printf '  %s✗ 有门禁未过：修完再提交 / 部署（禁止带 FAIL 提交）%s\n' "$RED" "$RST"
    fi
    return "$fail"
}

# ── cost：成本（委托 guard-cost.sh，不重复实现）────────────────────────
cmd_cost() {
    printf '%s═══ 成本（%s）═══%s\n\n' "$BOLD" "$(date '+%F %H:%M')" "$RST"
    bash ai-engineering/guard-cost.sh "$@"
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
        release) cadence_set release.prod_commit "${val:-$(git rev-parse --short HEAD)}" ;;
        *) printf '用法: cadence.sh mark <inspection|ship|weekly|release> [值]\n' >&2; exit 2 ;;
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
    release)  cmd_release "$@" ;;
    check)    cmd_check "$@" ;;
    cost)     cmd_cost "$@" ;;
    mark)     cmd_mark "$@" ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) printf '未知命令: %s（可选 status|daily|ship|release|check|weekly|todo|cost|mark）\n' "$CMD" >&2; exit 2 ;;
esac
