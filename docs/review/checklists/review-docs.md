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
| D13 | RFC frontmatter 一致性：`docs/rfc/*.md` 必须有 YAML frontmatter，缺则污染 `/project/status` rfcItems 状态 | 多模态 RFC 无 frontmatter → status=unknown（P1 #143，待修）|
| D14 | 外部模型名/版本号跨文档对拍：roadmap/VISION/CLAUDE.md 提及的外部模型与 `application.yml` 配置逐一对拍 | roadmap 仍写 GLM-4.6V-Flash（P1 #142，待修）|
| D15 | README 索引完整性脚本化：`for f in docs/rfc/*.md; do grep -q "$(basename $f .md)" docs/README.md || echo 未登记` | README 缺 3 篇 RFC（P1 #141，待修）|
| D16 | 「当前状态」类真相源联动：VISION §7 / product-architecture 状态表 / system-architecture §七 三处 ✅❌ 须与 CLAUDE.md 已完成清单同步 | 五层状态表过期（战略 #130，待修）|
| D17 | 数据用户层迁移/rename 后 grep 残留旧层路径引用（`data/default` 等）：冻结契约/代码注释/迁移脚本/前端默认 userId/CLAUDE.md 五处 | default→adai 迁移后 freeze/MarketAlert/前端默认值全残留（P1 #180）|
| D18 | api-spec 版本/变更记录强同步：diff 触及任一 § 内容必须升版 + 追加变更记录行 | 3 处改动未升版 v3.7（P3 #191）|
| D19 | Release Notes 发布日期门禁：只在用户确认 tag/部署后定稿，「验证通过」≠「已发布」| v1.0.0.md 过早定稿 2026-08-09（P3 #192）|
| D20 | 测试数三方对拍：REVIEW 已修复区行内测试数 vs CLAUDE.md 测试状态 vs 实测 @Test 计数 | 批 J 行 302 vs 实测 300（P3 #193）|
| D21 | 速查表反造假：前端参考 API 速查表每行——方法动词（GET/POST…）在后端 annotation 实测存在 + 返回类型在 api-spec/代码真实定义 + 前端 api_service 有对应调用 | frontend-reference 虚构 `GET /trading/trades` + TradeResponse（P1 #237，2026-08-12）|
| D22 | api-spec changelog 声称的每个行为变化必须反向 grep 对应 § 正文（状态码/字段表）确认已同步，升版不只记 changelog | v3.14 声明 413 但 § records/media 正文仍写 400（P1 #238，2026-08-12）|

---
**追加方式**：新发现文档类问题 → 追加一行，注明日期。
