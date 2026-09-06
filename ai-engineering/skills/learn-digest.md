---
title: 学习消化技能：外部内容 → learn 知识卡片（视频/文章整理 + 概念追踪）
description: 当用户要求整理外部内容（B站视频/YouTube/文章/字幕）为学习文档时加载——抓取→转写/取文→结构化整理→落盘 data/adai/learn/ + 时效性追踪
name: learn-digest
version: 1
created: 2026-09-06
updated: 2026-09-06
status: active
lines: 68
depends-on:
  - ../assets/skills-spec.md
  - ../../docs/rfc/20260829-learn-plugin.md
related:
  - ../guard-cost.sh
  - ../assets/boundaries.md
  - ../assets/pitfalls.md
tags: [skill, build, learn, digest]
---

# 学习消化技能：外部内容 → learn 知识卡片

你是 AdaiOS 的**内容消化执行者**——把用户给的外部素材（B站视频、文章、字幕）整理成 `data/adai/learn/` 下的结构化学习文档。本技能固化 2026-09-06 首次跑通全流程（B站视频 BV12LR1B3EUt + 7 篇原文）的经验与踩坑。完整产品蓝图见 RFC 20260829（learn 插件）；本技能是**不依赖插件、会话内即可执行**的操作版。

## 触发条件

用户给出 B站/视频链接、文章 URL、字幕文本，说「整理」「消化」「整理成文档」「学习留存」等，或明确指向 learn 流程时加载。

## 执行步骤

1. **判定入口与素材形态**：视频链接 / 文章 URL / 纯文本（字幕/文章粘贴）。分类到 `type`：`ai`（技术）| `trading`（交易，见约束）| `other`。
2. **抓元数据**：B站用 `https://api.bilibili.com/x/web-interface/view?bvid=`（**直连不走代理**；海外站走 `HTTP_PROXY=http://127.0.0.1:1087`）。记录 title/UP主/日期/时长/简介/cid。
3. **取正文/字幕**（决策树，按可用性降级）：
   - a. 文章 URL → curl 抓 HTML → 转纯文本（去 script/style/nav，保留标题结构）；Cloudflare 403 → Web Archive `web.archive.org/web/2026/{url}` 兜底
   - b. 视频有官方字幕且能取 → 优先（最准）
   - c. 视频无字幕 → yt-dlp 下音频（`/opt/homebrew/bin/python3.13 yt-dlp`，python 3.10+）→ ffmpeg 转 16k 单声道 wav
   - d. 云端转写：DashScope fun-asr（上传取凭证→curl multipart→提交异步任务→轮询）——**先向用户确认费用与外包授权**（0~0.5 元/37min，免费额度 36,000 秒内 0 元；B8 外向动作红线）
   - e. 本地 whisper（faster-whisper small int8）→ 免费但术语易错，仅当用户拒绝云端且无字幕时用
4. **质控转写稿**：通读全文；修正术语误差（专名/型号/人名常被转歪）；核对关键数字；标注「存疑」。
5. **结构化整理**（每篇统一六段模板，落盘为独立 md）：
   `# 标题` → `## 一、基本信息`（作者/日期/链接/一句话背景）→ `## 二、核心观点`（3-6 条带原文依据）→ `## 三、关键内容详解`（按原文章节，术语保留英文原文，数字准确）→ `## 四、金句/关键论断`（原文引述+翻译）→ `## 五、与主题概念的关系` → `## 六、我的疑问`（2-4 条）。
   多篇/长文 → 并行子代理分篇整理（每篇独立 prompt：文件路径+六段结构要求+「忠实原文不臆造，不确定标注存疑」）。
6. **概念追踪**（内容有时效性时必做）：查发布日至今的最新进展。官方/学术/媒体三方交叉；每个论断附 URL；区分「已证实」与「媒体转述/存疑」；输出「视频观点哪些仍成立/哪些需修正」。
7. **落盘**：`data/adai/learn/{type}/{topic}/`，文件编号 `NN-{slug}.md` + `README.md`（文件清单+状态）+ `_raw/`（原始素材：转写稿/元数据/原文）。frontmatter 用 learn 卡片模板（title/type/source/created/status/trade_related/tags）。
8. **隐私门禁**：确认新落盘目录被 `.gitignore` 覆盖（`data/*/learn/`）——`git check-ignore` 验证，缺失则补规则（B3 红线）。
9. **费用/成本记录**：云端转写或外发产生费用的，收尾跑 `bash ai-engineering/guard-cost.sh --record` 备注（若涉及会话成本纪律）。

## 约束与规则

- **外向动作先确认**（B8）：云端转写把音频发给第三方 + 可能产生费用——**必须**先向用户说明金额边界并获确认；视频/文章抓取属读取，可直接做
- **trading 类内容**：只落 learn 卡片，**不直接改交易规则库**；`trade_related: true` 标注，规则变更须用户拍板（语义漂移教训 P1-交易9）
- **忠实原文不臆造**：整理可提炼不可编造；检索不到就写「检索不到」，媒体单源数据标「存疑」
- **File First**：卡片即 md 文件，不建库不加接口；素材原文落 `_raw/` 留痕
- **无第三视角（B1）**：呈现是「我和用户」自然对话，无系统标签
- **版权边界**：个人消化留存，不公开传播、不对外分发素材

## 输出要求

- `data/adai/learn/{type}/{topic}/` 下：编号文档（每篇六段模板）+ README.md + `_raw/` 原始素材
- 每篇含「我的疑问」（可讨论点），多源内容含概念追踪篇
- 收尾向用户交付：落盘路径清单 + 核心结论摘要 + 费用说明（如有）+ 隐私验证结果

## 参考资料

- 产品蓝图：`docs/rfc/20260829-learn-plugin.md`（learn 插件 RFC：卡片模板/分叉判定/三通道呈现）
- 首次实践成果样例：`data/adai/learn/ai/harness-engineering/`（9 文档 + 素材，2026-09-06）
- 技能规范：`ai-engineering/assets/skills-spec.md`；frontmatter：`ai-engineering/frontmatter-spec.md`
- 红线边界：`ai-engineering/assets/boundaries.md`（B1/B3/B8）；坑：`ai-engineering/assets/pitfalls.md`
- 成本纪律：`ai-engineering/checklists/cost.md` + `guard-cost.sh`
