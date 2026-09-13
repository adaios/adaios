#!/usr/bin/env python3
r"""shell 脚本健壮性 lint：`$VAR` 紧跟非 ASCII 字节。

## 为什么需要它

非 UTF-8 locale（`LANG` 未设 / 为 `C`——cron、git hook、部分 CI 的默认）下，bash **不把
多字节字符当词法边界**：`echo "计数 $N（说明）"` 里的 `（`（`EF BC 88`）首字节会被并进
变量名，于是去找名为 `N\xef` 的变量。在 `set -u` 下就是

    script.sh: line 7: N?: unbound variable

当场中止；而**代码看起来完全正常**，本机（UTF-8）直接跑也正常——只在换环境时炸。
症状有强误导性：报「未定义」但变量上一行明明赋过值。

## 判据

把变量写进花括号即可（`${N}`），所以规则是：**凡中文文案里嵌 shell 变量，一律加花括号**。

## 检测方式

按 shell 词法跟踪状态，只在**真正会发生展开**的位置报：

- ✅ 报：双引号内 / 裸词中的 `$VAR` 紧跟非 ASCII
- ❌ 不报：单引号内（不展开）、`#` 注释内（不展开）、`\$` 转义（字面量）、
  `${VAR}`（已安全）、`$(cmd)` `$((expr))`、`$?` `$#` `$@` `$$`（单字符特殊参数立即终止）

## 用法

    python3 scripts/lint-shell-vars.py                 # 扫全部 git 跟踪的 shell 脚本
    python3 scripts/lint-shell-vars.py a.sh b.sh       # 只扫指定文件

退出码：0 = 干净；1 = 有命中。由 `ai-engineering/guard-tools.sh` T6 与 git pre-commit 调用。
"""

import re
import subprocess
import sys

IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|[0-9]+")


def tracked_shell_scripts():
    """git 跟踪的 shell 脚本：.sh/.bash + 无扩展名但带 sh/bash shebang 的（如 .githooks/pre-commit）。"""
    try:
        files = subprocess.run(
            ["git", "ls-files"], capture_output=True, text=True, check=True
        ).stdout.split()
    except (OSError, subprocess.CalledProcessError):
        return []
    out = []
    for path in files:
        if path.endswith((".sh", ".bash")):
            out.append(path)
            continue
        try:
            with open(path, "rb") as fh:
                head = fh.read(40)
        except OSError:
            continue
        if head.startswith(b"#!") and re.match(rb"#!.*\b(?:ba)?sh\b", head):
            out.append(path)
    return sorted(set(out))


def scan(path):
    """返回 [(lineno, 整行, 变量引用)]"""
    hits = []
    try:
        with open(path, "rb") as fh:
            text = fh.read().decode("utf-8")
    except (OSError, UnicodeDecodeError):
        return hits

    for lineno, line in enumerate(text.split("\n"), 1):
        i, n, state = 0, len(line), "normal"
        while i < n:
            c = line[i]
            if state == "single":  # 单引号：不展开
                if c == "'":
                    state = "normal"
                i += 1
                continue
            if c == "\\":  # 转义：跳过下一个字符
                i += 2
                continue
            if state == "normal":
                if c == "'":
                    state = "single"
                    i += 1
                    continue
                if c == '"':
                    state = "double"
                    i += 1
                    continue
                if c == "#" and (i == 0 or line[i - 1] in " \t;&|("):
                    break  # 注释到行尾
            else:  # double
                if c == '"':
                    state = "normal"
                    i += 1
                    continue
            if c == "$":
                m = IDENT.match(line, i + 1)
                if m:
                    if m.end() < n and ord(line[m.end()]) > 127:
                        hits.append((lineno, line.strip(), line[i:m.end()]))
                    i = m.end()
                    continue
            i += 1
    return hits


def main(argv):
    paths = argv[1:] or tracked_shell_scripts()
    if not paths:
        print("SHELL-LINT: SKIP（未找到 shell 脚本）")
        return 0

    total = 0
    for path in paths:
        for lineno, line, var in scan(path):
            total += 1
            print(f"  ❌ {path}:{lineno}: {var} 紧跟非 ASCII → 改用 ${{{var[1:]}}}")
            print(f"       {line[:110]}")
    if total:
        print(
            f"SHELL-LINT: FAIL（{total} 处）——"
            "非 UTF-8 locale 下该字节会被并进变量名，set -u 时 unbound variable 中止"
        )
        return 1
    print(f"SHELL-LINT: PASS（{len(paths)} 个脚本，0 处）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
