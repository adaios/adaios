---
title: 已知坑归集（Pitfalls）
description: 跨 checklists 归集的「踩过的坑」索引——症状/根因/修复/复发信号，按域分组；完整逐条在 checklists 活文档
version: 1
created: 2026-08-15
updated: 2026-09-12
status: active
lines: 95
depends-on:
  - ../checklists/guard.md
related:
  - ../checklists/review-backend.md
  - ../checklists/review-frontend.md
  - ../checklists/review-docs.md
  - ../checklists/review-knowledge.md
tags: [ai, assets, pitfalls]
---

# 已知坑归集

> **定位**：checklists 是「逐条检查方法 + 上次发现」的活文档；本文件把**已发生过的坑**按域归集为索引——AI 打开一眼看到「这个项目踩过哪些坑、复发信号是什么」。新坑由 AI 主动发现（沉淀过滤器），修复即入对应 checklists，汇总在此。
> **状态单一化（DF-06）**：待修状态只由 REVIEW/task-log 编号持有，本表不复制状态值（ADR-002）——查状态去 REVIEW，防多处复制漂移。

## 一、存储与数据安全（G1-G3 / B 系列）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| ID 秒级精度 | 同秒写入互相覆盖 | ID 生成 `yyyyMMdd_HHmmss` 无毫秒 | 加 `SSS`（G1） | ✅ 已修 | 新 ID 生成不含毫秒 |
| now() 推导路径 | 跨日复制丢轮次/旧卡归"今天" | storage 用 `LocalDate.now()` 推 filePath / parseDateTime 回退 now() | 从实体 `createdAt` 推导；缺失返回 null（G2/B29/B37） | ✅ 已修 | 新增 `now()` 回退代码 |
| 删除在降级路径 | AI 失败时删用户记录 | catch 降级路径内调用删除 | 正常业务删除豁免，降级路径禁止（G3） | ✅ 已修 | catch 块内出现 delete |
| 整文件重写并发 | 并发 RMW 静默丢更新 | Memory/TagIndex/Position 整文件重写无锁 | synchronized / per-user 锁（B14） | 状态见 REVIEW #126 | save 无锁 |

## 二、AI 集成健壮性（B11-B12/B24/B26）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| emoji 代理对 | AI 回复 emoji 抛异常丢字段 | 解析器未处理 surrogate pair | `LlmResponseParser` 按 matcher region 推进（B11） | ✅ 已修 | 新解析器不用 region |
| AI 失败删记录 | 调用失败数据丢失 | 失败路径直接删 | 失败有降级路径（B12） | ✅ 已修 | 失败→删除逻辑 |
| 哨兵复用 | summary="recorded" 被三处消费，记录无限重补 | 内容文本兼任处理标记 | 显式处理标记，禁止内容哨兵（B26） | ✅ 已修 | 新标记复用内容文本 |
| LLM 输出 JSON 夹未转义引号 | 结构化解析崩：`Unexpected character ('思')` / 期望逗号却遇汉字；**同一素材重试即成功（概率性）** | LLM 在 JSON 字符串值内直接写英文双引号（技术素材引用术语时高发），严格 Jackson 解析提前结束字符串 | ✅ **已修（2026-09-12 抓取批，双做）**：① prompt 层明确「字符串值内禁止英文双引号，引用请用「」」；② 解析层宽松兜底 `repairJson`——按「引号后是否紧跟 `:` `,` `}` `]`」判定值内引号并转义 + 删尾随逗号，且解析前先剥代码块围栏/前缀散文；修不好仍 fail-visible（素材留 `_raw/`、不产半成品）。回归网 `LearnJsonRepairTest`（11 用例：值内引号/已转义引号不误改/围栏/前缀/尾随逗号/彻底崩坏仍 fail-visible + prompt 契约） | ✅ 已修 | 解析异常信息里出现汉字/引号；失败重试就成 |

## 三、前端状态与生命周期（F 系列）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| await 后空值 | 对话态崩溃 | await 后解引用共享单例 `_activeCardId!` | 重新判空 + build 侧 null 兜底（F10/F29） | 状态见 REVIEW #205 | `!` 解引用无判空 |
| 列表重建挤掉活动卡 | 发媒体后崩溃 | `_loadFeed` 覆盖 `_cards` 但活动卡被挤出 | 校验 `_activeCardId` 仍在新列表（F29） | ✅ 已修 | 重建路径不校验 |
| 守卫只包异步 | 双击弹掉 home | onConfirm 守卫只包 async 不包 `nav.pop()` | 守卫包住闭包整体（F22） | ✅ 已修 | 副作用在守卫外 |
| 保活页陈旧 | 数据可变页面保活即陈旧 | IndexedStack initState-only 加载 | 刷新路径（F11/F41） | 状态见 REVIEW #234 | 保活页无刷新 |

## 四、文档与契约（D 系列）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| 数字散落漂移 | 测试数/端点数多处不一致 | 数字快照散在多处 | status.md 单一真相源（D20/D32） | ✅ 已修 | 新文档写数字快照 |
| frontmatter 断链 | 图谱边解析失败 | depends-on/related 相对路径错 | guard-meta M1 检测（D30） | ✅ 已修 | 新增边不校验 |
| lines 漂移 | 声明行数 ≠ 实际 | 手写 lines | guard-meta --fix 回写（D34） | ✅ 已修 | 手改 lines |

## 五、插件门控与隐私（B31-B33/B36/B40/K 系列）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| 门控旁路 | 无插件用户访问 trading/project | 只门控主入口漏写路径 | 端点清单枚举（B36/B40） | ✅ 已修 | 新端点不查 plugins |
| 隐私进 git | 真实持仓/记录进 git 历史 | 新落盘目录漏 .gitignore | `git check-ignore` 验证（B25/K8） | ✅ 已修 | 新 data/ 子目录未 ignore |

## 六、外部行情源环境（K 线/行情，2026-08-16 C2 批）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| 东财在生产被限 | 生产 buy-points/行情全走兜底（东财 K 线全部空，本地正常） | 服务器 IP（82.156.111.146）访问 push2his.eastmoney.com 返回空 | 腾讯降级兜底（KlineService 主源失败自动切）已验证生产 0 失败；判定链路本地真数据验证通过 | ⚠️ 观察中（降级兜底已生效，无需紧急处理） | 日志大量「东财 K线空」+ 腾讯兜底数据缺失 |

## 七、本地资产与生产同步（2026-08-30 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| 字体残缺版残留本地 | 本地 web 新 UI 中文显示框框（完美/案例/标注/匹配/理解 等缺字形），生产正常 | 2026-08-23 生产事故修复只替换了 `/opt/adaios/web/fonts/`，**本地三端 `web/fonts/` 未同步**（gitignore 不入库 → 无版本提示） | 重新子集化 GB2312 全量（7451 字形/1.9MB）+ 三端 web/app/admin 全替换 + `build/web/fonts/` 同步 + 字形数校验（详见 `ai-engineering/assets/projects/adai-web.md` 字体资产节）| ✅ 已修（2026-08-30）| 改字体只改一处 / 新 UI 文案出现框框 |

## 八、外部内容源与云端 ASR（2026-09-12 新增，D 形态前置实测）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| DashScope 上传文件下载失败 | 转写任务**秒级** FAILED；`paraformer-v1` 报 `FILE_DOWNLOAD_FAILED`，`paraformer-v2` 只报笼统 `SERVER_ERROR` | RESTful API 用 getPolicy 上传得到的 `oss://` 临时 URL 时，服务端需显式被允许解析 OSS 资源；**请求头缺 `X-DashScope-OssResourceResolve: enable`** | 提交 `POST /api/v1/services/audio/asr/transcription` 时带该头（官方 FAQ 藏着这条）；URL 拼法 `oss://` + `upload_dir` + `/` + `basename`；上传 multipart 字段对齐官方 SDK（`OSSAccessKeyId`/`Signature`/`policy`/`key`/`x-oss-object-acl`/`x-oss-forbid-overwrite`/`x-oss-content-type`/`success_action_status=200`）；轮询 `GET /api/v1/tasks/{id}`，成品在 `results[].transcription_url` | ✅ 已跑通（2026-09-12 生产实测 28min 视频 → 10297 字） | 任务秒级 FAILED 且错误码是下载类；不加头重试仍失败 |
| B站 dash 音频直传 ASR 必失败 | 上传返回 200，转写任务仍 FAILED；只把后缀改成 `.m4a` 也不认 | `playurl` 的 `dash.audio[].baseUrl` 下的是 **fMP4 分片容器**（`ftyp`+`moov`+`moof`/`mdat`），ASR 解不了 | 先转码：`ffmpeg -i x.m4s -vn -ac 1 -ar 16000 -b:a 32k x.mp3`（28min→6.7MB）；**生产服务器未装 ffmpeg** → 经 SSH 拉到本地转码再回传（不为一次性转码动生产系统包） | ✅ 已跑通 | 上传成功但转写失败；只改后缀不改容器 |
| B站音频下载缺 Referer | 音频 URL 直连 403（150B 错误页），转写链路拿不到音频 | B站 CDN 校验 `Referer`；`playurl` 的 baseUrl 带时效签名**也不够**，无 Referer 一律 403 | 抓取层 `downloadAudio` 固定带 `Referer: https://www.bilibili.com` + 浏览器 UA | ✅ 已修（2026-09-12 实测：带 Referer 206 可分段下载 / 不带 403） | 音频 URL 拼对了却 403；只验元数据不验音频就以为通了 |
| B站字幕接口默认拿不到 | `player/v2` 的 `subtitle.subtitles` 为空，误判「视频无字幕」 | 未登录请求时常不返回 AI 字幕列表 | 先探测；确无字幕再走转写（**实测多数视频确实无字幕**——这正是 D 形态必须对接 ASR 的依据） | ⚠️ 观察 | 字幕列表空但视频有 CC 标识 |

## 九、装配与构建（Spring / Gradle，2026-09-12 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| 同一个 Bean 写两个构造器 | 单测验得好好的，全量跑 `contextLoads` 才炸：`BeanInstantiationException: No default constructor found` → 整个 Spring 上下文起不来 | Spring 对**多构造器**的 Bean 无法自动选择（单构造器才隐式注入）；为测试方便加一个包级「测试构造器」就踩中 | 删掉测试构造器，让生产构造器参数化（base-url / interval 等本就该可注入——本轮 `BilibiliFetcher`/`ArticleFetcher`/`DashScopeAsrClient` 即这样修掉，顺带缩小 API 面） | ✅ 已修（2026-09-12） | 新增一个「只为测试用」的构造器；单测绿但 `AdaiCoreApplicationTests.contextLoads` 红 |
| Gradle wrapper 写不进 `~/.gradle` | `FileNotFoundException: ...gradle-8.14.5-bin.zip.lck (Operation not permitted)`，测试根本跑不起来 | 沙箱只放开 workspace 写权限，而 Gradle 的 wrapper 发行包/依赖缓存固定在用户主目录 | 给构建命令开更宽文件权限（本项目沙箱口径：full access）；不要用 `GRADLE_USER_HOME` 指到仓库内（会重新下载整包） | ✅ 已解（2026-09-12） | 报错路径在 `~/.gradle/`；构建还没编译就先失败 |

---
**追加方式**：AI 在开发/审核中发现新坑 → ①入对应 checklists（活文档）②本文件按域补一行（索引）。两条都要，防止只入一处。
