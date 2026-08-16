---
title: 开发执行流程（develop）
description: 工作流开发段——直改代码的入口/出口/沉淀触发；量级匹配见 design.md
version: 1
created: 2026-08-15
updated: 2026-08-16
status: active
lines: 62
depends-on:
  - discuss.md
  - design.md
related:
  - ../assets/boundaries.md
  - ../assets/pitfalls.md
  - ../process/ship.md
tags: [ai, workflow, develop]
---

# 开发执行（develop）

> **定位**：工作流第三段——把方案变成代码。多数改动**直接改代码**（见 design.md 量级匹配），本文件定义入口/出口/沉淀触发，让开发段不再是"空段"。

## 入口（动工前必查）

**第 0 步：生成任务上下文清单（进攻侧核心，不用人提醒）**

```bash
bash ai-engineering/guard-context.sh                 # 全量上下文
bash ai-engineering/guard-context.sh <主题词>        # 按主题过滤（trading/memory/…）
```

自动汇总：C1 当前状态 / C2 未修项（REVIEW）/ C3 原则级边界 / C4 已知坑 / C5 规范 / C6 待办——**开工前读这份清单，而不是翻 6 个文件**。

1. **先读状态**：`../state/_index.md`（做到哪了）
2. **先查边界**：`../assets/boundaries.md`（原则级不可违反；功能级当前不做）
3. **先扫坑**：`../assets/pitfalls.md`（复发信号——症状 vs 当前改动）
4. **读规范**：`../assets/conventions.md`（代码/文档/协作规范）
5. 方案类改动：已过 `design.md`（RFC 或量级匹配）

## 执行

- 直接改代码（不强制先写文档——量级匹配见 design.md）
- 提交前确认根包 `com.adaiadai.core`；分层依赖不违反（kernel 不反向依赖 infrastructure）
- 改动涉及新功能 → 明确归属 Kernel / Domain OS

## 出口（收尾前必查）

1. **沉淀触发**（对照 `discuss.md` 过滤器，AI 主动发现）：
   - 本批出现取舍/为什么这么定 → 提请 ADR
   - 本批踩坑/发现根因 → 提请入 checklists + pitfalls
   - 本批产生新想法 → 提请入 ideas/ 或 RFC
2. 无新增决策/坑 → 显式标注「无新增沉淀」，防漏
3. 进入 `../process/ship.md`（收尾门禁）

## 与前后段衔接

```
discuss（讨论）→ design（方案）→ develop（本段）→ ship（收尾）→ review/audit（审查）
```

---
**追加方式**：新发现开发段遗漏 → 补入对应小节。
