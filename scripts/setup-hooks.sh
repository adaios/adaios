#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 启用 git hooks（core.hooksPath → .githooks/）
# 换机器 clone 后执行一次：sh scripts/setup-hooks.sh
# 作用：pre-commit 自动跑 guard-align（内容对齐）+ guard-meta（结构）+ guard.sh（防 P0 复发）
# ─────────────────────────────────────────────────────────────
cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
echo "✅ git hooks 已启用（core.hooksPath = .githooks）"
echo "   提交时自动检查：文档对齐 + frontmatter 结构 + G1-G7 防复发"
