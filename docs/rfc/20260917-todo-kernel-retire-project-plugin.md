---
title: 待办归 Kernel——撤 project 插件与待办重定位
description: 用户在生产里问「任务模块的作用是干嘛的、项目插件的作用」，调研钉出三件事：①同一个「任务」，前端/文档/用户拍板都算它是 Kernel 基础能力，后端写端点却 403 锁在 project 插件上（能看不能动）；②「待办」有两套并存——活的在记忆 actionable（Feed 卡 + AI 待行动事项注入），死的在 Task 表（8 月起零写入），而 R2 还在把前者搬进后者（Feed 卡消失、AI 也不知道了）；③project 插件真正装的是 AdaiOS 自举看板（commit/RFC/Kernel 状态），对他人是第三视角，对自己已被 task-log.md 替代。本 RFC 定：撤 project 插件、待办独立为 Kernel builtin（纯清单两态 + 可选到期日 + 到期推送，不进 Feed），并定下 core / builtin / optional 三层能力定位。
date: 2026-09-17
status: implemented
decided-by: adai（2026-09-17 五条拍板：撤 project 插件 / 待办叫「待办」归 Kernel builtin / 纯清单两态 + 到期提醒 / Feed 去掉待办卡 / 不考虑旧 App 兼容可直接重装）
tags: [待办, 插件门控, project插件, Kernel, 定位拨正, 形态总纲]
related:
  - ./20260814-domain-plugin-model.md
  - ./20260916-first-meeting.md
  - ../architecture/framework-plus-plugin-model.md
  - ../reference/framework-plugin-gap.md
  - ../reference/feature-reference.md
  - ../guides/project-os-usage.md
  - ../review/audits/2026-08-20-app-health-check.md
  - ../review/REVIEW.md
---

# 待办归 Kernel——撤 project 插件与待办重定位

> **触发来源**：2026-09-17 19:52 生产端真实提问——「**目前任务模块的作用是干嘛的　还有插件项目的作用**」
> （该条已被 `guard-prod.sh` 的「用户之声」从空态引导 chip 中区分出来，见 `../reference/change-log.md:8`）。
> 用户随后在会话中提出四轮追问，核心是「**任务和项目插件，这两项的必要性**」。
>
> **五条拍板（2026-09-17）**：① **撤掉 project 插件**；② 待办留下，叫「**待办**」（不叫任务），归 **Kernel builtin**；
> ③ 形态 = **纯清单两态 + 可选到期日 + 到期提醒**（Feed 里不舒服，要有自己的地方）；
> ④ **Feed 去掉待办卡**；⑤ **不考虑旧 App 兼容**（用户可重装，端点直接硬切）。
>
> **实施边界**：本 RFC 为文档先行件；获批后按 §五 开工（**不部署、不 push**，等验收）。

---

## 一、现状事实（逐条钉到行号）

### F1 同一个「任务」，前端 / 文档 / 用户拍板 vs 后端实现，判成了两种东西

| 立场 | 口径 | 证据 |
|:-----|:-----|:-----|
| 前端 app | 「任务 = Kernel 基础服务（待办人人都有），不按插件门控」；2026-09-16 用户拍板「算原生能力，不要挪进插件组」 | `apps/adai-app/lib/pages/launcher_page.dart:290-292`、`:533` |
| 前端 web | 导航项 `_NavEntry('任务', …, null, …)`——`plugin = null` 即常驻基础服务（「项目」才是 `'project'`） | `apps/adai-web/lib/desktop_shell.dart:36,51-52` |
| Kernel 文档 | 「Kernel 基础服务（记录/问答/记忆/档案/时间线/搜索/**待办**）不是插件，人人都有」 | `kernel/plugin/PluginRegistry.java:12-13`、`docs/reference/feature-reference.md:1047` |
| RFC 决策 | D1：「**待办 = Kernel 基础能力**」，`RecordToTaskLinker` 去掉 domain 门槛 | `docs/rfc/20260814-domain-plugin-model.md:36` |
| **后端实现** | **写端点要 project 插件，否则 403「project 插件未启用，无法管理任务」** | `interfaces/ProjectStatusController.java:38-44`（helper）、`:74`（POST）、`:92`（PUT）、`:110-112`（DELETE） |

**读端点不门控**（`GET /project/tasks` `:59-65`、`GET /project/tasks/stats` `:121-125`）→ 无 project 插件的用户：
记录自动生成的待办**看得见、读得到，却建不了、改不了、完不成**。

### F2 「待办」有两套并存，而 R2 正在把活的那套搬进死的那套

| 通道 | 载体 | 出口 | 状态 |
|:-----|:-----|:-----|:-----|
| **活的** | 记忆 `actionable` | ① Feed 待办卡 `FeedAppService.java:152-153`；② 问答时注入「待行动事项」`ContextEngine.java:374-376`；③ 记忆页「待办」标签 + 一键完成 `MemoryService.markDone:526`（`PATCH /memory/{id}/done`） | 人人可用，P-app-03 已闭环 |
| **死的** | Task 表 `data/{userId}/project/tasks/YYYY/MM.md` | 任务页（app/web）；写操作被 project 插件 403 | 2026-08-01 后零写入 |

**R2 的搬运**：`RecordToTaskLinker.link()` 在建完任务后调用 `memoryService.clearActionable`（`RecordToTaskLinker.java:71,73`；
注释原话「跟踪点从记忆转移到任务看板，记忆只留事实回顾」；调用点 `interfaces/RecordController.java:255-262`，**不经任何门控**）。
后果：记录落盘那一刻（`RecordController.java:205` 已写入带 `actionable` 的记忆），**Feed 卡消失、AI 的「待行动事项」也丢了**——
待办被搬进一个没人打开的页面；对无插件用户，那个页面还点不动。

### F3 project 插件真正装的是「AdaiOS 自举看板」，不是通用项目管理

- 项目状态 = 本仓库的 git 提交数、RFC 状态、Kernel 组件状态、API 端点数（`ProjectStatusAppService.java:22,49,120`，纯数据聚合、不调用 AI）；
- `os/project-os/AGENTS.md` 自述：「当前管理的项目就是 **AdaiOS 开发本身**」；`docs/guides/project-os-usage.md:157-166` 同口径；
- 两轮审查早已判它违规、建议迁移，**至今未修、也未归口**：
  `../review/audits/2026-08-20-app-health-check.md:84`（S-2 第一原则泄漏）、`docs/review/app-polish-2026-08-15.md:47,67`（P-app-05，建议迁 admin 或降级）。

### F4 使用实据（本地 `data/` 口径，生产侧未核）

| 域 | 规模 | 最后修改 |
|:---|:-----|:---------|
| `adai/project`（待办） | 1 个文件、10 条、**全部 DONE** | **2026-08-01** |
| `alice/project` | 1 个文件、1 条 TODO | 2026-08-15 |
| `adai/trading`（对照） | 155 文件 / 1672 条成交 | 2026-09-05 |
| `adai/learn`（对照） | 16 文件 / 9 张卡 | 2026-09-15 |

同时前端**两个待办页零专项测试**；后端 30 个 `@Test`（`ProjectStatusControllerTest` 9 + `RecordToTaskLinkerTest` 9 + `ProjectFileRepositoryTest` 12）全在维护这条没人走的链路。
**注**：本地 `data/` 快照滞后于生产（心跳 2026-09-17），故「零写入」为本地口径；F3 的定位问题不依赖该数据。

### F5 用户的真实用法与期待（本次会话一手信息）

> 「目前情况是**我立即点击完成，而不在意是否已经真的完成**」——Feed 里的待办卡是**挡路**，不是被使用；
> 「期待场景：**有地方看、会提醒、到期通知**……反正感觉**在 Feed 里不舒服**」。

---

## 二、问题定性

**不是「没用的模块」，是三个错位叠在一起**：

1. **归属错位**：待办被前后端判成两种东西（F1）——能力是 Kernel 的，门控是插件的；
2. **通道并行**：同一件事有两份载体，活的那份被 R2 单向搬运进死的那份（F2）——**在持续把有效数据搬进死路**；
3. **定位漂移**：project 插件名为「个人项目管理」，实为 AdaiOS 自举看板（F3）——对他人是第三视角，对自己已被 `task-log.md` 替代。

---

## 三、能力三层定位（本 RFC 的通用产出）

> 用户提出「一切都可以当作插件，包括任务」——方向认同，但**内核不能插件化**（插件要靠它运行），
> 且「基础插件」这个词会误导实现（迟早有人去给记录做门控）。定三层：

| 层 | 内容 | 默认 | 能否关 | 现有实现 |
|:---|:-----|:----:|:------:|:---------|
| **core 内核** | 记录 / 问答 / 记忆 / 上下文 / 身份 / 存储 | 开 | **不可关** | Kernel 硬编码 |
| **builtin 内置能力** | **待办** / 搜索 / 时间线 / 简报 | 开 | 可关（用户侧） | Kernel 硬编码的 UI 项 |
| **optional 可选插件** | trading / learn | 关 | 可开（admin / 未来用户申请） | `Account.plugins` 门控 |

**判据一句话**：没它别的跑不起来 → core；第一次用就需要 → builtin；只有一部分人需要 → optional。
**统一心智**：所有用户可见能力登记在同一张表里（tier + 默认值不同），而不是"内核能力 vs 插件"两套机制。

---

## 四、方案

### 4.1 待办定稿（builtin）

| 维度 | 定稿 |
|:-----|:-----|
| 归属 | **Kernel builtin**：人人有、默认开、**无插件门控**（摘掉三处 403） |
| 形态 | **纯清单**：未完成在上 / 已完成折叠；每条 = 一句话 +（可选）到期日 + 完成 + 删除；顶部直接加一条 |
| 状态 | **两态**：`OPEN` / `DONE`（`DOING`、`CANCELLED` 去掉；取消即删除；看板三列随之消失） |
| 收集 | 记录里可执行 → 自动进待办（R2 保留）+ 清单页手动加 |
| 查看 | **自己的地方**：app Launcher「待办」/ web 导航「待办」（前端入口本就已归 builtin，只需改名与重做页面） |
| 提醒 | 可选**到期日** → 到期当天早上推一条 + 当天到点再推一条（新推送类型 `todo-due`，进 `PushSettings.ALL_TYPES`，默认开、可关） |
| Feed | **不再出现待办卡**（`FeedAppService` 停止产出 `action` 条目，Feed 回归纯对话流） |

### 4.2 撤 project 插件

| 项 | 处置 |
|:---|:-----|
| 入口 | app「阿呆系统」、web「项目」**下线** |
| 代码 | `ProjectStatusController` / `ProjectStatusAppService` / `ProjectContextContributor` / `ProjectKnowledgeSource` / `PluginRegistry` 的 project 项 / admin 账号页 project 开关 —— **删除，不留死代码** |
| 知识 | `os/project-os/` 文件**保留在仓库**（File First，知识不删），**不再注入任何用户上下文** |
| 数据 | 旧 `data/{userId}/project/` **原样留存，不迁不删**（adai 10 条全 DONE + alice 1 条测试） |
| 账号 | `Account.plugins` 里的残留 `"project"` 无需迁移——`PluginRegistry.isValid` 自动过滤未知插件名 |
| 文档 | roadmap 的 Project OS 状态行、feature-reference §10、api-spec 相关章节同步 |

### 4.3 记忆联动（单向同步，用户已确认）

- **建待办时不动记忆**（不再 `clearActionable`）→ 记忆继续供 AI 使用（问答时它该知道你手头有事）；
- **完成待办时**同步 `markDone(记忆)`；**删除待办时**同步清记忆标记（不留僵尸）；
- 即：**Task 是你会看会点的那份，记忆是 AI 用的那份，状态单向由待办流向记忆。**

### 4.4 命名迁移（一次改干净，避免 `task` / `todo` 混用）

| 层 | 现在 | 改成 |
|:---|:-----|:-----|
| 概念 / UI | 「任务」 | **「待办」** |
| 存储 | `data/{userId}/project/tasks/YYYY/MM.md` | `data/{userId}/todos/YYYY/MM.md` |
| 端点 | `/api/v1/project/tasks*` | **`/api/v1/todos*`（旧路径删除，不做兼容别名）** |
| 类 | `Task` / `TaskStatus` / `TaskRepository` / `ProjectTaskAppService` / `ProjectFileRepository` / `RecordToTaskLinker` | `Todo` / `TodoStatus` / `TodoRepository` / `TodoAppService` / `TodoFileRepository` / `RecordToTodoLinker` |
| 包 | `domain/project`（待办部分） | **`kernel/todo`**（与 memory 同级的基础能力） |
| 推送 | 无 | `todo-due` |

---

## 五、实施步骤（顺序即依赖）

| 步 | 动作 | 完成判据 |
|:--:|:-----|:---------|
| **①** | **摘门控与 R2 搬运**：任务写端点去 `requireProjectPlugin`；`#备忘/#想法` 排除判断从 R2 前移到记忆写入侧；R2 停止 `createTask` + `clearActionable` | 无插件用户可建/改/完成待办；Feed 待办不再凭空消失 |
| **②** | **改名与搬家**（§4.4 全表）：`Task*` → `Todo*`、`domain/project` → `kernel/todo`、端点 `/api/v1/todos*`、存储 `data/{userId}/todos/`、状态两态 | 全仓 `project/tasks` / `Task` 残留 = 0（历史文档除外） |
| **③** | **清单页 + 到期 + 推送**：双端页面重做（纯清单、两态）；`due` 字段（存储格式加可选行，向后兼容）；`TodoReminderService` + `todo-due` 进 PushSettings 与开关页 | 双端可增删改完成、可设到期；到期收到推送 |
| **④** | **撤 project 插件**（§4.2） | 两端入口消失；无 project 插件相关端点/类/开关；`./gradlew test` 全绿 |
| **⑤** | **文档与测试收尾**：api-spec / feature-reference §10 / roadmap / status.md / task-log M10；后端测试随改名重写；**补双端待办页专项测试**（当前为零） | guard-meta PASS + 三端测试全绿 + /ship 流程 |

---

## 六、验证

- **后端**：`./gradlew test` 全绿（改造后的 Todo 测试 + 门控移除回归 + R2 单向同步回归）；
- **前端**：`flutter analyze` 0 issue + `flutter test` 全绿（新增待办页用例）；
- **端到端**：无插件账号（如 `family`）可完整走通「记录 → 待办 → 到期提醒 → 完成 → 记忆同步」；
- **反向验证**：Feed 中不再出现 `action` 条目；撤插件后账号残留 `"project"` 不引发异常。

## 七、非目标（明确不做）

- **系统日历 / 提醒事项接入**（EventKit / CalDAV）——「日程/时间块」是另一层能力，且 Android/Web 无对应实现；
- **看板三列 / 优先级 / 标签 / RFC 引用 / 统计行**——那是项目管理，随 project 插件一起走；
- **项目 / 里程碑 / 迭代 / 复盘**——将来真有第二个用户的通用项目管理需求，再另立插件重写；
- **插件动态加载 / 应用市场**（沿用 `framework-plus-plugin-model.md:117` 结论）；
- **旧 App 兼容别名**（用户已确认可重装）。

## 八、风险与遗留

| 风险 | 处置 |
|:-----|:-----|
| 端点破坏性变更（已装 App 404） | 用户已拍板「可重装」；本次不做兼容壳，api-spec 标注为 breaking |
| 旧待办数据成为孤儿文件 | **「不迁」的原假设已被生产核对修正**（2026-09-17 只读实测：adai 有 10 条**全部 TODO**、最后写入 09-17 19:32，且都是 R2 从真实记录自动生成）——这批待办**只存在于 Task 表**（旧 R2 建任务时已 `clearActionable`，记忆侧无 actionable），**不迁移 = 升级后从产品里消失**；**用户已拍板全迁、2026-09-17 23:32 迁移完成**（旧文件一字未动 + 备份 + 以生产文件内容实测解析 PASS）；生产仍跑旧版本期间旧 R2 会继续写旧目录，部署前需再核增量 |
| `todo-due` 推送打扰 | 进 `PushSettings.ALL_TYPES`（默认开、用户可关），与 `learn-review` 同机制 |
| 记忆与待办状态不一致 | 单向同步（完成/删除由待办流向记忆），建待办时不再清记忆 |
| 本次未核生产 `data/` | 待办零写入为本地口径；实施前如需以生产为准，可只读核一次（B8 需用户确认） |

---

## 九、实施留痕（2026-09-17，主会话实施 + 3 子代理并行）

**状态**：`implemented` + **已部署（2026-09-17 第十次部署 v3.72）**——`deploy-gate.sh` GATE-BEFORE 三门 PASS + GATE-AFTER smoke 9/9；三端静态产物同日原子替换；公网 `/` `/m/` `/admin/` 200 + api 401；真链探针 `GET /todos` **10 条**（迁移数据可见）· 旧端点 404 · `todo-due` 关→恢复 · Feed `action` 条目 0；代码提交 `717f338`（实施）+ `6ac8e3b`（迁移登记），**未 push**。

| 步 | 完成判据 | 结果 |
|:--:|:---------|:-----|
| ① | 无插件用户可建/改/完成待办；Feed 待办不再凭空消失 | ✅ `/api/v1/todos` 不依赖 `PluginService`（`TodoControllerTest.createTodo_withoutPluginPlugin_isNotForbidden` 钉住）；R2 保留建待办但**不再 `clearActionable`**（`RecordToTodoLinkerTest.link_doesNotTouchMemory`），`#备忘/#想法` 前移到记忆写入侧（`RecordControllerTest.createRecord_excludeTag_memoryIsNotActionable`） |
| ② | 全仓 `project/tasks` / `Task` 残留 = 0（历史文档除外） | ✅ `PLUGIN_PROJECT` 全仓 0 处 · 旧类全删 · 新 `/api/v1/todos` + `data/{userId}/todos/YYYY/MM.md` + 两态 + 可选到期日（`TodoFileRepositoryTest`） |
| ③ | 双端可增删改完成、可设到期；到期收到推送 | ✅ app `todo_page.dart` + web `todo_page.dart`（专项测试 15 + 8 例，此前为零）；`TodoReminderService` 到期当天 08:00 / 18:00、类型 `todo-due`（默认开、可关、锁屏只报件数）、深链 `todo:today` 直达待办页（`TodoReminderServiceTest`） |
| ④ | 两端入口消失；无 project 相关端点/类/开关；`./gradlew test` 全绿 | ✅ 6 端点删除、`domain/project` 整包 + 5 个类删除、admin 插件开关/数据页签下线；后端 **2015**（0 失败 / 2 跳过，`--rerun-tasks --no-build-cache` 真跑） |
| ⑤ | guard-meta PASS + 三端测试全绿 + /ship 流程 | ✅ guard.sh **10 PASS / 0 HIT / 1 NOTE** · 三端 `flutter analyze` 0 issue（app **379** / web **317** / admin **69** 全绿）· guard-meta / guard-align / guard-sediment / change-log 见同批收尾 |

**§六 验证逐条**：后端 `./gradlew test` 全绿 ✅（含门控移除回归 + R2 单向同步回归）；前端 `flutter analyze` 0 issue + 测试全绿 ✅；端到端以「接口级（`TodoControllerTest`，含无插件账号 family）+ 服务级（`TodoAppServiceTest` 记忆联动）+ 页面级（双端待办页专项测试）」三层覆盖——**未起服务做端到端冒烟**（本批不部署）；反向验证 ✅（Feed 不再产出 `action` 条目：`FeedAppServiceTest.getFeed_pendingActionMemory_doesNotProduceActionEntry`；账号残留 `"project"` 不引发异常：`PluginServiceTest` / `MeControllerTest` 回归）。

**遗留（登记）**：① 端点破坏性变更 → 旧 App 需重装（用户已拍板），api-spec 标注 breaking；② **生产 `data/` 已只读核对（2026-09-17 23:15，用户授权）**：仅 `adai` 有旧 project 数据 = `tasks/2026/08.md` 1 条 + `09.md` 9 条（**10 条全部 TODO**，每条都带 `sourceRecordId`、由 R2 从真实记录自动生成；最后写入 **09-17 19:32**，即用户 19:52 提问前 20 分钟），`alice`/`family`/`adan`/`admin`/`applereview`/`default` 均无，`todos/` 目录尚不存在——**RFC F4 的「2026-08-01 后零写入」是本地口径、不成立于生产**；「原样留存不迁」的后果因此升级为「10 条活待办在升级后从产品里消失」，**用户拍板「全迁 10 条」→ 2026-09-17 23:32 迁移完成**（`todos/2026/{08,09}.md`，TODO → OPEN、保留原 `id`/`sourceRecordId`，旧文件一字未动 + 备份，以生产文件内容喂 `TodoFileRepository` 实测 PASS）；⚠️ 生产仍跑旧版本、旧 R2 仍会写旧目录 → 部署前需再核增量；③ 决策沉淀：`ai-engineering/assets/adr/ADR-006.md`（能力三层定位 core / builtin / optional）。
