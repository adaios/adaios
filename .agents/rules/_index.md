---
title: rules/ 目录索引
description: .agents/rules/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-06
status: active
lines: 83
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# rules/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据：同一知识只在一处详述）

## 文件清单（44 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `assets/adr/ADR-001.md` | 决策：ai-engineering/ 与代码工程平级，为驱动层而非附属文档（decides）；关联 RFC 20… | accepted |
| `assets/adr/ADR-002.md` | 决策：数字/未修项/批次/蓝图各有一处真相源，其余指针引用（decides）；关联 RFC 20260815-d… | accepted |
| `assets/adr/ADR-003.md` | 决策：Kernel 常驻 + Domain 受控插件（decides）；关联 RFC 20260814-doma… | accepted |
| `assets/adr/ADR-004.md` | 决策：推送 per-user 开关（写读双侧门控）+ 交易日志归集三步流水线（识别→候选→审核落库，仅插件用户）… | accepted |
| `assets/adr/ADR-005.md` | 决策：全库 CLAUDE.md→AGENTS.md 迁移（工具无关统一入口）+ 建设/收尾/审查技能封装为 SK… | accepted |
| `assets/adr/ADR-006.md` | 决策：所有用户可见能力分三层——core（记录/问答/记忆/上下文/身份/存储，不可关）/ builtin（待办… | accepted |
| `assets/ai-calling-governance.md` | 全场景 AI 调用治理设计稿——13 处 LLM + 3 处视觉调用按体验敏感度分档，四维治理（模型路由/传输模… | draft |
| `assets/ai-context-engineering.md` | 项目级 AI 上下文工程的总览——分层模型（L0 入口 / L1 任务 / L2 约束 / L3 事实 + 横跨… | active |
| `assets/ai-context-layer-spec.md` | AdaiOS 项目级 AI 上下文的中间层规范——定义 AI 资产**放在哪**（真相源）、**怎么被各工具发现… | active |
| `assets/boundaries.md` | AdaiOS「不做什么」的集中声明——原则级（不可违反）与功能级（当前不做）；任何新功能先查边界再定方案 | active |
| `assets/conventions.md` | 代码/文档/协作三组规范集中声明——从原根 CLAUDE.md（2026-08-19 删除）与 AI 工程层归集… | active |
| `assets/naming-spec.md` | skill / subagent / 脚本的统一命名体系——六个域 + 封闭角色词表 + 硬规则；含本项目的历史… | active |
| `assets/pitfalls.md` | 跨 checklists 归集的「踩过的坑」索引——症状/根因/修复/复发信号，按域分组；完整逐条在 check… | active |
| `assets/projects/adai-admin.md` | 从管理员使用角度，具体到每个页面每个功能地描述 adai-admin——四区模块/每个 tab 能做什么/职责边… | active |
| `assets/projects/adai-app.md` | 从用户使用角度，具体到每个小功能地描述 adai-app——两个主页/切换方式/每页模块功能/交互细节 | active |
| `assets/projects/adai-core.md` | adai-core 项目资产卡——分层/模块/端点分布/鉴权边界；改 core 前先读本卡 | active |
| `assets/projects/adai-web.md` | adai-web 项目资产卡——模块划分/职责边界/与 app 的关系；改 web 前先读本卡 | active |
| `assets/skills-spec.md` | AdaiOS 版 SKILL.md 技能包标准——审查官与高频流程封装为跨工具技能包（name/descript… | active |
| `deployment/backend-deployment.md` | 后端部署（deploy.sh + 生产环境）——环境信息 / 配置项真值表 / systemd / 部署步骤 /… | active |
| `deployment/gongan-filing.md` | 公安联网备案（公安部，独立于工信部 ICP）的办理步骤、材料与通过后挂载指引；法定截止 2026-09-30 | active |
| `deployment/icp-filing.md` | 个人网站备案资料清单与填报指引（腾讯云接入），结合本项目域名/服务器/三端现状 | active |
| `deployment/ios-release.md` | 把 iOS 分发从「数据线侧载」换成 TestFlight 的完整方案——为什么绕开 Xcode 云签名、一次性… | active |
| `deployment/testflight-external-testing.md` | 把阿呆通过 TestFlight 发给非团队成员使用的完整流程——内测/外测怎么选、Beta 审核备注模板（含测… | active |
| `guides/branch-development.md` | AI 上下文资产全景（工具层 / 业务层 / 代码都是文件、都走 git；唯一不入库的是出口、state、快照三… | active |
| `guides/development.md` | 全局构建/测试/运行/部署命令与开发环境说明——原根 CLAUDE.md 迁移承接，工具无关 | active |
| `guides/git-workflow.md` | 分支怎么开、怎么合、怎么推、怎么发——为「单人但并行（同日多会话 + worktree）」这一形态定规矩。与 w… | active |
| `guides/qoder-parallel-workflow.md` | 把「一条分支 = 一次只能干一件事」改成「多条任务线并行、人只在卡住处出场」的实操手册——Qoder CN CL… | active |
| `guides/routine.md` | AdaiOS 的周期性人肉工作总清单——哪些系统已自动（只需看）、哪些必须你亲自做（生产日报/盘后导入/备份/审… | active |
| `guides/skills-usage.md` | 人看的技能使用说明——AdaiOS 能力体系是什么、4 个技能包 + 16 个角色各何时用、怎么触发、怎么维护 | active |
| `guides/worktree-workflow.md` | 在本项目用 git worktree 开并行线时的全部额外动作——worktree 是「空壳」（data/.en… | active |
| `method/README.md` | 方法论放回仓库——AI 工程切入点图谱：流程机制替人记得（流程约定 > 内容编写）；新项目 = 搭一条流水线 | active |
| `method/pipeline-sequence.mmd` | — | active |
| `method/pipeline.md` | AI 工程流水线每个切入点的职责/脚本/触发/拦截——从 adaios 实践提炼，可复制到新项目 | active |
| `method/pipeline.mmd` | — | active |
| `method/scaffold.md` | init-ai-engineering.sh 设计——新项目一条命令搭好 AI 工程流水线（hooks + gu… | active |
| `process/audit.md` | /audit 通用版——8 审查官独立并行走查 + 交叉印证（默认增量，全量仅里程碑级），沉淀到 REVIEW.… | active |
| `process/cadence.md` | 用户与 AI 之间的固定节奏——每日巡检 / 收工 / 发布 / 每周 / 待办，各自「上次到哪、这次做什么、做… | active |
| `process/review-driven.md` | 审核驱动主链（需求 → 设计 → 编码）——文档先行 + 双角色真对打、多轮交叉；定义谁派谁、几轮收敛、何时升级… | active |
| `process/review.md` | /review 的通用版——按改动范围派对应审查官，滚动更新 REVIEW.md | active |
| `process/ship.md` | 开发收尾闭环——测试 → 契约同步 → 文档登记 → 元治理校验（ai-guard-meta）→ 规范提交（**… | active |
| `workflow/design.md` | RFC 骨架标准化——问题→方案→决策点→验收标准；方案通过后决策入 ADR | active |
| `workflow/develop.md` | 工作流开发段——直改代码的入口/出口/沉淀触发；量级匹配见 design.md | active |
| `workflow/discuss.md` | 想法/讨论的登记与过滤器——什么值得沉淀（入 ideas/ 或 RFC 前身）、什么是一次性对话；沉淀过滤器由 … | active |
| `workflow/overview.md` | AdaiOS AI 协作工作流一张图——约束模型（文档约束代码、审核约束两者）+ 五段闭环（讨论→方案→开发→s… | active |

## 子目录

| 目录 | 职责 | 索引 | 契约 |
|:--|:--|:--:|:--:|
| `assets/` | — | [→](./assets/_index.md) | [→](./assets/_directory.md) |
| `guides/` | — | [→](./guides/_index.md) | [→](./guides/_directory.md) |
| `deployment/` | — | [→](./deployment/_index.md) | [→](./deployment/_directory.md) |
| `process/` | — | [→](./process/_index.md) | [→](./process/_directory.md) |
| `workflow/` | — | [→](./workflow/_index.md) | [→](./workflow/_directory.md) |
| `method/` | — | [→](./method/_index.md) | [→](./method/_directory.md) |

## 过期判断

- 新增子目录 → 必须同时有 `_index.md` 与 `_directory.md`
- **清单须与实际一致**（`ai-guard-structure` S2；跑 `--fix` 刷新）
