# 全维度走查：AI 工程层自伤自查（8 官）

> 走查日期：2026-08-15（AI 工程层建成当日）
> 走查对象：`ai-engineering/` 层自身（自伤自查——用这套体系审查这套体系本身）
> 方式：8 审查官独立并行（产品架构/交互体验/界面/后端/前端/文档/知识数据/Context）+ 交叉印证 ⭐
> 范围：ai-engineering/** + AGENTS.md + RFC 20260815-ai-engineering-layer + 仓库外 ai-engineering-method/
> 结果：**P0 无。战略×7 + P1×14 + P2×26 + P3×24**（合并同族后）。**交叉印证 13 主题 ⭐ + 单官 7**。
> 守护：META-GUARD PASS（45 文件）；G 系列 7 PASS / 0 HIT。

---

## 一、核心结论

AI 工程层**方向正确、骨架已成**：三层结构（assets/workflow/state）+ 8 官 9 清单 + process 三件套 + guard-meta 全部真实落地且自检 PASS。但**建成当日即暴露两类自伤**：

1. **物理迁移清理未闭环**（6 官 ⭐⭐⭐⭐⭐⭐）：docs/ai→ai-engineering 迁移只移动了文件，未做 D10 旧路径清零——13-16 处 docs/ai 活引用残留，其中 ship/audit/review 的门禁命令 `bash docs/ai/guard-meta.sh` 按文档执行必失败（exit 127）。**元治理门禁实际处于失效状态**，而 guard-meta M1 只校验 frontmatter 边、不查正文路径，所以一直 PASS（盲区）。
2. **闭环承诺名实不符**（3 官 ⭐⭐⭐）：「六段闭环」只具名 5 段，develop 段无定义文件、验收段无核验程序；RFC 验收标准 #1-#5 无人核验；ADR 首批 3/5-10 条。

---

## 二、交叉印证发现（⭐ ≥2 官独立命中，按命中数排序）

| # | 主题 | 命中官 | 优先级 |
|:-:|:-----|:-------|:------:|
| C1 | **docs/ai 迁移残留未清零**（process 门禁命令断链：ship.md:46-47/audit.md:30/review.md:34,38 + frontmatter-spec §四/§六 + _index.md 标题 + docs-reviewer）| 6 官 | 战略/P1 |
| C2 | **guard-meta 只校验 frontmatter 边，正文路径引用无机器防线**（M1 盲区）——迁移残留因此全绿 PASS | 6 官 | 战略/P1 |
| C3 | **ship.md 契约同步指向不存在的 `docs/architecture/feature-reference.md`**（实际在 docs/reference/）| 3 官 | P1 |
| C4 | **六段闭环名实不符**：develop 无文件、验收无程序、「六段」只具名 5 段 | 3 官 | 战略/P2 |
| C5 | **ADR 覆盖不足**：首批 3/5-10 条，28 篇 RFC 历史决策大多无索引 | 3 官 | P2 |
| C6 | **workflow/ vs process/ 目录边界未定义**：review/audit/ship 实际在 process/，与 RFC 目标结构（全在 workflow/）偏离且无 ADR 记录 | 3 官 | P2 |
| C7 | **conventions.md 单一事实源不实**：代码规范在根 + adai-core CLAUDE.md 仍全文副本，无指针 | 2 官 | P1/战略 |
| C8 | **接入路由缺 assets/state**：README 五步不要求动工前查边界/坑/现状 | 2 官 | 战略 |
| C9 | **pitfalls 无已修/未修状态 + 无「何时读」触发点**（全层 10 处引用全是写入方向）| 2 官 | 战略 |
| C10 | **AGENTS.md 7 名字称 8 官**（漏 Context；首轮走查实跑 7 官）| 2 官 | P3 |
| C11 | **state/ 层空壳**：无本层自身验收状态投影，回答不了「本层做到哪了」| 2 官 | P3 |
| C12 | **task-log 走查区无状态列** + 残留 docs/ai 路径（W-P3-22/23）| 2 官 | P2 |
| C13 | **RFC 索引状态漂移**：ai-engineering-layer 在 rfc/_index.md 标 draft，文件实为 approved | 2 官 | P2 |

## 三、单官发现（未交叉，但均真实）

| # | 主题 | 官 | 优先级 |
|:-:|:-----|:---|:------:|
| S1 | REVIEW.md P1-W16 已修/未修状态自相矛盾（摘要标全出表，P1 表仍列未修）| Context | P2 |
| S2 | 跨仓库边界未操作化：method/research 只读性未声明、无「哪个先读」| Context | P2 |
| S3 | 第一原则「无第三视角」承载集中于 product 一官，ux/ui/frontend 清单无对应项，light 模式无人检查 | 产品架构 | P2 |
| S4 | RFC frontmatter 规范未文档化：design.md 用 approved/decided-by/revised，frontmatter-spec 枚举是 draft/active/superseded/archived | Context | P3 |
| S5 | REVIEW 计数自相矛盾（P2 摘要 11 vs 14 vs 15）| 文档 | P2 |
| S6 | G 系列脚本（docs/review/guard.sh）位置无说明，README 目录表找不到执行器 | 产品架构 | P3 |
| S7 | 外部目录（method）引用无只读边界（B8 外向动作/B9 外部目录只读）| Context | P2 |

## 四、P0（数据丢失）

无。

## 五、战略（合并同族后 7 项）

| # | 问题 | ⭐ |
|:-:|:-----|:--:|
| S-A1 | **迁移清理未闭环**：docs/ai 旧路径残留 13-16 处，ship 门禁命令按文档执行失败 | ⭐⭐ |
| S-A2 | **guard-meta M1 盲区**：不校验正文路径引用，迁移残留/正文断链永久 PASS | ⭐⭐ |
| S-A3 | **六段闭环名实不符**：develop 无文件、验收无核验程序、「六段」只具名 5 段 | ⭐ |
| S-A4 | **接入路由缺资产上下文**：动工前不查边界/坑/现状，B1-B7 全靠碰运气加载 | ⭐ |
| S-A5 | **pitfalls 状态失真**：活问题与已修复历史混排、无触发点 | ⭐ |
| S-A6 | **conventions 单一事实源不实**：代码规范三处副本已漂移 | ⭐ |
| S-A7 | **RFC 验收标准无人核验**：本层 RFC #1-#5 无任何流程要求核验 | — |

## 六、P1（合并后 14 项）

| # | 问题 | 位置 | ⭐ |
|:-:|:-----|:-----|:--:|
| P1-A1 | ship.md 门禁命令 `bash docs/ai/guard-meta.sh` 断链 | ship.md:46-47 | ⭐⭐⭐ |
| P1-A2 | audit/review 守护命令同断链 | audit.md:30 / review.md:34,38 | ⭐⭐⭐ |
| P1-A3 | frontmatter-spec §四/§六 强制范围仍写 docs/ai/** | frontmatter-spec.md:49,59 | ⭐⭐ |
| P1-A4 | ship.md 契约同步指向不存在的 feature-reference.md | ship.md:35 | ⭐⭐⭐ |
| P1-A5 | conventions.md 声称单一事实源但 CLAUDE.md 全文副本 | conventions.md:18 | ⭐ |
| P1-A6 | docs/README.md:90 链接 ai/checklists/ 断链（文字却写对）| docs/README.md:90 | ⭐ |
| P1-A7 | review.md 派官表 `ai/**、docs/ai/**` 残留 | review.md:52 | ⭐ |
| P1-A8 | docs-reviewer 强制范围仍 docs/ai/**（D 引用编号错引 D28）| docs-reviewer.md:24 | ⭐ |
| P1-A9 | REVIEW.md P1-W16 未修/已修状态矛盾（13 项仍列未修）| REVIEW.md:101-120 | — |
| P1-A10 | pitfalls 无状态列，B14/F10/F11 活问题与已修历史混排 | pitfalls.md:30 | ⭐ |
| P1-A11 | B3 边界来源张冠李戴（#127 实为鉴权非隐私）| boundaries.md | — |
| P1-A12 | B19 引用把未修 #179 写成已修 #127 | boundaries.md | — |
| P1-A13 | task-log W-P3-22/23 死链（docs/ai/roles/* 已删）+ 无状态列 | task-log.md:415-416 | ⭐ |
| P1-A14 | 走查存档 audits/2026-08-15.md 自身 frontmatter 断链（depends-on ai/process/audit.md）+ lines 120 vs 98 | audits/2026-08-15.md | — |

## 七、P2 / P3

- **P2（26 项）**：workflow/process 边界未定义、ADR 3/5-10、state 层空壳、RFC 索引 draft 漂移、六段计数矛盾、README 接入 step2 找「开发」文档扑空、ideas 登记双源、RFC §4.1 结构 vs 实现偏差、G 系列位置无说明、REVIEW 计数矛盾、task-log 状态列、外部目录只读边界、第一原则承载集中、guard-meta 范围外文档（audits/rfc）静默漂移、RFC frontmatter 规范未文档化、method 引用无只读声明、ADR 无边链接、review-context C7 只覆盖 ai-context-research 未覆盖 method、guard-meta.sh 不在 _index 清单、ADR 模板未物化、RFC 索引词汇混用、feature-reference 路径、change-log 步骤缺失等。
- **P3（24 项）**：AGENTS.md 7 名称 8 官、state 指针表假保证、task-log 无状态列、README 目录表 workflow 行歧义、conventions description 残留 docs/ai、docs-reviewer D 编号错引、RFC 验收偏差未回写、六段图物理路径未标注、README 三层表未含 process、method 目录 stub、ideas/README 双源等。

> P2/P3 完整逐条见各官结果（本次走查存档内联，后续迁移 task-log）。

## 八、检查点沉淀建议（已并入 checklists 候选）

| 新检查点 | 来源 |
|:---------|:-----|
| **M4 正文路径引用可解析**：grep 流程/规范/角色文件正文中的仓库内路径断言存在（docs/ai、feature-reference 类）——堵 M1 盲区 | 6 官一致 |
| **迁移完整性三件套**：目录迁移 = 文件移动 + 全库旧路径 grep 清零 + 索引同步，三步全过才算完成（D10 机械化）| 4 官 |
| **RFC 验收标准核验**：approved RFC 验收标准必须在 ship 时逐条 PASS/FAIL 留痕 | 产品架构/Context |
| **状态真相源三方对拍**：REVIEW 未修项 vs 摘要 vs change-log 出表记录三方一致 | Context/文档 |
| **触发点存在性**：资产类文件（pitfalls）必须在其适用流程文件写明「何时读」，README 罗列不算 | Context/交互 |
| **外部引用边界**：引用仓库外兄弟目录必须声明只读 + 验证路径有效 | Context |
| **工作流段完整性**：宣称 N 段闭环必须与具名段/定义文件一一对应 | 产品架构 |
| **审查官计数四对拍**：roles 文件数 ↔ AGENTS 名单 ↔ README N 官 ↔ audit 路由表 | 产品架构/Context |
| **规范单一事实源对拍**：conventions 声称单一的条目在 CLAUDE.md 只允许指针 | 产品架构/知识 |
| **RFC 索引状态对拍**：rfc/_index.md 每行状态 = RFC frontmatter status | 文档 |
| **契约同步目标存在性**：ship 引用契约文件路径 grep 断言存在 | 文档 |

## 九、执行成本

| 日期 | 模式 | 派官 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|:-----|
| 2026-08-15 | 自伤自查（ai-engineering 层）| 8 官全量 | 8 | — | 战略×7 + P1×14 + P2×26 + P3×24 | 0（审核不直接修）|
