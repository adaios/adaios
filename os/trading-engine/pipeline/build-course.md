# Trading Engine — 构建流程（课程 → 知识）

> **定位**：课程处理流水线——把交易课程（87 课）变成规则/策略/教训知识。**完整流程权威在 `../AGENTS.md`（438 行，Step 1-5 + 批量收敛 + 人工审核 + 交付）**，本文件是导航 + 状态摘要，不重复定义。

## 流程概览（权威：AGENTS.md）

```
第一层：单课处理（Step 1-5，每课串行）
  01-raw → 02-cleaned → 03-glossary(术语+14分类融合) → 04-rules → 05-system
  → 06-processed 写 .done（87 课已完成 ✅）
      ↑ 07-manual（人工修正，覆盖 AI 定义）

第二层：批量收敛（Phase A-C）
  融合术语 → 校准规则 → 重建系统 → 重建 knowledge/context

第三层：人工审核（07-manual → 触发重建链）

第四层：交付（knowledge/context = 对 AI 的接口层）
```

## 当前状态

| 层 | 状态 |
|:---|:-----|
| 单课处理 | ✅ 87 课全部完成（06-processed 87 个 .done）|
| 批量收敛 | 按需（一个季度/主题阶段触发）|
| 人工审核 | 07-manual 持续 |
| knowledge/context 交付 | 最近收敛 2026-07-11（current.md 待手动刷新）|

## 关键规则（来自 AGENTS.md，不可违反）

1. **01-raw 永不删除/修改**（原始数据永久保留）
2. **07-manual 任何时候不修改**（AI 只确认收到修正，融合时覆盖）
3. **批量处理逐课串行**（禁止并行）
4. **质量门禁**：Step 2 自检 cleaned 行数明显少于 raw；Step 4 每课规则 ≥3 条
5. **收尾自检**：raw/cleaned/glossary/rules/done 一一对应

## 与 build-engine 的关系

```
build-course（本文件）：课程 → knowledge/context（知识生产）
     ↓
build-engine（知识 → 输出形态）：knowledge/context → 插件/Agent/REST（能力消费）
```

**两个 build 区分**：课程 build（构建知识，AGENTS.md 权威）+ 引擎 build（输出形态，build-engine.md）——正是"构建 vs 依赖"分离。
