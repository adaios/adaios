#!/usr/bin/env python3
"""ai-guard-scope.sh 的反例回归测试（双向验证：正例 PASS 之外，证明每个检查项真会抓）。

用法:  python3 .agents/mechanism/guards/tests/guard-scope-fixture.py
退出码: 0 = 全部检查项按预期触发；1 = 有检查项「看起来在查、其实查不到」

为什么要有它：scope 守卫的使命就是防「假绿」（P1-交易101：设计 §6 前端整体未交付
却一路绿灯）。守卫自己若解析失败而静默 PASS，等于什么都没防住——所以每个
FAIL 面都要有坏样本证明它真会响；另有正例证明不误伤。
夹具只写 /tmp，**不动仓库任何文件**。
"""
import pathlib, re, shutil, subprocess, sys

HERE = pathlib.Path(__file__).resolve()
REPO = next(p for p in HERE.parents if (p / '.agents/mechanism').is_dir())
GUARD = REPO / '.agents/mechanism/guards/ai-guard-scope.sh'
FIX = pathlib.Path('/tmp/gs-fixture')

DESIGN = '''# 设计稿 mock
### 6. 三端呈现

| # | 功能 | web | app |
|:--|:--|:--|:--|
| `R-01` | 记录 | 有 | 有 |
| `R-02` | 账本 | 有 | 有 |
| `R-03` | 回合 | 有 | 有 |
| `R-04` | 分析 | 有 | 有 |
| `R-05` | 三粒度分析 | 有 | 结论 |
| `R-06` | 图表 | 有 | 有 |
| `R-07` | 规则 | 有 | 有 |
| `R-08` | 案例 | 有 | 有 |
| `R-09` | 计划 | 有 | 有 |
| `R-10` | 复盘 | 有 | 有 |
| `R-11` | 导出 | 有 | — |
| `R-12` | 导入 | 有 | — |
| 资金层 | 仓位 | 有 | 有 |
| 择时 | 择时 | 有 | — |

### 7. 下一节
'''

# 14 行完整条目（R-01..R-12 + 资金层 + 择时）；OK_ROWS 可被各 case 局部改写
def ok_rows(overrides=None):
    base = {
        'R-01': '| `R-01` | a | web | 本批 | 已交付 | `.agents/workspace/task/evidence.dart#TradingImportScreen` | — |',
        'R-02': '| `R-02` | b | web | 本批 | 已交付 | `.agents/workspace/task/evidence.dart#TradingImportScreen` | — |',
        'R-05': '| `R-05` | e | web | 本批 | 已交付 | `.agents/workspace/task/evidence.dart#TradingImportScreen` | — |',
        'R-12': '| `R-12` | l | web | 本批 | 已交付 | `.agents/workspace/task/evidence.dart#TradingImportScreen` | — |',
    }
    for i in range(1, 13):
        base.setdefault(f'R-{i:02d}', f'| `R-{i:02d}` | x | web | 非本任务 | — | — | 非本任务 |')
    base['资金层'] = '| 资金层 | m | web | 非本任务 | — | — | 非本任务 |'
    base['择时'] = '| 择时 | n | web | 非本任务 | — | — | 非本任务 |'
    if overrides:
        for k, (idx, row) in overrides.items():
            base[k] = row
    return [base[f'R-{i:02d}'] for i in range(1, 13)] + [base['资金层'], base['择时']]


def scope_text(rows, baseline='.agents/workspace/task/design.md'):
    head = '# 测试任务 · 范围对照表\n'
    if baseline:
        head += f'> 基线：{baseline}\n'
    head += '\n| # | 条目 | 面 | 计划 | 状态 | 证据 | 去向 |\n'
    head += '|:--|:--|:--|:--|:--|:--|:--|\n'
    return head + '\n'.join(rows) + '\n'


def mk(rows=None, baseline='.agents/workspace/task/design.md', design=DESIGN,
       ledger=True, review=True, scope_name='scope-t.md', scopes=None):
    """构建一个独立夹具仓库；scopes=[(文件名, 内容), ...] 可放多张表；ledger 可为字符串自定义内容"""
    shutil.rmtree(FIX, ignore_errors=True)
    w('.agents/workspace/task/design.md', design)
    w('.agents/workspace/task/evidence.dart', 'class TradingImportScreen {}\n')
    if ledger:
        content = '# LEDGER\n- 🟥 未交付：X\n' if ledger is True else ledger
        w('.agents/workspace/task/LEDGER.md', content)
    if review:
        w('.agents/records/REVIEW.md', '| P1-交易999 | 某历史问题 |\n')
    if scopes:
        for name, text in scopes:
            w(f'.agents/workspace/task/{name}', text)
    else:
        w(f'.agents/workspace/task/{scope_name}', scope_text(rows or ok_rows(), baseline))


def w(rel, text):
    p = FIX / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def run():
    r = subprocess.run(['bash', str(GUARD), '--root', str(FIX)],
                       capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr)


PASS_CNT, FAIL_CNT = 0, 0


def expect_fail(name, text, *needles):
    global PASS_CNT, FAIL_CNT
    rc, out = run()
    hit = all(n in out for n in needles)
    if rc != 0 and hit:
        PASS_CNT += 1
        print(f'  ✔ {name}')
    else:
        FAIL_CNT += 1
        print(f'  ✘ {name} — rc={rc} 期望 FAIL+{needles}')
        for line in out.splitlines()[:6]:
            print(f'      {line}')
    if text:
        pass


def expect_pass(name, *nots):
    global PASS_CNT, FAIL_CNT
    rc, out = run()
    bad = [n for n in nots if n in out]
    if rc == 0 and not bad:
        PASS_CNT += 1
        print(f'  ✔ {name}')
    else:
        FAIL_CNT += 1
        print(f'  ✘ {name} — rc={rc} 误报={bad}')
        for line in out.splitlines()[:8]:
            print(f'      {line}')


print('== 假绿防线（解析失败必须报，不许静默）==')
mk(scopes=[('scope-t.md', '# 没有基线行的表\n\n| `R-01` | a | web | 本批 | 已交付 | `.agents/workspace/task/evidence.dart#TradingImportScreen` | — |\n')])
expect_fail('S0a 缺「基线：」行', None, '缺「基线：」行')

mk(baseline='.agents/workspace/task/no-such-design.md')
expect_fail('S0b 基线设计稿不存在', None, '基线设计稿不存在')

mk(design='### 6. 三端呈现\n\n| `R-01` | a |\n\n### 7. 下一节\n')
expect_fail('S0c 设计稿 §6 解析 <10 条', None, '解析出 1 个条目')

mk(scopes=[('scope-t.md', '# 表\n> 基线：.agents/workspace/task/design.md\n\n| # | 条目 | 计划 | 状态 |\n|:--|:--|:--|:--|\n| `R-01` | a | 本批 | 已交付 |\n')])
expect_fail('S0d 表体 0 数据行（7 列格式变了）', None, '表体解析出 0 个数据行')

print('== 覆盖与枚举（S1/S2）==')
mk(rows=[r for r in ok_rows() if '`R-12`' not in r])
expect_fail('S1 缺 R-12 未交代', None, '未交代：R-12')

mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 随便吧 | 已交付 | `.agents/workspace/task/evidence.dart#TradingImportScreen` | — |')}))
expect_fail('S2a 计划枚举非法', None, '计划「随便吧」不在枚举')

mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | 快完了 | `.agents/workspace/task/evidence.dart#TradingImportScreen` | — |')}))
expect_fail('S2b 状态枚举非法', None, '状态「快完了」不在枚举')

mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | — | — | — |')}))
expect_fail('S2c 本批状态未闭合', None, '状态未闭合')

print('== 证据机核（S3）==')
mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | 已交付 | — | — |')}))
expect_fail('S3a 已交付但证据为空', None, '证据为空')

mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | 已交付 | `apps/nope/foo.dart#Bar` | — |')}))
expect_fail('S3b 证据路径不存在', None, '证据路径不存在')

mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | 已交付 | `.agents/workspace/task/evidence.dart#NoSuchSymbol` | — |')}))
expect_fail('S3c 证据关键词未命中（改了没证据）', None, '关键词未命中')

mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | 已交付 | `.agents/workspace/task/evidence.dart` | — |')}))
expect_fail('S3d 证据缺 # 关键词', None, '证据缺 `#` 关键词分隔')

print('== 去向可查（S4）==')
mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | 未交付 | — | — |')}))
expect_fail('S4a 未交付去向为空', None, '去向为空')

mk(ledger=False, rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | 未交付 | — | LEDGER · 批 2 |')}))
expect_fail('S4b 引用 LEDGER 但无 LEDGER.md', None, '无 LEDGER.md')

mk(ledger='# LEDGER\n（旧格式，没有红栏）\n',
   rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | 未交付 | — | LEDGER · 批 2 |')}))
expect_fail('S4b2 LEDGER 存在但无 🟥 红栏', None, '无 🟥')

mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 本批 | 未交付 | — | REVIEW P9-不存在99 |')}))
expect_fail('S4c 引用 REVIEW 编号查无', None, '查无此条')

mk(rows=ok_rows({'R-02': ('x', '| `R-02` | b | web | 下批 | — | — | — |')}))
expect_fail('S4d 下批但去向为空', None, '去向为空')

print('== 正例（不误伤）==')
mk(rows=ok_rows({
    'R-02': ('x', '| `R-02` | b | web | 本批 | 未交付 | — | LEDGER · 批 2 |'),
    'R-03': ('x', '| `R-03` | c | web | 下批 | — | — | 批 2 |'),
    'R-04': ('x', '| `R-04` | d | web | 本批 | 已交付 | `case:手工走查清单 → 验收 3` | — |'),
}))
expect_pass('全绿表（含未交付+LEDGER 去向 · case: 豁免）', 'FAIL')
mk(scopes=[('scope-a.md', scope_text(ok_rows())), ('scope-b.md', scope_text([r for r in ok_rows() if '`R-12`' not in r]))])
expect_fail('多表并存：坏表照样被抓', None, '未交代：R-12')

print('== 模板↔守卫接缝（模板的基线行必须能被守卫解析——防再次失联）==')
_tpl = (REPO / '.agents/workspace/_templates/scope.md').read_text(encoding='utf-8')
_m = re.search(r'^>\s*基线：\s*([^\s（(]+)', _tpl, re.M)
if _m and '分支名' in _m.group(1):
    PASS_CNT += 1
    print('  ✔ T1 模板「> 基线：」行可被守卫正则解析')
else:
    FAIL_CNT += 1
    print('  ✘ T1 模板基线行解析不了（模板与守卫失联——守卫会报「缺基线行」）')

print('== 无表（存量策略）==')
shutil.rmtree(FIX, ignore_errors=True)
w('.agents/workspace/task/readme.md', 'no scope here\n')
expect_pass('无 scope 表 → PASS(0)（不误伤存量任务）')

print(f'\n结果：PASS {PASS_CNT} · FAIL {FAIL_CNT}')
print('✅ 全部检查项（S0–S4 双向）均按预期' if FAIL_CNT == 0 else '❌ 有检查项未按预期触发')
sys.exit(0 if FAIL_CNT == 0 else 1)
