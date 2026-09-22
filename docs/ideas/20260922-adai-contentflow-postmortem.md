---
title: 积录（adai-contentflow）考古：阿呆的能力原型与五年对照
description: 2026-09-22 对已废弃老项目「积录」的只读考古——阿呆的哪些能力源自它、哪些是 AdaiOS 后来发明的、它留下的可回搬判断；含删除前的保留结论。想知道「阿呆从哪来/哪些老坑在重演」时读
version: 1
created: 2026-09-22
updated: 2026-09-22
status: active
lines: 82
depends-on: []
related:
  - _index.md
  - ../VISION.md
tags: [legacy, postmortem, kernel, learn]
---

# 积录（adai-contentflow）考古：阿呆的能力原型与五年对照

> **对象**：同级目录 `adai-contentflow`（产品名「积录」，包名 `com.adaiadai.contentflow`，早期代号 **Mx**）。Spring Boot 2.7 + Thymeleaf + JPA/MySQL 的 DDD 单体：363 个 Java 文件 / 1.93 万行 / 107 个模板 / 18M 静态资源。开发期约 2019-10 ~ 2025-04，2024-10-09 一次性入库（仅 2 个 commit）。
> **方式**：2026-09-22 只读考古（未修改任何文件），含一次全量走查。
> **状态**：**服务器与数据均已不存在，项目待删除。本文件是该项目的唯一留存。**

## 一、一句话结论

积录留下的不是代码，是**一份五年期的对照实验报告**——它当年缺的三样（回流、诚实、人格），恰好是 AdaiOS 今天最该守住的三样。

## 二、三条活下来的基因（真跑通的部分）

| 基因 | 位置 | 做法 | 今天对应 |
|:-----|:-----|:-----|:---------|
| **切面式无感观察** | `aspect/ReaderAspect.java:47-84` | `@Around` 拦博客详情，非博主访问即 counter+1 落 `Reading`；未登录记 `reader=null` | Kernel Memory 的采集 |
| **共现关联** | `service/sis/TagRelevanceService.java:34-63` | 取含目标标签的全部记录 → 按分隔符 split → 统计其他标签出现次数 → 降序 | **AdaiOS 尚缺的「标签→标签」一维** |
| **按标题自动分段** | `util/MarkdownUtil.java:23-55` + `application/ccp/BlogApplicationService.java:289-311` | 正则抓 H1/H2 → `TreeMap` 按索引保序 → 切出 `标题\|正文` → 逐段落 `Snippet` + `rank` | learn「一页一单元」 |

共现算法骨架（原为 20 行，可随手重写）：

```
for record in TagRelevance where record.tags contains X:
    for tag in split(record.tags, TAGS_SEPARATOR):
        if tag != X: counter[tag] += 1
return counter sorted by value desc
```

## 三、一条祖训：算不出来就说「未生成」

`constant/sis/ReadingConstant.java:53,68` 定义 `READER_LIKED_PERCENT_DEFAULT / READER_LIKED_PERCENT = "未生成"`；`service/sis/ReadingTranslator.java:35-42` 在样本不足时如实返回「未生成」，**不拿 0 冒充**。

> ⚠️ **AdaiOS 今天正在违反它**：S7（D3 自称「完美图匹配度」，实为阈值 + 硬编码映射）、P2-交易62（锚定日无文件日期时把「不可判定」说成「确定」）、P1-交易60（行情失败时用户只看到空值/旧值）。**建议：把这条口径写进审查清单。**

## 四、没有继承的四样（是 AdaiOS 发明的）

1. **人格**——全项目零「阿呆」、零拟人：文案是「管理标签/标记标签」，页脚 `Make Anythings(X)`，代号 Mx（`org.mxframework`、`mx_contentflow`、`MxException`、`mx-blog`）。从 Mx 到 adai、从工具口吻到「我和阿呆」，中间**没有技术必然性**——那是产品的分野，不是工程的分野。这也解释了「无第三视角」为何是第一原则：它不是文风偏好，是身份声明。
2. **智能**——全项目 grep 不到 AI/LLM。所谓「智能化」全靠正则 + 统计（正则靠 Regex Match Tracer 手工调）。
3. **File First**——老项目是数据库为真相源（`Blog` 复制一份 `content`；`VirtualFile` 把文件内容塞进字段且类上无 `@Entity`）。红线 B2 是对这条路线的一次否决。
4. **置信度与状态机**——`PersonTag` 只有布尔关联（无人标签的权重/次数/置信度，唯一统计是 `list.size()` 数人头）；卡片无复习流转、无复述、无 feedback。

## 五、六条判断（观点）

1. **它是阿呆的能力原型，不是前身。** 理念同源（README：把碎片化的知识「智能化，体系化，组织化，形成内容产品」），但代码零复用、数据零可迁——仓库里只有 `import.sql` 的 3 个用户 + 权限，没有任何内容/标签/画像种子。
2. **活下来的只有三条基因，其余是墓碑。** `HttpAspect`（切点指向**根本不存在的** `org.mxframework.*`，从未触发）· `Segment`（全项目零处 `new`）· `Markdown`（空类）· `TagOrderUtil`（恒返回 `"new"` 且零调用）· `VirtualFile`（无 `@Entity`）。363 个文件里约 74 处 `TODO`/`return null`。**一半是地基，一半是墓碑。**
3. **难的不是造能力，是接回流。** 采了喜恶、算了比率（`liked×100/(liked+disliked)`），却**不影响任何排序**（列表连排序参数都没有）。对照今天：P2-认知3（5 个认知端点前端零入口）、P2-认知2（画像统计建在 `sold.json`）是同一个病。**能力廉价，回路昂贵。**
4. **最狠的祖训是「未生成」**（见第三节）——它把「诚实」写成了常量。
5. **人格是决断，不是演进**（见第四节 1）。
6. **File First 有历史依据**，不只是洁癖（见第四节 3）。

## 六、删除前的保留结论

- **留**：本文件；上面三段骨架；README 开头那句愿景原文（「趁你还未忘记……积少成多，目之以录」）。
- **删**（删掉是收益）：`application-dev.yml` / `application-prod.yml` 的明文凭据、`import.sql` 的账号哈希、老机器绝对路径（`D:\Users\rotto\...`、`/root/adai-contentflow/`）。**凭据内容一律不落盘到本仓库。**
- **顺序**：① 先处理 GitHub 远程 `adai-projects/adai-contentflow`——凭据在其 commit `cdcb8cc` 的历史里，本地删干净而远程仍在等于没删（这段历史无协作价值，建议直接删库）；② 本地目录与 85M 的 `adai-contentflow.zip` 一并删（zip 不是备份，是第二份拷贝，同样含凭据）。
- **未决**：老凭据与今天生产账号密码同源。**服务器与库已不存在 ⇒ 老系统的风险归零，但同源字符串仍指向今天**。建议轮换一次生产密码彻底断链（动作未执行，由用户决定）。

## 七、如果要回搬，三个候选

1. **标签共现进 ContextEngine 召回**：现有 `TagIndexService.findRelatedIds`（标签→记录，`TagIndexService.java:138`，喂 `ContextEngine.java:269`）与 `TagRecommendationService`（频次 hot/cold，`:33-55`）之外，补「标签→标签」——用于扩大召回（同主题换说法的旧记录）与简报的「发现」（「这两件事你总是一起想」）。**前提核查**：`LearnCard.tags` 与 `ContentRecord.tags` 是否同一套索引。
2. **「未生成」口径进审查清单**（`ai-engineering/checklists/`）。
3. **guard 死意图扫描**：扫 `@Pointcut` 指向不存在的包、恒常量返回且零调用的工具方法（现有 guard 管「文档↔代码」对齐，不管代码里的死意图）。

## 关联

- 理念对照：`../VISION.md`
- 未修项对照：`../review/REVIEW.md`（S7 / P2-交易62 / P2-认知2 / P2-认知3）
- 现状真相源：`../reference/status.md`
