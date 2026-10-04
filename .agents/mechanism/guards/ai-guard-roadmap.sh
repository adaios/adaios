#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 规划状态对拍（roadmap 体检）— 回答「未来规划如何 / 规划是否可信」
#
# 用法:  bash .agents/mechanism/guards/ai-guard-roadmap.sh          # 规划简报 + 漂移检查
#
# 背景:  2026-08-23 状态层审查（P1-2）——roadmap 自称「最高优先级文档」，
#        但条目状态（待做/顺延/已实现）零对拍检测，S-5 已实锤漂移
#        （选号已实现仍标 v1.0.1 顺延；双主页/launcher/搜索形态无条目）。
#        本命令做：新鲜度 + 版本状态 + 已知漂移点检查。
#        真相源:.agents/direction/product-roadmap.md + status.md（实现证据）。
# ─────────────────────────────────────────────────────────────
set -u

cd "$(git rev-parse --show-toplevel)"   # 层级无关（2026-10-04 加固：原 ../.. 是隐式位置假设）
ROOT="$(pwd)"

python3 - "$ROOT" <<'PYEOF'
import re, sys, pathlib, datetime

ROOT = pathlib.Path(sys.argv[1])
ROADMAP = ROOT / '.agents/direction/product-roadmap.md'
STATUS = ROOT / '.agents/knowledge/reference/status.md'

today = datetime.date.today()
out = []
out.append(f"# 规划状态对拍（ai-guard-roadmap · {today.isoformat()}）")
out.append("> 真相源：product-roadmap.md（蓝图）+ status.md（实现证据）；本命令只检查不修改。\n")

if not ROADMAP.exists():
    print("# product-roadmap.md 不存在——规划真相源缺失（P0）")
    sys.exit(1)

text = ROADMAP.read_text(encoding='utf-8', errors='ignore')
status_text = STATUS.read_text(encoding='utf-8', errors='ignore') if STATUS.exists() else ''

# ── 1. 新鲜度 ──
m = re.search(r'文档版本：v([\d.]+)\s*\|\s*最后更新：([\d-]+)', text)
if m:
    ver, d = m.group(1), m.group(2)
    try:
        age = (today - datetime.date.fromisoformat(d)).days
    except ValueError:
        age = -1
    flag = '⚠️ 已陈旧' if age > 14 else '✅ 新鲜'
    out.append(f"## 1. 新鲜度：v{ver} · 最后更新 {d} · 距今 {age} 天（{flag}，阈值 14 天）")
else:
    out.append("## 1. 新鲜度：未找到「文档版本/最后更新」头（frontmatter 契约缺失）")

# ── 2. 版本状态表（§二） ──
out.append("\n## 2. 版本状态")
in_ver = False
for l in text.splitlines():
    if l.startswith('## 二、版本演进总览'):
        in_ver = True; continue
    if in_ver and l.startswith('## ') and not l.startswith('### '):
        break
    if in_ver and l.startswith('| **v') and '|' in l[1:]:
        c = [x.strip() for x in l.strip('|').split('|')]
        if len(c) >= 4:
            out.append(f"- {c[0]}｜{c[2][:60]}…｜{c[3]}")

# ── 3. 漂移检查（S-5 点名 + 通用规则） ──
out.append("\n## 3. 漂移检查（S-5 清单 + 实现证据对拍）")
checks = []
# 3a. 选号：含「选号」的行若仍标顺延/⏸（且未标 ✅ 或「已实现」）→ 漂移
sel_lines = [l for l in text.splitlines() if '选号' in l]
if sel_lines and any(('顺延' in l or '⏸' in l) and '已实现' not in l and '✅' not in l for l in sel_lines):
    if status_text and ('选号' in status_text or '切换链路' in status_text):
        checks.append(("⚠️", "选号（MD12）：roadmap 仍有选号行标「顺延/⏸」，但 status.md 已有实现证据（app 测试备注含「选号/切换链路」）——S-5 漂移，需改为「✅ 已实现」"))
    else:
        checks.append(("ℹ️", "选号（MD12）：roadmap 标顺延，status.md 无实现证据——状态一致，待 v1.0.1"))
# 3b. 形态缺条目：双主页/launcher/搜索形态
for kw, note in (('双主页', '08-20 体检 S-1：双主页形态违背 DESIGN「一个页面」'), ('launcher', '08-20 体检 S-5'), ('搜索形态', '08-20 体检 S-5')):
    if kw.lower() not in text.lower():
        checks.append(("ℹ️", f"「{kw}」未入 roadmap——{note}，需补条目或显式决策"))
# 3c. （2026-10-04 撤）原检查「roadmap 顺延项 ↔ task-log 任务」的可追溯性。
#     撤除原因：task-log.md 已退役为「历史任务档案」，不再是任务源；
#     路线→任务的追溯改由 **REVIEW.md 的编号**承担（ai-guard-feature F6 已强制对拍）。
if not checks:
    out.append("- （无已知漂移）")
for sev, msg in checks:
    out.append(f"- {sev} {msg}")

# ── 4. 结论 ──
warns = sum(1 for s, _ in checks if s == '⚠️')
infos = sum(1 for s, _ in checks if s == 'ℹ️')
out.append(f"\n## 结论：{len(checks)} 项检查（{warns} 漂移 · {infos} 提示）")
if warns:
    out.append(f"- **有 {warns} 处实现↔规划状态矛盾**，下批 roadmap 更新时同步（源头见 REVIEW S-5 / 08-20 体检）")
else:
    out.append("- 无实现↔规划状态矛盾")

print('\n'.join(out))
PYEOF
