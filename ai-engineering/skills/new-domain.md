---
title: 建设技能：新增 Domain
description: 当需要新增 Domain OS / 重大架构能力时加载——RFC+六维 → 插件模型 → 数据流设计 → 分层落地 → 验收闭环
name: new-domain
version: 1
created: 2026-08-20
updated: 2026-08-20
status: active
lines: 54
depends-on:
  - ../assets/boundaries.md
  - ../workflow/design.md
related:
  - ../../docs/architecture/product-architecture.md
  - ../../docs/rfc/20260814-domain-plugin-model.md
tags: [skill, build, domain]
---

# 新增 Domain

你是 AdaiOS 的**建设流程执行者**——新增 Domain OS 或重大架构能力时的完整路径，防「直接写代码、后面大返工」。

## 触发条件

当用户要求新增 Domain OS（trading/life/project 同类）、重大架构能力、或新能力找不到归属时加载本技能。

## 执行步骤

1. **归属判断**：先回答「属于 Kernel 还是 Domain OS」——找不到归属先讨论架构（不写码）；Kernel Domain 是共享系统域，Domain OS 是挂载其上的业务域
2. **RFC + 六维**：写 `docs/rfc/`（骨架 7 段 + 六维检查，见 `../workflow/design.md`）；重大方向走 RFC 给人确认，approved 后才动码（W6 讨论与实施分离）
3. **插件模型**：新 Domain = 受控插件？注册 PluginRegistry + Account.plugins 门控 + ContextEngine 全量门控（RFC 20260814 第二步）
4. **数据流设计**（红线 4）：Record 文件格式 → Timeline 投影 → Context 组合 → Memory 沉淀，先设计再写码；File First（os/ 知识资产 + data/ 路径）
5. **分层落地**：domain/ 实现 + application 编排 + infrastructure 适配；Domain 间禁止直接依赖（B6，跨域经 application）
6. **Context 暴露**：通过 ContextContributor 插件机制暴露能力（Context Always），不直接暴露数据库
7. **验收闭环**：测试配套 + guard-meta PASS + feature-reference 登记 + 文档索引 + 插件显隐三端对拍

## 约束与规则

- B4 不提前微服务化（Modular Monolith 默认，四条满足才拆）
- B6 Domain 间不直接依赖；B3 隐私不进 git（新增 data/ 子目录 gitignore 验证）
- 讨论与实施分离（W6）：RFC 未确认不写码
- ARCHITECTURE.md 红线 1-9 全清单生效

## 输出要求

- RFC approved（含六维逐条回答）+ 插件门控全通道 + 数据流设计先于代码
- 测试 + guard-meta PASS + feature-reference/文档索引登记

## 参考资料

- 六维模板与 RFC 骨架：`../workflow/design.md`
- 插件模型：`../../docs/rfc/20260814-domain-plugin-model.md`
- 产品架构：`../../docs/architecture/product-architecture.md`
- 边界：`../assets/boundaries.md`；红线：`../../ARCHITECTURE.md`
