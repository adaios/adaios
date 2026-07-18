# Developer Context

> 开发者上下文，描述当前开发任务、分支状态、待办事项与技术债务。
> 每次开发会话开始时加载，结束时更新。

## 当前会话

- **日期**：{{date}}
- **分支**：{{branch}}
- **任务**：{{current_task}}
- **关联 Issue/PR**：{{references}}

## 开发环境

- **IDE**：{{ide}}
- **JDK**：Java 17（{{jdk_version}}）
- **Gradle**：{{gradle_version}}
- **MySQL**：{{mysql_version}}
- **OS**：{{operating_system}}

## 当前迭代

- **迭代目标**：{{sprint_goal}}
- **状态**：{{status}}
- **开始时间**：{{start_date}}
- **结束时间**：{{end_date}}

## 待办工作

### {{priority_high}}

- [ ] {{task_item}} — {{assignee}}（{{deadline}}）

### {{priority_medium}}

- [ ] {{task_item}} — {{assignee}}

## 技术债务

| 条目 | 严重程度 | 引入时间 | 计划处理 |
|------|---------|---------|---------|
| {{item}} | {{severity}} | {{date}} | {{planned_date}} |

## 进行中的变更

### 已修改文件

- {{file_path}} — {{change_description}}

### 待提交

- {{change_summary}}

## 已知问题

- {{issue_description}}

## 相关资源

- 领域文档：`domains/`
- 架构上下文：`architecture-context.md`
- 项目上下文：`project-context.md`
