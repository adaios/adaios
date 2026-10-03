---
title: assets/ 目录索引
description: assets/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 43
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# assets/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（16 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `adr/ADR-001.md` | 决策：ai-engineering/ 与代码工程平级，为驱动层而非附属文档（decides）；关联 RFC 20… | accepted |
| `adr/ADR-002.md` | 决策：数字/未修项/批次/蓝图各有一处真相源，其余指针引用（decides）；关联 RFC 20260815-d… | accepted |
| `adr/ADR-003.md` | 决策：Kernel 常驻 + Domain 受控插件（decides）；关联 RFC 20260814-doma… | accepted |
| `adr/ADR-004.md` | 决策：推送 per-user 开关（写读双侧门控）+ 交易日志归集三步流水线（识别→候选→审核落库，仅插件用户）… | accepted |
| `adr/ADR-005.md` | 决策：全库 CLAUDE.md→AGENTS.md 迁移（工具无关统一入口）+ 建设/收尾/审查技能封装为 SK… | accepted |
| `adr/ADR-006.md` | 决策：所有用户可见能力分三层——core（记录/问答/记忆/上下文/身份/存储，不可关）/ builtin（待办… | accepted |
| `ai-context-layer-spec.md` | AdaiOS 项目级 AI 上下文的中间层规范——定义 AI 资产**放在哪**（真相源）、**怎么被各工具发现… | active |
| `boundaries.md` | AdaiOS「不做什么」的集中声明——原则级（不可违反）与功能级（当前不做）；任何新功能先查边界再定方案 | active |
| `conventions.md` | 代码/文档/协作三组规范集中声明——从原根 CLAUDE.md（2026-08-19 删除）与 AI 工程层归集… | active |
| `naming-spec.md` | skill / subagent / 脚本的统一命名体系——六个域 + 封闭角色词表 + 硬规则；含本项目的历史… | active |
| `pitfalls.md` | 跨 checklists 归集的「踩过的坑」索引——症状/根因/修复/复发信号，按域分组；完整逐条在 check… | active |
| `projects/adai-admin.md` | 从管理员使用角度，具体到每个页面每个功能地描述 adai-admin——四区模块/每个 tab 能做什么/职责边… | active |
| `projects/adai-app.md` | 从用户使用角度，具体到每个小功能地描述 adai-app——两个主页/切换方式/每页模块功能/交互细节 | active |
| `projects/adai-core.md` | adai-core 项目资产卡——分层/模块/端点分布/鉴权边界；改 core 前先读本卡 | active |
| `projects/adai-web.md` | adai-web 项目资产卡——模块划分/职责边界/与 app 的关系；改 web 前先读本卡 | active |
| `skills-spec.md` | AdaiOS 版 SKILL.md 技能包标准——审查官与高频流程封装为跨工具技能包（name/descript… | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`guard-structure` S2 双向校验；新增文件后跑 `guard-structure.sh --fix`）
