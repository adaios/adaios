# AdaiOS

Personal AI Operating System — 个人 AI 操作系统

AdaiOS 不是传统 CRUD 应用。它是围绕人、记忆、上下文、知识、决策、AI 协作构建的个人数字基础设施。

## 目录结构

| 目录 | 用途 |
|------|------|
| `services/adai-core` | 核心运行时（★ 唯一已实现的模块） |
| `domains` | Domain OS 领域定义文档 |
| `apps` | 应用入口（预留） |
| `data` | 个人知识资产（File First） |
| `ai` | AI 上下文模板 |
| `infra` | 基础设施配置 |
| `docs` | 项目文档 |
| `scripts` | 开发/运维脚本（预留） |

## 架构

- **Monorepo** — 统一代码仓库
- **Modular Monolith** — 不提前微服务化
- **Java 17 / Spring Boot 3** — 主要技术栈
- **Kernel + Domain OS** — 系统域与业务域分离
- **File First, Database Second, Context Always** — 最高设计原则

详见 [CLAUDE.md](CLAUDE.md) 和 [VISION.md](docs/VISION.md)。

## 快速开始

```bash
# 1. 启动 MySQL
docker compose -f infra/docker-compose.yml up -d

# 2. 编译
./gradlew :services:adai-core:build -x test

# 3. 运行测试
./gradlew :services:adai-core:test

# 4. 启动应用
./gradlew :services:adai-core:bootRun
```

## 核心设计原则

```
Human → Record/File → Kernel → Domain OS → AI Model
```

- **File First** — 所有长期知识以文件形式存在，Git 可管理，AI 可直接读取
- **Database Second** — 文件是 Source of Truth，数据库为查询/搜索性能而存在
- **Context Always** — 所有模块通过 Context Engine 输出统一的上下文能力
