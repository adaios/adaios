---
title: 建设技能：新建/修改 API
description: 当需要新增/修改 API 端点时加载——从代码到契约同步的完整闭环（api-spec/status/测试/插件门控）
name: new-api
version: 1
created: 2026-08-20
updated: 2026-08-20
status: active
lines: 55
depends-on:
  - ../assets/conventions.md
  - ../checklists/review-backend.md
related:
  - ../../docs/architecture/api-spec.md
  - ../process/ship.md
tags: [skill, build, api]
---

# 新建/修改 API

你是 AdaiOS 的**建设流程执行者**——新增/修改端点时按本技能走完整闭环，防「功能跑通就当完成」（ship 完成标准五件套）。

## 触发条件

当用户要求新增、修改 API 端点（Controller/Service 层对外接口）时加载本技能。

## 执行步骤

1. **归属确认**：端点属于哪个 Controller/域（Kernel 还是 Domain OS）；确认不绕过 ContextEngine（红线 5：禁止手拼 prompt / 直接暴露数据库）
2. **写代码**：按分层依赖 `interfaces → application → domain/kernel ← infrastructure`；构造注入（C2）、业务异常（C4）、SLF4J 日志（C3）
3. **插件门控**：新端点是否只对启用插件的用户开放？是 → 加 PluginService 门控（B36，防「无插件用户访问 trading/project」坑）
4. **测试配套**：Controller 层 `@WebMvcTest` + MockBean；关键分支（解析/upsert/状态保留/写回）必须测；边界用例（跨天/歧义/脏数据）
5. **同步 api-spec**：`docs/architecture/api-spec.md` 升版 + 变更记录行（D48 教训：15 端点无版本行）
6. **同步 status.md**：`docs/reference/status.md` 端点数更新（唯一真相源）
7. **对齐验证**：`bash ai-engineering/guard-align.sh`（A1 端点对拍）+ `bash ai-engineering/guard-meta.sh` PASS
8. **功能登记**：新功能 → `docs/reference/feature-reference.md` 补章节（D26 教训：只同步 api-spec 不算完整闭环）

## 约束与规则

- **api-spec 是契约**：改 API 必须同步（项目底线，提交前确认）
- 不绕过 ContextEngine；不直接暴露数据库（Context Always）
- 数据丢失不可接受（P0）；删除不得出现在降级路径（已知坑）
- 插件门控不旁路（新端点不查 plugins = 已知坑复发信号）

## 输出要求

- 测试通过 + guard-meta PASS + guard-align 对齐
- 契约同步齐全：功能代码 / 测试 / api-spec（升版有变更记录）/ status.md（端点数）/ feature-reference
- 上述缺一不算完成（ship 五件套）

## 参考资料

- 后端检查清单：`../checklists/review-backend.md`
- 契约：`../../docs/architecture/api-spec.md`
- 规范：`../assets/conventions.md`；边界：`../assets/boundaries.md`；已知坑：`../assets/pitfalls.md`
