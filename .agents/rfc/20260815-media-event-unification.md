---
title: 图文一体——媒体事件数据层统一（一次输入 = 一条记录）
date: 2026-08-15
status: implemented
decided-by: adai（2026-08-15：同意建议——层 1 展示层聚合先上生产验证，层 2 数据层整体化排入 v1.0.1，不在发布顺延期引入格式变更）
---

# 图文一体：媒体事件数据层统一

> 背景：产品决策「一次输入 = 一个事件」（阿呆 2026-08-15：图只是某种描述，图文是一个整体）。
> **展示层聚合已落地**（时间线/Feed 图文一体 + 多轮 chat 单条，commit `eb5e707`，后端 433 全绿）；
> 本 RFC 解决**数据层**：目前一次带图输入仍落盘 N 条记录。

## 一、现状（数据层仍是碎的）

| 场景 | 落盘 | 问题 |
|:-----|:-----|:-----|
| 带图陈述（3 图 + 文字）| 3 条 `image` 记录，各带 `【备注】caption` | 同一段文字写 3 份 |
| 带图询问（3 图 + 问题）| 3 条 `image` + 1 条 `image_qa` = 4 条 | 4 条碎记录 |
| 多轮 chat | 每轮问答 1 条记录（intent=question）| 数据层 N 轮 N 条 |

展示层聚合已让**显示**收敛为 1 个事件，但**数据层**（搜索命中数、记忆沉淀、统计）仍按 N 条计。

## 二、目标

一次带图输入 = **1 条记录**：

- **陈述**：1 条「图文记录」——`mediaIds` 引用 N 张图，content = VLM 理解 + 备注
- **询问**：1 条「图文问答」记录——`mediaIds` 引用 N 张图 + 问/答
- 原图文件照常落 `data/{userId}/records/YYYY/MM/media/`，`GET /records/media/{id}` 原图访问不变
- 多轮 chat 的每轮问答：继续复用卡片 turns（记录层只留首问，与展示层口径一致）

## 三、方案对比

| 方案 | 做法 | 优点 | 缺点 |
|:-----|:-----|:-----|:-----|
| **A. `mediaIds` 字段（推荐）** | ContentRecord frontmatter 新增可选 `mediaIds`，图片文件以"媒体附件"身份挂在主记录下 | 语义正确（图文一体）、搜索/记忆/统计收敛为 1、原图访问不变 | 动 freeze §2.1（MINOR）；前端需多图渲染 |
| B. 保持现状 | 数据层继续 N 条，仅展示聚合 | 零风险 | 数据层重复永久存在（搜索/记忆/统计失真） |
| C. 新复合类型 `media_event` | 全新记录类型 + 专用格式 | 最干净 | 改动最大、与 image/image_qa 双轨并存复杂 |

**推荐 A**：`image` 单图记录保留（L4 单图追问链路不动），新增/扩展 `mediaIds` 支持多图整体事件。

## 四、数据格式变更（freeze §2.1）

- **MINOR 变更**：ContentRecord frontmatter 新增可选 `mediaIds: [id1, id2]`（逗号+空格，同 tags 风格）
- `image_qa` 记录：content 引用图改为 `mediaIds` 字段承载（原「图片记录：{ids}」文本格式保留作展示，展示层正则解析继续兼容）
- 变更规则：只影响**新写入**；旧 `image` 记录读取路径（mediaPath）完全保留，无需迁移

## 五、影响面

| 面 | 改动 |
|:---|:-----|
| 后端 | `MediaRecordAppService`（recordImage/askImages 落盘重构：主记录 + mediaIds）；`RecordFileRepository`（mediaIds 读写）；Feed/Timeline（多图 mediaPaths，展示层从"首图"升级"多图"） |
| 前端 | feed_card / timeline 多图缩略图渲染（当前单 mediaPath）；点击查看全部原图 |
| 记忆 | Memory 关联主记录 id（从 4 条记忆收敛 1 条） |
| 测试 | 多模态 18 测试适配 + 展示层聚合测试升级为多图断言 |

## 六、实施顺序（分步，可随时停）

1. **step-1**：`mediaIds` 读写兼容（RecordFileRepository 解析/序列化，旧文件缺省空）——零行为变化
2. **step-2**：写入重构（recordImage/askImages 只落 1 条主记录 + mediaIds；单图 image 链路不动）
3. **step-3**：前端多图渲染（Feed/时间线 mediaPaths 数组）
4. **step-4**：展示层聚合从"取首图"升级"多图"（层 1 代码顺势简化）

## 七、决策点（已决策，2026-08-15）

> decided-by：adai 同意建议——层 1 展示层聚合先上生产验证，层 2 数据层整体化排入 v1.0.1。

1. **图片是否保留薄 `image` 记录**：✅ 保留（仅作文件索引/原图访问，原图访问/追问链路零改动）
2. `mediaIds` 引用**记录 id**：✅ 采用（与现有 findMediaPath 口径一致）
3. 本轮做 or v1.0.1：✅ **排 v1.0.1**（v1.0.0 顺延期不引入格式变更；step-1~4 届时按序执行）

---

## 八、实施记录（2026-09-22）

> 触发：用户「我要彻底打通这个链路，提升整个体验，带图对话的流程」——**提前落地**（原排 v1.0.1；v1.0.0 尚未 tag/部署）。

- **step-1 已落地**：`RecordFileRepository` 读写 `mediaIds`（**无附件不落行** → 旧格式字节零变化；旧文件缺字段 → 空列表，非 null）+ 回归（往返 / 缺字段兼容）。
- **step-2 已落地**：新增**投递级**写入 `MediaRecordAppService.recordImages`（一次投递 = **1 条主记录 + N 条薄附件**）；**旧单图路径 `recordImage` 一行未动**（兼容 + 零回归）。薄附件 `summary=图片附件` 作哨兵，防 `RecordRetryService` 把每张附件当独立记录重跑 VLM、重复沉淀记忆。
- **step-3 已落地**：双端（app / web）Feed 与 Timeline 消费 `mediaPaths`、**一卡多图并列**渲染（由 2 个子代理隔离并行实施，各带测试）。
- **step-4 已落地**：Feed 与 Timeline 的聚合从「取首图」升级为「**全部图** + 薄附件抑制」（一次投递 = 一条条目；否则时间线会出现 N 条「图片附件」）。`mediaPath` 仍为首图 → 旧前端不读新字段也能正常显示。
- **超出原方案的部分（如实记录）**：新增**投递级幂等键**（`Idempotency-Key`，治 REVIEW P1-多图2 的「超时重试 → 重复落盘」）+ **`understandMulti` 一次多图识别**（原方案只写「VLM 理解」，未定多图调用形态；逐张调用实测必然超时）。交互层细节与取舍见 `20260815-image-chat-interaction.md` §九。
