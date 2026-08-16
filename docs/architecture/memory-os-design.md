# Memory OS Design Specification

版本：v0.1

状态：Draft

目标：
定义 ADAI AI OS 中 Memory OS 的职责、数据模型、运行流程，以及与 Domain OS、Context Engine 的关系。

---

# 1. 文档目的

Memory OS 是 AI OS 的核心基础设施。

它解决的问题：

> 如何让 AI 从一次性对话，进化为长期理解用户的个人 AI。

传统 AI：

```
用户输入
    |
    |
AI 回复
    |
    |
结束
```

Memory OS：

```
用户输入

↓

事件 Event

↓

理解 Understanding

↓

Memory 提炼

↓

长期沉淀

↓

未来 Context 调用

↓

AI 个性化响应

```

核心目标：

让 AI 不只是知道：

"用户说过什么"

而是知道：

"用户是什么样的人"

---

# 2. 核心架构定位

ADAI OS 包含：

```
                    ADAI OS


                       |
                       |

              Context Engine


                       |

        --------------------------------

        |                              |

    Memory OS                    Domain OS


                                       |

              -----------------------------------

              |              |                 |

          Trading OS      Project OS       Life OS


```

---

# 3. Memory OS 与 Domain OS 的区别

这是整个系统最重要的边界。


## Memory OS

关注：

> 人


负责保存：

- 用户身份
- 用户偏好
- 用户行为模式
- 用户长期目标
- 用户经历
- 用户决策习惯


例如：

```
用户容易在上涨行情追涨。

用户喜欢系统化思考。

用户倾向先建立框架再执行。

```

这些属于：

Memory。


---

## Domain OS

关注：

> 事情


负责管理：

- 领域数据
- 领域规则
- 领域流程
- 领域知识


例如：

Trading OS:

```
股票行情
持仓
交易规则
买卖流程
复盘流程

```


Project OS:

```
项目
任务
代码
架构
发布流程

```


Life OS:

```
生活记录
计划
习惯
情绪
健康数据

```


---

# 4. 核心关系


一个用户输入可能同时影响：

```
                 User Input


                      |

                    Event


        --------------------------------


        |                              |


    Domain OS                    Memory OS


    处理事情                       理解用户


```

例如：

用户：

```
最近 AI 项目让我很兴奋，
但是总感觉执行速度跟不上。

```


产生：

## Project OS

记录：

```
项目状态：
AI Native 项目推进

```


## Memory OS

沉淀：

```
Pattern:

用户容易产生大量架构想法，
需要帮助拆解执行路径。

```

---

# 5. Memory OS 数据类型


第一阶段定义：


```
memory/


├── identity

├── preference

├── behavior

├── pattern

├── experience

├── decision

└── goal


```


---

# 5.1 Identity Memory

用户是谁。


示例：

```yaml
type: identity

content:

用户是一名软件工程师。

长期关注：

- AI
- 软件架构
- 投资


```


特点：

长期稳定。

变化频率低。


---

# 5.2 Preference Memory

用户偏好。


示例：

```yaml
type: preference


content:

用户喜欢：

- 直接反馈
- 系统化分析
- 技术深度


```


作用：

影响 AI 表达方式。


---

# 5.3 Behavior Memory

用户行为。


示例：

```yaml
type: behavior


content:

用户交易过程中容易在上涨阶段产生追涨行为。


trigger:

快速上涨
热点新闻


```


作用：

帮助 AI 提醒。


---

# 5.4 Pattern Memory

长期规律。


示例：

```yaml
type: pattern


content:

用户面对复杂问题时，
倾向先建立完整体系，
容易延迟执行。


confidence:

0.85

```


Pattern 是 Memory OS 最重要资产。


---

# 5.5 Experience Memory

人生经历。


示例：

```yaml
type: experience


event:

第一次实践 AI Native 项目


time:

2026-07


learning:

发现上下文管理是 AI 软件工程关键能力。


```


---

# 5.6 Decision Memory

重要决策。


示例：

```yaml
type: decision


decision:

采用单体架构。


reason:

当前个人开发阶段，
优先降低复杂度。


status:

active


```


作用：

避免重复讨论历史决定。


---

# 5.7 Goal Memory

长期目标。


示例：

```yaml
type: goal


goal:

建立个人 AI OS。


period:

2026-2030


```


---

# 6. 每日输入处理流程


用户每天输入：


```
Text
Voice
Image
Record

```


进入：


```
Capture Layer


↓

Event Creation


↓

Intent Recognition


↓

Domain Classification


↓

Memory Extraction


↓

Memory Evaluation


↓

Memory Update


```


---

# 7. Event 与 Memory 的区别


Event:

发生了什么。


Memory:

说明这个人的什么特点。


例如：


Event:

```
今天卖出股票。

```


不是 Memory。


Memory:

```
用户过去多次在亏损扩大阶段选择提前退出。

```


才是 Memory。


---

# 8. Memory 生命周期


Memory 不应该立即永久保存。


流程：


```
Candidate Memory


        |

        |

    Evaluation


        |

 -----------------

 |               |

Temporary      Stable

Memory         Memory


```


---

# 9. Memory 评价机制


Memory:

```yaml

id:

type:

content:

confidence:

importance:

created_time:

updated_time:

source_events:

status:


```


评分因素：

```
Memory Score


=

出现频率

+

时间相关性

+

用户确认

+

长期影响


```

---

# 10. AI 回复过程


每一次 AI 回复：


```
User Query


↓

Intent Detection


↓

Context Engine


↓

Memory Retrieval


↓

Domain Retrieval


↓

Prompt Assembly


↓

AI Response


```


---

# 11. Memory 如何影响 AI 回复


示例：

用户：

```
我要不要加仓？

```


Context Engine:


加载：

Memory:

```
用户过去容易追涨。

```

Trading OS:

```
当前仓位
交易规则
风险指标

```


最终 AI：


```
结合你过去几次交易记录，
类似情况下容易出现追高风险。

建议先检查：

1. 买入逻辑
2. 当前仓位
3. 止损条件


```


---

# 12. Memory 不直接暴露


错误：


```
根据你的Memory，你容易追涨。


```


正确：


```
结合你过去几次类似交易复盘，
这里需要注意追高风险。


```


Memory 是 AI 内部认知。


---

# 13. Domain OS 接入方式


Domain OS 不直接修改 Memory。


关系：


```
Trading OS

      |

      |

 Event

      |

 Memory Engine


```


例如：


Trading OS 提供：

```
交易行为数据

```


Memory Engine 判断：

```
形成交易行为模式


```


---

# 14. Context Engine 职责


Context Engine 是连接层。


负责：


```
当前问题

+

相关 Memory

+

相关 Domain 数据

+

用户偏好


↓

Prompt Context


```


---

# 15. MVP 实现范围


第一阶段：

不实现：

- 自动 Agent 管理 Memory
- 知识图谱
- 向量数据库
- 复杂推理系统


实现：


```
MemoryService


功能：

1.
读取 Memory


2.
创建 Candidate Memory


3.
更新 Memory


4.
查询 Memory



```


---

# 16. 推荐目录结构


```
.ai/


├── memory/

│
├── identity/

├── preferences/

├── patterns/

├── experiences/

├── decisions/

│
├── os/
│   ├── trading-engine/
│   ├── project-os/
│   └── life-os/


```


---

# 17. 最终目标


Memory OS：

不是：

```
聊天记录数据库

```


而是：

```
个人长期认知系统


```


Domain OS：

不是：

```
功能模块集合

```


而是：

```
个人不同领域的运行系统


```


最终形成：


```
                    Personal AI OS


                         |

                  Memory + Context


                         |

       ------------------------------------


       |                 |                |


   Life OS          Project OS       Trading OS


```


核心闭环：


```
每日输入

↓

事件

↓

领域处理

↓

Memory沉淀

↓

Context召回

↓

AI反馈

↓

持续理解用户


```