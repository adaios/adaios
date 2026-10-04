---
title: Layer 6 知识反哺闭环
date: 2026-07-25
status: implemented
---

## 现状

Layer 6 的前半段已打通：

```
✅ 交易记录 (POST /api/v1/trading/trades)
✅ AI 理解 (Memory 系统)
✅ 持仓注入 (TradingContextContributor → AI 上下文)
✅ os/trading-os/ 知识资产 (87 课已处理，11-context/ 交付层就绪)
```

**断点：** `os/trading-os/11-context/` 已就绪（identity / strategy / rules / mistakes / current），但 adai-core 没有任何代码读取它。知识存在但 AI 看不到。

```
❌ 知识召回 — Context Engine 不读 11-context/
❌ DECISION 意图 — 代码已写但路由死代码
❌ 复盘沉淀 — data/trading/reviews/ 空目录
❌ 知识反哺 — reviews → os/trading-os/ 无管道
```

---

## 方案

### Phase 1: Knowledge 骨架 + 知识召回

Phase 1 做两件事：**定义 Knowledge 作为 Kernel 组件的接口**，**用第一个实现验证它**。

#### 1a. Knowledge 骨架

```
kernel/knowledge/
    KnowledgeSource.java       ← 接口（新增）
    TradingKnowledgeSource.java ← 第一个实现（新增）
```

**接口定义：**

```java
// kernel/knowledge/KnowledgeSource.java
public interface KnowledgeSource {
    /** 知识源标识，如 "trading" */
    String name();

    /** 始终注入的摘要（1-2KB），如系统身份声明 */
    String globalContext();

    /** 按场景注入的知识块 */
    String enrich(String scene);
}
```

**与 ContextContributor 的关系：**

```
KnowledgeSource          ContextContributor
──────────────          ──────────────────
回答"我知道了什么"      回答"现在正在发生什么"
静态知识资产             动态运行时上下文
例：交易规则、战法        例：当前持仓、活跃市值
↓                       ↓
    Context Engine 统一组合 → AI prompt
```

两者不互相依赖，各自独立被 Context Engine 自动发现（`List<KnowledgeSource>` + `List<ContextContributor>`）。

#### 1b. TradingKnowledgeSource（第一个实现）

读取 `os/trading-os/11-context/`，注入 AI 上下文。

```
os/trading-os/11-context/
    identity.md   --→  TradingKnowledgeSource
    strategy.md   --→     读取 + 文件时间戳缓存
    rules.md      --→         ↓
    mistakes.md   --→    Context Engine.buildPrompt()
    current.md    --→         ↓
                           AI prompt
```

**注入策略：**

| 方法 | 注入内容 | 体积 | 时机 |
|:-----|:---------|:----|:-----|
| `globalContext()` | `identity.md` | ~2KB | 每次请求 |
| `enrich("trading")` | `identity.md` + `strategy.md` + `rules.md` + `mistakes.md` + `current.md` | ~50KB | trading / decision 场景 |
| `enrich(其他)` | `identity.md` | ~2KB | 通用场景（让 AI 知道交易系统存在） |

120 条规则全量注入，不做语义检索 — 50KB 在 LLM 上下文窗口内完全可承受。

**Context Engine 改动点：**

`buildPrompt()` 中，在 memory 和 domain context 之间插入 knowledge：

```
identityRef → cardContext → relatedRecords → memorySummary
    → knowledgeSnippets ← NEW
    → globalContext → domainContext → currentRecord
```

**改动清单：**

| 文件 | 改动 | 说明 |
|:-----|:-----|:------|
| **新建** `kernel/knowledge/KnowledgeSource.java` | 接口定义 | `name()` + `globalContext()` + `enrich(scene)` |
| **新建** `kernel/knowledge/TradingKnowledgeSource.java` | 实现 | 读 `os/trading-os/11-context/`，文件时间戳缓存 |
| **修改** `kernel/context/engine/ContextEngine.java` | 注入 `List<KnowledgeSource>`，在 `buildPrompt()` 中调用 | 自动发现所有实现 |
| **修改** `domain/trading/TradingContextContributor.java` | **不碰** | 继续负责持仓注入，知识走 KnowledgeSource 通道 |
| 配置 | 新增 `adai.knowledge.trading-os-path` | 默认 `../os/trading-os/11-context/` |

**为什么不让 TradingContextContributor 读知识？** 职责不同。Contributor 回答"当前持仓多少"，Knowledge 回答"交易规则是什么"。分开后 Life OS 的 KnowledgeSource 不需要碰任何 Contributor 代码。

### Phase 2: DECISION 意图激活

**核心思路：** 让"该不该加仓 / 要不要止损"这类交易决策问题走专门的 DECISION 流程，与普通 QUESTION 分离。

```
用户："立昂微现在该不该加仓？"
  ↓
IntentRecognizer → DECISION（AI 识别 + 正则兜底）
  ↓
RecordController → handleDecision()
  ↓
ContextEngine.compose("decision", record) → 注入 trading 场景知识
  ↓
AI 回答（含交易规则约束 + 持仓感知 + 风险提示）
  ↓
Memory 沉淀
```

**改动清单：**

| 文件 | 改动 | 说明 |
|:-----|:-----|:------|
| **修改** `kernel/context/IntentRecognizer.java` | `Intent` 枚举加 `DECISION` | 已有 QUESTION/STATEMENT，加第三个 |
| **修改** `kernel/context/IntentRecognizer.java` | `recognizeWithAi()` 返回 "decision" 时映射为 `Intent.DECISION` | 目前映射为 QUESTION（bug） |
| **修改** `kernel/context/IntentRecognizer.java` | `recognize()` 加 DECISION 正则 | 匹配"该不该/要不要/能不能/应不应该/是否该"等决策句 |
| **修改** `interfaces/RecordController.java` | `createRecord()` 加 `case DECISION` 路由 | 路由到决策流程 |
| **新建** `application/DecisionAppService.java` | 决策专用服务：Context("decision") → AI 回答 | 或扩展现有 `RecordFlowAppService.processDecision()` |
| **修改** `interfaces/RecordController.java` | 新增 `DecisionResponse` DTO | 返回 decision 意图 + 分析 + 建议 |
| **修改** `api-spec.md` | 新增 DECISION intent 文档 | |

**DECISION vs QUESTION 的区别：**

| | QUESTION | DECISION |
|:----|:---------|:---------|
| 意图特征 | 信息查询 | 行动决策 |
| 场景 | 通用 | 强制 trading |
| Context | 通用领域上下文 | 强制注入 trading 知识 + 持仓 |
| AI 引导 | "回答用户问题" | "作为交易系统，分析用户决策，不荐股但给出分析框架" |
| 前端渲染 | ask 卡片 | 决策卡片（MVP 复用 ask 样式） |

### Phase 3: 复盘自动沉淀

**核心思路：** 提供触发式复盘 — 用户说"帮我复盘今天的交易"或当日首次打开时，AI 基于当天交易记录 + 持仓变化生成复盘笔记。

```
触发条件：
  - 用户主动请求："复盘"
  - 每日首次 brief 请求时检测：今日有交易记录 → 建议复盘
        ↓
POST /api/v1/trading/review?date=2026-07-25
        ↓
扫描：当日交易记录 + 持仓变化 + 当日 records
        ↓
Context Engine (trading 场景 + memory 回读)
        ↓
AI 生成复盘笔记 → data/trading/reviews/2026-07-25_review.md
        ↓
内容模板：
  ## 今日交易复盘
  ### 1. 交易执行情况
  ### 2. 持仓变化
  ### 3. 与系统规则对照
  ### 4. 今日教训
  ### 5. 明日关注
```

**改动清单：**

| 文件 | 改动 | 说明 |
|:-----|:-----|:------|
| **新建** `application/TradingReviewAppService.java` | 复盘生成服务 | 编排：收集数据 → Context Engine → AI → 写文件 |
| **修改** `interfaces/TradingController.java` | 加 `POST /api/v1/trading/review` | 触发复盘 |
| **修改** `interfaces/TradingController.java` | 加 `GET /api/v1/trading/reviews` | 列表 |
| **新建** `infrastructure/storage/TradingReviewFileRepository.java` | 读写 `data/trading/reviews/` | |
| **修改** `BriefAppService.java` | 检测今日有交易 → brief 中加"今日有交易，要复盘吗？" | |

### Phase 4: 知识反哺管道（最小可用）

**核心思路：** 尊重 VISION.md 和 trading-os CLAUDE.md 的约定 — `os/trading-os/` 不接受 adai-core 自动写入。知识入库是**手动审核**过程。adai-core 的角色是：**生成候选内容 + 标记矛盾 → 用户审核 → 手动入库**。

```
data/trading/reviews/2026-07-25_review.md  (AI 生成)
        ↓
用户审核 (在 App 中查看、编辑)
        ↓
用户标记："这条经验值得入库"
        ↓
生成入库候选 → os/trading-os/99-inbox/review-2026-07-25.md
        ↓
用户在 trading-os 工作焦点下审核 → 归入正式目录
```

**改动清单：**

| 文件 | 改动 | 说明 |
|:-----|:-----|:------|
| **新建** `POST /api/v1/trading/reviews/{date}/promote` | 将复盘笔记中的指定经验提升为入库候选 | 写入 `os/trading-os/99-inbox/` |
| **新建** `GET /api/v1/trading/knowledge/conflicts` | 检测 rules 与当前持仓操作的矛盾 | 读 `11-context/rules.md` + 对比交易记录 |
| **修改** `apps/adai-app/` | 复盘查看页面（简单 Markdown 渲染） | 前端展示复盘内容 |

---

## 实施顺序

```
Phase 1 (Knowledge 骨架 + 知识召回) → 3-5 天
    ├── 1a. KnowledgeSource 接口 + Context Engine 集成
    └── 1b. TradingKnowledgeSource 实现 + 测试
    ↓   最小可行闭环：AI 能看见交易系统
Phase 2 (DECISION 激活) → 1-2 天
    ↓   决策问题走专门流程
Phase 3 (复盘沉淀) → 2-3 天
    ↓   自动生成复盘笔记
Phase 4 (知识反哺) → 1-2 天
        手动审核管道
```

**总体工期：** 约 1-2 周（按业余开发节奏）

---

## 不做的

- **不做实时市场数据接入**（那是 Layer 5，不是 Layer 6）
- **不做自动写入 `os/trading-os/`**（违反 File First + 手动审核原则）
- **不做复杂语义检索**（120 条规则全量注入在上下文窗口内完全可承受）
- **不做前端 DECISION 卡片差异化 UI**（MVP 阶段用现有 ask 卡片样式即可，Phase 2 只改后端路由 + prompt）
