# Project OS 使用指南

## 一句话

**Project OS = 项目管理工具 + AI 项目顾问。**

你已经在用它的核心功能了——当你在输入框说"项目阿呆，现在项目什么情况"，AI 会自动识别为 project 场景，拉取当前项目状态来回答你。

---

## 一、你已经在使用的方式（输入框问"项目阿呆"）

### 基本用法

在底部输入框直接问：

```
项目阿呆，现在项目什么情况
项目阿呆，最近在做什么
项目阿呆，有哪些待办任务
项目阿呆，B 方向进展如何
```

### 背后发生了什么

```
你输入 → IntentRecognizer 识别为 QUESTION
       → Context Engine 检测到 "项目" 关键词
       → ProjectContextContributor 注入：
           ├ 最近 5 条 git commit
           ├ 文档索引（RFC + 架构文档清单）
           ├ RFC 状态列表（draft / completed / implemented）
           └ 任务统计（总计 / 待办 / 进行中 / 已完成）
       → ProjectKnowledgeSource 注入：
           └ os/project-os/11-context/ 下的知识文件
       → AI 综合上述上下文回答你
```

**你不需要手动操作任何东西**——说了"项目"相关的内容，上下文自动拼好。

### 效果示例

你问："项目阿呆，最近在做什么"

AI 看到的内容（你不直接看到，但被注入到上下文）：
```
**AdaiOS 最近开发活动：**
- 75b8e1e fix: RFC frontmatter 解析支持行内注释和额外字段
- cc52f38 B Phase 3: Project OS 自举增强
- 2b530c6 B Phase 2: RFC 状态跟踪

**RFC 状态：**
- [draft] AdaiOS 项目现状与三大方向规划
- [completed] 近期 Bug 复盘与改进建议
- [completed] Memory 升级

**任务状态：** 总计 10 | 待办 3 | 进行 0 | 完成 7
```

---

## 二、任务系统

### 任务文件在哪

`data/project/tasks/YYYY/MM.md` — 按月存储的 Markdown 文件。

例 `data/project/tasks/2026/07.md`：
```markdown
# 任务 - 2026-07

---
id: task_20260730_225440890
title: B Phase 2: RFC 状态跟踪
description: 实现 RFC 文档 YAML frontmatter 解析
status: DONE
priority: P0
tags: [后端, 前端, RFC]
rfcRef: 20260726-project-status-and-roadmap
createdAt: 2026-07-30
updatedAt: 2026-07-30
---
B Phase 2: RFC 状态跟踪
```

- **File First**：文件是唯一真相源，AI 可直接读取
- 每个 `---` 块是一个任务条目

### 任务状态

| 状态 | 含义 | 何时用 |
|:-----|:------|:--------|
| TODO | 待办 | 计划要做还没开始的 |
| DOING | 进行中 | 正在做的 |
| DONE | 已完成 | 做完的 |
| CANCELLED | 已取消 | 决定不做的 |

### 优先级

| 级别 | 含义 |
|:-----|:------|
| P0 | 阻塞性，必须优先处理 |
| P1 | 重要，但可排队 |
| P2 | 一般优先级 |
| P3 | 低优先级/未来考虑 |

### API 操作

| 操作 | 方法 | 路径 | 示例 |
|:-----|:-----|:------|:-----|
| 列表 | GET | `/api/v1/project/tasks?status=DOING&tag=后端` | 查看进行中的后端任务 |
| 统计 | GET | `/api/v1/project/tasks/stats` | 查看概览数字 |
| 创建 | POST | `/api/v1/project/tasks` | 新建任务（JSON body） |
| 更新 | PUT | `/api/v1/project/tasks/{id}` | 改状态/标题/优先级 |
| 删除 | DELETE | `/api/v1/project/tasks/{id}` | 删除任务 |

创建/更新请求体：
```json
{
  "title": "B Phase 4: 前端任务面板",
  "description": "Flutter 端实现任务看板",
  "status": "TODO",
  "priority": "P0",
  "tags": ["前端", "ProjectOS"],
  "rfcRef": "20260726-project-status-and-roadmap"
}
```

---

## 三、任务与"项目阿呆"的联动

当你创建/更新任务后，下一次问"项目阿呆"就会反映最新状态。

**工作流示例：**

```
你在做 B Phase 4

1. 你创建任务（已通过 B Phase 3 后自动创建）
2. 你开始编码...

   中途问：项目阿呆，B Phase 4 要做什么
   → AI 看到任务状态（进行中的任务 + RFC 文档）
   → 能回答具体要做哪些前端页面

3. 做完后更新任务状态为 DONE

   又问：项目阿呆，现在还有哪些待办
   → AI 看到 B Phase 4 标为完成，剩下 A Phase 1
```

---

## 四、前端页面

### 项目状态页（`/projects`）

通过启动器 → 阿呆系统 → 项目状态 进入。

显示：
- **任务统计**：总计 / 待办 / 进行中 / 已完成 / 已取消
- **RFC 状态列表**：所有 RFC 文档的标题 + 状态色标识
- **Kernel 组件状态**：identity / record / timeline 等
- **Domain OS 状态**：trading / life / project
- **最近 git commit**

### 任务管理页（`/tasks`）（预留，B Phase 4）

后续会上任务创建/编辑/状态推进操作界面。

---

## 五、实用场景

### 场景 1：每日站会

问："项目阿呆，今天有什么要做的"

### 场景 2：进度跟进

问："项目阿呆，B 方向还差什么"

### 场景 3：回顾总结

问："项目阿呆，最近一周完成了什么"

### 场景 4：决策参考

问："项目阿呆，现在做 A 方向还是继续做 B"

### 场景 5：代码提交后

问："项目阿呆，我刚提交的代码更新了什么"

---

## 六、当前任务清单（2026-07-30）

| 任务 | 状态 | 优先级 |
|:-----|:-----|:------:|
| B Phase 2: RFC 状态跟踪 | ✅ DONE | P0 |
| B Phase 3: Project OS 自举增强 | ✅ DONE | P0 |
| Feed 分页 + 删除流程修复 | ✅ DONE | P0 |
| AI 回复截断 + emoji 修复 | ✅ DONE | P1 |
| 删除清理 Memory | ✅ DONE | P1 |
| RFC frontmatter 解析修复 | ✅ DONE | P1 |
| AI Native 设计体系文档 | ✅ DONE | P2 |
| **B Phase 4: 前端任务面板** | 📋 TODO | P0 |
| **A Phase 1: 行情数据接入** | 📋 TODO | P1 |
| **文档对齐：api-spec.md** | 📋 TODO | P2 |

---

## 七、常见问题

**Q: 为什么问"项目阿呆"会自动触发项目上下文？**

A: Context Engine 的 IntentRecognizer 检测到你输入中有"项目"相关意图，就调用 ProjectContextContributor 注入项目状态。这是自动的，无需手动指定场景。

**Q: 如果我问"阿呆，最近项目代码有什么改动"，也会触发吗？**

A: 会的。包含"项目"关键词的句子会被路由到 project 场景。

**Q: 任务数据丢了怎么办？**

A: 任务文件在 `data/project/tasks/` 下，跟随 Git 管理。只要提交过就能恢复。

**Q: 能不能不用 API，直接改文件？**

A: 可以。修改 `data/project/tasks/YYYY/MM.md` 后重启后端即可（或者不改文件，AI 下次读取也是新的）。但建议通过 API 操作以保证格式正确。

**Q: 创建任务时中文标题乱码？**

A: 直接用 Flutter 前端创建（后续 B Phase 4），或通过 Python `urllib.request` 发送 JSON，确保 `Content-Type: application/json; charset=utf-8`。
