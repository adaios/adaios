# AdaiOS 系统架构

Version: 0.2

---

## 一、架构总览

AdaiOS 不是 App → Service → Database。

而是：

```
Human
  │
  ▼ 输入 (App / Voice / Web)
Record / File First
  │
  ▼
Kernel
  ├── Identity      个人档案
  ├── Record        最小事件单元
  ├── Timeline      Record 时间序列投影
  ├── Context       上下文引擎（★ 核心）
  ├── Memory        长期记忆
  └── Knowledge     结构化知识
  │
  ▼
Domain OS
  ├── Trading OS（含研究）
  ├── Life OS
  └── Project OS
  │
  ▼
AI Model (Infrastructure)
```

## 二、核心概念

### 数据流（MVP 闭环）

```
用户输入 → Record(文件) → Context Engine(组合) → AI(理解) → Memory/Knowledge(沉淀)
                         ↑
                    Identity
                    Timeline
```

### File First 数据流向

```
文件（Source of Truth）
  │
  ├──→ Git 版本管理
  ├──→ AI 直接读取
  ├──→ 人直接查看
  │
  └──→ 数据库（索引/查询/缓存）
         │
         └──→ App 查询
```

## 三、adai-core 内部架构

```
com.adaiadai.core/
│
├── kernel/                    操作系统内核
│   ├── identity/              个人档案
│   ├── record/                最小事件单元
│   ├── timeline/              Record 投影
│   ├── context/               上下文引擎
│   │   ├── prompt/            Prompt 规则
│   │   ├── token/             Token 管理
│   │   └── policy/            策略管理
│   ├── memory/                长期记忆
│   └── knowledge/             结构化知识
│
├── domain/                     ★ Domain OS — 领域能力层
│   ├── trading/               Trading OS（含研究）
│   ├── life/                  Life OS
│   └── project/               Project OS
│
├── application/               用例编排层
│
├── interfaces/                入站适配层
│
└── infrastructure/            出站适配层
    ├── database/              数据库
    ├── storage/               文件存储
    ├── search/                搜索
    └── ai/                    AI 模型
        ├── llm/               LLM 客户端
        ├── router/            模型路由
        └── provider/          供应商适配
```

### 依赖规则

- `interfaces → application → {kernel, domain} ← infrastructure`
- kernel 内部：Record → Timeline → Context → Memory/Knowledge（流水线方向）
- domain 之间不直接依赖
- infrastructure 通过接口反转依赖

## 四、两类 Domain

### Kernel Domain（系统域）

所有 Domain OS 共享的基础能力：

| Domain | 职责 | 存储 |
|--------|------|------|
| identity | 个人档案（静态偏好、AI 协作规则） | File First |
| record | 最小个人事件单元 | File First |
| timeline | Record 时间序列投影 | 自动生成 |
| context | 上下文引擎（★） | 动态组合 |
| memory | 跨会话长期记忆 | File + DB 索引 |
| knowledge | 结构化知识资产 | File + DB 索引 |

### Domain OS（业务域）

挂载在 Kernel 上的领域能力：

| Domain | 职责 |
|--------|------|
| trading | 金融交易（含研究） |
| life | 个人生活管理 |
| project | 项目管理 |

## 五、Context Engine

Context 是 AdaiOS Kernel 的核心能力，不是 AI 辅助模块。

### 职责

- 收集用户相关信息（Identity + Timeline + Memory + Knowledge）
- 组合上下文 Package
- 构建 AI 输入环境
- 管理项目上下文
- 管理领域上下文

### 示例

用户询问"我的交易问题是什么？"

Context Engine 组合：
```
Identity（风险偏好、投资风格）
  + Timeline（最近交易记录）
  + Memory（历史复盘记忆）
  + Knowledge（交易规则、策略）
  = Trading Context Package → AI
```

## 六、当前阶段

### 目标

建立 AdaiOS 最小可运行内核。

### MVP 第一个闭环

用户输入 → Record(文件) → Context(理解) → AI(生成摘要/标签) → Memory(沉淀)

### 技术约束

- Java 17 + Spring Boot 3 + Modular Monolith
- 不提前微服务化
- 模块边界优先
- 数据资产优先
