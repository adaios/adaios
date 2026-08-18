---
title: 生产日志审查 2026-08-18
description: journalctl 拉取 49.235.37.220 当日 2986 行生产日志的问题总结——按 P0/P1/P2/P3 分级，只报告不直接修
version: 1
created: 2026-08-18
updated: 2026-08-18
status: active
lines: 61
depends-on:
  - ../../../services/adai-core/deploy.sh
related:
  - ../../review/REVIEW.md
  - ../../reference/issue-log.md
tags: [review, production, log]
---

# 生产日志审查 2026-08-18

> **范围**：`journalctl -u adai-core --since 2026-08-18`（49.235.37.220，2986 行，服务当前 active）。
> **方法**：全文拉取 → 按 ERROR/WARN/业务链路（行情/推送/交易归集/AI/图片）分类计数 → 关键行上下文核对。
> **原则**：审查只报告不直接修（B7）；P0 数据丢失可与用户确认后修。

## 一、P0/P1 数据质量

| # | 问题 | 证据 | 影响 |
|:-:|:-----|:-----|:-----|
| P0-1 | **图片记录 summary 泄漏 `<think>` 思考壳（5 条全中）**：GLM-4.1V-Thinking 的 `<think>…` 原文直接写入 summary 与记忆；`GlmResponseParser` 剥壳未生效（声明有剥壳逻辑，实际走了原始文本路径）| 16:27–16:30 五条图片记录 `summary=<think>用户现在需要分析这张图片…` + `MemoryService` 4 条同款；生产文件 `rec_20260818_162712761.md` 已落盘脏数据 | ① 用户可见卡片展示 `<think>`（第一原则泄漏）；② 成交截图 `domain=life`/`tags=[]`，未识别为交易 → **不进交易日志归集**，15:15 确认已错过这批 |
| P1-1 | **交易日志归集解析失败：`SELL unknown（数量未知）`** | 09:01–09:02 三次归集 `SELL unknown`，候选 1→2 笔 | 截图/文字识别链路对部分输入产出 unknown，候选质量差 |
| P1-2 | **缺数量候选仍确认落库**：00:54 `SELL 002428（数量未知）` 确认「成功 1/1」 | TradeLogCollectService 00:54:41 | 与 ADR-004「缺数量/价格确认时跳过并引导补全」约定不一致 |

## 二、P2 服务稳定性

| # | 问题 | 证据 | 影响 |
|:-:|:-----|:-----|:-----|
| P2-1 | **东财 K 线全量失败 1154 次**（10/14/15/16/20/21 时），全部降级腾讯 | `EastMoneyKlineDataSource` WARN + `KlineService 降级腾讯` | pitfalls 已知项（生产 IP 被东财限）持续；全链路行情依赖腾讯兜底（15:10 买点扫描 26 只→0 命中即兜底数据）|
| P2-2 | **凌晨三次重启（00:47/00:53/00:56）+ shutdown hook 类加载错误**：每次停止均抛 `NoClassDefFoundError`（GracefulShutdownCallback / Lifecycle$SingleUse / logback ThrowableProxy）| jar 落盘 00:56（部署动作）；三次 restart 均伴随堆栈 | 不致命但每次重启刷错；fat jar 关闭时序问题 |
| P2-3 | **DeepSeek 响应异常短走降级解析 ×4**（08:31/08:45/10:36/16:38）：请求预估 tokens 926–4161，响应 200 仅 70–87 字节且非 JSON | `LlmResponseParser: LLM 回复中未找到 JSON，使用降级解析` | 疑似拦截/超短回复，理解质量下降 |
| P2-4 | **个人档案缺失 ×4**：`identity/profile.md 不存在，使用默认档案`；生产仅 `adai` 有档案，`alice` 等账号无 | IdentityFileRepository WARN + 服务器 `ls data/*/identity/` | 多账号用户画像缺省，AI 上下文按默认档案走 |

## 三、P3 噪声/环境

| # | 问题 | 证据 |
|:-:|:-----|:-----|
| P3-1 | 公网扫描探测 ×4（`MGLNDD_49.235.37.220_8080`、TLS ClientHello 字节流当 HTTP 方法名）| `Invalid character found in method name` |
| P3-2 | 连接重置/Broken pipe ×9（客户端弱网断开）| `AsyncRequestNotUsableException: Connection reset by peer / Broken pipe` |
| P3-3 | 单条记录不可读 ×1 | `rec_20260811_232734755.md 为空或不可读`（10:38）|
| P3-4 | GET 方法不支持 ×2（探测或调用方方法错误）| `HttpRequestMethodNotSupportedException` |

## 四、正常面（不受影响）

- 收盘 15:05 账户更新正常（市值 113686.00 / 当日盈亏 671.00）
- 15:15「今日操作确认」推送成功；微信推送全天正常（break-cost/gain/loss/session/stop-loss 均成功）
- 15:10 自选买点扫描完成（26 只 → 0 命中，兜底数据）

## 五、建议优先级

1. **P0-1 优先**：查 `GlmResponseParser` 剥壳未生效路径（JSON 解析分支漏剥）→ 修复 + 对拍 `rec_20260818_1627*` 5 条脏记录（是否清洗需用户确认，涉及已落盘数据）
2. P1-2：TradeLogCollect 落库前置校验数量/价格非空
3. P2-4：deploy.sh seed 所有账号默认档案（含 alice）
4. P2-3：DeepSeek 短响应加日志上下文（响应原文），便于定位拦截源
5. P2-1/P2-2/P3 观察项：东财持续失败建议换源或加缓存；shutdown 堆栈、公网扫描记录即可
