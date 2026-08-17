#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 沉淀检查器（进攻侧 ②③）— 提交前检查"该沉淀的有没有沉淀"
#
# 用法:  bash ai-engineering/guard-sediment.sh [--check] [--fix-hint]
# 说明:  ship 时跑，检查三件事（软提示，不硬拦截——沉淀是内容判断）：
#         S1 变更提示：本批改了哪些代码文件 → 提示确认是否该入 pitfalls/ADR
#         S2 出表检查：REVIEW 未修项是否本批处理了但没标 ✅
#         S3 登记检查：change-log 是否登记本批（日期+批次）
#        退出码: 0 = 通过（或仅提示）；1 = 明确漏沉淀（变更未登记）
# ─────────────────────────────────────────────────────────────
set -u

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

python3 - "$ROOT" <<'PYEOF'
import re, sys, pathlib, subprocess

ROOT = pathlib.Path(sys.argv[1])

# 本批变更（staged）
try:
    diff = subprocess.run(['git', 'diff', '--cached', '--name-only', 'HEAD'],
                          cwd=str(ROOT), capture_output=True, text=True, timeout=10)
    changed = [l for l in diff.stdout.splitlines() if l.strip()]
except Exception:
    changed = []

code_changed = [c for c in changed if c.endswith(('.java', '.dart')) and 'test' not in c and '/test/' not in c]
doc_changed = [c for c in changed if c.endswith(('.md', '.sh'))]

fails = []
hints = []

# S1 变更提示：代码改了 → 该考虑沉淀
if code_changed:
    hints.append('S1 本批改了 %d 个代码文件，请确认沉淀（无则显式标注「无新增沉淀」）：' % len(code_changed))
    for c in code_changed[:12]:
        hints.append(f'    - {c}')
    hints.append('    - 取舍/为什么这么定 → 先过 ADR 三问（推翻成本高？有被否决备选？影响未来方向？全中才建 assets/adr/ADR-00N.md，否则 change-log 写清即可）')
    hints.append('    - 踩坑/根因 → 入 checklists + assets/pitfalls.md')
else:
    hints.append('S1 本批无代码变更（纯文档/配置），无需沉淀')

# S2 出表检查：REVIEW 未修项 vs 本批变更文件
review = ROOT / 'docs/review/REVIEW.md'
if review.exists() and code_changed:
    rt = review.read_text(encoding='utf-8')
    # 找 REVIEW 未修区的编号（战略/P1/P2 表格行）
    unfixed_ids = []
    in_unfixed = False
    for l in rt.splitlines():
        if l.startswith('## 🔴') or l.startswith('## 战略'):
            in_unfixed = True; continue
        if l.startswith('## ✅') or (l.startswith('## ') and in_unfixed):
            in_unfixed = False
        if in_unfixed and l.startswith('|'):
            m = re.match(r'\|\s*([A-Za-z]?\-?\d+\w*)\s*\|', l)
            if m and not l.startswith('|:'):
                unfixed_ids.append(m.group(1))
    if unfixed_ids:
        hints.append('S2 REVIEW 未修区有 %d 项（%s），本批是否处理了？处理了请标 ✅ 出表：'
                     % (len(unfixed_ids), ', '.join(unfixed_ids[:8])))

# S3 登记检查：change-log 是否登记本批（仅当有变更时）
if changed:
    cl = ROOT / 'docs/reference/change-log.md'
    if cl.exists():
        clt = cl.read_text(encoding='utf-8')
        today = __import__('datetime').date.today().isoformat()
        recent = [l for l in clt.splitlines() if l.startswith('|') and today in l]
        if not recent:
            fails.append('S3 change-log.md 未登记本批（今天 %s 无条目）——ship 步骤 3 要求每批登记（日期|批次|摘要）' % today)
        else:
            hints.append('S3 change-log 已有本批登记 ✓')
else:
    hints.append('S3 无变更，无需登记')

if fails:
    print('GUARD-SEDIMENT: FAIL')
    for x in fails: print('  ', x)
    for h in hints: print('  [hint]', h)
    sys.exit(1)
print('GUARD-SEDIMENT: PASS')
for h in hints: print('  [hint]', h)
sys.exit(0)
PYEOF
exit $?
