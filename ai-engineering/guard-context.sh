#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 任务前上下文注入（进攻侧核心）— 生成"开工前必读清单"
#
# 用法:  bash ai-engineering/guard-context.sh            # 全部上下文
#        bash ai-engineering/guard-context.sh <主题词>    # 按主题过滤
# 说明:  每次开工前跑一次，自动汇总 AI 该知道的上下文，不用人提醒：
#         C1 当前状态（state/_index 指针 → status/REVIEW/task-log）
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
TOPIC="${1:-}"

python3 - "$ROOT" "$TOPIC" <<'PYEOF'
import re, sys, pathlib

ROOT = pathlib.Path(sys.argv[1])
TOPIC = sys.argv[2] if len(sys.argv) > 2 else ''
AI = ROOT / 'ai-engineering'

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
out.append(f"# AI 任务上下文清单{('（主题：' + TOPIC + '）') if TOPIC else ''}")
out.append(f"> 生成时间：{__import__('datetime').date.today()} · 开工前读此清单，不用人提醒\n")

# C1 当前状态
out.append("## C1 当前状态")
status = (ROOT/'docs/reference/status.md')
if status.exists():
    for l in status.read_text(encoding='utf-8').splitlines():
        if '**' in l and ('|' in l or '：' in l):
            out.append(f"- {l.strip()}")
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
    for l in p.read_text(encoding='utf-8').splitlines():
        if l.startswith('|') and '|' in l[1:] and not l.startswith('|:'):
            cells = [c.strip() for c in l.strip('|').split('|')]
            if len(cells) >= 6 and cells[0] and cells[0][0].isalpha() and cells[0] != '坑':
                row = f"- {cells[0]}: {cells[1][:50]}（复发信号：{cells[5][:40]}）"
                if TOPIC and TOPIC not in row: continue
                out.append(row)
out.append("")

# C5 规范（按主题）
out.append("## C5 规范（conventions.md）")
c = AI/'assets/conventions.md'
if c.exists():
    for l in c.read_text(encoding='utf-8').splitlines():
        if l.startswith('| C') or l.startswith('| D') or l.startswith('| W'):
            if '|' in l[1:]:
                cells = [x.strip() for x in l.strip('|').split('|')]
                if len(cells) >= 3:
                    row = f"- {cells[0]}: {cells[1][:60]}"
                    if TOPIC and TOPIC not in row: continue
                    out.append(row)
out.append("")

# C6 待办（task-log 当前任务区）
out.append("## C6 待办（task-log.md 当前任务）")
tl = ROOT/'docs/reference/task-log.md'
if tl.exists():
    lines = tl.read_text(encoding='utf-8').splitlines()
    for l in lines:
        if l.startswith('|') and '|' in l[1:] and not l.startswith('|:'):
            cells = [x.strip() for x in l.strip('|').split('|')]
            if len(cells) >= 3 and cells[0] and (cells[0][0].isalpha() or cells[0][0].isdigit()):
                row = f"- {cells[0]}: {cells[1][:70]}"
                if TOPIC and TOPIC not in row: continue
                out.append(row)
            if len(out) > 60: break
out.append("")

print('\n'.join(out))
PYEOF
exit $?
