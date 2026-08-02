# 文档审核检查点清单

> 格式：`[检查方法]` — 检查什么。`上次发现` 记录历史命中。新发现模式追加到底部。

## 契约真相源

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| D1 | 每个 Controller 端点 ↔ `docs/architecture/api-spec.md` ↔ 前端调用 三方对齐 | api-spec 缺 7 个端点（P1，已修 v3.1）|
| D2 | `docs/README.md` 索引指向的文件都存在；新增文档是否登记入口 | — |

## 架构图与代码一致

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| D3 | 根 CLAUDE.md 架构图声明的类/组件是否真实存在 | CLAUDE.md 描述过期（DECISION/正则兜底/B Phase4 待做）（P2，已修）|
| D4 | CLAUDE.md「当前焦点」状态表 vs REVIEW.md 是否同步 | — |

## RFC 决策漂移

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| D5 | 新增 RFC 声称的决策 vs 其后代码实现是否一致；RFC 里 `status: draft` 是否被遗忘未更新；文件结构节/角色数/报告路径是否与实现一致 | RFC 20260801-review-skill 未随落地滚动（draft/4角色/报告路径旧）（P2 #35）|

## 文档资产健康

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| D6 | `docs/` 下是否有孤儿/过期/重复文档；`docs/reference/issue-log.md` 是否持续更新 | issue-log 唯一在 reference/（P2 #38）|
| D7 | `data/identity/profile.sample.md` 等 git 追踪的 sample 与真实文件格式是否一致（隐私文件不进 git）| 缺 identity/trading 目录（P2，已修）|
| D8 | **skill/agent 自身可执行性**：SKILL.md/agent 引用的 bash 命令、grep 路径必须在仓库实测可执行，不 silent-fail | ship SKILL grep 路径不存在 → api-spec 同步跳过（P1 #34）|
| D9 | **审核体系自审**：diff 触及 `.claude/**` 时，deep 必须派 review-docs 复核 skill/agent 的路径、清单引用、路由表覆盖一致性 | 路由表缺 `.claude/**` 无角色覆盖（战略 #33）|
| D10 | 文件合并/移动/删除后，grep 全库对旧路径的引用（排除 inbox/历史），确保无断链、无重复 | 2026-08-02 文档精简：inbox 归位 17 文件 + frontend-reference 合并 + data-flow 并入 system-arch；曾现 ai-native 双份/AI_CONTEXT 孤儿 |
| D11 | 新增子项目 CLAUDE.md 的运行参数（端口/构建命令）与 `scripts/serve_web.sh` 跨文档对齐 | adai-web CLAUDE.md `:8081` vs serve 脚本 `:8082`（P3，待修）|
| D12 | api-spec Response 示例字段名直接对照后端 record/@JsonGetter 序列化名 + 前端 DTO fromJson 读取 key，三方对拍 | adai-web `positionCount` 后端无此字段 + portfolio 示例 `totalMarketValue` 失真（P1，待修）|

---
**追加方式**：新发现文档类问题 → 追加一行，注明日期。
