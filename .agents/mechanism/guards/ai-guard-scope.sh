#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 交付完备性守卫（ai-guard-scope）—— scope 对照表自检
#
# 用法:  bash .agents/mechanism/guards/ai-guard-scope.sh
#        bash .agents/mechanism/guards/ai-guard-scope.sh --root /tmp/fixture   # 测试夹具（默认仓库根）
# 背景:  P1-交易101（设计 §6 前端整体未交付且未声明，一路绿灯过审/合并/上线：
#        「审查只覆盖正确性、不覆盖完备性」）→ 提案
#        .agents/workspace/_meta/proposal-scope-completeness-20261006.md
#        —— 把完备性做成和正确性一样硬：一张表（scope）+ 一个守卫（本脚本）。
# 退出码: 0 = PASS；1 = FAIL
# 触发:  收工（ship）前必跑；无 scope 表的任务不检查（启用初期对存量不强制）
# 反例回归: python3 .agents/mechanism/guards/tests/guard-scope-fixture.py —— 新增/改写检查项时同步补坏样本
#
# 检查项（逐 scope 表）:
#   S0 防假绿（scope 表解析出 ≥1 数据行；设计稿 §6 解析出 ≥10 条目——解析失败宁可报错）
#   S1 设计 §6 条目集 ⊆ scope 表 `#` 列（每个条目都要有交代，哪怕「非本任务」）
#   S2 计划/状态枚举合法 + 计划=本批时状态必须闭合（收尾不许留 —）
#   S3 状态=已交付 → 证据非空 + `路径#关键词` 实测存在/命中（`case:` 前缀豁免，人工核）
#   S4 未交付/下批 → 去向非空；LEDGER / REVIEW 锚点必须可查
#   S5 统计（NOTE）：本批计划 N / 已交付 X / 未交付 Y（含逐条去向）
#
# 边界（如实声明，不假装覆盖）:
#   · S3 机查到「代码引用级」（路径 + 关键词命中）；界面真正可用靠验收走查 + 审核官
#   · 不查「设计 §6 本身是否完整」——那是设计审核的职责
#   · 「去向=批 N」类只核非空（批任务的承接由下一轮 scope 表核）
# ─────────────────────────────────────────────────────────────
set -u

ROOT_OVERRIDE=""
if [ "${1:-}" = "--root" ]; then
  ROOT_OVERRIDE="${2:?--root 需要一个路径}"
  cd "${ROOT_OVERRIDE}" || exit 1
else
  cd "$(git rev-parse --show-toplevel)"   # 层级无关
fi
ROOT="$(pwd)"

python3 - "$ROOT" <<'PYEOF'
import re, sys, pathlib

ROOT = pathlib.Path(sys.argv[1])
WS = ROOT / '.agents/workspace'
REVIEW = ROOT / '.agents/records/REVIEW.md'

TEXT_EXT = {'.java', '.dart', '.ts', '.js', '.sh', '.py', '.md', '.json',
            '.yml', '.yaml', '.kts', '.xml', '.html', '.css', '.txt'}
MAX_FILES = 3000          # 目录型证据的扫描上限（防炸）
MAX_SIZE = 2_000_000      # 单文件大小上限（跳过超大文件）

PLAN_OK = {'本批', '下批', '不做', '非本任务'}
STATUS_OK = {'已交付', '未交付', '—', '-'}
SPECIAL_IDS = ('资金层', '择时', '行情')

fails = []
notes = []


def rel(p):
    try:
        return str(p.relative_to(ROOT))
    except ValueError:
        return str(p)


def parse_design_ids(baseline):
    """从设计稿「§6 三端呈现」表提取条目集；解析失败返回 None（宁可报错，不假绿）"""
    if baseline is None or not baseline.exists():
        return None
    text = baseline.read_text(encoding='utf-8', errors='ignore')
    m = re.search(r'^#{2,4}\s*6\.\s*三端呈现\s*$', text, re.M)
    if not m:
        return None
    body = text[m.end():]
    e = re.search(r'^#{2,4}\s+\S', body, re.M)   # 下一节边界
    scope = body[:e.start()] if e else body
    ids = set()
    for line in scope.splitlines():
        line = line.strip()
        if not line.startswith('|'):
            continue
        cells = [c.strip() for c in line.strip('|').split('|')]
        if not cells:
            continue
        c0 = cells[0]
        ids.update(re.findall(r'R-\d\d', c0))
        for sp in SPECIAL_IDS:
            if sp in c0:
                ids.add(sp)
    return ids if ids else None


def parse_scope(text):
    """解析 scope 表 → (baseline 路径, [(行号, ids, cells), ...])"""
    baseline = None
    m = re.search(r'^>\s*基线：\s*([^\s（(]+)', text, re.M)
    if m:
        raw = re.sub(r'^\./', '', m.group(1).strip('`'))   # 只剥「./」前缀；lstrip('./') 会连 .agents 的首点也剥掉（测试抓出）
        baseline = ROOT / raw
    rows = []
    for i, line in enumerate(text.splitlines()):
        s = line.strip()
        if not s.startswith('|'):
            continue
        cells = [c.strip() for c in s.strip('|').split('|')]
        if len(cells) != 7:
            continue
        c0 = cells[0].strip('` ')
        ids = re.findall(r'R-\d\d', c0)
        if not ids:
            ids = [sp for sp in SPECIAL_IDS if sp in c0]
        if not ids:
            continue
        rows.append((i + 1, ids, cells))
    return baseline, rows


def check_evidence(ev):
    """S3：证据 `路径#关键词1,关键词2`（`;` 分隔多条；`case:` 前缀豁免）"""
    problems = []
    for piece in ev.split(';'):
        piece = piece.strip().strip('`')
        if not piece or piece in ('—', '-'):
            continue
        if piece.startswith('case:'):
            continue
        if '#' not in piece:
            problems.append(f'证据缺 `#` 关键词分隔：{piece}')
            continue
        ps, kws = piece.split('#', 1)
        ps = re.sub(r'^\./', '', ps.strip())   # 同上：只剥「./」前缀
        p = ROOT / ps
        if not p.exists():
            problems.append(f'证据路径不存在：{ps}')
            continue
        kwlist = [k.strip() for k in kws.split(',') if k.strip()]
        if not kwlist:
            problems.append(f'证据未给关键词：{piece}')
            continue
        if p.is_file():
            content = p.read_text(encoding='utf-8', errors='ignore')
            for kw in kwlist:
                if kw not in content:
                    problems.append(f'证据关键词未命中：{ps} 内无「{kw}」')
        else:
            hit = {kw: False for kw in kwlist}
            n = 0
            for f2 in p.rglob('*'):
                if not f2.is_file() or f2.suffix.lower() not in TEXT_EXT:
                    continue
                n += 1
                if n > MAX_FILES:
                    break
                try:
                    if f2.stat().st_size > MAX_SIZE:
                        continue
                    c = f2.read_text(encoding='utf-8', errors='ignore')
                except OSError:
                    continue
                for kw in kwlist:
                    if not hit[kw] and kw in c:
                        hit[kw] = True
            for kw, ok in hit.items():
                if not ok:
                    problems.append(f'证据关键词未命中：目录 {ps} 下文本文件均无「{kw}」')
    return problems


def check_target(tgt, ledger):
    """S4：去向非空；LEDGER / REVIEW 锚点必须可查"""
    problems = []
    tgt = tgt.strip().strip('`')
    if tgt in ('', '—', '-'):
        return ['去向为空（未交付/下批必填）']
    if 'LEDGER' in tgt:
        if not ledger.exists():
            problems.append('去向引用 LEDGER，但任务目录无 LEDGER.md')
        elif '🟥' not in ledger.read_text(encoding='utf-8', errors='ignore'):
            problems.append('去向引用 LEDGER，但 LEDGER.md 内无 🟥 未交付登记行')
    for rid in re.findall(r'P\d+-[^\s、,，|）)）;；·]+', tgt):
        if not REVIEW.exists() or not re.search(re.escape(rid) + r'(?![0-9A-Za-z])',
                                                REVIEW.read_text(encoding='utf-8', errors='ignore')):
            problems.append(f'去向引用 REVIEW 编号 {rid}，REVIEW.md 查无此条')
    return problems


scope_files = sorted(WS.glob('*/scope-*.md'))
if not scope_files:
    print('SCOPE-GUARD: PASS (0 个任务含 scope 表——编码段开工须 `cp _templates/scope.md`；'
          '收工前本守卫必跑)')
    sys.exit(0)

for p in scope_files:
    r = rel(p)
    text = p.read_text(encoding='utf-8', errors='ignore')
    baseline, rows = parse_scope(text)

    if not baseline:
        fails.append(f'S0 {r}: 缺「基线：」行（正文须含 `> 基线：<设计稿相对仓库根路径>`）')
    elif not baseline.exists():
        fails.append(f'S0 {r}: 基线设计稿不存在：{rel(baseline)}')

    design_ids = parse_design_ids(baseline)
    if design_ids is None or len(design_ids) < 10:
        got = len(design_ids) if design_ids else 0
        fails.append(f'S0 {r}: 设计稿 §6 解析出 {got} 个条目（<10——解析失败宁可报错，不假绿）')
        design_ids = None

    if not rows:
        fails.append(f'S0 {r}: 表体解析出 0 个数据行（7 列表格式变了？宁可报错也不要假绿）')
        continue

    scope_ids = set()
    f_planned = f_done = f_undone = 0
    undone_items = []

    for ln, ids, cells in rows:
        scope_ids.update(ids)
        label = '/'.join(ids)
        plan, status, ev, tgt = cells[3], cells[4], cells[5], cells[6]

        # S2 枚举 + 收尾闭合
        if plan not in PLAN_OK:
            fails.append(f'S2 {r}:{ln} {label} 计划「{plan}」不在枚举 {sorted(PLAN_OK)}')
        if status not in STATUS_OK:
            fails.append(f'S2 {r}:{ln} {label} 状态「{status}」不在枚举 {sorted(STATUS_OK)}')
        if plan == '本批' and status in ('—', '-'):
            fails.append(f'S2 {r}:{ln} {label} 计划=本批但状态未闭合（收尾必须填：已交付/未交付）')

        # S3 已交付 → 证据
        if status == '已交付':
            if ev in ('', '—', '-'):
                fails.append(f'S3 {r}:{ln} {label} 状态=已交付但证据为空')
            else:
                for x in check_evidence(ev):
                    fails.append(f'S3 {r}:{ln} {label} {x}')

        # S4 未交付 / 下批 → 去向
        if status == '未交付' or plan == '下批':
            for x in check_target(tgt, p.parent / 'LEDGER.md'):
                fails.append(f'S4 {r}:{ln} {label} {x}')

        # S5 统计
        if plan == '本批':
            f_planned += 1
            if status == '已交付':
                f_done += 1
            elif status == '未交付':
                f_undone += 1
                undone_items.append(f'{label}（去向：{tgt}）')

    # S1 覆盖（设计 §6 → scope 表）
    if design_ids:
        missing = sorted(design_ids - scope_ids)
        if missing:
            fails.append(f'S1 {r}: 设计 §6 有 {len(missing)} 个条目未交代：{"、".join(missing)}')
        extra = sorted(scope_ids - design_ids)
        if extra:
            notes.append(f'{r}: 表内多出设计 §6 之外的条目（提示级，允许细分行）：{"、".join(extra)}')

    notes.append(f'{r} 完成度：本批计划 {f_planned} · 已交付 {f_done} · 未交付 {f_undone}')
    for x in undone_items:
        notes.append(f'  未交付 → {x}')

if fails:
    print(f'SCOPE-GUARD: {len(set(fails))} FAIL')
    for x in sorted(set(fails)):
        print('   ', x)
    sys.exit(1)

print(f'SCOPE-GUARD: PASS ({len(scope_files)} 个 scope 表 · 条目覆盖齐 · 证据可 grep · 去向可查)')
for x in notes:
    print(f'   [note] {x}' if x.startswith('   ') is False else x)
PYEOF
exit $?
