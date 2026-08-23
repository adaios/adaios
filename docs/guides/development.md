---
title: 开发指南（Development Guide）
description: 全局构建/测试/运行/部署命令与开发环境说明——原根 CLAUDE.md 迁移承接，工具无关
version: 1
created: 2026-08-19
updated: 2026-08-19
status: active
lines: 46
depends-on: []
related:
  - ../VISION.md
  - ../README.md
tags: [guide, dev]
---

# 开发指南

> 承接原根 `CLAUDE.md` 的构建命令（2026-08-19 根 CLAUDE.md 删除、子项目统一改名为 AGENTS.md）。子项目内部命令以各子项目 `AGENTS.md` 为准（就近原则）。

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
cd apps/adai-app && sh scripts/serve_web.sh        # Web（本地补丁 + Python 服务器）
cd apps/adai-app && flutter run -d android         # Android
```

## 环境与工程

- **零数据库启动**：MVP 阶段不需要 MySQL，所有数据通过 File First 存储到 `data/`。
- **git hooks（换机 clone 后执行一次）**：`sh scripts/setup-hooks.sh` —— 启用 pre-commit 自动检查（文档对齐 + frontmatter 结构 + G1-G7 防复发）。

## 相关

- 架构红线：`../../ARCHITECTURE.md`（仓库根）
- 文档索引：`docs/README.md`
