---
title: 接线盘点——零件齐了没插电（数据/能力已就位但未接到消费方）
description: 2026-09-17 只读盘点——系统扫描「已生产但未回流 AI 生成」「生成绕过记忆注入」「能力齐但触发理由缺失」「记忆资产无出口」「方法零消费」五类断点，逐条附文件行号证据与可复核方法
version: 1
created: 2026-09-17
updated: 2026-09-17
status: active
lines: 192
depends-on: []
related:
  - ../../ideas/20260916-plugin-and-cold-start-discussion.md
  - ../REVIEW.md
tags: [audit, wiring, memory, plugin, read-only]
---

# 接线盘点——零件齐了没插电

> **性质**：只读盘点（audit）。**未改任何代码/文件**，仅做静态扫描与阅读（遵守 AGENTS.md 规则 7）。
> **时间**：2026-09-17 00:03–00:05（Asia/Shanghai）· 代码基线：`adai-core` 后端 1935 测试 / 153 端点（`status.md` 快照）。
> **怎么来的**：讨论中发现两处「管道全有、中间断了一截」的同构现象（推送 / 上下文），遂做全仓系统扫描——**凡「生产方存在、消费方缺失或仅展示」者，逐条列证**。
> **姊妹文档**：`../../ideas/20260916-plugin-and-cold-start-discussion.md`（产品讨论沉淀）。
> **⚠️ 阅读约定**：本文只报告，**不代表已批准修复**；每条均给出可复核命令，读者可自行验证。

---

## 一、结论摘要

扫出 **5 类断点、共 11 条**（另 2 条已登记同类项见 §六）。它们不是散落的疏漏，而是**同一个系统性模式**：

| 类 | 断点本质 | 条数 | 一句话影响 |
|:--:|:---------|:----:|:-----------|
| **A** | 数据生产了，但只用于展示，**不回流 AI 生成** | 2 | 阿呆「知道」你的偏好，却不在生成时使用 |
| **B** | 生成场景**绕过记忆注入** | 1（2 处调用） | 阿呆给你做卡片时，**不知道你是谁** |
| **C** | 能力齐了，**触发理由缺失** | 2 | 会为行情找你 9 次，不会为你的事找你 1 次 |
| **D** | 记忆资产**无用户出口** | 4 | 机制在跑，用户感知不到 |
| **E** | 方法**零消费**（死代码） | 2 | 写了没人用 |

> **模式诊断（本文最重要的判断）**：
> **阿呆几乎不缺能力，缺的是「接线」。** 生产端与消费端各自都建好了，中间那根线没接——而「基于对你的了解」要成立，前提正是这根线。

---

## 二、盘点方法（可复核）

| 步骤 | 做法 | 命令（工作目录 `services/adai-core/src/main/java/com/adaiadai/core`） |
|:----:|:-----|:-------------------------------------------------------------------|
| 1 | 找「有位置但传空」的上下文组装点 | `grep -rn "ContextPackage.simple" --include=*.java .` + 读 `ContextPackage.java:65` |
| 2 | 统计核心读取方法的消费方数量 | `for m in <方法名>; do grep -rn "\.$m(" --include=*.java .; done` |
| 3 | 找「能主动找用户」的触发点 | `grep -rEn "^[[:space:]]*@Scheduled\(" --include=*.java .` |
| 4 | 查记忆字段的前端消费 | `grep -rn "topic\|superseded\|evolvedTo\|lastConfirmed\|sentiment" apps/adai-{web,app}/lib --include=*.dart` |

---

## 三、A 类：数据生产了，但不回流 AI 生成

### A1 · 用户偏好提取后只进展示页，不进任何 prompt

- **证据**：`MemoryService.findAllPreferences`（`kernel/memory/MemoryService.java:346/353`）全仓唯一消费方 = `interfaces/MemoryController.java:79`（`GET /memory/insights` 聚合返回，供档案页展示）。
- **复核**：`grep -rn "findAllPreferences" --include=*.java .` → 仅 1 处业务调用。
- **影响**：阿呆把用户的偏好**提炼并存了下来**（`MemoryPreference` 带 `confidence`），但**生成内容时不读它**。「越用越懂你」在**展示侧**成立，在**生成侧**完全不成立。
- **关联**：讨论文档 §7.2；这是「表征适配」缺的地基。

### A2 · 行为模式（patterns）同上

- **证据**：`MemoryService.findAllPatterns`（`MemoryService.java:299/314`）唯一消费方 = `MemoryController.java:78`（同一 insights 端点）。
- **影响**：同 A1。patterns 是「AI 真的在观察你」的核心资产，目前**只能被看，不能被用**。

---

## 四、B 类：生成场景绕过记忆注入

### B1 · learn 卡片生成不注入用户上下文（含 userId 也未使用）

- **证据**（`application/LearnDigestAppService.java`）：

```java
// :905  方法签名收了 userId
public LearnCard digest(String userId, String content, String typeHint, ...) {
    // :913  但 prompt 构造函数没有 userId / 无偏好 / 无记忆
    String userPrompt = buildDigestPrompt(content, platform, author, url, published, typeHint,
            existingTopics(userId));          // ← 只用了「已有主题目录」
    // :915  记忆位与知识位硬编码为空
    ContextPackage ctx = ContextPackage.simple(
            "learn", null, "学习消化", userPrompt, List.of(), userPrompt);
```

- **根因**：`ContextPackage.simple(...)`（`kernel/context/engine/ContextPackage.java:65-74`）把**记忆位与知识位硬编码为 `List.of()`**——即「轻量上下文」，构造上就不带记忆。
- **同样的第二处**：`LearnDigestAppService.java:1030`（`repages` 历史卡重排）同样用 `simple(...)`。
- **影响**：**阿呆给用户做卡片时不知道「你是谁」。** 炒股老手与刚入门者拿到同一套 prompt 生成的同一张卡——与「用你喜欢的方式展示」直接冲突。
- **⚠️ 必须区分（避免误伤）**：`simple()` **本身不是缺陷**。它在「不需要记忆」的场景是正当用法——例如 `ConversationController.java:74`（`POST /conversations/end` 对话总结，只需对话原文）。**断点仅在于：把它用于生成「用户长期保存的产物」。**
- **对照（正例）**：问答主链路走的是 `contextEngine.compose(...)`，**记忆注入是通的**——`QuestionAppService.java:93/117` → `ContextEngine.loadMemorySummary`（`:338-375`，含 `recentActive` + `recentMemories` + `touchActive`）。**同类能力已有正确实现，learn 只是没接。**

---

## 五、C 类：能力齐了，触发理由缺失

### C1 · 11 个定时任务，**0 个**来自用户自己

- **精确清单**（`grep -rEn "^[[:space:]]*@Scheduled\(" --include=*.java .` → **11 条**）：

| # | 位置 | 用途 | 归属 |
|:-:|:-----|:-----|:-----|
| 1 | `TradingSessionPushService:213` | 早盘计划 | 阿呆业务 |
| 2 | `TradingSessionPushService:225` | 午间跟踪 | 阿呆业务 |
| 3 | `TradingSessionPushService:237` | 尾盘建议 | 阿呆业务 |
| 4 | `TradingSessionPushService:256` | 收盘小结 | 阿呆业务 |
| 5 | `TradingSessionPushService:285` | 收盘更新 | 阿呆业务 |
| 6 | `TradingSessionPushService:413` | 当日成交确认 | 阿呆业务 |
| 7 | `TradingSessionPushService:427` | 买点提醒 | 阿呆业务 |
| 8 | `LearnReviewPushService:87` | learn 复习提醒（满 7 天） | 阿呆业务 |
| 9 | `MarketAlertService:117` | 行情异动轮询 | 阿呆业务 |
| 10 | `CaseVerifyBackfillScheduler:54` | 交易用例回填 | 内部维护 |
| 11 | `RecordRetryService:73` | 记录重试 | 内部维护 |

- **判定**：**9 条推送全部是「阿呆的业务」**（交易/学习/行情），2 条是内部维护。**没有一条的触发理由是「用户自己托付的事」。**
- **对照**：推送基础设施**全部就位**——`PushChannel` 渠道插件化（feed/bark/apns/wechat）、APNs 已打通（`ApnsPushChannel`，.p8/ES256、环境随 token 存）、`SchedulingConfig` 调度器在跑。
- **影响**：**阿呆会为行情主动找你 9 次，却不会为你明天要交房租找你 1 次。**
- **关联**：讨论文档 §5.1「托付 → 履约 → 信任」——新用户依赖的**唯一缺口就在这一条**。

### C2 · 用户显式托付无落点（`actionable` 只进 Feed 待办，无时间、无主动送达）

- **证据**：
  - `MemoryService.findPendingActions` 唯一消费方 = `FeedAppService.java:153`（把 actionable 记忆**并入 Feed 时间轴**）；
  - `RecordToTaskLinker.java:18` 的 actionable → 任务转派**受 project 插件门控**（新用户 `plugins=[]` 走不通）；
  - `Memory` 记录**无「提醒时间」字段**（见 `kernel/memory/Memory.java` 字段表：`doneAt`/`lastConfirmed`/`topic` 有，**无 remind-at**）。
- **影响**：用户说「明天提醒我 X」→ AI 理解 → 进 Feed 列表 → **要用户自己打开 App 翻**，且系统不认识「明天」。**「托付」的履约环节缺失。**

---

## 六、D 类：记忆资产无用户出口

> 后端机制完整（Phase 1–5），用户侧只有一个「**按天翻条目的日志浏览器**」（`getMemoryDates` → `getMemory(date)`）。

| # | 资产 | 后端状态 | 前端消费实测 | 缺口 |
|:-:|:-----|:--------:|:-------------|:-----|
| D1 | `Memory.topic`（同话题归并，Phase 2） | ✅ 有 | ❌ **零**（前端 `topic` 全部属于 learn 卡，非记忆） | 主题聚合视图不存在 |
| D2 | `Memory.evolvedTo`（演变链指针，Phase 2） | ✅ 有 | ❌ **零** | 只有 `superseded` 的「划线」弱出口（`memory_page.dart:191/223`）→ 用户看到「这条过时了」，**看不到「那现在是什么」** |
| D3 | `Memory.lastConfirmed`（时效衰减依据，Phase 4） | ✅ 有 | ❌ **零** | 衰减机制在跑，用户不知道记忆在变淡 |
| D4 | `Memory.sentiment` | ✅ 有 | ⚠️ 弱：`adai-app/lib/pages/memory_page.dart:290` 仅列表图标；**web 零** | 无趋势、无聚合 |

- **复核**：`grep -rn "evolvedTo\|lastConfirmed\|\.topic" apps/adai-web/lib apps/adai-app/lib --include=*.dart`
- **注**：`patterns`/`preferences` 已于 2026-09-16 接上档案页（`RFC 20260916`），是**唯一**已接通出口的记忆资产。

---

## 七、E 类：方法零消费

| # | 方法 | 调用点 | 说明 |
|:-:|:-----|:------:|:-----|
| E1 | `MemoryService.findByKind`（`:430`） | **0** | 按 kind 查记忆——无任何生产调用 |
| E2 | `MemoryService.hasRealMemory`（`:410`） | **0** | 判定是否真实记忆——无任何生产调用 |

- **复核**：`grep -rn "\.findByKind(\|\.hasRealMemory(" --include=*.java .` → 排除自身后均为 0。
- **说明**：二者也可能是**为测试/未来预留**的 API，本盘点**不断言应删**，仅标记「当前无消费方」。

---

## 八、已登记的同类项（不在本次新增，引用 REVIEW）

| 编号 | 内容 | 位置 |
|:-----|:-----|:-----|
| `P2-认知3` | profile 相关 5 个新端点**零前端入口**（用户拍板 C：先对味试点再排 UI） | `docs/review/REVIEW.md` |
| `P2-learn28` | 「有字幕视频」真链无法验证（B站字幕接口限制，非代码问题） | 同上 |

---

## 九、总结与建议顺序

**一句话**：

> 生产端与消费端各自建好了，**中间那根线没接**。这批断点的共同修复动作不是「加功能」，是「接线」。

**建议顺序**（按杠杆 / 成本比，**均需用户拍板后另行开工**）：

| 序 | 动作 | 对应断点 | 成本 | 杠杆 |
|:-:|:-----|:---------|:----:|:-----|
| 1 | **偏好回流生成**：把 `findAllPreferences`/`findAllPatterns` 接入生成链路（learn 优先） | A1/A2/B1 | 低 | ⭐⭐⭐ 直接兑现「基于对你的了解」 |
| 2 | **托付式提醒**：per-user 提醒（新增 remind-at）+ 到点主动送达 | C1/C2 | 中 | ⭐⭐⭐ 新用户依赖的唯一缺口 |
| 3 | **记忆出口**：主题线（`topic`）+ 演变链闭环（`evolvedTo`） | D1/D2 | 低 | ⭐⭐ 零新增数据，纯读取 |
| 4 | 确认日常化（`lastConfirmed`）/ 情绪趋势（`sentiment`） | D3/D4 | 低 | ⭐⭐ |
| 5 | 清理或启用零消费方法 | E1/E2 | 低 | ⭐ |

**⚠️ 与 `REVIEW.md` 的关系**：本盘点**不替代** REVIEW 的缺陷登记——§八 已登记项不重复记；§三~§七 若用户拍板修复，应按 `/ship` 流程登记 REVIEW 并入 change-log。

---

## 十、边界与未验证项（诚实声明）

- **本文为静态扫描**：未运行应用、未做运行时验证（如通过 AOP/反射的隐式调用、配置驱动的调用）；
- **未覆盖前端零入口的穷尽扫描**：D 类仅覆盖记忆字段，未对所有 153 端点做「端点 ↔ 前端消费」全量对拍（属 `guard-align` 范畴）；
- **不断言「应该修」**：A/B 类属**设计取舍**范畴（如 `simple()` 的轻量定位是合理设计），本文只报告「现状与产品目标不一致」之处，**是否修由用户拍板**；
- **未评估修复成本**：§九 的成本列为粗略判断，落实前需另行估算。
