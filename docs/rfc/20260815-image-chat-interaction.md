---
title: 带图交流——发图即对话（交互方案）
description: adai-app 带图交流模块交互设计——发图=一次完整"说"，AI 判定 log/ask 分流，ask 直进对话态、log 自然沉淀；含场景拆分/判定规则/状态机/改动点清单
date: 2026-08-15
status: draft
depends-on:
  - 20260802-multimodal-image-glm.md
  - 20260815-media-event-unification.md
related:
  - ../architecture/api-spec.md
tags: [ui, interaction, media, adai-app]
---

# 带图交流——发图即对话（交互方案）

> 状态：draft（交互方案，待评审后实施）。针对 adai-app 移动端；adai-web 桌面端按同一语义对齐（本版不展开）。
> 关联：`20260802-multimodal-image-glm.md`（图片记录能力基础）、`20260815-media-event-unification.md`（数据层图文一体，v1.0.1）。

## 一、现状问题（为什么改）

读代码核对的现状（`apps/adai-app/lib/main_page.dart`、`widgets/input_bar.dart`、`widgets/feed_card.dart`、`services/api_service.dart` + `services/adai-core` MediaController/FeedAppService）：

1. **发图不是一次"说"**。图+问句发送后，`_flushPendingAsk`（main_page L574）只把回答截断进 SnackBar + `_loadFeed()`，**对话态从不自动进入**——用户必须再点卡片上的「提问」才进对话。与"图即上下文、可直接追问"的语义脱节。
2. **纯图无反馈**。纯图发送后只弹「📷 已记录 N 张图片」（main_page L404），阿呆的 VLM 理解（summary）只写在卡上，**从不说出来**。
3. **意图判定只看文字**。空文字永远 log（`_onSendMedia` L336 仅 caption 非空才注册 pending ask）；图的内容不参与判定——发一张报错截图想求解，不带字就只记录。
4. **S-2 聚合卡身份断裂（潜在 bug，实现时验证）**。ask-batch 后 Feed 由 `FeedAppService.toFeedEntry`（L229-243）聚合成 `image_qa` 条目，但前端 `mediaUrl = api.mediaUrl(条目 id)`（api_service L496），而 `GET /records/media/{image_qa_id}` → `mediaPathFor`（MediaRecordAppService L125）按 `{id}.{ext}` 找文件——image_qa 记录没有媒体文件 → **缩略图 404**；且对聚合卡追问时 `askMedia(imageRecordId=image_qa_id)` 同样解析不到原图。
5. **判定/回答期间无可见状态**。上传完成后到 ask-batch 返回之间，界面无"阿呆正在看图"反馈。

## 二、设计目标（一句话 + 原则）

> **发图 = 一次完整的"说"**：选图（±文字）→ 发送 → 阿呆看图并判定意图 → **ask 直接开聊 / log 直接记下并反馈** → 可连续追问 → 结束沉淀为带图总结卡。用户不再需要手动点「提问」开启对话。

- **判定交给 AI**：不提供手动意图切换按钮，与文字输入的「入口统一，后台分流」同构。
- **无第三视角**：阿呆所有输出是自然对话句（"看到你吃了日料 🍣，已记下"），不出现「问：/答：/图片记录：/已记录：」标签。
- **一次输入一个动作**：发图期间不打断（上传逐张、判定统一、回答后一次性进入对话态，避免 log 误入对话再退出）。

## 三、交互流程总览

```
选图(1-3) ± 文字 ──发送──► [上传] 逐张上传（输入栏上方进度条 n/m；Feed 插入占位卡，显示本地预览）
                                 │ 全部成功
                                 ▼
                        [判定] 阿呆看图 + 文字 → 意图（后端 IntentRecognizer）
                    ┌────────────┴─────────────┐
                 log（带图记录）             ask（带图询问）
                    │                          │
              ┌─────┴─────┐          [判定中] 状态条「🔍 阿呆正在看图…」
              │           │                    │ ask-batch 综合看图回答返回
       纯图/陈述收尾   判定中状态条              ▼
       SnackBar 回执   「🔍 阿呆正在看图…」  [对话态] 图常驻 + 问句/回答气泡 + 输入框聚焦
       「已记下：…」     │                    │ 连续追问（文本输入）
              │           │                    ▼
              ▼           ▼            [结束对话] → 带图总结卡（ended：绿框 + 总结 + 原图 + 标签）
       带图记录卡   带图记录卡（log 分支        ▲
       （idle 沉淀）  同左，落卡）              │ 任意 settled 卡点「提问」可重开对话
       随时可「提问」──────────────────────────┘
```

**一次发送的分支决策点**（前端不做预判，全部交后端）：
- caption 为空 → 纯图，判 log（P0），收尾落卡；
- caption 非空 → 走 ask-batch（后端判定 question/log，P0 现有能力）。

## 四、场景拆分（逐步交互，含全部状态）

### 场景 A：带图记录（log）——图 + 陈述文字 或 纯图

| 步骤 | 用户动作 | 界面反馈 | 阿呆行为 |
|:----|:--------|:--------|:--------|
| A1 | 点输入栏「+」→ 弹层「图片」→ 相册多选（≤3 张，`_maxImages`） | 输入栏上方挂横向缩略图条（56px + n/3 角标 + 每张 ✕ 可移除）；hint 变绿「添加说明（可空）…」 | — |
| A2 | （可选）输入"今天的晚饭"→ 点绿色 ↑ | 键盘收起、输入栏清空、缩略图条消失 | — |
| A3 | — | 输入栏上方出现「📤 上传中 1/2」进度条（main_page L899-918 现有槽位）；Feed 底部逐张插入占位卡：**P0 增强——占位卡显示本地缩略图预览**（mediaBytes 已有，替代纯转圈） | 逐张 `uploadImage`（字节 + caption）：存原图 → GLM-VLM 理解 → 记录 + 记忆（现有） |
| A4 | — | 每张成功 → 占位卡原位替换为带图记录卡（96px 缩略图 + 用户文字 + 阿呆 summary 行） | 返回 summary（"看到你吃了日料🍣"）+ tags |
| A5 | — | 全部完成 → 进度条消失 → **P0 新增：SnackBar 自然回执**「看到你吃了日料 🍣，已记下」（替代「📷 已记录 N 张图片」；纯图则「随手一拍，已记下：傍晚的江边 🌇」） | — |
| A6 | 沉淀 | 卡片 idle 态（记录 badge + summary 行 + 缩略图），底部保留「提问」入口（随时可发起对话）；`_loadFeed()` 刷新 | — |
| A7（失败） | 网络失败 | 进度条隐藏 → 失败占位卡转 error（底栏橙字 + 「重试」按钮，`_lineRetry`），其余卡不受影响；SnackBar「图片上传失败: …」 | — |
| A8（重试） | 点「重试」 | 卡片原位恢复 loading → 重走 `uploadImage`（保留 mediaBytes，`_retryMediaUpload` L524）→ 成功替换为记录卡 | — |
| A9（部分失败+问句） | — | 全部成功后若有 pending 问句 → 补跑 ask（现有 P1-2 `_pendingAsk*` 机制保留） | ask-batch |

### 场景 B：带图询问（ask）——图 + 问句

| 步骤 | 用户动作 | 界面反馈 | 阿呆行为 |
|:----|:--------|:--------|:--------|
| B1-B3 | 同 A1-A3（选图 → 输入"这是什么菜？"→ 发送） | 同 A：进度条 + 占位卡（本地预览） | 逐张 `uploadImage` |
| B4 | — | 全部上传成功 → **先不收尾**：进度条位切换为「🔍 阿呆正在看图…」状态条（P0 新增 `_mediaJudging`，与上传进度共用一个槽位、互斥）；Feed 内卡片保持记录卡形态 | — |
| B5 | — | — | 前端调 `askBatch(imageRecordIds, question=caption)`（L582）：后端 `IntentRecognizer` 判 question → VLM 综合看图回答 → `image_qa` 沉淀 + Q/A 追加首图卡 card 文件（现有） |
| B6 | — | **P0 核心：返回 intent=question → 前端直接进入对话态**（`_enterImageChat`）：<br>① 激活首图本地卡：mode=chatting、turns=[用户问句, 阿呆回答]、mediaUrl=首图；<br>② 视图切为对话布局（`_buildActiveLayout`）：顶部「对话」badge + 关闭；图区首图 96px 居中、点击弹全图（#208）；气泡列表：问句（右绿）/ 回答（左灰 markdown）；底部「结束对话」；<br>③ 输入框自动聚焦（hint「问点什么…」绿框）；<br>④ **不刷新 Feed**（保持宿主卡身份，避免 S-2 聚合 id 漂移） | 返回 answer + intent |
| B7（追问） | 输入文字发送 | 用户气泡追加 → 「正在思考…」（loading dots）→ 阿呆回答气泡追加；图持续可见（#208） | `askMedia(imageRecordId=首图 id, question)`（L623） |
| B8（结束） | 点「结束对话」 | 返回 Feed：卡片 = ended 态（绿框 + ✓总结 banner + 标签 + 缩略图 + 「提问」入口）——**带图总结卡** | `endConversation(turns, cardId)`（L285）→ summary + tags |
| B9（判定为 log） | 文字是陈述（如"今天的晚饭"） | ask-batch 返回 intent=log → 走场景 A 收尾（SnackBar 回执 + 落卡 + 刷新） | — |
| B10（判定失败） | ask-batch 报错 | SnackBar「阿呆没看懂这张图，再试一次？」；卡片保持记录卡形态；问句不丢（点首图卡「提问」可重发） | — |
| B11（追问失败） | 网络失败 | 现有行为：loading 复位 + SnackBar 错误；气泡保留问句，可直接重发（`_appendToActiveCard` L652 catch） | — |

### 场景 C：多图（≤3 张）

| 输入 | 交互 | 与单图差异 |
|:----|:----|:----|
| 多图 + 问句 | ask-batch 一次看全部图综合回答（后端已支持）；对话态图区 **P0 只显示首图**（其余图在 Feed 中仍是独立卡）；**P1**：图区多图并排/切换 | 对话宿主 = 首图卡；图区呈现范围受限（P1 补） |
| 多图 + 陈述/纯图 | 逐张落 N 张独立记录卡（现状保留，改动最小）；**P1**：合并组卡（对齐 media-event-unification 数据层 v1.0.1） | 无对话；记录密度高（P1 组卡收敛） |
| 多图部分失败 | 成功的先落卡，失败的 error 可重试；pending 问句等全部成功补跑（现有 P1-2） | 与单图同机制，仅数量维度 |

### 场景 D：对话中看图（#208 增强评估）

- **现状（已满足）**：对话态图区 96px 原图常驻（main_page L1204-1210 `_buildActiveMediaThumb`），点击弹全图 dialog（L1297）。
- **P0**：保持现状（单图场景已完整）。
- **P1（建议做，成本低）**：多图对话图区并排（横向 Row，多张 96px 缩略图，各自点击弹全图）。前置：后端 feed `image_qa` 条目暴露引用图 id 列表（见改动点 B-6）。
- **P2（不做，评估如下）**：**圈选指图**（在图上画圈问"这里是什么"）。评估：需 ①前端画布 overlay（坐标捕获）②坐标→VLM 提示协议（后端 prompt 支持"图中区域 (x1,y1)-(x2,y2)"）③GLM-VLM 对坐标定位能力未验证。成本高、收益不确定——**文字指代已可行**（"右上角那个图标"VLM 可理解），先满足；圈选列入远期单独评估。

### 场景 E：结束沉淀

- **ask 对话结束** → `endConversation(turns, cardId=首图 id)` → 卡片 ended：绿框 + ✓总结 banner + 标签 + 原图缩略图（`_buildSummaryBanner` + `_buildMediaThumb` 已支持此组合）→ **带图总结卡**；随时可「提问」重开。
- **log 卡** 无对话，本身就是记录卡；随时可「提问」升级为对话。
- 注意：ended/总结是前端本地状态，刷新后由后端 entry 的 summary/turns 还原（现状文本卡同语义，图片卡验证即可）。

## 五、判定规则（AI 如何分流 log/ask）

**结论：文字判定优先 + 看图兜底（P1），前端不判、后端判定。**

### P0 规则（本版实现，后端零新增）

| 输入 | 判定 | 依据 |
|:----|:----|:----|
| caption 非空 | `IntentRecognizer.recognizeWithAi(caption)` → question / log | 与 `POST /records` 文本输入完全同链路（用户要求"和文字一样"）；AI 失败降级问号启发式（ask-batch L112-118 已实现） |
| caption 为空（纯图） | **log** | 拍下即记录（场景 A 语义） |

### P1 规则（增强）：纯图看图兜底

- `uploadImage` 的 VLM 理解 prompt 顺带输出 `intent`：**疑问型内容**（报错截图、界面截图、陌生物品特写、外语文本）→ `question`；其余 → `log`；`MediaRecordResult` 回传（当前硬编码 log，MediaRecordAppService L119）。
- 前端收到 `intent=question` 且 caption 空 → 进对话态，阿呆主动开场「我看到了[summary]，你想问什么？」（前端用 summary 拼装，无需新端点；后续追问走 `askMedia`）。

### 边界情况表（含规则细节）

| 输入样例 | 判定 | 说明 |
|:----|:----|:----|
| 纯图 | log（P0）；P1 按图内容兜底 | — |
| 图 + "今天的晚饭" / "出差路上随手拍" | log | 陈述句 |
| 图 + "这是什么菜？" / "这图里 K 线怎么看" | ask | 疑问句（问号/疑问词） |
| 图 + "帮我看看这图有什么问题" / "翻译这个菜单" | ask | 命令式祈使，AI 判 question |
| 图 + "把这张发票金额记下来" | log | 动作是"记录" |
| 图 + "哦" / "。" / 纯符号 | log | 文字无信息量 → 按记录（P1 可加：≤2 字且无问号 → log） |
| 多图 + 一个问题 | ask（综合） | ask-batch 一次看全部图 |

> 设计取舍：**不先看图再判**——多一次 VLM 往返 + 判定输出格式不稳定；文字判定与文本输入严格同构、确定性强。图的兜底仅在"无文字"这一信息真空处启用。

## 六、状态机（图片卡在流程中的状态）

```
                    ┌────────── 任意 settled 卡点「提问」──────────┐
                    ▼                                            │
 ┌────────────┐   ┌────────────┐   ┌──────────┐   ┌─────────────┐ │
 │media_      │──►│media_      │──►│deciding  │──►│  chatting   │ │
 │uploading   │   │uploaded    │   │(判定/回答)│   │ (对话态)    │ │
 │(占位卡)    │   │(记录卡)    │   │          │   │             │ │
 └─────┬──────┘   └─────┬──────┘   └──────────┘   └──────┬──────┘ │
   成功  │    失败       │ 判定 log/纯图                   │ 结束   │
        ▼               ▼                                 ▼        │
   ┌────────┐     ┌────────────┐                   ┌────────────┐ │
   │media_  │◄────│ idle 记录卡 │◄──────────────────│  ended     │─┘
   │error   │重试  │ (log 沉淀)  │                   │(带图总结卡) │
   └────────┘     └────────────┘                   └────────────┘
```

**与现有 CardMode（idle/waiting/chatting/ended）的关系**——不加新枚举，用组合表达：

| 流程态 | 实现（CardMode + 字段） | 说明 |
|:----|:----|:----|
| media_uploading | `mode=idle, loading=true, mediaBytes!=null, mediaUrl=null` | 现有占位卡（main_page L351-358） |
| media_uploaded | `mode=idle, loading=false, mediaUrl!=null, summary, intent=log` | 现有 `_buildMediaSuccessCard`（L426） |
| deciding | **前端瞬时布尔 `_mediaJudging`**（不进卡片状态）+ 状态条 | 避免污染 CardMode；与 `_uploadTotal` 共用渲染槽位 |
| chatting / ended / waiting | 现有 CardMode 复用 | 对话态与沉淀完全复用现有状态机（`_onAskCard`/`_closeChat`/`_appendToActiveCard`） |

> P1（可选）：加 `mediaPhase` 枚举便于测试断言与占位卡细分渲染；P0 不加，组合语义已够。

## 七、改动点清单

### 前端（apps/adai-app）

**P0 —— 核心闭环（发图 = 一次"说"）**

| # | 文件 / 位置 | 改动 |
|:--|:----|:----|
| F1 | `main_page.dart` `_onSendMedia`（L321-422） | 统一编排：uploadAll → caption 空 ? 纯图收尾 : 判定中→ask-batch。纯图收尾 SnackBar 改自然回执「已记下：{summary}」（替代 L404）。新增 `_mediaJudging` 状态切换 |
| F2 | `main_page.dart` `_flushPendingAsk`（L574-592） | 不再只 SnackBar+刷新：返回 `AskBatchResponse`；`intent=question` → 调新方法 `_enterImageChat`；`intent=log` → 回执 SnackBar + 刷新。保留 P1-2 部分失败补跑语义 |
| F3 | `main_page.dart` 新增 `_enterImageChat(cardId, turns)` | 用**本地首图卡**进对话态（不刷新 Feed，避免 S-2 聚合 id 漂移）：`_activeCardId`/`_hasActiveChat`/`_chatEnterTurnCount` 设置 + `mode=chatting` + 手拼 turns=[用户问句, 阿呆回答] + 保留 mediaUrl；`_scrollToBottom` + 输入聚焦 |
| F4 | `main_page.dart` `_buildActiveLayout`（L1166-1258） | 确认本地宿主卡（mediaUrl + 手拼 turns）渲染正常——图区（L1204-1210）与气泡（L1227-1236）已有，验证即可，预计无结构改动 |
| F5 | `main_page.dart` build 进度条槽位（L899-918） | 槽位条件改为 `_uploadTotal > 0 \|\| _mediaJudging`；文案二态：「📤 上传中 n/m」/「🔍 阿呆正在看图…」 |
| F6 | `feed_card.dart` `_buildMediaThumb`（L411-440） | P0 增强：`mediaBytes != null` 时渲染本地预览（Image.memory，复用 input_bar 的降采样模式），占位卡显示图而非纯转圈 |
| F7 | `api_service.dart` | 无新方法。`AskBatchResponse` 已含 intent/answer/recordId/imageRecordIds，够用 |

**P1 —— 增强**

| # | 改动 |
|:--|:----|
| F8 | 多图对话图区：首图 +「共 N 张」角标 + 并排/点击切换（依赖 B-6） |
| F9 | 占位卡细分：上传中角标「1/3」、失败保留本地预览 |
| F10 | 纯图看图兜底判定配合（依赖 B-5）：`intent=question` 且无文字 → 进对话态 + 阿呆开场白 |

**P2 / 后续**

| # | 改动 |
|:--|:----|
| F11 | 多图 log 组卡（对齐 media-event-unification 数据层 v1.0.1） |

### 后端（services/adai-core）

**P0 —— 契约修复/验证（S-2 聚合卡身份断裂）**

| # | 位置 | 改动 |
|:--|:----|:----|
| B1 | `MediaRecordAppService.mediaPathFor`（L125） | **修复**：入参为 `image_qa` 记录 id 时解析 content 中引用首图（复用 FeedAppService 的 IMAGE_REF 逻辑）→ 返回首图 mediaPath。修复聚合卡缩略图 404 |
| B2 | `MediaRecordAppService.askImage`/`askImages`（L140/L201） | **修复**：recordId 为 `image_qa` id 时先解析首图再取字节（修复聚合卡追问 400） |
| B3 | `FeedAppService.toFeedEntry`（L214-261） | **增强**：`image_qa` 条目附加 turns（= 首图卡 Q/A，复用 L246 逻辑）——刷新后聚合卡以"图文对话卡"形态呈现（Q/A 气泡 + 「提问」入口），与前端本地对话态语义一致 |
| B4 | `POST /conversations/end` 图片对话 | 验证：turns → summary 回写图片卡 card 文件（文本卡同路径，图片卡验证即可） |

**P1 —— 判定增强**

| # | 改动 |
|:--|:----|
| B5 | `uploadImage` VLM prompt：① summary 口语化为"阿呆回执句"（"看到你吃了日料 🍣"而非标题式"持仓截图：浦发银行"——注意 title 复用 summary，需验证卡片标题观感）；② 顺带输出纯图 intent（log/question）；`MediaRecordResult` 增加 intent 字段（当前 L119 硬编码 log） |
| B6 | feed `image_qa` 条目暴露引用图 id 列表（多图对话图区，配合 F8） |

### 测试

| 层级 | 用例 |
|:--|:----|
| 前端 widget | 带图 ask → 进对话态（turns 手拼、active 卡、不刷新）；log 回执 SnackBar；判定中状态条；占位卡本地预览；部分失败重试后补跑 ask |
| 后端 | mediaPathFor image_qa 解析；askImage 接 image_qa id；image_qa 条目带 turns |

## 八、不做清单（控制范围）

1. **圈选指图**（画圈问"这里是什么"）——需画布交互 + 坐标→VLM 协议 + 模型定位能力验证，成本高收益未验证；文字指代（"右上角那个"）已可行，远期单独评估。
2. **多图并排 / 相册组卡**——P0 不做（首图 + 独立卡）；组卡并入 media-event-unification 数据层（v1.0.1）。
3. **手动意图选择按钮**（log/ask 切换）——违背"AI 判定"方向，不做。
4. **图内 OCR 高亮 / 翻译浮层**——VLM 文本能力已覆盖，浮层交互另议。
5. **视频 / 动图 / 文件上传**——保持 image_picker 图片，≤3 张。
6. **上传并发加速**——保持逐张顺序上传 + 进度可见（并发会破坏进度语义与失败定位）。
7. **语音输入**——v2 方向，已移除 stub，不复活。
