#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 技能包质量校验（S3 / S4 / S5 / S7）—— 补 guard-tools T3 之外的部分
#
# 与其它守卫的分工（避免重复判据）：
#   guard-tools T3 技能枚举 + name 字段齐备 + 两种布局  → S1 / S2 / S9
#   guard-meta     frontmatter 契约 / lines / 图谱      → 对技能同样适用
#   guard-skills   官方规范的**格式硬约束** + 结构完整性  → S3 / S4 / S5 / S7（本脚本）
#
# 官方依据：Agent Skills 规范（agentskills.io/specification）
#   name        ≤64；仅小写字母/数字/连字符；不得首尾连字符或连续连字符；须等于父目录名
#   description 1–1024
#
# 覆盖范围：skills/ 与 roles/ 的两种布局（与 T3 同口径）—— 审查官虽不进技能出口，
# 但它们的 description 同样要发布到工具（subagent 定义），故一并校验。
#
# 明确不做 S8（skills-lock.json 哈希锁定）：本项目单人 + 有 git，版本控制已覆盖；
# 哈希锁是给「无版本管理的技能市场」用的（判定见 RFC 20261003-skill-conformance §四）。
# ─────────────────────────────────────────────────────────────
set -u
cd "$(dirname "$0")/../.."
ROOT="$(pwd)"

python3 - "$ROOT" <<'PYEOF'
import re, sys, pathlib

ROOT = pathlib.Path(sys.argv[1]); AI = ROOT / '.agents'
fails = []; checked = 0

NAME_RE = re.compile(r'^[a-z0-9]+(-[a-z0-9]+)*$')
# 五段结构：允许**等价标题**与**带说明后缀**（如「执行步骤（四问）」）——
# 「触发条件」等价于「为什么需要你」（三个外部视角官的有意表达，见 skills-spec「与官方规范的偏离」）
SECTIONS = [
    ('触发条件', ['触发条件', '为什么需要你']),
    ('执行步骤', ['执行步骤']),
    ('约束与规则', ['约束与规则']),
    ('输出要求', ['输出要求']),
    ('参考资料', ['参考资料']),
]

def collect():
    out = []
    for base in ('skills', 'roles'):
        d = AI / base
        if not d.is_dir(): continue
        out += sorted(d.glob('*.md')) + sorted(d.glob('*/SKILL.md'))
    return [f for f in out if f.exists()]

for f in collect():
    rel = f.relative_to(ROOT)
    text = f.read_text(encoding='utf-8')
    m = re.match(r'^---\n(.*?)\n---\n(.*)$', text, re.S)
    if not m:
        fails.append('S2 %s: 缺 frontmatter' % rel); continue
    fm, body = m.group(1), m.group(2); checked += 1

    def val(k):
        mm = re.search(r'^%s:\s*(.+)$' % k, fm, re.M)
        return mm.group(1).strip() if mm else ''

    name, desc = val('name'), val('description')
    expect = f.parent.name if f.name == 'SKILL.md' else f.stem

    # S3 name 合规（官方硬约束）
    if not name:
        fails.append('S3 %s: 缺 name' % rel)
    else:
        if len(name) > 64:
            fails.append('S3 %s: name 超 64 字符（%d）' % (rel, len(name)))
        if not NAME_RE.match(name):
            fails.append('S3 %s: name 不合规（仅小写字母/数字/连字符，不得首尾或连续连字符）：%s' % (rel, name))
        if name != expect:
            fails.append('S3 %s: name `%s` ≠ 期望 `%s`（目录布局=父目录名 / 扁平=文件名）' % (rel, name, expect))

    # S4 description 长度
    if not desc:
        fails.append('S4 %s: 缺 description' % rel)
    elif len(desc) > 1024:
        fails.append('S4 %s: description 超 1024 字符（%d）' % (rel, len(desc)))

    # S5 五段结构（按二级标题**前缀**匹配——容忍说明后缀；不用关键词，正文提一句不算数）
    missing = [std for std, alts in SECTIONS
               if not any(re.search(r'^##\s*%s' % re.escape(a), body, re.M) for a in alts)]
    if missing:
        fails.append('S5 %s: 缺段落 %s' % (rel, '、'.join(missing)))

# S7 偏离在案（防遗忘：与官方规范的偏离必须留痕）
spec = AI / 'assets' / 'skills-spec.md'
if not spec.exists():
    fails.append('S7 缺 %s' % spec.relative_to(ROOT))
elif '偏离' not in spec.read_text(encoding='utf-8'):
    fails.append('S7 %s: 缺「与官方规范的偏离」段落（结构性偏离必须留痕）' % spec.relative_to(ROOT))

if fails:
    print('SKILL-GUARD: FAIL (%d 项)' % len(fails))
    for x in fails:
        print('   ❌ ' + x)
    sys.exit(1)

print('SKILL-GUARD: PASS (%d 个技能包 · S3 name / S4 description / S5 五段结构 / S7 偏离在案)' % checked)
PYEOF
