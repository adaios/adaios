#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 文档自动对齐守护 — 代码 ↔ 文档内容一致性检查
#
# 用法:  bash ai-engineering/guard-align.sh [--fix-msg]
# 说明:  提交/部署前自动对齐（配合 .githooks/pre-commit 自动触发，或 /ship 手动跑）：
#         A1 端点对齐：源码 @Mapping ↔ api-spec.md 端点标题逐一对拍（硬 FAIL）
#         A2 测试数对齐：实测 @Test 数 ↔ status.md 声明（硬 FAIL）
#         A3 变更登记提示：git 变更文件 → 提醒确认文档同步（软 WARN，不拦截）
#        退出码: 0 = PASS；1 = FAIL（端点/测试数漂移，阻止提交）
# ─────────────────────────────────────────────────────────────
set -u

cd "$(dirname "$0")/.."   # ai-engineering → 仓库根
ROOT="$(pwd)"

python3 - "$ROOT" <<'PYEOF'
import re, sys, pathlib, subprocess

ROOT = pathlib.Path(sys.argv[1])

fails = []
warns = []

# ── A1 端点对齐：源码 @Mapping ↔ api-spec 标题 ──
interfaces = ROOT / 'services/adai-core/src/main/java/com/adaiadai/core/interfaces'
spec = (ROOT / 'docs/architecture/api-spec.md').read_text(encoding='utf-8')

endpoints = []  # (method, full_path)
for ctrl in sorted(interfaces.glob('*Controller.java')):
    text = ctrl.read_text(encoding='utf-8')
    # 类级 RequestMapping（两种写法：带引号 / 无引号）
    cls_m = re.search(r'@RequestMapping\(\s*"?(/api/v1/[^")\s]+)', text)
    base = cls_m.group(1) if cls_m else ''
    # 方法级 Mapping（带路径 + 命名参数写法 + 类级 base 拼接）
    # 兼容命名参数写法 @PostMapping(value = "/path")（2026-08-16：imports/save 触发盲区）
    for m in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"?([^")\s]+)', text):
        method = m.group(1).upper()
        path = m.group(2)
        full = path if path.startswith('/api') else (base + path)
        endpoints.append((method, full))
    # 裸注解（@GetMapping / @GetMapping() 无路径参数 → 继承类级 base，P2-20 2026-08-17：
    # 此前只数带括号路径的，11 个裸注解漏数 → A1 报 61 vs 真相源 72）
    for m in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\s*\(\s*\)\s*(?:\n|$)|@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\n|$)', text):
        method = m.group(1) or m.group(2)
        if base:
            endpoints.append((method.upper(), base))

missing = []
for method, path in sorted(endpoints):
    # api-spec 标题形如 ### `POST /api/v1/...`，可能带 ?query= 参数后缀 → 忽略
    title = f'### `{method} {path}'
    if title not in spec and title + '?' not in spec and title + '`' not in spec:
        missing.append(f'{method} {path}')
if missing:
    fails.append(f'A1 端点未在 api-spec.md 登记（{len(missing)} 个）：')
    for x in missing:
        fails.append(f'    - {x}')
else:
    print(f'A1 端点对齐 PASS（{len(endpoints)} 个端点全部在 api-spec.md）')

# ── A2 测试数对齐：实测 @Test ↔ status.md ──
status_text = (ROOT / 'docs/reference/status.md').read_text(encoding='utf-8')

def count_tests(glob_pattern, is_dart=False):
    n = 0
    for f in sorted(ROOT.glob(glob_pattern)):
        t = f.read_text(encoding='utf-8', errors='ignore')
        if is_dart:
            n += len(re.findall(r'testWidgets\(', t)) + len(re.findall(r'^\s*test\(', t, re.M))
        else:
            # 兼容简写 @Test 与全限定 @org.junit.jupiter.api.Test（2026-08-16：全限定写法触发漏数）
            n += len(re.findall(r'@(?:org\.junit\.jupiter\.api\.)?Test\b', t))
    return n

backend_tests = count_tests('services/adai-core/src/test/java/**/*.java')
app_tests = count_tests('apps/adai-app/test/**/*.dart', is_dart=True)
admin_tests = count_tests('apps/adai-admin/test/**/*.dart', is_dart=True)
web_tests = count_tests('apps/adai-web/test/**/*.dart', is_dart=True)

def check_status_label(declared, actual, label):
    m = re.search(r'\|\s*' + re.escape(label) + r'\s*\|\s*\*\*(\d+)\*\*', status_text)
    if not m:
        warns.append(f'A2 无法解析 status.md 中「{label}」声明（检查格式）')
        return
    declared_num = int(m.group(1))
    if declared_num != actual:
        fails.append(f'A2 {label} 测试数漂移：status.md 声明 {declared_num}，实测 {actual}（需更新 status.md）')

check_status_label('后端 adai-core', backend_tests, '后端 adai-core')
check_status_label('前端 adai-app', app_tests, '前端 adai-app')
check_status_label('前端 adai-admin', admin_tests, '前端 adai-admin')
check_status_label('前端 adai-web', web_tests, '前端 adai-web')
print(f'A2 测试数核对：后端 {backend_tests} / app {app_tests} / admin {admin_tests} / web {web_tests}')

# ── A4 端点数对拍：endpoints.txt 实测 vs status.md 声明（D35 脚本化）──
ept = ROOT / 'services/adai-core/build/resources/main/META-INF/endpoints.txt'
if ept.exists():
    try:
        actual_ept = int(ept.read_text(encoding='utf-8').strip())
        m_ept = re.search(r'端点：\*\*(\d+)\*\*', status_text)
        if m_ept and int(m_ept.group(1)) != actual_ept:
            fails.append(f'A4 端点数漂移：endpoints.txt 实测 {actual_ept}，status.md 声明 {m_ept.group(1)}（需更新 status.md）')
        elif not m_ept:
            warns.append('A4 无法解析 status.md 端点声明（检查格式）')
    except ValueError:
        warns.append('A4 endpoints.txt 内容非数字，跳过')
else:
    warns.append('A4 endpoints.txt 不存在（需 gradle build 生成），跳过')

# ── A3 变更登记提示：git 变更文件 → 确认文档同步 ──
try:
    diff = subprocess.run(
        ['git', 'diff', '--cached', '--name-only', 'HEAD'],
        cwd=str(ROOT), capture_output=True, text=True, timeout=10)
    changed = [l for l in diff.stdout.splitlines() if l.strip()]
    code_changed = [c for c in changed
                    if c.endswith(('.java', '.dart')) and 'test' not in c.lower() and 'test/' not in c]
    if code_changed:
        warns.append('A3 以下代码文件变更，请确认文档已同步（api-spec/feature-reference/项目资产卡）：')
        for c in code_changed[:15]:
            warns.append(f'    - {c}')
except Exception as e:
    warns.append(f'A3 变更检测跳过（{e}）')

# ── 输出 ──
if fails:
    print('GUARD-ALIGN: FAIL')
    for x in fails:
        print('  ', x)
    sys.exit(1)
print('GUARD-ALIGN: PASS')
for w in warns:
    print('  [WARN]', w)
sys.exit(0)
PYEOF
exit $?
