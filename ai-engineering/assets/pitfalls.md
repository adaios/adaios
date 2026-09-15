---
title: 已知坑归集（Pitfalls）
description: 跨 checklists 归集的「踩过的坑」索引——症状/根因/修复/复发信号，按域分组；完整逐条在 checklists 活文档
version: 1
created: 2026-08-15
updated: 2026-09-15
status: active
lines: 168
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

| 出站抓取无白名单（SSRF） | 服务端替用户访问任意地址：内网站点/管理口被读走并回显，云元数据地址可换出临时凭证 | 「抓取」被当成普通读取，忘了目标来自**用户输入**（还叠加 `followRedirects`：一跳就落到内网） | 出站白名单：拒私有/回环/链路本地/元数据网段与非标准端口；**关自动重定向、逐跳复检**；第三方响应给的地址（字幕/快照）也要收敛域名白名单；响应体加上限（防大文件打爆内存） | ✅ 已修（2026-09-12 learn 抓取批，对抗审查 P0-1） | 新增任何「用户给 URL，服务端去取」的功能；播 `allow-private-hosts` 这类测试开关进生产 |
| 检查-再动作竞态（并发花钱） | 两端同时点「确认」→ **同一个付费动作执行两次**（重复计费/重复扣额）；连点提交 → 双任务、后一个把前一个的上下文覆盖成孤儿 | `get → 判断 → put/resume` 三步在并发下都可双双通过检查；后台池大小**不构成**保护（竞态发生在 HTTP 线程） | 状态转移用 `ConcurrentHashMap.compute`/CAS 原子完成（本项目 2026-09-12 learn 抓取批即此修法），配多线程回归断言「恰好一个胜出」 | ✅ 已修（2026-09-12） | 新写「先判断再改状态」的提交/确认/领取逻辑；只做单人点击测试就以为安全 |

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

## 十、多写入方与契约错位（2026-09-12 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| 同目录两个写入方，读用精确名、写用算出来的路径 | **半读半写**：别人写的文件「看得见但读不全」（段名/字段名对不上 → 关键段全空），一改就报「不存在」这种莫名其妙的错 | 两套契约共存（一方 `{type}/{topic}/NN-*.md` + `## 核心观点（一句话）`，另一方 `{type}/{date}_{title}.md` + `## 核心观点`），而读侧按精确名取值、写侧按自己算出的路径读文件 | 读侧**容错**（段名去编号前缀/括号后缀；正则放开并允许空行与加粗）；写侧加**守卫**（先定位文件真实位置，与算出的路径不一致 → 只读 + 人话拒绝）；**不做语义改名**（不同名的段不冒充别的段） | ✅ 已修（2026-09-12 learn 读侧对齐批） | 新功能与既有产物格式共存；读侧精确匹配 + 写侧算路径；「卡片不存在」出现在明明列表里看得见的卡上 |

## 九、装配与构建（Spring / Gradle，2026-09-12 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| 同一个 Bean 写两个构造器 | 单测验得好好的，全量跑 `contextLoads` 才炸：`BeanInstantiationException: No default constructor found` → 整个 Spring 上下文起不来 | Spring 对**多构造器**的 Bean 无法自动选择（单构造器才隐式注入）；为测试方便加一个包级「测试构造器」就踩中 | 删掉测试构造器，让生产构造器参数化（base-url / interval 等本就该可注入——本轮 `BilibiliFetcher`/`ArticleFetcher`/`DashScopeAsrClient` 即这样修掉，顺带缩小 API 面） | ✅ 已修（2026-09-12） | 新增一个「只为测试用」的构造器；单测绿但 `AdaiCoreApplicationTests.contextLoads` 红 |
| Gradle wrapper 写不进 `~/.gradle` | `FileNotFoundException: ...gradle-8.14.5-bin.zip.lck (Operation not permitted)`，测试根本跑不起来 | 沙箱只放开 workspace 写权限，而 Gradle 的 wrapper 发行包/依赖缓存固定在用户主目录 | 给构建命令开更宽文件权限（本项目沙箱口径：full access）；不要用 `GRADLE_USER_HOME` 指到仓库内（会重新下载整包） | ✅ 已解（2026-09-12） | 报错路径在 `~/.gradle/`；构建还没编译就先失败 |

## 十一、账实一致性（2026-09-12 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **券商文件里有权威字段却没被解析**（本次：当日盈亏） | 账户卡的「当日盈亏」长期不准：用户查证时文件里那列 **Σ = −1759.00**（与逐股复算一字不差），卡片上却是 **−2837.00**——权威值一直在用户手上，系统从来没用过 | 前端持仓解析器只定位 `symbol/name/quantity/cost` **四列**；后端入参 `PositionImportItem` 也没有该字段；唯一读「当日盈亏」的是 `parseCash`（资金股份查询），而**那份导出偏偏没有这一列**（有这一列的是「持仓股」导出）→ 系统永远退回自算，而自算会错 | `POST /positions/import` 加 query `todayPnl`（前端全表求和，**含 0 股行**）+ 后端三闸才写（值空不写 / 无快照不写 / **文件日≠快照日不写**）+ 与既有值不同则 WARN 记录两口径差 | ✅ 已修（2026-09-13） | 新增/改动「券商文件 → 系统字段」链路时只挑自己需要的列；两个相似格式的导出（持仓股 vs 资金股份查询）列集不同却没交叉核对；某字段在 UI 上一直是「自算值」而没人问「券商是不是本来就给了」 |
| **日历判定「靠调用方前提」**（周末盲的 `isTradingDay`） | 周六的涨跌被写成「当日盈亏」并挂了两天：2026-09-12（周六）11:00 导入 09-11 历史成交 → 触发 `refreshTodayPnl` → 用**周六的日期** + 周末行情接口给的「最后两个交易日收盘」+ 当时**被双计污染**的持仓 → 算出 −2837.00 落盘 | `isTradingDay` 只查法定节假日表（表里没有周末），其正确性依赖一个**没写在签名里的前提**——「调用方都是工作日 cron，周末由 cron 排除」。一旦被 HTTP 触发的路径复用，前提失效，周六就成了交易日 | 拆成两个方法并把前提写进 javadoc：`isTradingDay`（仅节假日，**只许 cron 调用方用**）+ `isTradingDayStrict`（周末 + 节假日，**自证**，供非 cron 路径）；`refreshTodayPnl` 改用后者并加「非交易日不重算」闸 | ✅ 已修（2026-09-13） | 新增调用某个「日历/环境判定」方法前没读它的 javadoc 前提；方法名声称的语义（「是否交易日」）与实现（「是否非节假日」）不一致；测试结果依赖「今天是不是工作日」 |
| 防重机制 fail-open（读不到状态就继续跑） | 线上一片安静，账却慢慢错：券商快照防重（锚定）因锚定文件不存在而**整条失效**，导入把已含在快照里的成交重放一遍 → 持仓与现金双计（2026-09-07 −3.19 万 / 09-09 −1.27 万 / **09-12 复发 −2.67 万**，三天后靠人肉眼发现） | 防重型机制的「状态读不到」被当成「不需要防重」（`find()` 缺失 → 空锚定 → 继续按旧行为重放）；且修复只改了新代码路径，**没有存量回填、没有运行时自检、没有失败可见**（REVIEW 里写「已修」，生产上机制根本没在跑） | 三件事一起做：①**fail-closed**（锚定未知 + 已有账目状态 + 需要改账的增量 → 拒绝并指路，只留显式逃生口 `mode=append`）②**运行时自检**（`GET /trading/anchor`、`GET /trading/integrity`；deploy-gate 部署后 probe 存在性）③**存量回填**（`PUT /trading/anchor` 显式补锚定日与持仓基线） | ✅ 已修（2026-09-12 交易账实一致性批，RFC 20260912） | 新增/改动任何防重·防双计·配额类机制时只写「正常路径」；机制状态读不到就降级继续；修复后没有回填脚本/自检端点/告警出口 |
| 真实成交被拒即丢（状态不准的代价转嫁给数据） | 用户明明有成交，系统里查不到：导入时逐笔失败只写 WARN 日志并计入「去重跳过 N」，3 笔真实卖出（2026-09-03 600487 400 股 / 09-04 000776 600 股 / 09-08 000831 800 股）反复导入反复消失 | 回放时用「当前持仓」判定可归属性，不足即 `throw` → 上层 `catch(TradingException)` 后 `skipped++`：把「系统状态不准」当成「这笔不该存在」，而真实成交是用户的真金白银 | 拆开两件事：**记账**（真实成交一律落流水，永不丢弃）+ **归属**（能不能并入持仓/现金是派生问题）。归不上的行 → 落流水 + `rejected` 行级明细（人话原因）+ ERROR 日志 + 对账闸门报缺口 | ✅ 已修（2026-09-12） | 导入/回放/批量的失败分支只 `log.warn` 或只累加「跳过/失败数」；用户看不到**哪一笔**没进去；`catch` 块里把业务数据丢掉 |
| 同笔跨来源双落（幂等判定不对称） | 同一笔成交在流水里出现两次：白天截图/记录归集落的行没有成交编号，收盘导入的同一笔有编号 → 两条并排，持仓被卖成负数（600206 一度 −600 股） | 幂等判定分了两条路：有编号的行只查编号、无编号的行才查指纹——跨来源（截图/记录 vs 券商导出）必然一边有编号一边没有，于是永远互相看不见 | 幂等判定**统一**：orderId → 指纹（`symbol·direction·entryDate·price·volume`）双键，且**有编号的行也查指纹**；命中即**合并回填**（补 orderId/fee/成交时间）而不是丢弃或新增；同价同量但成交时刻明显不同（>1 分钟）视为两笔真实成交，不得合并 | ✅ 已修（2026-09-12） | 同一实体的多个写入来源各写一套去重逻辑；「有编号才查编号」这类分支不对称；导入结果只给总数不给逐笔归属 |
| 条件导出的两份实现 API 面不一致（只有 build 那个平台才炸） | `flutter build web` 编译失败：`No named parameter with the name 'httpClient'`；而 `flutter test` 与 iOS 构建全绿——**测试永远覆盖不到另一个平台分支** | `sse_client.dart` 用条件导出 `export 'sse_client_io.dart' if (dart.library.js_interop) 'sse_client_web.dart'`，要求两份实现的构造签名/方法签名严格一致；实际 io 侧有 `SseClient({http.Client? httpClient})`、web 侧只有 `SseClient()`，且 web 目标**从未构建过**（PWA 是第一次）→ 断链长期潜伏 | 补齐 web 侧同名可选参数（fetch 渐进响应用不到 `package:http`，参数标注为占位并写明无效原因）；**根本解是给门禁加「每端各跑一次 build」**——test 覆盖不到平台分支 | ✅ 已修本次断链（2026-09-13 PWA 装机批）；门禁补 web build 待排（REVIEW P2-工程1） | 新增条件导入/条件导出（`if (dart.library.*)`）；只加参数到其中一份实现；某端只跑 test 不跑 build；某端长期「构建过但没人再构建」 |
| 同仓库并发会话收尾（git add -A 卷走别人的活） | 本批工作区里 9 个文件（含新增测试与脚本）被**另一个会话的无关提交**一并带走（2026-09-13 实际发生：PWA 批被卷进 `fix(learn): 纠正额度/报价口径`），本批登记随之缺失 | 两个会话同时在同一工作区干活；收尾方用 `git add -A`/`--all` 而非显式路径 → 把对方尚未提交的改动当成自己的提交；更坏的分支是双方都 `-A` + `checkout`/`stash`，会直接吞掉对方未提交的工作 | ① 收尾提交**显式列路径**（`git add <明确路径>`），不用 `-A`；② 同一时刻只允许一个写仓库的会话；③ 收工前 `git status` 必须干净再离场 | ⚠️ 待用户拍板是否写进 ship 流程为硬规则（REVIEW P2-工程2） | 提交信息与改动内容不匹配；`git log --stat` 里出现与本批无关的文件；发现「我的文件不见了」但 git 历史里又有它 |
| 解析失败行被静默跳过 × 全量覆盖落盘（漏一行 = 删一条真数据） | 用户「明明三只持仓，只导入 2 只」：通达信持仓快照 4 行（3 只有持仓 + 1 行 0 股残留）只落 2 只，界面零提示；被丢的 600601 成本为**负数**（−5.078，做 T/分红摊出来的，合法） | 两件事各自「看起来合理」地凑成了数据丢失：①解析器把「不认识的值」当成「我这行不要了」→ `continue` 静默跳过；②落盘走的是 `replace=true` 全量覆盖（以文件为准）→ 文件里没有的行被当作「已清仓」**删除**。两者叠加时，**解析器的一个宽容/苛刻判断直接变成一次静默删除**，而用户和日志都看不到 | ①解析器只把「取不到数」当错误，语义判断（负成本/0 股）交由业务规则区分对待；②**调用方 fail-closed**：只要有一行没解析成功，就**不用这份文件做全量覆盖**（拒绝 + 逐行摆原因 + 不发请求）；③「跳过的行」必须显式回传（0 股归「已清空跳过」并告知，不能混进 errors 也不能消失）；④落盘前的守卫按「要读的最大列下标」校验，短行报人话而不是崩 | ✅ 已修（2026-09-13 负成本持仓批） | 新增任何「解析文件 → 全量覆盖落盘」的链路（导入/同步/重建）时只测正常文件；解析器里出现 `continue` 跳过而不记录原因；errors/skipped 收集了却没有消费方；用户报「N 条变成 M 条」而日志里查不到被丢掉的那条 |

## 十二、iOS 构建·签名·分发（2026-09-13 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **付费后 Xcode 不重签描述文件** | 升级付费开发者账号后重新构建，App 仍是 **7 天**过期（`embedded.mobileprovision` 起止日一字未变）；会员权益明明已生效、构建日志全绿 | Xcode 优先复用**本地缓存且尚未过期**的旧 profile（免费期那份还剩几天寿命），压根不向 Apple 服务器按新会员状态重新签发 | 删掉 `~/Library/Developer/Xcode/UserData/Provisioning Profiles/*.mobileprovision` 再构建——无 profile 可用时 Xcode 只能去服务器要新的（实测立即拿到 1 年版） | ✅ 已解（2026-09-13 iOS 开发者账号批） | 「权益变了但签名没变」；只对比代码/配置不查 profile 实际起止日；以为重新构建＝重新签名 |
| **Flutter 引擎比目标 iOS 旧 → debug 装机必闪退** | 装完点开即闪退（`EXC_BAD_ACCESS` / `KERN_INVALID_ADDRESS at 0x0`），崩溃栈落在 `-[FlutterViewController createTouchRateCorrectionVSyncClientIfNeeded]`；**同一份代码 release 构建却完全正常** | Flutter 3.44.6 的引擎（2026-06-30 构建）早于设备 iOS 26.6.1；该函数为 ProMotion 高刷屏做触摸采样率校正，**debug 才有此代码路径**（release 不走）→ 引擎与 OS 版本错配 | 升 Flutter 3.44.6 → **3.47.4**（引擎 2026-09-03，晚于 iOS 26）；**过渡期**：iOS 真机先用 `flutter build ios --release` 安装绕开，代价是失去热重载 | ✅ 已解（2026-09-13） | 只装 release 就断定「iOS 没问题」；debug/release 行为不一致时当成偶发；Flutter 版本比目标 OS 老 |
| **`flutter upgrade` 内部的 git fetch 不读 shell 代理** | `HTTP_PROXY`/`https_proxy` 都在环境里、`curl -x` 走代理 200，但 `flutter upgrade` 仍报 `LibreSSL SSL_connect: SSL_ERROR_SYSCALL in connection to github.com:443` | Flutter 工具链内部调 `git fetch --tags`，而 **git 不认 shell 的 `*_PROXY` 环境变量**（需显式 `git config http.proxy`） | 给 SDK 仓库单独配（不动全局）：`git -C <flutter-sdk> config http.proxy http://127.0.0.1:1087`；引擎下载另开国内镜像 `FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn` | ✅ 已解（2026-09-13） | 「curl 通但 git 不通」；只在 shell 里 `export` 代理却没落到 git config |


## 十三、推送与 APNs（2026-09-13 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **FlutterAppDelegate 已遵循 UNUserNotificationCenterDelegate** | Swift 里写 `extension AppDelegate: UNUserNotificationCenterDelegate { func userNotificationCenter(...) }` → 编译报 **`Redundant conformance of 'AppDelegate' to protocol 'UNUserNotificationCenterDelegate'`**，同时四个方法报 **`Overriding declaration requires an 'override' keyword`** | Flutter 的 `FlutterAppDelegate` **本身已遵循该协议**（头文件里查不到——`FlutterAppDelegate.h` 只列 `UIApplicationDelegate, FlutterPluginRegistry, FlutterAppLifeCycleProvider`，实现藏在编译好的 framework 里），而它在实现里把通知回调**转发给注册为通知类插件的 FlutterPlugin** | 四个方法改写进类体并加 `override`（不放 extension）；**刻意不调 super**——本项目无任何通知插件（pubspec 无 flutter_local_notifications / firebase_messaging），调 super 反而要处理「插件未处理时 completionHandler 可能已被调用」的双调用风险；**将来引入通知类插件时必须改成「先 super 转发、未处理再自己 completionHandler」** | ✅ 已解（2026-09-13 APNs 批） | 只读头文件就断言「父类没实现这个协议」；`extension X: 协议` 编译报 redundant conformance；把「头文件没有」等同于「没有实现」|
| **`#if DEBUG` 判 APNs 环境必错** | 推送全部被 APNs 丢弃（`BadDeviceToken`），但代码看着天经地义 | APNs 有 **sandbox / production 两套互不相通**的网关，token 只在自己那套有效。本项目 iOS 装机是 **`flutter build ios --release` + development 描述文件** = **release 优化 + 沙箱环境**，`#if DEBUG` 会判成生产 → 拿沙箱 token 往生产网关送 | 不猜：**Swift 里读签名打进包里的 `embedded.mobileprovision`**（解出 XML plist 取 `Entitlements.aps-environment`），读不到才回落 sandbox；后端把 `environment` **跟着 token 存**并按它选网关 | ✅ 已解（2026-09-13） | 用构建配置（debug/release）推断**运行环境**；新增任何「按环境选地址」的逻辑时假设一维；送错网关只回一个不带上下文的 `BadDeviceToken` |
| **APNs JWT 直接送 Java 的 ECDSA 签名 → 401** | `403 InvalidProviderToken` / `401`，**报错完全不含「签名格式」线索**，且换 key、换 keyId、对时钟都无效 | Java 的 `Signature.getInstance("SHA256withECDSA")` 输出 **DER**（`SEQUENCE{INTEGER r, INTEGER s}`），而 JOSE/ES256 要求 **定长 raw R\|\|S（各 32 字节）**。DER 的 INTEGER 是**有符号大端**：最高位为 1 时多一个 `0x00` 前导字节（约 50% 概率），数值小时又不足 32 字节 | 自实现 `derToJose`：两侧分量**去前导 0 → 右对齐补零到 32**；单测覆盖「有符号补位」与「分量不足」两类边界 + **用公钥真验签**（不是只断言长度） | ✅ 已解（2026-09-13） | 加密相关失败只断言「不抛异常」而不验签；格式转换没有边界用例；把「换凭据无效」当成凭据问题（其实一直是编码问题）|
| **Jackson 默认忽略尾部多余内容** | 被截断/写坏的文件（`[]]`、`[...] 垃圾`）**解析成功并读出前半段**——「损坏」变成「读出一部分」，静默丢数据 | `DeserializationFeature.FAIL_ON_TRAILING_TOKENS` **默认关闭** | 对「损坏必须被发现」的写路径显式 `.enable(FAIL_ON_TRAILING_TOKENS)`（并用反例单测锁死）；读路径仍可宽容（降级为空） | ⚠️ 本批只修 `PushDeviceFileRepository`；其它仓储同型**未逐一核**（REVIEW P2-工程4）| 用「能不能解析成 JSON」当损坏判据；只测「整段乱码」，不测「合法前缀 + 垃圾后缀」|
| **付费开发者账号 ≠ 能力自动可用** | 以为付了钱就啥都有了；实际推送/后台/小组件等**能力是逐项声明+注册**的，缺一道就静默失败 | 能力需要三处同时具备：① `Runner.entitlements` 声明 `aps-environment`；② pbxproj 挂 `CODE_SIGN_ENTITLEMENTS`（**三个 Runner 配置都要**，只挂 Debug 会让装机用的 release 没能力）；③ App ID 上该能力被开启 + 描述文件重签 | 三处补齐；实测 `flutter build ios`（自动签名）会自动去 Apple 侧开启能力并重签描述文件（`codesign -d --entitlements` + `embedded.mobileprovision` 双取证） | ✅ 已解（2026-09-13） | 只加 entitlements 文件不挂 pbxproj；只挂 Debug 配置；改完不验产物 entitlements |
| **付费账号到期 = 周期性「打不开日」** | 2027-09-13 之后 App 会像免费签名 7 天过期那样**直接打不开**（同型事故换了个周期） | 描述文件有效期 1 年，**不自动续期就不会续签**；证书/描述文件失效 → 系统拒绝启动已安装 App | 到期日写进文档（`backend-deployment.md` §9）+ pitfalls；**仍缺日历提醒**（REVIEW P2-APNs5） | ⚠️ 部分缓解 | 把「升级成 1 年」当成「解决了」；只有文档没有会主动叫人的提醒 |


## 十四、iOS 外部入口（URL scheme / App Intents，2026-09-13 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **只接 `openURLContexts` → 冷启动静默丢内容** | 点链接/唤起 App **确实打开了**，但内容没了（看到一个空的记录框、或什么都没发生）；运行中（warm）却完全正常 | iOS 的 URL 有**两条互不重叠**的送达路径：App 已在跑 → `scene(_:openURLContexts:)`；**App 没在跑 → `scene(_:willConnectTo:options:)` 的 `connectionOptions.urlContexts`**。只实现前者，冷启动那条就没人接——而且不报错 | 两条都实现（`SceneDelegate`）。判据：**任何「从外部唤起 App 并带数据」的功能，必须同时问「冷启动时数据从哪来」** | ✅ 已解（2026-09-13） | 只测「App 开着时点一下」；把「App 起来了」当成「功能生效了」 |
| **`FlutterSceneDelegate` 的 scene 方法：头文件没有，但必须 `override` 且必须调 `super`** | 不写 `override` → 编译报 `Overriding declaration requires an 'override' keyword`（说明 Swift 其实看得见）；写了不调 `super` → **`willConnectTo` 里 Flutter 的引擎装配不执行，App 起不来/白屏** | Flutter 把实现藏在编译好的 framework 里（`FlutterSceneDelegate.h` 只暴露 `window`，`nm` 才能看到 `scene:willConnectToSession:options:` 等）。它的实现负责转发给「场景生命周期插件」+ 引擎装配 | 方法加 `override` **并调 `super`**（与 AppDelegate 的通知回调**相反的取舍**——那边刻意不调，因为无通知插件且要避免 completionHandler 双调用；**每个 API 都要单独判断，不能照搬结论**） | ✅ 已解（2026-09-13） | 把「头文件没声明」等同于「父类没实现」（也可能是「实现但没暴露」）；照搬另一个 API 的 super 取舍 |
| **App Intent 冷启动时引擎还没起来 → 投递丢** | 用 Siri 记一笔，App 起来了但内容没进去（尤其 App 被杀掉后再唤起） | `openAppWhenRun = true` 时 `perform()` 与 Flutter 引擎初始化**没有先后保证**：引擎没好时 MethodChannel 为空，直接投就是丢 | **先落 UserDefaults，再发同进程通知**：引擎就绪 → 通知即刻投；未就绪 → AppDelegate 不消费，Dart 起来后 `takePendingEntry` 兜底取。drain 即清空 → 天然消费一次（不会重复记两条） | ✅ 已解（2026-09-13） | 只走 MethodChannel 投递一次性事件；不做「事件可能早于监听者」的假设 |
| **冷启动的启动 URL 被 iOS 送两遍 → 同一句话落两条记录** | 冷启动点链接：App 打开了、内容也对，但**同一条记录出现两次**（实测两条相隔 516ms）。warm 路径完全正常 | 冷启动时 iOS 会把「启动用的那个 URL」**同时**经 `scene(_:willConnectTo:options:)` 的 `connectionOptions.urlContexts` **和** `scene(_:openURLContexts:)` 送达（两条路径都接就会被处理两次）。这是 OS 的送达方式，不是用户动作 | 在**入口处**按 (action, text) 去重（窗口 3 秒——人不可能 3 秒内用同一条链接说两遍一样的话，而两次送达必然在 1 秒内）。**去重放在 URL 入口而不是 `stash`**：App Intent 是用户亲口说的，内容相同也必须每次都记 | ✅ 已解（2026-09-13） | 「接了两条路径」就当万事大吉，不做「同一次外部动作被送两次」的假设；去重位置放错层（把用户主动动作也吞掉） |
| **scene 生命周期下 `application(_:open:options:)` 根本不会被调用** | URL 处理写在 AppDelegate 里 → 点了没有任何反应，日志也干净 | 用了 `UIApplicationSceneManifest`（scene 生命周期）的 App，URL 一律由 SceneDelegate 收；AppDelegate 那几个 `application(_:open:)` 是**非 scene 时代**的入口 | URL 处理放 `SceneDelegate`；AppDelegate 只留「与 scene 无关」的职责（通知、启动配置） | ✅ 已解（2026-09-13） | 文档抄来的示例没确认是 scene 还是非 scene 架构 |

## 十五、AI 工程工具链（harness 自身，2026-09-14 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **`$VAR` 紧跟全角标点 → bash 把标点字节并进变量名** | `guard-tools.sh: line 77: N_SKILLS?: unbound variable`——变量上一行明明赋过值却报未定义；`set -u` 下脚本当场中止，看代码完全正常 | 非 UTF-8 locale（`LANG` 未设 / 为 `C`——cron、git hook、部分 CI 的默认）时 bash 不把多字节字符当词法边界：`$N_SKILLS）` 里 `）`（`EF BC 89`）的首字节被当成变量名的合法字符，于是去找名为 `N_SKILLS\xef` 的变量 | 变量一律用 `${...}` 界定（`${N_SKILLS}` 而非 `$N_SKILLS`）；**凡中文文案里嵌 shell 变量，一律加花括号**。**已机器化（2026-09-14）**：`scripts/lint-shell-vars.py` 按 shell 词法扫描（单引号/注释/`\$` 转义不报，`${}` `$()` `$?` 不报），挂 `guard-tools.sh` T6 + git pre-commit 第 4 层——**落地当轮扫出全仓 18 处存量**（deploy-gate / guard-tools / weekly-audit / build_apk / build_web / sync-adai-rulepack / backup_prod / migrate-data-to-user-layer），全部修复 | ✅ 已修 + 已加自动门禁（2026-09-14） | 报「unbound variable」但变量确实赋过值；报错里变量名后面粘着一个乱码字符；中英混排的 `echo` 字符串；本机直接跑正常、cron/hook 里跑就崩 |

## 十六、解析与失败可见性（2026-09-14 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **注释写「跳过该行」，实现却是 NPE 炸整批** | 导入一份文件直接 500/崩溃，而代码注释明明写着「数据异常跳过」；单测只覆盖正常文件时永远发现不了 | 三元表达式里把可能为 null 的解析结果**直接链式调用**：`col >= 0 ? parseNum(cells[col]).stripTrailingZeros() : null`——`parseNum` 对非数字返回 null，于是 `null.stripTrailingZeros()` 抛 NPE。作者的心智模型是「解析失败 → 跳过」，代码实际是「解析失败 → 崩」 | **先解析、再判空、再使用**：`price = parseNum(...); if (price == null) { 记录丢弃; continue; }`。**判据**：凡「容错解析」函数返回 null 的分支，紧跟着的链式调用一律视为可疑 | ✅ 已修（2026-09-14 P2-交易43 附带） | 注释与下一行代码语义相反（「跳过」/「忽略」/「容错」却无 `continue`/`return`）；容错函数返回 null 却直接 `.method()`；导入类端点返回 500 而非 400 人话 |
| **后端把「丢行」上报了，前端不展示 = 白修** | 修完解析层以为万事大吉，用户侧感受与修复前**完全一样**（还是只看到「识别出 N 笔」） | 可见性修复是**两段链**：解析层上报（`unparsed`/`dropped`）→ 响应字段 → 前端展示。只做前两段时，用户在 UI 上什么也看不到 | 修「丢数据可见性」时**把三段当一件事验收**：后端字段 + 前端展示 + 一条「有丢行时用户能看到」的测试。本批即因此把前端接线作为同一批的必须项 | ✅ 已按此验收（2026-09-14） | 只改后端就宣称「用户现在能看见了」；新增响应字段零前端引用；测试只断言后端字段、无 UI 断言 |

## 十七、生产运维与网络暴露（2026-09-15 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **服务绑 `0.0.0.0`，文档却写「已不对外」** | 文档声称「旧 IP:8080 直连已不对外」，实际 `ss` 显示 8080/8082/8083/8084 **全在 `0.0.0.0`**；当天静态站 **202 条外网直连记录**、API 端口有境外 IP 直连 → 可**绕过 Caddy/HTTPS 明文**访问登录接口（密码/token 裸奔） | 端口绑定从来没被当成契约：Tomcat 默认 `0.0.0.0`、`ThreadingServer(("0.0.0.0", port))`；而 Caddy 反代写 `127.0.0.1:PORT` 看起来「已经在内网」，掩盖了服务其实同时在公网裸听 | 服务侧绑回环（unit 加 `Environment=SERVER_ADDRESS=127.0.0.1`、静态服务改 `("127.0.0.1", port)`），对外只留 Caddy；**验收必须从外部视角打**（本机 curl `IP:PORT` 应连接失败），`ss` 只能证明「监听了」、不能证明「外面打不到」 | ✅ 已修（2026-09-15） | 新增长期运行的服务只测「Caddy 能通」；文档里出现「已不对外/已关闭/已收敛」这类断言却拿不出外部视角实测 |
| **`caddy validate` 以 root 留下日志文件 → reload 静默不生效** | `caddy validate` 报 Valid，紧接着 `systemctl reload caddy` 失败：`open /var/log/caddy/xxx-access.log: permission denied`；**Caddy 保留旧配置继续服务**（站点照常 200），所以只有 exit code 与 journal 里有痕迹，极易被当成「不影响」 | `caddy validate` **不是纯语法检查**——它会真的构建配置并打开 log writer；以 root 跑就在 `/var/log/caddy/` 留下 `root:root 600` 的空文件，而服务进程是 `caddy` 用户，写不进去 | `validate` 后先清掉它留下的空日志再 reload（`sudo rm -f /var/log/caddy/*.log`）或 `sudo chown caddy:caddy`；**顺序固定为 validate → 清残留 → reload** | ✅ 已修（2026-09-15） | 用 root 跑任何「会落地文件」的校验/dry-run 工具；reload/重载类命令失败但线上仍可用 → 没人回头看 exit code |

---
**追加方式**：AI 在开发/审核中发现新坑 → ①入对应 checklists（活文档）②本文件按域补一行（索引）。两条都要，防止只入一处。
