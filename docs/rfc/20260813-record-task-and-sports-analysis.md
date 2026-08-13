---
title: 记录↔任务关联 + 相机动作分析（想法升级 RFC）
date: 2026-08-13
status: implemented
idea: 20260812-record-task-association.md（R2）+ 20260812-camera-sports-analysis.md
---

# 记录↔任务关联 + 相机动作分析（想法升级 RFC）

> 来源：`docs/ideas/20260812-record-task-association.md`（R2，阿呆 08-12）+ `docs/ideas/20260812-camera-sports-analysis.md`（投篮动作分析）。
> 两个想法都是「**复用现有能力 + 加一层语义**」的增量，但都触到既有数据格式/流程。
> 本 RFC 聚焦结构影响分析——**改什么、动哪些契约、存量数据怎么办、前端受影响吗**。方向确认后实现。

---

## 一、记录↔任务关联（R2）

### 目标

`domain=project` 的记录自动生成任务进看板，形成「记录 → 任务 → 解决 → 反哺」闭环。
触发场景：项目 bug/需求反馈（如 08-12 的 #14/#15/#16 三条），此前只能人工誊抄进任务看板。

### 现状（代码事实）

- **记录**：`ContentRecord`（`kernel/record/ContentRecord.java`），`domain` 字段（life/trading/project）由 AI understanding 判定，默认 life；判定在 `RecordController.handleStatem`（`interfaces/RecordController.java:161` `understanding.domain()`），保存点在 `:204-211`；`PATCH /{id}/domain` 可手动改
- **任务**：`Task` 实体 + `ProjectTaskAppService.createTask()`（`application/ProjectTaskAppService.java:40`），落 `data/project/tasks/{yyyy}/{MM}.md`（`ProjectFileRepository`）
- **两条线零关联**：`ProjectKnowledgeSource` 只读 `os/project-os/11-context`，不消费任务也不消费记录

### 方案设计

**数据流**：
```
用户输入(项目 bug/建议) → handleStatem 保存记录(domain=project 已定)
  → best-effort 调 RecordToTaskLinker（失败不阻塞记录，同 memory persist 原则）
  → 查重（同 sourceRecordId 已有任务则跳过）
  → ProjectTaskAppService.createTask(sourceRecordId=rec_xxx, title=摘要, description=正文, tags=记录tags)
  → 任务进看板
```

**触发规则（定稿，方案 B：默认转 + AI 判挡 + 手动标签挡，阿呆 08-13 选型）**：

```
记录转任务 ⇔ domain=project AND intent=log AND 无排除标签 AND actionable=true
```

- `domain=project`：AI 领域判定（`understanding.domain()`，`RecordController.java:161`）
- `intent=log`：question 对话不转
- **排除标签（手动挡）**：记录含约定标签 `#备忘` / `#想法` → 不转（阿呆主动挡掉不想跟踪的）
- `actionable=true`（AI 挡）：复用 `understanding.actionable()`（记忆系统 Phase 3 已有判定）——陈述/状态类（进展不错）不转

**触发位置**：`RecordController.handleStatem` 保存后（`:211` 后）best-effort 调 `RecordToTaskLinker`（失败不阻塞记录，同 memory persist 原则）。

**记忆协调**：转任务时记录照常沉淀记忆，但**记忆清除 actionable 标记**——任务即跟踪载体，记忆只留事实回顾，避免双份待办。

### 结构影响分析（核心）

| 层 | 文件 | 改动 | 侵入度 |
|:--|:--|:--|:--:|
| 后端·领域 | `domain/project/Task.java` | record 加可空 `sourceRecordId` 字段（构造签名 +1 参）| 中 |
| 后端·存储 | `infrastructure/storage/ProjectFileRepository.java` | `ENTRY_PATTERN`（`:46`）加**可选 group** `sourceRecordId` + `formatTaskEntry` 输出 + `parseEntries` 解析 | 中 |
| 后端·应用 | 新 `RecordToTaskLinker`（application 层）| 查重 + 调 createTask，best-effort | 低 |
| 后端·接口 | `interfaces/RecordController.java` | `handleStatem` 保存后（`:211` 后）挂 linker 调用；注入 1 依赖 | 低 |
| 后端·契约 | `docs/architecture/data-format-freeze.md` §2.11 | 任务格式加 `sourceRecordId`（可空）说明 + 变更记录 | 低 |
| 前端·看板 | `apps/adai-app` / `apps/adai-web` task_page | **零改动**（Flutter fromJson 忽略未知键；看板照常渲染）| 无 |
| 测试 | `ProjectFileRepositoryTest` 9 个 | 加字段需补断言；新增 linker 查重测试 | 中 |

**关键兼容性设计**：
- **存量任务文件不用重写**——`sourceRecordId` 正则做**可选匹配**，旧任务缺该行 → 解析 `null`；新任务才带字段。向后兼容。
- **不回溯历史记录**——只对新记录触发，避免旧数据刷屏看板。
- **任务字段 vs 独立映射的取舍**：加字段让任务文件自包含（File First：一个任务文件 AI 可直接读全，来源可溯源），代价是任务格式契约变更；独立映射文件（recordId→taskId）零任务文件改动，但违背自包含、看板显示来源需额外查映射。**推荐加字段**（向后兼容，变更受控）。

### 待确认

1. ~~触发档位~~ → **已定稿（方案 B）**：默认转 + AI 判挡（actionable）+ 手动标签挡（#备忘/#想法）
2. `sourceRecordId` 存储：**任务字段**（recommend，向后兼容加字段）还是独立映射？
3. 记录删除时任务是否联动删除？（第一版建议**不做**，保持简单）
4. 排除标签用 `#备忘` / `#想法` 两个约定词（可配置化）？

---

## 二、相机动作分析

### 目标

拍照 → AI 分析投篮姿势等动作，指出问题（手肘外翻、发力点偏低…）+ 改进建议。
视频抽帧分析明确留 v2（无视频通道）。

### 现状（代码事实）

- L4 图片通道已完整：`POST /records/media` 上传 → `MediaRecordAppService.recordImage`（VLM 结构化理解）→ ContentRecord + Memory
- **追问通道已具备任意图问答**：`POST /records/media/{id}/ask` → `askImage`（`application/MediaRecordAppService.java:131`）→ `VisualAiClient.ask`（自然语言）→ 回答气泡持久化（#209 追加 card turns）
- **关键**：`askImage` 能回答任意图片问题——动作分析只是它的一类预设问题

### 两阶段方案

| 阶段 | 做法 | 结构影响 | 成本 |
|:--|:--|:--|:--:|
| **Phase 1（推荐先上）** | 图片卡加「分析动作」快捷按钮 → 发**预设分析问题**（"请分析这张照片中的投篮姿势，指出动作问题 + 改进建议"）→ **复用现有 askImage** → 回答气泡挂卡下 | **零后端改动**，纯前端图片卡加一个按钮 | 低 |
| **Phase 2（产品化）** | 上传时选「动作分析」模式 → 后端 `analyzeSportsAction()`（专用分析 prompt + 沉淀专用 type + 记忆 insight）| type 新增 + 徽标映射 | 中 |

**Phase 1 零风险依据**：走已存在的 `askImage` 通道，`ContentRecord` type 仍是 `image_qa`（契约已有），无新端点/新字段/新数据文件。前端图片卡（`feed_card.dart`）已有点击「提问」逻辑，加一个同构按钮即可。

**Phase 2 结构影响**：
| 层 | 改动 | 侵入度 |
|:--|:--|:--:|
| `ContentRecord` type 新增 `sports_analysis` | freeze §2.1 type 列表 + Feed/时间线徽标映射（13 类 + 未知兜底 #165 已覆盖旧端）| 低 |
| `MediaRecordAppService` 新方法 `analyzeSportsAction` | 复用 upload 字节流 + `visualAiClient.ask` 专用 prompt + 沉淀记录 | 低 |
| 入口 | 复用 media 上传 + mode 参数，或新端点 | 低 |
| ai-log 场景标记 | `source="media"` 已存在，零改动 | 无 |

### 待确认

1. **Phase 1 先上**（recommend，当天可体验、零后端风险），还是直接 Phase 2？
2. Phase 2 记录类型：独立 `sports_analysis`（时间线清晰）还是复用 `image_qa`（零契约变更）？

---

## 三、结构影响总览

| 方案 | 后端 | 契约(freeze) | 前端 | 存量数据 | 侵入度 |
|:--|:--|:--|:--|:--|:--:|
| R2（窄档+任务字段）| Task + ProjectFileRepository + 新 Linker + Controller 挂点 | §2.11 任务格式 | 零 | 向后兼容（可选正则）| **中** |
| A2 Phase 1（复用 ask）| **零** | 零 | 图片卡加按钮 | 无 | **极低** |
| A2 Phase 2（专用 type）| MediaRecordAppService 新方法 | §2.1 type 列表 | 上传入口 + 徽标 | 无 | **低** |

**推荐组合**：R2 窄档 + 任务字段（向后兼容）；A2 先 Phase 1（零风险体验），Phase 2 视体验再定。

**备选不选**：任务独立映射文件（违背 File First 自包含）；宽档触发（噪音）；R2 与 #171（项目记录聚合视图）合并做（聚合视图是前端呈现，R2 是数据闭环，可分开推进）。
