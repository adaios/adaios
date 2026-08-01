# Project OS — 个人项目管理领域

## 职责

Project OS 负责个人项目的全生命周期管理，涵盖想法捕捉、项目规划、任务分解、执行跟踪、复盘总结。作为 AdaiOS 的项目管理中心，帮助用户在 AI 协助下将想法转化为可执行的计划，并持续追踪进展。

## 输入

| 来源 | 数据 | 说明 |
|------|------|------|
| adai-core | 项目指令 | 创建/查询/更新项目及任务 |
| adai-core | Context Engine | 当前活跃项目上下文 |
| adai-core | Memory | 历史项目模式和经验 |
| 用户 | 想法捕捉 | 随时记录的想法、灵感 |

## 输出

| 目标 | 数据 | 说明 |
|------|------|------|
| adai-core | 项目状态 | 进展、完成度、里程碑 |
| adai-core | 任务通知 | 截止提醒、依赖阻塞 |
| adai-core | 项目洞察 | 进度分析、模式发现 |
| adai-data | 项目记录 | 项目文件、任务、复盘 |

## 与 adai-core 的关系

- **依赖方向**：`adai-core` → `project-os`（通过 domain 接口调用）
- **协作方式**：project-os 实现 `com.adaiadai.core.domain.project` 包中定义的接口，跨域协作由 `application` 层编排
- **上下文组合**：Context Engine 读取 Identity（用户工作方式偏好）+ Timeline（时间投入）+ Memory（历史项目经验）组合为 Project Context Package 提供给 AI
- **存储**：采用 File First，项目文件以 Markdown 形式存储在 `data/projects/` 目录

## 边界范围

- **包含**：想法管理、项目规划、任务分解、里程碑、依赖管理、进度追踪、复盘总结
- **不包含**：研究分析内容（归 Trading OS 研究子模块）、生活日程（归 Life OS）
