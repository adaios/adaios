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
# P2-工程7（2026-09-16）：生产到底在跑哪份代码——deploy.sh 部署时落在 backend/DEPLOYED 的构建来源
# （backend 目录是 adaios:adaios 750，ubuntu 进不去 → 必须 sudo 读）
out['deployed'] = sh('sudo cat /opt/adaios/backend/DEPLOYED 2>/dev/null').strip()
# 2026-09-16：三个静态产物各自的时间戳——**发版最容易漏 admin**（本次实测：jar / web / app-web
# 都发了，admin 还停在 09-08、落后整整 8 天，而没有任何地方看得出来）。摆在这里，谁落后一眼可见。
out['artifacts'] = sh(
    "for d in web app-web admin; do "
    "t=$(date -r /opt/adaios/$d '+%m-%d %H:%M' 2>/dev/null); "
    "[ -n \"$t\" ] && printf '%s=%s  ' \"$d\" \"$t\"; "
    "done").strip()

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

# 2026-09-18：心跳口径补「真实动作」——图片/文字记录（records/YYYY/MM/rec_<yyyymmdd>_*.md）。
# 起因：用户当天在 App 里发了截图（截图入账 → 落 record + 图片），但**对话卡片为 0**，
# 日报显示「今天 0 张」像他没在用。截图入账不建对话卡片，所以卡片数不是活跃度上限。
def records_on(day):
    n = 0
    for u in sorted(d for d in DATA.iterdir() if d.is_dir() and (d / 'records').is_dir()):
        p = (u / 'records' / f'{day.year:04d}' / f'{day.month:02d}')
        if not p.is_dir():
            continue
        n += len(list(p.glob(f'rec_{day.strftime("%Y%m%d")}_*.md')))
    return n

trend, trend_acts = [], []
for i in range(DAYS - 1, -1, -1):
    d = TODAY - datetime.timedelta(days=i)
    c = len(cards_on(d))
    trend.append((d.strftime('%m-%d'), c))
    trend_acts.append((d.strftime('%m-%d'), c + records_on(d)))
out['trend'] = trend
out['trend_acts'] = trend_acts

# ── ④ 公网用量（Caddy 访问日志，2026-09-15 才开）──
# 4xx/5xx 必须分四类，否则日报天天是假警报（2026-09-16 实测：
# 当天 49 个 API 错误里，扫描器 12 + 部署探针 7 + 设计语义 11，真待关注 0）
SCAN_PAT = ('/.env', '/wp-', '/wp/', '/wordpress', '/backup', '/old/', '/new/',
            '/test.php', '/.git', '/admin.php', '/config', '/xmlrpc', '/sitemap',
            '/robots.txt', '/favicon.ico', '/.ssh', '/phpmyadmin', '/vendor/',
            '/.aws', '/shell', '/db/', '/.well-known/', '/cgi-bin',
            # 2026-09-18：**不是本产品的路由命名空间**——探测这些路径的一律是扫描器。
            # 当日实测：单一 IP 31.56.58.165 用 UA「metabase-cve-2026-72898-detect/1.0
            # (benign detection probes only)」GET/POST `/api/session/reset_password`
            # 与 `/api/session/properties`（我们根本没有这两个路由）→ 原分类落到
            # 「★待关注」，日报每天误报一次（用户问「今日巡检还有其他问题不」的由头）。
            '/api/session', '/api/v1/session', '/actuator', '/druid', '/solr/',
            '/jenkins', '/console', '/metabase', '/geoserver', '/nacos')
PROBE_UA = ('curl', 'python-requests', 'wget', 'go-http', 'httpie', 'postman', 'java/')
# 2026-09-18：漏洞扫描器/探针的 UA 特征串（它们通常自报家门）——单列一组、**优先于部署探针判定**，
# 免得「别人的扫描器」被记成「我们自己的部署脚本」（语义要分清）。
SCANNER_UA = ('nuclei', 'zgrab', 'masscan', 'nikto', 'nmap', 'metabase-cve', 'cve-', '-detect/')
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
    if any(k in u for k in SCANNER_UA):
        return 'scanner'
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
    # S-凭据1（2026-09-17 B4 批）：外部令牌的付费动作闸门——「同一把钥匙换了 IP」或
    # 「付费动作超频」都只写 WARN（不阻断正常使用），所以必须由日报主动捞出来给人看。
    'ApiTokenGuard': '⚠ 外部令牌异常使用（同一把钥匙换 IP / 付费动作超频）——到「学习」页看看是不是该收回那把钥匙',
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
# P2-工程7：生产代码版本——2026-09-16 想给「分享失败」加一行日志时才发现，
# 没有它根本答不出「生产跑的是哪个 commit、工作区当时脏不脏」。
_dep = d.get('deployed') or ''
if _dep:
    _f = dict(l.split('=', 1) for l in _dep.splitlines() if '=' in l)
    print(f"  生产代码 {(_f.get('commit') or '?')[:7]} · 部署于 {_f.get('deployedAt', '?')}"
          f" · 当时工作区脏文件 {_f.get('dirtyFiles', '?')}")
    if _f.get('commitSubject'):
        print(f"    {_f['commitSubject'][:72]}")
else:
    print("  生产代码 unknown（还没有 backend/DEPLOYED——这是加上部署记录之前的版本）")
if d.get('artifacts'):
    print(f"  静态产物 {d['artifacts']}（三处都该与 jar 同一次发版；哪个明显落后就是漏发了）")
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
# P2-工程8（2026-09-17）：空态引导 chip 触发的问题**不是用户真实意图**——09-17 那条
# 「你能干什么？」就是空态引导产生的，却被日报读成用户主动提问（本轮巡检据此误判过一次，
# 把老用户当成「新用户在试探能力边界」）。这里显式标注，避免信号源被自家产品缺陷污染
# （P1-UI14 修好后该类记录自然消失）。
GUIDE_PROMPTS = ('你能干什么？', '你有什么特别的能力？', '我该怎么用你？')
guided_count = 0
for c in d['cards']:
    q = c['q'] or ''
    is_guided = any(g in q for g in GUIDE_PROMPTS)
    if is_guided:
        guided_count += 1
    print(f"  \033[36m{c['time']}\033[0m [{c['user']}] {q}")
    if is_guided:
        print("          \033[33m⚠ 空态引导 chip 触发（非用户主动提问）——不计入「用户之声」\033[0m")
    if c['summary']:
        print(f"          └ {c['summary']}")
if guided_count:
    print(f"  \033[33m（今日 {guided_count} 张为空态引导触发、已标注；真实提问 {len(d['cards']) - guided_count} 张）\033[0m")

# 趋势
hr(f'心跳（近 {len(d["trend"])} 天：对话卡片 / 真实动作）')
acts = dict(d.get('trend_acts') or [])
bar = ' · '.join(f"{day}:{n}" + (f"({acts[day]})" if acts.get(day, n) != n else "")
                 for day, n in d['trend'])
total = sum(n for _, n in d['trend'])
acts_total = sum(n for _, n in (d.get('trend_acts') or d['trend']))
print(f"  {bar}")
print(f"  合计 {total} 张卡片 · {acts_total} 次真实动作（卡片 / 图片记录）"
      + ("   \033[31m← 连续多日为 0：要么没用，要么入口断了\033[0m" if acts_total == 0 else ""))
print()
RENDER_EOF

# ── 到期倒数（REVIEW P2-APNs5，2026-09-17）──────────────────────────────────────
# 为什么塞进每日巡检：这些日子**只会被忘记**——iOS 描述文件/付费账号到期当天 App 直接
# 打不开（2026-08-26 已经吃过一次「7 天过期」的亏），而没人会主动去翻文档。
# 日期真相源：docs/reference/status.md（改期请两处同步）。
# 注意：hr 是上面 Python 渲染块里的函数，shell 侧不存在（2026-09-17 修复
# 「line 334: hr: command not found」）；这里用 printf 复刻同一视觉样式。
printf '\n\033[1m── %s ──\033[0m\n' "到期倒数（30 天内标红）"
check_expiry() {
    local label="$1" date="$2" note="$3" days
    local target
    target=$(date -j -f "%Y-%m-%d" "$date" +%s 2>/dev/null || date -d "$date" +%s 2>/dev/null)
    [ -z "$target" ] && { printf "  · %s（%s）\n" "$label" "$date"; return; }
    days=$(( (target - $(date +%s)) / 86400 ))
    if [ "$days" -lt 0 ]; then
        printf "  \033[31m✗ %s 已过期 %d 天！%s\033[0m\n" "$label" "$(( -days ))" "$note"
    elif [ "$days" -lt 30 ]; then
        printf "  \033[31m! %s 还有 %d 天到期 —— %s\033[0m\n" "$label" "$days" "$note"
    else
        printf "  · %s 还有 %d 天（%s）\n" "$label" "$days" "$date"
    fi
}
check_expiry "iOS 描述文件 / 付费账号" "2027-09-13" "到期当天 App 打不开：提前续费 + 重签（见 docs/deployment/ios-release.md §到期与应急）"
check_expiry "公安联网备案期限" "2026-09-30" "ICP 后 30 天内必须办完（REVIEW P1-合规1，www.beian.gov.cn）"
check_expiry "域名 adaiadai.com" "2027-01-30" "DNSPod 续费"
echo
