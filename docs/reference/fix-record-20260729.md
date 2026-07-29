# 修复记录 — 2026-07-29

> **本轮修复：** 4 个问题，涉及 prompt 冲突、状态机旁路修复、死代码。
> **方案选择原则：** 从最安全、最小改动的方案开始推进。

---

## #1 — SUMMARY 人称代词未根除

**本质：** prompt 冲突 — 系统层和用户层指令矛盾。

### 方案选择：A（修 system prompt）

改 `DeepSeekAiClient.java` 的默认 system prompt，消除与 `ContextEngine.java` 用户层指令的矛盾。

**不改方案 B/C 的原因：** 方案 B（删系统层指令）和 C（全部归 ContextEngine）改动大，且意图识别路径依赖 system prompt，方案 A 风险最低。

### 改动

```
文件：DeepSeekAiClient.java:264
系统层（buildSimpleBody 默认 system prompt）：

旧：insight用一句话表达你对用户的理解，有信息增量，不要复述原文
新：insight用一句话客观概括，有信息增量，不要复述原文，避免人称代词

旧：用tags数组标注关键词标签；用domain字段判定所属领域(life/trading/project之一)
新：用tags数组标注关键词标签；用domain字段判定所属领域(life/trading/project之一)
  （tags/domain 部分不变）
```

### 验证

- 检查 `LlmResponseParserTest` 中 summary/insight 解析测试不因文本变化而失败
- 手动测试：输入一条记录，确认 AI 返回的 insight 不含"你/我/用户"

---

## ND1 — QUESTION 场景输出指令冲突

**本质：** prompt 冲突 — CHAT 模式下 system prompt 只要求 `{"domain":"..."}`，ContextEngine 要求完整 JSON。

### 方案选择：A（升 system prompt）

将 CHAT 模式的 system prompt 从简化的 domain-only JSON 升级为完整 JSON 格式，与 ContextEngine 一致。

**不改方案 B（降级为仅 domain）的原因：** 
- 前端依赖 summary/tags/actionable 等字段
- 降级意味着前端要改，得不偿失
- 升 prompt 让两处一致，模型总是遵守 system prompt，行为稳定

### 改动

```
文件：DeepSeekAiClient.java:173-174
CHAT 模式 system prompt：

旧："回复结束后另起一行，附上 JSON 标注领域：{\"domain\":\"life|trading|project\"}"

新：
回复结束后在末尾另起一行输出 JSON（不要包裹 markdown 代码块）：
{
  "summary": "3-5个词概括本次问答主题，避免人称代词，像标签一样简洁",
  "tags": ["标签1", "标签2"],
  "sentiment": "positive 或 negative 或 neutral",
  "domain": "life(生活)/trading(交易)/project(项目)",
  "actionable": true 或 false,
  "actionSuggestion": "需要后续操作写建议，否则写 null"
}
```

ContextEngine.java:456-475 中的 question JSON 输出指令保持不动（与 system prompt 一致，冗余但不冲突）。

### 验证

- 提问后确认 AI 回复末尾有完整 JSON（含 summary/tags/sentiment/domain/actionable）
- `LlmResponseParser` 的 `extractJson()` 能正常提取
- 前端 `_stripDomainJson()` 能正常清理

---

## #3/#4 — 结束对话 mode=ended

**本质：** 旁路修复 — `_closeChat` 设 `mode: CardMode.idle`，`CardMode.ended` 从未被赋值。

### 方案选择：C（ended + ask 入口）

`_closeChat` 成功后设 `mode: CardMode.ended`。feed_card.dart 已有完整的 ended 渲染（绿边框 + summary banner + tags + ask 按钮），只需要主页面正确设置 mode。

**不改方案 A/B 的原因：**
- 方案 A（删 ended 枚举）和 B（保留 idle）都是"放弃 ended"，但 ended 的设计（绿边框视觉区分已结束对话）是有价值的
- 方案 C 改动最小（1 行），利用了已有渲染代码

### 改动

```
文件：main_page.dart:203-206

旧：mode: CardMode.idle
新：mode: CardMode.ended
```

### 验证

- 走完一次完整对话：输入 → ask → 聊天 → end
- 确认卡片关闭后显示绿边框 + summary banner + 标签 + ask 按钮
- 确认点击 ask 可以重新激活对话
- `feed_card_test.dart` 中 mode=ended 的测试应该通过（之前是构造数据，现在是真实路径）

---

## ND2 — handleDecision 死代码

**本质：** 死代码 — 完整逻辑但永不执行。

### 方案选择：A（删除）

删除 `RecordController.handleDecision()` 方法（80 行）和 `DecisionResponse` record。

**不改方案 B/C 的原因：** 方案 A 最干净。方案 B（保留+标注）只是拖延，方案 C（加前端入口）引入了"决策"这个未定义的交互概念。

### 改动

```
文件：RecordController.java
删除：handleDecision() 方法（第 209-240 行）
删除：DecisionResponse record（第 285-292 行）
```

### 验证

- `./gradlew build` 通过
- `RecordControllerTest` 通过
- 确认没有其他代码引用 `handleDecision` 或 `DecisionResponse`

---

## 修复后检查清单

- [x] `./gradlew test` 后端测试全通过
- [x] `flutter test` 前端测试全通过
- [ ] 手动验证 #1：输入记录，insight 无人称代词（需要运行后验证）
- [ ] 手动验证 ND1：提问，AI 回复末尾有完整 JSON（需要运行后验证）
- [ ] 手动验证 #3/#4：结束对话后卡片绿边框 + summary + ask（需要运行后验证）
- [x] 对比 `feature-reference.md` 确认无差异
- [x] 更新 `task-log.md` 已修复任务
