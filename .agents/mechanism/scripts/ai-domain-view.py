#!/usr/bin/env python3
# ─────────────────────────────────────────────────────────────
# 域视图 —— 一条命令答「某个域现在怎么样 · 还欠什么 · 往哪走」
#
# 用法:  python3 .agents/mechanism/scripts/ai-domain-view.py [域名] [--json]
#        不带域名 → 列出全部域及一行摘要
#        域名     → 该域的完整视图（定位 / 现状 / 未修 / 规划 / 关键文档）
#
# 为什么存在（2026-10-04 用户压力测试暴露）:
#   知识**按性质存**（7 顶层，正确），但人**按领域问**（「交易插件怎么样」）——
#   要跨 4 处拼（trading-features / REVIEW / rfc / designs），慢且易漏。
#   本脚本补「按领域取」这一层。
#
# ⚠️ 设计红线：**纯聚合视图，现算不落盘**——落盘就会变成第 7 个数据源，
#    违反「单一权威」（REVIEW.md = 未修项唯一权威）。视图永远现算，所以不会过期。
#
# 域的发现：从 features 主轴的 ID 前缀**自动推导**（不硬编码域清单，
#    新增 trade.* 功能无需改本脚本）。
# ─────────────────────────────────────────────────────────────
import json
import pathlib
import re
import subprocess
import sys
import unicodedata

ROOT = pathlib.Path(subprocess.run(['git', 'rev-parse', '--show-toplevel'],
                                  capture_output=True, text=True).stdout.strip())
AG = ROOT / '.agents'
FEAT = AG / 'knowledge/features/_index.md'
REVIEW = AG / 'records/REVIEW.md'
RFC = AG / 'direction/rfc'
MANUALS = AG / 'knowledge/reference/manuals'
DESIGNS = AG / 'knowledge/reference/designs'
STATUS = AG / 'knowledge/reference/status.md'
PITFALLS = AG / 'rules/assets/pitfalls.md'
WS = AG / 'workspace'

# 域 → 检索关键词（同时匹配中英文；REVIEW 编号用中文，文件名用英文）
# 域 → 检索关键词。**两组来源**：
#   ① features 主轴的 ID 前缀（trade. / learn. / admin. / product. / 无前缀=kernel）
#   ② REVIEW 编号里的「域词」（实测分布：交易 18 · learn 7 · 分享 6 · 工程 5 · UI 4 · 对话 4
#      · 前端 3 · APNs 3 · 文档 3 · 用户 2 · 认知 2 —— 据实补全，否则这些项全落「未归类」）
KW = {
    'trading':  ['交易', '认知', 'trading', 'trade'],
    'learn':    ['学习', 'learn', '整理'],
    'admin':    ['admin', '管理后台', '后台'],
    'product':  ['产品', 'product', '定位', '主动性'],
    'platform': ['推送', 'push', '入口', 'entry', '分享', 'share', '多账号', 'account',
                 '插件模型', 'plugin', '工程', '合规', '发布', '审查', 'APNs'],
    'kernel':   ['记录', '记忆', '问答', 'feed', '简报', 'brief', '时间线', 'timeline',
                 '搜索', 'search', '标签', 'tag', '待办', 'todo', '节律', 'rhythm', '身份', 'identity',
                 'UI', '前端', '对话', '用户', '文档'],
}
# 域显示名与定位（一行）
TITLE = {
    'trading': '交易插件（trading）',
    'learn': '学习插件（learn）',
    'admin': '管理后台（adai-admin）',
    'platform': '平台能力（推送 / 入口 / 多账号 / 插件模型）',
    'kernel': '内核（记录 · 记忆 · 问答 · Feed · 简报 …）',
}
# 「已完成」的判据——注意 `✅ 部分修` / `⏳ 部分落地` **不算完成**（半修 = 还没修完）
# ── 已修判据（**反向**，不枚举写法）──────────────────────────────
# 教训：先用"枚举已完成写法"（已修/已解/已收口/…）→ 每遇到新写法就漏一个
#   （`✅ **已修` 漏过 → 补加粗；`✅ 全落地` 又漏 → 说明枚举法本身错）。
# 反向判据：**含 ✅ 且 ✅ 后不是「部分/半/待/未」= 已修**。
#   ✅ 已修 / ✅ 已实测 / ✅ 全落地 / ✅ **已修  → 已修
#   ✅ 部分修 / ✅ 部分已修 / ✅ 待验证        → **未修**（没修完）
TICK_PAT = re.compile(r'✅')
PARTIAL_PAT = re.compile(r'✅\s*\*{0,2}\s*(?:部分|半|待|未)')


# 「结案」的第二种形态：不修也不算问题（误报/不成立/无需修）——留在未修段没有意义
CLOSED_PAT = re.compile(r'(误报|不成立|无需修|非问题|已撤销)')


def is_fixed(line):
    if TICK_PAT.search(line) and not PARTIAL_PAT.search(line):
        return True
    return bool(CLOSED_PAT.search(line))


# 兼容旧引用名（本文件内多处用 DONE_PAT.search）
class _DoneCompat:
    @staticmethod
    def search(s):
        return True if is_fixed(s) else None


DONE_PAT = _DoneCompat()
# 注：REVIEW 的「是否已修」是**行内自由文本**（没有状态列）→ 任何计数都是**近似**；
#     根治办法是给表格加 `状态` 列（open/fixed/partial），但那是 622 行的大改，待用户拍板。

ANSI = {'b': '\033[1m', 'd': '\033[2m', 'r': '\033[0m', 'c': '\033[36m', 'y': '\033[33m'}


def strip(s):
    return re.sub(r'\x1b\[[0-9;]*m', '', s)


def read(p):
    try:
        return p.read_text(encoding='utf-8', errors='ignore')
    except Exception:
        return ''


# ── ① 域发现：从 features 主轴 ID 前缀推导 ─────────────────────
def feature_rows():
    """只在「## 二、功能清单」段内取行——否则会吃到「## 文件清单」里的意图卡行
    （意图卡文件行不是功能）。"""
    txt = read(FEAT)
    m = re.search(r'## 二、功能清单(.*?)(?=\n## |\Z)', txt, re.S)
    seg = m.group(1) if m else ''
    rows = []
    for line in seg.splitlines():
        cells = [c.strip() for c in line.strip().strip('|').split('|')]
        m = re.match(r'^`([^`]+)`$', cells[0]) if cells else None
        if m and len(cells) >= 6:
            rows.append({'id': m.group(1), 'name': cells[1], 'status': cells[2],
                         'req': cells[3], 'impl': cells[4],
                         'owe': cells[5] if cells[5] not in ('', '—') else ''})
    return rows


# 无前缀但明确属于 platform 的功能 ID（实测：features 主轴里这 6 个没有域前缀）
NO_PREFIX_PLATFORM = {'push', 'entry', 'share-ext', 'media', 'account', 'plugin-model'}


def domain_of(fid):
    if '.' in fid:
        pre = fid.split('.')[0]
        return {'trade': 'trading'}.get(pre, pre)
    if fid in NO_PREFIX_PLATFORM:
        return 'platform'
    return 'kernel'


def domains():
    seen = {}
    for r in feature_rows():
        d = domain_of(r['id'])
        seen.setdefault(d, []).append(r)
    return seen


# ── ② REVIEW：该域的未修项（排除已修）──────────────────────────
def review_for(kws):
    """返回 (未修条目, 编号集合)。已修 = 行内含「✅ 已修」；P0/P3 段也算。"""
    txt = read(REVIEW)
    open_items, ids = [], set()
    cur_sev = None
    for line in txt.splitlines():
        if line.startswith('## '):
            if 'P1' in line: cur_sev = 'P1'
            elif 'P2' in line: cur_sev = 'P2'
            elif '战略' in line: cur_sev = '战略'
            elif 'P0' in line or 'P3' in line: cur_sev = 'P0/P3'
            elif '已修复' in line or '走查' in line: cur_sev = None
            continue
        if cur_sev is None or not line.startswith('|'):
            continue
        if re.match(r'^\|[\s:|-]+\|', line):     # 分隔行
            continue
        cells = [c.strip() for c in line.strip('|').split('|')]
        if len(cells) < 2:
            continue
        cid = cells[0]
        if not re.match(r'^(P\d+-|S\d|战略|\d+$)', cid):
            continue
        if DONE_PAT.search(line):       # 「✅ 部分修 / ⏳」不算已修（DONE_PAT 不匹配它们）
            continue
        # 域归属只看**编号里的域词**（P1-交易15 → 域词「交易」），不看描述文本
        # ——否则 P1-分享7 的描述里出现「交易」二字就会串进 trading 域。
        m2 = re.match(r'^P\d+-(.+?)\d*$', cid)
        tag = m2.group(1) if m2 else cid
        if not any(k in tag or k.lower() == tag.lower() for k in kws):
            continue
        if any(k in tag for k in kws):
            ids.add(re.sub(r'\s.*$', '', cid))
            open_items.append((cur_sev, cid, strip(cells[1])[:96]))
    return open_items, ids


# ── ③ 文件类：rfc / designs / manuals ─────────────────────────
def files_matching(d, kws, suffix='.md'):
    if not d.is_dir():
        return []
    out = []
    for p in sorted(d.glob(f'*{suffix}')):
        if p.name.startswith('_'):
            continue
        n = p.name.lower()
        if any(k.lower() in n for k in kws):
            out.append(p)
    return out


def rfc_status(p):
    m = re.search(r'^status:\s*(\S+)', read(p), re.M)
    return m.group(1) if m else '?'


# ── ④ 输出 ───────────────────────────────────────────────────
def domain_view(name, as_json=False):
    dmap = domains()
    if name not in dmap:
        return None
    feats = dmap[name]
    kws = KW.get(name, [name])
    rv, rv_ids = review_for(kws)
    rfcs = files_matching(RFC, kws)
    drafts = [p for p in rfcs if rfc_status(p) == 'draft']
    des = files_matching(DESIGNS, kws)
    man = files_matching(MANUALS, kws)
    owe = [f for f in feats if f.get('owe')]

    if as_json:
        return {'domain': name, 'features': feats, 'review_open': rv,
                'review_ids': sorted(rv_ids), 'rfcs': [p.name for p in rfcs],
                'drafts': [p.name for p in drafts], 'designs': [p.name for p in des],
                'manuals': [p.name for p in man]}

    B, D, R, C, Y = ANSI['b'], ANSI['d'], ANSI['r'], ANSI['c'], ANSI['y']
    print(f"\n{B}═══ 域视图：{TITLE.get(name, name)} ═══{R}\n")

    # 功能
    st = {}
    for f in feats:
        st[f['status']] = st.get(f['status'], 0) + 1
    dist = ' · '.join(f'{k} {v}' for k, v in sorted(st.items(), key=lambda x: -x[1]))
    print(f"{B}【功能】{R}{len(feats)} 个 —— {dist}")
    for f in feats:
        mark = '⚠️' if f['status'] in ('building', 'rfc', 'idea') else '  '
        print(f"   {mark} `{f['id']}` {f['name'][:44]}  {D}[{f['status']}]{R}")

    # 现状
    print(f"\n{B}【现状】{R}", end='')
    if man:
        for p in man:
            print(f"{p.name}（{len(read(p).splitlines())} 行）", end=' ')
    else:
        print("（无专属手册）", end='')
        print()
    m = re.search(r'端点[：:]\s*\*\*(\d+)\*\*', read(STATUS))
    if m:
        print(f"   全项目端点数 {m.group(1)}（该域占比需查手册端点表）")

    # 未修
    print(f"\n{B}【未修】{R}REVIEW.md 里 {len(rv)} 项{' ' + Y + '(唯一权威)' + R if rv else ' —— 无'}")
    by_sev = {}
    for sev, cid, desc in rv:
        by_sev.setdefault(sev, []).append((cid, desc))
    for sev in ('战略', 'P1', 'P2', 'P0/P3'):
        for cid, desc in by_sev.get(sev, []):
            print(f"   [{sev}] {cid}  {desc}")

    # 规划
    print(f"\n{B}【规划方向】{R}")
    print(f"   RFC {len(rfcs)} 份（{Y}其中 draft {len(drafts)} 份待你拍板{R}）")
    for p in rfcs[:6]:
        s = rfc_status(p)
        print(f"     · {p.stem[:60]}  {D}[{s}]{R}")
    if len(rfcs) > 6:
        print(f"     …（共 {len(rfcs)} 份，见 .agents/direction/rfc/）")
    if des:
        print(f"   设计文档 {len(des)} 份：")
        for p in des:
            print(f"     · {p.name}")

    # 关键文档
    print(f"\n{B}【关键文档】{R}")
    docs = [f'.agents/knowledge/reference/manuals/{p.name}' for p in man] + \
           [f'.agents/knowledge/reference/designs/{p.name}' for p in des]
    for x in docs[:6]:
        print(f"   · {x}")
    print()
    return None



# ── ⑤ 项目全景（overview）─────────────────────────────────────
def overview(as_json=False):
    feats = feature_rows()
    st = {}
    for f in feats:
        st[f['status']] = st.get(f['status'], 0) + 1
    # 未修分档
    txt = read(REVIEW); sev_count = {}
    cur = None
    for line in txt.splitlines():
        if line.startswith('## '):
            if 'P1' in line: cur = 'P1'
            elif 'P2' in line: cur = 'P2'
            elif '战略' in line: cur = '战略'
            elif 'P0' in line or 'P3' in line: cur = 'P0/P3'
            elif '已修复' in line or '走查' in line: cur = None
            continue
        if cur and line.startswith('|') and not re.match(r'^\|[\s:|-]+\|', line) \
           and re.match(r'^\|\s*(P\d+-|S\d|战略|\d+\s*\|)', line) and not DONE_PAT.search(line):
            sev_count[cur] = sev_count.get(cur, 0) + 1
    # 数字
    stxt = read(STATUS)
    tests = re.findall(r'\|\s*(后端 adai-core|前端 adai-app|前端 adai-web|前端 adai-admin)\s*\|\s*\*\*(\d+)\*\*', stxt)
    ep = re.search(r'端点[：:]\s*\*\*(\d+)\*\*', stxt)
    ctrl = re.search(r'Controller[：:]\s*\*\*(\d+)\*\*', stxt)
    # 里程碑
    rtxt = read(ROOT / '.agents/direction/product-roadmap.md')
    ver = re.findall(r'^###\s+(v[\d.]+[^\n—]*?)\s*(?:—|$)', rtxt, re.M)
    # 在制品
    ws = [q for q in WS.rglob('*.md') if not q.name.startswith('_')] if WS.is_dir() else []

    if as_json:
        return {'features': st, 'review_open': sev_count,
                'tests': dict(tests), 'endpoints': ep.group(1) if ep else None,
                'controllers': ctrl.group(1) if ctrl else None,
                'versions': [v.strip() for v in ver], 'workspace': len(ws)}

    B, D, R, C, Y = ANSI['b'], ANSI['d'], ANSI['r'], ANSI['c'], ANSI['y']
    print(f"\n{B}═══ 项目全景 ═══{R}\n")
    dist = ' · '.join(f'{k} {v}' for k, v in sorted(st.items(), key=lambda x: -x[1]))
    print(f"{B}【功能】{R}{len(feats)} 个 —— {dist}")
    print(f"{B}【规模】{R}" + ' · '.join(f'{k.split()[-1]} {v}' for k, v in tests)
          + (f" · 端点 {ep.group(1)}" if ep else '') + (f" · Controller {ctrl.group(1)}" if ctrl else ''))
    if ver:
        print(f"{B}【里程碑】{R}" + ' → '.join(v.strip() for v in ver[-4:]))
        print(f"   {D}进入 v1.0.0 的标准见 .agents/direction/product-roadmap.md §五{R}")
    print(f"{B}【未修】{R}" + ' · '.join(f'{k} {v}' for k, v in sorted(sev_count.items())) or '（无）')
    print(f"{B}【在制品】{R}workspace {len(ws)} 份" + ('   （空 = 当前无进行中的任务 ✅）' if not ws else ''))
    print(f"{B}【域】{R}" + ' · '.join(sorted(domains())))
    print()
    return None


def review_items():
    """**统一解析**：REVIEW 全部未修项（含搁置/复核）。
    返回 [{section, id, text, status}]，status ∈ 未修/搁置/复核。

    ⚠️ 判据（唯一）：
      · fixed  → 被 DONE_PAT 命中（`✅ 已修` / `✅ 已收口` / `✅ **已修` …）→ **排除**
      · 其余   → 保留；其中 `⏸/搁置` → 搁置，`⚠️/复核` → 复核，`✅ 部分修`/`⏳` → **未修**（半修＝没修完）
    此前 ai-guard-unfixed 另有 DONE_MARKS（含裸 `✅`）→ 把 `✅ 部分修` 也当已修，
    导致同一份 REVIEW 两个工具给出 17 vs 40 两个数（2026-10-04 统一）。
    """
    txt = read(REVIEW); items = []; cur = None
    for line in txt.splitlines():
        if line.startswith('## '):
            if line.startswith('## 🔴'):
                cur = line[3:].strip().replace('（未修复）', '').strip()
            else:
                cur = None                     # 非 🔴 段（已修复区/走查/成本）不算未修
            continue
        if not cur or not line.startswith('|') or re.match(r'^\|[\s:|-]+\|\s*$', line):
            continue
        cells = [c.strip() for c in line.strip().strip('|').split('|')]
        if not cells or not re.match(r'^(P\d+-|S\d|战略|\d+$)', cells[0]):
            continue
        if DONE_PAT.search(line):
            continue
        cid = cells[0]
        st = '搁置' if ('⏸' in line or '搁置' in line) else ('复核' if ('⚠️' in line or '复核' in line) else '未修')
        desc = cells[1] if len(cells) > 1 else ''
        items.append({'section': cur, 'id': cid, 'status': st,
                      'text': (desc[:120] + ('…' if len(desc) > 120 else ''))})
    return items


def open_review_ids():
    """全部未修项的编号集合（不分域）——用于算「未归类」。"""
    return {it['id'] for it in review_items()}


def list_domains():
    B, D, R = ANSI['b'], ANSI['d'], ANSI['r']
    print(f"\n{B}═══ 域总览 ═══{R}  {D}（用法：domain <域名>）{R}\n")
    for name, feats in sorted(domains().items()):
        kws = KW.get(name, [name])
        rv, _ = review_for(kws)
        rfcs = files_matching(RFC, kws)
        drafts = [p for p in rfcs if rfc_status(p) == 'draft']
        st = {}
        for f in feats:
            st[f['status']] = st.get(f['status'], 0) + 1
        dist = ' · '.join(f'{k} {v}' for k, v in sorted(st.items(), key=lambda x: -x[1]))
        print(f"  {B}{name:<9}{R} {len(feats):>2} 功能（{dist}） "
              f"· 未修 {len(rv):>2} · RFC {len(rfcs):>2}（draft {len(drafts)}）")
    # 未归类：不属于任何单一域的未修编号（S 编号 / 战略段 / 跨域项）。
    # 不显示会让「某域未修 0」被误读为全绿——各域之和 + 未归类 = 合计。
    all_ids = open_review_ids()
    assigned = set()
    for nm in domains():
        _, ids = review_for(KW.get(nm, [nm]))
        assigned |= ids
    rest = all_ids - assigned
    print(f"  {B}{'未归类':<9}{R} {'':>2}          · 未修 {len(rest):>2}  "
          f"{D}（S 编号 / 战略段 / 跨域——不属于任何单一域）{R}")
    print(f"  {D}合计未修 {len(all_ids)}（各域之和 + 未归类）{R}")
    print()


def main():
    raw = sys.argv[1:]
    if '--review-items' in raw:                 # 独立子命令（不走 args 过滤）
        print(json.dumps(review_items(), ensure_ascii=False))
        return
    args = [a for a in raw if not a.startswith('--')]
    as_json = '--json' in raw
    if not args:                      # 无参数 = 全景 + 域总览（最有用的默认）
        ov = overview(as_json)
        if as_json and ov:
            print(json.dumps(ov, ensure_ascii=False, indent=2))
        else:
            list_domains()
        return
    if args[0] == '--review-items':             # 供 ai-guard-unfixed 复用（统一解析）
        print(json.dumps(review_items(), ensure_ascii=False))
        return
    if args[0] in ('overview', '--overview'):   # 只出全景
        res = overview(as_json)
        if as_json and res:
            print(json.dumps(res, ensure_ascii=False, indent=2))
        return
    name = args[0]
    dmap = domains()
    if name not in dmap:
        print(f"未知域：{name}（可选：{' / '.join(sorted(dmap))}）", file=sys.stderr)
        sys.exit(2)
    res = domain_view(name, as_json)
    if as_json:
        print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
