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

---
**追加方式**：新发现知识/数据问题 → 追加一行，注明日期。
