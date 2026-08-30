---
title: learn 插件——外部内容学习沉淀（视频/文章 → 个人知识卡片 → 三通道呈现）
date: 2026-08-29
status: draft
---

# learn 插件：外部内容学习沉淀

> **方向 RFC**：用户日常消费 B 站等平台的高质量视频/文章（AI 相关、交易相关、知识科普混合），希望整理成文档慢慢消化、留存。learn 插件把「外部内容 → 个人知识资产」变成一条标准流水线：**喂入 → AI 结构化卡片（File First）→ 对话流/资产页/问答三通道呈现 → 交易类内容经审核闸反哺交易规则库**。

---

## 一、背景（现状与问题，实测证据）

| # | 现状 | 问题 |
|:-:|:-----|:-----|
| A | 用户经常看 B 站高质量视频（AI 类、交易类、科普类混合），当前**无留存管道** | 收藏夹吃灰、链接易失效/下架、内容无法被阿呆想起——「看了就忘」 |
| B | 交易知识库（`os/trading-engine/knowledge/context/`，R1-R120）只能经问答碎片召回 | 阿呆能引用 R66，但**用户看不到整篇 rules.md 原文**——「资产全景」缺失 |
| C | 项目已有成熟范式：插件模型（RFC 20260814）+ trading 三阶段（多用户 `data/{userId}/` 架构）+ promote 审核闸 + KnowledgeSource 注入 | 无——范式现成，learn 是「按模板再走一遍」 |

## 二、问题（病灶/缺口清单）

| # | 缺口 | 证据/影响 |
|:-:|:-----|:-----|
| 1 | 外部内容无结构化留存 | 视频看完即忘，无法复述、无法复习、无法被问答召回 |
| 2 | 问答是碎片召回，不是资产全景 | 用户看不到自己沉淀的整篇文档（learn 无此能力；交易知识库同病） |
| 3 | 跨域内容（交易视频）归属不清 | 交易类内容既要学习沉淀、又可能反哺交易规则库——双域交集需明确边界 |
| 4 | 无复习机制 | 留存 ≠ 消化，需要间隔复习（一周/一月回看卡片）|

## 三、方案

### 3.1 一句话

**AdaiOS 加 `learn` 插件：视频/文章喂给阿呆 → AI 按类型模板结构化成本人卡片（File First，md 按用户落服务器 `data/{userId}/learn/`）→ 对话流/资产页/问答三通道呈现 → 交易类内容经你审核闸反哺交易规则库。**

### 3.2 核心原则

1. **多用户架构，单用户运行**：数据模型/注入/路径全按多用户设计（对齐 trading：`data/{userId}/` + KnowledgeSource 按用户）；当前只跑 `adai` 一个账号，多用户测试成本 V1 不付，但架构不留返工
2. **File First**：卡片即 md 文件，浏览 = 渲染文件，不建额外库、不加额外接口
3. **内容归 learn，规则候选归 trading，跨域只引用不搬移**：无双写、无同步问题
4. **无第三视角（B1）**：一切呈现是「我和阿呆」的自然对话，无系统标签
5. **纯内聚，不做抓取**：用户粘贴字幕/链接原文进来，阿呆负责结构化——守 B8 外向动作红线 + 版权边界（个人消化留存，不公开传播）

### 3.3 数据模型（多用户）

```
data/{userId}/learn/
├── ai/2026-08-29_RAG与Agent的区别.md
├── trading/2026-08-29_回调一半的判定.md
└── other/2026-08-28_XXXX.md

data/{userId}/trading/        ← 既有（V2 加 candidates/ 候选目录）
data/{userId}/records/        ← 既有（原始素材记录，统一入口留痕）
```

### 3.4 卡片模板（frontmatter + 正文，按类型可配置）

```yaml
---
title: 回调一半的判定
type: trading            # ai | trading | other（可配置扩展）
source:
  platform: bilibili
  author: 某某UP
  url: https://b23.tv/xxx
  published: 2026-08-15
created: 2026-08-29       # 消化日期
status: new               # new | review | done（复习状态）
trade_related: false      # 是否涉及可执行交易规则
trade_note: ""            # 交易类备注：与 R 规则的关系（互补/冲突/重复，V1 仅记录）
record_id: "xxx"          # 回链原始记录（统一入口留痕）
tags: [回调, 止损]
---
```

正文（渐进式摘要三层）：

```markdown
## 核心观点
（一句话，用自己的话）

## 关键要点
- 02:31 要点一（带时间戳）
- 05:47 要点二

## 我的疑问
- 疑问一（存疑点，可触发后续讨论）

## 复述
（24h 内自己写 100-200 字——消化关键，防「存了等于没存」）
```

**类型模板差异**（frontmatter `type` 决定追加字段）：

| 类型 | 追加字段 |
|:-----|:---------|
| trading | 逻辑链、可验证信号、与自选股/交易系统的关联（`trade_note`）|
| ai（技术）| 核心概念、代码/命令要点、实践步骤、坑 |
| other（科普/人文）| 论据链、背景脉络、与已有知识的关联点 |

### 3.5 流程（喂入 → 卡片化 → 分叉判定 → 呈现）

```
① 喂入：粘贴字幕/链接/文章原文 → POST /api/v1/records（统一入口，红线「入口统一」）
        meta.source 带平台/UP主/URL
② 卡片化：AI 按 type 模板结构化生成卡片 → 落 data/{userId}/learn/{type}/{date}_{title}.md
        （LLM 失败 → 落原始素材 + 提示，不生成半成品卡片，fail-visible）
③ 分叉判定（仅 type=trading）：
      ├─ 理念/心态/方法论 ───────────────► learn 止（卡片完成）
      ├─ 具体规则/信号，与 R 规则互补 ──► trading 候选（V2：data/{userId}/trading/candidates/
      │     建议卡 + learn_card_id 回链）─► 你审核 ─► promote ─► 融合校准 ─► knowledge/context 重建
      ├─ 具体规则，与 R 规则冲突 ──────► trade_note 标注冲突点，暂不动规则 ─► 你拍板
      └─ 与 R1-R120 重复 ─────────────► trade_note 标注「已有 Rxx」─► learn 止
④ 呈现：三通道（见 3.7）
```

**关键设计：交易内容过「你确认」闸**。项目已有血淋淋教训——课程讲「回调一半」指回撤到涨幅一半位置 `(high+low)/2`，代码实现成「距前高回撤 50%」，**语义漂移**（P1-交易9 未修项）。视频口语化表述进规则库必有翻译损耗，只有你（交易系统主人）拍板，语义才不歪。

### 3.6 插件边界

| 能力 | 归属 | 说明 |
|:-----|:-----|:-----|
| learn 卡片化/归档/列表 | **learn 插件** | 新插件：PluginRegistry 注册 + Account.plugins 门控 + ContextEngine 门控 + 三端显隐 |
| 资产浏览（目录树 + 全文渲染）| **Kernel 共享** | learn + 交易知识库同一套 UI；后端复用/下沉 admin 的 `GET /admin/knowledge` 浏览能力（当前收敛在治理端，下沉为用户端能力）|
| 交易知识注入/规则引擎 | trading 插件 | **不动** |
| 跨域引用 | 引用不搬移 | learn → trading 候选只存「建议卡 + learn_card_id 回链」，内容不复制（无双写）|

### 3.7 呈现三通道

```
喂入 ──► ① 对话流即时呈现（阿呆复述卡片，主呈现）
              │
              ├─► ② 资产页归档（web 全景浏览，次呈现）
              │
              └─► ③ 问答召回（LearnKnowledgeSource 注入，价值呈现）
```

**① 对话流（主）**：喂入当下，阿呆在对话里复述卡片给你看（无第三视角）：
> 阿呆：「这篇《回调一半的判定》我帮你记下来了。核心观点是……三个要点是……我有个疑问：它说回调一半，和我们 R66 的表述好像对不上？要不要我标个冲突？」
> 你：「对，标上」→ 阿呆就地更新 `trade_note`。

**② 资产页（次，web 端主场）**：桌面 web 导航加「资产」项：

```
「资产」导航（桌面 web）
┌──────────────┬─────────────────────────────┐
│ 目录树        │  全文渲染（右侧主区）          │
│ 📁 学习笔记    │  # 回调一半的判定（整篇卡片）   │
│  ├─ ai        │  核心观点 / 要点 / 疑问 / 复述 │
│  ├─ trading   │                             │
│  └─ other     │  （或 R66 止损纪律整篇原文）   │
│ 📁 交易知识库  │                             │
│  ├─ rules     │                             │
│  ├─ strategy  │                             │
│  └─ mistakes  │                             │
└──────────────┴─────────────────────────────┘
```

- 左目录树：learn 按 type 分组 + 交易知识库 `knowledge/context/` 五文件（rules 从 R1-R120 一次看全——顺带解决「看不到整篇文档」）
- 右全文：点开即整篇 md 渲染；全文搜索（复用现有 search 能力）
- **双端分工（成本克制）**：web 全量浏览 + 编辑补充（长文阅读/打字的主场）；app 只做「最近学习 + 单篇全文」（随手喂入的主场），完整浏览引导到 web
- **按当前登录用户读自己的 `data/{userId}/learn/`**；交易知识 os/ 走 owner 白名单（对齐 trading 现状：仅 adai 回落 os/，P1-3 fallback 收窄教训）

**③ 问答召回（价值）**：`LearnKnowledgeSource`（按用户）把最近笔记注入问答上下文——你问「上次讲 RAG 那篇说了啥」，阿呆引用你消化过的那篇作答，而不是泛泛而谈。**这是「留存」的最终意义：存了能被想起来**。

### 3.8 端点设计（草案，V1 范围）

| 端点 | 方法 | 说明 |
|:-----|:-----|:-----|
| `/api/v1/records` | POST | 统一入口喂入素材（已有，learn 后台分流）|
| `/api/v1/learn/cards` | GET | 卡片列表（type/source/status 筛选）|
| `/api/v1/learn/cards/{id}` | GET | 单篇卡片全文 |
| `/api/v1/learn/tree` | GET | 资产目录树（learn 分组 + 交易知识库，用户端资产页用）|
| `/api/v1/learn/content?path=` | GET | 按路径读 md 全文渲染（复用/下沉 admin knowledge 浏览能力）|

V2：`PATCH /learn/cards/{id}`（对话流让阿呆改）、`POST /learn/cards/{id}/review`（复习状态流转）、`POST /trading/candidates`（反哺候选）。

### 3.9 六维检查

#### 目标与约束
实现「外部内容 → 个人知识卡片」流水线 + 三通道呈现 + 资产全景浏览。
**不做**：抓取/下载（B8 外向动作 + 版权）、多用户产品化测试（架构预留即可）、V1 不做 trading promote 联动、不做复习推送。

#### 架构与边界
- 新插件 `learn`：`domain/learn/`（卡片生成/列表/读取）+ 配置 `adai.knowledge.learn-path`（仿 trading）+ `data/{userId}/learn/`
- 资产浏览 = Kernel 共享能力（learn + trading 同 UI），后端复用 admin knowledge 浏览端点下沉
- **不动** trading 域实现、不动 records/记忆/时间线现有链路（仅新增 learn 分流）

#### 技术规范
Java 17 + Spring Boot，遵循 conventions.md C1-C8（分层依赖 C7：interfaces → application → domain/kernel ← infrastructure）；File First md 文件；路径配置注入仿 trading（`adai.knowledge.learn-path`）。

#### 质量门槛
- 后端测试配套：卡片生成 / 分叉判定 / 按用户读取（无文件不注入）/ LLM 失败降级，全绿
- guard-meta PASS + guard-align PASS；feature-reference 登记 learn 章节；三端显隐对拍（`/me/plugins`）

#### 边界条件
- 无字幕纯链接 → 提示用户补素材（learn 只做结构化，不做抓取）
- LLM 失败 → 落原始素材 + 提示，不生成半成品卡片（fail-visible，对齐交易规则层教训）
- 重复内容 → 标题相似提示「已有《XXX》卡片？」（V1 弱校验）
- 并发写 → per-user 条带锁（对齐 trading PUT /rules 教训：X5 并发写无锁 RMW）
- 跨日/时区 → created 用服务器日期

#### 安全约束
- `data/` gitignore 保护（B3 红线，新增 learn 子目录 gitignore 验证）
- 无第三视角（B1）；外向动作不做（B8）；插件门控不旁路
- 素材原文是用户自愿喂入的个人内容，落服务器 `data/` 是既定模式；备份纳入 backup_prod.sh
- **已知沿用**：X-User-Id 零鉴权是 REVIEW #179 未修项——learn 数据路径同样暴露，V1 沿用现状不扩大攻击面，登录体系落地时统一收紧

## 四、决策点（需用户拍板）

| # | 决策 | 选项 | 我的建议 |
|:-:|:-----|:-----|:---------|
| 1 | 插件命名 | `learn` / `study` / `notes` | `learn`（语义「学习沉淀」，与 research 主动研究区分开）|
| 2 | 资产浏览归属 | Kernel 共享（learn + 交易知识库同 UI）/ learn 插件内 | **Kernel 共享**（顺带解决交易知识「看不到全文」）|
| 3 | V1 是否含 trading 候选联动 | 只留 `trade_related` 标记 / V1 就做候选 | 只留标记（V1 聚焦 learn 跑通，联动 V2）|
| 4 | 喂入入口 | 统一 `POST /records` 分流（红线「入口统一」）/ 独立 `POST /learn/cards` | **统一入口**（项目红线，records 留痕可回溯）|
| 5 | 卡片编辑方式 | 对话流让阿呆改（无第三视角）/ 资产页就地编辑 | 对话流改（V2），符合产品气质 |
| 6 | 多用户测试 | V1 不付（架构预留）/ V1 付 | V1 不付（单用户运行，测试成本后置）|

## 五、风险与对策

| 风险 | 对策 |
|:-----|:-----|
| LLM 结构化失败丢素材 | fail-visible：落原始素材 + 提示，不生成半成品（对齐交易规则层 P0 教训）|
| 交易规则语义漂移（P1-交易9 血泪）| 交易候选必须过用户审核闸 + 融合校准后才进 knowledge/context（promote 机制延伸）|
| 卡片杂乱/膨胀 | type 分类 + status 复习状态 + 全文搜索 |
| 素材/卡片隐私泄漏 | `data/` gitignore + 按用户路径（B3）；LearnKnowledgeSource fallback 收窄（无文件不注入，对齐 P1-3）|
| 路径配置断链 | 仿 trading 配置注入 + guard-align/pre-commit 检查 |
| 资产页浏览能力下沉破坏 admin | 复用不搬移：admin 端点保持，用户端新增只读端点（或共用服务层）|
| 备份遗漏 | learn 目录纳入 backup_prod.sh（验收项）|

## 六、落地路径（分批次，每批可验收）

| 阶段 | 内容 | 验收 |
|:-----|:-----|:-----|
| **V1（learn 插件 MVP）** | ① 插件注册 + 门控（PluginRegistry + Account.plugins + 三端显隐）；② `POST /records` domain=learn 分流 → 卡片生成 → `data/{userId}/learn/`；③ `GET /learn/cards` 列表/单篇 + `GET /learn/tree` 资产树；④ web「资产」页（目录树 + 全文渲染）+ app「最近学习」入口；⑤ `LearnKnowledgeSource` 问答注入（按用户，无文件不注入）；⑥ `trade_related` 标记（V1 仅记录）；⑦ backup_prod.sh 纳入 learn | 后端测试全绿 + guard-meta PASS + 生产实测：喂一篇真实字幕 → 卡片落盘 → 资产页可见 → 问答可召回 |
| **V2（消化闭环）** | 复习提醒（status 流转 new→review→done + 推送）；trading 候选→promote 联动（candidates/ + 审核闸 + 融合）；卡片编辑（对话流让阿呆改）；多用户测试补全 | 复习推送生效；交易视频反哺规则库走通全链路；多用户隔离测试 |

## 七、验收标准（可测量）

1. 后端测试新增 ≥ 12 条（卡片生成 / 分叉判定 / 按用户读取 / 降级 / 资产树），全绿
2. `guard-meta.sh` PASS + `guard-align.sh` PASS
3. feature-reference 登记 learn 章节（端点表 + 插件清单）
4. 三端显隐对拍：app/web 按 `/me/plugins` 显示「学习/资产」入口，未启用用户不显示
5. 生产实测（adai 账号）：喂一篇 B 站交易视频字幕 → 卡片落 `data/adai/learn/trading/` → web 资产页目录树可见 + 全文渲染 → 问答「上次讲回调那篇说了啥」能召回
6. `backup_prod.sh` 覆盖 learn 目录（备份清单含 `data/*/learn/`）
