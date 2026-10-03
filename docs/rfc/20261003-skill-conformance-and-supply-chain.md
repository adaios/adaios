---
title: 技能包合规校准与 AI 供应链锁定——补上行业调研报告 §9/§12 指出的两个空白
description: 2026-10-03 行业调研（AGENTS.md / Agent Skills / MCP 现状）结论：技能包有两处真空白——① 16 个技能包未按 Agent Skills 官方规范校验（name 约束无检查、五段结构无机器判据）；② 技能与依赖无版本锁定与篡改可见性。本 RFC 定：技能包走「对齐可对齐项 + 明确记录两条结构性偏离」（不做目录迁移），新增 `ai-guard-skills.sh`（S1–S8）与 `skills-lock.json` 内容哈希清单，并把「技能改动必须重签」变成提交前门禁；依赖层只做清单快照，不引入联网 SCA；第三方技能准入留作未来批次。
date: 2026-10-03
status: draft
decided-by: —（待拍板：用户 2026-10-03「补」= 出方案，方案落地须另行点头）
tags: [ai-engineering, skills, 供应链, 门禁, 审查, RFC]
related:
  - ../../.agents/assets/skills-spec.md
  - ../../.agents/guards/ai-guard-meta.sh
  - ../../.agents/guards/ai-guard-feature.sh
  - ../../.agents/process/ship.md
  - ../reference/status.md
  - ../review/REVIEW.md
---

# 技能包合规校准与 AI 供应链锁定

> ⚠️ **本文的 §3.2 选项判断已被 [RFC 20261003-project-level-ai-context-layer](./20261003-project-level-ai-context-layer.md) 修正**（2026-10-03 同日）。原因：本文选 B（不重排目录）的核心依据是「没有任何外部工具直接扫描 `ai-engineering/` 布局」——用户随后补充的真实目标（Qoder / Codex / Claude Code / DSH 都要读项目层 AI 上下文）**恰恰推翻了这一前提**，故布局与适配改按中间层 RFC 执行。本文的 **S1–S8 校验项与 `skills-lock.json` 内容哈希方案仍然有效**，被中间层 RFC 吸收沿用；正文保留原文以留决策痕迹。

> 一句话：本项目的技能包是**给 AI 的执行指令**——它被改动就等于 AI 的行为被改动。当前这两件事都没有机器判据：技能是否符合标准、技能有没有被静默改过。

## 一、背景（为什么现在做）

起因是 2026-10-03 的行业调研（`AI编程行业标准化调研报告-2026-10.md`）。三条与本项目直接相关的结论：

1. **Agent Skills 是唯一没有基金会治理的主流标准**（AGENTS.md 与 MCP 已于 2025-12-09 捐给 Linux 基金会 AAIF），规范治理主体仍是 Anthropic 一家。这意味着**规范本身会变，本地合规不能只靠"我记得"**。
2. **官方规范有机器可校验的硬约束**：`name` ≤64 字符、仅小写字母/数字/连字符、不得首尾或连续连字符、必须等于父目录名；`description` 1–1024；`SKILL.md` 为必需文件名；官方还有校验器 `skills-ref validate`。
3. **技能供应链攻击已从论文变成事故**（2026 年 1–2 月 1,200+ 恶意技能进入主流技能市场、出现首个 agent CVE；42,447 个技能扫描中 26.1% 至少含一个漏洞）。同时 MCP 与 SKILL.md **都不声明能力边界**——协议层不会告诉你一个"搜索文件"的技能是否在读环境变量。

本项目当前无第三方技能，所以第 3 条的现实形态不是"上游投毒"，而是**自研技能被静默改动**（并发会话、自动 `--fix`、误操作）。这正是 `ai-guard-meta` 不覆盖的一层。

## 二、现状盘点（先看状态再动手）

| 项 | 现状 | 与官方规范的差距 |
|:--|:--|:--|
| 技能位置 | `.agents/roles/*.md`（12）+ `.agents/skills/*.md`（4） | **扁平文件**，非 `<name>/SKILL.md` 目录布局 |
| `name` 字段 | 已有，= 文件名 stem（如 `code-backend-reviewer`） | 字符集/长度/连字符约束**无任何检查** |
| `description` | 已有，写触发语义 | 长度上限 1024 **无检查** |
| 五段结构 | 已在 `assets/skills-spec.md` 定义（触发/步骤/约束/输出/参考） | **无机器判据**，靠人自觉 |
| frontmatter 10 字段 | ai-guard-meta 必查 ✅ | 已覆盖 |
| 内容完整性 | 无 | **无哈希、无锁定**，改动不可见 |
| 依赖锁定 | Flutter 两端 `pubspec.lock` 自带 content-hash | 后端依赖无清单快照 |
| 门禁基座 | `.githooks/pre-commit` 六层 + `.agents/scripts/ai-scan-secrets.py`（Python 执行器 + bash 守卫的先例） | 可复用该模式 |

## 三、方案 A：技能包规范校准

### 3.1 结构性冲突（必须先承认，不能装作能对齐）

官方规范有两条要求**在扁平布局下无法满足**：

| 官方要求 | 扁平布局 | 可对齐？ |
|:--|:--|:--|
| `name` 字符集 / 长度 / 无首尾连续连字符 | 文件属性 + frontmatter | ✅ 可校验 |
| `description` 1–1024 | frontmatter | ✅ 可校验 |
| `SKILL.md` 为必需文件名 | 文件叫 `xxx-reviewer.md` | ❌ |
| `name` 必须等于**父目录名** | 无父目录（无扩展名 stem） | ❌ |

### 3.2 三个选项与取舍

| 选项 | 做法 | 成本与风险 | 判定 |
|:--|:--|:--|:--|
| **A 全量目录迁移** | 16 个文件改为 `roles/<name>/SKILL.md` | **高**：16 处路径全变 → `depends-on`/`related` 图谱断链、`_index.md` 清单、ai-guard-meta 断链检查、pre-commit 触发条件、审查官调用路径全部要动；且违反 `skills-spec §四`「留在治理体系内」的既定决策 | ❌ 不做 |
| **B 对齐可对齐项 + 显式记录偏离** | 保持扁平；新增 `ai-guard-skills.sh` 校验可对齐项；两条偏离写进 `skills-spec` 并注明理由 | 低 | ✅ **本批** |
| **C 扁平真相源 + 导出合规产物** | 由脚本生成 `dist/skills/<name>/SKILL.md`（仅供外部工具加载与 `skills-ref validate`） | 中 | ⏳ 需要时再做 |

**选 B 的核心理由**：本项目技能的实际消费方是项目内配置读取（DSH / Claude / Qoder 均按项目内路径加载），**没有任何外部工具直接扫描 `ai-engineering/` 布局**。为不存在的外部消费者付一次全量迁移成本，是拿真风险换形式合规。C 选项保留了口子：真有外部消费者时导出产物即可，真相源仍是一份。

### 3.3 `ai-guard-skills.sh` 检查项

| 编号 | 检查 | 判据 |
|:--|:--|:--|
| S1 | 技能枚举 | `roles/*.md` + `skills/*.md`；**枚举为空 = FAIL**（防假绿） |
| S2 | 必填字段 | `name` 与 `description` 存在且非空 |
| S3 | `name` 合规 | ≤64 且匹配 `^[a-z0-9]+(-[a-z0-9]+)*$`（该正则同时禁首尾与连续连字符）；且等于文件名 stem |
| S4 | `description` 长度 | 1–1024 |
| S5 | 五段结构 | 按标题层级匹配「触发条件 / 执行步骤 / 约束与规则 / 输出要求 / 参考资料」，**不得只 grep 关键词**（正文提一句不算过） |
| S6 | 分类标记 | `tags` 含 `skill`（沿用 `skills-spec §五`） |
| S7 | 偏离在案 | `skills-spec.md` 中必须存在「与官方规范的偏离」段落，缺失 = FAIL（防遗忘：偏离必须留痕） |
| S8 | 锁定一致 | 与 `skills-lock.json` 对拍（见 §4.2），不一致 = FAIL 并列出被改文件 |

输出格式沿用项目惯例：`SKILL-GUARD: PASS (...)` / 失败逐条列 `S<n>`。

### 3.4 官方校验器的接入边界

`skills-ref validate` 只对官方目录布局有意义 → **扁平结构下不能直接跑**。故首期不接；若将来走选项 C，把 `skills-ref validate` 作为**导出产物的验收步骤**，而不是项目内的门禁。

## 四、方案 B：供应链锁定与篡改可见

### 4.1 威胁模型（本项目实际面对什么）

| 威胁 | 现实性 | 本批对策 |
|:--|:--|:--|
| 自研技能被静默改动（并发会话 / 自动修复 / 误操作） | **高**（已有 `ai-guard-meta --fix` 自动回写工作区的先例） | B1 内容哈希锁定 |
| 未来引入第三方技能时上游投毒 | 当前为零，将来为正 | B3 准入流程（留口） |
| Flutter / Maven 依赖供应链 | 常规 | B2 清单快照 |

### 4.2 B1 技能内容哈希清单 `.agents/state/skills-lock.json`

```json
{
  "generated": "2026-10-03",
  "algorithm": "sha256",
  "skills": [
    {"name": "code-backend-reviewer", "path": "roles/code-backend-reviewer.md",
     "sha256": "<64 hex>", "lines": 49, "tags": ["review", "backend", "skill"]}
  ],
  "external": []
}
```

- **生成/重签**：`bash .agents/guards/ai-guard-skills.sh --lock`
- **默认模式**：逐项对拍 → 不一致 = FAIL，输出「哪个技能被改」的清单
- **变更协议**：修改技能内容必须显式重签 → **技能改动在 diff 里必然可见**（lock 文件一起变）
- **防绕过**：哈希覆盖**全文件**字节（不是只哈希 frontmatter）；`lines` 与 `ai-guard-meta` 的 lines 双向对拍，避免"只改正文不重签"

> 定位说明：这不是"防恶意"（本机威胁模型里没有对手），是**把不可见的改动变成可见事件**——技能是执行指令，改动应当像改代码一样留在 diff 里。

### 4.3 B2 依赖层（只做清单快照）

- Flutter：两端 `pubspec.lock` 已有 content-hash → 只检查「存在且已提交」，不引工具。
- Maven：导出依赖清单快照（GAV 列表）供 diff 可见。
- **明确不做**：不引入需要联网的 SCA（OSV / Snyk 等）。理由：本项目依赖面窄、有网络成本，而收益低于门禁维护成本；列为未来可选。

### 4.4 B3 第三方技能准入（**未来批次，本批不实施**）

若将来通过 `npx skills add` 等引入外部技能：登记来源 URL + 提交哈希 + 审计结论 + 决策人，写入 `skills-lock.json` 的 `external[]`；未登记的外部技能由 guard **提示**（不阻断，避免逼出 `--no-verify`）。

## 五、实施批次

| 批 | 内容 | 类型 |
|:--|:--|:--|
| **批 1** | `ai-guard-skills.sh`（S1–S7）+ 反例测试 | 新增脚本 |
| **批 2** | `skills-lock.json` + S8 对拍 + pre-commit 挂一层 | 新增 |
| **批 3** | `skills-spec.md` 更新：对齐官方规范 v1、写入两条结构性偏离（引用本 RFC） | 文档 |
| 批 4（可选） | 依赖清单快照（B2） | 新增 |
| 未来 | 选项 C 导出产物 / B3 第三方准入 | — |

批 3 与批 1 有依赖：S7 要求 `skills-spec.md` 含偏离段落，故**批 3 必须先于或同批于批 1**。

## 六、验收标准

1. **反例必须真触发**（沿用 ai-guard-feature 的教训：测试中曾抓出"卡计数恒 0"的假绿）：逐条构造反例——`name` 超 64 / 含大写 / 含连续连字符、`description` 超长、缺五段之一、`tags` 缺 `skill`、枚举为空、偏离段落被删、技能文件被改而 lock 未重签。
2. **不回归**：`ai-guard-meta` / `ai-guard-align` / `ai-guard-feature` / `ai-guard-tools --shell-lint` 全部仍 PASS。
3. **零内容改动**：16 个现有技能包的正文与 frontmatter **一个字节不改**（本批只加校验）。
4. **钩子可降级**：脚本缺失时跳过而非锁死（沿用 pre-commit §0b 与 §2b 的既有防御，避免"改钩子却不带脚本"把提交能力锁死）。
5. **假绿三态明确**：枚举为空 / 脚本缺失 / lock 文件缺失，各自行为写进注释与输出。

## 七、明确不做（防范围蔓延）

- 不做技能目录全量迁移（选项 A）。
- 不改现有 16 个技能的内容。
- 不引入联网 SCA、不装额外运行时依赖（Python3 已用于 `ai-scan-secrets.py`，可复用）。
- 不动 `data/`、产品代码、既有守卫脚本的判据。

## 八、待拍板点

| # | 决策 | 建议 |
|:--|:--|:--|
| 1 | 选项 A / B / C | **B 现在做，C 留口** |
| 2 | lock 不一致是硬阻断还是软提示 | **硬阻断**（技能是执行指令，改动必须重签） |
| 3 | 第三方技能准入（B3）是否现在做 | **否**，当前无第三方技能，避免为空场景建机制 |
| 4 | 依赖层（B2）是否并入本批 | **否**，独立批 4 |

---

**追加方式**：本 RFC 落地后，新发现的技能化质量问题补入 `.agents/assets/skills-spec.md`；本文件状态由 `draft` → `approved` 须经用户点头。
