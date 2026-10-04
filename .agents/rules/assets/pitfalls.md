---
title: 已知坑归集（Pitfalls）
description: 跨 checklists 归集的「踩过的坑」索引——症状/根因/修复/复发信号，按域分组；完整逐条在 checklists 活文档
version: 1
created: 2026-08-15
updated: 2026-10-04
status: active
lines: 318
depends-on:
  - ../../toolkit/checklists/ai-guard-checklist.md
related:
  - ../../toolkit/checklists/code-backend-reviewer.md
  - ../../toolkit/checklists/code-frontend-reviewer.md
  - ../../toolkit/checklists/docs-contract-reviewer.md
  - ../../toolkit/checklists/data-knowledge-reviewer.md
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
| 周期习惯被当待办（概览卡天天提醒） | 首页/推送**天天复读**同一条习惯（用户 2026-09-23：「阿呆 app 概览卡片，天天提醒我」「**这是我的工作周期习惯，不是待办**」） | **节律没有专属形态** → 只能落进「记录 → 待办」唯一管道（`RecordToTodoLinker` 判据只看 `actionable`，一句「今天周四 固定发版日 在加班」被转成永久 OPEN 待办）；而简报注入**无类型分流、无上限**，prompt 还明写「发现习惯就自然提及」——输入侧就在要求模型复读 | **A 批止血＝注入侧三闸**：`isRhythmLike` 分流（周期表述不进提醒段）+ 条数硬上限 + 删「习惯就提及」指令 + 新增规则 8「禁止编造提醒/禁止把习惯当要做的事」+ 回读统一 `recentActive()`；**根治**＝节律独立为 Kernel 一等条目（RRULE 命中日注入）+ 记忆 bi-temporal 有效期 + 变更走「问一句」（RFC `20260923-rhythm-and-memory-temporality` §四） | ⚠️ A 批已止血（2026-09-23），B/C 待做（详见 REVIEW P2-工程10） | 新增「周期性/长期」表述被转成一次性待办；新增注入面不带「类型闸门 + 条数上限」；prompt 里出现「提醒用户他的习惯」这类指令 |

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
| frontmatter 断链 | 图谱边解析失败 | depends-on/related 相对路径错 | ai-guard-meta M1 检测（D30） | ✅ 已修 | 新增边不校验 |
| lines 漂移 | 声明行数 ≠ 实际 | 手写 lines | ai-guard-meta --fix 回写（D34） | ✅ 已修 | 手改 lines |

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
| 字体残缺版残留本地 | 本地 web 新 UI 中文显示框框（完美/案例/标注/匹配/理解 等缺字形），生产正常 | 2026-08-23 生产事故修复只替换了 `/opt/adaios/web/fonts/`，**本地三端 `web/fonts/` 未同步**（gitignore 不入库 → 无版本提示） | 重新子集化 GB2312 全量（7451 字形/1.9MB）+ 三端 web/app/admin 全替换 + `build/web/fonts/` 同步 + 字形数校验（详见 `.agents/rules/assets/projects/adai-web.md` 字体资产节）| ✅ 已修（2026-08-30）| 改字体只改一处 / 新 UI 文案出现框框 |

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
| 防重机制 fail-open（读不到状态就继续跑） | 线上一片安静，账却慢慢错：券商快照防重（锚定）因锚定文件不存在而**整条失效**，导入把已含在快照里的成交重放一遍 → 持仓与现金双计（2026-09-07 −3.19 万 / 09-09 −1.27 万 / **09-12 复发 −2.67 万**，三天后靠人肉眼发现） | 防重型机制的「状态读不到」被当成「不需要防重」（`find()` 缺失 → 空锚定 → 继续按旧行为重放）；且修复只改了新代码路径，**没有存量回填、没有运行时自检、没有失败可见**（REVIEW 里写「已修」，生产上机制根本没在跑） | 三件事一起做：①**fail-closed**（锚定未知 + 已有账目状态 + 需要改账的增量 → 拒绝并指路，只留显式逃生口 `mode=append`）②**运行时自检**（`GET /trading/anchor`、`GET /trading/integrity`；code-deploy-gate 部署后 probe 存在性）③**存量回填**（`PUT /trading/anchor` 显式补锚定日与持仓基线） | ✅ 已修（2026-09-12 交易账实一致性批，RFC 20260912） | 新增/改动任何防重·防双计·配额类机制时只写「正常路径」；机制状态读不到就降级继续；修复后没有回填脚本/自检端点/告警出口 |
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
| **`$VAR` 紧跟全角标点 → bash 把标点字节并进变量名** | `ai-guard-tools.sh: line 77: N_SKILLS?: unbound variable`——变量上一行明明赋过值却报未定义；`set -u` 下脚本当场中止，看代码完全正常 | 非 UTF-8 locale（`LANG` 未设 / 为 `C`——cron、git hook、部分 CI 的默认）时 bash 不把多字节字符当词法边界：`$N_SKILLS）` 里 `）`（`EF BC 89`）的首字节被当成变量名的合法字符，于是去找名为 `N_SKILLS\xef` 的变量 | 变量一律用 `${...}` 界定（`${N_SKILLS}` 而非 `$N_SKILLS`）；**凡中文文案里嵌 shell 变量，一律加花括号**。**已机器化（2026-09-14）**：`.agents/mechanism/scripts/ai-lint-shell-vars.py` 按 shell 词法扫描（单引号/注释/`\$` 转义不报，`${}` `$()` `$?` 不报），挂 `ai-guard-tools.sh` T6 + git pre-commit 第 4 层——**落地当轮扫出全仓 18 处存量**（code-deploy-gate / ai-guard-tools / task-weekly-audit / build_apk / build_web / sync-adai-rulepack / code-backup-prod / data-migrate-user-layer），全部修复 | ✅ 已修 + 已加自动门禁（2026-09-14） | 报「unbound variable」但变量确实赋过值；报错里变量名后面粘着一个乱码字符；中英混排的 `echo` 字符串；本机直接跑正常、cron/hook 里跑就崩 |
| **`find -newermt today` 恒为 0 → 心跳一直说「用户今天没用」** | C0 产品心跳的「今日 N 条」**长期显示 0**，而用户当天明明在用（2026-09-16 实有 6 张对话卡 / 27 条记录）；同一行的「近 7 天」「近 14 天」却正常——**只有今日那一格坏掉**，看起来像「今天刚好还没记」，**不报错、不崩、没人会怀疑脚本**。这是「用户是否还在用」的最高优先级信号，坏了会让 AI 误判产品已停用，转而继续空转加功能 | **GNU date 把 `today` 解析成「当前时刻」而不是「今天 00:00」**：实测生产 `date -d today` → `Wed Sep 16 09:02:09 PM CST 2026`（分秒正是执行的那一刻）。于是 `find -newermt 'today'` = 「找比**现在**更新的文件」→ 恒 0（除非存在未来时间戳）。相对量 `'-7 days'` / `'-14 days'` 语义正确，所以坏的恰好只有 `today` 这一个词 | 改用**绝对日**当边界：`T=$(date +%F); find … -newermt "$T"`（或 GNU find 的 `-daystart -mtime -1`）。2026-09-16 修 `ai-guard-context.sh` C0 心跳，修后当日 0 → **27**（实测对齐）。**判据**：时间边界参数一律用可验证的绝对量（`date +%F` / ISO 串），不用 `today`/`now` 这类模糊词 | ✅ 已修（2026-09-16，随「生产日报」每日流程首跑发现） | 任何「今日/当天」口径的统计**长期恒为 0**，或各窗口之间数量关系反常（今日 0 但近 7 天几十）；`date -d today` 打印出当前时分秒而非 `00:00:00`；find/date 的边界参数写作 `today` / `now` / `0 day` |

## 十六、解析与失败可见性（2026-09-14 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **注释写「跳过该行」，实现却是 NPE 炸整批** | 导入一份文件直接 500/崩溃，而代码注释明明写着「数据异常跳过」；单测只覆盖正常文件时永远发现不了 | 三元表达式里把可能为 null 的解析结果**直接链式调用**：`col >= 0 ? parseNum(cells[col]).stripTrailingZeros() : null`——`parseNum` 对非数字返回 null，于是 `null.stripTrailingZeros()` 抛 NPE。作者的心智模型是「解析失败 → 跳过」，代码实际是「解析失败 → 崩」 | **先解析、再判空、再使用**：`price = parseNum(...); if (price == null) { 记录丢弃; continue; }`。**判据**：凡「容错解析」函数返回 null 的分支，紧跟着的链式调用一律视为可疑 | ✅ 已修（2026-09-14 P2-交易43 附带） | 注释与下一行代码语义相反（「跳过」/「忽略」/「容错」却无 `continue`/`return`）；容错函数返回 null 却直接 `.method()`；导入类端点返回 500 而非 400 人话 |
| **后端把「丢行」上报了，前端不展示 = 白修** | 修完解析层以为万事大吉，用户侧感受与修复前**完全一样**（还是只看到「识别出 N 笔」） | 可见性修复是**两段链**：解析层上报（`unparsed`/`dropped`）→ 响应字段 → 前端展示。只做前两段时，用户在 UI 上什么也看不到 | 修「丢数据可见性」时**把三段当一件事验收**：后端字段 + 前端展示 + 一条「有丢行时用户能看到」的测试。本批即因此把前端接线作为同一批的必须项 | ✅ 已按此验收（2026-09-14） | 只改后端就宣称「用户现在能看见了」；新增响应字段零前端引用；测试只断言后端字段、无 UI 断言 |
| **「成功」不回执 = 用户当成失败，并重复触发** | 用户连分享同一条微博两次、说「阿呆没反应」——而日志显示**两次都成功了**（都抓取、都落卡）。更糟的下游：**重复触发又制造重复数据**（同一条内容落了两张卡，AI 两次起了不同标题） | 可见性只做了**失败侧**（P1-分享7 把 failed 从 60 秒延到 30 分钟），**成功侧**仍是「提交即关门」三连：扩展 **1 秒关窗** + 主 App **不被拉起** + `done` 结果 **60 秒过期**，而 App 学习页只在失败时说话 → 用户走到查看页时结果已过期，**「我整理好了」这句话没有机会说出口**。（旧注释自认「done 有卡片兜底，60 秒够」——**兜底 ≠ 知情**：卡片在列表里，用户不知道那是刚分享的那条） | 三处一起补、缺一不可：① `done` 与 `failed` **同档 TTL**（60s → 30 分钟）；② App 进页把终态**说出来**（「你刚分享的那条，我整理好了《标题》」+ 点开直达，看过就不再重复）；③ 顺手按来源链接**去重**（重复触发的代价降到零，且省掉一次抓取+一次模型调用）。**配套判据**：查重是省钱优化、不是提交的正确性前提 → 读卡片失败必须 **fail-open**（按没整理过继续），不能反过来把正常提交拦掉 | ✅ 已修（2026-09-23 P1-分享8） | 新增任何「提交即关门 / 后台处理」的入口（分享扩展、快捷指令、异步任务）却只报「已收到」而**没有终态回执**；结果的保留时长**短于用户走到查看页的时间**；失败路径有提示、成功路径零提示（测试也只测失败）；用户用「没反应」描述一个**已完成**的操作 |

## 十七、生产运维与网络暴露（2026-09-15 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **服务绑 `0.0.0.0`，文档却写「已不对外」** | 文档声称「旧 IP:8080 直连已不对外」，实际 `ss` 显示 8080/8082/8083/8084 **全在 `0.0.0.0`**；当天静态站 **202 条外网直连记录**、API 端口有境外 IP 直连 → 可**绕过 Caddy/HTTPS 明文**访问登录接口（密码/token 裸奔） | 端口绑定从来没被当成契约：Tomcat 默认 `0.0.0.0`、`ThreadingServer(("0.0.0.0", port))`；而 Caddy 反代写 `127.0.0.1:PORT` 看起来「已经在内网」，掩盖了服务其实同时在公网裸听 | 服务侧绑回环（unit 加 `Environment=SERVER_ADDRESS=127.0.0.1`、静态服务改 `("127.0.0.1", port)`），对外只留 Caddy；**验收必须从外部视角打**（本机 curl `IP:PORT` 应连接失败），`ss` 只能证明「监听了」、不能证明「外面打不到」 | ✅ 已修（2026-09-15） | 新增长期运行的服务只测「Caddy 能通」；文档里出现「已不对外/已关闭/已收敛」这类断言却拿不出外部视角实测 |
| **`caddy validate` 以 root 留下日志文件 → reload 静默不生效** | `caddy validate` 报 Valid，紧接着 `systemctl reload caddy` 失败：`open /var/log/caddy/xxx-access.log: permission denied`；**Caddy 保留旧配置继续服务**（站点照常 200），所以只有 exit code 与 journal 里有痕迹，极易被当成「不影响」 | `caddy validate` **不是纯语法检查**——它会真的构建配置并打开 log writer；以 root 跑就在 `/var/log/caddy/` 留下 `root:root 600` 的空文件，而服务进程是 `caddy` 用户，写不进去 | `validate` 后先清掉它留下的空日志再 reload（`sudo rm -f /var/log/caddy/*.log`）或 `sudo chown caddy:caddy`；**顺序固定为 validate → 清残留 → reload** | ✅ 已修（2026-09-15） | 用 root 跑任何「会落地文件」的校验/dry-run 工具；reload/重载类命令失败但线上仍可用 → 没人回头看 exit code |

## 十八、iOS 分发签名（TestFlight，2026-09-15 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **云签名要 Admin，App Manager 不够** | `xcodebuild -exportArchive` 报 `Cloud signing permission error` + `No signing certificate "iOS Distribution" found`，即使 API Key 有效、权限看着齐全 | Xcode 的**云托管分发证书**要求 **Admin** 角色；而 App Store Connect API Key 常按「最小权限」建为 App Manager | **绕开云签名**：App Store Connect API 允许 App Manager 直接 `POST /v1/certificates`（`IOS_DISTRIBUTION` + 本地 CSR）与 `POST /v1/profiles`（`IOS_APP_STORE`），再走本地 **manual 签名**导出。见 `apps/adai-app/scripts/asc_signing.py` | ✅ 已修（2026-09-15） | 一看到「云签名权限」就去要 Admin；其实 API 自己就能建证书/profile |
| **OpenSSL 3 的 p12，macOS `security import` 解不开** | `security: SecKeychainItemImport: MAC verification failed during PKCS12 import (wrong password?)`——密码明明是对的 | OpenSSL 3 默认用 AES-256-CBC + PBKDF2 加密 p12，Apple 的 `security` 不认这套 | `openssl pkcs12 -export **-legacy** …` 生成传统算法 p12 | ✅ 已修（2026-09-15） | 手工合成 p12 给 keychain 用时；报「密码错误」但你确定密码没错 |
| **App Store Connect API 不给 `include` 就不返回 relationships** | 按 bundleId 匹配 profile「全部落空」→ 误判成「没有」→ 为同一 App 建出第二个 profile；再跑撞 `409 Multiple profiles found with the name …` | 列表端点默认**不返回**关联数据，`relationships` 为空 → 匹配条件恒为 false | 列表一律带 `&include=bundleId` 再按关联匹配；**并且** DELETE 成功返回 **204 空 body**，`json.loads` 会抛异常——必须判空 body，否则清理循环崩在第一个删除处（本批因此**误删了分享扩展的描述文件**） | ✅ 已修（2026-09-15） | 用「关联 id」做集合匹配却拿不到 relationships；批量删除脚本只删成功第一条就炸 |
| **`flutter build ipa` 的 export 阶段必失败** | 脚本报错退出，但其实 archive 已经产出 | 本机 Xcode 未登录账号 → Flutter 内置的 export 走云签名必挂；**我们要的只是 archive** | 脚本容忍该阶段失败（`set +e`）并**检查 archive 是否真的产出**，导出改由自己的 manual 签名流程接管 | ✅ 已修（2026-09-15） | 把 `flutter build ipa` 当「一条命令出包」用；因为它失败就认为「构建没成」 |

## 十九、外部模型输出与「去重 / 合并」键（2026-09-17 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **VLM 输出格式漂移 → 下游正则 0 命中 → 静默降级丢数据** | 一张 **3 笔成交**的截图只落 **1 笔**，界面上没有任何提示（用户以为「就这些」）。生产实证：GLM 的 `extractedText` **内容全对**（三笔的代码/价格/数量/成交额/时间一个不少），但它把表格「一行多列」输出成了「**一列一行**」（每个单元格独占一行） | 链路两端**没有格式契约**：上游是通用图片理解 prompt（从未要求保留行结构），下游正则 `TABLE_TRADE_PATTERN` 却硬性假设「字段同处一行、以 `\h+` 分隔」→ 0 匹配 → 回退单笔解析（Schema 只能装一笔）。**等于把「结构还原」这件确定性工作交给了一个不确定的模型** | 加**竖排表格还原**通道（以方向行为锚点按行类型重建：向上取价格/代码/名称、向下取数量/成交额/时间）+ 产出前做 `价格×数量 = 成交额` 交叉校验；三通道全 0 命中时把「含买卖字样却未被覆盖」的行进 `dropped`。**正解是让模型直接输出结构化数组**，而不是「自由文本 + 事后正则猜」 | ✅ 已修（2026-09-17 P0-交易53 / P1-交易55/56） | 新增任何「模型输出文本 → 正则/字段解析」的链路；解析器对排版做了隐含假设；**降级路径没有上报「我没认出来」**；同一张图重传结果不稳定 |
| **去重键少一个维度，就会吞掉真实数据** | 同标的、同方向、**各 100 股**的三笔真实成交（价格 68.27 / 67.73 / 67.92）被去重成 **1 笔** | `sameTrade` 只用 `symbol + 方向 + 数量(±10%)` 作键——而「同标的同方向同数量」在真实盘口里**极其常见**（分笔成交）。**去重本为防重复，代价却是丢真数据**，而且是静默的 | 键上补**价格**维度（价格不同即不同笔）；同图重传时价格一致 → 去重语义不变。**取舍原则：宁可多留一笔让用户手动删，也不静默吞掉真实成交**（吞掉是数据丢失，多留只是多点一下） | ✅ 已修（2026-09-17 P0-交易53 / P1-交易56） | 设计或修改任何「按内容判定重复」的键；键的维度少于「能唯一确定一笔」的字段；测试只覆盖「重复能被去掉」而**没覆盖「不同的两笔不能互吞」** |
| **条件渲染把「操作入口」当「样式」藏掉** | 用户对着一堆识别错误的候选行**没有任何删除按钮**（截图候选普遍识别不到日期 → `×` 从不渲染） | `missingDate ? 「补日期」按钮 : 「×」按钮`——把两个**正交动作**写成了互斥分支。日期缺失本只该决定「要不要显示补日期」，却顺带决定了「能不能删」 | 两个入口**并存**（缺日期时才多一个「补日期」）。判据：**分支里若含「动作」而不只是「外观」，一律可疑** | ✅ 已修（2026-09-17 P0-UI13） | UI 出现 `x ? A按钮 : B按钮` 形态；用户说「某个操作找不到/点不到」而代码里其实有；空值或缺字段状态把主操作一起藏掉了 |
| **列表合并按「位置」而不是按 id，刷新即重复** | 同一条对话被渲染**两份**（用户实测：清待办 + 收行情推送时触发）。反向验证：渲染 7 张卡只有 6 个唯一 id | 刷新时用 `sublist(0, length - pageSize)` 按**位置**切「更早页」，而列表里混着**附加条目**（push/action），后端 page0 又返回「N 条核心 + **全部**附加条目」→ 那条唯一的核心卡既落在「更早页」里、又在 fresh 里 → 同 id 两份 | 合并一律**按 id 去重**（app 刷新 + web 分页都补）。任何追加式合并（`[...new, ...old]`）先算 `existingIds` / `freshIds` | ✅ 已修（2026-09-17 P1-前端3；且是 09-16 P2-UI12 的**同族复发——上次只修了展示层折叠、没在合并层治本**） | 用 `length - N` / `skip(N)` 之类**位置**做增量合并；列表里混有多种来源的条目；新增分页或刷新路径却没同步去重逻辑；同一缺陷换个形态再来一次 |

---
## 二十、多会话并发与部署产物（2026-09-23 新增）

> 本节三条都来自同一次「行情链路韧性批」（RFC 20260923）的真实实施过程——**都发生在部署链路上，且都不会让测试变红**。

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **构建产物夹带另一个并发会话的未提交改动** | 本地 jar 部署上去后，生产行为与「本批改动」对不上；更坏的形态是**别人写到一半的代码被带上生产** | gradle 从**工作区**编译，而工作区同时有另一个会话未提交的改动（本项目支持同日多会话并行）；`git status` 能看出，但**构建命令不会提醒** | 用 **`git worktree add /tmp/xxx HEAD --detach`** 在干净 HEAD 上构建（隔离并发改动），再拿那份 jar 部署；部署后删除 worktree | ✅ 已用（2026-09-23） | 多会话同时开在同一仓库；部署前 `git status` 有**不属于本批**的改动；只验证「bundle 存在」而不验证「bundle 内容是本批的」 |
| **`gradlew bootJar -q \| tail` 掩盖「jar 没重新产出」** | 部署日志全绿（GATE-BEFORE PASS + smoke 全过），但**新端点 404**——查下去发现部署的是**上一版 jar** | `-q` 抑制正常输出、`\| tail -N` 截掉前面（含 up-to-date/错误提示），而后面 `ls -la *.jar` 只看「文件存在」不看**时间戳**；exit code 还因为 `ls` 成功而是 0 | 部署前**校验 jar 的 mtime 与内容**：`unzip -l <jar> \| grep <本批新增类>`（本批新增 `SinaKlineDataSource`，第一次部署前 grep 到 **0** 才发现） | ✅ 已修（2026-09-23） | 改了后端但「端点/接口没生效」，而部署与 smoke 全绿；用 `-q`/管道 tail 看构建结果；只判断产物存在不判断内容是新的 |
| **范围守卫拦不住「同一文件内的跨会话改动」** | `ADAI_BATCH_PATHS` 校验**通过**（文件路径全在声明范围内），但提交 diff 里**混进了另一个会话写进同一文件的行**——`.agents/direction/rfc/_index.md` 这类**索引/记账文件**是高频共写点：A 会话加自己 2 行、B 会话加自己 1 行，双方提交都会带上对方的 | 范围守卫是**路径级**判定（前缀匹配），**不检查文件内容的归属**；而 `_index.md`、`change-log.md` 天然是「多会话高频共写点」 | ① 提交前用 **`git diff --cached` 复核内容**（不只路径）；② 索引类文件必要时 **`git add -p` 分块**只暂存自己的行；③ 或约定「索引登记是共享追加点、不视为越界」（待拍板） | ⚠️ 待拍板（2026-10-03 实测遇到） | 范围守卫 PASS，但 `git diff --cached` 里有**自己不认识的条目行** |
| **并行会话留下的门禁失败会拦住所有人的提交** | 另一会话建了审查报告但**没登记索引** → `ai-guard-meta` 报 **M3 孤儿** → `pre-commit`（检查**工作区**）**拦住任何会话的提交**；本例该红持续数分钟后对方补登记，**自行转绿** | `pre-commit` 的守卫检查工作区且是**全局的**——一个会话的半成品会阻塞全体；而「不是我造成的红」最容易被误当自己的问题去修 | ① 并行会话**提交前自检**（`ai-guard-meta` PASS 再提交）；② 察觉「红不是我的文件引起」时**不要擅自改**（跨批越界）——先确认归属，**对方补完即自愈**；③ 确需解阻塞再补那份登记（需人点头） | ✅ 已复现（2026-10-03；数分钟后自愈） | 守卫报错的文件**不在本批**；`git log -1 -- <该文件>` **无记录**（它还没提交）；报错类型是 M3 孤儿 / 未登记 |
| **`git add` 之后再编辑同一文件 → 提交的是索引里的旧版本** | 门禁报「status.md 声明 401，实测 408」——可明明刚刚把 401 改成过 408；提交后工作区仍显示该文件「已修改」 | `git add` 把**当时**的内容写进索引；之后的编辑只改工作区。`git commit` 提交的是**索引**（旧版），改动留在工作区（表现为「提交了却还是 M」） | 改完**重新 `git add`**；提交前用 `git diff --cached` 复核「要提交的到底是什么」；**先改文件、后 add、再 commit** 是唯一安全顺序 | ✅ 已修（2026-09-23） | 提交后 `git status` 里同一个文件仍显示 ` M`；门禁报「声明值 ≠ 实测值」而你确信改过；`git add` 与编辑交错进行 |

## 二十一、前端测试环境（flutter test 的隐式依赖解析，2026-09-23 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **`flutter test` 先做隐式 pub 解析，握手失败就一条测试都不跑** | 在 `apps/adai-app` 跑 `flutter test` 卡在 `Resolving dependencies... / Downloading packages... / Connection terminated during handshake / Failed to update packages.`——**测试一条都没执行**；而同一行的 `flutter analyze` 是 PASS，脚本里 `flutter analyze \| tail && flutter test` 这种写法还会把失败**吞掉**（管道让退出码变成 `tail` 的 0） | `flutter test` 默认先做一次 `pub get`（即使 `.dart_tool/package_config.json` 已存在），网络/代理抖动即中止；`pub get --offline` 只能救 `pub get` 自己，救不了 test 触发的隐式解析 | `flutter pub get --offline` → **`flutter test --no-pub`**（跳过隐式解析，直接用本地缓存）；脚本里避免 `cmd \| tail && next`（吞退出码），要判 `${PIPESTATUS[0]}` 或分步执行 | ✅ 已用（2026-09-23，app 408→409 实测） | 日志出现 `Resolving dependencies` / `Failed to update packages`；「测试全绿」其实一条没跑；两个 flutter 命令**并行**（先报 `Waiting for another flutter command to release the startup lock`，随后依赖解析失败） |

## 二十二、AI 工程脚本的文本处理（字节 vs 字符，2026-09-26 新增）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **`head -c N` 按字节截断中文 → 写文件抛 `UnicodeEncodeError: surrogates not allowed`** | `task-cadence.sh ship` 收尾时快照与成本入账**都成功**，末尾却抛 UnicodeEncodeError；`ship.subject` **静默没写成**（`task-cadence.json` 少一个键，再无其他报错——不留意就永远发现不了） | `git log --pretty=%s \| head -c 120` 是**按字节**截断，而中文 3 字节/字，切在字符中间 → 该值成为**非法 UTF-8**；bash 把它传进 python 的 `sys.argv` 时按 `surrogateescape` 解码为**孤立代理对**，再 `write_text(encoding='utf-8')` 就被 Python 拒绝（代理对不可编码） | 两层修：① 截断改用**字符**语义（`cut -c1-120`）；② 写入口 `cadence_set` 先 `val.encode('utf-8','surrogateescape').decode('utf-8','replace')`，且 `write_text(..., errors='replace')`——**记账绝不因脏字节中断** | ✅ 已修（2026-09-26；回归：120 字节坏串写入成功、JSON 有效、值 65 字符） | 脚本里出现 `head -c` / `cut -b` 去处理**可能含中文**的文本；症状总是「前面都成功、最后写 JSON 时抛 surrogates not allowed」；提交标题（中文）一长就复现，短则不出现 |

## 二十三、守卫与迁移的静默失效（2026-10-03 新增：技能目录化批 + `.agents/` 大迁移）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **`.gitignore` 写 `skills/` 会把真相源 `.agents/toolkit/skills/` 一起忽略** | 新增工具出口目录后，本该进 git 的**真相源静默不入库**——`git status` 看不到它，也**没有任何报错**；等换机 clone 才发现技能全丢 | `.gitignore` 里不带 `/` 的模式匹配**任意层级**的同名目录，`skills/` 命中 `.agents/toolkit/skills/`；而「加一条忽略规则」看起来只影响新目录 | 顶层出口一律写**锚定根**的 `/skills/`；加规则后用 `git check-ignore -v <真相源路径>` **反向验证**（本批实测：四个出口全 ignored、真相源 not ignored） | ✅ 已用（2026-10-03） | 新增与既有目录**同名**的顶层目录时；只验证「新目录被忽略」而不验证「别人没被误伤」 |
| **守卫用 `glob('*.md')` 收集待检文件 → 目录化后静默漏检（假绿）** | `ai-guard-meta` 报 **PASS**，但新布局的 `SKILL.md` **根本没被检查**（frontmatter/lines/断链全不查）；唯一线索是文件数**从 175 掉到 174**（旧文件消失、新文件未纳入） | `Path.glob('*.md')` **不递归**，`skills/data-learn-writer/SKILL.md` 不在匹配集内；守卫「少查一个文件」不会报错，只会更绿 | 收集逻辑两种布局都覆盖：`glob('*.md') + glob('*/SKILL.md')`——**只收 SKILL.md**，不收技能目录内 `references/*.md`（那些无 10 字段契约，会被 REQUIRED 误判） | ✅ 已修（2026-10-03；修后 PASS 175 files） | 任何「按目录 glob 收集待检文件」的守卫；**移动/新增文件的层级**之后；症状总是「门禁全绿但新文件从来没被检查过」 |
| **shell 计数检查的 glob 漏掉新布局 → 计数静默偏低** | `ai-guard-tools` T3 报「15 个技能包（roles/ 12 + skills/ **3**）」而实际是 16（skills/ 4）；数字错了但**不报错** | 同样是不递归 glob；且 `name` 校验取「文件名 stem」，目录布局下应改用**父目录名**（官方硬约束） | glob 补 `*/SKILL.md`；`name` 取值改 `case`：SKILL.md 取父目录名、其余取文件名 stem | ✅ 已修（2026-10-03；修后 16 个） | 移动技能文件后计数变了；**计数类输出没有断言**（偏低也照样 PASS） |
| **把单值字符串字段纳入「按列表处理」的检查 → 值被逐字符迭代** | 给守卫新增 `supersededBy` 检查后，报出 **`supersededBy 断链 r` / `断链 s` / `断链 t`** 这种逐字符的诡异输出 | `depends-on`/`related` 是 YAML **列表**，而 `supersededBy` 是**单值字符串**；`for ref in (meta.get(key) or [])` 对字符串迭代＝逐字符 | 取值后先归一化：`refs = val if isinstance(val, list) else [val]`（M1/M3 两处同修） | ✅ 已修（2026-10-03） | 给检查器**新增字段**时没确认它是列表还是标量；报错里出现**单个字母/字符** |
| **提「新建机制」方案前没盘点已有机制 → 白做一版方案** | 为「工具看不到项目层技能」写了一版从零建中间层的 RFC；随后发现 `.agents/mechanism/scripts/ai-link-skills.sh` + `.gitignore` 三目录忽略 + `ai-guard-tools` **T4** 早已存在并在运行（只是只注册 1 技能 / 1 工具） | 直接按行业标准外推缺口，没先 `ls .agents/mechanism/scripts/`、读 `.gitignore` 注释、跑一遍 `ai-guard-tools.sh`——这三处正是本项目的「已有家底」入口 | 提案前固定动作：`ls .agents/mechanism/scripts/` + 读 `.gitignore` + `bash .agents/mechanism/guards/ai-guard-tools.sh`（T1–T7 会把已有工具链全列出来） | ✅ 已用（2026-10-03） | 准备新增脚本/目录约定时；方案里出现「从零建立」字样；用户反问「这个不是已经有了吗」 |
| **gitignore 不支持行尾注释 → 规则静默失效** | 新加的忽略规则**看似生效、实则什么都没匹配**（`git check-ignore` 返回未忽略）：写成 `.agents/toolkit/skills/  # 临时注释`，直到逐项验证才发现不生效 | gitignore 把**整行**（含 `#` 与中文）当作**模式字符串**；注释必须**独立成行** | 注释单独一行、模式行不带任何尾随内容；改完**逐项 `git check-ignore -v` 验证** | ✅ 已修（2026-10-03，B0 实测抓到；**同日第二次**：把说明写成行尾注释加在 `for ...:` 的续行上，**注释吃掉冒号** → SyntaxError，整个守卫跑不起来） | 新增/修改的忽略规则验证不通过却看不出原因；模式行里出现空格、`#` 或中文；**给「续行」加行尾注释**（Python / gitignore 都栽） |
| **脚本的 `cd "$(dirname "$0")/.."` 是隐式位置假设** | 18 个守卫/执行器搬进 `.agents/mechanism/guards/` 后，`ROOT` 指向 `.agents/` 而非仓库根 → 所有基于 `ROOT` 的路径检查**静默错位**（可能 false PASS） | 脚本用「我在仓库根**下一层**」这个位置假设推导 ROOT——**位置一变假设即废**，且没有任何断言保护 | 统一改 `cd "$(dirname "$0")/../.."`；更稳的是 `cd "$(git rev-parse --show-toplevel)"` | ✅ 已修（18 个脚本） | 脚本目录层级变动后未重验；脚本报 PASS 但手工走一遍明显不对 |
| **无扩展名文件被「按后缀过滤」的替换脚本跳过** | 批量替换后 `pre-commit` 里仍有 8 处旧路径 → 提交时它去调已不存在的 `ai-engineering/ai-guard-align.sh`，**连续两次提交被拦**才暴露 | 替换脚本用 `f.suffix in exts` 过滤文件类型，`pre-commit` / `.gitignore` / `.gitattributes` 这类**无后缀关键文件被静默跳过** | 替换脚本对**无后缀文件白名单**（或先全量扫、再按类型跳过）；改完对**关键文件单独 grep** | ✅ 已修 | 迁移后提交被门禁拦、**报错指向「旧路径不存在」**——先怀疑「漏了无后缀文件」 |
| **生成器内部的路径重写规则也要跟着搬** | `ai-sync-agents.sh` 仍把 roles 里的 `./x.md` 重写为 `ai-engineering/assets/x.md`（旧前缀）→ **24 个 subagent 定义路径全错**，而 `--check` 报 **PASS** | 自检是「生成物 vs 真相源」的**对拍**——两边由**同一个错误规则**产出，所以**天然自洽**，错误被完美掩盖 | 搬家时把**生成器内部的路径规则**当成「引用」一并替换（不能只改文档引用）；改完**抽查生成物内容**而非只看自检 | ✅ 已修（抽查 `.agents/rules/assets/pitfalls.md` ✅） | 生成物自检 PASS 但**内容里的路径指向已迁移的旧位置**；自检只比「两边一致」不比「是否正确」 |
| **相对路径断链要「按目标真实位置重算」而非人工逐个改** | 搬家后门禁报 **20 处 M1 断链**（`../ai-guard-meta.sh` 这类 frontmatter 引用），成批出现 | 相对路径的**基准目录变了**（文件与新目标不在原来的层级关系上） | 写脚本：解析断链 → 按 **basename** 找目标真实位置 → **重算相对路径**；一次修完 12 处 | ✅ 已修 | 门禁 M1 断链**成批出现**且同一模式；人工逐个改到一半发现漏 |
| **守卫把「模板占位符」当成真实路径 → 误报阻断提交** | `ai-guard-structure` S3 报 `依赖路径不存在 → ../designs/<需求id>/`——而 `<需求id>` 是**命名规范里的占位符**，不是路径。守卫上线后第一次遇到「带占位符的依赖声明」就误报，**把一次正常提交拦下** | S3 的判据是「反引号里的相对路径」，**没有区分「真实路径」与「模板占位符」**；而模板类文档天然要写 `<id>` 这类示意 | 判据加排除：含 `<` / `>` 的引用视为**命名规范**而非路径。通则：**凡「从文本里抽路径」的守卫，先想清楚哪些是路径、哪些是示意**（`{}` `*` `...` 同理）| ✅ 已修（2026-10-03） | 守卫报「路径不存在」，但被报的字符串里带 `<...>` / `{}` / `*` 占位符；**新写的模板类文档首次进入守卫扫描范围时** |
| **把别处的真源「复制」成本地内容 → 副本静默过期** | 同一串标识符出现在两处，**真源一变副本就过期且不报错**：① 两份体系文档各自定义一套 `L1/L2/L3`（规范＝存放维、方案稿＝加载维）→ 跨文档推理歧义；② **12/12 目录**的 `_index.md` 与 `_directory.md` 的「职责」行**一字不差**（同一脚本从同一份数据各写一份）；③ 方案稿抄了守卫的检查项编号（`S1–S4`），守卫加 `S5` 后**文档静默过期** | **复制而非引用**——真源（守卫脚本 / 目录数据 / 另一文档）的内容被抄进本地，而「副本过期」**没有任何守卫会报** | ① 同一知识**只在真源详述**、别处**只引用**（写名字不写清单）；② 易变标识符（检查项编号 / 数量 / 枚举）**不要复制**，或注明「以 X 为准」；③ **能机器查的必须机器查**——`ai-guard-structure` **S5**（两件套整行重复）就是为这条而加 | ✅ 已修（2026-10-03，三处实例 + S5 兜底） | 同一串标识符出现在两个文件里；改了真源却只有一处变；「文档说 A、脚本做 B」却没人报错 |
| **批量改名的「域前缀扩散」——只该替换脚本名，不该替换裸词** | 把 `cadence` 一律加 `task-` 前缀后：**状态文件名** `state/cadence.json` 与**流程文档名** `process/cadence.md` 被一起改掉，而这两个**都还在原地** ⇒ `ai-guard-meta` M4 报「正文路径引用不存在」，自己写的更严扫描又挖出 12 处断链 | 批量替换时**没区分「这个词在哪一层」**：脚本名要加域前缀，而**状态文件 / 流程文档 / 配置键**不该加。同一个词在不同语境下归属不同类 | ① 替换规则**按「后缀 + 路径形态」限定**（如只替换 `*.sh` / `*.py` 的文件名与其路径引用）；② 改完**跑一遍「路径引用是否存在」的独立扫描**（要比 M4 更严——M4 按设计跳过 `.agents/direction/rfc/`）| ✅ 已修（2026-10-03，4 个文件 + 7 处旧形态引用） | 一次改名里**同一个词同时出现在脚本名和别的名字里**；改完门禁全绿但**引用指向不存在的文件** |
| **改名只 mv 了文件——忘了目录 / 忘了内容字段 / 忘了本机状态** | 三处同族：① `noon-task.d/` 是**目录**，只 mv 了 `noon-task.sh` ⇒ 文档里的 `task-noon.d` 指向不存在；② `task-ship.md` 的 **`name:` 字段**漏改（因对 `ship` 只做路径替换）⇒ `ai-guard-skills` S3 当场抓出；③ 旧**日志名**与新脚本名不匹配 ⇒ `ai-guard-tools` 报「尚无日志」警告 | `git mv` 只作用于**你列出的那些路径**；而「改名」实际涉及**四个层面**：文件名 · 目录名 · 文件**内容里的标识字段**（`name:`）· 与之配套的**本机状态**（日志 / 游标）| 改名前先列**四层清单**（文件 / 目录 / 内容字段 / 状态）；改完**逐个实测**（跑守卫 + 跑脚本本体）| ✅ 已修（2026-10-03，三处） | 改名后「文档说的文件不存在」或「守卫说 name 不符」；**日志 / 游标类状态文件没跟着改** |
| **守卫自身的「硬编码清单」与「过宽正则」——守卫也会张冠李戴** | ① `ai-guard-structure` 的 **S4 正则** `(guard-[a-z0-9-]+\.sh)` 把 `ai-guard-context.sh` 里的 `guard-context.sh` 也匹配了 ⇒ 误报 11 项；② 改名脚本里 **`GUARDS` 清单手写了 11 个**，漏了 `guard-roadmap` / `guard-unfixed` ⇒ 两个守卫没改名 | 守卫与工具脚本里**硬编码的清单 / 正则**，在体系变动时**不会自动跟上**——而它们本身就是「检查别人」的，**没人检查它们** | ① 正则加**边界与必需前缀**（`((?:ai-)?guard-…)`）并按**全名**校验存在性；② **清单从实际文件生成**，不手写（`ls *.sh` 而非打字）| ✅ 已修（2026-10-03） | 守卫报出「看起来荒谬」的错（把新名字里的旧片段当断链）；改名后**有文件被漏掉** |
| **排除范围写太宽——「本机状态」把「受管元数据」一起排除了** | 替换脚本写 「.agents/records/state」 就 `continue`，结果 `state/_directory.md` 与 `_index.md`（**目录两件套，受管元数据**）漏改 ⇒ S4 报「提到 guard-cost.sh 但它不在 guards/」| 用**目录级**排除去挡**文件级**问题：那个目录里既有「不该动的本机状态」（`*.json` / `*.log`），也有「必须跟着改的元数据」（`_index.md` / `_directory.md`）| 排除条件**精确到文件类型或文件名**（`.agents/records/state/*.json` / `*.log`），**不要整目录排除** | ✅ 已修（2026-10-03，2 个文件） | 替换 / 扫描的排除项写成**目录**而不是**模式**；某目录里既有「别动」也有「要改」的东西 |

## 二十四、跨容器目录归纳（2026-10-04 新增：docs → .agents 边界改「按性质」批）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **相对路径"自动正确"只是巧合，不能当保证** | 把 `docs/guides` `docs/deployment` 整体移入 `.agents/` 后，`.agents/rules/assets/` 与 `.agents/rules/process/` 里的 `../guides/x.md` **恰好仍然正确**（两者相对层级没变）；但同一批里 13 处引用**静默断链**——且 `M1` **不报**（相对引路径不是 frontmatter 边） | 相对路径只依赖**源与目标之间的层级差**，不依赖绝对位置。整批同向迁移时，部分引用"白捡正确"、部分断掉，**必须逐个核对而非整体推断** | 迁移后立即跑**全仓相对引用扫描**（`grep -rn '\.\./'`），逐个按新位置重算；跨容器迁移**先假设全断** | ✅ 已用（2026-10-04：13 处重算） | 批量移动目录后；脑中出现「相对路径应该没变」；症状是**同一目录里有的引用对、有的断** |
| **账本类文件没有 frontmatter——纳入守卫覆盖前先看脚本怎么读它** | `change-log` / `task-log` / `issue-log` 三个账本**从来就没有 frontmatter**（表格滚动的日志）。直接纳入 `ai-guard-meta` 会立刻报「缺 10 字段」；草率补 frontmatter 又可能**打断读它们的脚本** | 账本是**数据文件**而非**契约文档**——被 `ai-guard-unfixed` / `context` / `roadmap` / `sediment` 以**路径常量**读取，按 `## 标题` 与 `|` 表格行解析 | 先**读脚本解析逻辑**再决定：实测四脚本均按 `## ` / `|` 解析 ⇒ `---` 与 `key: value` 都不匹配 ⇒ **frontmatter 安全** | ✅ 已用（2026-10-04：3 个账本补 frontmatter 并纳入覆盖） | 给"历史数据文件"加元数据前；该文件被脚本按路径直接读取时 |
| **历史记录纳入"路径存在性检查"＝强制它指向现在（失真）** | `change-log` 正文含大量"当时"的路径（如旧 `docs/inbox/branch-notes` 与 `build_web.sh`，均已不存在），纳入 `M4` 后报 **9 处断链**——但**这些路径本来就该不存在**（那是历史） | `M4` 的判据（"正文引用的路径必须存在"）是为**活文档**设计的；对 **append-only 历史**要求它"指向现在"是**判据用错了对象**，改历史反而毁掉其价值 | 覆盖范围**按文件豁免**：`ai-guard-meta` 收录 `docs/records/` 时排除三个账本（代码注释写明理由） | ✅ 已用（2026-10-04） | 把"日志 / 历史 / 归档"类文件纳入面向"现行一致性"的守卫；修完发现要改历史记录原文 |
| **守卫从文本里抽路径时，「提及」≠「引用」** | 在"来源"说明里写「自 docs/guides/ 移入 7 份」（带尾斜杠）→ `M4` 报「正文路径引用不存在 docs/guides」（该目录已删）。**句子说的是"这里曾经有"，判据读成"这里现在必须有"** | `M4` 正则按「反引号 + `docs/` 开头 + 以 `/` 或 `.md`/`.sh` 结尾」匹配，**不区分"指向"与"提到"** | 说明性提法**去掉尾斜杠**（docs/guides 不以 / 结尾 ⇒ 不匹配）；通则：路径正则应能区分"指向"与"提及" | ✅ 已用（2026-10-04：5 处） | 守卫报的"断链"出现在**说明性文字**而非链接行；被报路径确实是**有意提及的旧位置** |

## 二十五、跨容器大迁移与「守卫间判据冲突」（2026-10-04 新增：二批 `docs/` → `.agents/` 判据收紧为「AI 运行时是否需要」）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **前缀替换会误伤「特例」——因为特例表漏了一项** | 93 份迁移里唯一一处错位：`product-roadmap.md` 应进 `.agents/direction/`，却被前缀规则改写成 .agents/knowledge/reference/product-roadmap.md（**该路径不存在**），14 个文件引用全断 | 替换脚本是「**特例表（长路径优先）+ 前缀表（兜底）**」两段式；我列特例时**漏了 product-roadmap**（它和 VISION 同进 direction），前缀表就把它吞进了 reference | 迁移后立刻跑一次**「新路径存在性」扫描**（`grep -o` 提取所有新路径 → 逐个 `test -e`），比事后靠守卫报错快得多 | ✅ 已修（2026-10-04：14 处） | 批量替换后；**只有个别文件**指向不存在的路径（其余全对）——典型特征是「特例表漏项」而非「规则写错」 |
| **覆盖面一扩大，历史欠账立刻浮现：11 份文档从来没有 frontmatter** | 把 `docs/architecture` / `docs/reference` 收进 `.agents/` 后，meta 一次性报 **101 条 M1**——`VISION.md`/`api-spec.md`/`status.md`/`product-roadmap.md` 等 **11 份核心文档连 frontmatter 都没有** | 它们此前在 `docs/` 下**不在 meta 的收集范围**里（分批渐进档），所以从来没有字段契约要求——**"PASS" 是覆盖面决定的，不是质量决定的** | ①按「覆盖面→欠账」预期排期（本次 11 份补 frontmatter + 68 文件 169 处相对路径重算 + 若干缺字段）；②**扩大守卫范围时，先预估会暴露多少欠账**，别当成"改坏了" | ✅ 已修（2026-10-04） | 守卫范围一扩就报出成批 FAIL，且都是「从来如此」而非「刚弄坏」；症状是**核心文档缺最基本字段** |
| **两个守卫对同一文件给出互相矛盾的判据** | `_directory.md` 陷入死结：`ai-guard-structure` 的 `actual_items` **明确排除它**（不该列进 `_index.md` 清单），而 `ai-guard-meta` 的 M3 **要求它被引用或在清单里**（否则报孤儿）——**列也错、不列也错** | 两个守卫各自演进，对「谁是入口节点」的定义不一致：structure 认 `_index.md`+`_directory.md` 是元文件，meta 的 `is_entry()` 只豁免 `_index.md` | 统一判据：`is_entry()` 加上 `_directory.md`（与 structure 的 `actual_items` 对齐），并在注释里写明「此处必须与另一守卫保持一致，否则同一文件被两边互相矛盾地要求」 | ✅ 已修（2026-10-04） | 某个文件在守卫 A 那里「多列」、在守卫 B 那里「孤儿」；两个守卫的**元文件定义**不一致 |
| **`_index.md` 被当成两种东西：目录清单 vs 内容索引** | `features/_index.md`（功能主轴，37 行功能）与 `rfc/_index.md`（RFC 清单，标题格式）搬进 `.agents/` 后立刻被 S2 报「漏列 66 项 / 多列 37 项」——它们**本来是内容索引，不是文件清单** | S2 的判据 `^\| \`([^\`]+)\` \|` **扫全文**，把功能 ID（`` `account` ``）与本就不带反引号的标题列都当文件名；而这两个文件在 `docs/` 下时**不在 structure 管辖范围**，问题一直没暴露 | ①S2 **限定在「## 文件清单」段内**抓取（分段解析，别扫全文）；②`features/_index.md` 补一个标准「文件清单」段；③`rfc/_index.md` 的文件名列加反引号 | ✅ 已修（2026-10-04） | 一个 `_index.md` 同时承担「目录清单」与「内容索引」两种职责；**守卫报的项数恰好等于业务条目数**（37 个功能 / 66 份 RFC） |
| **无尾斜杠的路径常量逃过前缀替换** | `ai-guard-feature.sh` 的 `FEAT = ROOT / 'docs/features'`（**无尾斜杠**）没被「docs/features 带斜杠」的前缀规则命中 → 守卫继续找旧路径，报「F0 索引不存在」而索引其实在 | 批量替换的键带尾斜杠（docs/features 加斜杠），而脚本里写的是不带斜杠的形式——**字符串替换只认精确匹配** | 迁移后对**脚本里的路径常量**单独扫一遍（`grep -rn "docs/" .agents/*/*.sh`），不依赖通用替换；通则：**替换规则要同时覆盖「带斜杠」与「不带斜杠」两种写法** | ✅ 已修（2026-10-04） | 守卫报「文件不存在」但文件确实在；替换后**只有脚本类文件**漏改 |

| **守卫把「元文件」当「业务文件」——同型问题连出三次** | ① `ai-guard-feature` 把 `features/_directory.md` 当意图卡（F9/F10 报「孤儿卡」）；② `ai-guard-structure` S2 把功能 ID（`account`）与 RFC 标题当文件名（假报「多列 37 / 漏列 66」）；③ `ai-guard-feature` F5 把 `rfc/_directory.md` 当 RFC（报「缺 date 字段」）——**三次都拦在提交上** | 守卫的扫描一律写成 `glob('*.md')` 或 `p.name != '_index.md'`——**只想到「索引文件」，没想到「目录契约文件」**；而 `_directory.md` 是 2026-10-03 才引入的新元文件，**早于它的守卫都没把它算进去** | 统一成 `p.name not in ('_index.md', '_directory.md')`；**新增任何「扫描某目录所有 md」的守卫时，先问一句「这个目录里有几种元文件」** | ✅ 已修（三处） | 守卫报出的「业务问题」（孤儿卡 / 死链 / 缺字段）**指向的文件名以下划线开头**；同一类问题在不同守卫里各冒一次 |

## 二十六、新守卫的「判据设计」（2026-10-04 新增：ai-guard-health 首建批）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **判据测「症状」而非「病」→ 满屏假阳性** | 体积红线按「>500 行」报出 **6 份超线**，看着像 6 个问题；逐份看结构后发现 **5 份章节清晰**（55~117 行/章），真问题只有 1 份（1107 行挤在 4 章、**277 行/章**） | 把**症状**（行数多）当成了**病**（内容没组织）。长而有组织的文档（契约表 / 手册 / **17 章的设计文档**）被无差别报出，**红线形同虚设** | 判据升级为「**>500 行 且 平均每章 >150 行**」= 结构失控；并区分 **red（结构失控）** 与 **warn（长但有组织）** 两档 | ✅ 已修（2026-10-04） | 新判据第一次跑就报出一批项，**数量与直觉不符**（"怎么会有 6 个？"）；懒得逐项核对而直接加豁免 |
| **按 `##` 数章节 → 被「H1 当章节用」骗过** | `memory-os-design.md` 被判「1107 行 / **4 章** = 277 行每章」→ 结构失控；实际它有 **17 章 + 11 子节**（结构清晰），只是章节用了 **H1**（`# 1. 文档目的`） | 判据写死 `re.findall(r'^## ')`——**假设了作者的层级风格**。而 markdown 里 H1 被当章节用是常见写法 | ①章节计数改为 **H2 + H3 之和**（测"组织度"而非"某级标题数"）；②**新增 H1 唯一性检测**（一个文档只应一个 H1），把层级不规范本身当一个质量维度报出 | ✅ 已修（2026-10-04：5 份文档降级 114 个标题） | 判据说「章节很少」但**文档里明明有小标题**；标题写法在不同文档间不一致 |
| **数 markdown 标题不跳代码块 → 把 shell 注释当标题** | 扫「H1 滥用」首轮报 `backend-deployment.md` 有 **88 个 H1**、以及另外 12 份文档——**看着像全仓格式崩坏**；排除代码块后真数只有 **3 份** | 代码块里的 shell 注释 `# 部署步骤` 行首就是 `# ` → 被正则当 H1。**首轮扫描几乎把一次小问题误判成大工程** | 所有"数标题"的正则**必须维护 `in_code` 状态**（逐行翻转 ``` 并跳过）；本坑已写进 `count_headings()` 的 docstring | ✅ 已修（2026-10-04） | 扫描结果**数量级异常**（88 个！）；同一类问题在"排除代码块"后**数量骤降** |

## 二十七、六顶层重构（2026-10-04：`.agents/` 20 → 6 个顶层目录）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **`.gitignore` 规则随目录移动而失配 → 隐私暴露（最严重）** | 「.agents/state」 移到 「.agents/records/state」 后，`.gitignore` 里 5 条 「.agents/state」 规则**全部失配** → `cost-log.jsonl`（成本账）/ `cadence.json`（游标）等**本机状态暴露在 git 里**；**没有任何守卫会报** | 移动目录时只想到"代码与文档的引用"，**忘了 `.gitignore` 也是"引用"**——它按**路径字符串**匹配，路径一变规则就静默失效 | 移动后立即 `git check-ignore -v <新路径>` **逐条反验**；本次修 5 条规则 | ✅ 已修（2026-10-04） | 移动**任何被 gitignore 的目录**后；`git status` 里出现本该忽略的文件；**敏感文件（凭据/成本/隐私）出现在待提交列表** |
| **无后缀文件逃过「按后缀过滤」的替换脚本**（同型第 2 次） | 「.githooks/pre-commit」（**无扩展名**）里的 15 处路径**完全没被替换** → **提交门禁**继续调已不存在的旧路径 | 替换脚本用 `git ls-files '*.md' '*.sh' '*.py'` 过滤 → 无后缀关键文件被静默跳过。**pitfalls 二十三已记过完全相同的坑** | 替换脚本改为 `git ls-files` **全量**（不按后缀过滤）；改完对 `.githooks/` 单独扫一遍 | ✅ 已修（2026-10-04） | 迁移后**提交被门禁拦**、报错指向"不存在的旧路径"；被漏的总是 `.githooks/` / `.gitignore` 这类无后缀文件 |
| **pathlib 拼接逃过字符串替换** | `ai-guard-skills.sh` 的 `AI / 'assets' / 'skills-spec.md'` 没被 「.agents/assets」 的替换命中 → 守卫报"缺 skills-spec"、技能包计数掉到 **0** | 脚本里有**两种路径写法**：字符串 `'.agents/assets/x'` 与 **pathlib 拼接** `AI / 'assets' / 'x'`。替换只覆盖了前者 | 扫描 `AI / 'xxx'` 形式单独替换；**新增路径常量时只用一种写法**（推荐 pathlib 拼接 + 单一 `AI` 根变量） | ✅ 已修（2026-10-04） | 守卫报"文件不存在"但文件确实在新位置；替换后**只有脚本类文件**漏改（文档都对了） |
| **给命令替换内部插注释 → 语法错误** | ROOT 加固时把 `cd "$(dirname "$0")/../.."` 换成 `cd "$(git rev-parse …)"   # 注释` —— 但有两处原写法是 **`$(cd … && pwd)`**（在命令替换里），注释插进去直接 **`bad substitution`** | 批量替换是**纯文本操作**，不区分"语句位置的 `cd`"与"命令替换里的 `cd`"；而**注释在命令替换内是语法错误**（`#` 会吃掉后面的 `)`） | 修 4 处（去掉命令替换内的注释）；**加固/替换后必须跑 `bash -n` 全量语法检查**（本次 `bash -n` 竟未报出这两处，是**运行时**才暴露的——所以还要**真跑一次**） | ✅ 已修（2026-10-04） | 批量替换后脚本报 `bad substitution` / `unbound variable`；`bash -n` 通过但运行即错 |
| **目录深度一变，三种相对路径全要重算** | 移动后 meta 报 **22 处 M1 断链** + structure 报 **104 处 S3/S4**；且修完还有第三轮 | 相对路径有**三种载体**：① frontmatter 的 `depends-on`/`related` ② 正文 markdown 链接 `](path)` ③ **`_directory.md` 正文里的裸路径**（含反引号包裹）。且**深度变化**让"指向仓库根"的路径（`docs/` `AGENTS.md`）也要加层 | 三轮分别扫：basename 索引重算 → `_directory.md` 专项重算 → **仓库根目标固定前缀**（`'../' * (depth+1)`）；**多候选 basename**（`README.md` 有多个）要按目录深度算而非按名字 | ✅ 已修（2026-10-04：共 ~430 处） | 移动**跨层级**的目录后；同一批里"部分引用对、部分断"；断链集中在**指向仓库根**与**多候选同名文件**上 |

| **`git mv` 不搬「未跟踪」文件 —— 与 gitignore 改动叠加即隐私泄露** | 本次隐私事故的**完整链条**：① `git mv .agents/state .agents/records/state` 只移动了**已跟踪**的 `_index.md`/`_directory.md`，**未跟踪**的本机状态（成本账 cost-log.jsonl · cost-cache.json）**留在旧位置**；② 同日把 gitignore 的旧规则改成了新规则 → **旧路径不再被忽略**；③ 同批的 `git add .agents` 把残留文件**加进了仓库**（已提交，事后自查才发现） | `git mv` 的语义是「移动**已跟踪**文件」——对未跟踪内容**静默无操作**（不报错、不提示）；而 gitignore 规则一改，原本「看不见」的残留就**突然可见**。**两个操作各自都合理，叠加起来造成泄露** | ① 移动含本机状态的目录后，**必须 `ls` 旧位置**确认无残留（本次留了 5 个文件）；② **改 gitignore 规则时新旧路径都保留忽略**（过渡期），确认无残留后再删旧规则；③ 事后清理：`git rm --cached` → 未 push 时用 `filter-branch`/`filter-repo` 重写历史 → 删 `refs/original/` + reflog expire + gc（本次：811 commit 重写，仓库 65M→22M） | ✅ 已修（2026-10-04：索引移除 + 历史重写 + 旧规则保留） | 移动**含 gitignore 内容**的目录后；`git status` 里出现本该忽略的文件；**敏感文件出现在待提交列表** |


## 二十八、守卫判据与「文档分层」的失配（2026-10-04：reference 分层批）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **`glob()` 不递归 → 覆盖面对半砍（静默假绿）** | `reference/` 分了 3 个子目录后，`ai-guard-meta` 报 **PASS**，但覆盖数从 **238 → 220**（**少了 18 份**）——**没报错，只是不再检查它们** | 收集范围写的是 `(AI/'reference').glob('*.md')`——**`glob` 只匹配本层**；子目录一出现，那些文件**静默逃出检查范围**。守卫仍打印 PASS，因为"检查过的都过了" | 改成 **`rglob('*.md')`**；并在报告里**同时打印覆盖数**（238→220 这种数字变化是唯一线索） | ✅ 已修（2026-10-04） | 目录**新增子目录**后；守卫 PASS 但**覆盖数/条目数变小**；"没报错"与"变少了"同时出现 |
| **`--fix` 的正则要求特定段结构 → 结构一变就静默不修（且仍报成功）** | `reference/_index.md` 的清单段是旧分类结构（含 `### 状态与总纲` 等小标题），`ai-guard-structure --fix` 的 `re.sub` **不匹配** → **清单没被刷新**，S2 连续两轮报同样的"漏列 16 / 多列 18"；**我的批量替换脚本也犯了同样毛病**——`if m:` 为 `None` 时仍写回并打印 ✓ | 自动修复工具靠**正则匹配固定段结构**；段里一旦多出小标题/换行，正则失配 → `re.sub` 无操作**且不报错**。而"报告成功"的逻辑写在**替换之外**（只看函数跑完没） | ① 手工按新结构重写清单段；② **修/写批量脚本时，"未匹配"必须显式报错**（本次我的脚本正是反例）；③ 断言式检查：改完**再跑一次**守卫确认 | ✅ 已修（2026-10-04） | 同一处 S2/S3 报错**修了一轮还在**；修复命令**报成功但文件 mtime 未变**；段结构被手工改过之后 |

| **守卫的目录收集写「只查已有 `_index.md` 的目录」→ 不建即免检（后门）** | `ai-guard-structure` 原判据 `SUBS = [d for d in AG.rglob("*") if (d/"_index.md").exists()]` —— **一个目录只要不建 `_index.md`，就永远不在检查范围内**。实测漏掉 **5 个**：`workspace/`（在制品容器，**用户规划里最重要的目录**）· `rules/assets/adr`（6 份 ADR）· `rules/assets/projects`（4 份资产卡）· 技能包目录 · 配置目录。**守卫一直报 PASS** | 收集条件用「**已存在的标志物**」当准入——变成**自证**：有 `_index.md` 才检查 `_index.md`。这类判据的失效方式是**静默扩大盲区**：新增目录越多、没被检查的越多，而 PASS 一直绿 | 改为 `_structural(d)`：**默认所有目录都要两件套**，只**显式排除**四类（隐藏/缓存 · `*.d` 配置 · 含 `SKILL.md` 的技能包 · `workspace/` 下第 2 层起的在制品）；并补齐 3 组真遗漏（workspace / adr / projects） | ✅ 已修（2026-10-04） | 判据的准入条件里出现「**已存在某标志物**」；新增目录后**守卫仍然 PASS**；**统计数字（子目录数）与新结构不符** |
| **launchd plist 是「安装时快照」→ 改了脚本变量不重装则仍旧路径** | 六顶层重构时改了 `ai-setup-launchd.sh` 的 `STATE`（旧路径 → `.agents/records/state`）并重装过一次；但重装**发生在修 `STATE` 之前**，于是 plist 里仍写着旧路径 → 过了一段时间，**旧目录 「.agents/state」 被自动重建**（`backup.log` 出现），`ai-guard-structure` 随即报它缺两件套 | plist 把路径**固化在文件里**（不是每次执行时解析），所以**脚本里的变量改了不等于 plist 改了**；而「重装过」这个动作**会让人以为已经同步** | ① 改任何**会写进 plist 的变量**后，**必须重装**（`ai-setup-launchd.sh`）并 `--check`；② 用 grep **反查 plist 里的固化路径**，而不是相信脚本；③ 顺序：**先定稿变量，再重装** | ✅ 已修（2026-10-04：重装 + plist 路径已核 + 旧目录清理） | 定时任务/系统集成**报路径不存在**或**往旧路径写文件**；「我明明改过了」；**旧目录在一段时间后自己冒出来** |

| **「兜底工具」替代「归并」→ 数据源只增不减** | 2026-08-23 用户发现未修项散在 `REVIEW` / `audits` / `task-log` 三处，当时的解法是**建聚合工具**（`ai-guard-unfixed`）而非归并；两个月后 `task-log.md` 变成**空转的中间产物**（「待办迁移区」的 P2 表已空、「当前任务」多是 2026-08 的已修任务），**却仍被 4 个守卫当数据源读**。2026-10-04 用户再问「5 处每一处都必要么」才被清出 | **兜底让症状消失，但没减少真相源**——症状是"看不到全貌"，根因是"多处记同一件事"；聚合工具后者的病还在，且**工具本身成了新的维护面**（它的读取清单也是要维护的真相源） | ① 遇到"信息散在多处"先问「**能不能归并成一处**」，归并不是万能时才兜底；② 兜底工具必须**显式声明唯一权威**（本工具的注释就写了"REVIEW.md 是唯一真相源"——写得对，但没执行）；③ **定期回访兜底的必要性**：当兜底来源已空转，就该退役它 | ✅ 已修（2026-10-04：task-log 退役为历史档案 + 撤 3 处守卫依赖） | 兜底工具的**某个来源长期为空却仍在读取**；"我明明建了工具"却仍有人问"到底在哪"；**数据源数量只增不减** |

## 三十、知识「按性质存」≠「按领域取」（2026-10-04 域视图批）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **知识「按性质存」≠「按领域取」——缺域视图** | 用户问「交易插件现状 + 问题 + 规划」→ 要翻 **4 处**（trading-features / REVIEW 的 103 个交易编号 / rfc 21 份 / designs 5 份）；问「项目达到多少」→ 要翻 3 处 | 知识**按性质存**（7 顶层，这层是对的）；但人**按领域问** → **中间缺一层「域视图」**。本会话建了 15 个守卫（检查知识**对不对**），却只建了 1 个视图（`todo`）——**守卫解决"不可信"，视图解决"找不到"**，后者对人的价值更大 | 新建 `ai-domain-view.py`：**纯聚合、现算不落盘**（落盘＝第 7 个数据源）。提供 `domain <域>`（域视图）与 `overview`（项目全景）| ✅ 已建（2026-10-04） | **同一个问题要翻 3+ 处**才能答；用户问"现状"时你要开 4 个文件 |
| **域推导只看 ID 前缀 → 整个域静默消失** | platform 的 6 个功能（`push` / `entry` / `share-ext` / `media` / `account` / `plugin-model`）**没有域前缀** → 全被归进 kernel → **platform 域根本不存在**，域总览里看不到它，17 项未修也全落「未归类」 | 用「ID 前缀」当域判据时，**默认了"每个域都有前缀"**；而 features 主轴里 6 个 platform 功能是无前缀的裸 ID | 加 `NO_PREFIX_PLATFORM` 显式映射；并让域总览**必须显示「未归类」**——否则「某域未修 0」会被读成全绿 | ✅ 已修（2026-10-04） | 域列表里**少了本该有的域**；某域数字为 0 但直觉上不可能；未归类占比异常高 |
| **自由文本状态 → 任何计数都只是近似** | 同一个「未修项」数，三个实现给出 **15 / 41 / 47** 三个值；到底哪些算"未修"取决于正则怎么写 | REVIEW 表格**没有结构化状态列**——"是否已修"写在**行内自由文本**里（`✅ 已修` / `✅ 已实测` / `✅ 已收口` / `⏳ 部分落地` …）→ 正则匹配**必然漏**，且漏多少无人知 | 短期：三处统一到**同一判据**（`DONE_PAT`）+ 输出标 `≈`；长期（建议）：给 REVIEW 表格加 **`状态` 列**（open/fixed/partial）→ 机器可精确计数 | ⚠️ 部分（待用户拍板加列） | **同一事实、两个命令、两个数**；给数字时不敢说确定 |

## 三十一、REVIEW 未修项的「计数不可信」根治（2026-10-04）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **「枚举已完成写法」判已修 → 每遇新写法就漏一个** | 未修项数一路飘：`15 → 41 → 47 → 64 → 40 → 24`。每次都是"又发现一种没覆盖的写法"（`✅ 已实测` → `✅ **已修` → `✅ 全落地` → **`⚠️ 复核：误报`**）| 判据写成**枚举**（`已修｜已解｜已收口｜已闭环｜…`）→ 这是**开集当闭集用**："完成"的表述是**开放集合**，枚举永远追不上 | **反向判据**：`含 ✅ 且 ✅ 后不是「部分/半/待/未」= 已修`。并把判据**收敛到唯一实现**（`ai-domain-view.py review_items()`），其他工具调它；**结案有两形态**：① `✅ 且非部分/半/待/未` ② `误报/不成立/无需修`。**收敛过程即证据**：同一份 REVIEW 的未修数从 64 → 40 → 24 → **20** | ✅ 已修（2026-10-04） | 判据里出现**枚举的"状态词"列表**；每过一阵就要"补一种写法"；**同一事实的数字反复变** |
| **「未修复」段里 84% 是已修项 → 段名与内容相反** | `🔴 P2（未修复）` 段 **184 行**里只有 26 行真未修，其余 158 行是**已修历史**（当时列在未修段内、状态已 ✅）。任何"未修数"都只能靠**正则扫行内文本**估 | 把新条目**追加在同一段**、修好后**只改行内状态**而不移走 → 段逐渐变成"问题流水账"，**段名承诺的语义（未修复）与内容脱节** | **归档分离**：已修项整体移入 `docs/archive/review-fixed-2026-10.md`；`REVIEW.md` 只留真未修 + 段首声明"数行数 = 未修数"。**从此计数不依赖文本判据** | ✅ 已修（2026-10-04：236 + 16 行归档） | 段落标题写"未修/待办/待处理"但**内容大半已完成**；某个"清单"需要**解析器**才能计数 |
| **同一份事实、两套解析 → 两个数** | `ai-guard-unfixed` 报 **17** 条、`ai-domain-view` 报 **40** 条、段内实际行数 **40** —— 三个数 | 两个脚本**各写一份 REVIEW 解析**（`DONE_MARKS` 含裸 `✅` vs `DONE_PAT` 枚举），判据不同 → 结果必然不同 | 解析**收敛到一处**（`review_items()`），`unfixed` 用 `subprocess` 调 `--review-items`；验证方式：**段内行数 = 解析数 = 守卫数**（三处对拍）| ✅ 已修（2026-10-04） | **同一事实两个工具给出两个数**；每个工具都"看起来对" |

## 三十二、REVIEW 纯化：只记未修项（2026-10-04）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **「最近 N 条」摘要段没人滚动 → 退化成完整列表，与归档重复** | `REVIEW.md` 的「已修复区」标题写「**最近 10 条**，一行摘要」，**实际 46 行**；内容与 `change-log.md`（顶部即最近批次）+ 新建的归档文件**三者同类** —— 又一处双源 | 段的定位是「**滚动摘要**」（超出 N 条即移走），但**没有任何机制或守卫强制滚动** → **只进不出**，逐渐变成第二份完整历史；而"最近修了什么"本来 change-log 就有 | 撤掉「已修复区」：`REVIEW.md` **只记未修项**；已修项 → 归档文件（原摘要一并并入）；"最近修了什么" → `change-log.md`。同步撤 `ai-guard-unfixed` 的 ④ 状态对账（判据失去对象）| ✅ 已修（2026-10-04） | 标「最近 N 条」的段落**实际条数远超 N**；同一类信息**两处都能找到**；某段的**移除从未发生** |

## 三十三、REVIEW 的「假精确」：只统计表格行（2026-10-04）

| 坑 | 症状 | 根因 | 修复 | 状态 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| **「未修项」有两种格式，工具只认一种 → 假精确** | `REVIEW.md` 的 `P0/P3` 段是**多批次审查的过程记录**（列表 + 正文 + 表格混排）：**6 条表格行** + **3 处「列表形式」的未修登记**（每处把 5-11 项写在一条里，用 `·` 分隔，共 **≈25 项**）。解析器只认表格行 → 报 **20 条**，**看着很确定**，实则**漏了近一半** | 未修项有**两种载体**（表格行 / 列表项），而工具按"格式"解析、不按"语义"；更隐蔽的是**工具不报错**——它给出一个**确定的数字**，掩盖了**未被统计的部分**。这比"假绿"更危险：假绿至少内容是错的，**假精确看起来是对的** | ① `ai-domain-view.py` 增 `pending_unstructured()`：**把未统计的部分显式报出来**（`⚠️ 另有 ≈25 项「列表形式」未修待结构化（未计入）`），`todo` 与 `overview` 同步；② `P0/P3` 段改为「待结构化」表（列项目名 + 项数）；③ 6 条 `P2-*` 表格行按编号移回 P2 段 | ✅ 已修（2026-10-04：显式标注 + 项目名清单；**逐项结构化仍是待办**） | 某个"计数"**过于整齐**；同一份文档里**格式不统一**（表格/列表/正文混排）；工具**能报数但不报"没数进去的部分"** |

---

**追加方式**：AI 在开发/审核中发现新坑 → ①入对应 checklists（活文档）②本文件按域补一行（索引）。两条都要，防止只入一处。
