---
title: learn V2 增量深审报告
description: learn V2 消化闭环 4 commit（d3b8f4a^..ff7451f）四官隔离并行审查 + 主会话事实核实
version: 1
created: 2026-09-07
updated: 2026-09-07
status: active
lines: 99
depends-on:
  - ../../../ai-engineering/frontmatter-spec.md
related:
  - ../REVIEW.md
tags: [ai, review, learn]
---

# learn V2 增量深审报告（2026-09-07）

## 审查概况

- **范围**：`d3b8f4a^..ff7451f`（learn V2 消化闭环 4 commit：批1 复习流转/编辑 d3b8f4a → 批3 trading 候选联动 0bcebfe → 批4 复习提醒推送 66b4baf → 前端接线 ff7451f），31 文件 +2389/−95
- **模式**：deep（review.md）· 四官隔离并行（材料按域裁剪、官间互不可见）+ 主会话独立核实
- **派官**：backend-reviewer / frontend-reviewer / docs-reviewer / adversarial-reviewer ×4 独立子代理
- **守护**：G1-G7 7 PASS / 0 HIT · META PASS · guard-align PASS（122 端点）
- **主会话核实**：learn 测试类 failures=0（LearnControllerTest 21 / CandidateAppService 22 / ReviewPush 8 / LearnCardFileRepo 29 等，1292 全绿对齐）；B3 隐私（candidates/ 落 `data/*/trading/` 已 ignore）✅

## 结论

**P0：无**（无数据丢失/安全漏洞；gitignore/原子写/per-user 条带锁/跨目录锁不冲突均核验通过）

**战略×2 + P1×4 + P2×11 + P3×3（合并去重后；docs 项并入 P2-docs 编号）**

## 交叉命中（材料隔离 → 独立证据加权）

| 命中 | 问题 | 证据来源 |
|:-:|:-----|:-----|
| ⭐⭐⭐ | learn_card_id 回链悬空指针 | 对抗 P1-3 + backend P2⑤ + 主会话代码实锤 |
| ⭐⭐ | 复习提醒计时口径失真 + 无冷却 | 对抗 P1-1 + backend 战略① + docs P2① |
| ⭐⭐ | learn-review 开关归属 trading 门控（纯 learn 用户不可自关）| 对抗 P1-1 + backend 战略② + docs P1③ |
| ⭐⭐ | title-only 寻址跨日同名歧义（老卡无法寻址/改错卡）| 对抗 P1-2 + backend P1 + 主会话 find() 实锤 |
| ⭐ | 前端写操作失败人话丢失（技术文本外露）| frontend P1 + 对抗前端细节 |
| ⭐ | 候选删除按文件序首命中 vs 列表最新语义不一致 | 对抗 P2-3 + backend P2⑥ |

## 未修复项清单（归口 REVIEW.md）

### 🏛 战略（口径/归属，需拍板）

- **S-learn1 ⭐⭐⭐ 复习提醒计时口径**：`LearnReviewPushService.staleReviewCards` 用 **created（消化日）≤ today−7** 判「进入复习队列满 7 天」，非「进入 review 之日」——卡片 new 放一个月才点 review，次日即收「满 7 天」提醒（文案造假）；且无冷却/静默/衰减，搁置一张卡每晚 20:00 都推（30 天 30 次）。位置 `LearnReviewPushService.java:95-104`。建议：review 流转时补记 reviewAt 时间戳，按 reviewAt 计时；推送加每卡节流/去重。
- **S-learn2 ⭐⭐⭐ learn-review 开关归属**：开关类型入 PushSettings.ALL_TYPES 但读写端点全在 `TradingController`（`requireTradingPlugin` 403）+ 双端入口在交易设置页——**纯 learn 用户（无 trading 插件）「可关」落空**，老 JSON 缺省开。位置 TradingController push-settings ×3 / web+app 推送设置页。建议：learn-review 开关移出 trading 门控（learn 侧独立设置或并入 learn 端点）。

### 🔴 P1

- **P1-learn1 ⭐⭐⭐ learn_card_id 回链悬空指针**：`LearnCandidateAppService.createFromCard` 回链用 raw title 拼 `learn/{type}/{created}_{title}`，实际文件名是 `fileStem(title)`（`/ : * ? " < > | #`→`-`、前导 `-`/`.` 剥除、60 字截断）——交易标题常含 `/`（A股/港股），落盘候选回链指向**不存在路径**，按 id 开源卡即 404。位置 `LearnCandidateAppService.java:61`。建议：回链复用 fileStem 或存 learn 卡相对真实路径。
- **P1-learn2 ⭐⭐ title-only 寻址跨日同名歧义**：防覆盖只挡同日；跨日重喂同标题落第二张同名卡后，`find()` = list（created desc）+ `findFirst()` **恒取最新**——PATCH status/edit、反哺、GET 详情对老卡操作静默落新卡，老卡自身无法流转/编辑；前端 `_replaceCard` 按 type+title 替换槽位 → 刷新后老卡回弹、双卡内容串台。位置 `LearnCardFileRepository.java:77-84` / web learn_page。建议：业务键加日期消歧（或 created 透传），歧义 400 人话。
- **P1-learn3 ⭐ 前端写失败人话丢失 + 技术文本外露**（U26/F57 复发）：反哺/流转/复述三处 catch 不取后端 body.error，`_human` 只认 `Exception:` 前缀——`ApiException(400): API 请求失败（HTTP 400）` 整段外露给用户；失败被吞成笼统「操作失败请重试」。位置 web `learn_page.dart:330-433` / `api_service.dart:1322,1345`。建议：统一换用既有 `extractApiErrorMessage`。
- **P1-learn4 ⭐ 候选删除删错对象**：`LearnTradingCandidateFileRepository.delete` 对 `listFiles` **无序首命中**删除，而列表按 created desc——跨日同名候选并存时删列表某条实际删文件序第一条，无回显。位置仓储 delete / `DELETE /learn/cards/candidates?title=`。建议：按 created+title 定位或返回被删标题确认。

### 🔴 P2

- **P2-learn1 ⭐ 复习提醒无冷却 nag**（S-learn1 体感面）：搁置卡每晚被推、永不衰减；老 JSON 用户缺省开找不到关闭入口（归属见 S-learn2）。随 S-learn1/2 一并修。
- **P2-learn2 ⭐ learn-review 卡被标「行情卡」**：`FeedAppService.toPushEntry` 硬编码 `tags=["行情"]`、`domain="trading"`——learn-review 复习提醒进 Feed 后视觉=交易行情卡，徽章/配色错位。位置 `FeedAppService.java:447-453`。建议：按条目 type 派生 tags/domain 或 learn-review 单独映射。
- **P2-learn3 ⭐ Feed 注入门控非类型级**：门控放宽为 trading **或** learn 后只按 PushSettings 类型过滤，不校验条目域↔用户插件——当日盘中关 trading 的残留 push 行（早盘/收盘小结）对纯 learn 用户可见（门控旁路类）。位置 `FeedAppService.java:154-160`。建议：条目类型级门控（learn-review 才放纯 learn）。
- **P2-learn4 ⭐ 反哺前端死路 + 死代码**：`getLearnCandidates`/`deleteLearnCandidate`（web api_service:1184-1205）**零调用方**——用户误建候选无查看/删除（含确认）入口；trade_related=false 卡无按钮无解释，用户不知为何不能反哺。位置 web learn_page / api_service。建议：候选列表/删除 UI 或删死代码 + 不可反哺原因提示。
- **P2-learn5 反哺按钮未二次门控 trading 插件态**：按钮仅判 `type=='trading'&&tradeRelated&&status!='done'`，LearnPage 未接收 enabledPlugins——learn 开 + trading 关（先建 trading 卡后关插件）仍可达并写入已停用插件目录。位置 web `learn_page.dart:292-294`。建议：Shell 传 enabledPlugins 或组件内门控。
- **P2-learn6 edit merge 在仓储锁外**：`AppService.edit` 读-补丁-写时序，并发两 PATCH 丢更新（B14 变体）。位置 `LearnDigestAppService.edit` / `LearnCardFileRepository.update`。建议：锁内 merge 或仓储级 update 原子化。
- **P2-learn7 update 整文件重写丢手工未知段**（B7 同族，File First 需声明）：update 以模板重写，手工在卡片里加的未知段/未知 frontmatter 键被抹。建议：契约声明「仅编辑受管字段，未知段保留」+ round-trip 测试。
- **P2-learn8 status 三态任意互转无序列约束**：new→done 直接跳、done→review 回退均放行（无 isValidTransition）。建议：只放行 new→review→done 或明确允许回退语义。
- **P2-learn9 _replaceCard 选中漂移 + _load 无代际**（F39/F53/F54 类）：更新请求在途时用户改点同组另一卡，响应到达强制拉回；刷新连点/与流转交错旧快照覆盖新结果。位置 web `learn_page.dart:33-53,393-411`。建议：按稳定标识判当前选中才回写 + 树级代际/串行。
- **P2-learn10 写入口无 in-flight 守卫 + SnackBar 堆积**（F22/F35 落点）：流转/保存/反哺三入口双击双 PATCH+双 snack，弹窗「保存」双击双 pop。建议：动作级 `_busy` 守卫 + clearSnackBars。
- **P2-learn11 源卡删除后候选孤儿回链**（低危提示）：learn 卡无删除端点（V1 未做），将来加删除时需级联或标注孤儿。

### 🔴 P2-docs（登记类）

- **P2-docs1 change-log 未登记 learn V2 4 commit**：change-log 顶部最新仍为 L2 呈现层；本批 4 commit（批1 +22→1255 / 批3 +28→1283 / 批4 +9→1292 / web 167→171 / 端点 117→122）待 ship 补登记。
- **P2-docs2 GET /learn/card 端点计数矛盾**：LearnController **实 9 个 learn 路由**（含 GET /card），而 status/change-log 口径只计 3-4 个（v3.48 记 113→116 漏 /card；api-spec §18 段落与 feature-ref 附录有 /card）——端点计数漂移。建议以 endpoints.txt 路由数对拍补正。
- **P2-docs3 api-spec learn 节顶注释「V1 只记录不联动规则库」未随批 3 演进**（candidate 已落地联动）。建议更新演进说明。
- **P2-docs4 feature-reference 版本头停在 v1.5/2026-08-25**，未随 09-06/07 learn 三改升版。建议升版登记。
- **P2-docs5 status adai-app 行备注缺 ff7451f 的 app V2 呈现登记**（与 web 行不对称）。建议补。
- **P2-docs6 feature-reference 推送开关「8 类型」清单落后实际 10 类**（close-summary/learn-review 漏）。建议同步。
- **P2-docs7 status 后端行里程碑尾注错配**：批1 尾注「1283」实为 1255（1283 是批3 后值），总数 1292 自洽。建议尾注修正。

### 🔴 P3

- 复习提醒无 Feed 双击点进卡交互（推送只提示不跳转卡片）——价值层待排。
- 候选审核「融合归正式目录」工作流未成形（跨 trading knowledge 工作流）——功能闭环待产品化。
- 复习提醒跨 ai/trading/other 聚合但文案不分组（长列表可读性）——体验待排。

## 官报告原始发现

（合并去重后已全量归口上方清单；原始子代理输出未落盘——review.md 上下文隔离规范：官报告只进汇总层。）

## 检查点建议（已入清单）

- backend 官建议 B62-B64；frontend 官建议 F62-F64；docs 官建议 D61-D63——见对应 checklists/（主会话按编号段并入）。

## 成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-09-07 | deep 增量（learn V2 消化闭环）| backend/frontend/docs/adversarial ×4 隔离并行 | 4 | ~30min | 战略×2 + P1×4 + P2×11 + P3×3（去重后）| 0（审查只报告）|
