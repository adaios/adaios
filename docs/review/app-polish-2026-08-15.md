# app 端功能打磨评估（以 app 为焦点，三端职责边界）

> 日期：2026-08-15
> 范围：apps/adai-app 全部 12 页面 + 支撑组件；对照后端 52 端点分布 + adai-admin/adai-web 职责
> 方法：代码级调研（3 路并行）+ 关键发现实测验证
> 评估标尺：VISION（个人 AI OS）+ DESIGN.md（记录今天/理解过去/帮助未来；一个页面；不要用户分类）

---

## 一、app 定位（一句话）

**app = 用户的手机端入口：今天（Feed 对话）+ 我的（功能导航）。** 核心价值是「记录今天」——所有功能都应服务「记录→理解→帮助」闭环，而不是功能陈列柜。

## 二、三端职责边界（app 为焦点）

| 端 | 定位 | 职责 |
|:---|:-----|:-----|
| **adai-app（移动端）** | 个人日常使用 | 记录/对话/查看（Kernel 基础 + Domain 插件消费）|
| **adai-web（桌面端）** | 深度工作形态 | 同一产品桌面呈现（两栏壳 + 8 模块，非适配）|
| **adai-admin（管理端）** | 系统治理 | 账号/数据/系统/知识管理（纯治理，不该有个人内容编辑）|

**边界原则**：
1. 个人数据（档案/记忆/任务/记录）编辑权属用户（app/web）；admin 只读 + 治理操作
2. 账号 CRUD、数据清理、系统配置、知识库治理 → admin
3. Domain 插件（project/trading）在 app/web 消费，admin 不参与业务闭环
4. app 与 web 共用同一产品端点集（40 个），无形态差异——**这是正确设计**（不是重复）

## 三、app 逐功能评估（12 页面）

### A. 核心（必须打磨好）

| 功能 | 必要性 | 目的 | 交互现状 | 问题 | 方案 |
|:-----|:------:|:-----|:---------|:-----|:-----|
| **Feed 主页面**（main_page）| ★★★ 命脉 | 记录今天：统一卡片流 + 一次输入一个事件 | 卡片四态状态机 + 对话 + 图片 + 标签 | 无重大问题（W1/W2 已修）| 保持，继续打磨交互细节 |
| **输入栏**（input_bar）| ★★★ 命脉 | 文字 + 多图（≤3）+ 附件 | 完整；语音 stub 已移除 | 附件菜单只有「图片」真实可用，**文件/链接是"未实现占位"**（`input_bar.dart:297`，点击仅提示）| 要么实现文件/链接上传，要么移除占位入口（不误导）|

### B. 功能导航（launcher 世界 B）

| 功能 | 必要性 | 目的 | 问题 | 方案 |
|:-----|:------:|:-----|:-----|:-----|
| 切换账号 | ★★★ | 多账号 | 正常 | 保持 |
| 关于我（profile）| ★★ | 个人档案查看/编辑 | 与 Feed TopBar 人像图标**双入口**（重叠）| 保留 launcher 入口，Feed TopBar 人像保留（快速可达）——二选一，避免三处 |
| 记忆 | ★★★ | 理解过去 | **actionable 待办在记忆页只读**（Feed 可完成，闭环断裂 P-app-03）| 记忆页待办加「完成」操作，或引导回 Feed |
| 时间线 | ★★★ | 理解过去 | **三处入口**（Feed 日期 + launcher + timeline 全页）| 统一为 Feed 日期 → BottomSheet（DESIGN 本意），launcher 保留全页版 |
| 搜索 | ★★ | 全文检索 | 正常 | 保持 |
| 标签宇宙 | ★★ | 浏览标签 | launcher 内嵌，标签点击进搜索 | 保持 |
| 阿呆系统（project_status）| ★ | 展示性仪表盘 | **暴露后端架构（Kernel 组件/RFC 状态）给普通用户——第三视角** | ⚠️ 降级为「项目概览」或移入 admin/web，app 用户不需要看 RFC 状态 |
| 任务（project_task）| ★★★ | 个人待办（Kernel 基础）| CRUD 完整 | **P-app-08 P0：编辑实为新建**（`_editTask` 不记 id 恒 POST）| 记录 `_editingTaskId`，保存时 PUT |
| 交易（trading）| ★★ 插件 | 持仓/记录/复盘 | 完整 | 保持；复盘反哺在 app 触发合理 |
| 生活快速记录（life_quick_entry）| ★ 插件 | 预设模板速记 | **死代码：lib/ 内 0 引用** | 删除或接入（若保留，挂到输入栏 ⊕）|

### C. 账号

| 功能 | 必要性 | 目的 | 问题 | 方案 |
|:-----|:------:|:-----|:-----|:-----|
| 首屏选号（account_select）| ★★★ | 多账号 | 正常 | 保持 |

## 四、跨页面问题清单（优先级排序）

| # | 级别 | 问题 | 证据 | 方案 |
|:-:|:----:|:-----|:-----|:-----|
| P-app-08 | **P0** | 任务「编辑」实为新建：`_editTask` 只预填表单不记 task.id，保存恒 `createTask` → 原任务保留 + 重复任务 | `project_task_page.dart:485-497` | 加 `_editingTaskId`，保存时 PUT |
| P-app-01 | P1 | 生活快速记录是死代码（0 引用）但 UI_REFERENCE 仍文档化 | `life_quick_entry.dart` | 删除或接入输入栏 ⊕ |
| P-app-02 | P1 | Feed 标签筛选 `filterTag` 全链路有代码但**无入口设置它**（只有清除）| main.dart/main_page | 接入（标签宇宙点击带筛选）或删除死链 |
| P-app-03 | P1 | 待办记忆闭环断裂：Feed action 卡可「完成」，记忆页只读 | memory_page.dart | 记忆页待办加完成操作 |
| P-app-04 | P2 | 时间线三处入口 + 全页/Modal 两形态 | timeline 三处 | 统一入口策略 |
| P-app-05 | P2 | 阿呆系统页暴露后端架构（Kernel/RFC）给用户——第三视角 | project_status_page | 降级或移 admin |
| P-app-06 | P2 | 信息重复：任务统计（状态页 vs 任务页）、身份（launcher vs profile）| 两页 | 收敛单一入口 |
| P-app-07 | P2 | profile 与 launcher「关于我」双入口 | main_page + launcher | 统一 |
| P-app-09 | P2 | 附件菜单「文件/链接」是未实现占位（点击仅提示「未实现」）| `input_bar.dart:297,333` | 实现或移除入口 |

## 五、职责边界发现（后端视角，影响 app 打磨）

| # | 级别 | 发现 | 建议 |
|:-:|:----:|:-----|:-----|
| P-be-01 | **P0 安全** | admin 的 6 个维护端点（records/retry、memory/rebuild、memory/{id} PATCH、cards/cleanup、trading/has-activity、trading/knowledge/conflicts）**不在管理鉴权路径下**，任何伪造 X-User-Id 可操作任意用户 | 移到 /admin/** 或加管理鉴权 |
| P-be-02 | P1 | admin 复用 POST /records + DELETE /records 做数据管理，无管理鉴权 | 同上 |
| P-be-03 | P1 | admin 混入个人内容编辑（档案 PUT /identity、记忆 PATCH /memory、任务增删）——admin 应有治理视角而非编辑个人数据 | admin 收敛为只读 + 治理操作 |
| P-be-04 | P3 | 3 个死端点：admin/ai-logs（**已实现但 admin 无「AI 日志」页面，未接线**）、cards/migrate（历史迁移已废弃）、memory/record/{id} | ai-logs 接线到 admin；其余删除 |
| P-be-05 | ✓ | app 未越界：仅调 accounts/available + me/plugins，未调账号创建/删除/数据清理/系统配置 | 确认健康 |
| P-be-06 | P2 | 鉴权总前提：全部 per-user 端点仅靠 X-User-Id header 隔离（无身份认证），admin 维护端点亦不在令牌保护内 | 随 P-be-01 一并治理（管理端点入 /admin/** 或加管理鉴权）|

### admin 职责错位细化（P-role 系列，与 P-be 互补）

| # | 级别 | 发现 | 建议 |
|:-:|:----:|:-----|:-----|
| P-role-01 | P1 | 档案编辑（PUT /identity）admin + web 重复 | 只属用户端，admin 移除 |
| P-role-02 | P2 | 记忆手动修正（PATCH /memory/{id}）admin 独有 | 应属用户端——web/app 补上「修正记忆」能力，admin 移除 |
| P-role-03 | P1 | 记录删除 admin + web 重复 | 用户端已有，admin 应只读 |
| P-role-04 | P1 | 任务 CRUD admin + web 重复 | 只属用户端，admin 移除 |
| P-role-08 | P2 | 复盘生成+反哺三端重复 | 生成属用户业务；入库后治理属 admin |
| P-role-09 | P1 | web/app 直接 promote 写 os/99-inbox 缺治理环节 | promote 后需 admin 确认入库（治理环节）|
| P-role-10 | P2 | admin api_service 保留 POST /records 无调用（休眠越权面）| 删除死代码 |
| P-role-11 | P2 | admin 顶栏可切任意 userId 操作其个人数据 | 单 owner 成立；多用户化时是隐私越权面，需角色约束 |

**边界原则（定稿）**：个人数据写只属用户端（app/web）；系统级写（账号/插件门控/重建/清理/知识正式入库）只属 admin；读同一数据源不算重复，同样的写操作多端实现才算。

## 六、以 app 为焦点的打磨优先级（建议执行顺序）

**第一批（P0，必须修）**：
1. `P-app-08` 任务编辑 bug（记 id → PUT）
2. `P-be-01` 维护端点鉴权（安全底线）

**第二批（P1，体验）**：
3. `P-app-01` life_quick_entry 删除或接入
4. `P-app-02` filterTag 接入标签宇宙（标签点击 → Feed 带筛选，正是「标签宇宙」的本意）
5. `P-app-03` 记忆页待办完成操作

**第三批（P2，职责清晰化）**：
6. `P-app-05` 阿呆系统页降级/迁移（去掉 RFC/Kernel 内部结构）
7. `P-app-04/06/07` 入口收敛（时间线/身份/统计单一入口）
8. `P-be-03/04` admin 收敛 + 死端点清理

## 七、待用户拍板

1. **阿呆系统页**（project_status）：降级为「项目概览」（去掉 Kernel/RFC 内部结构）？还是整个移入 admin？
2. **生活快速记录**：删除（输入栏 ⊕ 已有多图/附件）还是接入？
3. **时间线**：Feed 日期 → BottomSheet（DESIGN 本意）还是保留 launcher 全页？
4. **admin 边界**：个人内容编辑（档案/记忆/任务）是否从 admin 移除（收敛为纯治理）？
