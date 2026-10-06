#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 把「审查官」真相源生成为各工具的 subagent 定义（换机 / 改动后执行）
#
# 用法:
#   bash .agents/mechanism/scripts/ai-sync-agents.sh          # 生成 / 更新
#   bash .agents/mechanism/scripts/ai-sync-agents.sh --check  # 只检不写；不一致则退出码 1
#
# 为什么是「生成」而不是「软链」（与技能层的根本差别）：
#   审查官的真相源是 .agents/toolkit/roles/<name>.md（项目层，进 git，**扁平**）。
#   但各工具的 subagent 定义**格式不同** —— Qoder 是 md + YAML、Codex 是 **TOML** ——
#   同一份内容没法用一条软链喂两家，只能生成。（技能层两家都认 <name>/SKILL.md，故可软链。）
#
# 为什么审查官真相源保持扁平、不目录化：
#   它们**不进技能出口**（流程内触发，见 process/review.md），其"出口"是本脚本生成的定义；
#   而目录化要用 3 个审查官换 44 处引用修复（2026-10-03 尽调实测）——零收益。
#
# 注册范围（2026-10-03 由 3 个扩到全部 12 个）：
#   唯一的成本是**每个 subagent 的 description 常驻上下文**——实测 12 个合计约 1.2k 字
#   （≈2k token），远低于 Claude Code 官方 15k token 的告警线（那条预算正是为 description 设的）。
#   ⚠️ 新增审查官时盯住这条线：接近预算时优先**缩短 description**，而不是砍审查官。
#
# 生成时做两件事（不止复制正文）：
#   ① **路径重写**：正文里的 `../assets/x.md` → `.agents/rules/assets/x.md`
#      （真相源里的相对路径是相对 roles/ 的；作为 system prompt 会被解析错）
#   ② **只读强制**：Qoder `tools: Read, Grep, Glob` · Codex `sandbox_mode = "read-only"`
#      —— 把原则 B7「审查只报告不直接修」从 prompt 自律升级为**机制强制**
#
# 规范：项目级 AI 上下文中间层规则见 .agents/rules/assets/ai-context-layer-spec.md
# ─────────────────────────────────────────────────────────────
set -u
cd "$(git rev-parse --show-toplevel)"   # 层级无关（2026-10-04 加固：原 ../.. 是隐式位置假设）
ROOT="$(pwd)"

# ── 注册清单：新增「值得独立派出」的审查官时把名字加进来（= roles/ 下的文件名 stem）──
REGISTER=(
  ai-adversarial-reviewer ux-stranger-reviewer code-backend-reviewer
  ai-context-reviewer docs-contract-reviewer code-frontend-reviewer data-knowledge-reviewer
  docs-product-reviewer ux-social-reviewer ux-support-reviewer ux-visual-reviewer ux-interaction-reviewer
  docs-requirement-reviewer   # 需求文稿审核官（介入点①前一步，2026-10-06）
  docs-design-writer   # 审核驱动主链的产作者（与设计文档审核者真对打，2026-10-03）
  process-reviewer   # 流程官（QA）——盯流程 / 记问题 / 提改进，不判内容、不代笔（2026-10-06）
  ai-context-health-reviewer   # 体系体检官——全景体检 .agents/ 的叙事层（守卫覆盖不到的那些）；触发词「体检」（2026-10-06）
)

# ── 目标工具 subagent 目录：**清单的唯一真相源在 lib/ai-export-targets.sh**（2026-10-05 起）──
#    格式仍由本脚本按目录名分派（.qoder → md+YAML · .codex → TOML）。
# shellcheck source=/dev/null
. "$ROOT/.agents/mechanism/scripts/lib/ai-export-targets.sh"
TARGETS=("${AGENT_TARGETS[@]}")

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

FAIL=0
for t in "${TARGETS[@]}"; do
  for s in "${REGISTER[@]}"; do
    SRC=".agents/toolkit/roles/$s.md"
    if [ ! -f "$SRC" ]; then
      echo "  ❌ 真相源不存在: ${SRC}（清单里的名字写错了？）"
      FAIL=$((FAIL+1)); continue
    fi

    case "$t" in
      *.qoder/*) EXT="md" ;;
      *.codex/*) EXT="toml" ;;
      *)         EXT="md" ;;
    esac
    DST="$t/$s.$EXT"

    # 生成到临时文件再比对/落位（避免半成品）
    TMP="$(mktemp)"
    python3 - "$ROOT" "$SRC" "$t" "$EXT" > "$TMP" <<'PYEOF'
import re, sys, pathlib
root, src, target, ext = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
text = pathlib.Path(src).read_text(encoding="utf-8")

# ── 拆 frontmatter 与正文 ──
m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.S)
if not m:
    sys.exit("真相源缺 frontmatter: %s" % src)
fm, body = m.group(1), m.group(2)

def fm_val(key):
    mm = re.search(r"^%s:\s*(.+)$" % re.escape(key), fm, re.M)
    return mm.group(1).strip() if mm else ""

name = fm_val("name") or pathlib.Path(src).stem
desc = fm_val("description")

# ── 路径重写：roles/ 到项目根是 ../../、到 ai-engineering/ 是 ../ ──
body = body.replace("../../", "").replace("../", ".agents/")
body = body.strip() + "\n"

if ext == "toml":
    # Codex：TOML，正文进 developer_instructions（多行基本字符串）
    safe = body.replace('"""', '\\"\\"\\"')
    print('name = "%s"' % name)
    print('description = "%s"' % desc.replace('"', '\\"'))
    print('sandbox_mode = "read-only"   # 机制强制「审查只报告不直接修」（B7）')
    print('developer_instructions = """')
    print(safe, end="")
    print('"""')
else:
    # Qoder / 通用：md + YAML frontmatter，正文 = system prompt
    print("---")
    print("name: %s" % name)
    print("description: %s" % desc)
    print("tools: Read, Grep, Glob   # 只读强制：审查只报告不直接修（B7）")
    print("---")
    print()
    print(body, end="")
PYEOF

    if [ "$CHECK" -eq 1 ]; then
      if [ -f "$DST" ] && cmp -s "$TMP" "$DST"; then
        echo "  ✅ 已同步: ${DST}"
      else
        echo "  ❌ 未生成/已过期: ${DST}（跑 bash .agents/mechanism/scripts/ai-sync-agents.sh）"
        FAIL=$((FAIL+1))
      fi
      rm -f "$TMP"; continue
    fi

    if [ -f "$DST" ] && cmp -s "$TMP" "$DST"; then
      rm -f "$TMP"; echo "  ✅ 已是最新: ${DST}"; continue
    fi

    mkdir -p "$t"
    mv "$TMP" "$DST"
    echo "  🔗 已生成: ${DST}"
  done
done

if [ "$FAIL" -gt 0 ]; then
  echo ""; echo "结果: ${FAIL} 项异常"; exit 1
fi
[ "$CHECK" -eq 0 ] && { echo ""; echo "✅ subagent 定义生成完成（验证: bash .agents/mechanism/scripts/ai-sync-agents.sh --check）"; }
exit 0
