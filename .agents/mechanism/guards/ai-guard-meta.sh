#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 元治理守护检查 — ai-engineering/ + AGENTS.md 的 frontmatter 契约自检
#
# 用法:  bash .agents/mechanism/guards/ai-guard-meta.sh       # 检查
#        bash .agents/mechanism/guards/ai-guard-meta.sh --fix # 检查 + 回写 lines 字段（D34 校准）
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

cd "$(git rev-parse --show-toplevel)"   # 层级无关（2026-10-04 加固：原 ../.. 是隐式位置假设）   # ai-engineering → 仓库根
ROOT="$(pwd)"

python3 - "$ROOT" "$FIX" <<'PYEOF'
import re, sys, pathlib

ROOT = pathlib.Path(sys.argv[1])
FIX = (sys.argv[2] == '--fix')
DOCS = ROOT / 'docs'
AI = ROOT / '.agents'

# 强制范围（frontmatter-spec §四）：AGENTS.md + docs/_index.md + 各目录 _index.md + .agents/**
# 2026-10-04 二批（判据＝「AI 上下文运行时是否需要」）：AI 运行需要的文档**全部收进 .agents/**，
# docs/ 自此为**档案馆**（史 / 存档 / 对外 / 未定型）——本清单据此重组。
# 2026-10-06（体系体检 r2 · #2）：改为**全量递归**收集——此前是**手工枚举**（漏一个就永不检查；
# 实测漏了 11 个：4 组顶层两件套 + 根 `_directory.md` + 根契约 + `docs/README.md` + `docs/records/_directory.md`，
# 其中 9 个声明 `lines: 1`）。全量后 `--fix` 也能一次性回写它们。
# ⚠️ 排除 `.agents/skills/`——它是**工具出口位**（软链，gitignore），不是真相源。
files = [ROOT/'AGENTS.md']
files += sorted(f for f in AI.rglob('*.md') if not str(f.relative_to(AI)).startswith('skills/'))
# docs/ 是**档案馆**：只收「索引与目录契约」（`README.md` / `_index.md` / `_directory.md`）——
# 普通内容文档（`archive/` `records/` `ideas/` 等历史材料）**不收**：它们无 frontmatter 是设计如此
# （全量递归会一次报 103 条，实测如此；那种噪音等于让守卫失效）。
files += sorted(DOCS.glob('README.md'))
files += sorted(DOCS.rglob('_index.md'))
files += sorted(DOCS.rglob('_directory.md'))
files += sorted((AI/'rules/workflow').glob('*.md'))    # 工作流层
files += sorted((AI/'records/state').glob('*.md'))       # 状态层
files += sorted((AI/'mechanism/guards/tests').glob('*.md'))       # 守卫反例回归区索引
files += sorted((AI/'mechanism/guards').glob('*.md'))      # 守卫 + 目录两件套
files += sorted((AI/'mechanism/scripts/lib').glob('*.md'))         # 库 + 两件套
files += sorted((AI/'rules/method').glob('*.md'))      # 元方法层
files += sorted((AI/'mechanism/scripts').glob('*.md'))     # 环境脚本 + 两件套
files += sorted((AI/'workspace').rglob('*.md'))  # 在制品（2026-10-04 提为顶层）
# ── docs/ 档案馆：**活文档**（研究 / 想法 / 法务）纳入；**历史存档豁免** ──
# 豁免：records/audits 27 份走查存档 · records 的 4 份存档（issue-log / testflight / app-polish / release-*）
#       · archive/ 正文——它们是"当时的事实"，不适用"引用必须指向现在"。
files += sorted((DOCS/'research').glob('*.md'))
files += sorted((DOCS/'ideas').glob('*.md'))
files += sorted((DOCS/'legal').glob('*.md'))
files = [f for f in files if f.exists()]
files = list(dict.fromkeys(files))   # 去重：同一文件会被多个 glob 命中（如 docs/*/_index.md 与各区专属 glob）
                                     # —— list 累加会让「N files」虚高（2026-10-03 实测虚高 3）；判据本身等价（同一文件查两遍）

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
    # _directory.md 与 _index.md 是配对的既定入口：structure 的 actual_items 明确排除它，
    # 故此处必须同样豁免，否则同一文件会被 S2（不该列）与 M3（不列即孤儿）**互相矛盾地要求**。
    return f.name in ('_index.md', '_directory.md') or f == ROOT/'AGENTS.md'

def is_light(f):
    # 轻量档：RFC 用自有 frontmatter（title/date/status/decided-by），audits 为历史存档
    # ⚠️ **例外：目录契约（`_index.md` / `_directory.md`）永远走严格档**——它们是**活的导航/契约**，
    #    不是历史材料。否则 `docs/records/_directory.md` 的 lines **永不被查**（2026-10-06 体检 r2 #2 实证）。
    if f.name in ('_index.md', '_directory.md'):
        return False
    r = str(f.relative_to(ROOT))
    return r.startswith('.agents/direction/rfc/') or r.startswith('.agents/records/') or r.startswith('docs/records/')

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
    for key in ('depends-on','related','supersededBy'):
        val = meta.get(key) or []
        refs = val if isinstance(val, list) else [val]   # supersededBy 是**单值字符串**；depends-on/related 是列表
        for ref in refs:
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
    # 2026-10-06（体检 r3 · P2-2）：`updated` 此前**只在 lines 漂移时**刷新 ⇒ **「内容改了但行数没变」**
    # 的文件日期永远不动（实测 6 个：把 `ai-engineering/` 换成 `.agents/` 不改行数）。改为**按 git**：
    # 凡工作区/暂存区相对 HEAD 有改动的文件，一并刷 `updated`。一次调用拿全集，不在循环里逐个跑 git。
    import subprocess
    _DIRTY = set(subprocess.run(["git", "diff", "--name-only", "HEAD"], cwd=str(ROOT),
                                capture_output=True, text=True).stdout.split())
    for f in files:
        whole = f.read_text(encoding='utf-8')
        lines = whole.split('\n')
        if not lines or lines[0] != '---': continue
        end = None
        for i in range(1, len(lines)):
            if lines[i] == '---': end = i; break
        if end is None: continue
        actual = whole.count('\n') + (0 if whole.endswith('\n') else 1)
        # ⚠️ 幂等基线必须是**改动前**的快照（2026-10-06 体检 r4 · P2-r4-0 修的回归）：
        # 下面 `lines[i]` 是**就地改**的——若拿改后的 `lines` 当基线，`_new != lines` 在
        # 「只差 lines 字段 + updated 已是今天」时**恒为假** ⇒ 静默不写盘却打印「无漂移，无需回写」；
        # 而全仓只有这里能写 `lines:` ⇒ **M2 会永远 FAIL 且自带解药失效**
        # （即 pitfalls.md:281「--fix 静默不修且报成功」的复发）。
        orig = list(lines)
        lines_fixed = False
        for i in range(1, end):
            if lines[i].startswith('lines:') and lines[i] != 'lines: %d' % actual:
                lines[i] = 'lines: %d' % actual
                lines_fixed = True
        # updated：lines 漂移 **或** git 显示该文件有改动时刷新（后者治「改内容不改行数」）
        if lines_fixed or str(f.relative_to(ROOT)) in _DIRTY:
            _new = list(lines)
            for i in range(1, end):
                if _new[i].startswith('updated:'):
                    _new[i] = 'updated: %s' % today
            if _new != orig:                       # 幂等：与**改动前**的快照比
                f.write_text('\n'.join(_new), encoding='utf-8')
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
    for key in ('depends-on','related','supersededBy'):
        val = meta.get(key) or []
        refs = val if isinstance(val, list) else [val]   # supersededBy 是**单值字符串**；depends-on/related 是列表
        for ref in refs:
            if not isinstance(ref, str) or not ref.strip(): continue
            refp = ref.split('#')[0].strip()
            if not refp: continue
            t = (f.parent / refp).resolve()
            if t.exists(): referenced.add(str(t))
# _index.md 文件清单（| path | 职责 | 状态 |）也算引用（全部子目录索引）
# rglob：两件套落地后索引可在任意层级（如 workspace/tasks/）
for idx in [DOCS/'_index.md'] + sorted(DOCS.glob('*/_index.md')) + [AI/'_index.md'] + sorted(AI.rglob('_index.md')):
    # 2026-10-03：收**所有**子目录索引（原先硬编码 assets/workflow/state 三个；目录两件套落地后每个子目录都有 _index.md）
    if not idx.exists(): continue
    for line in idx.read_text(encoding='utf-8').splitlines():
        m = re.match(r'^\|\s*`?([\w./-]+\.md)`?\s*\|', line)   # 兼容反引号包裹的文件名（2026-10-03 目录两件套落地）
        if m:
            t = (idx.parent / m.group(1)).resolve()
            if t.exists(): referenced.add(str(t))

for f in files:
    if is_entry(f): continue
    if str(f.resolve()) not in referenced:
        fails.append(f'M3 {f.relative_to(ROOT)}: 孤儿（无引用且不在 _index 清单）')

# M4 正文路径引用扫描：强制区文档正文中的仓库内路径（`docs/...`、`ai-engineering/...`、`bash <script>`）
# 断言目标存在——堵 M1 盲区（frontmatter 边之外，正文路径引用断链）
M4_SKIP = ('.agents/direction/rfc/', '.agents/records/', 'docs/records/', 'docs/README.md',
           '.agents/workspace/_meta/')  # 决策记录与存档含"当时"的路径，不查正文
# ⚠️ `.agents/workspace/_meta/`（跨任务过程档）同样豁免：**派单文件按设计就会指向"尚未落盘的交付物"**
#    （r1/r2 各撞过一次：M4 报"指向不存在的报告"，而报告落盘后自动消解）。这是流程的固有摩擦，不是缺陷。
for f in files:
    rel = str(f.relative_to(ROOT))
    if rel.startswith(M4_SKIP): continue
    text = f.read_text(encoding='utf-8')
    # ── M4-a：命令式引用 `cmd <path>`（扫**全文**，不再区分代码块 / 行内）──
    #   演进：r2 前只查 ```bash 代码块**行首** → r2 加行内 → r3 认 sh/zsh/python3 → **r4 放宽形态**。
    #   r4 修的两点（P2-r4-1）：
    #     ① **词边界**：此前缺 `(?<![\w.-])` ⇒ `task-cadence.sh status/daily` 里的 `.sh ` 被当成 `sh`
    #        命令、把 `status/daily` 当路径（实测一条误报）；
    #     ② **形态**：此前只认「行首的 cmd」⇒ 两种常见写法**整类逃检**——`cd <dir> && sh <path>`
    #        （runbook 写法）与 `用法: python3 <path>`（脚本 usage 行）。现允许 ≤30 字前缀，并捕获
    #        可选的 `cd <dir> &&`（命中时路径按 `<dir>/` 解析）。
    #   仍只认**像路径的**（含 `/` 或以 `.sh`/`.py` 结尾），免得 `bash -n` / `bash TOKEN=…` 误判。
    #   解析基准依次：仓库根 → 本文件所在目录 → `cd <dir>` 之后的相对路径。
    _CMD_REF = re.compile(
        r'(?:cd\s+([\w./-]+)\s*&&\s*[^\n`]{0,20}?)?'
        r'(?:[^\n`]{0,30}?)'
        r'(?<![\w.-])(?:bash|sh|zsh|python3?)\s+'   # ← 词边界必须**紧贴解释器名**：
        r'((?:[\w.@-]+/)+[\w.@-]+|[\w.-]+\.(?:sh|py))')  # 放段首拦不住 `push`/`x.sh` 里的 `sh`
    for m in _CMD_REF.finditer(text):
        _cd, cmd = m.group(1), m.group(2)
        if ((ROOT / cmd).exists() or (f.parent / cmd).exists()
                or (_cd and (ROOT / _cd / cmd).exists())):
            continue
        fails.append(f'M4 {rel}: 命令路径不存在 {cmd}')
    # 行内仓库路径（docs/xxx、ai-engineering/xxx、AGENTS.md；CLAUDE.md 2026-08-19 已删，正则保留防残留）
    for m in re.finditer(r'`((?:docs/|\.agents/|AGENTS\.md|AGENTS\.local\.md|CLAUDE\.md)[\w./-]*(?:\.md|\.sh|/))`', text):
        # 2026-10-03：docs → docs/，避免把文件名 `docs-contract-reviewer.md` 误判为仓库路径
        ref = m.group(1).rstrip('/')
        if ref.endswith('/'): continue  # 目录引用跳过
        if ref in ('ai-engineering-method', 'ai-context-research'): continue  # 仓库外兄弟目录（同级）
        if ref == 'AGENTS.local.md': continue  # gitignore 快照（机器生成不入库，clone 缺失是设计非漂移，2026-08-23 审计实证）
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
