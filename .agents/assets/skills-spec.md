---
title: 技能包规范（Skills Spec）
description: AdaiOS 版 SKILL.md 技能包标准——审查官与高频流程封装为跨工具技能包（name/description + 触发/步骤/约束/输出/参考 五段），兼容 Agent Skills 开放标准
version: 1
created: 2026-08-19
updated: 2026-10-03
status: active
lines: 85
depends-on:
  - ../frontmatter-spec.md
related:
  - ../roles/
  - ../process/review.md
  - ../process/audit.md
tags: [ai, meta, skills, governance]
---

# 技能包规范（Skills Spec）

> 定位：把 AdaiOS 的审查官/高频流程封装成 **SKILL.md 技能包**——跨工具可识别（Claude Code / Qoder / DSH 均读取）、按需加载省上下文。借鉴 Anthropic Agent Skills 开放标准（2025-12-18 开放为开放标准）。

## 一、为什么做（背景）

- **渐进式披露**：8 审查官此前是「角色 prompt」，review 时全量注入；技能包化后元数据常载（~100 token）、指令触发时加载、清单执行时引用——防上下文过载（与 AGENTS.md 100-200 行原则同源）
- **跨工具互通**：同一份技能文件 Claude Code / Qoder / DSH 均能识别，呼应 AGENTS.md「工具无关，换工具零迁移」
- **可复用可版本化**：技能以文件存在，进 Git，随项目演进

## 二、SKILL.md 五段结构

| 段 | 内容 | 对应旧结构 |
|:---|:-----|:----------|
| 触发条件 | 何时加载本技能（用户请求类型/领域）| 审查视角开头 |
| 执行步骤 | 步骤化操作（1..N）| 审查视角 |
| 约束与规则 | 边界（只报告不修等）+ 清单引用 | 散落正文 |
| 输出要求 | 输出格式/质量门槛 | 输出格式 |
| 参考资料 | checklists/规范/边界/坑 | related |

## 三、frontmatter 融合（本规范核心）

SKILL 开放标准要求 `name` + `description`；AdaiOS frontmatter 契约要求 10 字段（guard-meta 必查）。融合规则：

- **保留全部 10 字段**：title/description/version/created/updated/status/lines/depends-on/related/tags
- **新增 `name`**：小写 kebab-case，与文件名一致（SKILL 标准必填）
- **`description` 写触发语义**：「当需要 XX 时加载」+ 解决什么问题（人/AI 双读者，符合方法论 Purpose/Trigger）

示例（`.agents/roles/code-backend-reviewer.md`）：

```yaml
---
title: 审查官：后端代码官
description: 当需要对 services/adai-core/ 后端代码做审查时加载——分层、数据安全、AI 集成健壮性、测试覆盖
name: code-backend-reviewer
...
tags: [review, backend, skill]
---
```

## 四、存放位置

- **技能本体**：`.agents/roles/<name>/SKILL.md`（审查官技能，防守侧）+ `.agents/skills/<name>/SKILL.md`（建设/流程技能，进攻侧——code-api-writer / code-domain-writer / ship / data-learn-writer）——留在治理体系内（frontmatter/索引/guard-meta 门禁齐全）
- **布局与出口（规则出处）**：技能放哪、怎么被各工具发现、出口怎么维护、新增工具怎么接 —— **一律见 [`ai-context-layer-spec.md`](ai-context-layer-spec.md)**（项目级 AI 上下文中间层规范）。本文只管技能包的**格式**：五段结构 + frontmatter 十字段融合。
- **规范**：本文件（assets/ 层）
- **工具侧加载路径**：由工具自己配置（见 AGENTS.md「工具接入」），项目内零迁移

## 五、新增技能流程

1. **何时建**：同一工作重复 ≥2 次（特定审查、特定建设流程、收尾流程）→ 封装为技能
2. **怎么建**：按本规范写 SKILL.md（五段 + name）→ 登记 `.agents/_index.md` → `bash .agents/guards/guard-meta.sh` PASS
3. **分类标记**：frontmatter `tags` 加 `skill`；审查官加 `review`、建设/流程加 `build`，便于检索/统计

## 六、与官方规范的偏离（必须留痕）

本规范以 **Agent Skills 开放标准**（`agentskills.io/specification`）为格式基线，但有**四条有意偏离**——`guard-skills.sh` 的 **S7** 会检查本节是否存在（偏离**必须留痕**，防遗忘）：

| # | 偏离 | 官方要求 | 我们怎么做 | 理由 |
|:--|:--|:--|:--|:--|
| 1 | **真相源位置** | 技能放在客户端约定目录（`.claude/skills/` 等） | 真相源在 **`ai-engineering/`**，靠软链出口到各工具目录 | 单一真相源（一份喂多工具，见 `ai-context-layer-spec.md` §二）|
| 2 | **审查官保持扁平** | 技能用 `<name>/SKILL.md` 目录布局 | `roles/<name>.md` **扁平**，**不进技能出口** | 审查官的"出口"是**生成的 subagent 定义**（Qoder md+YAML / Codex TOML，格式不同必须生成）；目录化零收益、44 处引用成本 |
| 3 | **过渡期部分技能仍扁平** | 同上 | `code-api-writer` / `code-domain-writer` / `ship` 暂留 `<name>.md` | 按需迁移——不为形式一致付全量迁移成本（`ai-context-layer-spec.md` §八 反模式 5）|
| 4 | **不做哈希锁定（S8）** | 生态中有 `skills-lock.json`（Vercel CLI） | **不做** | 本项目单人 + git 已覆盖版本控制；哈希锁是给"无版本管理的技能市场"用的 |
| 5 | **`触发条件` 的等价标题** | 段落名为「触发条件」 | 三个外部视角官用 **`为什么需要你`**（`guard-skills` S5 视其为等价） | 对陌生人官/社会性官/支持台官，这一问比"何时触发"更有力——**表达优先于形式对称** |

---

**追加方式**：新发现技能化质量问题 → 补入本规范。
