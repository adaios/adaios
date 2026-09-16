#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 生产日报（每日流程）— 生产日志 + 真实对话卡片，一条命令看全
#
# 用法:
#   bash ai-engineering/guard-prod.sh              # 今天 + 近 7 天趋势
#   bash ai-engineering/guard-prod.sh --days 14    # 趋势窗口拉长
#   bash ai-engineering/guard-prod.sh --date 2026-09-16   # 补看某天
#   bash ai-engineering/guard-prod.sh --json       # 只出原始 JSON（喂给 AI / 二次处理）
#
# 为什么有这个脚本（2026-09-16 用户「看看生产日志，看看生产对话卡片，
# 其实这个可以固定为每日流程」）:
#   两侧合起来才是一个闭环——
#     ① 生产日志  = 系统侧真相：服务活着吗、什么在反复报错、有没有 5xx
#     ② 对话卡片  = 用户侧真相：他今天真的问了什么、在骂什么、要什么
#   2026-09-16 首次跑出来的实据：当天 6 张卡里 4 张是产品缺陷反馈
#   （卡片乱序 / 交易重复展示 / 输入框表情包多余 / 背面菜单对新用户过载），
#   而 09-11~09-15 连续 5 天 0 张卡——「有人在用」这件事，只有这里看得见。
#
# 观测边界（如实声明，别把它当全能）:
#   - Caddy 访问日志 2026-09-15 才开（之前无记录），用量趋势从那天起
#   - 登录前匿名请求不带 X-User-Id，无法区分用户
#   - GET /trading/review 的 404 是**设计语义**（复盘未生成=404，前端轮询用），
#     已在输出里标注为「正常」，不算故障
#
# 依赖: ssh 免密到生产（ubuntu@ + ~/.ssh/id_ed25519）、生产侧免密 sudo
# ─────────────────────────────────────────────────────────────
set -u
cd "$(dirname "$0")/.."

HOST="${ADAI_PROD_HOST:-ubuntu@82.156.111.146}"
SSH_KEY="${ADAI_PROD_KEY:-$HOME/.ssh/id_ed25519}"
DAYS=7
ONLY_DATE=""
JSON_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --days)   DAYS="${2:-7}"; shift 2 ;;
    --days=*) DAYS="${1#*=}"; shift ;;
    --date)   ONLY_DATE="${2:-}"; shift 2 ;;
    --date=*) ONLY_DATE="${1#*=}"; shift ;;
    --json)   JSON_ONLY=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "未知参数: ${1}（--help 看用法）" >&2; exit 2 ;;
  esac
done

# 生产是公网 IP + 域名，本机代理会拦，显式绕过
export no_proxy="82.156.111.146,adaiadai.com,api.adaiadai.com,${no_proxy:-localhost}"

PROBE_PY=$(cat <<'PROBE_EOF'
import json, subprocess, re, collections, datetime, pathlib, sys

DAYS = int(sys.argv[1]) if len(sys.argv) > 1 else 7
WANT = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else None
TODAY = datetime.date.fromisoformat(WANT) if WANT else datetime.date.today()
DATA = pathlib.Path('/opt/adaios/data')
out = {'host': 'prod', 'date': TODAY.isoformat()}

def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout

# ── ① 服务存活 ──
svcs = ['adai-core', 'adaios-web', 'adaios-admin', 'adaios-app', 'caddy']
out['services'] = {s: sh('systemctl is-active ' + s).strip() for s in svcs}

# ── ② 应用日志（当日）──
jr = sh(f'journalctl -u adai-core --since "{TODAY.isoformat()} 00:00:00" '
        f'--until "{TODAY.isoformat()} 23:59:59" --no-pager')
err_lines, warn = [], collections.Counter()
for line in jr.splitlines():
    if re.search(r'\sERROR\s', line):
        err_lines.append(line.strip()[-260:])
    m = re.search(r'WARN\s+\d+\s+---\s+(?:\[[^\]]*\]\s+)+(\S+)\s+:', line)
    if m:
        warn[m.group(1).rsplit('.', 1)[-1]] += 1
out['error_lines'] = err_lines[:10]
out['error_total'] = len(err_lines)
out['warn'] = warn.most_common(14)
out['warn_total'] = sum(warn.values())

# ── ③ 对话卡片（用户之声 = 本日报的核心）──
def cards_on(day):
    found = []
    for u in sorted(d for d in DATA.iterdir() if d.is_dir() and (d / 'records').is_dir()):
        p = (u / 'records' / 'cards' / f'{day.year:04d}' / f'{day.month:02d}' / f'{day.day:02d}')
        if not p.is_dir():
            continue
        for f in sorted(p.glob('*.md')):
            txt = f.read_text(errors='replace')
            ms = re.search(r'^summary:\s*(.+)$', txt, re.M)
            mc = re.search(r'^createdAt:\s*(\S+)', txt, re.M)
            q = ''
            for line in txt.splitlines():
                if line.startswith('用户：'):
                    q = line[3:].strip()
                    break
            found.append({
                'user': u.name,
                'id': f.stem,
                'time': (mc.group(1)[11:16] if mc else
                         datetime.datetime.fromtimestamp(f.stat().st_mtime).strftime('%H:%M')),
                'summary': (ms.group(1).strip() if ms else '')[:70],
                'q': q[:90],
            })
    return found

out['cards'] = cards_on(TODAY)
trend = []
for i in range(DAYS - 1, -1, -1):
    d = TODAY - datetime.timedelta(days=i)
    trend.append((d.strftime('%m-%d'), len(cards_on(d))))
out['trend'] = trend

# ── ④ 公网用量（Caddy 访问日志，2026-09-15 才开）──
# 4xx/5xx 必须分四类，否则日报天天是假警报（2026-09-16 实测：
# 当天 49 个 API 错误里，扫描器 12 + 部署探针 7 + 设计语义 11，真待关注 0）
SCAN_PAT = ('/.env', '/wp-', '/wp/', '/wordpress', '/backup', '/old/', '/new/',
            '/test.php', '/.git', '/admin.php', '/config', '/xmlrpc', '/sitemap',
            '/robots.txt', '/favicon.ico', '/.ssh', '/phpmyadmin', '/vendor/',
            '/.aws', '/shell', '/db/', '/.well-known/', '/cgi-bin')
PROBE_UA = ('curl', 'python-requests', 'wget', 'go-http', 'httpie', 'postman', 'java/')
BENIGN_API = {
    '/api/v1/trading/review': '复盘未生成=404，前端轮询的正常语义',
    '/api/v1/auth/me': '登录态过期=401，重新登录即可',
    '/api/v1/feed': '登录态过期=401',
    '/api/v1/me/plugins': '登录态过期=401',
    '/api/v1/brief/cached': '登录态过期=401',
}

def classify(ua, uri, method, kind, status):
    u = (ua or '').lower()
    if uri in BENIGN_API:
        return 'benign'
    if any(k in u for k in PROBE_UA):
        return 'probe'
    if kind == 'web':
        # 静态站（SPA）不会对合法路由回 404——web 侧 404 只可能是路径不存在
        if status == 404:
            return 'scanner'
        if method not in ('GET', 'HEAD'):
            return 'scanner'
        return 'user'
    # API 侧：浏览器 UA 打 API = 扫描器（真实客户端只有 Dart / 扩展）
    if status == 404 and uri == '/':
        return 'scanner'
    if 'mozilla' in u or any(p in uri for p in SCAN_PAT):
        return 'scanner'
    if not u:
        return 'scanner'
    return 'user'

def caddy(path, since, kind):
    by_day, errs, ep = collections.Counter(), collections.Counter(), collections.Counter()
    try:
        fh = open(path, errors='replace')
    except OSError:
        return None
    with fh:
        for line in fh:
            try:
                d = json.loads(line)
            except Exception:
                continue
            day = datetime.datetime.fromtimestamp(d['ts']).strftime('%Y-%m-%d')
            if day < since:
                continue
            by_day[day] += 1
            s = d.get('status', 0)
            if s >= 400:
                req = d['request']
                uri = req['uri'].split('?')[0]
                ua = (req.get('headers', {}).get('User-Agent') or [''])[0]
                cls = classify(ua, uri, req.get('method', ''), kind, s)
                errs[f'{day}|{cls}'] += 1
                ep[(cls, s, uri)] += 1
    return {'by_day': dict(by_day), 'errors': dict(errs),
            'top_err': [[c, s, u, n] for (c, s, u), n in ep.most_common(40)]}

since = (TODAY - datetime.timedelta(days=DAYS - 1)).isoformat()
out['api'] = caddy('/var/log/caddy/api-access.log', since, 'api')
out['web'] = caddy('/var/log/caddy/adaiadai-access.log', since, 'web')
out['benign'] = BENIGN_API


print(json.dumps(out, ensure_ascii=False))
PROBE_EOF
)

PROBE_B64=$(printf '%s' "$PROBE_PY" | base64 | tr -d '\n')

RAW=$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o BatchMode=yes \
      "$HOST" "base64 -d > /tmp/_adai_probe.py && sudo python3 /tmp/_adai_probe.py $DAYS '$ONLY_DATE'; rm -f /tmp/_adai_probe.py" \
      <<< "$PROBE_B64" 2>/dev/null)

if [ -z "$RAW" ] || ! printf '%s' "$RAW" | head -1 | grep -q '^{'; then
  echo "✗ 取不到生产数据（SSH 不通 / 生产不可达 / sudo 受限）" >&2
  echo "  手工确认: ssh -i $SSH_KEY $HOST 'systemctl is-active adai-core'" >&2
  exit 1
fi

if [ "$JSON_ONLY" = "1" ]; then
  printf '%s\n' "$RAW"
  exit 0
fi

ADAI_PROD_RAW="$RAW" python3 - <<'RENDER_EOF'
import json, os

d = json.loads(os.environ['ADAI_PROD_RAW'])
CN = {
    'EastMoneyKlineDataSource': '行情源(东财)取数失败',
    'TencentMarketDataSource': '行情源(腾讯)取数失败',
    'KlineService': 'K线服务告警（多为上游取数失败的连带）',
    'AuthFilter': '鉴权拦截（多为登录态过期的 401，非攻击）',
    'EquityCurveService': '资金曲线缺收盘价（该日盈亏未计入）',
    'LlmResponseParser': 'LLM 回复非 JSON → 走降级解析',
    'DeepSeekAiClient': 'DeepSeek 调用异常',
    'LearnDigestAppService': 'learn 消化失败（链接/素材没读懂）',
    'ApnsPushChannel': 'APNs 推送告警',
    'RecordFileRepository': '删除时记录不存在（多为对话卡的正常无记录）',
    'CardFileRepository': '删除时卡片不存在',
    'MemoryService': '删除时记忆不存在',
    'DefaultHandlerExceptionResolver': '请求参数/方法不合法（多为客户端调用姿势问题）',
    'TradingScreenshotAppService': '截图入账告警',
    'GlmVisualAiClient': 'GLM 视觉理解失败（429=限流，稍后重试即可）',
    'RecordAppService': '记录写入告警',
    'AiService': 'AI 调用告警',
}

def hr(t):
    print(f"\n\033[1m── {t} ──\033[0m")

print(f"\033[1m═══ 生产日报 {d['date']} ═══\033[0m")

# 服务
svc = d['services']
bad = [k for k, v in svc.items() if v != 'active']
hr('服务')
if bad:
    print(f"  \033[31m✗ 异常: {', '.join(f'{k}={svc[k]}' for k in bad)}\033[0m")
else:
    print(f"  ✅ {' / '.join(svc)} 全 active")
print(f"  ERROR {d['error_total']} · WARN {d['warn_total']}")
for line in d['error_lines']:
    print(f"  \033[31m✗ {line}\033[0m")

# 告警人话
if d['warn']:
    hr('告警（人话 + 计数）')
    for k, n in d['warn']:
        print(f"  {n:>6}  {CN.get(k, k)}")

# 用量（4xx/5xx 必须分类，否则天天假警报）
CLS_CN = {'scanner': '扫描器/爬虫', 'probe': '部署探针/脚本', 'benign': '设计语义', 'user': '★待关注'}
CLS_CO = {'scanner': '\033[90m', 'probe': '\033[90m', 'benign': '\033[32m', 'user': '\033[31m'}

def usage(tag, blk):
    if not blk:
        return
    today = blk['by_day'].get(d['date'], 0)
    prev = sorted((k, v) for k, v in blk['by_day'].items() if k < d['date'])
    delta = f"（上一记录日 {prev[-1][0]} {prev[-1][1]}）" if prev else ''
    buckets = {}
    for k, v in blk['errors'].items():
        day, cls = k.split('|', 1)
        if day == d['date']:
            buckets[cls] = buckets.get(cls, 0) + v
    total = sum(buckets.values())
    parts = ' · '.join(f"{CLS_CN.get(c, c)} {n}" for c, n in
                       sorted(buckets.items(), key=lambda x: -x[1]))
    print(f"  {tag}: {today} 次{delta} · 4xx/5xx {total}" + (f" = {parts}" if parts else ""))
    shown = 0
    for cls, s, u, n in blk['top_err']:
        if cls != 'user':
            continue
        note = d.get('benign', {}).get(u, '')
        print(f"      {CLS_CO['user']}{s} {u} ×{n}{'  ← ' + note if note else ''}\033[0m")
        shown += 1
    if total and not shown:
        print("      \033[32m✅ 没有一条是真实用户侧的失败\033[0m")

if d.get('api') or d.get('web'):
    hr('公网用量（Caddy，2026-09-15 起有记录）')
    usage('API', d.get('api'))
    usage('Web', d.get('web'))

# 用户之声 ★
hr(f"用户之声（{d['date']} 真实对话卡片）")
if not d['cards']:
    print("  （今天没有新对话卡片）")
for c in d['cards']:
    print(f"  \033[36m{c['time']}\033[0m [{c['user']}] {c['q']}")
    if c['summary']:
        print(f"          └ {c['summary']}")

# 趋势
hr(f'心跳（近 {len(d["trend"])} 天对话卡片数）')
bar = ' · '.join(f"{day}:{n}" for day, n in d['trend'])
total = sum(n for _, n in d['trend'])
print(f"  {bar}")
print(f"  合计 {total} 张" + ("   \033[31m← 连续多日为 0：要么没用，要么入口断了\033[0m" if total == 0 else ""))
print()
RENDER_EOF
