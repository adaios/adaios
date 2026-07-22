# AI Context Structure

> 项目 AI 上下文目录结构规范 v1.0

---

# 1. 目标

AI Context 的目标：

不是建立一个文档仓库。

而是建立一个：

> 支撑 AI 理解项目、执行任务、遵守约束、持续维护的上下文系统。


核心流程：

```
入口
 ↓
工作流
 ↓
规则 / 技能 / 知识
 ↓
执行任务
 ↓
审查
 ↓
记录状态
```

AI 不需要一次读取所有内容。

AI 应该：

1. 从入口理解项目
2. 根据任务进入 Workflow
3. 按需读取 Knowledge
4. 遵守 Rules
5. 使用 Skills
6. 参考 Decisions
7. 更新 Memory

---

# 2. .ai 目录结构

```
.ai/

├── README.md                     # AI入口
│
├── 01-workflows/                 # 工作流程
│
├── 02-rules/                     # 规则与约束
│
├── 03-skills/                    # 可复用能力
│
├── 04-knowledge/                 # 项目知识
│
├── 05-decisions/                 # 设计决策
│
├── 06-memory/                    # 项目状态
│
├── 07-roles/                     # AI角色
│
├── 08-reviewers/                 # AI审查
│
├── 09-prompts/                   # 任务提示词
│
├── 10-templates/                 # 文档模板
│
└── 11-work/                      # AI工作空间
    │
    ├── tasks/
    ├── reviews/
    ├── releases/
    └── archive/
```

---

# 3. 核心目录说明

---

# README.md

## 定位

AI Context 总入口。


## 职责

告诉 AI：

- 项目是什么
- 当前目标是什么
- 如何开始工作
- Workflow 在哪里
- 核心规则在哪里


## 不包含

不要放：

- 详细业务知识
- 技术细节
- 长篇设计说明


README 负责：

```
导航
```

不是：

```
存储知识
```

---

# 01-workflows

## 定位

AI 工作流程。

回答：

> 收到任务后，AI 应该如何行动？


例如：

```
01-workflows/

├── feature-development.md

├── bug-fix.md

├── refactor.md

└── release.md
```


Workflow 是 AI 的：

```
执行路线
```

内容：

- 任务触发条件
- 执行步骤
- 需要读取的 Context
- 输出结果

---

# 02-rules

## 定位

项目规则和边界。

回答：

> AI 什么可以做，什么不能做？


例如：

```
02-rules/

├── architecture.md

├── coding.md

├── database.md

└── security.md
```


包含：

- 技术约束
- 架构边界
- 编码规范
- 禁止事项


Rule 是：

```
护栏
```

---

# 03-skills

## 定位

可复用执行能力。

回答：

> 某件事情具体如何完成？


例如：

```
03-skills/

├── create-api.md

├── create-domain.md

└── database-migration.md
```


Skill 类似：

```
函数
```

Workflow 可以调用 Skill。

---

# 04-knowledge

## 定位

项目知识。

回答：

> AI 判断时需要参考什么？


例如：

```
04-knowledge/

├── project-overview.md

├── domain-model.md

├── business-rule.md

└── technical-stack.md
```


Knowledge 提供：

- 背景
- 概念
- 业务信息
- 技术说明


注意：

Knowledge 不是入口。

应该被 Workflow / Skill 按需引用。

---

# 05-decisions

## 定位

历史设计决策。

回答：

> 为什么这样设计？


例如：

```
05-decisions/

├── architecture-choice.md

├── technology-choice.md

└── tradeoff.md
```


记录：

- 背景
- 方案
- 原因
- 结果


Decision ≠ Rule

区别：

Rule：

```
必须这样做
```

Decision：

```
当初为什么这样做
```

---

# 06-memory

## 定位

项目当前状态。


回答：

> 项目现在是什么状态？


例如：

```
06-memory/

├── current-state.md

├── changelog.md

└── known-issues.md
```


记录：

- 当前进度
- 已完成事项
- 已知问题
- 下一阶段计划

---

# 07-roles

## 定位

AI 角色定义。


回答：

> AI 应该站在哪个角度思考？


例如：

```
07-roles/

├── developer.md

├── architect.md

├── reviewer.md
```


Role 定义：

- 关注什么
- 忽略什么
- 判断标准是什么

---

# 08-reviewers

## 定位

AI 审查能力。


回答：

> 如何检查结果是否正确？


例如：

```
08-reviewers/

├── context-reviewer.md

├── architecture-reviewer.md

└── code-reviewer.md
```


Reviewer 用于：

- 检查 Context
- 检查设计
- 检查代码

---

# 09-prompts

## 定位

任务调用模板。


回答：

> 如何向 AI 发起任务？


例如：

```
09-prompts/

├── analyze.md

├── generate-document.md

└── review.md
```


Prompt：

负责启动一次 AI 行为。

---

# 10-templates

## 定位

输出格式模板。


回答：

> 最终结果应该长什么样？


例如：

```
10-templates/

├── design-doc.md

├── decision-record.md

└── research-report.md
```


Template：

规定结构。

---

# 11-work

## 定位

AI 工作过程空间。


回答：

> 当前任务在哪里进行？


结构：

```
11-work/

├── tasks/
├── reviews/
├── releases/
└── archive/
```


---

## tasks

当前任务。

例如：

```
tasks/

└── add-user-module/

    ├── requirement.md
    ├── analysis.md
    └── result.md
```


---

## reviews

审查记录。

例如：

```
reviews/

├── context-review.md

└── architecture-review.md
```


---

## releases

发布过程。

例如：

```
releases/

└── v1.0/

    ├── changelog.md
    └── checklist.md
```


---

## archive

历史归档。

---

# 4. 文件关系

整体关系：

```

                         README.md
                             |
                             |
                     判断任务类型
                             |
                             ↓

                     01-workflows
                             |
          ┌──────────────────┼──────────────────┐
          ↓                  ↓                  ↓

      02-rules          03-skills        04-knowledge


          ↑                  ↑                  ↑

          └────────── 05-decisions ─────────────┘


                             ↓

                         06-memory


================================================


07-roles

定义 AI 思考方式


08-reviewers

检查执行结果


09-prompts

发起 AI 任务


10-templates

规范输出格式


11-work

保存 AI 工作过程

```

---

# 5. AI 工作路径示例

任务：

```
新增交易模块
```


AI：

```
README.md

↓

01-workflows/feature-development.md

↓

读取：

02-rules/architecture.md

↓

读取：

04-knowledge/domain-model.md

↓

调用：

03-skills/create-domain.md

↓

参考：

05-decisions/

↓

执行任务

↓

08-reviewers/context-reviewer.md

↓

更新：

06-memory/current-state.md

↓

保存：

11-work/tasks/

```

---

# 6. 最终原则

每个 Context 文件都应该回答：

1. 为什么存在？
2. 什么时候读取？
3. 读取后改变什么行为？
4. 与哪些文件关联？


AI Context 的目标：

不是让 AI 知道所有东西。

而是让 AI：

```
知道从哪里开始

知道下一步去哪

知道哪些不能做

知道什么时候需要参考知识

知道什么时候结束
```