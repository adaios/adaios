#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 目录级自洽守卫（ai-guard-structure）—— 补 ai-guard-meta（文件级）之外的那一半
#
# 查什么（S1–S4）：
#   S1 每个子目录都有 _index.md 与 _directory.md（目录两件套齐备）
#   S2 _index.md 的清单 ⇄ 实际文件（**双向**：漏列 / 多列都报）
#   S3 _directory.md 里声明的依赖路径真实存在
#   S4 _directory.md 里提到的守卫真实存在
#
# 与 ai-guard-meta 的分工：
#   ai-guard-meta      管**文件级**（frontmatter / lines / 断链 / 孤儿 / 正文路径）
#   ai-guard-structure 管**目录级**（契约与清单是否与**事实**一致）—— 本脚本
#
# 为什么需要：目录级失真不会报错——`.agents/_index.md` 曾长期写着旧标题与旧路径，
#   门禁全绿（M3 只查"文件名在不在清单里"，不管路径）。见 pitfalls 二十三。
#
# 用法：
#   bash .agents/mechanism/guards/ai-guard-structure.sh          # 只检查
#   bash .agents/mechanism/guards/ai-guard-structure.sh --fix    # 重刷各 _index.md 的清单段（从实际文件生成）
# ─────────────────────────────────────────────────────────────
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"   # 层级无关（2026-10-04 加固：原 ../.. 是隐式位置假设）
ROOT="$(pwd)"
FIX=0
[ "${1:-}" = "--fix" ] && FIX=1

python3 - "$ROOT" "$FIX" <<'PYEOF'
import re, sys, pathlib

ROOT = pathlib.Path(sys.argv[1])
FIX = sys.argv[2] == "1"
AG = ROOT / ".agents"
fails = []
fixed = []


def fm_of(f):
    try:
        t = f.read_text(encoding="utf-8")
    except Exception:
        return {}
    m = re.match(r"^---\n(.*?)\n---", t, re.S)
    if not m:
        return {}
    d = {}
    for line in m.group(1).splitlines():
        if ":" in line and not line.startswith((" ", "\t", "-")):
            k, v = line.split(":", 1)
            d[k.strip()] = v.strip()
    return d


def describe(f, limit=56):
    if f.suffix == ".md":
        d = fm_of(f).get("description", "")
        return (d[:limit] + "…") if len(d) > limit else (d or "—")
    try:
        for line in f.read_text(encoding="utf-8", errors="ignore").splitlines()[:10]:
            s = line.strip()
            if s.startswith("#") and "!" not in s[:4]:
                c = s.lstrip("#").strip()
                if c and "─" not in c:
                    return (c[:limit] + "…") if len(c) > limit else c
    except Exception:
        pass
    return "—"


def status_of(f):
    return (fm_of(f).get("status", "active") or "active") if f.suffix == ".md" else "active"


def actual_items(d):
    # 排除「运行时产物」——`__pycache__/` 与 `*.pyc` 由脚本生成，不该进清单
    # （2026-10-04：新增 .py 脚本后首次跑，S2 报「漏列 __pycache__/…pyc」，提交被 pre-commit 拦下）。
    return sorted(str(f.relative_to(d)) for f in d.rglob("*")
                  if f.is_file() and f.name not in ("_index.md", "_directory.md")
                  and "__pycache__" not in f.parts
                  and not f.name.endswith((".pyc", ".pyo")))


if not AG.is_dir():
    print("STRUCTURE-GUARD: FAIL（.agents/ 不存在）")
    sys.exit(1)

# 扫「有 _index.md 的目录」（不限层级）—— 这样 workspace/tasks/ 这类**二级受管目录**也能查到；
# 没有 _index.md 的目录（assets/adr/、skills/data-learn-writer/）由父目录清单的 rglob 覆盖，不单独受管。
def _structural(d):
    """结构目录 = 该有「_index.md + _directory.md」两件套的目录。
    排除：隐藏/缓存目录 · 配置目录（*.d）· 技能包（含 SKILL.md）· workspace/ 下的在制品（第 2 层起）。
    ⚠️ 2026-10-04 修盲区：原判据是「已有 _index.md 的目录」——**不建 _index.md 的目录永远免检**
    （实测漏掉 5 个：workspace / adr / projects / 技能包 / 配置目录；前三个确实是真遗漏）。"""
    rel = d.relative_to(AG)
    if any(pp.startswith('.') or pp == '__pycache__' for pp in rel.parts):
        return False
    if d.name.endswith('.d'):                 # 配置目录（如 task-noon.d）
        return False
    if (d / 'SKILL.md').exists():             # 技能包（契约是 SKILL.md，不是 _index.md）
        return False
    if rel.parts[0] == 'workspace' and len(rel.parts) >= 2:
        return False                          # 在制品目录（每需求一个，动态生长）
    return True

SUBS = sorted(str(d.relative_to(AG)) for d in AG.rglob("*") if d.is_dir() and _structural(d))

# ── S1 + S2 ──
for name in SUBS:
    d = AG / name
    for need in ("_index.md", "_directory.md"):
        if not (d / need).exists():
            fails.append("S1 %s/: 缺 %s（目录两件套不齐）" % (name, need))

    idx = d / "_index.md"
    if not idx.exists():
        continue
    text = idx.read_text(encoding="utf-8")
    # 2026-10-04 修：只在「## 文件清单」段内抓取——此前抓全文，把功能主轴的 ID（`account`）
    # 与 RFC 清单的标题列误当文件名，造成「多列 37 项 / 漏列 66 项」的假报。
    _m = re.search(r'## 文件清单(?:（\d+ 项）)?\n(.*?)(?=\n## |\Z)', text, re.S)
    _scope = _m.group(1) if _m else ''
    listed = set(re.findall(r"^\| `([^`]+)` \|", _scope, re.M))
    actual = set(actual_items(d))

    if FIX and listed != actual:
        items = [(r, describe(d / r), status_of(d / r)) for r in sorted(actual)]
        block = "## 文件清单（%d 项）\n\n| 文件 | 职责 | 状态 |\n|:--|:--|:--:|\n" % len(items)
        for r, de, st in items:
            block += "| `%s` | %s | %s |\n" % (r, de, st)
        new = re.sub(r"## 文件清单（\d+ 项）\n\n\| 文件 \| 职责 \| 状态 \|\n\|:--\|:--\|:--:\|\n(?:\|.*\n)*",
                     block, text)
        if new != text:
            idx.write_text(new, encoding="utf-8")
            fixed.append("%s/_index.md" % name)
        listed = actual

    missing = sorted(actual - listed)
    extra = sorted(listed - actual)
    if missing:
        fails.append("S2 %s/_index.md: 漏列 %d 项（如 %s）" % (name, len(missing), " · ".join(missing[:3])))
    if extra:
        fails.append("S2 %s/_index.md: 多列 %d 项（文件不存在：%s）" % (name, len(extra), " · ".join(extra[:3])))

# ── S3 + S4 ──
for name in SUBS:
    dirf = AG / name / "_directory.md"
    if not dirf.exists():
        continue
    t = dirf.read_text(encoding="utf-8")

    # S3：反引号里的相对路径（../xxx、./xxx）
    for ref in sorted(set(re.findall(r"`(\.\.?/[^`\s]+?)`", t))):
        if "<" in ref or ">" in ref:
            continue          # 模板占位符（如 <需求id>）是命名规范、不是真实路径（2026-10-03）
        ref_clean = ref.rstrip("/")
        target = (dirf.parent / ref_clean).resolve()
        if not target.exists():
            fails.append("S3 %s/_directory.md: 依赖路径不存在 → %s" % (name, ref))

    # S4：提到的守卫是否存在
    for g in sorted(set(re.findall(r"((?:ai-)?guard-[a-z0-9-]+\.sh)", t))):
        if not (AG / "mechanism" / "guards" / g).exists():
            fails.append("S4 %s/_directory.md: 提到 %s 但它不在 mechanism/guards/（漏了 ai- 前缀？）" % (name, g))

# ── S5：两件套之间不得有整行重复（铁律 1「单一权威来源」的机器校验）──
# 为什么需要：_index.md 与 _directory.md 若由同一脚本、从同一份数据生成，极易把同一句话
# 写进两处 —— 2026-10-03 自审实测 **12/12 目录**的「职责」行一字不差重复，即"体系违反自己的铁律"。
# 只比**正文的长行**（≥15 字符），排除 frontmatter 字段与表格/引用/标题/列表等结构性行——
# 那些本来就两边都有，不是"同一知识的两处详述"。
def body_dup_set(f):
    txt = f.read_text(encoding="utf-8")
    m = re.match(r"^---\n.*?\n---\n(.*)$", txt, re.S)
    body = m.group(1) if m else txt
    out = set()
    for line in body.splitlines():
        s = line.strip()
        if len(s) < 15:
            continue
        if s.startswith(("|", ">", "#", "```", "- ", "* ")):
            continue
        out.add(s)
    return out


for name in SUBS:
    idx, dirf = AG / name / "_index.md", AG / name / "_directory.md"
    if not (idx.exists() and dirf.exists()):
        continue
    dup = body_dup_set(idx) & body_dup_set(dirf)
    if dup:
        fails.append("S5 %s/: 两件套有 %d 行整行重复（同一知识应只在一处详述）如「%s」"
                     % (name, len(dup), sorted(dup)[0][:44]))

if FIX and fixed:
    print("STRUCTURE-GUARD: 已重刷 %d 个 _index.md 清单" % len(fixed))
    for x in fixed:
        print("   ✅ " + x)
    print()

if fails:
    print("STRUCTURE-GUARD: FAIL (%d 项)" % len(fails))
    for x in fails:
        print("   ❌ " + x)
    print()
    print("   修复：bash .agents/mechanism/guards/ai-guard-structure.sh --fix（S2 可自动；S1/S3/S4/S5 需手工）")
    sys.exit(1)

print("STRUCTURE-GUARD: PASS (%d 个子目录 · 两件套齐备 · 清单⇄实际一致 · 依赖与守卫引用有效 · 两件套不重复)" % len(SUBS))
PYEOF
