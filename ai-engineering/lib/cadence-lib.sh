#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 游标库（cadence state）— 「上次到哪了」的唯一存储
#
# 为什么需要它（2026-09-26 用户提出「我们需要某种默契」）：
#   此前的固定动作全是**无状态**的——每日巡检永远看「今天 + 近 7 天」，
#   收工不知道「上次收工是哪个 commit」，周审不知道自己上周来过没有。
#   于是 AI 每次都从零问起、用户每次都要交代背景——这不叫默契，叫重新认识。
#   「默契」的技术前提 = 游标：把「上次做到哪」落盘，AI 下次自己读得到。
#
# 存储：ai-engineering/state/cadence.json（gitignore 之外？否——见 CADENCE.md §游标是否入库）
#   {
#     "inspection": {"last_at": "...", "covered_through": "2026-09-25", "runs": 12},
#     "ship":       {"last_at": "...", "head": "34f6cba6", "subject": "..."},
#     "weekly":     {"last_at": "...", "week": "2026-W39"}
#   }
#
# 用法（被 cadence.sh / guard-prod.sh source）:
#   source ai-engineering/lib/cadence-lib.sh
#   cadence_get inspection.covered_through          # 取值（无则空）
#   cadence_set inspection.covered_through 2026-09-26
#   cadence_advance_day inspection.covered_through 2026-09-26   # 只前进不后退
#   cadence_json                                    # 整份 JSON（status 总览用）
# ─────────────────────────────────────────────────────────────

CADENCE_ROOT="${CADENCE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
CADENCE_STATE="${CADENCE_STATE:-${CADENCE_ROOT}/ai-engineering/state/cadence.json}"

# 读一个点号路径的值；不存在时输出第二个参数（默认空）
cadence_get() {
    python3 - "$CADENCE_STATE" "$1" "${2-}" <<'PY'
import json, pathlib, sys
path, key, default = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    cur = json.loads(pathlib.Path(path).read_text(encoding='utf-8'))
except Exception:
    cur = {}
for k in key.split('.'):
    if isinstance(cur, dict) and k in cur:
        cur = cur[k]
    else:
        print(default)
        sys.exit(0)
print(cur if not isinstance(cur, (dict, list)) else json.dumps(cur, ensure_ascii=False))
PY
}

# 写一个点号路径的值（原子替换；纯数字写成 int，true/false 写成 bool）
cadence_set() {
    python3 - "$CADENCE_STATE" "$1" "$2" <<'PY'
import json, os, pathlib, sys
path, key, val = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
try:
    d = json.loads(path.read_text(encoding='utf-8'))
except Exception:
    d = {}
if isinstance(val, str):
    # 防御（2026-09-26 收工实测踩到）：调用方可能用 `head -c` 之类的**按字节**截断，
    # 把多字节中文字符切成两半 → argv 里出现孤立代理对（surrogateescape 产物），
    # 直接写 UTF-8 会抛 UnicodeEncodeError。先还原原始字节、再按 UTF-8 容错解码，
    # 坏字节替换为 U+FFFD —— 记账绝不因脏字节中断。
    val = val.encode('utf-8', 'surrogateescape').decode('utf-8', 'replace')
if isinstance(val, str) and val.isdigit():
    v = int(val)
elif val == 'true':
    v = True
elif val == 'false':
    v = False
else:
    v = val
cur, ks = d, key.split('.')
for k in ks[:-1]:
    nxt = cur.get(k)
    if not isinstance(nxt, dict):
        nxt = {}
        cur[k] = nxt
    cur = nxt
cur[ks[-1]] = v
path.parent.mkdir(parents=True, exist_ok=True)
tmp = path.with_name(path.name + '.tmp')
tmp.write_text(json.dumps(d, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', errors='replace')
os.replace(tmp, path)
PY
}

# 日期型游标只前进：本次比已记录的旧（或相等）就什么都不做
# 用法: cadence_advance_day <dotted.key> <YYYY-MM-DD>
cadence_advance_day() {
    local key="$1" day="$2"
    [ -n "$day" ] || return 0
    local cur
    cur="$(cadence_get "$key")"
    # ISO 日期定长，字典序即时间序（显式 LC_ALL=C 避免 locale 干扰）
    if [ -n "$cur" ] && [ "$(LC_ALL=C printf '%s\n%s\n' "$cur" "$day" | sort | tail -1)" = "$cur" ]; then
        return 0
    fi
    cadence_set "$key" "$day"
}

# 整份游标（美化 JSON），state 文件不存在时给一个空壳
cadence_json() {
    python3 - "$CADENCE_STATE" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
try:
    d = json.loads(p.read_text(encoding='utf-8'))
except Exception:
    d = {}
print(json.dumps(d, ensure_ascii=False, indent=2))
PY
}

# 距今多少天（<日期> → 整数；空/非法 → 空）
cadence_days_since() {
    [ -n "${1:-}" ] || return 0
    python3 - "$1" <<'PY'
import datetime, sys
try:
    d = datetime.date.fromisoformat(sys.argv[1])
    print((datetime.date.today() - d).days)
except Exception:
    pass
PY
}
