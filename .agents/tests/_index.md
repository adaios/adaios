---
title: .agents/tests 目录索引
description: 守卫脚本的反例回归测试区——用坏样本证明「守卫真会抓」，防「看起来在查、其实查不到」的假绿
version: 1
created: 2026-10-01
updated: 2026-10-01
status: active
lines: 36
depends-on: []
related:
  - ../guards/guard-feature.sh
  - ../_index.md
tags: [meta, index, tests]
---

# .agents/tests 目录索引

**职责**：存放**守卫脚本自身的反例回归测试**——不是测产品代码，是测「守卫有没有真的在查」。

**为什么需要**：2026-10-01 首版 `guard-feature.sh` 的切卡正则被一级标题挡住 → 卡计数恒 0（F7/F8 形同虚设），而正例照样 PASS。**只测正例＝假绿的温床**，故把坏样本固化下来。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| guard-feature-fixture.py | `guard-feature.sh` 反例回归：造 11 行坏索引 + 3 张坏卡（含实现细节 / 超长 / 孤儿）+ 坏 RFC status，断言 F0–F9 与「状态-证据对拍」「存量跳过」全部按预期触发；夹具只写 `/tmp` | active |

## 用法

```bash
python3 .agents/tests/guard-feature-fixture.py   # 0 = 全触发；1 = 有检查项漏检
```

## 过期判断

- 守卫新增/改写检查项后，本目录测试若未同步补坏样本 → 该检查项等于**没有验证**（应随改动一起补）。
