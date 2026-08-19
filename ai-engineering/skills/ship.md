---
title: 建设技能：/ship 收尾闭环
description: 当开发批次完成需要收尾（/ship）时加载——五件套完成标准→契约同步→登记→门禁→规范提交
name: ship
version: 1
created: 2026-08-20
updated: 2026-08-20
status: active
lines: 51
depends-on:
  - ../process/ship.md
  - ../guard-meta.sh
related:
  - ../process/review.md
  - ../guard-align.sh
tags: [skill, build, ship]
---

# /ship 收尾闭环

你是 AdaiOS 的**收尾流程执行者**——开发批次提交前按本技能走完整闭环。权威流程见 `../process/ship.md`，本技能是加载即执行的检查版。

## 触发条件

当用户说「/ship」或批次开发完成需要收尾提交时加载本技能。

## 执行步骤

1. **五件套核对**（缺一不算完成）：功能代码 / 测试同批（关键分支必测）/ 契约同步（api-spec|feature-reference|freeze）/ 门禁主动跑 / 写码前对照过检查清单
2. **测试**：`cd services/adai-core && ./gradlew test`；更新 `docs/reference/status.md` 测试数（唯一真相源）
3. **契约同步**：新增/修改 API → `api-spec.md`（版本+变更记录）；data 格式变更 → `freeze`；新功能 → `feature-reference.md`
4. **文档登记**：对应目录 `_index.md` 文件清单；子项目 AGENTS.md 批次状态；`docs/reference/change-log.md` 顶部追加（日期 | 批次 | 摘要 | 测试数变化）
5. **决策沉淀**：RFC 验收标准逐条 PASS/FAIL 留痕；ADR 三问全中才建（否则 change-log 写「为什么这么定」）；踩坑入 checklists + pitfalls；`bash ai-engineering/guard-sediment.sh`
6. **门禁**：`guard-meta.sh --fix` + `guard-meta.sh` + `guard-align.sh` 全 PASS（禁止带 FAIL 提交；pre-commit hook 自动兜底）
7. **规范提交**：一提交一主题（feat:/fix:/docs:），不混合无关改动

## 约束与规则

- 反模式勿重演（2026-08-16 反思）：功能跑通=完成 / 测试后置 / gradle 绿=安全 / 不翻清单凭感觉写
- 部署是外向动作（B8）：deploy-gate 由人确认触发，本技能不自动部署

## 输出要求

- 规范提交信息 + 全部门禁 PASS + change-log 已登记
- 五件套缺一即回退对应步骤，不得带缺项收尾

## 参考资料

- 权威流程：`../process/ship.md`
- 元治理：`../guard-meta.sh`；对齐：`../guard-align.sh`
- 沉淀：`../guard-sediment.sh`；部署门禁：`../deploy-gate.sh`；审查：`../process/review.md`
