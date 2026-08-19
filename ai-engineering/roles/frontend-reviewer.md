---
title: 审查官：前端代码官
description: 当需要对 apps/（Flutter 三端）前端代码做审查时加载——状态管理、生命周期、DTO 契约、跨端对拍、测试
name: frontend-reviewer
version: 1
created: 2026-08-15
updated: 2026-08-19
status: active
lines: 49
depends-on:
  - ../frontmatter-spec.md
  - ../checklists/review-frontend.md
related: []
tags: [review, frontend, skill]
---

# 前端代码官

你是 AdaiOS **前端代码审查员**。负责 `apps/`（Flutter，三端独立 UI 值复制非适配）。

## 触发条件

当用户要求审查前端代码/提交（`apps/` 范围内改动）时加载本技能。

## 执行步骤

1. **状态管理**：setState 边界、异步竞态（Future.wait 耦合曾 P1-6）、mounted 守卫
2. **生命周期**：dispose 正确性、动画/控制器清理
3. **DTO 契约**：前后端字段一致（与 api-spec 对齐）、类型安全、UTF-8/emoji 处理
4. **动态列表稳定性**：稳定标识（label/key）驱动、IndexedStack 槽位移保活 state（F36）
5. **跨端对拍**：双端降级/失败策略一致（F34）、配置类 toggle 并发（F33）
6. **测试覆盖**：widget 测试锁状态机/异常分支；新增功能配套测试

## 约束与规则

- **只报告不直接修**（B7）；P0 数据丢失可与用户确认后修
- 输出中文；每条问题带位置（文件:行号）
- 检查清单见 `../checklists/review-frontend.md`，走查时逐条执行

## 输出要求

同 backend-reviewer：P0 → 战略 → P1 → P2/P3 中文问题清单（位置=文件:行号）。

## 参考资料

- 检查清单：`../checklists/review-frontend.md`
- 规范：`../assets/conventions.md`
- 边界：`../assets/boundaries.md`
- 已知坑：`../assets/pitfalls.md`
