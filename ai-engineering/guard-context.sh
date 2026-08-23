#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 任务前上下文注入（进攻侧核心）— 生成"开工前必读清单"
#
# 用法:  bash ai-engineering/guard-context.sh            # 全部上下文
#        bash ai-engineering/guard-context.sh <主题词>    # 按主题过滤
#        bash ai-engineering/guard-context.sh --write-local  # 收尾：写 AGENTS.local.md 快照（DSH 等新会话自动注入）
# 说明:  每次开工前跑一次，自动汇总 AI 该知道的上下文，不用人提醒：
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

# 快照 = 每轮会话都注入的固定开销，必须控体积（见 checklists/cost.md C7）
LIM = 12 if WRITE_LOCAL else 10**9     # C2/C4 行数上限
LIM_C5 = 12 if WRITE_LOCAL else 10**9  # C5 行数上限
LIM_C6 = 12 if WRITE_LOCAL else 60     # C6 行数上限

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
    out.append(f"> 生成时间：{__import__('datetime').date.today()} · 由 `bash ai-engineering/guard-context.sh --write-local` 生成")
    out.append("> 本文件由 DSH/Claude 等工具在**新会话开始时自动注入**，是上次收尾时的状态基线；真相源是 docs/ 与 ai-engineering/assets/ 源文件，改源文件后重新生成即可（gitignore，不入库）。")
    out.append("> 当日成本 C6.5 不在此（隔日失真），开工现跑 `guard-context.sh` 获取。\n")
else:
    out.append(f"# AI 任务上下文清单{('（主题：' + TOPIC + '）') if TOPIC else ''}")
    out.append(f"> 生成时间：{__import__('datetime').date.today()} · 开工前读此清单，不用人提醒\n")

# C1 当前状态
out.append("## C1 当前状态")
status = (ROOT/'docs/reference/status.md')
if status.exists():
    for l in status.read_text(encoding='utf-8').splitlines():
        if '**' in l and ('|' in l or '：' in l):
            item = l.strip()
            if WRITE_LOCAL and len(item) > 90:
                item = item[:90] + '…（详见 status.md）'
            out.append(f"- {item}")
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
            row += f"（{desc[:70]}…）" if len(desc) > 70 else f"（{desc}）"
        if TOPIC and TOPIC not in row: continue
        out.append(row)
        handbook_count += 1
if handbook_count == 0:
    out.append("- （暂无主题手册；深度文档见 docs/README.md）")
out.append("")

# C2 未修项（REVIEW）
out.append("## C2 未修项（REVIEW.md）")
review = ROOT/'docs/review/REVIEW.md'
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
            cells = [c.strip() for c in l.strip('|').split('|')]
            if len(cells) >= 3 and cells[0] and (cells[0][0].isalpha() or cells[0][0].isdigit()):
                row = f"- {cells[0]}: {cells[1][:80]}"
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
    target = ROOT / 'AGENTS.local.md'
    target.write_text(body + '\n', encoding='utf-8')
    size = len(body.encode('utf-8'))
    print(f'[guard-context] 快照已写入 AGENTS.local.md（{size} 字节 / {body.count(chr(10)) + 1} 行）')
    if size > 8192:
        print('[guard-context] ⚠️ 超过 8KB 注入预算（cost.md C7），请精简源文件')
else:
    print(body)
PYEOF
exit $?
