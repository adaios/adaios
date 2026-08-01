# 产品路线 v1.0.0（Product Roadmap）

> **定位：AdaiOS 产品唯一蓝图，最高优先级文档之一。**
>
> 路线驱动开发——**所有任务从这里拆分，所有目标对照这里确认**。
> 常驻文档，随版本演进滚动更新（旧版本规划保留在「版本历史」）。
>
> 文档版本：v1.0 | 最后更新：2026-08-02
> 关联：[VISION.md](../../VISION.md)（为什么）｜[product-architecture.md](product-architecture.md)（是什么）

---

## 一、开发模式：路线驱动开发

**这是我理想的工作模式**——所有开发围绕一份路线文档展开，不散装、不临时找方向：

```
产品路线（本文件，目的地：v1.0.0）
   │ ① 看着路线，确认目标（本版本要做什么）
   ▼
任务拆分（docs/reference/task-log.md，接下来做什么）
   │ ② 从路线拆任务，任务标注归属的版本/里程碑
   ▼
开发执行（/ship 收尾单个功能）
   │ ③ 执行中遇到方向决策、发现问题
   ▼
决策记录（docs/rfc/，怎么决定的）
问题记录（docs/reference/issue-log.md，遇到什么问题）
审核把关（docs/review/REVIEW.md，质量如何）
```

| 环节 | 文档 | 角色 | 频率 |
|:-----|:-----|:-----|:-----|
| **看路线** | 本文件 | 目的地、版本蓝图 | 每次会话/每次拆任务前 |
| **拆任务** | `reference/task-log.md` | 从路线拆出可执行任务（模块化、P0-P3）| 版本开始前 |
| **确认目标** | 本文件 + task-log | 对照路线确认"本批做哪些、达成什么" | 每批任务前 |
| **做决策** | `rfc/` | 方向性决策记录（proposed→approved→implemented）| 有决策时 |
| **记问题** | `reference/issue-log.md` | 问题/缺陷记录（模块化）| 有问题时 |
| **审质量** | `review/REVIEW.md` | 审核状态报告 + 守护检查 | /review 时 |

**规则：所有任务必须能回溯到路线。** 路线里没有的，先不进开发——那是探索（进 `docs/ideas/`）或需要新增到路线。

---

## 二、版本演进总览

SemVer（规则见 `docs/rfc/20260801-release-versioning.md`）：`MAJOR.MINOR.PATCH`

| 版本 | 定位 | 内容概要 | 状态 |
|:-----|:-----|:---------|:----:|
| **v0.1.0** | 首个可发布基线 | 记忆进化 Phase 1-3 + 审核修复三批 + #13 消重复 + deploy.sh | 📋 推进中 |
| **v0.2.0** | 能力补全 | Layer 5 外部信息深化 + Layer 2 主动推送（待定）| ⬜ 候选 |
| **v0.3.0** | 多模态 | Layer 4 多模态记录（待定）| ⬜ 候选 |
| **…** | 版本演进 | 见「版本历史」，每版从功能全景中取项 | ⬜ 待定 |
| **v1.0.0** | **正式版** | 核心闭环稳定 + 数据格式冻结（见 §五）| 🎯 目的地 |

---

## 三、v1.0.0 功能全景（目的地）

从五层产品架构 + Domain OS + 工程基建三个维度，给出 v1.0.0 应具备的完整能力。✅=已完成 🟡=基础 ❌=空白 📋=规划中 ⬜=候选待定。

### 3.1 五层产品架构

| 层 | 能力 | 状态 | 说明 |
|:---|:-----|:----:|:-----|
| **L1 AI 问答** | 意图识别 / Context Engine / 卡片对话 / Memory 回读 / Knowledge 注入 | ✅ | 完整 |
| **L2 主动推送** | 今日简报 | 🟡 | 基础；深化方向（异动/提醒）待定 |
| **L3 身份+记忆** | Identity / Record / Timeline / Context / Memory / Knowledge | ✅ | Kernel 六大组件完整 |
| **L4 通用记录** | 文字记录 + 意图路由 | ✅ | — |
| | **多模态记录（图片/音频）** | ❌ | **最大空白，候选 v0.3.0** |
| **L5 外部信息** | 行情接入（腾讯）Phase 1 上下文注入 | ✅ | 已落地 |
| | 行情主动推送 / Feed 行情嵌入 | ⬜ | 推送机制待设计；新闻无稳定源暂不做 |
| **L6 交易闭环** | 持仓 / 复盘 / 知识反哺（promote/conflicts）/ DECISION 路由 | ✅ | 完整 |

### 3.2 Domain OS

| Domain | 状态 | 说明 |
|:-------|:----:|:-----|
| **Trading OS** | ✅ | 87 课知识库 → 11-context → KnowledgeSource → Context Engine 全链路 |
| **Project OS** | ✅ | Status API + git 自举 + RFC 索引 + 轻量任务系统（Phase 1-4 全完成）|
| **Life OS** | 🏗 等数据 | 骨架就绪（快速记录 + LifeKnowledgeSource）；情绪/习惯/周报待数据积累后触发 |

### 3.3 记忆系统（Knowledge Evolves）

| 能力 | 状态 | 说明 |
|:-----|:----:|:-----|
| 记忆沉淀/去重/降级 | ✅ | #24 已落地（DEGRADED + 升级语义）|
| 记忆类型 kind | 📋 | 进化 Phase 1（本次 v0.1.0）|
| 主题级合并 superseded | 📋 | 进化 Phase 2（本次 v0.1.0）|
| actionable 闭环 | 📋 | 进化 Phase 3（本次 v0.1.0）|
| 时效与淘汰 | 📋 | 进化 Phase 4（本次）|
| 筛选降噪 | 📋 | 进化 Phase 5（本次）|

### 3.4 工程基建

| 能力 | 状态 | 说明 |
|:-----|:----:|:-----|
| 版本机制 | 📋 | RFC 已写，待 accepted（本次）|
| 审核流程 | ✅ | /review 三档 + 5 角色 + guard.sh |
| 发布流程 | 📋 | v0.1.0 首次跑通（本次）|
| deploy.sh | 📋 | 待规范化（本次）|
| adai-admin 管理后台 | ⬜ | 规划中，范围待定 |

---

## 四、版本规划详情

### v0.1.0（当前版本）— 首个可发布基线

**范围**（见 `docs/rfc/20260801-release-versioning.md` §七）：

```
v0.1.0 — 首个可发布基线
├── 功能：记忆进化 Phase 1-3（类型 + 主题合并 + actionable 闭环）
│        + Phase 4/5（时效淘汰 + 筛选降噪）
├── 质量：审核修复三批（已提交 cce0b1a/af530f0/c37e251）
├── 重构：#13 interfaces 编排消重复
├── 部署：deploy.sh 规范化
└── 验证：guard.sh + 全测试 + /review --full → 冻结发布（tag + Release Notes）
```

**发布标准**：
1. 全量验证通过（守护 G1-G7 + 后端全测试 + /review --full）
2. REVIEW.md 无未解决的 P0/P1（#13/#19/#22 明确"发布前处理 or 记录为已知问题"）
3. 数据格式变更已写迁移说明（记忆进化新增 kind/topic/superseded 字段：向后兼容，无需手动迁移）
4. `git tag v0.1.0`（annotated）+ `docs/releases/v0.1.0.md` Release Notes

### 后续版本（候选，待确认）

> 这些是 v1.0.0 全景里"未做"的部分，**是否进 v1.0.0、进哪个版本，由用户确认后填入**。

| 候选 | 归属 | 前置依赖 |
|:-----|:-----|:---------|
| L2 主动推送深化（异动/提醒）| v0.2.0 | 推送机制设计（无渠道，需定轮询/定时方案）|
| L5 Feed 行情嵌入 | v0.2.0 | 行情数据已接入，FeedEntry 加 market 类型 |
| L4 多模态记录 | v0.3.0 | 记录格式设计 + AI 视觉能力确认 |
| Life OS 情绪/习惯/周报 | 数据积累后 | 10+ 条生活数据 |
| adai-admin 管理后台 | 待定 | 规划 RFC 确认范围 |

---

## 五、进入 v1.0.0 的标准

版本机制 RFC（`20260801-release-versioning.md` §二）定义：

> 进入 `v1.0.0` 需：**核心闭环（记忆/问答/复盘）稳定 + 数据格式冻结**。

补充判断（建议，可调整）：
- 五层架构无 ❌ 空白（L4 多模态、L5 外部信息已具备基础能力）
- 三个 Domain OS 中 Life OS 至少有一个闭环能力（情绪/习惯/周报任一）
- 记忆系统 5 个 Phase 全部落地
- 版本机制跑通至少 2 个版本（v0.1.0 + v0.2.0），发布流程被验证过

---

## 六、版本历史

| 版本 | 发布日期 | 内容 | Release Notes |
|:-----|:---------|:-----|:--------------|
| v0.1.0 | 2026-08-XX（待发布）| 见 §四 | `docs/releases/v0.1.0.md` |

> 历史版本规划滚动保留在此表；详细内容看对应 Release Notes。

---

## 七、关联文档

| 文档 | 角色 | 位置 |
|:-----|:-----|:-----|
| VISION.md | **为什么**——理念、五层架构原则 | `docs/VISION.md` |
| product-architecture.md | **是什么**——五层架构详解 | `docs/architecture/product-architecture.md` |
| 本文件 | **去哪里**——版本蓝图（唯一路线）| `docs/architecture/product-roadmap.md` |
| task-log.md | **做什么**——任务拆分（从路线拆出）| `docs/reference/task-log.md` |
| issue-log.md | **遇到什么**——问题记录 | `docs/reference/issue-log.md` |
| rfc/ | **怎么决定**——决策记录 | `docs/rfc/` |
| REVIEW.md | **质量如何**——审核状态 | `docs/review/REVIEW.md` |
| api-spec.md | **契约是什么**——接口真相源 | `docs/architecture/api-spec.md` |
| releases/ | **发布过什么**——Release Notes | `docs/releases/` |
