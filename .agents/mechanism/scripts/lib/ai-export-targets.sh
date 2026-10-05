#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# AI 资产「工具出口」清单 —— **唯一真相源**（2026-10-05 批）
#
# 解决的问题：同一个事实（出口在哪、谁去检查）原先散在 4 个脚本里各写一份，
#   加一个工具要改 4 处，漏一处**静默**——事故已经发生过：
#   · 2026-10-04 六顶层重构把真相源从 `.agents/skills/` 移到 `.agents/toolkit/skills/`，
#     出口位 `.agents/skills` 被漏改 ⇒ 规范 §四 至今声称它是 DSH/Codex 的出口，
#     实际不存在，而 ai-guard-tools 的 T4 还在扫这个空路径（静默 PASS）。
#   · 同型两次：`ai-guard-release.sh` 里 `release-units.sh` 路径漏改（发版判定工具一直失效）、
#     `code-deploy-gate.sh` 的 ROOT 被 cd 错（部署门禁跑不起来）。
#
# 谁读它（全部 source 本文件，不再各写一份）：
#   ① ai-link-skills.sh  —— 按 SKILL_TARGETS 建**技能**软链出口
#   ② ai-sync-agents.sh  —— 按 AGENT_TARGETS **生成**子代理定义
#   ③ ai-guard-tools.sh  —— T4 直接读 SKILL_TARGETS 判定软链真身
#                            ⇒ 「新加的出口没人检查」在结构上不可能再发生
#   ④ ai-sync-all.sh     —— 一条命令全量重建 + 自检
#
# 新增工具：**只改本文件** → 跑 `bash .agents/mechanism/scripts/ai-sync-all.sh`
#   → 按规范 §五 第 4 步放探针实测 → 把结果写回 `ai-context-layer-spec.md` §四（含日期）。
# ─────────────────────────────────────────────────────────────

# ── 技能出口（相对仓库根；真相源 = .agents/toolkit/skills/<name>/SKILL.md）──
#   .dsh/skills     DSH（与 .agents/skills 两者实测均生效）
#   .claude/skills  Claude Code（官方明确支持技能目录软链；官方**不读** .agents/）
#   .qoder/skills   Qoder（官方 CLI + IDE 文档确认；**不是**仓库根 skills/）
#   .agents/skills  公约数阵营（Codex / Cursor / Gemini CLI / Copilot / OpenCode …）
#                   ⚠️ 它是**出口位**，不是真相源——真相源在 .agents/toolkit/skills/（两者别混）
SKILL_TARGETS=(".dsh/skills" ".claude/skills" ".qoder/skills" ".agents/skills")

# ── 子代理出口（相对仓库根；格式由 ai-sync-agents.sh 按目录名分派）──
#   .qoder/agents   md + YAML
#   .codex/agents   TOML（同一份内容没法用一条软链喂两家，只能生成）
AGENT_TARGETS=(".qoder/agents" ".codex/agents")

# ── 预留位：有工具真用到时再启用（启用前必须实测并把结果写回规范 §四）──
# SKILL_TARGETS=("${SKILL_TARGETS[@]}" "skills")   # OpenClaw 等用仓库根 skills/ 的工具
