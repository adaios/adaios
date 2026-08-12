# 记录 ↔ 任务模块关联（想法，2026-08-12 登记）

> 来源：阿呆 2026-08-12 补充想法——「adai用户提交的关于项目类型的记录是否可以和我的任务模块关联起来呢」。
> 状态：想法登记（未立项）。成熟后升级 `docs/rfc/`。

## 想法原话（转述）

阿呆提交的**关于项目类型的记录**（domain=project / 提到项目的 bug、需求反馈），希望与**任务模块**（Project OS 任务看板）关联起来。

## 触发场景

2026-08-12 凌晨阿呆在 App 输入 3 条项目 bug 反馈（#14 问候语 / #15 聊天简化 / #16 输入框误触）+ 若干需求。这些记录进了 Feed / 记忆 / 搜索，但**不会自动出现在任务看板**——要变成任务需人工誊抄。关联后：记录即任务源头，形成「记录 → 任务 → 解决 → 反哺」闭环。

## 现状（代码事实）

- **任务**：`data/project/tasks/YYYY/MM.md`，`ProjectFileRepository`（`TASKS_DIR = "project/tasks"`）+ `ProjectTaskAppService` 编排
- **记录**：`data/{userId}/records/YYYY/MM/rec_*.md`，含 `domain: project`、`tags`、`intent: log/question`
- **无任何关联**：`ProjectKnowledgeSource` 只读 `os/project-os/11-context` 领域知识，不消费任务也不消费记录；两条线独立
- 记录格式已冻结（`data-format-freeze.md`），加关联字段需走变更流程

## 候选方向

| 方向 | 说明 | 成本 | 备注 |
|:-----|:-----|:----:|:-----|
| **A. 记录→任务自动生成** | domain=project（或规则匹配）的记录自动生成 Task（来源标 recordId），进任务看板 | 中 | 最贴合"关联起来"，需防重复生成（幂等）|
| **B. 任务详情展示关联记录** | 任务可引用相关记录（记录↔任务双向），任务卡展开显示来源记录 | 中 | 不生成任务，只建立引用；record 加关联字段需 freeze 变更 |
| **C. Context 注入** | Context Engine 组装 project 场景时把相关记录带入，AI 问答能感知记录→任务上下文 | 低 | 无数据模型改动，先让"记录"进 Project 上下文 |
| **D. 分类规则** | 先定义"什么算项目类型记录"（domain=project？tags 含项目？），再决定关联动作 | 前置 | A/B 的前提，需先确认判定规则 |

## 归属与约束

- **跨域**（record=Kernel，task=Project OS）→ 编排放 `application` 层（符合「Domain 之间不允许直接依赖，跨域通过 application 编排」）
- 任务写入走 `ProjectFileRepository` 现有 API，不绕过
- 幂等：同 recordId 不重复生成任务（可参考 `RecordRetryService` 判定模式）
- 与 **R1（AI 交互日志）** 互补：R1 管"AI 怎么被调"，本想法管"用户反馈怎么变成待办"，都是产品自闭环的一环

## 关联

- `ProjectFileRepository`（`infrastructure/storage/`）
- `ProjectTaskAppService`（`application/`）
- `domain` 判定字段：ContentRecord.domain / tags / intent
- 相关：[[project-review-vs-personal-os]]
