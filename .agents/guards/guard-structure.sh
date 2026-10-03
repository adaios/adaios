#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 目录级自洽守卫（guard-structure）—— 补 guard-meta（文件级）之外的那一半
#
# 查什么（S1–S4）：
#   S1 每个子目录都有 _index.md 与 _directory.md（目录两件套齐备）
#   S2 _index.md 的清单 ⇄ 实际文件（**双向**：漏列 / 多列都报）
#   S3 _directory.md 里声明的依赖路径真实存在
#   S4 _directory.md 里提到的守卫真实存在
#
# 与 guard-meta 的分工：
#   guard-meta      管**文件级**（frontmatter / lines / 断链 / 孤儿 / 正文路径）
#   guard-structure 管**目录级**（契约与清单是否与**事实**一致）—— 本脚本
#
# 为什么需要：目录级失真不会报错——`.agents/_index.md` 曾长期写着旧标题与旧路径，
#   门禁全绿（M3 只查"文件名在不在清单里"，不管路径）。见 pitfalls 二十三。
#
# 用法：
#   bash .agents/guards/guard-structure.sh          # 只检查
#   bash .agents/guards/guard-structure.sh --fix    # 重刷各 _index.md 的清单段（从实际文件生成）
# ─────────────────────────────────────────────────────────────
set -uo pipefail
cd "$(dirname "$0")/../.."
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
    return sorted(str(f.relative_to(d)) for f in d.rglob("*")
                  if f.is_file() and f.name not in ("_index.md", "_directory.md"))


if not AG.is_dir():
    print("STRUCTURE-GUARD: FAIL（.agents/ 不存在）")
    sys.exit(1)

SUBS = sorted(d.name for d in AG.iterdir() if d.is_dir() and not d.name.startswith("."))

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
    listed = set(re.findall(r"^\| `([^`]+)` \|", text, re.M))
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
        ref_clean = ref.rstrip("/")
        target = (dirf.parent / ref_clean).resolve()
        if not target.exists():
            fails.append("S3 %s/_directory.md: 依赖路径不存在 → %s" % (name, ref))

    # S4：提到的守卫是否存在
    for g in sorted(set(re.findall(r"(guard-[a-z0-9-]+\.sh)", t))):
        if not (AG / "guards" / g).exists():
            fails.append("S4 %s/_directory.md: 提到 %s 但它不在 guards/" % (name, g))

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
    print("   修复：bash .agents/guards/guard-structure.sh --fix（S2 可自动；S1/S3/S4 需手工）")
    sys.exit(1)

print("STRUCTURE-GUARD: PASS (%d 个子目录 · 两件套齐备 · 清单⇄实际一致 · 依赖与守卫引用有效)" % len(SUBS))
PYEOF
