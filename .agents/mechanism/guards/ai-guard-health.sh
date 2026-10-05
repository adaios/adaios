#!/usr/bin/env bash
# AI 上下文工程 · 健康总检（ai-guard-health）—— 一条命令回答「整个 .agents/ 健不健康」
#
# 用法:
#   bash .agents/mechanism/guards/ai-guard-health.sh          # 人话报告（默认）
#   bash .agents/mechanism/guards/ai-guard-health.sh --json   # JSON（喂 AI / 二次处理）
#   bash .agents/mechanism/guards/ai-guard-health.sh --full   # 含提示级明细（体积全文 / 新鲜度逐个）
#   bash .agents/mechanism/guards/ai-guard-health.sh --fix    # 先跑 structure/meta 的 --fix，再体检
#
# 为什么有这个脚本（2026-10-04 用户「我需要一个脚本维持整个 .agents/ 的健康」）:
#   现有 14 个守卫各管一摊（structure 管目录 / meta 管文件 / skills 管格式…），
#   但**没有一个总入口**能回答「整个工程健不健康、缺什么」。本脚本是那个入口。
#
# 设计原则（避坑：pitfalls 二十三「提新建机制前先盘点已有机制」）:
#   ① **不重写已有判据** —— 维度 1/2 直接调用 ai-guard-structure / ai-guard-meta，
#      只解析它们的结论；本脚本**不重复实现**它们查过的东西。
#   ② 本脚本新增的是它们**没有的「内容规范层」**：命名合规 · 体积红线 · 契约完备 · 新鲜度。
#
# 六个维度:
#   ① 结构完整  —— 两件套齐备 · 清单⇄实际（调 ai-guard-structure）
#   ② 元数据一致 —— frontmatter 图谱 / lines / 孤儿 / 正文路径（调 ai-guard-meta）
#   ③ 命名合规  —— kebab-case · ≤32 字符 · 域前缀必选（按 assets/naming-spec.md §四）
#   ④ 体积红线  —— >300 提示 / >500 必拆（§七 9 的未实现项）
#   ⑤ 契约完备  —— _directory.md 必填段齐备
#   ⑥ 新鲜度    —— frontmatter updated 过旧（提示级）
#
# 退出码: 0 = 健康（可含警告）；1 = 有 FAIL（① 或 ② 不过，或 ③⑤ 有硬问题）
# ─────────────────────────────────────────────────────────────
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"   # 层级无关（2026-10-04 加固：原 ../.. 是隐式位置假设） || exit 1

python3 - "$@" <<'PYEOF'
import os, re, sys, json, subprocess, datetime, pathlib

ARGS = sys.argv[1:]
AS_JSON = '--json' in ARGS
FULL    = '--full' in ARGS
DO_FIX  = '--fix'  in ARGS
ROOT = pathlib.Path('.')
AG   = ROOT / '.agents'

# ══════════════ 维度 ① ② ：调用现有守卫（不重写判据）══════════════
def run_guard(name):
    cmd = ['bash', f'.agents/mechanism/guards/{name}.sh'] + (['--fix'] if DO_FIX else [])
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    out = (r.stdout or '') + (r.stderr or '')
    return r.returncode == 0, out

if DO_FIX:
    run_guard('ai-guard-structure'); run_guard('ai-guard-meta')

ok_struct, out_struct = run_guard('ai-guard-structure')
ok_meta,   out_meta   = run_guard('ai-guard-meta')

def tail_lines(txt, n=6):
    ls = [l for l in txt.splitlines() if l.strip()]
    return ls[-n:] if ls else []

# ══════════════ 维度 ③ ：命名合规（naming-spec §四）══════════════
DOMAINS = ('task-', 'docs-', 'code-', 'ux-', 'data-', 'ai-')
NAMING_DIRS = ('skills', 'roles', 'guards', 'scripts')
KEBAB = re.compile(r'^[a-z0-9]+(-[a-z0-9]+)*$')
MAXLEN = 32
# §6.6「明确不在改名范围」+ 两类**产品侧**资产（非 AI 工程机制，不适用域前缀）
NAMING_EXEMPT = {
    'guard.sh',        # 产品侧守护（G1–G9 检查后端代码），非 AI 工程机制
    'serve_static.py', # 生产静态服务器（自 docs/deployment 归位）
    'cadence-lib.sh',  # §6.6：库文件，等第二个库出现再统一
}

def count_headings(txt, level):
    """数某级的 markdown 标题——**必须跳过代码块**（`# 注释` 会被误判；
    实测 backend-deployment.md 的"88 个 H1"全是 shell 注释）。"""
    in_code = False; n = 0; pat = '#' * level + ' '
    for line in txt.splitlines():
        if line.lstrip().startswith('```'):
            in_code = not in_code; continue
        if in_code:
            continue
        if line.startswith(pat) and not line.startswith(pat + '#'):
            n += 1
    return n

def check_headings():
    """H1 唯一性——一个文档只应有一个 H1（文档标题），章节走 H2/H3。
    2026-10-04 实测：3 份文档把 H1 当章节标题（memory-os-design 25 个 / VISION 11 个 / frontend-reference 3 个）。"""
    bad = []
    for p in sorted(AG.rglob('*.md')):
        try:
            txt = p.read_text(encoding='utf-8')
        except Exception:
            continue
        h1 = count_headings(txt, 1)
        if h1 > 1:
            bad.append((str(p.relative_to(AG)), h1))
    return bad

def check_naming():
    bad = []
    for d in NAMING_DIRS:
        base = AG / d
        if not base.is_dir():
            continue
        for p in sorted(base.rglob('*')):
            if p.is_dir():
                continue
            if p.name.startswith(('_', '.')):     # 元文件（_index / _directory）与占位符（.gitkeep）
                continue
            if p.name in NAMING_EXEMPT:
                continue
            if p.name == 'SKILL.md':              # 官方目录布局；域前缀看**父目录名**
                stem = p.parent.name
            else:
                stem = p.stem
            rel = str(p.relative_to(AG))
            if not KEBAB.match(stem):
                bad.append((rel, f'非 kebab-case（{stem}）'))
            elif len(stem) > MAXLEN:
                bad.append((rel, f'超 {MAXLEN} 字符（{len(stem)}）'))
            elif not stem.startswith(DOMAINS):
                bad.append((rel, f'缺域前缀（六选一：{" ".join(x.rstrip("-") + "-" for x in DOMAINS)}）'))
    return bad

# ══════════════ 维度 ④ ：体积红线（§七 9 未实现项）══════════════
RED, WARN = 500, 300
# 天然长且有正当理由的（写在规范里的除外——这里给**显式白名单**，不是沉默豁免）
VOLUME_EXEMPT = [
    (r'^reference/api-spec\.md$',              '端点契约表（机器对拍，按域增长）'),
    (r'^reference/feature-reference\.md$',     '功能参考（全量明细）'),
    (r'^records/(REVIEW|change-log)\.md$',     '账本（滚动追加）'),
    (r'^assets/pitfalls\.md$',                 '坑清单（累计追加）'),
    (r'^assets/ai-context-engineering\.md$',   '体系总览（本工程的唯一全图）'),
    (r'^rfc/',                                 '决策记录（含未采纳的备选与理由）'),
]

def volume_exempt(rel):
    for pat, why in VOLUME_EXEMPT:
        if re.match(pat, rel):
            return why
    return None

# 红线判据（2026-10-04 校准）：**测的是「结构是否失控」，不是「行数」**——
#   初版只按行数报，6 份"超线"里 5 份其实章节清晰（55~117 行/章），是**假阳性**；
#   真问题只有 1 份：1107 行挤在 4 章里（277 行/章，单章 837 行）。
#   ⇒ >500 行 **且** 平均每章 >150 行 = 结构失控（该拆/重组）；否则只是"长而有组织"。
DENSITY_RED = 150

def check_volume():
    red, warn = [], []   # red=结构失控 · warn=长但有组织
    for p in sorted(AG.rglob('*.md')):
        rel = str(p.relative_to(AG))
        try:
            txt = p.read_text(encoding='utf-8')
        except Exception:
            continue
        n = len(txt.splitlines())
        if n <= WARN:
            continue
        secs = max(count_headings(txt, 2) + count_headings(txt, 3), 1)
        dens = round(n / secs)
        why = volume_exempt(rel)
        item = (rel, n, secs, dens, why)
        if n > RED and dens > DENSITY_RED and not why:
            red.append(item)          # 结构失控（豁免类不受此判）
        else:
            warn.append(item)         # 长但有组织 / 已豁免
    return red, warn

# ══════════════ 维度 ⑤ ：契约完备（_directory.md 必填段）══════════════
REQUIRED_SEC = ('职责边界', '依赖关系', '约束', '维护动作')

def check_contracts():
    bad = []
    for d in sorted([AG] + [x for x in AG.rglob('*') if x.is_dir()]):
        f = d / '_directory.md'
        if not f.exists():
            continue
        t = f.read_text(encoding='utf-8')
        secs = set(re.findall(r'^##\s*([^\n#]+)', t, re.M))
        secs = {s.strip().split('（')[0].strip() for s in secs}
        miss = [s for s in REQUIRED_SEC if s not in secs]
        if miss:
            rel = str(f.relative_to(AG)) if f != AG / '_directory.md' else '_directory.md'
            bad.append((rel, miss))
    return bad

# ══════════════ 维度 ⑥ ：新鲜度（提示级）══════════════
STALE_DAYS = 90

def check_freshness():
    stale, today = [], datetime.date.today()
    for p in sorted(AG.rglob('*.md')):
        try:
            head = p.read_text(encoding='utf-8')[:1200]
        except Exception:
            continue
        m = re.search(r'^updated:\s*(\d{4}-\d{2}-\d{2})', head, re.M)
        if not m:
            continue
        try:
            d = datetime.date.fromisoformat(m.group(1))
        except Exception:
            continue
        days = (today - d).days
        if days > STALE_DAYS:
            stale.append((str(p.relative_to(AG)), days))
    return sorted(stale, key=lambda x: -x[1])

# ══════════════ 汇总 ══════════════
naming   = check_naming()
headings = check_headings()
vol_red, vol_warn = check_volume()
contracts = check_contracts()
stale   = check_freshness()

# 子目录概览
# 被 git 忽略的顶层子目录（本机状态 / **工具出口位**，如 .agents/skills）＝不进 git ⇒ 不参与概览与两件套判定
# （2026-10-05 加：恢复 .agents/skills 出口时，它被当成受管目录、概览里报「缺两件套」。同判据见 ai-guard-structure）
def _ignored_top():
    dirs = [x for x in AG.iterdir() if x.is_dir()]
    if not dirs:
        return set()
    import subprocess
    p = subprocess.run(["git", "check-ignore", "--stdin"],
                       input="\n".join(str(x) for x in dirs), capture_output=True, text=True)
    return {pathlib.Path(ln.strip()).name for ln in p.stdout.splitlines() if ln.strip()}

_IGNORED_TOP = _ignored_top()

def dir_overview():
    rows = []
    for d in sorted([x for x in AG.iterdir() if x.is_dir()]):
        rel = d.name
        if rel == 'state' or rel in _IGNORED_TOP:
            continue
        allf = [p for p in d.rglob('*') if p.is_file()]
        mds  = [p for p in allf if p.suffix == '.md']
        idx = (d / '_index.md').exists(); dr = (d / '_directory.md').exists()
        big = sum(1 for p in mds if len(p.read_text(encoding='utf-8', errors='ignore').splitlines()) > WARN)
        rows.append({'dir': rel, 'files': len(allf), 'pair': idx and dr,
                     'over_warn': big,
                     'contract_miss': any(c[0].startswith(rel + '/') or c[0] == rel + '/_directory.md'
                                          for c in contracts)})
    return rows

overview = dir_overview()

# 健康分（每项 FAIL 扣分，仅作观感，不参与退出码）
score = 100
score -= 0 if ok_struct else 25
score -= 0 if ok_meta else 25
score -= min((len(naming) + len(headings)) * 2, 15)
score -= min(len(vol_red) * 2, 10)
score -= min(len(contracts) * 2, 10)
score = max(score, 0)

hard_fail = (not ok_struct) or (not ok_meta) or bool(naming) or bool(headings) or bool(contracts)

now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M')

if AS_JSON:
    print(json.dumps({
        'when': now, 'score': score,
        'structure_ok': ok_struct, 'meta_ok': ok_meta,
        'structure_tail': tail_lines(out_struct), 'meta_tail': tail_lines(out_meta),
        'naming': [{'path': a, 'why': b} for a, b in naming],
        'headings': [{'path': a, 'h1_count': b} for a, b in headings],
        'volume_red': [{'path': a, 'lines': b, 'sections': c, 'per_section': d, 'exempt': e} for a, b, c, d, e in vol_red],
        'volume_warn': [{'path': a, 'lines': b, 'sections': c, 'per_section': d, 'exempt': e} for a, b, c, d, e in vol_warn],
        'contracts': [{'path': a, 'missing': b} for a, b in contracts],
        'stale': [{'path': a, 'days': b} for a, b in stale],
        'dirs': overview,
    }, ensure_ascii=False, indent=2))
    sys.exit(1 if hard_fail else 0)

# ── 人话报告 ──
P, F, W, I = '✅', '❌', '⚠️ ', '💤'
print(f"\n\033[1m═══ AI 上下文工程 · 健康总检（{now}）═══\033[0m\n")
print(f"\033[1m【总评】{score} / 100 —— "
      f"{'结构完好' if ok_struct and ok_meta else '结构有问题'}，"
      f"{len(naming)} 处命名待正 · {len(vol_red)} 份超红线 · {len(contracts)} 处契约不全\033[0m\n")

def head(mark, title, detail):
    print(f"  {mark} {title:<34} {detail}")

head(P if ok_struct else F, '① 结构完整（两件套 ⇄ 清单）',
     tail_lines(out_struct, 1)[0] if out_struct else '')
head(P if ok_meta else F, '② 元数据一致（frontmatter/lines/孤儿）',
     tail_lines(out_meta, 1)[0] if out_meta else '')
head(P if not (naming or headings) else F, '③ 格式合规（命名 + H1 唯一）',
     'PASS' if not (naming or headings) else f'{len(naming)} 处命名 · {len(headings)} 份 H1 滥用')
head(P if not vol_red else W, f'④ 结构失控（>{RED} 行 且 >{DENSITY_RED}/章）',
     'PASS' if not vol_red else f'{len(vol_red)} 份需处理' + (f' · 另 {len(vol_warn)} 份长但有组织' if vol_warn else ''))
head(P if not contracts else F, '⑤ 契约完备（_directory.md 必填段）',
     'PASS' if not contracts else f'{len(contracts)} 处缺段')
head(P if not stale else I, f'⑥ 新鲜度（updated >{STALE_DAYS} 天）',
     'PASS' if not stale else f'{len(stale)} 份陈旧（提示级）')

if not ok_struct:
    print('\n\033[1m── ① 结构问题 ──\033[0m')
    for l in tail_lines(out_struct, 8): print('   ', l)
if not ok_meta:
    print('\n\033[1m── ② 元数据问题 ──\033[0m')
    for l in tail_lines(out_meta, 8): print('   ', l)
if naming:
    print('\n\033[1m── ③ 命名待正（按 naming-spec §四；§6.6 豁免已排除）──\033[0m')
    for a, b in naming[:FULL and 99 or 8]: print(f"    · {a} —— {b}")
    if not FULL and len(naming) > 8: print(f"    …（共 {len(naming)} 处，--full 看全）")
if vol_red:
    print(f'\n\033[1m── ④ 结构失控（>{RED} 行 且 平均每章 >{DENSITY_RED} 行）──\033[0m')
    for a, n, secs, dens, why in vol_red:
        print(f"    · {a} —— {n} 行 / {secs} 章 = **{dens} 行每章**  ★ 需拆分或重组分节")
if FULL and vol_warn:
    print(f'\n\033[1m── ④b 长但有组织（>{WARN} 行）──\033[0m')
    for a, n, secs, dens, why in vol_warn:
        print(f"    · {a} —— {n} 行 / {secs} 章 = {dens} 行每章" + (f"（豁免：{why}）" if why else ''))
if contracts:
    print('\n\033[1m── ⑤ 契约缺段 ──\033[0m')
    for a, miss in contracts: print(f"    · {a} —— 缺 {' / '.join(miss)}")
if FULL and stale:
    print(f'\n\033[1m── ⑥ 陈旧文档（>{STALE_DAYS} 天未更新）──\033[0m')
    for a, d in stale: print(f"    · {a} —— {d} 天")

print('\n\033[1m── 子目录概览 ──\033[0m')
for r in overview:
    mark = P if (r['pair'] and not r['contract_miss']) else F
    extra = '' if r['pair'] else '  ⚠️ 缺两件套'
    if r['contract_miss']: extra += '  ⚠️ 契约缺段'
    if r['over_warn']: extra += f"  · {r['over_warn']} 份 >{WARN} 行"
    print(f"  {mark} {r['dir']:<12} {r['files']:>3} 份{extra}")

print()
if hard_fail:
    print('\033[31m❌ 有硬问题——修完再继续（③⑤ 见上；①② 跑各自守卫看详情）\033[0m')
    if not DO_FIX:
        print('   可先试：bash .agents/mechanism/guards/ai-guard-health.sh --fix')
    sys.exit(1)
print('\033[32m✅ 健康——① ② ③ ⑤ 全过（④ ⑥ 为提示级）\033[0m')
sys.exit(0)
PYEOF
