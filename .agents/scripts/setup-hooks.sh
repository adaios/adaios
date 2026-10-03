#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 启用 git hooks（core.hooksPath → .githooks/）+ 恢复仓库级 git 设置
# 换机器 clone 后执行一次：bash .agents/scripts/setup-hooks.sh
# 作用：pre-commit 自动跑 本批范围守卫 + 隐私/密钥闸门 + guard-align + guard-meta + guard.sh + shell-lint
#
# 为什么顺手设 git config：下面三条是**仓库级**设置（写在 .git/config 里）——
# .git 本身不入库，所以换机 / 重新 clone 后会丢。2026-10-01 落地多分支工作方式时加的：
#   rerere.enabled  记住冲突解法，rebase 时自动复用（多分支并行时不用重复解同一处注册表冲突）
#   fetch.prune     fetch 时清掉远端已删分支的过期引用
#   pull.rebase     拉取走 rebase：主干历史保持线性，批次边界清晰
# ─────────────────────────────────────────────────────────────
set -u
cd "$(dirname "$0")/../.."

git config core.hooksPath .githooks
echo "✅ git hooks 已启用（core.hooksPath = .githooks）"
echo "   提交时自动检查：范围守卫 + 隐私/密钥闸门 + 文档对齐 + frontmatter 结构 + 防复发 + shell 健壮性"

# ── 仓库级 git 设置（幂等：已设就只报状态，不改动）──
echo ""
echo "▸ 仓库级 git 设置（换机 / 重新 clone 后需重设，故放在这个「换机必跑」脚本里）"
for kv in "rerere.enabled=true" "fetch.prune=true" "pull.rebase=true"; do
  key="${kv%%=*}"
  want="${kv#*=}"
  have="$(git config --local --get "${key}" 2>/dev/null || true)"
  if [ "${have}" = "${want}" ]; then
    echo "  ✅ ${key} = ${have}"
  else
    git config --local "${key}" "${want}"
    echo "  ✅ ${key} = ${want}（原值：${have:-未设}）"
  fi
done
echo "   可选配套（未默认开启）：rebase.autoStash=true 会在 rebase 前自动暂存未提交改动；"
echo "                            rerere.autoupdate=true 会在解完冲突后自动 git add。"
