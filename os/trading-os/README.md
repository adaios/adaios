# adai-trading-os

**交易课程整理 · 交易系统提炼 · 行为复盘** — AI 知识工程项目

将零散的交易课程转录文本，逐步加工为结构化的术语库、规则库和最终交易系统。

---

## 三层架构

```
第一层：单课处理（每课独立跑完 Step 1→5 + Fusion）
              ↓
第二层：批量收敛（多课积累后融合术语、校准规则、整合系统）
              ↓
第三层：人工审核（07-manual 修正 → 按需重建下游）
```

---

## 数据流

```
00-temp/ (原始下载)
    │
    ▼
01-raw/ (原始归档，不删除)
    │
    ▼
02-cleaned/ (最低清洗)
    │
    ├──→ 03-glossary/*.glossary.md (逐课术语)
    │         │
    │         ▼
    │    Fusion ──→ 03-glossary/current/ (14 分类融合术语库)
    │         ↑
    │   07-manual/glossary/ (人工修正，融合时覆盖)
    │
    ├──→ 04-rules/ (逐课规则)
    │         │
    │         ▼
    │   校准（基于 current glossary）
    │
    └────────→ 05-system/trading-system.md (最终交易系统)
                      ↑
                07-manual/system/ (直接采用)
```

---

## 目录结构

| 目录 | 用途 | 说明 |
|:----|:-----|:------|
| `00-temp/` | 从云盘下载的原始课程文本 | 按日期子目录存放，处理后保留合并 .md |
| `01-raw/` | 原始主题文本，**永久保留** | 每课一个 `.md` 文件，严禁删除 |
| `02-cleaned/` | 最低清洗后文本 | 仅做最低限度修剪，保留口语/闲聊/失败案例 |
| `03-glossary/` | 逐课术语提取 | 每课一个 `.glossary.md`，AI 自动提取 |
| `03-glossary/current/` | **14 分类融合术语库** | 唯一有效术语定义来源，auto + manual 融合生成 |
| `04-rules/` | 逐课规则提炼 | 每课一个 `.rules.md`，基于当前术语库校准 |
| `05-system/` | 最终交易系统 | 每课增量更新，历史版归档在 archive/ |
| `06-processed/` | 流程标记 | `.done` 文件标记已完成的课程 |
| `07-manual/glossary/` | 人工术语修正记录 | 最高优先级，融合时覆盖 AI 定义 |
| `07-manual/rules/` | 人工规则修正 | 规则校准时覆盖 AI 产出 |
| `07-manual/system/` | 人工系统结构 | 直接决定交易系统主体框架 |
| `08-review/` | 交易复盘（预留） | — |
| `09-scripts/` | 工具脚本（预留） | — |
| `10-prompts/` | 提示词模板（预留） | — |
| `11-context/` | 交易系统对 AI 暴露的认知接口层 | 不新增知识，仅重组 05-system+04-rules+03-glossary；收敛时同步更新 |
| `12-research/` | 市场生态认知（IPO、行业结构、资本关系） | 与交易系统互补的平行视角，不走流水线 |

---

## 单课处理流程

| 步骤 | 内容 | 产出 |
|:----:|:-----|:-----|
| Step 1 | 合并 00-temp/ → 01-raw/ | `01-raw/YYYY-MM-DD_主题.md` |
| Step 2 | 生成 02-cleaned/ | `02-cleaned/YYYY-MM-DD_主题.cleaned.md` |
| Step 3 | 提取 03-glossary/ 术语 | `03-glossary/YYYY-MM-DD_主题.glossary.md` |
| Fusion | 术语归入 14 分类 | `03-glossary/current/glossary.md` |
| Step 4 | 提炼 04-rules/ | `04-rules/YYYY-MM-DD_主题.rules.md` |
| Step 5 | 更新 05-system/ | `05-system/trading-system.md` |

---

## 核心工作原则

1. **绝不删除 01-raw/** 原始数据
2. 优先提炼明确规则，优先风险控制
3. 不做荐股，不主观发挥，不过度扩展系统复杂度
4. 保持系统简单、明确、可执行
5. 上游变更按需触发重建，由用户确认后执行

---

## 贡献说明

本项目是个人交易系统知识工程，不接受外部 PR。如有疑问或建议请联系作者。

详见 [CLAUDE.md](CLAUDE.md)（AI 工作指令，包含完整流程和规则）。
