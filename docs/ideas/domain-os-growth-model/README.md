# Domain OS 生长模型（草稿）

> **状态：未定型思考**（2026-08-07）。
> 本文件是对"Domain OS 如何从无到有、从小到大、以及如何复用/演变/压缩"的一次架构讨论沉淀。
> 具体形态尚未定稿，保留开放问题；成熟后升级为 `docs/rfc/` 并移出 ideas 区。

## 1. 背景与动机

仓库里已有三个 Domain OS，它们不是并列产物，而是**同一个生长律在不同时刻的标本**：

| OS | 阶段 | 证据 |
|:--|:--|:--|
| trading-os | **成熟态** | 完整管道（01-raw → 05-system → 11-context）+ 收敛循环（Phase A/B/C）+ 卫星目录 |
| project-os | **骨架+自举** | 管道未动，多了"外部数据源融合"（docs/rfc、git log、CLAUDE.md） |
| life-os | **种子** | 只有 definition/ + 11-context/identity.md，零结构 |

近期 health-os 的考虑（见 `docs/rfc/20260730-health-management-scenario.md`，该 RFC 结论为"健康不独立、属 Life Domain"）触发对通用问题的追问：

> Domain OS 到底怎么长出来？可复用的单元是什么？结构是否应当随数据演变？如何在变大后保持可用？

## 2. 核心命题

> **Domain OS 的生长律 = 通道协议 + 阶段机，结构是数据的影子。**
>
> 复用的单元不是目录骨架，而是四通道协议（形成/学习/投影/收敛）；
> 结构随数据驱动地展开，不预建空目录；
> 压缩（融合/投影/遗忘）是所有 OS 共有的一等操作。

三个诉求（可复用 / 可自行演变 / 可自行压缩）不是并列的加分项，而是**恰好对应三个生长转折**：

| 生长转折 | 需要的属性 | 仓库证据 |
|:--|:--|:--|
| 从无到有（0→1） | **可复用** | trading-os 完整跑通 → 管道协议被验证 |
| 从一到二、二到三（1→2→3） | **可自行演变** | life/project 克隆了目录却没克隆出数据 |
| 从小变大（small→large） | **可自行压缩** | trading-os 融合机制存在但未抽象为共有操作 |

## 3. 四通道协议

从 trading-os 抽出的运转模型——一个 OS 有四条通道，各自回答一个问题：

| 通道 | 链路 | 回答 | 类比 |
|:--|:--|:--|:--|
| 形成 | 01-raw → cleaned → glossary → rules → system | 知识如何被建出来 | 编译 |
| 学习 | 08-review + 07-manual | 系统如何被修正 | 反馈环 |
| 投影 | 11-context（identity/current/rules/mistakes…） | 系统如何被理解 | `/proc`（运行态） |
| 收敛 | 融合 + 校准 + 重建（Phase A/B/C） | 系统如何被压缩 | GC / 链接器 |

关键点：**AI 只消费投影通道（11-context），不消费形成通道**。11-context 不新增知识，只重组 05-system + 04-rules + 03-glossary——这就是"运行态压缩"。

## 4. 阶段机：结构是数据的影子

一个 OS 的生命周期分四阶段，结构只响应数据出现，不预建：

| 阶段 | 该有什么 | 不该有什么 |
|:--|:--|:--|
| **种子（Seed）** | definition/ + 11-context/identity.md（身份声明） | 任何空目录、任何管道目录 |
| **出生（Birth）** | 第一条真实数据进入，管道首次跑通 | 为"未来"建的结构 |
| **生长（Growth）** | 数据积累、系统扩张、卫星目录按需出现 | 复制来的无用目录 |
| **收敛（Converge）** | 融合 + 压缩 + 11-context 重建，成为被消费的运行态 | 膨胀而不收敛 |

反规则：**某目录长期为空 = 在预设结构而非响应数据 → 删掉或等真数据**。
（trading-os 的 12-research 是后来随市场生态认知需求出现的，不是一开始建的。）

收敛后仍可能再次生长（新数据 / 新现实），所以是**螺旋不是直线**——trading-os 的 Phase C 重建就是这个循环的显式操作。

## 5. 三种压缩

压缩不是一种，是三种；前两种 trading-os 已有，第三种尚未系统化：

```
内容压缩（融合）：海量 raw → 每课 glossary → current/glossary 1 份 → rules → system.md 1 份
投影压缩（蒸馏）：system.md → 11-context 5 文件（AI 只读这份）
遗忘压缩（淘汰）：时点实例剔除（框架保留、实例剔除）、归档到 archive/、空目录清理
```

"可自行压缩"要成为一等公民操作：所有 OS 共有的、按需触发的收敛动作（trading-os 的 Phase A/B/C 是其一个实现）。

## 6. 独立判据：OS 还是子模块

拆不拆的真正判据是**数据管道是否独立**，四个信号：

1. **独立的数据管道类型**（知识型文本管道 vs 结构化数据流）
2. **独立的外部数据源**（如行情 API、手表/手环 API）
3. **独立的运营节奏**（日/时级数据 vs 偶尔记录）
4. **独立的边界与安全**（隐私、异常预警等专属处理）

注意：**领域间高耦合不是不拆分的理由**——Context Engine 本就跨域组合（Trading + Life + Health 打进一个 Package），相关性和目录结构是两个维度。`20260730-health-management-scenario.md` 以"健康与生活高度相关"为由不拆分，结论可保留但理由应换成管道判据。

## 7. 应用到 health-os

现状：health 的四个信号全无——没有设备接入、没有数据流动，只有 life-os concepts.md 里的 HealthRecord 概念。

建议（草稿）：

- **现在**：不建独立 health-os。在 life-os 内播种：
  - `os/life-os/11-context/health.md` — 健康身份投影（基线/目标/规则/高频错误，映射 identity/current/rules/mistakes）
  - `os/life-os/definition/` — 把 HealthRecord 展开为 health 子模块的工作流
- **晋升触发条件写死**：外部数据源真实接入（手表 API）或持续高频健康记录出现时，才把种子迁出、独立成 health-os。
- **晋升 = 搬种子 + 写 CLAUDE.md**，不推翻任何东西，seed 成本接近零。

## 8. 开放问题（未定）

- 阶段机的**显式化程度**：要不要给每个 OS 一个 `definition/stage.md`（声明自己在哪一阶段）？还是由目录存在性隐式表达？
- "压缩操作"的**抽象方式**：收敛循环是否值得写成跨 OS 复用的脚本/提示词模板（如 trading-os 的 10-prompts），还是保持各 OS 自主？
- 独立判据的**阈值**：四个信号满足几个才晋升？谁来判断（用户 / AI / 数据出现自动触发）？
- 子模块（life-os 内 health/finance/relationship…）与 OS 的**边界**：life-os 自身会不会长成"一堆子模块的容器 OS"，这与"子模块晋升独立 OS"如何调和？
- 知识型 OS（trading）与数据型 OS（health）的**通道差异**：四通道协议是否需要按数据类型分化（文本管道 vs 数据管道）？

## 9. 相关文档

- `docs/rfc/20260730-health-management-scenario.md` — 健康场景设计（"不独立"结论待换判据）
- `os/trading-os/CLAUDE.md` — 四通道 + 收敛循环的成熟实现（生长律的实证来源）
- `os/life-os/`、`os/project-os/` — 种子 / 骨架标本
- `docs/VISION.md` §5.3、`docs/architecture/product-architecture.md` — Domain OS 层定位
