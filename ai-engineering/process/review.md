---
title: 增量深审流程
description: /review 的通用版——按改动范围派对应审查官，滚动更新 REVIEW.md
version: 2
created: 2026-08-15
updated: 2026-08-23
status: active
lines: 142
depends-on:
  - ../frontmatter-spec.md
related:
  - audit.md
  - ../../docs/review/REVIEW.md
  - ../roles/adversarial-reviewer.md
tags: [ai, process]
---

# 增量深审流程（review）

> 对应原 `/review` skill，工具无关化。轻量（默认）/ 深度（--deep）/ 全量（--full）三档。

## 1. 定基线

```bash
git log --oneline -20          # 找上次审查 commit 作为基线（REVIEW.md 头部 baseline）
```

- 默认：`git diff <基线>..HEAD`（增量）
- --full：全仓库，不设 diff 边界

## 2. 守护检查（每次必跑）

```bash
bash docs/review/guard.sh        # G1-G7 代码级守护
bash ai-engineering/guard-meta.sh       # 元治理：frontmatter 图谱/lines/孤儿（D30/D34）
```

- G1-G7 有 HIT 即记录为问题（复发信号）。清单见 `ai-engineering/checklists/guard.md`。
- guard-meta 有 FAIL 时先 `bash ai-engineering/guard-meta.sh --fix` 回写 lines，再人工处理断链/孤儿（D34 回写门禁）。

## 3. 按模式派官

- **light**：不派官，守护 + `git diff --stat` 快扫，列风险点
- **deep**：按 diff 触及目录派对应审查官（见下表），每个官并行独立审查；**默认 + 1 名对抗官**（adversarial-reviewer，找茬视角）
- **full**：8 官全派 + 1 对抗官

| 改动位置 | 派官 |
|:---------|:-----|
| services/adai-core/** | backend-reviewer |
| apps/**（Flutter）| frontend-reviewer（+ ui-reviewer 若涉视觉、+ ux-reviewer 若涉流程）|
| docs/**、*.md | docs-reviewer |
| os/**、data/** | knowledge-reviewer |
| ai/**、ai-engineering/** | context-reviewer |
| 跨多目录 | 多官并行 |

> **模型分层（成本纪律 2026-08-18）**：deep 派官默认 Flash；仅深审场景（product-arch 全局视角、大重构）可切 Pro——差价 3 倍（见 `checklists/cost.md` S6）。`--full` 已默认不跑，见 `audit.md` 成本纪律。

### 对抗官（adversarial-reviewer，2026-08-23 确立）

- **角色**：不评估「对不对」，只找「哪里会炸 / 用户哪里会骂 / 边界哪里漏」——客观官的补充，不是替代
- **派法**：deep 默认含 1 名（与客观官并行、互相不可见）；light 不派；full 必派
- **价值**：客观官从「符合规范」视角查，对抗官从「一定有问题」视角查——补上审查官对**体感/隐患**的盲区（行业 Adversarial Review 模式）
- **成本**：1 官 Flash，与其他官并行，几乎不增加耗时

### 上下文隔离（2026-08-23 确立，操作规范）

> 背景：行业实证「共享主会话上下文的并行官 = 同源共振，⭐ 信号虚高」（Anthropic issue #62524；「别让同一个大脑既当裁判又当运动员」）。隔离后「多官命中同一问题」才恢复独立证据价值。

**派官时对每个官执行三步隔离：**

1. **独立进程**：每个官 = 一个独立子代理（subagent），**不带主会话历史**——prompt 自包含，只含：①改动范围（`git diff <基线>..HEAD` 或指定文件）②该官专属检查清单路径 ③输出格式要求。主会话不把对话上下文灌给官。
2. **材料按角色裁剪**：每个官只喂**自己域内的材料**（见下表）——**不喂全库、不喂其他官的发现**。材料有差异，交叉印证才有信号。
3. **官间不互通**：各官并行、互不可见；主会话只做**汇总层**去重合并，不回灌原始 diff。汇总时「多官命中」才算 ⭐（同源材料下的命中降级为提示）。

**材料裁剪对照表：**

| 官 | 只喂（自己域内）| 不喂 |
|:--|:--|:--|
| backend-reviewer | diff 中 `services/**` 部分 + api-spec 相关节 | 前端 diff、其他官发现、主会话讨论历史 |
| frontend-reviewer | diff 中 `apps/**` 部分 + DTO 契约节 | 同上 |
| docs-reviewer | diff 中 `docs/**`、`*.md` 部分 + 对应 _index | 同上 |
| knowledge-reviewer | diff 中 `os/**`、`data/**` 部分 + data-format-freeze | 同上 |
| product-arch | 全部 diff + Roadmap + ARCHITECTURE.md | 同上 |
| adversarial-reviewer | 全部 diff + `assets/pitfalls.md` + `checklists/review-ux.md` | 同上 |

**官 prompt 模板（可直接复制，替换占位符）：**

```text
你是 {官名}（{一句话角色}）。审查范围：git diff {基线}..HEAD 中 {对应目录}。
契约参考：{该官域内契约文件路径 + 相关节}。
检查清单：{该官专属 checklists 路径}（逐条执行）。
输出：P0 → 战略 → P1 → P2，每条 ≤3 行（位置 / 一句问题 / 一句建议）。
约束：只读上面给的材料；不读其他文件；不推测其他审查官的发现。
```

```text
你是 adversarial-reviewer（对抗找茬官）。审查范围：git diff {基线}..HEAD（全部）。
已知坑：ai-engineering/assets/pitfalls.md（复发信号，命中即标注）。
交互检查：ai-engineering/checklists/review-ux.md。
输出：P0 → P1 → P2，每条 ≤3 行（位置 / 一句攻击视角问题 / 后果或攻击路径），
     只报最危险的前几条，宁缺毋滥。
约束：假设改动一定有问题；不重复客观官会报的常规问题；只读给的材料。
```

**汇总合并规则（第 4 步执行）：**

- 收集各官报告 → 去重：同一问题多官命中 → ⭐ 加权（材料已隔离，视为独立证据）
- 不回灌：不把任何官的报告喂给另一官；汇总时也不回灌原始 diff
- 同源降级：若发现某官读了隔离材料之外的东西，其命中降级为提示
- 输出控字规则不变（第 4 步：每条 ≤3 行）

> 效果：①⭐ 恢复信号价值 ②每官上下文更小，成本更低（cost.md S9：长会话串行 vs 并行子代理）③官的报告是「自己的发现」而非「对同一份材料的复述」。

### 性能专项（/perf，2026-08-22）

加载慢类问题**不派 8 官、不跑 guard、不滚动 REVIEW.md**——症状型快查：

- 触发：`/perf <端名>`（adai-app / adai-web / adai-admin）
- 执行：按 `checklists/review-perf.md` 阶段 A→F 逐条核对，默认 Flash
- 时间盒 15 分钟，超时即停；输出 ≤10 条（位置 / 一句问题 / 一句建议），标注「卡首屏 / 卡内容 / 卡交互」
- 疑似后端慢（快照计算/K 线扫描）→ 一句转派 backend-reviewer，不深挖
- 沉淀：只追加一行到 review-perf.md 对应阶段，不进 REVIEW.md 滚动区

## 4. 汇总排序

收集各官结果 → 去重合并 → P0（数据丢失）→ 战略 → P1 → P2/P3。守护命中并入。

- **输出控字（成本纪律 2026-08-18）**：每条问题 ≤3 行（位置/一句问题/一句建议），仅 P0/战略级展开，P1-P3 列表直出（输出是 9-27 元/M 最贵通道）。

## 5. 滚动更新 REVIEW.md

- 未修项逐条核对（本次已修 → 移已修复区；未修 → 保留）
- 新问题追加对应优先级区；更新头部（日期/基线/模式）
- 已修复区只留最近 10 条

## 6. 沉淀检查点

各官报告附「新增检查点建议」→ 补进对应 `checklists/`。

## 7. 记录成本

REVIEW.md 末尾成本表追加一行（日期/模式/派官/agent 数/耗时/新增/修复）。
