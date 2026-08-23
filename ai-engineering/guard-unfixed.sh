#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 未修复问题总清单（聚合 4 个维护点）— 用户问「还有哪些未修」一条命令拿全
#
# 用法:  bash ai-engineering/guard-unfixed.sh            # 全量总清单
#        bash ai-engineering/guard-unfixed.sh <主题词>    # 按主题过滤（如 trading / ui / 鉴权）
#        bash ai-engineering/guard-unfixed.sh --drift     # 只看游离+矛盾（不展示已归口明细）
#
# 聚合来源（REVIEW.md 是唯一真相源，其余为补充与对账）:
#   ① docs/review/REVIEW.md        战略 + P0/P1/P2 未修复（表内状态列非已修的）
#   ② docs/reference/task-log.md   待办迁移区（P3/观察项/可排期）
#   ③ docs/review/audits/*.md      每期审查报告中的「未修」行——未归口 REVIEW 的标记为游离
#   ④ 状态对账:已修复区声称出表、但 REVIEW 表状态未标 ✅ 的编号（下批 review 回填）
#
# 背景:2026-08-23 用户盘点发现未修项散在 REVIEW/audits/task-log 多处
#      （launcher 排序等只在 08-20 体检报告里、REVIEW 查无）→ 建本命令兜底。
# ─────────────────────────────────────────────────────────────
set -u

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
ARG="${1:-}"
TOPIC=""
DRIFT_ONLY=0
if [ "$ARG" = "--drift" ]; then
    DRIFT_ONLY=1
else
    TOPIC="$ARG"
fi

python3 - "$ROOT" "$TOPIC" "$DRIFT_ONLY" <<'PYEOF'
import re, sys, pathlib, datetime

ROOT = pathlib.Path(sys.argv[1])
TOPIC = sys.argv[2]
DRIFT_ONLY = len(sys.argv) > 3 and sys.argv[3] == '1'

REVIEW = ROOT / 'docs/review/REVIEW.md'
TASKLOG = ROOT / 'docs/reference/task-log.md'
AUDITS_DIR = ROOT / 'docs/review/audits'

def topic_ok(*texts):
    if not TOPIC:
        return True
    return any(TOPIC.lower() in t.lower() for t in texts)

# ── 已修标记判定（REVIEW 行级启发式：✅/已修/出表/已确认/已移除 = 闭环）──
DONE_MARKS = ('✅', '已修', '出表', '已确认', '已移除', '已闭环', '不成立', '误报', '清零')
def done_line(row):
    return any(m in row for m in DONE_MARKS)

def status_class(row):
    """返回 未修 / 搁置 / 复核 分类（done 由调用方先滤掉）。"""
    if '⏸' in row or '搁置' in row:
        return '搁置'
    if '⚠️' in row or '复核' in row:
        return '复核'
    return '未修'

def cell0(row):
    c = [x.strip() for x in row.strip('|').split('|')]
    return c[0] if c else ''

# ══════════════ ① REVIEW.md：未修复主清单 ══════════════
review_unfixed = []   # (section, id, text, status)
review_ids = set()    # 未修编号集合（含搁置/复核，用于游离与对账）
if REVIEW.exists():
    lines = REVIEW.read_text(encoding='utf-8', errors='ignore').splitlines()
    in_unfixed_zone = False
    section = ''
    for l in lines:
        if l.startswith('## 🔴'):
            in_unfixed_zone = True
            section = l.strip(' #').replace('（未修复）', '').strip()
            continue
        if l.startswith('## ') and not l.startswith('## 🔴'):
            if in_unfixed_zone:
                break  # 到已修复区
        if not in_unfixed_zone:
            continue
        # P0/P3 列表项（- **P0-交易A（未修…）** / - **P3 打磨项…**）
        if l.startswith('- **P0') or l.startswith('- P0') or l.startswith('- **P3') or l.startswith('- P3'):
            if '清零' in l:
                continue  # 「P0 其余当前清零」说明行
            if done_line(l):
                continue
            txt = l.strip('- *').strip().replace('**', '')
            st = status_class(l)
            if topic_ok(l):
                review_unfixed.append((section, 'P0/P3', txt, st))
                review_ids.add('P0/P3')
            continue
        if not (l.startswith('|') and '|' in l[1:]):
            continue
        if re.match(r'^\|?\s*:?-', l):
            continue  # 分隔行
        cid = cell0(l)
        if not cid or cid == '#' or not re.match(r'^[A-Za-z0-9#]+', cid):
            continue  # 表头/非编号行
        if done_line(l):
            continue
        st = status_class(l)
        desc = l.strip('|').split('|')[1].strip() if '|' in l.strip('|') else ''
        # 截断：问题列通常很长，保留前 120 字 + 状态尾巴
        desc_short = desc[:120] + ('…' if len(desc) > 120 else '')
        row_txt = f"**{cid}** {desc_short}  [{st}]"
        if topic_ok(l):
            review_unfixed.append((section, cid, row_txt, st))
            review_ids.add(cid)

# ══════════════ ② task-log.md：可排期/观察待办 ══════════════
task_todos = []
if TASKLOG.exists():
    tlines = TASKLOG.read_text(encoding='utf-8', errors='ignore').splitlines()
    in_mig = False
    for l in tlines:
        if l.startswith('## 待办迁移') or l.startswith('## 全维度走查'):
            in_mig = True
            continue
        if in_mig and l.startswith('## ') and not l.startswith('### '):
            break
        if not in_mig:
            continue
        if not (l.startswith('|') and '|' in l[1:]):
            continue
        if re.match(r'^\|?\s*:?-', l):
            continue
        c = [x.strip() for x in l.strip('|').split('|')]
        if len(c) >= 4 and c[0] and c[0] != '#':
            # c[0]=编号 c[1]=任务 c[-1]=优先级
            task = c[1][:90] + ('…' if len(c[1]) > 90 else '')
            row_txt = f"{c[0]}｜{task}（{c[-1]}）"
            if topic_ok(l):
                task_todos.append(row_txt)

# ══════════════ ③ audits/*.md：未修行 → 未归口即游离 ══════════════
# unfixed-gate：REVIEW.md 中登记的「报告 → 已归口编号」映射（防旧报告反复报游离）
gate = {}
if REVIEW.exists():
    m = re.search(r'<!-- unfixed-gate\n(.*?)\n-->', REVIEW.read_text(encoding='utf-8'), re.S)
    if m:
        for line in m.group(1).splitlines():
            line = line.strip()
            if '→' in line:
                rep, note = line.split('→', 1)
                gate[pathlib.Path(rep.strip()).name] = note.strip()

drift = []   # (报告, 行文本)
reviewed_rows = []  # 已归口（REVIEW 编号命中）
gated = []   # 已归口（unfixed-gate 报告级豁免）
if AUDITS_DIR.exists():
    for f in sorted(AUDITS_DIR.glob('*.md')):
        gated_note = gate.get(f.name)
        for l in f.read_text(encoding='utf-8', errors='ignore').splitlines():
            if 'REVIEW 已有' in l or 'REVIEW 已有未修' in l:
                continue  # 已注明归口 REVIEW
            if '对拍' in l or '一致' in l or '已出表' in l:
                continue  # 检查项/对账说明，非未修问题
            low = l.lower()
            if '已修' in low.replace('未修', '') or l.strip().startswith(('>', '#')):
                continue
            # 表格行按状态列判定（未修/待拍板/待用户）；列表行要求含「未修」
            if l.startswith('|'):
                c = [x.strip() for x in l.strip('|').split('|')]
                if len(c) < 2:
                    continue
                if not any(w in c[-1] for w in ('未修', '待拍板', '待用户')):
                    continue
                if len(c) >= 3 and re.match(r'^[A-Za-z0-9-]+$', c[0]) and len(c[0]) < 14:
                    txt = c[1]
                else:
                    txt = c[0]
            else:
                if '未修' not in l:
                    continue
                txt = l.strip('- *').strip().replace('**', '')
            if not txt or len(txt) < 4:
                continue
            if topic_ok(l):
                if gated_note:
                    gated.append((f.name, gated_note))
                    continue
                # 归口判定：行内出现 REVIEW 未修编号（如 P2-UI1 / P1-前端1 / #179）
                hit = any(rid not in ('P0', 'P0/P3') and rid in l for rid in review_ids)
                (reviewed_rows if hit else drift).append((f.name, txt[:110]))

# ══════════════ ④ 状态对账：已修复区提及 vs REVIEW 表未标 ✅ ══════════════
conflicts = []
if REVIEW.exists():
    rlines = REVIEW.read_text(encoding='utf-8', errors='ignore').splitlines()
    fixed_zone = []
    in_fixed = False
    for l in rlines:
        if l.startswith('## ✅ 已修复区'):
            in_fixed = True
            continue
        if in_fixed:
            fixed_zone.append(l)
    fixed_text = '\n'.join(fixed_zone)
    # 编号展开（只在声称「出表/已修」的行内提取，避免 D 批「待拍板跳过」误报）：
    # P1-交易11/12/13 → 3 个；P2-推送4/5/6 同；P2-交易4/P2-交易20 各自独立。
    pat = re.compile(r'(P\d+-[A-Za-z\u4e00-\u9fff]+?)(\d+)((?:/\d+)*)')
    mentioned = set()
    for line in fixed_zone:
        if not any(m in line for m in ('出表', '已修', '已闭环', '✅')):
            continue
        # 按子句提取，排除「待拍板/跳过/不适用」句（如 D 批 P2-UI1 待拍板、P2-UX2 不适用）
        for seg in re.split(r'[；;。]', line):
            if any(w in seg for w in ('待拍板', '跳过', '不适用', '待用户')):
                continue
            for m in pat.finditer(seg):
                base, first, rest = m.group(1), m.group(2), m.group(3)
                nums = [int(first)] + [int(x) for x in rest.strip('/').split('/') if x.strip('/').isdigit()]
                for n in nums:
                    mentioned.add(f"{base}{n}")
    for rid in sorted(review_ids):
        if rid in ('P0', 'P0/P3'):
            continue  # P0/P3 区无编号可对账（P0-交易A 已标已修，P3 打磨项在 P2-UI5/8/9 承接）
        if rid in mentioned:
            conflicts.append(rid)

# 去重（同一问题在报告交叉印证表 + 角色清单各出现一次）
def dedup(items, key=lambda x: x[1]):
    seen, out = set(), []
    for it in items:
        k = key(it)
        if k in seen:
            continue
        seen.add(k)
        out.append(it)
    return out
drift = dedup(drift)
reviewed_rows = dedup(reviewed_rows)

# ══════════════ 输出 ══════════════
out = []
today = datetime.date.today().isoformat()
if DRIFT_ONLY:
    out.append(f"# 未修项·游离与对账（{today}）")
    out.append(f"> 来源聚合：REVIEW.md（{len(review_ids)} 条未修/搁置/复核）· audits（游离 {len(drift)}）· 对账矛盾（{len(conflicts)}）\n")
else:
    out.append(f"# 未修复问题总清单（机器聚合 {today}）")
    out.append(f"> 命令：`bash ai-engineering/guard-unfixed.sh` · REVIEW.md 是唯一真相源，task-log/audits 为补充与对账；已修复区声明与表状态冲突的在 ④。\n")

secs = {}
for s, cid, txt, st in review_unfixed:
    secs.setdefault(s, []).append((cid, txt, st))
if not DRIFT_ONLY:
    out.append("## ① REVIEW.md 未修复（真相源）")
    if not secs:
        out.append("- （无）")
    for s in secs:
        items = secs[s]
        n_unfixed = sum(1 for _, _, st in items if st == '未修')
        n_hold = sum(1 for _, _, st in items if st == '搁置')
        n_check = sum(1 for _, _, st in items if st == '复核')
        tag = f"未修 {n_unfixed} · 搁置 {n_hold} · 复核 {n_check}" if (n_hold or n_check) else f"{len(items)} 条未修"
        out.append(f"\n### {s}（{tag}）")
        for cid, txt, st in items:
            out.append(f"- {txt}")
    out.append("")

if not DRIFT_ONLY:
    out.append(f"## ② task-log.md 可排期/观察待办（{len(task_todos)}）")
    if not task_todos:
        out.append("- （无）")
    for t in task_todos:
        out.append(f"- {t}")
    out.append("")

out.append(f"## ③ audits 游离未修（未归口 REVIEW，{len(drift)}）← 需归口")
if not drift:
    out.append("- （无游离项，全部已归口 REVIEW）")
for name, txt in drift:
    out.append(f"- {txt}  （来源 `audits/{name}`）")
if gated:
    seen_g = set()
    out.append(f"\n已归口（unfixed-gate 豁免 {len(set(g[0] for g in gated))} 份报告）：")
    for name, note in gated:
        if name in seen_g:
            continue
        seen_g.add(name)
        out.append(f"- `audits/{name}` → {note}")
out.append("")

out.append(f"## ④ 状态对账矛盾（已修复区提及、REVIEW 表未标 ✅，{len(conflicts)}）← 下批 review 回填")
if not conflicts:
    out.append("- （无矛盾）")
for c in conflicts:
    out.append(f"- {c}")
out.append("")

total = len(review_unfixed)
# 快照新鲜度（P2-4）：AGENTS.local.md 早于真相源更新 → 提示刷新（快照每轮注入，过旧 = 喂陈旧未修项）
snap = ROOT / 'AGENTS.local.md'
stale = []
if snap.exists() and REVIEW.exists() and snap.stat().st_mtime < REVIEW.stat().st_mtime:
    stale.append("AGENTS.local.md 快照早于 REVIEW.md——跑 `bash ai-engineering/guard-context.sh --write-local` 刷新")
if snap.exists() and TASKLOG.exists() and snap.stat().st_mtime < TASKLOG.stat().st_mtime:
    stale.append("AGENTS.local.md 快照早于 task-log.md——跑 `bash ai-engineering/guard-context.sh --write-local` 刷新")

out.append("## 统计")
if stale:
    for s in stale:
        out.append(f"- ⚠️ {s}")
out.append(f"- REVIEW 未修/搁置/复核：**{total}** 条（未修 {sum(1 for *_, st in review_unfixed if st=='未修')} · 搁置 {sum(1 for *_, st in review_unfixed if st=='搁置')} · 复核 {sum(1 for *_, st in review_unfixed if st=='复核')}）")
out.append(f"- task-log 可排期/观察：{len(task_todos)} 条")
out.append(f"- audits 游离未归口：{len(drift)} 条（建议归口 REVIEW 或补状态）")
if gated:
    out.append(f"- audits 已归口（unfixed-gate）：{len(set(g[0] for g in gated))} 份报告豁免")
out.append(f"- 对账矛盾：{len(conflicts)} 处（已修复区与 REVIEW 表状态不一致，下批 /review 时同步）")

print('\n'.join(out))
PYEOF
