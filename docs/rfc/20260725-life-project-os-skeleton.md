---
title: Life OS + Project OS 骨架
date: 2026-07-25
status: implemented
---

## 动机

Knowledge 骨架已就绪（`KnowledgeSource` 接口 + `TradingKnowledgeSource` 首个实现），但 Life OS 和 Project OS 仍是空壳（只有 `package-info.java`）。趁接口还热乎，把三个 Domain OS 知识源补齐。

更重要的是——**你现在就在做 Project**。每个 commit、每个 RFC、每个架构决策都是 Project OS 的原始数据。让 AdaiOS 帮你理解 AdaiOS 的开发进度，这是自举的第一步。

---

## Life OS 骨架

### 定位

Life OS 是个人生活管理域。与 Trading OS（金融交易）正交：交易回答"钱怎么管"，生活回答"日子怎么过"。

**MVP 不做**：
- 不建复杂的 `os/life-os/` 知识库（还没生活课程数据）
- 不做健康/情绪/习惯的深度分析
- 不做日程提醒

**MVP 只做**：让 AI 知道"Life OS 存在，它在管生活这件事"。

### 改动

| 文件 | 改动 |
|:-----|:-----|
| **新建** `domain/life/LifeContextContributor.java` | 实现 `ContextContributor` — `globalContext()` 返回 Life 域存在声明，`enrich("life")` 返回生活相关记录摘要 |
| **新建** `os/life-os/CLAUDE.md` | Life OS 工作焦点主页（极简版，目录预留） |
| **新建** `os/life-os/11-context/identity.md` | Life OS 身份声明——"这是什么系统，管什么" |
| **新建** `os/life-os/11-context/README.md` | 接口层说明 |
| **新建** `kernel/knowledge/LifeKnowledgeSource.java` | `KnowledgeSource` 实现，读 `os/life-os/11-context/identity.md` |

### 数据注入

| 方法 | 注入内容 | 时机 |
|:-----|:---------|:-----|
| `globalContext()` | `identity.md` (~1KB) | 每次请求 |
| `enrich("life")` | 近期日记/生活记录摘要 | life 场景 |

Life OS 没有像 Trading 的 87 课知识积累，所以 `enrich("life")` 暂时只注入 identity 声明。等数据积累起来后再扩。

---

## Project OS 骨架

### 定位

Project OS 是项目管理域——管理的就是 AdaiOS 开发本身。

**已有数据源（无需新建）：**

```
docs/rfc/          ← 9 个 RFC，覆盖架构/UI/知识闭环
docs/architecture/ ← 12 份架构文档
git log            ← 27 次提交（4月至今）
CLAUDE.md          ← 项目约定
```

### 改动

| 文件 | 改动 |
|:-----|:-----|
| **新建** `domain/project/ProjectContextContributor.java` | 实现 `ContextContributor` — `globalContext()` 注入最近 git 活动摘要，`enrich("project")` 注入 RFC 列表 + 架构文档索引 |
| **新建** `os/project-os/CLAUDE.md` | Project OS 工作焦点主页 |
| **新建** `os/project-os/11-context/identity.md` | Project OS 身份声明——"这是 AdaiOS 项目，模块化单体，前后端分离" |
| **新建** `os/project-os/11-context/README.md` | 接口层说明 |
| **新建** `kernel/knowledge/ProjectKnowledgeSource.java` | `KnowledgeSource` 实现，读 `os/project-os/11-context/` + `docs/` |

### 数据注入

| 方法 | 注入内容 | 体积 | 时机 |
|:-----|:---------|:----|:-----|
| `globalContext()` | `identity.md` + 最近 5 个 commit 摘要 | ~2KB | 每次请求 |
| `enrich("project")` | `identity.md` + docs 目录索引 + 最近 RFC 列表 | ~5KB | project 场景 |

**与 TradingKnowledgeSource 的关键区别：** Project OS 的知识不是预组织的（没有 11-context/rules.md），而是直接从 `docs/` 和 `git log` 动态生成的索引。更轻量，但模式一样。

---

## 两个 OS 的 KnowledgeSource 对比

| | Trading | Life | Project |
|:----|:--------|:-----|:--------|
| 知识来源 | 11-context/ 五份文件 | 11-context/identity.md | 11-context/ + docs/ + git |
| 知识成熟度 | 高（87 课精炼） | 无（空白） | 中（9 RFC + 12 文档） |
| globalContext() | ~2KB | ~1KB | ~2KB |
| enrich() | ~50KB | ~1KB | ~5KB |
| 未来扩展 | 规则检索 | 日记/健康/习惯 | 代码分析/里程碑 |

---

## 实施计划

1. 建 `os/life-os/` 和 `os/project-os/` 目录 + CLAUDE.md + 11-context/
2. 写 `LifeContextContributor` + `LifeKnowledgeSource`
3. 写 `ProjectContextContributor` + `ProjectKnowledgeSource`
4. 编译 + 测试（Knowledge 自动发现不需要额外配置）
5. 更新 API spec + CLAUDE.md

**工期：** 1-2 天
