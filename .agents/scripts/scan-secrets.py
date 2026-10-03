#!/usr/bin/env python3
r"""暂存区密钥扫描：提交前堵住「密钥进了 git 历史」这个不可逆风险。

## 为什么需要它

`.gitignore` 只防**将来**、不查**过去**。本项目把 6 个密钥放在 `services/adai-core/.env`
（DEEPSEEK_API_KEY / GLM_API_KEY / ADAI_ADMIN_TOKEN / ADAI_PUSH_WECHAT_SENDKEY /
ADAI_SMOKE_ACCOUNT / ADAI_SMOKE_PASSWORD），另有 APNs 私钥（`.p8`）与生产密码。
一旦误提交，密钥永久留在 GitHub 历史里——**删掉文件也删不干净**（要改历史 + 换密钥）。

## 判据（两级）

- **BLOCK（阻断提交）**
  1. **文件名**：`.env*` / `*.p8` `*.p12` `*.jks` `*.keystore` `*.mobileprovision` / `id_rsa*`
     ——独立于 diff 文本判定（`git diff --cached --name-only`），所以**二进制文件与纯重命名也拦得住**
  2. **内容形态**：私钥块 / `sk-…` / `AKIA…` / `ghp_…` / `github_pat_…` / `xox?-…` / `AIza…`
- **WARN（只提示，不阻断）**
  - `API_KEY / SECRET / TOKEN / PASSWORD / SENDKEY … = <长值>` 且值不像占位符
  - 命中了上面的形态但**看着像占位符或代码字面量**（含 `your`/`example`/`placeholder` 等词，
    或整串被引号包着，如 Java 里的 `"-----BEGIN PRIVATE KEY-----"`）——本仓库自带这类行，
    硬拦会把将来的正常提交逼去 `--no-verify`（比没有守卫更糟，见 `.agents/process/ship.md`）

## 边界（已知且刻意）

只扫**暂存区新增行**，不查历史。要审历史用参数模式逐个文件扫。

## 输出

**绝不回显完整密钥**：只给文件、行号、规则名，以及「前 4 位 + 总长度」。

## 用法

    python3 .agents/scripts/scan-secrets.py              # 扫暂存区（pre-commit 调用）
    python3 .agents/scripts/scan-secrets.py <file>...    # 扫指定文件全文（人工审计 / 自测）

退出码：0 = 干净或只有 WARN；1 = 有 BLOCK；2 = 读取暂存区失败（git 出错）。
"""

import re
import subprocess
import sys

# ── BLOCK 级：敏感文件名（独立于 diff 文本判定，二进制与 rename 同样拦得住）──
PATH_PATTERNS = [
    (re.compile(r"(^|/)\.env($|\.)"), "env 文件（应被 .gitignore 排除）"),
    (re.compile(r"\.(p8|p12|pfx|jks|keystore|mobileprovision)$", re.I), "签名 / 证书文件"),
    (re.compile(r"(^|/)id_(rsa|ed25519|ecdsa)$"), "SSH 私钥"),
]

# ── BLOCK 级：高置信密钥形态（出现即拦，除非看着像占位符/代码字面量 → 降 WARN）──
FORM_PATTERNS = [
    ("私钥块", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("AI key（sk- 形态）", re.compile(r"\bsk-[A-Za-z0-9_-]{20,}")),
    ("AWS Access Key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("GitHub 细粒度 PAT", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{50,}\b")),
    ("Slack token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
]

# ── WARN 级：通用赋值（值长且像随机串才提示）──
ASSIGN = re.compile(
    r"""(?i)\b([A-Z0-9_]*(?:API_?KEY|APIKEY|SECRET|TOKEN|PASSWORD|PASSWD|SENDKEY|CREDENTIAL)[A-Z0-9_]*)\s*[:=]\s*["']?([^\s"'#;]{12,})"""
)

# 占位符（前缀式，用于通用赋值）
PLACEHOLDER = re.compile(
    r"""(?i)^(\$\{|<|\{\{|your[_-]|my[_-]|xxx|placeholder|example|sample|changeme|change_me|todo|none|null|nil|true|false|test|dummy|fake|redacted|\*+|\.\.\.|\[)"""
)

# 占位符（包含式，用于形态命中：`sk-your-deepseek-api-key-here` 这类要放过）
PLACEHOLDER_SUBSTR = re.compile(
    r"""(?i)(your|example|sample|placeholder|changeme|change_me|dummy|fake|redacted|xxxx|\.\.\.|here)"""
)


def looks_random(value):
    """长值 + 大小写/数字混合 → 像密钥（用于 WARN 级判据）。"""
    if len(value) < 20:
        return False
    has_digit = any(c.isdigit() for c in value)
    has_upper = any(c.isupper() for c in value)
    has_lower = any(c.islower() for c in value)
    return has_digit and (has_upper or has_lower) and (has_upper + has_lower) >= 2


def in_quotes(text, pos):
    """匹配处是否紧跟在一个引号之后（→ 多半是代码/配置里的字符串字面量，不是真密钥）。"""
    return text[:pos].rstrip().endswith(('"', "'"))


def mask(value):
    """脱敏：只给前 4 位与长度，绝不回显完整值。"""
    return "%s…（%d 字符）" % (value[:4], len(value))


def check_path(path, blocks):
    """文件名级判定（二进制文件没有 diff 文本，必须靠这条）。"""
    for pat, name in PATH_PATTERNS:
        if pat.search(path):
            blocks.append((path, 0, "敏感文件：%s" % name, ""))
            return


def scan_text(path, lineno, text, blocks, warns):
    hit = False
    for name, pat in FORM_PATTERNS:
        for m in pat.finditer(text):
            value = m.group(0)
            if in_quotes(text, m.start()) or PLACEHOLDER_SUBSTR.search(value):
                warns.append((path, lineno, "%s（像占位符/字符串字面量，未阻断）" % name, mask(value)))
            else:
                blocks.append((path, lineno, name, mask(value)))
                hit = True
    if hit:
        return  # 同一行已判 BLOCK，不再重复 WARN（减噪）
    m = ASSIGN.search(text)
    if m:
        key, value = m.group(1), m.group(2)
        if not PLACEHOLDER.match(value) and looks_random(value):
            warns.append((path, lineno, "%s 赋值" % key, mask(value)))


def staged_paths():
    """暂存区里**新增/修改/重命名**的文件路径（含二进制文件——它们没有 diff 文本）。"""
    proc = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        print("SECRET-SCAN: 无法读取暂存区（git diff 出错）", file=sys.stderr)
        sys.exit(2)
    return [p for p in proc.stdout.splitlines() if p.strip()]


def staged_lines():
    """产出 (路径, 行号, 行内容)：暂存区**新增**的行（只看将要提交的内容）。

    状态机而非「按前缀猜」：`+++` 只在 hunk 之外才可能是文件头，而 hunk 内部
    以 `+++` 开头的内容行（diff 里写作 `++++`）必须按内容扫——2026-10-01 对抗审查实测
    「内容行以 `++ ` 开头会被当成文件头 → 整行漏扫」。
    """
    proc = subprocess.run(
        ["git", "diff", "--cached", "-U0", "--no-color", "--diff-filter=ACMR"],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        print("SECRET-SCAN: 无法读取暂存区（git diff 出错）", file=sys.stderr)
        sys.exit(2)
    path, lineno, in_hunk = None, 0, False
    for line in proc.stdout.splitlines():
        if line.startswith("diff --git "):
            m = re.match(r"diff --git a/(.*) b/(.*)$", line)
            path = m.group(2) if m else None
            lineno, in_hunk = 0, False
        elif line.startswith("@@"):
            m = re.search(r"\+(\d+)", line)
            lineno = int(m.group(1)) if m else 0
            in_hunk = True
        elif in_hunk and line.startswith("+"):
            if path:
                yield path, lineno, line[1:]
            lineno += 1


def file_lines(paths):
    for path in paths:
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                for i, line in enumerate(fh, 1):
                    yield path, i, line.rstrip("\n")
        except OSError as exc:
            print("SECRET-SCAN: 读不到 %s（%s）" % (path, exc), file=sys.stderr)


def main(argv):
    blocks, warns = [], []
    if argv:
        paths = list(argv)
        source, label = file_lines(paths), "%d 个文件" % len(paths)
    else:
        paths = staged_paths()
        source, label = staged_lines(), "暂存区新增行"

    for path in paths:
        check_path(path, blocks)          # 文件名级：两种模式都做（覆盖二进制 / rename）
    for path, lineno, text in source:
        scan_text(path, lineno, text, blocks, warns)

    if not paths and not argv:
        print("SECRET-SCAN: 暂存区无内容，跳过")
        return 0

    dedup, keys = [], set()
    for item in blocks:
        key = (item[0], item[1], item[2])
        if key not in keys:
            keys.add(key)
            dedup.append(item)

    for path, lineno, rule, shown in warns:
        where = "%s:%d" % (path, lineno) if lineno else path
        print("  ⚠️  [WARN] %s  %s%s" % (where, rule, ("  " + shown) if shown else ""))

    if dedup:
        print("SECRET-SCAN: %d 处 BLOCK（扫描 %s）" % (len(dedup), label))
        for path, lineno, rule, shown in dedup:
            where = "%s:%d" % (path, lineno) if lineno else path
            print("  ❌ [BLOCK] %s  %s%s" % (where, rule, ("  " + shown) if shown else ""))
        print("   处置：从暂存区摘出（git restore --staged <file>）；若确实需要提交，")
        print("         说明它为何不是密钥；若已推送过，必须换密钥而非删文件。")
        return 1

    suffix = "，%d 处 WARN" % len(warns) if warns else ""
    print("SECRET-SCAN: PASS（%s，0 处 BLOCK%s）" % (label, suffix))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
