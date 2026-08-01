---
title: 记忆系统进化 — 元记忆对比与落地方案
date: 2026-08-01
status: accepted
---

# 记忆系统进化（Memory System Evolution）

## 一、背景：两套记忆系统

AdaiOS 有两套记忆系统并存，但**成熟度差异很大**：

| 维度 | Claude 元记忆（`.claude/projects/.../memory/`）| adai-core 应用记忆（`data/memory/`）|
|:-----|:--------------------------------------------|:----------------------------------|
| 触发 | **筛选**：只记非显然结论，repo 已记录的不记 | **全量**：每条 record 都沉淀（#24 后 AI 失败也降级沉淀）|
| 结构 | 有 `type`（user/feedback/project/reference）| 无类型；有 tags/sentiment/actionable |
| 为什么 | 正文带 **Why / How to apply** | 只有 summary（洞察），无来源背景 |
| 召回 | MEMORY.md 索引全量 + 按需读文件 | 时间窗口 `recent(7)` / findByDate |
| 更新 | **覆盖更新旧文件 / 删除过时** | 只追加 + 同 recordId 去重/升级 |
| 淘汰 | 有"过时需验证"意识 | **无淘汰、无过期验证** |

**实证缺口**（`data/memory/2026/07.md`，2026-07-23）：
```
mem_20260723_162634  recordId=rec_...632  tags=[饮品, 茉莉花茶]  → "下午喝了茉莉花茶"
mem_20260723_162635  recordId=rec_...634  tags=[茶, 饮品]       → "喜欢喝茶，尤其是茉莉花茶…"
```
两条是同一话题（喝茶）的事实→洞察**进化链**，recordId 不同 → 无合并机制，永远并存。AI 回读看到两条平级记忆，无法知道"喜欢喝茶"是更新的理解。

## 二、核心判断

项目记忆是**"全量 + 时间 + 只追加"的档案系统**；Claude 元记忆是**"筛选 + 类型 + 为什么 + 更新淘汰"的活系统**。最大差距不在存储，而在**进化能力**和**闭环能力**——恰好对应 VISION 两条原则：

- **Knowledge Evolves**（知识不断演化：新闻→主题→行业→策略）
- **Reality → Knowledge → Action → Reality**（必须形成反馈闭环）

## 三、落地方案（分 5 个 Phase，按价值排序）

### Phase 1：记忆类型（kind）— 让记忆可分类召回

- `Memory` record 增加 `kind` 字段：`fact` / `insight` / `preference` / `pattern` / `decision`
- 来源：从 AiUnderstanding 推导——`preferences` 非空→`preference`；`patterns` 非空→`pattern`；`insight` 非空→`insight`；QUESTION 决策类→`decision`；否则→`fact`
- 文件格式：frontmatter 加 `kind:` 行
- 召回：`findAllPatterns`/`findAllPreferences` 收敛为按 `kind` 统一查询
- **理由**：分类是进化/淘汰的前提——"用户偏好"和"事实记录"的时效性完全不同（偏好可被修正，事实只能被覆盖）

### Phase 2：主题级合并 / 进化（核心，Knowledge Evolves）

- 引入"记忆主题"（`topic`）：同一话题的记忆归为一个主题，**新记忆成为主题最新版本，旧版本标记 `superseded`**
- 匹配（MVP，不做语义模型）：
  1. 新记忆与近 30 天记忆做 **tags 重叠**匹配（≥1 个重叠标签 → 候选主题）
  2. 候选内取 **创建时间最新** 的主题为准
- 沉淀流程：`persist()` 匹配到主题 → 旧条目标 `superseded: true` + `evolvesFrom/evolvedTo` 指针 → 新条目带 `topic: <主题id>`
- 回读：`loadMemorySummary` 只取各主题**最新未 superseded** 版本（事实与洞察合并展示）
- **理由**：直接解决"喝茶"式断裂——fact 被 insight 取代，但演变历史保留（可追溯）
- 先不做语义相似度（LLM 参与成本高），tags + 时间窗口的启发式已能覆盖多数场景

### Phase 3：actionable 闭环（Reality → Knowledge → Action → Reality）

- **消费方一**：Context Engine 回读时，`actionable=true` 记忆单独生成"## 待行动事项"section 注入（当前 `loadMemorySummary` 只聚合 summary，丢弃 actionable）
- **消费方二**：Feed 构造时，未完成 actionable 记忆作为"待办提醒"条目
- **完成机制**：新接口 `PATCH /api/v1/memory/{id}/done`（标记完成，`actionable=false` + 记录完成时间）
- **关联 #23**：Layer 6 规则冲突（如"单吊"）产生的行动建议，正是 actionable 记忆的天然来源——冲突检测 → 沉淀 actionable 记忆 → 问答/Feed 提醒 → 用户执行 → 标记完成 → 新记录反哺。反馈闭环接通
- **理由**：当前 `actionable/actionSuggestion` 字段存在但无消费方，记忆是"死的"

### Phase 4：时效与淘汰

- 记忆条目加 `lastConfirmed`（最近一次被回读/确认的时间）
- `findAllPatterns`/`findAllPreferences` 按置信度 **× 时效衰减**（旧记忆降权，不再平权参与）
- 定期（随 rebuild）清理：`superseded` 超 60 天、`actionable` 完成超 30 天的条目归档或删除
- **理由**：Claude 元记忆有"过时需验证"意识，项目记忆没有——半年前偏好不应平权误导当前决策

### Phase 5：筛选降噪

- `persist()` 判断"是否有信息增量"：`insight` 空 && patterns 空 && preferences 空 → 不沉淀为 insight 类（仅保留 fact 短记或跳过）
- 降级沉淀（DEGRADED，#24）保留为保底，AI 恢复后升级覆盖（已有机制）
- **理由**：Claude 元记忆只记非显然结论；项目记忆全量沉淀会膨胀成 records 副本，稀释回读质量

## 四、文件格式演进

```markdown
---
id: mem_xxx
recordId: rec_xxx
kind: insight              # Phase 1
topic: topic_tea           # Phase 2
superseded: false          # Phase 2（true 时 evolvedTo 指向新版本）
evolvedTo: mem_yyy
lastConfirmed: 2026-08-01  # Phase 4
tags: [茶, 饮品]
sentiment: neutral
actionable: false
actionSuggestion: null
createdAt: 2026-07-23T16:26:35
---
喜欢喝茶，尤其是茉莉花茶，香气清新，让人放松。
```

向后兼容：新字段全部可选，旧条目解析为 `kind=insight, superseded=false`，不破坏现有读取。

## 五、验收标准

1. **主题化**：同话题记忆（如"喝茶"）能追踪 fact→insight 演变链，回读只带最新版本
2. **闭环**：一条 actionable 记忆从产生（冲突检测）→ 注入问答/Feed → 完成标记，全程可追踪
3. **时效**：被取代/过期的记忆不再平权参与回读
4. **降噪**：memory 条目增长不随 records 线性膨胀（有信息增量才沉淀）

## 六、实施建议

- **Phase 1 + 2** 是地基（类型 + 主题），改动集中在 `Memory`/`MemoryService`/`MemoryFileRepository` 格式与 `persist` 逻辑，建议先落地
- **Phase 3** 涉及 Context Engine 回读 + Feed + 新接口，独立可并期推进（天然承接 #23）
- **Phase 4/5** 是质量收尾，随使用量增长后再优化

建议实施顺序：**Phase 1 → 2 → 3 → 4/5**（与 #24 已落地的降级/升级机制衔接，不冲突）。
