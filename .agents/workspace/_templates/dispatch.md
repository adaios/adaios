---
title: 派单模板（审核驱动主链）
description: 派审核官 / 编写者时的 prompt 骨架——照抄改；含回执 · 材料前置 · 先落文件 · 3 分钟闸门（协议见 rules/process/review-driven.md §9）
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 37
depends-on: []
related:
  - ../../rules/process/review-driven.md
tags: [meta, template, workspace]
---

# 派单模板（照抄改）

> 五条协议见 [`../../rules/process/review-driven.md`](../../rules/process/review-driven.md) §9。把 `<>` 换掉即可。

```
【任务】<只判哪几条 / 不判什么 —— 一句话说清>
【角色真相源】`.agents/toolkit/roles/<name>.md` + `.agents/toolkit/checklists/<name>.md`

【材料】（主链前置，别让它自己去找）
- 审核对象：<文件 + 行范围>
- 证据片段：<贴原文 / 贴代码行 —— 直接粘进来>
- 背景：<只贴相关的那几行，别给一整个目录>

【产出】`workspace/<分支名>/<文件名>`

【第一动作】先把 `workspace/_templates/review-design.md` 复制成上面的产出文件
（**先落空文件再填内容**——这是主链唯一能看到你还活着的方式）

【时间】3 分钟内必须看到文件；看不到主链会**打断重派**
【先行回执】收到先回一句「已收到，开始 <X>」
【硬要求】中文；只报告不改（B7）；**不要改 `_index.md`**（主链统一登记）；
        frontmatter 10 字段齐全 + `lines:` 与实际一致；写一节落一节
```
