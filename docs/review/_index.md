---
title: docs/review 目录索引
description: review 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-10-03
status: active
lines: 55
depends-on: []
related:
  - ../_index.md
tags: [meta, index, review]
---

# docs/review 目录索引

**职责**：审核结果区——未修项滚动区 + 走查存档。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| REVIEW.md | 审核全量状态报告（未修项滚动区） | active |
| audits/2026-10-03-trading-cash-stocktake.md | **交易账本存量盘点（资金侧）**——D4「先出报告」的交付（只读：1,721 笔流水 × 09-23 清理前 1,722 笔逐 ID 差集 + 5 份 `account.json` 历史备份 + `journalctl` 现场日志）：① **3 笔无委托号流水仍在**（`orderId`+`fee` 双空：09-16 600536 700+100、09-18 000831 200）→ 600536 被记卖出 1,600 股（真实 800）→ 重放为 **−800 股**负持仓、现金多进 **24,904 元**（＝09-23「系统 24,101.01 / 券商 414.86」主因）；⭐**日志取证修正了定性**——归集的 700+100 **走了完整记账（动了现金与持仓）**，券商那笔 800 股才只补流水，铁证是日志「交易日志确认落库｜**同笔已记过 0**」（800 与 700/100 拆单粒度不同，指纹必然失效）；② **13 笔股息/红利税流水已于 09-23 被当「0股@0.00 脏流水」清理（净额 1,062.60 元），而 09-13 存档对同一批的建议恰是「不删」**——两处口径相反，且股息不在任何成交导出里，删掉即永久失去现金痕迹；③ **09-09 那笔 15,000 提现已查证为真实**（余额连续性反证：假设它不存在，残差恰为 15,006.61）→ `principal=130,000` **正确**；④ **系统现金漂移轨迹取证**：−37,226.29（09-07）→ −12,721.84（09-09）→ 1,381.93（09-11 覆盖）→ −6,093.97（09-15）→ **+24,101.01**（09-22）→ 414.86（09-23 覆盖），而真实券商现金全程 ≤2,278.16。**已核实干净**：09-13 清单 A 的 6 组重复全清。只报告未改数据，处置待授权 | active |
| audits/2026-09-17-wiring-audit.md | **接线盘点——零件齐了没插电**（只读扫描：A 数据生产但不回流生成 / B 生成绕过记忆注入 / C 能力齐但触发理由缺失 / D 记忆资产无出口 / E 方法零消费，共 5 类 11 条，逐条附文件行号与可复核命令；只报告未修） | active |
| audits/2026-09-17-deep-review-backend.md | **晚间批+深夜第二批增量深审·后端官**（`d060841..HEAD` 51 文件；P0×0 · 战略×1 · P1×2 · P2×4 · P3×6）：⭐**P1-1 `restoreOrigin` 判据含 `status:`，而 A 形态技能卡模板本身带 status → 只读保护可被一句话绕过**（主会话已复核成立）· ⭐**P1-2 `GET /trading/pnl-periods` 盘前把上一交易日当 today 覆盖并回填 week/month → 双计**（已复核成立）· P2 轮换回滚失败抛 401 登出 / purge 假成功 / 图片配额记账早于 execute / 盘前 refreshTodayPnl 靠文案判据。附：生产 JVM 时区待验证、purge 无 admin 入口与文档不符 | active |
| audits/2026-09-17-deep-review-frontend.md | **增量深审·前端官**（app/web/iOS 全绿复核：analyze 0 / app 359 / web 302 与 status 一致；P0×0 · P1×0 · P2×3 · P3×8+观察4）：P2 Keychain 迁移即清旧明文（无事务、失败即两边空）· 清明文不检查扩展能否读 Keychain（中间态会打断分享链路）· ⭐**深链 key 在中文正文里必然匹配不到 → 兜底高亮「最新一张推送卡」，与注释承诺相反**。**learn23 证伪未成功**（web 六入口 + app 两入口逐条核过）但补隐性依赖：`writable ?? true` fail-open + 四动作函数内部无早退 | active |
| audits/2026-09-17-deep-review-adversarial.md | **增量深审·对抗官**（P0×2 · P1×4 · P2×6 · P3×4）：所报两条 P0 **经主会话逐条复核均不成立**——Keychain 授权实测描述文件为 `keychain-access-groups: [4G3D37YKSB.*]`（通配授权可用，非「全无」，且它引用的归档是旧 entitlements 那次）；盘前写回被闸2 挡住（`refreshTodayPnl` 确实经 `computeDailyPnl→dailyPnlDetail`，但 notes 新提示落在 actionable 里 → 直接 return 不写回）。**但其指出的机制问题成立**：闸2 靠文案前缀判定属巧合非设计 · purge 假成功+空目录残留+而无入口 · 图片配额失败不退（接口声明「允许负数回退」零调用者）· `git add -A` 禁令零机制 · `trading:today` 点 A 高亮 B（与前端官独立命中）· 轮换/purge 三端零入口 | active |
| audits/2026-09-17-deep-review-docs.md | **增量深审·文档官**（P0×0 · P1×1 · P2×6 · P3×7+范围外2）：⭐**P1 真实股东代码仍在库**——`change-log.md:9`（本批自己写的「脱敏」那一行把原值又抄了一遍；`git grep` 仅此一处）而同一行自称「9 文件归零」+ REVIEW 标 ✅；⭐**P2 REVIEW 14 条 ✅ 与正文系统性矛盾**（只加 ✅ 未动正文，11 条仍写「未做/待排」；且违反 REVIEW 自定「✅ 移入已修复区」）；**restore-origin 判据三方口径不一**（api-spec 说 frontmatter / 实现是全文 contains / javadoc 漏 status）；另：图片日配额只进变更记录未进端点正文 · purge「admin 输入账号名确认」失真（客户端 0 入口）· rotate 回滚失败实返 401 未登记。**核对通过**：四处数字一致且可复现（v3.71 / 1939·359·302·69 / 154 端点，独立复算 + ai-guard-align 重跑）· 两新端点契约与实现逐项一致且未写 cardPath · purge 语义一致 · 14 条 ✅ 代码侧全部真实存在 · v1.0.0 差距表两列可复现 | active |
| audits/2026-09-14-pre-deploy-review.md | **部署前增量深审**（未发布面 `dc95599..HEAD` 四官隔离并行 + 主会话实测：⭐ 多官命中 4 处；**P0×2 当场修复出表**——锁屏脱敏只覆盖收盘小结 / 外部令牌可收回接不上；P1×8 + 战略×1 + P2×6 已归口 REVIEW）| active |
| audits/2026-09-13-first-contact-review.md | **首轮外部视角审查**（面向身边人之前：陌生人/社会性/支持台三官首跑，只看字不看码，**三端口径** → P0×2「Android 两端零通知 + Android App debug 签名不可分发」+ P1×7 术语/空态/三端不同源/9 个无效推送开关 + 决策×6【D1/D2/D6 已拍板 A，附函数级落地方案】；只报告未修）| active |
| audits/2026-09-13-trading-ledger-dirty-rows.md | 交易账本存量脏流水逐笔核对存档（全量 1707 笔重判：真重复 6 组非 4 笔 + 负持仓同根因 + 粗暴去重反例 25 只负持仓；只报告未改数据，处置待拍板） | active |
| audits/2026-09-05-cognition-layer-review.md | 交易⑤认知层批三官深审存档（backend/docs/adversarial 隔离并行：P1×5 + 💥×6 + docs×6 全修出表，守护 G2 一并修） | active |
| audits/2026-09-06-admin-uiux-review.md | adai-admin 管理后台 UI/UX 专项审查存档（ui/ux 双官隔离并行：P1×4 成立 + P1×1 误报排除 + P2×25 + P3×31，修复待拍板） | active |
| audits/2026-09-09-daily-pnl-review.md | 当日盈亏精确计算三官深审存档（backend/frontend/adversarial 隔离并行：⭐⭐ 交叉命中 T+1 成本配比算术缺陷，修复批已落地 audits 同日期 + REVIEW 头部登记；未修三项排后续） | active |
| audits/2026-09-07-learn-v2-review.md | learn V2 消化闭环增量深审存档（backend/frontend/docs/adversarial 四官隔离：战略×2 + P1×4 + P2×11 + P3×3，0 修复只报告） | active |
| audits/2026-09-05-memory-fidelity-audit.md | 记忆失真审计基线（只读：kind 失衡/无卡对话原话缺失/抽样加料过半 → memory-fidelity.md 修订 + 写侧保真立项） | active |
| audits/2026-08-30-case-library-data-review.md | 案例库数据批次审查存档（降级主会话：KDJ latest 回归/搜索竞态已修 + P3 登记） | active |
| audits/2026-08-30-case-library-review.md | 完美买点案例库批次审查存档（降级主会话审：S1 save 双文件回滚已修 + P1×1/P2×5/P3×1 登记） | active |
| audits/2026-08-30-trading-rule-layer-review.md | 交易插件规则层审查存档（四官 + 对抗官隔离，P0×2 信任炸弹 + 硬约束 fail-open，待拍板降级语义） | active |
| audits/2026-08-25-lot-tracking-review.md | RFC 20260825 批次跟踪批审查存档（四官隔离，对抗 P0-1 交叉防重实锤修复） | active |
| audits/2026-08-23-ai-engineering-meta-audit.md | AI 上下文建设工程体系元审核存档（主审核 + 对抗官复核 + 实证实验，修复批 072dcee） | active |
| audits/2026-08-23-reviewer-isolation-demo.md | 隔离审查演示存档（对抗官首战 + 上下文隔离，交易归集批残留） | active |
| audits/2026-08-20-app-health-check.md | app 全面体检走查存档（用户体感导向，4 官） | active |
| audits/2026-08-15.md | 首轮全维度走查存档（7 官） | active |
| audits/2026-08-15-ai-engineering-self.md | AI 工程层自伤自查存档（8 官） | active |
| audits/2026-08-16-ai-engineering-workflow.md | AI 工程工作流自伤自查存档（第二轮，三视角） | active |
| audits/2026-08-18-production-log.md | 生产日志审查存档（2026-08-18 journalctl 当日问题分级） | active |
| audits/2026-08-24-ai-calling-governance-doc-review.md | AI 调用治理方案文档深审存档（docs/backend/frontend/adversarial 四官隔离，S-9/S-10 登记） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
