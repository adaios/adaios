#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 成本监控（防守侧）— 读 DSH 会话日志，按天/月算 DeepSeek 实际消费
#
# 用法:
#   bash ai-engineering/guard-cost.sh                # 今天
#   bash ai-engineering/guard-cost.sh --day 2026-08-17
#   bash ai-engineering/guard-cost.sh --month 2026-08
#   bash ai-engineering/guard-cost.sh --top 10        # 今日 Top 会话
#   bash ai-engineering/guard-cost.sh --record        # 今日结果追加成本日志
#   bash ai-engineering/guard-cost.sh --log           # 查看历史成本日志
#
# 说明:
#   数据源 = ~/.dsh/sessions/**/session.jsonl.zstd（DSH 每次模型调用落一条 usage）
#   价格表 = DeepSeek 2026-08-17 峰谷定价（V4-Flash/V4-Pro，高峰 9-12/14-18 点）
#   费用 = 输入×单价 + 缓存读取×单价 + 输出×单价（思维链已含在输出里）
#   背景: 2026-08-17 涨价第一天 DSH 单日 77 元 → 立此脚本盯账（详见 checklists/cost.md）
# ─────────────────────────────────────────────────────────────
set -u

# 解析参数
DAY=""
MONTH=""
TOP_N=0
RECORD=0
LOG=0
for a in "$@"; do
  case "$a" in
    --day) DAY="$(echo "$2" | sed 's/^--.*//')"; shift 2 ;;
    --day=*) DAY="${a#*=}"; shift ;;
    --month) MONTH="$(echo "$2" | sed 's/^--.*//')"; shift 2 ;;
    --month=*) MONTH="${a#*=}"; shift ;;
    --top) TOP_N="${2:-10}"; shift 2 ;;
    --top=*) TOP_N="${a#*=}"; shift ;;
    --record) RECORD=1; shift ;;
    --log) LOG=1; shift ;;
    *) shift ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATE_DIR="$ROOT/ai-engineering/state"
COST_LOG="$STATE_DIR/cost-log.jsonl"

# 找 zstd
ZSTD="$(command -v zstd 2>/dev/null || echo /opt/homebrew/bin/zstd)"
if [ ! -x "$ZSTD" ]; then
  echo "✗ 找不到 zstd（需要它解压 DSH 会话日志）" >&2
  echo "  安装: brew install zstd" >&2
  exit 1
fi

# DSH 会话目录
SESS_DIR="$HOME/.dsh/sessions"
if [ ! -d "$SESS_DIR" ]; then
  echo "✗ 找不到 DSH 会话目录: $SESS_DIR" >&2
  exit 1
fi

# 历史日志查看（按 date 聚合同日多笔——2026-08-23 追加式记账后）
if [ "$LOG" = "1" ]; then
  echo "== 成本历史（state/cost-log.jsonl，按日聚合）=="
  if [ ! -f "$COST_LOG" ]; then echo "（尚无记录，先跑 --record）"; exit 0; fi
  python3 - "$COST_LOG" <<'PYEOF'
import json, sys, collections
agg = collections.OrderedDict()
for line in open(sys.argv[1], encoding='utf-8'):
    line = line.strip()
    if not line: continue
    try: e = json.loads(line)
    except Exception: continue
    d = e.get('date', '?')
    a = agg.setdefault(d, {'calls': 0, 'cost': 0.0, 'records': 0})
    a['calls'] += e.get('calls', 0); a['cost'] += e.get('cost', 0); a['records'] += 1
for d, a in agg.items():
    print(f"{d}  cost ¥{a['cost']:.2f}  calls {a['calls']}  (记录 {a['records']} 笔)")
PYEOF
  exit 0
fi

# 默认范围 = 今天（无 --day/--month 时）
if [ -z "$DAY" ] && [ -z "$MONTH" ]; then
  DAY="$(date +%Y-%m-%d)"
fi

# 组装 python 入参
ARGS=("$ROOT" "$SESS_DIR" "$ZSTD" "$DAY" "$MONTH" "$TOP_N" "$RECORD" "$COST_LOG")

python3 - "${ARGS[@]}" <<'PYEOF'
import json, os, sys, glob, subprocess, datetime, collections

ROOT, SESS_DIR, ZSTD, DAY, MONTH, TOP_N, RECORD, COST_LOG = sys.argv[1:9]
TOP_N = int(TOP_N) if TOP_N else 0
RECORD = RECORD == '1'

# DeepSeek 2026-08-17 峰谷定价（元/百万 tokens）
# 高峰: 9:00-12:00, 14:00-18:00（北京时间）; 空闲 = 高峰半价
PRICE = {
    'deepseek-v4-flash': {'peak': {'in': 3.0, 'cache': 0.10, 'out': 9.0},
                          'off':  {'in': 1.5, 'cache': 0.05, 'out': 4.5}},
    'deepseek-v4-pro':   {'peak': {'in': 9.0, 'cache': 0.30, 'out': 27.0},
                          'off':  {'in': 4.5, 'cache': 0.15, 'out': 13.5}},
    # 缺省按 flash 计（DSH 默认）
}

def is_peak(ts):
    h = ts.hour
    return (9 <= h < 12) or (14 <= h < 18)

def price_for(model, ts):
    m = 'deepseek-v4-pro' if 'pro' in model else 'deepseek-v4-flash'
    win = 'peak' if is_peak(ts) else 'off'
    return m, PRICE.get(m, PRICE['deepseek-v4-flash'])[win]

def day_key(ts):
    return ts.strftime('%Y-%m-%d')

def month_key(ts):
    return ts.strftime('%Y-%m')

# 扫描所有会话，收集 usage 记录
records = []  # (sid, label, model, ts, in, cache, out, reason, cost)
sess_meta = {}
sess_paths = sorted(glob.glob(os.path.join(SESS_DIR, '*', '*', 'session.jsonl.zstd')))
if not sess_paths:
    sess_paths = sorted(glob.glob(os.path.join(SESS_DIR, '*', 'session.jsonl.zstd')))

for path in sess_paths:
    sid = path.split(os.sep)[-2]
    try:
        raw = subprocess.run([ZSTD, '-dc', path], capture_output=True, timeout=60).stdout.decode('utf-8', 'ignore')
    except Exception:
        continue
    label = None
    for line in raw.splitlines():
        try: d = json.loads(line)
        except: continue
        t = d.get('type')
        if t == 'subagent/descriptor':
            label = d.get('data', {}).get('label') or label
        elif t == 'assistant/message':
            data = d.get('data', {})
            u = data.get('usage')
            if not u: continue
            ts = datetime.datetime.fromtimestamp(d.get('time', 0) / 1000)
            if DAY and day_key(ts) != DAY: continue
            if MONTH and month_key(ts) != MONTH: continue
            src = data.get('message', {}).get('source', {})
            model = src.get('model', 'deepseek-v4-flash') or 'deepseek-v4-flash'
            m, pr = price_for(model, ts)
            i = u.get('inputTokens', 0); c = u.get('cacheReadTokens', 0); o = u.get('outputTokens', 0); r = u.get('reasoningTokens', 0)
            cost = i/1e6*pr['in'] + c/1e6*pr['cache'] + o/1e6*pr['out']
            records.append((sid, label, m, ts, i, c, o, r, cost))
            if label: sess_meta[sid] = label

if not records:
    scope = DAY or (MONTH + '-*') or 'today'
    print(f"该范围（{scope}）无 DSH 调用记录")
    sys.exit(0)

# 汇总
tot = collections.Counter()
by_win = collections.defaultdict(collections.Counter)
by_sess = collections.defaultdict(collections.Counter)
for sid, label, m, ts, i, c, o, r, cost in records:
    win = 'peak' if is_peak(ts) else 'off'
    tot['calls'] += 1
    tot['cost'] += cost
    tot['in'] += i; tot['cache'] += c; tot['out'] += o; tot['reason'] += r
    by_win[(m, win)]['cost'] += cost
    by_win[(m, win)]['calls'] += 1
    by_sess[sid]['cost'] += cost
    by_sess[sid]['calls'] += 1
    by_sess[sid]['cache'] += c
    by_sess[sid]['label'] = label or '(主会话)'

scope = DAY or (MONTH and (MONTH + ' 整月')) or '今天'
print(f"# DSH 成本 · {scope}")
print(f"> 调用 {tot['calls']} 次 | 预估总费用 **{tot['cost']:.2f} 元**")
print(f"> 输入 {tot['in']/1e6:.1f}M | 缓存读取 {tot['cache']/1e6:.1f}M | 输出 {tot['out']/1e6:.1f}M（含思维链 {tot['reason']/1e6:.1f}M）")
print()

print("## 按模型 × 时段")
for (m, win), v in sorted(by_win.items()):
    print(f"- {m} · {'高峰' if win=='peak' else '空闲'}: {v['calls']} 次 = {v['cost']:.2f} 元")
print()

# 费用构成
cache_cost = sum(rec[5]/1e6 * (PRICE.get(rec[2], PRICE['deepseek-v4-flash'])['peak' if is_peak(rec[3]) else 'off']['cache']) for rec in records)
in_cost = sum(rec[4]/1e6 * (PRICE.get(rec[2], PRICE['deepseek-v4-flash'])['peak' if is_peak(rec[3]) else 'off']['in']) for rec in records)
out_cost = sum(rec[6]/1e6 * (PRICE.get(rec[2], PRICE['deepseek-v4-flash'])['peak' if is_peak(rec[3]) else 'off']['out']) for rec in records)
print("## 费用构成")
print(f"- 缓存命中输入（涨价后的大头）: {cache_cost:.2f} 元 ({cache_cost/tot['cost']*100:.0f}%)")
print(f"- 输入未命中: {in_cost:.2f} 元 ({in_cost/tot['cost']*100:.0f}%)")
print(f"- 输出（含思维链）: {out_cost:.2f} 元 ({out_cost/tot['cost']*100:.0f}%)")
print()

if TOP_N > 0:
    print(f"## Top {TOP_N} 会话")
    for sid, v in sorted(by_sess.items(), key=lambda kv: -kv[1]['cost'])[:TOP_N]:
        print(f"- {v['label'][:30]} | {v['calls']} 次 | {v['cost']:.2f} 元 | 缓存 {v['cache']/1e6:.0f}M")
    print()

# 提醒（成本纪律）
print("## 提醒（checklists/cost.md 省钱原则）")
warn = []
if tot['cost'] > 20: warn.append(f"今日已超 20 元（{tot['cost']:.1f}）——高峰时段长会话是主因，考虑错峰/断会话")
if cache_cost / max(tot['cost'], 0.01) > 0.7: warn.append("缓存读取占 70%+——单会话上下文过长，建议分阶段开新会话")
if tot['calls'] > 500: warn.append("调用次数超 500——批量任务密集，评估是否可合并/降频")
print('\n'.join(f"- {w}" for w in warn) if warn else "- 当前在健康区间（详见 checklists/cost.md）")

# 记录（增量式，2026-08-23 P1-A2 修复 v2：真增量——只统计上次记账时刻后的新调用）
# 原修复 v1（追加全量）会因同日多笔全量快照求和翻倍（实证 46.62+48.2=94.82 重复）；
# 正确语义：每条记录 = 上次 recorded_at → 本次 的增量，--log 按日求和 = 当日真实消费。
if RECORD:
    os.makedirs(os.path.dirname(COST_LOG), exist_ok=True)
    # 读该日最后一条 recorded_at 作为统计起点
    last_ts = None
    if os.path.exists(COST_LOG):
        for l in open(COST_LOG, encoding='utf-8').read().splitlines():
            l = l.strip()
            if not l: continue
            try: e = json.loads(l)
            except Exception: continue
            if e.get('date') != (DAY or day_key(datetime.datetime.now())): continue
            ra = e.get('recorded_at')
            if ra and (last_ts is None or ra > last_ts): last_ts = ra
    since = last_ts or (DAY + 'T00:00:00' if DAY else '00:00:00')
    # 过滤：只保留 last_ts 之后的调用
    if last_ts:
        lt = datetime.datetime.strptime(last_ts, '%Y-%m-%dT%H:%M:%S')
        records = [r for r in records if r[3] > lt]
        if not records:
            print(f"\n✓ {since} 之后无新调用，跳过（避免重复记账）")
            sys.exit(0)
        # 重算增量汇总
        tot = collections.Counter()
        by_win = collections.defaultdict(collections.Counter)
        by_sess = collections.defaultdict(collections.Counter)
        for sid, label, m, ts, i, c, o, r, cost in records:
            win = 'peak' if is_peak(ts) else 'off'
            tot['calls'] += 1; tot['cost'] += cost
            tot['in'] += i; tot['cache'] += c; tot['out'] += o; tot['reason'] += r
            by_win[(m, win)]['cost'] += cost; by_win[(m, win)]['calls'] += 1
            by_sess[sid]['cost'] += cost; by_sess[sid]['calls'] += 1
            by_sess[sid]['cache'] += c; by_sess[sid]['label'] = label or '(主会话)'
        cache_cost = sum(rec[5]/1e6 * (PRICE.get(rec[2], PRICE['deepseek-v4-flash'])['peak' if is_peak(rec[3]) else 'off']['cache']) for rec in records)
        in_cost = sum(rec[4]/1e6 * (PRICE.get(rec[2], PRICE['deepseek-v4-flash'])['peak' if is_peak(rec[3]) else 'off']['in']) for rec in records)
        out_cost = sum(rec[6]/1e6 * (PRICE.get(rec[2], PRICE['deepseek-v4-flash'])['peak' if is_peak(rec[3]) else 'off']['out']) for rec in records)
    entry = {
        'date': DAY or day_key(datetime.datetime.now()),
        'recorded_at': datetime.datetime.now().strftime('%Y-%m-%dT%H:%M:%S'),
        'since': since,
        'calls': tot['calls'],
        'cost': round(tot['cost'], 2),
        'cache_cost': round(cache_cost, 2),
        'in_cost': round(in_cost, 2),
        'out_cost': round(out_cost, 2),
        'input_m': round(tot['in']/1e6, 1),
        'cache_m': round(tot['cache']/1e6, 1),
        'output_m': round(tot['out']/1e6, 1),
    }
    lines = []
    if os.path.exists(COST_LOG):
        lines = [l for l in open(COST_LOG, encoding='utf-8').read().splitlines() if l.strip()]
    lines.append(json.dumps(entry, ensure_ascii=False))
    with open(COST_LOG, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print(f"\n✓ 已追加增量（since {since} → {entry['recorded_at']}，+{entry['cost']} 元）到 {COST_LOG}")
PYEOF
exit $?
