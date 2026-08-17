# Plugin 模型实施任务拆分

> **依据**：`docs/rfc/20260814-domain-plugin-model.md`（5 决策 + 门控全通道 + 两步走）
> **执行顺序**（adai 定）：① 任务拆分（本文档）→ ② 同步实现（多项目）→ ③ 自测通过 → ④ 前后端连调测试
> **状态**：第一步 ✅ 已实施 + 自测 + 前后端连调通过（2026-08-15：后端 394 · adai-app 89 · adai-web 44 全绿；alice E2E：知识门控生效无交易知识、非 project 记录自动转待办、DeepSeek 修复后 AI 正常）· **第二步 ✅ 已实施 + 自测通过（2026-08-15：后端 422 · adai-app 92 · adai-web 46 · adai-admin 32 全绿；插件门控全通道落地：Account.plugins + /me/plugins + ContextEngine 知识/贡献者门控 + D5 domain 收敛 + Feed 行情卡门控 + promote 403 + 前端模块显隐 + admin 插件开关；前后端连调待真实账号验证）**

---

## 第一步 · 最小闭环（单用户即有价值）

> 目标：拨正"知识注入所有用户"的错位 + R2 通用化，成本一天内。

### adai-core

| # | 任务 | 涉及文件 | 验证 |
|:-:|:-----|:---------|:-----|
| T1.1 | **R2 自动转待办通用化**：`RecordToTaskLinker.link()` 去掉 `"project".equals(domain)` 门槛（`RecordToTaskLinker.java:49`），任何用户可执行记录（AI `actionable` + 排除 `#备忘/#想法`）都转自己的待办 | `RecordToTaskLinker.java` / `RecordController.java:220`（挂点不变）| 新增测试：非 project 域记录可转待办 + 排除标签仍挡 + 幂等不重复 |
| T1.2 | **误转保护**：仅**非空摘要**的记录才转待办（AI 判定空/无内容跳过），空 actionable 过滤沿用 `findPendingActions` 机制 | `RecordToTaskLinker.java` | 测试：空摘要/空 actionable 不转 |
| T1.3 | **KnowledgeSource 注入按 owner 过滤**（⚠️ superseded 2026-08-17：实现已被插件门控取代——RFC 20260814 后 os/ 知识按用户启用 trading 插件注入，owner 常量不再生效；保留本行供追溯）：trading/project 知识源只在 owner 账号（配置 `adai.plugins.owner-user-id`，默认 adai）注入；life 不门控。拨正"os/ 知识注入所有用户" | `ContextEngine.loadKnowledgeContext` / 知识源 owner 常量 | 测试：非 owner 用户问答含交易词 → 上下文无 os/ 知识；owner 正常 |

### adai-app / adai-web

| # | 任务 | 涉及文件 | 验证 |
|:-:|:-----|:---------|:-----|
| T1.4 | **前端 fallback 修复**：URL 无 userId 时不再回退 `default`（账号已删），直接首屏选号 | 双端入口路由 / UserStore | 测试/手测：清 URL 刷新 → 首屏选号 |
| T1.5 | **DeepSeek 空内容修复（连调发现的既有生产回归）**：v4-pro 是推理模型，max_tokens 被思维链吃满→content 空→系统性降级（生产 08-13 换 v4-pro 后 intent/understand 必现）。修：理解/generate 1024/2048→8192、chat→8192、brief→4096、intent 50→512；**reasoning_content 是思考不是答案，不回退当结果**（喂 JSON 解析器会污染）| `DeepSeekAiClient.java` | +5 测试（解析回归，锁定"content 空报空内容走重试"）+ E2E 真机验证 |

## 第二步 · 插件系统（等第一个真实第二用户或确认开放）

> 目标：完整门控（Account.plugins 载体 + 注入 + 前端 + 控制面）。第一步跑通后再铺开。

### adai-core

| # | 任务 | 涉及文件 | 验证 |
|:-:|:-----|:---------|:-----|
| T2.1 | **Account 加 `plugins` 字段**（`List<String>`，默认空）+ 序列化；seed adai = `["trading","project"]` | `Account` 记录 / `AccountFileRepository` | 测试：plugins 序列化 round-trip / 老账号无字段默认空 |
| T2.2 | **AccountController 支持改 plugins**：`UpdateAccountRequest` 加 `plugins`；GET 返回账号 plugins | `AccountController.java:82,129` | 测试：PATCH plugins 生效 / 空默认 |
| T2.3 | **PluginRegistry**（新增）：`KnowledgeSource.name()` / Contributor → 插件名 + owner 映射 | 新增 `kernel/plugin/PluginRegistry` | 测试：trading↔TradingKnowledgeSource 映射 |
| T2.4 | **ContextEngine 全量门控**：按用户 `enabledPlugins` 过滤 KnowledgeSource/Contributor；`detectDomainScene(record, enabledPlugins)` 只在启用插件间判定，无插件用户一律 life（D5） | `ContextEngine.compose` / `detectDomainScene` | 测试：无插件用户含"股票"词 → domain=life 且无知识注入 |
| T2.5 | **新端点 `GET /api/v1/me/plugins`**：返回当前用户启用插件（前端门控用）| 新 Controller 方法 / WebConfig | 测试：adai=2 插件 / 新用户=空 |
| T2.6 | **Feed 行情卡门控**：`type=market` 只注入启用 trading 插件用户（D5 连带）| `FeedAppService` | 测试：无插件用户 Feed 无 market 卡 |
| T2.7 | **多用户插件隔离测试**（§九 验收）：新用户无知识注入 / 启用后有 / 数据不串 / promote 仅作者 / Feed 无行情卡 | 后端测试 | `gradlew test` 全绿 |
| T2.8 | **api-spec 同步**：`/me/plugins`、Account.plugins、domain 判定规则更新 | `docs/architecture/api-spec.md` | 契约一致 |

### adai-app / adai-web

| # | 任务 | 涉及文件 | 验证 |
|:-:|:-----|:---------|:-----|
| T2.9 | **模块门控**：调 `GET /me/plugins`，交易页/项目仪表盘按列表显隐；基础 7 项常驻 | 双端导航 / 壳层 / ApiService | widget 测试：无插件不渲染交易入口 |

### adai-admin

| # | 任务 | 涉及文件 | 验证 |
|:-:|:-----|:---------|:-----|
| T2.10 | **账号管理加插件开关**：编辑表单加 trading/project 开关（调 PATCH），展示当前 plugins | 账号管理页 / store | widget 测试 / 手测 |

## 验证与连调

**自测标准**
- adai-core：`./gradlew test` 全绿（含 T2.7 隔离测试）
- adai-app / adai-web / adai-admin：`flutter analyze` 0 error + `flutter test` 全绿

**前后端连调清单（真实多用户 E2E）**
1. `alice`（无插件）：首屏选号进入 → 只有基础 7 项，无交易页/项目仪表盘；记录含"股票"词 → 卡片 domain=life，AI 上下文无 os/ 知识；Feed 无行情卡；待办可正常建（记录转待办生效）
2. adai（有 trading/project）：全功能不变——交易页、项目仪表盘、行情卡、知识注入照旧
3. admin 给 alice 开 trading 插件 → 刷新后 alice 出现交易页 + 行情卡 + 交易知识注入；她的持仓/复盘只属于她
4. promote 反哺：仅 adai 可用（非作者 403/无入口）

## 依赖关系

```
T1.1 → T1.2（同批）
T1.3 → 第一步完成
─────── 里程碑：最小闭环拨正 ───────
T2.1 → T2.2 → T2.4（T2.4 依赖 plugins 载体）
T2.3 → T2.4
T2.5 → T2.9（前端门控依赖 /me/plugins）
T2.6 独立
T2.10 依赖 T2.2（PATCH plugins）
T2.7 / T2.8 收尾
```

> 第一步跑通后，第二步是否立即铺开由 adai 确认（等真实第二用户或确认开放）。
