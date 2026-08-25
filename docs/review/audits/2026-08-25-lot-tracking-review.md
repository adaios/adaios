---
title: RFC 20260825 批次跟踪批审查报告
date: 2026-08-25
status: active
---

# 2026-08-25 批次跟踪批审查（deep 四官隔离并行）

**范围**：工作树（RFC 20260825 逐笔批次跟踪与行为纠偏——后端批次推导/LIFO/行为标注/导入双模式/推送 TTL + web 批次弹窗/导入总结 + app 持仓卡批次简版 + 文档）。
**模式**：deep，backend-reviewer / frontend-reviewer / docs-reviewer / adversarial-reviewer ×4 隔离并行（材料按角色裁剪、官间不互通、主会话汇总去重）。守护 G1-G7 7 PASS / 0 HIT + META PASS。

## 发现与处置

### 已修复（本批随审查闭环，见 change-log 2026-08-25 行）

| # | 级 | 发现 | 修复 |
|:-:|:--|:--|:--|
| 对抗 P0-1 | P0 | 手动记录（流水无 orderId）与收盘导入（有 orderId）同笔成交零交集去重 → 重复入账（持仓/现金翻倍） | importSync 有 orderId 行也查指纹交叉防重 + fingerprint 价格 stripTrailingZeros 归一化（BigDecimal scale 坑）+ 回归测试 |
| 后端 P1-1 | P1 | 初始批次在流水重放后创建——「底仓快照 + 只有卖出」时卖单无批次可扣、差额丢失、初始批次虚增 | 初始批次前置（重放前建，LIFO 最后扣）+ buyDate null 判空 |
| 后端 P1-2 | P1 | sync 模式丢券商实扣手续费（流水 fee=null），与 append 模式不一致 | recordTradeWithOrderId 透传 fee |
| 对抗 P1-1 | P1 | 混合窗口整批降级 append——混一笔超窗成交则近日成交静默不更新持仓 | 窗口内外拆组并行（先 append 后 sync），syncMode=sync 合并返回 |
| 对抗 P1-2 | P1 | 追高判定无时间窗口——3 个月前割肉价参与「卖飞买回」误判 | 最近 10 自然日窗口 + 卖出日期入文案 |
| 对抗 P1-3 | P1 | 默认 −7% 强加止损并输出「违反纪律 R66」批评文案（未设止损用户被审判） | 显式止损 → 纪律级文案；默认兜底 → 风控提示文案（推送 + 行为标注同步分级） |
| 对抗 P1-4 | P1 | 状态类行为（破止损/浮盈回吐/超期）用今天日期注入历史日期复盘 → 时间错位 | 状态类判定仅 date=今天 时注入 + 测试 |
| 对抗 P1-5 | P1 | 行情类推送 15:30 消失 → 收盘后晚上看 App 全空（误以为漏推） | 改次日 09:30 消失（6 处文档同步） |
| 后端 P2-1 | P2 | reconcile 只遍历流水 symbol——纯持仓无流水股票漏导无提示 | 对账补持仓 symbol（无流水行提示） |
| 后端 P2-2 | P2 | 回合盈亏不含卖出费用（佣金/印花税/过户费）→ 总账虚高 | realizedPnl 扣卖出费用（fee 或模型模拟，按量分摊） |
| 后端 P3 | P3 | 短线超期死代码（非初始批次 role 恒 null） | 补 buyPoint 短线战法判定（SB1 等） |
| 对抗 P2-4 | P2 | isExpired 对无 expiresAt 旧数据永不过期（RFC 承诺未兑现） | 按类型默认保留期（行情类次日 09:30 / 汇总类次日 23:59，读取过滤 + 写入剔除） |
| 前端 P1 | P1 | web 批次弹窗状态列 initial 优先 closed——初始底仓卖完显示矛盾 | closed 优先 + 组合渲染测试 |
| 前端 P2 | P2 | reconcile 串股（弹窗冒全部股票对账行）+ 总结标题硬编码「今日操作」 | 按 symbol 过滤 + summary.date 展示（「8/25 操作」） |
| 文档 P1×3 | P1 | api-spec v3.27 行被替换吞掉 / 表格字面 \n / status +19 vs change-log +20 | 恢复 v3.27 + 修表格 + 统一 +22（758） |
| 文档 P2×2 | P2 | RFC 仍 draft / _index lines 漂移 | RFC → implemented + implementation 字段 + --fix 回写 |

### 未修（登记 REVIEW.md P2，后续批）

| # | 级 | 问题 |
|:-:|:--|:--|
| P2-批次1 | P2 | ~~web `_showLots` 连点/双击无幂等守卫~~ —— **已修（在途守卫 `_lotsDialogOpen`）** |
| P2-批次2 | P2 | ~~行为标注只分析导入文件的最大日期~~ —— **已修（逐日分析合并）** |
| P2-批次3 | P2 | ~~app 批次行 9px/10px 超小字号~~ —— **已修（9-10px → 11px，P2-UI5 恢复 27 处）** |

### 已核实通过（不修）

- 10 天窗口双模式判定 / orderId 幂等透传 / LIFO 跨批分算 / 按日合并加权含费 / 亏损加仓跨日判定 / 复盘标注 try/catch 兜底 / 推送 TTL 旧构造兼容 / /lots 插件门控 / importSync 锁内 recordTrade 可重入
- 批次定义/LIFO/初始批次/止损 −7% 兜底/行为六类/expiresAt 在 RFC ↔ api-spec ↔ trading-features ↔ change-log ↔ data-format-freeze 五处一致

## 复核结论

P0 实锤 1 条（手动/导入交叉防重）已修；全部 P1 已修；未修仅 2 条 P2（交互守卫 + 多日行为标注窗口），登记滚动。后端 758 / web 121 / app 125 全绿，guard-meta/align PASS。
