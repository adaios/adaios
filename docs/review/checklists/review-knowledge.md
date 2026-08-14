# 知识/数据审核检查点清单

> 格式：`[检查方法]` — 检查什么。`上次发现` 记录历史命中。新发现模式追加到底部。对应 agent：`review-knowledge`。

## 知识资产消费链路（os/ → KnowledgeSource → Context）

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| K1 | `os/trading-os/` 有内容的文件 ↔ `TradingKnowledgeSource` 读取路径是否有效、内容真正进 context | 交易知识 73KB 从不注入（战略缺口，已修）|
| K2 | `os/life-os/11-context/identity.md` ↔ `LifeKnowledgeSource`（`adai.knowledge.life-os-path` 配置）| life-os-path 死配置（战略缺口，已修）|
| K3 | 每个 `os/*/` 是否被至少一个 `KnowledgeSource` 消费；新增 os 知识无消费方 = 战略缺口 | — |
| K4 | `os/*-os/definition/` 定义文档 ↔ `adai-core` 对应实现一致（概念/术语/工作流）| — |

## 数据资产健康（data/）

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| K5 | `data/` 各子目录：损坏文件、孤儿文件、测试残留、重复堆积 | records 混入 10+ 测试文件（P2，已修）|
| K6 | 文件格式 ↔ Repository 解析器一致（如 `project/tasks/` ↔ `ProjectFileRepository.ENTRY_PATTERN`）| 任务多行 title 写坏 07.md（P0，已修）|
| K7 | `data/identity/`、`data/trading/` 等目录存在；sample 文件（git 追踪）与真实文件格式一致 | 缺 identity/trading 目录（P2，已修）|
| K12 | `data/records/` 下"非 `rec_` 前缀且非 `cards/` 子目录"的 .md 视为孤儿 → 标记待迁移（对齐 CardMigrationService 扫描），迁移而非手动删除 | 3 个卡片孤立在 records/2026/07/22/，两份仓库读不到（P1 #16a）|
| K13 | `os/*-os/definition/` 声称能力 ↔ `domain.*` 包实际类逐项对照，防架构愿景文档与代码长期漂移 | trading/life definition 过度描述（P2 #39）|
| K14 | `os/` 目录 rename/重组后逐文件核对同级文档目录表是否同步（`git log --name-status` R 项 × 引用文件 grep）| trading-os/README 漏 definition/（P2 #40）|

## 隐私红线

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| K8 | `data/records/`、`data/memory/`、`data/trading/`、`data/identity/profile.md`、`data/index/` 在 `.gitignore`，git 只追踪 sample | — |
| K9 | 新增数据文件类型时是否同步 .gitignore 或故意提交 | — |

## 闭环

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| K10 | 知识反哺（复盘→promote→入库）是否有真实产物 | Layer 6 从未运转（P2 #23 待办）|
| K11 | 记忆沉淀是否随使用增长（`data/memory/` 有内容而非空壳）| 记忆稀疏（P2 #24 待办）|
| K15 | records 的 `summary:` 字段 JSON/多行泄漏（活跃 bug 复发检测）：扫描 `^summary: *\{` 或值含换行 | record summary 写 AI JSON（P1 #135，待修）|
| K16 | `records/cards/` 下同 id 双副本（raw + `card_` 前缀并存）| 3 对双副本读成 6 张卡（P1 #139，待修）|
| K17 | memory recordId ↔ records 文件交叉验证（悬空引用）| 唯一悬空 recordId（P2 #151，待修）|
| K18 | tags.json `count` vs `recordIds.length` 一致性（截断/通胀漂移）| 三体 count=64 vs 50（P2 #152，待修）|
| K19 | `os/*-os/11-context/README` 引用的目录存在性（防复制模板漂移）| life-os 引用不存在的 05-system/04-rules（P3，待修）|
| K20 | 数据用户层迁移/rename 后 grep 残留旧层路径引用（`data/default` 等）| default→adai 后契约/注释/默认 userId 全漂移（P1 #180）|
| K21 | 知识反哺入库候选抽检：引用的 R/E 编号真实存在于 11-context/rules.md|mistakes.md + 内容与 positions.md 一致 | 2026-08-09 9 条规则引用全部真实验证（战略 #178）|
| K22 | 自动生成入库候选的隐私一致性：含真实持仓/财务数据进 git 时与 `data/*/trading/` gitignore 策略对照 | 候选真实持仓进 git（P0 #184，2026-08-12 已修复：promote 脱敏）|
| K23 | 自动生成入库候选脱敏抽检：grep 真实持仓特征（`市值|现金余额|成本\d+|股`）命中且 git 追踪 = K8 红线复发；修复应落在生成源（TradingController.buildPromoteContent）而非事后改文件 | 2026-08-09 真实持仓进 git（P0 #184，2026-08-12 已修：sanitizeReviewContent 生成源脱敏）|
| K24 | os/ 自动生成文件名约定一致性：`99-inbox/` 下文件名匹配 `YYYY-MM-DD_主题.md`（根因在 adai-core 生成代码 `TradingController.java:154` 硬编码 review- 前缀）| 候选文件名不符约定（P1 #211，2026-08-12）|
| K25 | 子项目 CLAUDE.md 对 os/ 的只读声明 vs adai-core 实际写路径（promote→99-inbox）逐项对照，防「声明只读、实际写入」漂移 | adai-core CLAUDE.md:131 声称只读但 promote 写入（P2 #223，2026-08-12）|
| K26 | promote 脱敏后复查历史版本残留真实持仓（`git log --all --name-only -- "os/trading-os/99-inbox/*.md"` 逐版本 grep 持仓特征）；仓库若推送过远端需登记 rewrite 决策而非静默接受 | f3ca035 历史残留真实持仓（P1 #239，2026-08-12）|
| K27 | 自动生成候选改写标点后加全角括号配对检查（每行 `（` 与 `）` 计数相等），防半修引入未闭合括号 | R35) → R35 引入未闭合 `（`（P2 #241，2026-08-12）|
| K28 | 删除某主题记录后复查残留 + rebuild 复发：grep `data/{userId}/records/` + `memory/` 该主题关键词，确认 ①源记录全删（note/question/conversation 派生三类）②派生记忆已删 ③部署触发 rebuild 后不复发；删除操作自身也可能被沉淀为 actionable 待办（需一并清） | 2026-08-14 岗位调整残留——漏删源记录 rec_20260813_201151636 → memory rebuild 反复重新派生岗位记忆（"删了又出现"根因）+ 清理动作自身沉淀 actionable 待办 mem_20260814_102636776「删除岗位调整记录仍残留」出现在 Feed 顶部（已清）|

---
**追加方式**：新发现知识/数据问题 → 追加一行，注明日期。
