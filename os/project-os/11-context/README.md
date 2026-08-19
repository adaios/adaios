# 11-context / 项目管理系统上下文接口层

## 定位

`11-context/` 是 Project OS 对 AI / 外部系统暴露的认知接口层。

**特殊之处：** Project OS 的知识来源在本目录之外，还自动融合项目级别数据：
- `docs/rfc/` — 设计决策
- `docs/architecture/` — 系统文档
- `git log` — 开发活动
- `AGENTS.md` — 项目约定

## 文件清单

| 文件 | 内容 |
|:----|:-----|
| `identity.md` | 项目身份信息（名称、架构、结构） |

## 维护原则

1. `identity.md` 随项目结构变化手动更新
2. 自动数据源（rfc/architecture/git）由 `ProjectKnowledgeSource` 实时读取
