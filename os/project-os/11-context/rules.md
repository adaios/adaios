# AdaiOS 开发规则

> 从项目 AGENTS.md 提炼，每条规则标注来源。
> 服务 AI 理解"AdaiOS 项目怎么开发"。

---

## 架构规则

**R1 模块化单体是默认架构**
> 不提前微服务化。当 Domain OS 满足独立生命周期、独立数据边界、独立部署需求、多人维护四条时再拆分。
> 来源：AGENTS.md 开发规则

**R2 分层依赖规则**
> interfaces → application → domain/kernel ← infrastructure。禁止跨层调用。
> 来源：AGENTS.md 分层依赖规则

**R3 Domain 之间不允许直接依赖**
> 跨域协作通过 application 层编排。Trading OS 不直接调 Life OS。
> 来源：AGENTS.md 分层依赖规则

**R4 Kernel 六组件不可跳过**
> Identity → Record → Timeline → Context → Memory → Knowledge。任何新功能必须明确归属。
> 来源：AGENTS.md

---

## 数据规则

**R5 File First, Database Second**
> `os/` 和 `data/` 下的知识以文件为 Source of Truth。数据库为查询性能存在，能从文件重建。
> 来源：AGENTS.md 最高设计原则

**R6 不混合代码和知识**
> 代码仓库只放代码、配置、构建脚本。知识资产归 os/ 和 data/。
> 来源：AGENTS.md 开发规则

**R7 新能力先回答"属于哪个 Domain？"**
> 找不到归属时先讨论架构，不直接写代码。
> 来源：AGENTS.md 开发规则

---

## 开发流程规则

**R8 文档先行**
> 后端新 API → 先写 API Spec → 确认 → 写测试 → 实现。
> 前端新 UI → 先写 UI Flow → 确认 → 写 Flutter。
> 新功能 → 先写 RFC → 确认 → 写代码。
> Bug 修复和纯逻辑改动不需要文档。
> 来源：AGENTS.md 开发工作流

**R9 os/ 目录保持独立工作流**
> 每个 `os/*/` 项目有独立的 AGENTS.md 和目录规则。adai-core 只读不写。
> 来源：AGENTS.md 开发规则

**R10 前端三端兼容约束**
> Flutter Android/iOS/Web 三方兼容。引入新依赖前确认 platform support。
> 来源：Memory — flutter-cross-platform-compat

---

## 代码规则

**R11 Java 17 特性优先**
> 优先使用 Record、Sealed Class、Pattern Matching、Text Block。
> 来源：AGENTS.md 代码约定

**R12 禁止 @Autowired 字段注入**
> 统一 Constructor Injection。
> 来源：AGENTS.md 代码约定

**R13 所有 Java 代码在 com.adaiadai.core 下**
> 根包不变化，新增模块在根包下按层建包。
> 来源：AGENTS.md 开发规则

**R14 入口统一，后台分流**
> `POST /api/v1/records` 是所有输入唯一入口。IntentRecognizer 自动分流。
> 来源：AGENTS.md 开发规则

---

## AI 协作规则

**R15 不是 CRUD 应用**
> AdaiOS 是一套 Personal AI Operating System。开发功能时优先考虑：是否增强个人上下文？是否沉淀长期资产？是否帮助 AI 更理解用户？
> 来源：VISION.md §10

**R16 禁止自动写入 os/ 目录**
> adai-core 只读取 os/ 的知识产出，不反向写入。唯一例外：99-inbox/（入库候选）。
> 来源：AGENTS.md + VISION.md

**R17 不提前设计抽象**
> 有真实用例驱动时才定义接口，不凭空设计框架。
> 来源：VISION.md 不过度设计原则
