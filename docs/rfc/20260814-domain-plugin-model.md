---
title: Domain=插件模型（Kernel 基础服务 / Domain 受控插件）
date: 2026-08-14
status: approved
decided-by: adai（2026-08-14 会话，四决策 + 载体选型）
reviewed: 高级产品经理审查（2026-08-14，有条件通过，修订已并入 §四/§五/§八/§九）
revised: 2026-08-14（domain 判定随插件门控——新增 D5 + §四/§五 更新）
---

# Domain=插件模型

> **方向拨正 RFC**：当前实现把 adai 的个人知识（`os/trading-os` 87课/规则）当"产品公共内容"注入**所有用户**上下文。
> 与 VISION §4「Knowledge = 你的知识体系（逐步构建）」相悖。本 RFC 定模型 + 门控方案，作为多用户开发基线。

---

## 一、目标模型

```
Kernel（OS 内核）  = 人人平等、常驻：记录/问答/记忆/档案/时间线/搜索/待办
Domain（插件）     = adai 所有、受控开放：trading / project
  ├── 知识资产    = 插件的"内容"（os/trading-os、os/project-os = adai 写的）
  ├── 能力注入    = KnowledgeSource + ContextContributor
  ├── 界面模块    = 前端功能页（交易页、项目仪表盘）
  └── 数据空间    = 启用用户的私有数据（data/{userId}/…）
```

**关键心智**：插件的代码+内容共享一份（adai 写），但每个启用用户的**数据各自独立**（"装了一个 App，数据各归各"）。**"控制开发给其他用户" = 给账号启用插件。**

Life 非门控插件：无 adai 私有所知内容，上下文本就是 per-user 记忆聚合 → 常驻。

## 二、决策（adai 2026-08-14 定）

| # | 决策 | 内容 |
|:-:|:-----|:-----|
| D1 | 自动转待办 **通用化** | R2 `RecordToTaskLinker` 去掉 `domain=project` 限制——**任何用户**的可执行记录（AI `actionable` + 排除 `#备忘/#想法`）都转成自己的待办。待办 = Kernel 基础能力。**误转保护**：AI `actionable` 判定有质量风险（07-23 空 actionable 记忆事故），通用化后从 project 域扩到全域需兜底——非空摘要才转（判定空/无内容跳过），空 actionable 历史过滤沿用既有机制；"确认后入待办"作为第二步可选开关 |
| D2 | 插件所有权 | trading / project 两插件**均属 adai**。启用前不注入其知识、不显示其模块 |
| D3 | 插件启用载体 | **`accounts.json` 每账号加 `plugins: []`**（见 §三），后台（adai-admin）管理 |
| D4 | 插件门控 ≠ 鉴权 | 门控管"能看什么功能"；#179 登录鉴权管"能读谁的数据"，两件事。鉴权 v1.0.1 单独做 |
| D5 | **domain 判定随插件门控** | `detectDomainScene` + AI understanding 的 domain 只在本用户**已启用插件**间判定；无插件用户 → 一律 life（单一 domain，无需判定）。trading/project 与插件一一对应——未启用即不分类到该域 |

## 三、载体选型（D3 落定：accounts.json `plugins` 字段）

对比过"独立插件注册表"与"accounts.json 加字段"，选后者：

1. **后台管理面最少**：`PATCH /accounts/{userId}` 已存在（`AccountController.java:82`，`UpdateAccountRequest` 现含 enabled/role）——加一个 `plugins` 字段即可；adai-admin 已有账号管理页，表单加"插件开关"即成完整后台。独立注册表要多建一套管理端点 + 页面。
2. **唯一真相源**："这个用户能用什么" = 读账号的 plugins 列表，一处拿全，无需 join。
3. **向后兼容**：老账号无该字段 = 空列表 = 无插件——**恰好符合"新用户默认什么都没有"**。adai 账号 seed `["trading","project"]`。

插件**目录**不需要 admin 可编辑载体：插件构建期已知（`KnowledgeSource.name()` 已返回 trading/project/life），所有权用配置/常量声明。动态插件目录、动态加载属过度设计，不做。

## 四、门控实现（文件级）

```
后端注入门控：
  Account 记录                 + List<String> plugins（默认空）
  AccountFileRepository        seed adai = ["trading","project"]；序列化 plugins
  AccountController            UpdateAccountRequest + plugins；GET 返回账号 plugins
  PluginRegistry（新增）        KnowledgeSource.name() / Contributor → 插件名映射 + owner
  ContextEngine                组装时按用户 enabledPlugins 过滤 KnowledgeSource/Contributor + detectDomainScene 只在本用户启用插件间判定（D5）
  RecordToTaskLinker           link() 去掉 "project".equals(domain) 门槛（D1）
前端模块门控：
  新端点 GET /api/v1/me/plugins   返回当前用户启用插件
  adai-app/adai-web            交易页、项目仪表盘按插件列表显隐；基础 7 项常驻
后台控制面：
  adai-admin 账号管理           编辑表单加 trading/project 插件开关（调 PATCH）
```

**门控边界（用户可见内容全通道，PM 审查修订）**：

| 数据通道 | 新用户（无插件）| 启用用户（如 adai）|
|:---------|:-------------|:-----------------|
| 知识注入 | 不注入任何 `os/` 知识 | 注入已启用插件的 11-context |
| domain 判定（会话卡片）| 无插件 → 一律 life，不判定 | 在已启用插件间按内容判定（trading↔trading、project↔project）|
| Feed 构造 | **不注入 `type=market`**（大盘行情卡）；`type=push` 空持仓自然无 | 注入行情卡 + 持仓推送 |
| 搜索 | 只搜自己的记录（SearchService 不搜 `os/` 知识）| 同左 |
| 页面模块 | 基础 7 项；无交易页/项目仪表盘 | 插件模块显隐 |
| 记忆/时间线 | 派生自自己记录；自己记的交易内容属他，可看 | 同左 |
| 交易闭环端点 | 不暴露（持仓/复盘/反哺归 trading 插件）| 提供 |

> 门控发生在**注入点与 Feed 构造点**，不依赖前端隐藏——新用户即使 URL 直连交易接口也拿不到共享知识；但用户**自己**的记录/记忆永远可读（数据属于他，与知识注入严格区分）。

## 五、新用户（无插件）服务面

- **有**：记录 / 问答 / 记忆 / 档案 / 时间线 / 搜索 / 待办（7 项，全部空态 + 引导）
- 记录不判 domain：无插件用户一律 life/默认，无 trading/project 概念（D5）
- **无**：交易页、项目仪表盘、`os/` 任何知识注入、adai 的持仓/复盘/行情推送
- 简报：空态欢迎语，不掺行情

> **首次体验（开放产品问题，显式标注）**：空态 7 项能否留住新用户，属多用户开放策略（非本 RFC 范围），但需独立设计 onboarding/首次价值——记录是高门槛习惯，**门控完成 ≠ 多用户产品完成**。

## 六、边界与连带影响

- **promote 反哺**（写 `os/trading-os/99-inbox/`）：插件作者（adai）的收敛动作；开放给其他用户前，是否开放"反哺"是插件能力开关，默认关闭。
- **MarketContextContributor**：大盘行情/持仓上下文归 trading 插件门控（新用户不注入），避免"基础面带行情"。
- **现有账号**：`alice`（accounts.json 中已启用）无 plugins 字段 → 默认空 → 得到 7 项基础，符合方向；无需迁移。
- **数据不变**：`data/{userId}/` 结构不动，门控只发生在注入/展示层。

## 七、非目标（v1.0.1+）

- #179 登录鉴权（账号+口令+token）
- 插件动态加载 / 目录 admin 编辑 / 应用市场
- "产品内容层"（为所有用户写的通用知识插件）——将来 adai 另写一个开放插件即得，无需单独机制

## 八、实施阶段（两步走）

**第一步 · 最小闭环（单用户即有价值，建议先行）**
- R2 自动转待办通用化（D1）+ 误转保护
- `KnowledgeSource` 注入按 owner 过滤（adai 专属，一行归属判断，拨正"知识注入所有用户"的错位）
- 前端 fallback 修复（无 userId → 首屏选号，不再回退 default）

**第二步 · 插件系统（等第一个真实第二用户或确认开放再铺开）**
- Account.plugins 载体 + PluginRegistry + ContextEngine 全量过滤
- `GET /me/plugins` + adai-app/adai-web 模块门控 + adai-admin 插件开关
- 多用户插件隔离测试（见 §九）

## 九、审查补充（高级产品经理审查修订，P2 非阻塞）

| 项 | 说明 |
|:---|:-----|
| 授权链/用户可见性 | "控制开发"当前 = 后台开关；用户侧是否知道/能申请插件未定——先纯后台，用户可见性策略留待多用户开放时设计 |
| 验收标准 | 多用户插件隔离测试：新用户无知识注入 / 启用后有注入 / 数据不串 / promote 仅作者 / Feed 无行情卡；参照 #177 多账号切换链路测试做法 |
| accounts.json 膨胀预留 | 插件级 per-user 配置（如 A 股/美股视图）将来归插件自身数据空间，不塞账号文件 |
| VISION 五层对齐 | L1/L3/L4 = Kernel 底座（人人有）；L6 交易闭环 + 领域知识 = 插件驱动；本 RFC 与五层架构一致 |
