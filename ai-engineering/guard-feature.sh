#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 功能索引守护检查（guard-feature）—— docs/features/ 功能主轴自检
#
# 用法:  bash ai-engineering/guard-feature.sh
#        bash ai-engineering/guard-feature.sh --root /tmp/fixture   # 测试夹具（默认仓库根）
# 背景:  RFC 20261001 §3.2——功能索引层「一行一卡」是否真实：字段齐、链接可达、
#        状态合法、卡内没被抄进实现细节、卡没超长、卡文件已登记。
# 退出码: 0 = PASS；1 = FAIL
# 反例回归: python3 ai-engineering/tests/guard-feature-fixture.py —— 新增/改写检查项时同步补坏样本
#           （只测正例＝假绿温床：首版切卡正则被一级标题挡住、卡计数恒 0，正例照样 PASS）
#
# 检查项:
#   F0 索引表非空（防「解析失败 → 假绿」）
#   F1 索引行字段齐（ID/功能/状态/实现出处 必填；需求出处与欠着允许「—」）
#   F2 索引层全部 markdown 链接可达（**剥 #锚点后**判文件存在——锚点链接是规格允许的）
#   F3 标 ⚠️ 的行必须写明缺因（文件缺失/无实体/无 RFC）
#   F4 状态枚举合法 + **关键词粗对拍**（shipped 不得配「待建/未做/无专章/待生长」）
#      ⚠️ 这不是 RFC 原承诺的「状态与证据一致」（那需要每行带可机器验证的证据字段）；
#         现状＝状态由人填 + 一条粗对拍，**已如实降格**，见 docs/features/_index.md 头部与 RFC §十。
#   F5 RFC status 枚举合法（只查 date >= 2026-10-01 的新文件；**缺 date 一律按新文件强制**，
#      存量 65 篇实测均有 date，故无误伤）
#   F6 「欠着」必须是 REVIEW.md 里真实存在的编号（**词边界匹配**，防 P2-测试1 命中 P2-测试11）
#   F7 意图卡内无实现细节（类名/端点/路径/源文件/方法调用）
#   F8 单张意图卡 ≤12 行（口径：**含 `##` 标题行、不含空行**）
#   F9 卡文件必须在 _index.md 里以链接形式登记（防孤儿卡文件）
#   F10 防假绿：文件里有 `## ` 段落却识别不出任何「卡」（卡标题格式＝标题含反引号 ID）
#
# 边界（如实声明，不假装覆盖）：
#   · 需求出处只做**存在性**校验，**不做相关性校验**（「链接可达但内容无关」抓不到，靠人工/审查官）
#   · 覆盖数（功能该有几行）无人校验；状态列由人填
# ─────────────────────────────────────────────────────────────
set -u

ROOT_OVERRIDE=""
if [ "${1:-}" = "--root" ]; then
  ROOT_OVERRIDE="${2:?--root 需要一个路径}"
  cd "${ROOT_OVERRIDE}" || exit 1
else
  cd "$(dirname "$0")/.."
fi
ROOT="$(pwd)"

python3 - "$ROOT" <<'PYEOF'
import re, sys, pathlib

ROOT = pathlib.Path(sys.argv[1])
FEAT = ROOT / 'docs/features'
INDEX = FEAT / '_index.md'
REVIEW = ROOT / 'docs/review/REVIEW.md'
RFC = ROOT / 'docs/rfc'

CUTOFF = '2026-10-01'          # F5 生效日：此前的老文件不强制（存量渐进）
STATUS_OK = {'idea', 'rfc', 'designed', 'building', 'shipped', 'retired'}
RFC_STATUS_OK = {'draft', 'approved', 'implemented', 'superseded', 'withdrawn'}
CARD_MAX_LINES = 12
MISSING_HINTS = ('文件缺失', '无实体', '无 RFC', '缺失')
STALE_WORDS = ('待建', '未做', '无专章', '待生长')

DETAIL_PATTERNS = [
    (r'\b\w+(?:Controller|Service|Repository|Client|Dto|Request|Response)\b', '类名'),
    (r'\b(?:GET|POST|PUT|DELETE|PATCH)\s+/', 'HTTP 方法 + 路径'),
    (r'/api/v\d[\w/{}.-]*', '端点路径（/api/…）'),
    (r'/(?:learn|trading|push|auth|records|todos|memory|feed)/', '路径（接口或知识目录——卡里都不该出现）'),
    (r'\b\w+\.(?:java|dart|ts|js|yml|yaml|json|xml)\b', '源文件名'),
    (r'\b\w+\(\)', '方法调用'),
]

fails = []

if not INDEX.exists():
    print('FEATURE-GUARD: 1 FAIL')
    print('    F0 索引不存在 docs/features/_index.md')
    sys.exit(1)

text = INDEX.read_text(encoding='utf-8')

# ── 解析功能行：首列是反引号包裹的 ID ──────────────────────────
rows = []
for ln, line in enumerate(text.splitlines(), 1):
    if not line.startswith('|'):
        continue
    cells = [c.strip() for c in line.strip().strip('|').split('|')]
    if len(cells) < 2 or not re.fullmatch(r'`[\w.\-]+`', cells[0]):
        continue
    rows.append((ln, cells))

# F0 防假绿
if not rows:
    fails.append('F0 索引表解析出 0 个功能行（表格格式变了？宁可报错也不要假绿）')

# F1 / F3 / F4 / F6
# REVIEW 缺失时不能静默跳过 F6（假绿防线：宁可报错，也不要「查不了就当过」）
if not REVIEW.exists():
    fails.append('F6 缺 docs/review/REVIEW.md——「欠着」编号无法校验，按 FAIL 处理（不做假绿）')
review_text = REVIEW.read_text(encoding='utf-8') if REVIEW.exists() else ''

for ln, cells in rows:
    fid = cells[0].strip('`')
    if len(cells) != 6:
        fails.append(f'F1 {INDEX.name}:{ln} `{fid}` 列数 {len(cells)} ≠ 6')
        continue
    for idx, name in ((1, '功能'), (2, '状态')):
        if cells[idx] in ('', '—', '-'):
            fails.append(f'F1 {INDEX.name}:{ln} `{fid}` 字段缺失：{name}')
    if cells[4] == '':
        fails.append(f'F1 {INDEX.name}:{ln} `{fid}` 字段缺失：实现出处（确实没有就写「—」，不要留空）')
    # F3 ⚠️ 必须写明缺因
    if '⚠' in ' '.join(cells) and not any(h in ' '.join(cells) for h in MISSING_HINTS):
        fails.append(f'F3 {INDEX.name}:{ln} `{fid}` 标了 ⚠️ 但没写缺因（缺一分文件缺失/无实体/无 RFC）')
    # F4 状态枚举 + 关键词粗对拍（**不是**「状态与证据一致」，见文件头边界声明）
    if cells[2] not in STATUS_OK:
        fails.append(f'F4 {INDEX.name}:{ln} `{fid}` 状态 `{cells[2]}` 不在枚举 {sorted(STATUS_OK)}')
    if cells[2] == 'shipped' and any(w in cells[4] for w in STALE_WORDS):
        fails.append(f'F4 {INDEX.name}:{ln} `{fid}` 状态是 shipped，但实现出处写着「待建/未做/无专章」——状态与事实不符')
    # F6 欠着编号必须真实存在（词边界：P2-测试1 不得命中 P2-测试11）
    raw = cells[5]
    if raw not in ('—', '-', ''):
        ids = re.findall(r'(?:P\d+-[^\s、,，|]+|S-?\d+)', raw)
        if not ids:
            fails.append(f'F6 {INDEX.name}:{ln} `{fid}` 「欠着」既非「—」也不含 REVIEW 编号：{raw}')
        for i in ids:
            if review_text and not re.search(re.escape(i) + r'(?![0-9A-Za-z])', review_text):
                fails.append(f'F6 {INDEX.name}:{ln} `{fid}` 欠着编号 `{i}` 在 REVIEW.md 里查无此条')

# F2 链接可达（索引层全部 markdown 链接；**先剥 #锚点**——锚点链接是规格允许的）
for f in [INDEX] + [p for p in sorted(FEAT.rglob('*.md')) if p.name != '_index.md']:
    body = f.read_text(encoding='utf-8')
    for m in re.finditer(r'\]\(([^)\s]+)\)', body):
        t = m.group(1).strip()
        if t.startswith(('http://', 'https://', '#')):
            continue
        target = t.split('#', 1)[0]          # 剥锚点
        if not target:
            continue
        if not (f.parent / target).resolve().exists():
            fails.append(f'F2 {f.relative_to(ROOT)}: 死链 {t}')

# ── F5 RFC status 枚举（存量渐进；缺 date 按新文件强制）────────
rfc_skipped = 0
if RFC.exists():
    for p in sorted(RFC.glob('*.md')):
        if p.name == '_index.md':
            continue
        body = p.read_text(encoding='utf-8')
        # frontmatter 按「第二个 ---」解析（原实现用 [:900] 窗口，description 一长就漏 —— 对抗审查 B2）
        fm = re.match(r'^---\n(.*?)\n---', body, re.S)
        head = fm.group(1) if fm else ''
        ms = re.search(r'^status:\s*(.+)$', head, re.M)
        if not ms:
            continue                        # 连 status 都没有：不归本检查管（frontmatter 契约另管）
        md = re.search(r'^date:\s*(\S+)', head, re.M)
        is_new = (md is None) or (md.group(1) >= CUTOFF)   # 缺 date → 保守当新文件（存量 65 篇实测均有 date）
        if not is_new:
            rfc_skipped += 1
            continue
        val = ms.group(1).strip().split()[0].strip('"\'')
        if val not in RFC_STATUS_OK:
            where = p.name if md else f'{p.name}（缺 date 字段）'
            fails.append(f'F5 {where}: status `{val}` 不在枚举 {sorted(RFC_STATUS_OK)}')

# ── F7 / F8 / F9 / F10 意图卡 ─────────────────────────────────
card_files = [p for p in sorted(FEAT.rglob('*.md')) if p.name != '_index.md']
card_count = 0
for p in card_files:
    # F9 登记判定：索引里必须以链接形式出现该文件名（原实现用子串包含 —— 对抗审查 C6）
    if f']({p.name})' not in text and f'](./{p.name})' not in text:
        fails.append(f'F9 {p.relative_to(FEAT)}: 未在 _index.md 里以链接形式登记（孤儿卡文件）')
    body = p.read_text(encoding='utf-8')
    body = re.sub(r'^---\n.*?\n---\n', '', body, count=1, flags=re.S)   # 去 frontmatter
    sections = re.split(r'^## ', body, flags=re.M)[1:]
    cards = [s for s in sections if '`' in s.splitlines()[0]]           # 卡标题＝标题里含反引号 ID
    # F10 防假绿：有 ## 段落却一张卡都没识别出来（格式写歪了 → F7/F8 会静默失效）
    if sections and not cards:
        fails.append(f'F10 {p.relative_to(FEAT)}: 有 {len(sections)} 个 ## 段落但识别不出任何卡'
                     f'（卡标题格式＝`## \\`ID\\` · 名称`，见 _index.md §三）')
    for sec in cards:
        card_count += 1
        title = sec.splitlines()[0].strip()[:40]
        for pat, why in DETAIL_PATTERNS:
            m = re.search(pat, sec)
            if m:
                fails.append(f'F7 {p.name} 「{title}」: 卡内出现实现细节 {m.group(0)}（{why}）——实现一律链接出去')
        n = len([x for x in sec.splitlines() if x.strip()])
        if n > CARD_MAX_LINES:
            fails.append(f'F8 {p.name} 「{title}」: {n} 行 > {CARD_MAX_LINES}（口径：含标题行、不含空行）')

if fails:
    print(f'FEATURE-GUARD: {len(set(fails))} FAIL')
    for x in sorted(set(fails)):
        print('   ', x)
    sys.exit(1)

print(f'FEATURE-GUARD: PASS ({len(rows)} 功能 · {len(card_files)} 卡文件 · {card_count} 张卡 · RFC status 存量跳过 {rfc_skipped})')
PYEOF
exit $?
