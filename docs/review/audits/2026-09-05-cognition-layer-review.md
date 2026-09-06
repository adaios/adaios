---
title: 交易⑤认知层批三官深审（RFC 20260905，用户「需要审核么」→ 派 deep）
date: 2026-09-05
type: review
scope: services/adai-core + docs（认知层落地批 18 文件 + 文档登记）
reviewers: backend-reviewer / docs-reviewer / adversarial-reviewer（三官隔离并行）
---

# 交易⑤认知层批 · 三官深审报告

## 审查结论

- **backend**：无 P0（无确定性数据丢失）。P1×5 + P2/P3×8。存储实现扎实（append 条带锁 + 原子写 + 损坏拒写均正确），风险在 sold.json 新写者未入锁与两条回查链基准日错误。
- **docs**：guard 盲区 6 必改 + 3 遗留（RFC 双§七、decided-by 矛盾、测试增量分项、断链、index lines、排序契约、档位）。
- **对抗**：💥×6 + ⚠️×10 + 🤔×6。核心：尾盘建议不留痕→B 层空转、now() 时间锚+卖后建议未滤、globalContext 无数据声称了解用户、sold.json 无锁、同 symbol 多笔错对象、违纪率≡亏损率、样本门槛。

## 已修复清单（全部出表）

| 编号 | 问题 | 修复 |
|---|---|---|
| P1-1/💥3 | 遵守率回查基准 now()→旧清仓漏算 | computeAdviceAdherence 以 sellDate 为锚扫卖前 30 天（+回归测试 oldSoldStillCounted）|
| P1-2/💥2 | 复盘对照 now()+取最新不滤卖后 | buildAdviceCompareSection 以 sellDate 锚 + date≤sellDate 滤（+回归 postSellAdvice_notReferenced）|
| P1-3/💥5 | sold.json saveAll 无锁+写失败 warn | SoldTradeFileRepository per-user 条带锁 + 抛 StorageException（P0-1 口径）|
| P1-4 | domain→application 反向依赖（C7 违）| TradingProfileService 下沉 domain/trading（纯搬迁，7 引用文件 import 更新）|
| P1-5 | AdviceEntry id 截到秒可重复 | id 由 createdAt 格式化含毫秒 yyyyMMdd_HHmmss_SSS（+测试）|
| 💥1 | 尾盘建议不留痕→遵守率分母空 | TradingSessionPushService 注入 AdviceHistoryRepository，buildCloseTemplate 逐票落 session-push（+测试）|
| 💥4 | globalContext 无数据声称了解你 | 有画像数据才声明；无 → 空（+2 测试）|
| 💥6/P2-5 | 同 symbol 多笔情绪答错对象 | submitAnswer/psychology-questions 按 sellDate 取最近一笔（+定位注释）|
| 🤔18 | 小样本 1 笔就下判断 | objectiveProfileText <10 笔只给笔数不给胜率/纪律率（+2 测试）|
| docs-3 | advice-history 空 symbol 跨月拼接非全局倒序 | Controller 拼接后统一 createdAt 降序（+跨月排序测试）|
| docs-1 | RFC 双「## 七」+ decided-by 矛盾 | 删旧实施顺序节 + frontmatter 口径统一 |
| docs-4/5/6 | benchmark 断链 / rfc_index lines / change-log 档位端点 | 修正 + 端点 108→113 |
| ⚠️13 | days≤365 vs 仓储 3 个月 | 端点上限 90 + api-spec 注明 |
| ⚠️8 | 复盘对照英文直出 clear/reduce | humanSuggestion → 加仓/持有/减仓/清仓（+断言更新）|
| G2（守护自抓）| AdviceHistoryFileRepository now() 路径推导 | 实体构造默认值收敛 + today 注入仓储（7 PASS/0 HIT）|

## 遗留（P2/P3 或产品口径，登记待办）

- P2 违纪率≡亏损率口径（三 verdict 分类覆盖全部亏损——需产品确认「纪律违反率」标签语义）
- P2 deepLoss 与长短仓 else-if 互斥（长拿深亏问不到 deep_loss_trigger）
- P2 profile 注入无缓存（每次重算）——个人系统低频可缓，标注
- P2 appendSubjective 与 PUT /profile 并发互覆；重复 POST 无幂等（sold 层）
- P2 留痕损坏静默失能（无自愈/告警）；留痕无同日同票去重
- P2 代码匹配 byte 级无规范化（600584 vs sh600584）
- P3 画像统计建在 sold.json（RFC §二自曝失真口径——画像 v2 应基于逐笔回合）
- P3 前端零入口（5 端点 API-first，前端孤岛待产品排期）

## 测试增量

1181 → **1187**（+6：排序 +1 / id 毫秒 +1 / push 留痕 +1 / contributor 重构 net +1 / profile 样本门槛 +2）。守卫 G1-G7 7 PASS / 0 HIT；guard-align/meta PASS。
