---
title: 开发指南（Development Guide）
description: 全局构建/测试/运行/部署命令与开发环境说明——原根 CLAUDE.md 迁移承接，工具无关
version: 1
created: 2026-08-19
updated: 2026-10-03
status: active
lines: 60
depends-on: []
related:
  - ../../direction/VISION.md
  - ../../../docs/README.md
tags: [guide, dev]
---

# 开发指南

> 承接原根 CLAUDE.md 的构建命令（2026-08-19 根 CLAUDE.md 已删除、子项目统一改名为 AGENTS.md）。子项目内部命令以各子项目 `AGENTS.md` 为准（就近原则）。

## 后端（services/adai-core/）

```bash
cd services/adai-core && ./gradlew build -x test          # 编译（跳过测试）
cd services/adai-core && ./gradlew build                  # 编译 + 测试

cd services/adai-core && ./gradlew test                   # 运行全部测试
cd services/adai-core && ./gradlew test --tests "*ClassName*"   # 单个测试类
cd services/adai-core && ./gradlew test --tests "*ClassName.methodName"  # 单个方法

cd services/adai-core && ./gradlew bootJar
cd services/adai-core && ./deploy.sh 82.156.111.146 build/libs/adai-core-0.0.1-SNAPSHOT.jar   # 部署（scp + 重启 + 验证）
```

> ⚠️ **部署是外向动作，由人确认后手动触发**（脚本只负责上传/重启，见 `deploy.sh` 头部说明；边界 B8）。

- 运行 DeepSeek 模式（默认，需在 `.env` 配置 `DEEPSEEK_API_KEY`）：`cd services/adai-core && ./gradlew bootRun`
- Mock 模式（无需 API Key，临时测试用）：`cd services/adai-core && ./gradlew bootRun`

## 前端（apps/）

```bash
cd apps/adai-app && flutter run -d chrome          # Web
cd apps/adai-app && sh .agents/mechanism/scripts/serve_web.sh        # Web（本地补丁 + Python 服务器）
cd apps/adai-app && flutter run -d android         # Android
```

## 环境与工程

- **零数据库启动**：MVP 阶段不需要 MySQL，所有数据通过 File First 存储到 `data/`。
- **git hooks（换机 clone 后执行一次）**：`sh .agents/mechanism/scripts/ai-setup-hooks.sh` —— 启用 pre-commit 自动检查（文档对齐 + frontmatter 结构 + G1-G7 防复发 + shell 脚本健壮性）。
- **技能注册（换机 clone 后执行一次）**：`bash .agents/mechanism/scripts/ai-link-skills.sh` —— 把「用户直触发」技能**软链**到四个工具出口（`.dsh/skills`＝DSH、`.agents/skills`＝DSH 与 Codex/Cursor/Gemini CLI/Copilot 等的公约数、`.claude/skills`＝Claude Code、`.qoder/skills`＝Qoder CLI/IDE/JetBrains 插件），一个真相源喂多工具。工具侧目录被 `.gitignore` 忽略（注册是本机状态，真相源在 `.agents/toolkit/skills/<name>/SKILL.md`），不跑这条就**没有技能可用**。⚠️ 若将来启用根 `skills/` 出口（OpenClaw 等用根目录的工具），其忽略规则必须写成锚定根的 **`/skills/`**——写成 `skills/` 会连真相源 `.agents/toolkit/skills/` 一起忽略（见 pitfalls 二十三）。自检：`bash .agents/mechanism/scripts/ai-link-skills.sh --check` 或 `bash .agents/mechanism/guards/ai-guard-tools.sh`（T4）。
- **子代理注册（换机 clone 后执行一次）**：`bash .agents/mechanism/scripts/ai-sync-agents.sh` —— 把**审查官**（`.agents/toolkit/roles/<name>.md`，扁平真相源）**生成**为各工具的 subagent 定义（`.qoder/agents/*.md` ＝ md+YAML、`.codex/agents/*.toml` ＝ TOML；12 个审查官 × 2 家）。生成时做两件不止复制的事：**相对路径重写**（`../assets/x` → `.agents/rules/assets/x`）+ **只读强制**（Qoder `tools: Read, Grep, Glob` / Codex `sandbox_mode="read-only"`）⇒ 把 B7「审查只报告不直接修」从 prompt 自律升级为机制强制。格式因工具而异**故必须生成、不能软链**。自检：`bash .agents/mechanism/scripts/ai-sync-agents.sh --check`。
- **定时任务（换机 clone 后执行一次）**：`bash .agents/mechanism/scripts/ai-setup-launchd.sh` —— 装两个 LaunchAgent：每日 21:10 生产备份 + 每周一 09:00 每周审查。**不要用 crontab**（macOS TCC 会拦；2026-09-14 前就是这么静默失效 26 天的）。自检：`bash .agents/mechanism/scripts/ai-setup-launchd.sh --check` 或 `ai-guard-tools.sh`（T7）。清单见 `routine.md`。

## 相关

- 架构红线：`../../../ARCHITECTURE.md`（仓库根）
- **Git 规范**（分支 / 合并 / 推送 / 发布）：`git-workflow.md`
- worktree 并行线机制：`worktree-workflow.md`
- 文档索引：`docs/README.md`
