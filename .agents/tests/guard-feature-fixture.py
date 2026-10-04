#!/usr/bin/env python3
"""ai-guard-feature.sh 的反例回归测试（双向验证：正例 PASS 之外，证明每个检查项真会抓）。

用法:  python3 .agents/tests/guard-feature-fixture.py
退出码: 0 = 全部检查项按预期触发；1 = 有检查项「看起来在查、其实查不到」

为什么要有它：2026-10-01 首版切卡逻辑被一级标题挡住 → 卡计数恒 0（F7/F8 形同虚设），
正例照样 PASS。**只测正例 = 假绿的温床**。当年对抗审查又抓出 4 处假绿 + 1 处误报
（见 docs/records/audits/2026-10-01-feature-index-adversarial.md），坏样本全部固化在这里。
夹具只写 /tmp，**不动仓库任何文件**。
"""
import pathlib, shutil, subprocess, sys

REPO = pathlib.Path(__file__).resolve().parents[2]
FIX = pathlib.Path('/tmp/gf-fixture')


def w(rel, text):
    p = FIX / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


shutil.rmtree(FIX, ignore_errors=True)

w('.agents/features/_index.md', '''# 索引

| ID | 功能 | 状态 | 需求出处 | 实现出处 | 欠着 |
|:---|:-----|:----:|:---------|:---------|:-----|
| `good` | 正常功能 | shipped | [rfc](../rfc/20261001-ok.md#some-anchor) | 手册 §1 | P2-测试11 |
| `bad-cols` | 少一列 | shipped | — | 手册 §2 |
| `bad-empty` |  | shipped | — | 手册 §3 | — |
| `bad-impl` | 空实现 | shipped | — |  | — |
| `bad-link` | 死链 | shipped | [x](../rfc/nope.md) | 手册 §4 | — |
| `bad-warn` | 标了警告 | shipped | ⚠️ RFC 20260101 | 手册 §5 | — |
| `bad-status` | 状态乱写 | done | — | 手册 §6 | — |
| `bad-mismatch` | 状态与事实不符 | shipped | — | 手册 §6b（认知层整体**待建**）| — |
| `bad-ref` | 欠着编号假 | shipped | — | 手册 §7 | P2-不存在99 |
| `bad-substr` | 欠着子串假绿 | shipped | — | 手册 §7b | P2-测试1 |
| `bad-prose` | 欠着写散文 | shipped | — | 手册 §8 | 等一下再说 |
| `orphan-card` | 孤儿卡 | shipped | — | 手册 §9 | — |

## 意图卡

| 插件 | 卡文件 | 已生长的卡 |
|:-----|:-------|:-----------|
| kernel | [kernel.md](kernel.md) | `good` |
''')

w('.agents/features/kernel.md', '''---
title: x
---

# 卡

## `good` · 正常卡

- **干什么用**：一句话。
- **不做什么**：一句话。
- **选型理由**：一句话。
- **验收**：一条。
- **细节**：链接。

## `bad-detail` · 含实现细节的卡

- **干什么用**：调用 `TradeController`，改 `POST /api/v1/records` 与 `TradingAppService.java`，再跑 `foo()`。

## `long` · 超长卡

''' + '\n'.join(f'- 第 {i} 行' for i in range(1, 14)) + '\n')

# F9 孤儿卡（根目录，未登记）
w('.agents/features/other.md', '---\ntitle: y\n---\n\n# 孤儿\n\n## `某卡`\n\n- **干什么用**：一句话。\n')
# B5 子目录卡文件（非递归 glob 会漏检）——故意不登记
w('.agents/features/kernel/orphan.md', '---\ntitle: z\n---\n\n# 子目录孤儿\n\n## `子卡`\n\n- **干什么用**：一句话。\n')
# F10 有 ## 段落但标题不含反引号 → 卡识别不出来（防格式写歪导致 F7/F8 静默失效）
w('.agents/features/kernel/badformat.md', '---\ntitle: f\n---\n\n# 格式写歪\n\n## 记录提交与意图分流\n\n- **干什么用**：标题里没有反引号 ID。\n')

w('.agents/rfc/20261001-ok.md', '---\ntitle: ok\ndate: 2026-10-01\nstatus: approved\n---\n# ok\n')
w('.agents/rfc/20261001-bad.md', '---\ntitle: bad\ndate: 2026-10-01\nstatus: completed\n---\n# bad\n')
# B2a 新 RFC 缺 date：不得静默跳过
w('.agents/rfc/20261001-nodate.md', '---\ntitle: nodate\nstatus: completed\n---\n# 缺 date\n')
# B2b date 被超长 description 顶到解析窗口之外（旧实现 [:900] 会漏）
w('.agents/rfc/20261001-longdesc.md',
  '---\ntitle: longdesc\ndescription: ' + 'x' * 2000 + '\ndate: 2026-10-01\nstatus: completed\n---\n# 长 description\n')
# 存量：date < 截止日 → 应被跳过（不报）
w('.agents/rfc/20260901-old.md', '---\ntitle: old\ndate: 2026-09-01\nstatus: completed\n---\n# old\n')
w('.agents/records/REVIEW.md', '| P2-测试11 | 正常条目 |\n')
w('empty/.agents/features/_index.md', '# 啥也没有\n\n没有功能行。\n')


def run(root):
    r = subprocess.run(['bash', str(REPO / '.agents/guards/ai-guard-feature.sh'), '--root', str(root)],
                       capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr).strip()


rc, out = run(FIX)
print('── 反例夹具输出 ──')
print(out)
print(f'退出码={rc}')

rc2, out2 = run(FIX / 'empty')
print('── F0 空表夹具 ──')
print(out2, f'退出码={rc2}')

ok = True


def need(cond, msg):
    global ok
    if not cond:
        print(f'✗ {msg}')
        ok = False


need(rc != 0, '反例夹具竟然 PASS（假绿）')
for f in ['F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'F9', 'F10']:
    need(f'   {f} ' in out or f' {f} ' in out, f'{f} 未被触发')
need('F0' in out2 and rc2 != 0, 'F0 空表未被拦下')
need('20260901-old' not in out, '存量 RFC 未被跳过（渐进策略失效）')
need('状态与事实不符' in out, 'F4 状态-证据粗对拍未触发')
# 对抗审查 B1：子串匹配假绿（REVIEW 只有 P2-测试11，引用 P2-测试1 必须被抓）
need('P2-测试1` 在 REVIEW.md 里查无此条' in out, 'F6 词边界失效（子串假绿未修）')
# 对抗审查 B2：缺 date / date 被顶出窗口 都要报
need('20261001-nodate.md' in out, 'F5 缺 date 的新 RFC 被静默跳过')
need('20261001-longdesc.md' in out, 'F5 date 被超长 description 顶出解析窗口')
# 对抗审查 B3：带锚点的合法链接不得误报死链
need('some-anchor' not in out, 'F2 把带锚点的合法链接误报成死链')
# 对抗审查 B5：子目录卡文件不得漏检
need('kernel/orphan.md' in out, 'F9 漏检子目录卡文件（glob 非递归）')
# F10：格式写歪必须报，不能静默
need('F10' in out and 'badformat.md' in out, 'F10 未拦住「有 ## 段落却识别不出卡」')
need('F9' in out, 'F9 未拦住孤儿卡')

print('\n' + ('✅ 全部检查项（F0–F10 + 状态对拍 + 存量跳过 + 锚点不误报）均按预期' if ok else '❌ 有检查项未按预期触发'))
sys.exit(0 if ok else 1)
