---
title: 活跃市值区间开关方案（用户手动判定 · 双端红绿切换）
description: 把「活跃市值=一切的前提」的区间判定权还给用户——App/Web 手动切多头/空头，落 market-stage.json；推送择时状态三级读取（用户判定优先），根治 current.md 旧规则永久锁死空头的问题（2026-09-03 对话确立，2026-09-04 定稿实施）
version: 1
created: 2026-09-04
status: active
depends-on:
  - trading-features.md
  - ../architecture/api-spec.md
related:
  - trading-features.md
  - status.md
  - ../reference/change-log.md
tags: [trading, market-stage, timing, architecture]
---

# 活跃市值区间开关方案（用户手动判定 · 双端红绿切换）

## 背景（2026-09-03 用户对话确立）

用户在当日对话中强调：**指南针软件的「活跃市值」指标是交易系统「一切的前提」**——它决定当前处于多头还是空头区间，进而决定仓位松紧与操作手法（空头高抛低吸、多头拿得住）。阿呆的知识底座只把活跃市值当 0AMV/通达信口径讲（16:04 答偏），且系统的择时状态**锁死在 `current.md` 的一条 OAMV 规则推断上**：

> current.md：6/26 触发 -2.3% 离场法则 → 此后一直「空头区间」，等 +4% 转多。

问题：
1. **判定权不在用户**——用户无法亲手表达「现在是空头/多头」，只能靠 OAMV 规则或改文件。
2. **推断可能过时/被锁死**——规则从 6/26 推到现在仍空头，用户想用自己的判断也覆盖不了推送。
3. **双端不可见**——app/web 没有任何地方能看到/切换当前区间。

用户拍板（2026-09-04）：做一个**开关**，由用户手动判定多头/空头，App/Web 两端都要有，当前置「空头」，一起上线。口径 = 指南针活跃市值（= Z 哥课程体系活跃市值，非通达信 0AMV 独立口径）。

## 方案总览

```
你在 App/Web 交易页切换 多头/空头
      ↓ PUT /api/v1/trading/market-stage {"stage":"bear"}
data/{userId}/trading/market-stage.json  ← 用户私有，File First
      ↓
时段推送（早盘/午间/尾盘）的【择时状态】三级读取：
  ① market-stage.json（用户手动判定，权威）→ ② current.md → ③ "择时状态未知"
```

## 数据契约

### 存储文件 `data/{userId}/trading/market-stage.json`

```json
{
  "stage": "bear",
  "updatedAt": "2026-09-04T09:00:00"
}
```

- `stage`：`bull`（多头区间）| `bear`（空头区间）——**两档**（用户拍板，无「未判定」档；文件缺失 = 从未手动判定）
- `updatedAt`：最近手动判定时间（ISO 本地时间，yyyy-MM-dd'T'HH:mm:ss）
- File First：可导出/导入/版本管理；per-user 条带锁原子写（P2-交易28 锁池模式，防并发 RMW 丢更新）

### API（详见 api-spec.md v3.41）

| 方法 | 路径 | 请求 | 响应 |
|:----|:-----|:-----|:-----|
| GET | `/api/v1/trading/market-stage` | — | `{"exists":true,"stage":"bear","updatedAt":"..."}` 或 `{"exists":false,"stage":null,"updatedAt":null}` |
| PUT | `/api/v1/trading/market-stage` | `{"stage":"bear"}` | `{"updated":true,"stage":"bear","updatedAt":"..."}`；非法/缺失 → 400 |

> 需 trading 插件（403，与 /trading/rules 同门控）。

### 推送消费（后端改动点）

`TradingSessionPushService.readMarketStage()` 由「读 current.md 一行」改为三级：

1. 读 `market-stage.json`：`bull` → 「当前判断：多头区间（用户手动判定 yyyy-MM-dd）」；`bear` → 「当前判断：空头区间（用户手动判定 yyyy-MM-dd）」——**推送与知识注入以用户判定为准**；
2. 文件缺失/不可读 → 回退现逻辑（current.md「当前判断」行）；
3. 仍不可得 → 「择时状态未知」（现状兜底不变）。

## 双端 UI 规格

红涨绿亏全局一致：**多头 = 红，空头 = 绿**。

| 端 | 位置 | 样式 | 交互 |
|:--|:--|:--|:--|
| **Web**（`apps/adai-web/lib/pages/trading_page.dart`）| 交易页顶部/账户区旁的「市场阶段」切换 | 两态胶囊/分段控件 + 圆点图标：🟢 空头区间（绿）/ 🔴 多头区间（红）；副文案显示「手动 · 更新于 HH:mm」或「按规则推断」 | 点击即 PUT 切换；失败 toast 人话 |
| **App**（`apps/adai-app/lib/pages/trading_page.dart`）| 交易页持仓卡上方同款「市场阶段」条 | 同上（红/绿态） | 同上 |

- 初始值：本地与生产 `data/adai/trading/market-stage.json` 预置 `{"stage":"bear","updatedAt":<部署时间>}`（用户拍板「顺手补上即可，目前是空头」）。
- 未设置（新用户无文件）：GET 返回 exists=false → 前端显示「未判定（按规则推断）」灰态，仍可点切。
- 已设置：显示用户判定值；切换时乐观更新 + PUT 失败回滚。

## 测试计划

| 层 | 用例 |
|:--|:--|
| Repository | 写入/读取 round-trip；文件缺失 → 空；损坏 → 降级；并发写锁不丢 |
| Controller | GET 有/无记录；PUT bull/bear 成功；缺失/非法 stage → 400；插件门控 403 |
| 推送三级 | 用户 bear → 「空头区间（用户手动判定」；无文件 → current.md 行；都无 → 「择时状态未知」 |
| 前端 | Web/App：GET 渲染两态、点击切换 PUT 调用、失败回滚（widget 测试按各端既有模式） |

## 上线计划

1. 后端：repository + 端点 + 推送三级读取 + 测试（`./gradlew test`）
2. 本地数据预置 `market-stage.json` = bear
3. Web/App 双端 UI + 测试
4. 文档登记：api-spec v3.41（已写）/ feature-reference（已写）/ 本方案 / status.md / change-log
5. 生产上线（deploy-gate）：后端 jar + web 重建 + 生产 `market-stage.json` 预置 bear + smoke
