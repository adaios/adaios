#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 任务前上下文注入（进攻侧核心）— 生成"开工前必读清单"
#
# 用法:  bash ai-engineering/guard-context.sh            # 全部上下文
#        bash ai-engineering/guard-context.sh <主题词>    # 按主题过滤
#        bash ai-engineering/guard-context.sh --write-local  # 收尾：写 AGENTS.local.md 快照（DSH 等新会话自动注入）
# 说明:  每次开工前跑一次，自动汇总 AI 该知道的上下文，不用人提醒：
#         C0 产品心跳（用户是否还在用 — 最高优先级信号，2026-09-13 新增）
#         C1 当前状态（state/_index 指针 → status/REVIEW/task-log）
#         C1.5 主题手册导航（docs/reference/*-features.md 深度文档直读索引）
#         C2 未修项（REVIEW 战略/P1/P2 中与本批相关的）
#         C3 边界（boundaries 原则级）
#         C4 坑（pitfalls 复发信号）
#         C5 相关规范（conventions 按主题）
#         C6 待办（task-log 当前任务）
#        输出 = 一份 Markdown 清单，喂给 AI 作为开工上下文
# ─────────────────────────────────────────────────────────────
set -u

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
WRITE_LOCAL=0
if [ "${1:-}" = "--write-local" ]; then
    WRITE_LOCAL=1
    TOPIC=""
else
    TOPIC="${1:-}"
fi

python3 - "$ROOT" "$TOPIC" "$WRITE_LOCAL" <<'PYEOF'
import re, sys, pathlib

ROOT = pathlib.Path(sys.argv[1])
TOPIC = sys.argv[2] if len(sys.argv) > 2 else ''
WRITE_LOCAL = len(sys.argv) > 3 and sys.argv[3] == '1'
AI = ROOT / 'ai-engineering'

# 快照 = 每轮会话都注入的固定开销，必须控体积（见 checklists/cost.md C7）。
# 2026-09-14：C1 段此前**没有任何条数上限**，单段涨到 3.6KB/25 行，是快照突破 8KB 的唯一大头
# （C2/C4/C5/C6 都有上限，只有 C1 漏了）。现补 LIM_C1 + 收紧各段上限，并在末尾加**总量兜底**
# ——无论源文件怎么膨胀，快照都会被裁回预算内，不再依赖"有人记得看那行警告"。
BUDGET = 8192                          # 快照总预算（字节，cost.md C7）
LIM = 8 if WRITE_LOCAL else 10**9      # C2/C4 行数上限
LIM_C5 = 10 if WRITE_LOCAL else 10**9  # C5 行数上限
LIM_C6 = 10 if WRITE_LOCAL else 60     # C6 行数上限
LIM_C1 = 19 if WRITE_LOCAL else 10**9  # C1 行数上限

def head_file(p, n=15, title=None):
    if not p.exists(): return ''
    lines = p.read_text(encoding='utf-8', errors='ignore').splitlines()
    body = [l for l in lines if not l.startswith('---')]
    # 跳过 frontmatter
    start = 0
    if body and body[0].strip() == '---':
        for i, l in enumerate(body[1:], 1):
            if l.strip() == '---': start = i+1; break
    return '\n'.join(body[start:start+n])

out = []
if WRITE_LOCAL:
    out.append("# AI 开工上下文快照（机器生成，勿手改）")
    out.append(f"> 生成：{__import__('datetime').date.today()} · `guard-context.sh --write-local` · 新会话自动注入；真相源 `docs/`，改源后重跑（gitignore，不入库）。")
    out.append("> 当日成本不在此（隔日失真），开工现跑 `guard-context.sh`。\n")
else:
    out.append(f"# AI 任务上下文清单{('（主题：' + TOPIC + '）') if TOPIC else ''}")
    out.append(f"> 生成时间：{__import__('datetime').date.today()} · 开工前读此清单，不用人提醒\n")

# C0 产品心跳（用户是否还在用 — 最高优先级信号）
# 由来（2026-09-13）：项目一度「手感上停了」，真相是 AI 建设速率高、产品交付中、**用户使用速率→0**
#   （生产记录 09-10 后断档）。旧 guard 只注入「AI 花了多少钱」，没有任何一行是「用户还在不在用」，
#   于是 AI 会一路空转建设。本段把「使用心跳」变成每轮开工第一眼可见的信号。
# 成本：一次只读 ssh（15 分钟 TTL 缓存），失败静默降级 — 绝不阻塞开工。
out.append("## C0 产品心跳（用户是否还在用 — 最高优先级）")
try:
    import subprocess as _sp0, json as _json0, time as _time0, datetime as _dt0
    _cache = AI/'state/usage-cache.json'
    _TTL = 900
    _raw = None; _age = None
    try:
        if _cache.exists():
            _c = _json0.loads(_cache.read_text(encoding='utf-8'))
            _age = _time0.time() - float(_c.get('ts', 0))
            if _age < _TTL: _raw = _c.get('raw'); _age = int(_age)
    except Exception:
        _raw = None
    if _raw is None:
        _remote = (
            "D=/opt/adaios/data/adai; R=$D/records; T=$(date +%F); "
            "last=$(sudo find $R -type f -printf '%TY-%Tm-%Td\n' 2>/dev/null | sort | tail -1); "
            "n7=$(sudo find $R -type f -newermt '-7 days' 2>/dev/null | wc -l); "
            "n14=$(sudo find $R -type f -newermt '-14 days' 2>/dev/null | wc -l); "
            # 不能用 -newermt 'today'：GNU date 把 'today' 解析成**当前时刻**（不是今天 00:00），
            # 于是 find 永远找不到「比现在更新」的文件 → 今日恒为 0（2026-09-16 实测发现并修）
            "today=$(sudo find $R -type f -newermt \"$T\" 2>/dev/null | wc -l); "
            "tr=$(sudo find $D/trading -type f -printf '%TY-%Tm-%Td\n' 2>/dev/null | sort | tail -1); "
            "echo \"LAST=${last:-none}|N7=$n7|N14=$n14|TODAY=$today|TRADING=${tr:-none}\""
        )
        _r = _sp0.run(['ssh', '-o', 'ConnectTimeout=8', '-o', 'BatchMode=yes',
                       'ubuntu@82.156.111.146', _remote],
                      capture_output=True, text=True, timeout=25)
        if _r.returncode == 0 and 'LAST=' in _r.stdout:
            _raw = _r.stdout.strip().splitlines()[-1].strip()
            _age = 0
            try:
                _cache.parent.mkdir(parents=True, exist_ok=True)
                _cache.write_text(_json0.dumps({'ts': _time0.time(), 'raw': _raw}), encoding='utf-8')
            except Exception:
                pass
    if _raw:
        _p = dict(kv.split('=', 1) for kv in _raw.split('|') if '=' in kv)
        _last = _p.get('LAST', 'none')
        _days = '?'
        if _last and _last != 'none':
            try:
                _days = (_dt0.date.today() - _dt0.date.fromisoformat(_last)).days
            except Exception:
                pass
        _fresh = '' if not _age else f'（{_age//60} 分钟前缓存，非实时）'
        if WRITE_LOCAL:
            # 快照 = 每轮注入固定开销，C0 压到 2 行（见 checklists/cost.md C7）
            out.append(f"> 最后记录 **{_last}**（{_days} 天前）· 今日 {_p.get('TODAY','?')} · 近 7 天 {_p.get('N7','?')} · 交易最近 {_p.get('TRADING','?')}{_fresh}")
            out.append("> 每日流程：用户说「**每日巡检**」→ 跑 `bash ai-engineering/guard-prod.sh`，只用人话讲「用户之声 / 有没有新异常 / 心跳趋势」（AGENTS.md 规则 8）")
        else:
            out.append(f"> 最后一条记录：**{_last}**（{_days} 天前）· 今日 **{_p.get('TODAY','?')}** 条 · 近 7 天 **{_p.get('N7','?')}** 条 · 近 14 天 **{_p.get('N14','?')}** 条{_fresh}")
            out.append(f"> 交易模块最近写入：{_p.get('TRADING','?')}")
            if _p.get('TODAY', '0') not in ('0', '?', ''):
                out.append("> 📣 今日有新的真实使用 — 先跑 `bash ai-engineering/guard-prod.sh`：看用户在问什么（用户之声）+ 生产日志有没有新异常")
        _spend = 0.0
        _log = AI/'state/cost-log.jsonl'
        if _log.exists():
            _cut = (_dt0.date.today() - _dt0.timedelta(days=7)).isoformat()
            for _l in _log.read_text(encoding='utf-8', errors='ignore').splitlines():
                try:
                    _rec = _json0.loads(_l)
                    if _rec.get('date', '') >= _cut: _spend += float(_rec.get('cost') or 0)
                except Exception:
                    pass
        if not WRITE_LOCAL:
            out.append(f"> 近 7 天：AI 投入 **{_spend:.1f} 元** / 你的记录 **{_p.get('N7','?')}** 条")
        try:
            _d = int(_days)
        except Exception:
            _d = -1
        if _d >= 3:
            out.append(f"> ⚠️ **用户已 {_d} 天未记录** — 本轮优先修「让人愿意用」的摩擦（入口/可信度/顺手），**不要继续加新功能**（近 7 天 AI 投入 {_spend:.1f} 元）；新功能先问「这会让他明天多用一次吗？」")
    else:
        out.append("> （未能读取生产使用数据：ssh 不可达或无缓存；不影响开工，但本轮请**先问用户最近用没用**）")
except Exception as _e0:
    out.append(f"> （使用心跳读取失败: {_e0}）")
out.append("")

# C1 当前状态
out.append("## C1 当前状态")
status = (ROOT/'docs/reference/status.md')
if status.exists():
    c1 = 0
    for l in status.read_text(encoding='utf-8').splitlines():
        if '**' in l and ('|' in l or '：' in l):
            item = l.strip()
            # 2026-09-14：三类行不进快照（它们把「公安备案/域名到期」等硬信息挤出了上限）：
            # ① 版本沿革「上一版本…」= 历史，真相源在 status.md / change-log；
            # ② 「更新规则…」= 文档元信息，不是状态；
            # ③ 「HEAD（≠）= 生产…」= 版本状态已由「生产当前版本…」行承载，重复。
            if WRITE_LOCAL and ('上一版本' in item or '此前版本' in item
                                or '更新规则' in item or 'HEAD' in item):
                continue
            if WRITE_LOCAL and len(item) > 58:
                item = item[:58] + '…（详见 status.md）'
            out.append(f"- {item}")
            c1 += 1
            if c1 >= LIM_C1:
                out.append(f"- …（快照精简，C1 共 {c1} 条，详见 status.md）")
                break
out.append("")

# C1.5 主题手册导航（深度文档直读索引，2026-08-22 新增）
# 约定：docs/reference/*-features.md = 各主题功能手册（trading-features.md 等），
#       新主题手册放入即自动纳入本导航；TOPIC 过滤时只留相关主题。
out.append("## C1.5 主题手册导航（深度文档，按需直读）")
handbook_dir = ROOT/'docs/reference'
handbook_count = 0
if handbook_dir.exists():
    for f in sorted(handbook_dir.glob('*-features.md')):
        title = f.name
        desc = ""
        txt = f.read_text(encoding='utf-8', errors='ignore')
        fm = re.search(r'^---\n(.*?)\n---', txt, re.S)
        if fm:
            tm = re.search(r'^title:\s*(.+)$', fm.group(1), re.M)
            dm = re.search(r'^description:\s*(.+)$', fm.group(1), re.M)
            if tm: title = tm.group(1).strip()
            if dm: desc = dm.group(1).strip()
        row = f"- **{title}** → `docs/reference/{f.name}`"
        if desc:
            row += f"（{desc[:50]}…）" if len(desc) > 50 else f"（{desc}）"
        if TOPIC and TOPIC not in row: continue
        out.append(row)
        handbook_count += 1
if handbook_count == 0:
    out.append("- （暂无主题手册；深度文档见 docs/README.md）")
out.append("")

# C2 未修项（REVIEW）
out.append("## C2 未修项（REVIEW.md）")
review = ROOT/'docs/review/REVIEW.md'
DONE_MARKS = ('✅', '已修', '出表', '已确认', '已移除', '已闭环', '不成立', '误报', '清零')
if review.exists():
    lines = review.read_text(encoding='utf-8').splitlines()
    in_unfixed = False
    count = 0
    for l in lines:
        if l.startswith('## 🔴') or l.startswith('## 战略'):
            in_unfixed = True; continue
        if l.startswith('## ✅') or l.startswith('## '):
            if in_unfixed: break
        if in_unfixed and l.startswith('|') and '|' in l[1:]:
            # 2026-08-23（P1-1）：已修/出表项不注入快照——快照每轮注入，
            # 喂已修项会误导 AI 重复修 + 浪费每轮固定上下文（cost.md C7）
            if any(m in l for m in DONE_MARKS):
                continue
            cells = [c.strip() for c in l.strip('|').split('|')]
            if len(cells) >= 3 and cells[0] and (cells[0][0].isalpha() or cells[0][0].isdigit()):
                row = f"- {cells[0]}: {cells[1][:60]}"
                if TOPIC and TOPIC not in row: continue
                out.append(row)
                count += 1
                if count >= LIM:
                    out.append(f"- …（快照精简，共 {count} 条，详见 REVIEW.md）")
                    break
    if count == 0:
        out.append("- （无未修项，或本主题无关）")
out.append("")

# C3 边界
out.append("## C3 原则级边界（boundaries.md）")
b = AI/'assets/boundaries.md'
if b.exists():
    for l in b.read_text(encoding='utf-8').splitlines():
        if l.startswith('| B') and '|' in l[1:]:
            cells = [c.strip() for c in l.strip('|').split('|')]
            if len(cells) >= 3:
                out.append(f"- {cells[0]} {cells[1][:60]}")
out.append("")

# C4 坑（pitfalls）
out.append("## C4 已知坑（pitfalls.md 复发信号）")
p = AI/'assets/pitfalls.md'
if p.exists():
    count = 0
    for l in p.read_text(encoding='utf-8').splitlines():
        if l.startswith('|') and '|' in l[1:] and not l.startswith('|:'):
            cells = [c.strip() for c in l.strip('|').split('|')]
            if len(cells) >= 6 and cells[0] and cells[0][0].isalpha() and cells[0] != '坑':
                row = f"- {cells[0]}: {cells[1][:50]}（复发信号：{cells[5][:40]}）"
                if TOPIC and TOPIC not in row: continue
                out.append(row)
                count += 1
                if count >= LIM:
                    out.append(f"- …（快照精简，共 {count} 条，详见 pitfalls.md）")
                    break
out.append("")

# C5 规范（按主题）
out.append("## C5 规范（conventions.md）")
c = AI/'assets/conventions.md'
if c.exists():
    count = 0
    for l in c.read_text(encoding='utf-8').splitlines():
        if l.startswith('| C') or l.startswith('| D') or l.startswith('| W'):
            if '|' in l[1:]:
                cells = [x.strip() for x in l.strip('|').split('|')]
                if len(cells) >= 3:
                    row = f"- {cells[0]}: {cells[1][:60]}"
                    if TOPIC and TOPIC not in row: continue
                    out.append(row)
                    count += 1
                    if count >= LIM_C5:
                        out.append(f"- …（快照精简，共 {count} 条，详见 conventions.md）")
                        break
out.append("")

# C6.5 成本纪律（每次开工提醒：今天烧了多少 + 省钱原则）
out.append("## C6.5 成本纪律（省钱原则见 checklists/cost.md）")
if WRITE_LOCAL:
    out.append("> （快照不含当日成本：隔日失真；开工时现跑 `guard-context.sh` 获取）")
else:
    try:
        import subprocess as _sp
        _cost = _sp.run(['bash', str(AI/'guard-cost.sh'), '--day',
                         __import__('datetime').date.today().isoformat()],
                        capture_output=True, text=True, timeout=25)
        _lines = [l for l in _cost.stdout.splitlines() if l.startswith('>')]
        out.append('\n'.join('> ' + l[2:].strip() for l in _lines[:4]) if _lines else '> （guard-cost 未输出，跳过）')
        for l in _cost.stdout.splitlines():
            if l.startswith('- 今日已超') or l.startswith('- 缓存读取占') or l.startswith('- 调用次数超'):
                out.append('> ⚠️ ' + l.lstrip('- '))
        if TOPIC and ('cost' in TOPIC or '成本' in TOPIC or '省钱' in TOPIC):
            c = AI/'checklists/cost.md'
            if c.exists():
                body = c.read_text(encoding='utf-8').splitlines()
                start = 0
                for i, l in enumerate(body):
                    if l.startswith('# 成本纪律'): start = i; break
                for l in body[start+1:start+26]:
                    if l.startswith('## ') or l.startswith('| C') or l.startswith('### S'):
                        out.append('> ' + l.strip())
    except Exception as _e:
        out.append(f'> （guard-cost 调用失败: {_e}）')
out.append("")

# C6 待办（task-log 当前任务区）
out.append("## C6 待办（task-log.md 当前任务）")
tl = ROOT/'docs/reference/task-log.md'
if tl.exists():
    lines = tl.read_text(encoding='utf-8').splitlines()
    count = 0
    for l in lines:
        if l.startswith('|') and '|' in l[1:] and not l.startswith('|:'):
            cells = [x.strip() for x in l.strip('|').split('|')]
            if len(cells) >= 3 and cells[0] and (cells[0][0].isalpha() or cells[0][0].isdigit()):
                if WRITE_LOCAL and cells[1] in ('模块名', '含义', '说明'):
                    continue  # 表头行，不进快照
                row = f"- {cells[0]}: {cells[1][:70]}"
                if TOPIC and TOPIC not in row: continue
                out.append(row)
                count += 1
                if count >= LIM_C6:
                    out.append(f"- …（快照精简，共 {count} 条，详见 task-log.md）")
                    break
out.append("")

body = '\n'.join(out)
if WRITE_LOCAL:
    # 总量兜底（2026-09-14）：超预算时按段优先级自动裁剪，保证快照永不超注入预算。
    # 裁剪顺序 = 信息重要度倒序（C6 待办 → C5 规范 → C4 坑 → C3 边界 → C2 未修 → C1.5 → C1），
    # C0 心跳与头部不动。此前只有一行「请精简源文件」的警告——没人看就等于没有闸。
    before = len(body.encode('utf-8'))
    if before > BUDGET:
        lines = body.split('\n')
        for seg in ('C6 待办', 'C5 规范', 'C4 已知坑', 'C3 原则级边界',
                    'C2 未修项', 'C1.5 主题手册', 'C1 当前状态'):
            s = next((i for i, l in enumerate(lines) if l.startswith('## ') and seg in l), None)
            if s is None:
                continue
            e = next((i for i in range(s + 1, len(lines)) if lines[i].startswith('## ')), len(lines))
            # 删段内末尾内容行（段末是空行，标题至少保留一行）
            while len('\n'.join(lines).encode('utf-8')) > BUDGET and e - s > 2:
                del lines[e - 2]
                e -= 1
        body = '\n'.join(lines)
    target = ROOT / 'AGENTS.local.md'
    target.write_text(body + '\n', encoding='utf-8')
    size = len(body.encode('utf-8'))
    print(f'[guard-context] 快照已写入 AGENTS.local.md（{size} 字节 / {body.count(chr(10)) + 1} 行）')
    if size > BUDGET:
        print(f'[guard-context] ⚠️ 仍超 {BUDGET} 字节预算（各段已裁到最小），请精简源文件')
    elif before > BUDGET:
        print(f'[guard-context] 已按段优先级自动裁剪 {before} → {size} 字节（预算 {BUDGET}）；源文件建议同步精简')
else:
    print(body)
PYEOF
exit $?
