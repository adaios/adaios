#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 元治理守护检查 — ai-engineering/ + AGENTS.md 的 frontmatter 契约自检
#
# 用法:  bash ai-engineering/guard-meta.sh       # 检查
#        bash ai-engineering/guard-meta.sh --fix # 检查 + 回写 lines 字段（D34 校准）
# 说明:  脚本内部自动 cd 到仓库根，免疫 cwd 漂移；
#        检查三件事（对应 D30/D34/M2）：
#          M1 图谱边：depends-on/related 相对路径必须解析到存在的文件
#          M2 lines 字段：frontmatter 声明行数 == 实际 wc -l（D34 校准）
#          M3 孤儿检测：无任何边引用 且 不在任何 _index.md 文件清单中的文件
#              （入口节点豁免：AGENTS.md 与各 _index.md 是既定入口，不算孤儿）
#        退出码: 0 = 全部 PASS；1 = 有 FAIL（存在漂移/断链）
# ─────────────────────────────────────────────────────────────
set -u

FIX="${1:-}"

cd "$(dirname "$0")/.."   # ai-engineering → 仓库根
ROOT="$(pwd)"

python3 - "$ROOT" "$FIX" <<'PYEOF'
import re, sys, pathlib

ROOT = pathlib.Path(sys.argv[1])
FIX = (sys.argv[2] == '--fix')
DOCS = ROOT / 'docs'
AI = ROOT / 'ai-engineering'

# 强制范围（frontmatter-spec §四）：AGENTS.md + docs/_index.md + 各目录 _index.md + ai-engineering/**
files = [ROOT/'AGENTS.md', DOCS/'_index.md', AI/'_index.md', AI/'README.md', AI/'frontmatter-spec.md']
files += sorted(DOCS.glob('*/_index.md'))        # 各子目录索引（目录治理）
files += sorted((AI/'roles').glob('*.md'))
files += sorted((AI/'skills').glob('*.md'))        # 建设/流程技能包
files += sorted((AI/'process').glob('*.md'))
files += sorted((AI/'checklists').glob('*.md'))
files += sorted((AI/'assets').glob('*.md'))      # 资产层
files += sorted((AI/'assets/adr').glob('*.md'))  # ADR
files += sorted((AI/'assets/projects').glob('*.md'))  # 项目资产卡
files += sorted((AI/'workflow').glob('*.md'))    # 工作流层
files += sorted((AI/'state').glob('*.md'))       # 状态层
files += sorted((DOCS/'review/audits').glob('*.md'))  # 走查存档（带 frontmatter）
files += sorted((DOCS/'rfc').glob('*.md'))            # RFC（带 frontmatter）
files = [f for f in files if f.exists()]

def parse_fm(path):
    t = path.read_text(encoding='utf-8')
    m = re.match(r'^---\n(.*?)\n---', t, re.S)
    if not m: return None
    body = m.group(1)
    out, cur = {}, None
    for line in body.splitlines():
        if re.match(r'^\s*-\s', line):
            if cur: out.setdefault(cur, []).append(line.strip()[2:].strip())
        elif ':' in line:
            k, v = line.split(':', 1); k, v = k.strip(), v.strip()
            cur = k
            if v == '[]': out[k] = []
            elif v.startswith('[') and v.endswith(']'):
                out[k] = [x.strip() for x in v[1:-1].split(',') if x.strip()]
            elif v: out[k] = v
            else: out[k] = []
    return out

REQUIRED = ['title','description','version','created','updated','status','lines','depends-on','related','tags']
fails = []

def is_entry(f):
    return f.name == '_index.md' or f == ROOT/'AGENTS.md'

def is_light(f):
    # 轻量档：RFC 用自有 frontmatter（title/date/status/decided-by），audits 为历史存档
    r = str(f.relative_to(ROOT))
    return r.startswith('docs/rfc/') or r.startswith('docs/review/audits/')

# M1 + M2 per file（轻量档只查 有 frontmatter + lines 准确 + 边可解析）
for f in files:
    meta = parse_fm(f)
    rel = str(f.relative_to(ROOT))
    if meta is None:
        fails.append(f'M1 {rel}: 无 frontmatter'); continue
    if not is_light(f):
        missing = [k for k in REQUIRED if k not in meta]
        if missing:
            fails.append(f'M1 {rel}: 缺字段 {",".join(missing)}')
    # M2 lines（轻量档 RFC 无 lines 字段，跳过）
    if not is_light(f):
        whole = f.read_text(encoding='utf-8')
        actual = whole.count('\n') + (0 if whole.endswith('\n') else 1)
        if str(meta.get('lines')) != str(actual):
            fails.append(f'M2 {rel}: lines 声明 {meta.get("lines")} != 实际 {actual}')
    # M1 edges
    for key in ('depends-on','related'):
        for ref in (meta.get(key) or []):
            if not isinstance(ref, str) or not ref.strip(): continue
            refp = ref.split('#')[0].strip()
            if not refp: continue
            target = (f.parent / refp).resolve()
            if not target.exists():
                fails.append(f'M1 {rel}: {key} 断链 {ref}')

# --fix: 回写 lines（按 wc -l 校准）+ updated（今日）；仅当内容实际变更时写文件（幂等）
if FIX:
    import datetime
    today = datetime.date.today().isoformat()
    changed = []
    for f in files:
        whole = f.read_text(encoding='utf-8')
        lines = whole.split('\n')
        if not lines or lines[0] != '---': continue
        end = None
        for i in range(1, len(lines)):
            if lines[i] == '---': end = i; break
        if end is None: continue
        actual = whole.count('\n') + (0 if whole.endswith('\n') else 1)
        lines_fixed = False
        for i in range(1, end):
            if lines[i].startswith('lines:') and lines[i] != 'lines: %d' % actual:
                lines[i] = 'lines: %d' % actual
                lines_fixed = True
        # updated 只在 lines 实际漂移（内容变更）时刷新，避免无改动也写盘
        if lines_fixed:
            for i in range(1, end):
                if lines[i].startswith('updated:'):
                    lines[i] = 'updated: %s' % today
            f.write_text('\n'.join(lines), encoding='utf-8')
            changed.append(f.relative_to(ROOT))
    if changed:
        print('--fix: %d 文件回写 lines/updated' % len(changed))
        for c in changed: print('   ', c)
    else:
        print('--fix: 无漂移，无需回写')
    sys.exit(0)

# M3 orphans: 收集边引用 + 所有 _index.md 文件清单引用
referenced = set()
for f in files:
    meta = parse_fm(f)
    if not meta: continue
    for key in ('depends-on','related'):
        for ref in (meta.get(key) or []):
            if not isinstance(ref, str) or not ref.strip(): continue
            refp = ref.split('#')[0].strip()
            if not refp: continue
            t = (f.parent / refp).resolve()
            if t.exists(): referenced.add(str(t))
# _index.md 文件清单（| path | 职责 | 状态 |）也算引用（全部子目录索引）
for idx in [DOCS/'_index.md'] + sorted(DOCS.glob('*/_index.md')) + [AI/'_index.md'] + sorted((AI/'assets').glob('_index.md')) + sorted((AI/'workflow').glob('_index.md')) + sorted((AI/'state').glob('_index.md')):
    if not idx.exists(): continue
    for line in idx.read_text(encoding='utf-8').splitlines():
        m = re.match(r'^\|\s*([\w./-]+\.md)\s*\|', line)
        if m:
            t = (idx.parent / m.group(1)).resolve()
            if t.exists(): referenced.add(str(t))

for f in files:
    if is_entry(f): continue
    if str(f.resolve()) not in referenced:
        fails.append(f'M3 {f.relative_to(ROOT)}: 孤儿（无引用且不在 _index 清单）')

# M4 正文路径引用扫描：强制区文档正文中的仓库内路径（`docs/...`、`ai-engineering/...`、`bash <script>`）
# 断言目标存在——堵 M1 盲区（frontmatter 边之外，正文路径引用断链）
M4_SKIP = ('docs/rfc/', 'docs/review/audits/')  # 历史记录/存档不查正文
for f in files:
    rel = str(f.relative_to(ROOT))
    if rel.startswith(M4_SKIP): continue
    text = f.read_text(encoding='utf-8')
    # 代码块中的 bash 命令路径：```bash 块内 bash <path> 行
    for block in re.findall(r'```bash\n(.*?)\n```', text, re.S):
        for line in block.splitlines():
            m = re.match(r'^\s*bash\s+([\w./-]+)', line)
            if not m: continue
            cmd = m.group(1)
            target = (ROOT / cmd).resolve()
            if not target.exists():
                fails.append(f'M4 {rel}: bash 命令路径不存在 {cmd}')
    # 行内仓库路径（docs/xxx、ai-engineering/xxx、AGENTS.md；CLAUDE.md 2026-08-19 已删，正则保留防残留）
    for m in re.finditer(r'`((?:docs|ai-engineering|AGENTS|CLAUDE)[\w./-]*(?:\.md|\.sh|/))`', text):
        ref = m.group(1).rstrip('/')
        if ref.endswith('/'): continue  # 目录引用跳过
        if ref in ('ai-engineering-method', 'ai-context-research'): continue  # 仓库外兄弟目录（同级）
        target = (ROOT / ref).resolve()
        if not target.exists():
            fails.append(f'M4 {rel}: 正文路径引用不存在 {ref}')

if fails:
    print('META-GUARD: %d FAIL' % len(fails))
    for x in sorted(set(fails)): print('  ', x)
    sys.exit(1)
print('META-GUARD: PASS (%d files, edges/lines/orphans all ok)' % len(files))
PYEOF
exit $?
