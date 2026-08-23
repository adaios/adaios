---
title: 元审核：AI 上下文建设工程体系（ai-engineering/ 全量 + 对抗官复核）
description: 主审核（客观）+ adversarial-reviewer（独立子代理，上下文隔离）交叉印证——12 条发现裁定 + 8 条独立炸点 + 5 实验实证；修复批 072dcee 落地后归口
version: 1
created: 2026-08-23
updated: 2026-08-23
status: active
depends-on:
  - ../../../ai-engineering/process/audit.md
related:
  - ../REVIEW.md
  - ../../../ai-engineering/README.md
tags: [review, audit, ai-engineering, meta]
---

# 元审核：AI 上下文建设工程体系（2026-08-23）

> **对象**：ai-engineering/ 全量 + AGENTS.md + AGENTS.local.md + .claude/ 接入 + .githooks/pre-commit。
> **方法**：主审核（客观全量读审 + 行业标准对照）→ adversarial-reviewer 独立子代理复核（上下文隔离，零主会话污染）→ 5 项实证实验（/tmp 干净 clone，工作区零污染）。
> **行业基准**：AGENTS.md 最佳实践 / Anthropic Agent Skills 开放标准 / Harness Engineering / Context Engineering 四问。
> **结论**：体系成熟度显著高于一般项目；**1 战略盲区 + 3 战略炸点 + 8 P1 + 7 P2/P3**；无 P0。修复批 `072dcee` 已落地。

## 一、主审核发现 → 对抗官裁定（12 条）

| # | 主审核发现 | 对抗官裁定 | 状态 |
|:-:|:----------|:----------|:----:|
| S1 | guard 脚本族脱离元治理（guard-meta 只扫 .md）| **证实且更狠**：guard-unfixed/roadmap 未入库但流程强制引用 | ✅ 已修（入库+登记）|
| P1-1 | _index.md 登记缺失（3 脚本 + review-perf）| 证实；M3 正则只匹配 .md，登记机制对脚本结构性失明 | ✅ 已修（补登记）|
| P1-2 | README「8 审查官」滞后（实 9）| 证实；命中「数字散落漂移」复发信号 | ✅ 已修（改 8 客观+1 对抗）|
| P1-3 | audit.md 未纳入对抗官 | 证实；**最贵流程缺最狠视角**，full 定义两套口径打架 | ⚠️ 待修（audit 官表 +1）|
| P1-4 | 跨工具互通零兑现（12 SKILL.md 无工具注册）| 证实（纸面承诺）| ✅ 已修（guard-tools.sh 自检）|
| P1-5 | init-ai-engineering.sh 不存在 | 证实；M4 正则扫不到裸名，结构性不可检测 | ⚠️ 待修（标待建或补脚本）|
| P1-6 | README 引用不存在的 ai-engineering-method/ | 证实；**M4 白名单显式固化错误假设** | ⚠️ 待修（删行或改指 method/）|
| P2-1 | audit.md 归口机制过时 | 部分成立（弱化）：audit.md 内部 58 行 vs 59 行自相矛盾 | ⚠️ 待修 |
| P2-2 | .claude 残留旧 IP 49.235.37.220 ×5 | 证实；allowlist 是对已下线服务器免确认放行 | ⚠️ 待修（本机手动清）|
| P2-3 | weekly-audit cron 未挂载 | 部分成立（无法证实）：macOS TCC 拦截 crontab，无任何挂载证据 | ⚠️ 待确认 |
| P2-4 | frontmatter「9 字段」实为 10 | 证实；命中数字漂移复发信号 | ⚠️ 待修 |
| P2-5 | AGENTS.md updated 滞后 | 证实；--fix 会把 updated 刷成今天，抹掉审计痕迹 | ⚠️ 待修（设计取舍）|

## 二、对抗官独立炸点（8 条，主审核漏掉）

| # | 级别 | 问题 | 状态 |
|:-:|:----:|:-----|:----:|
| S-A1 | 战略 | 门禁三重绕过全开：`--no-verify` 提示自印 + .claude allowlist 含 `git commit *`/`python3 *` + hooksPath 不入库换机缺席 | ✅ 部分已修（删提示+隐私前移）；hooksPath 缺席由 guard-tools T1 检测 |
| S-A2 | 战略 | pre-commit `--fix` 回写不进 index → 干净 clone M2 必 FAIL（实证修正：实为 **M4 AGENTS.local.md 必 FAIL**，gitignore 快照不入库）| ✅ 已修（M4 白名单）|
| S-A3 | 战略 | 强制归口步骤③跑未入库脚本（新 clone 命令不存在）| ✅ 已修（入库）|
| P1-A1 | P1 | 隐私闸门只查路径前缀不查内容；`.txt/.json` 类型在触发条件外直接 exit 0 | ✅ 已修（前移+gitignore 复核，实证拦截）|
| P1-A2 | P1 | cost-log `--record` 覆盖式快照，中途记账锁死当日 | ✅ 已修（改追加+recorded_at）|
| P1-A3 | P1 | guard-cost 全量解压所有会话，25s 超时静默降级 | ⚠️ 待修（增量/缓存）|
| P1-A4 | P1 | deploy-gate smoke 用 `X-User-Id: adai` = 用零鉴权漏洞验证部署 | ⚠️ 待修（依赖 #179）|
| P1-A5 | P1 | G1-G7 是仓库级模糊启发式非逐点检测，低信号结论 | ⚠️ 待修（逐点化）|
| P2-A1 | P2 | AGENTS.local.md 已达 7826/8192 字节（95% 预算）| ⚠️ 观察（控量策略）|
| P2-A2 | P2 | 方法论文档自身漂移（research-notes/ 不存在、状态表标 ❌ 缺实已存在）| ⚠️ 待修 |
| P2-A3 | P2 | pre-commit 吞掉 guard.sh 输出（>/dev/null），脚本自坏无法区分 | ⚠️ 待修 |

## 三、实证实验（5 项，/tmp 干净 clone）

| 实验 | 验证 | 结果 |
|:-----|:-----|:-----|
| 1. clone 跑 guard-meta | S-A2 | 修复前 3 FAIL（M4 AGENTS.local）；修复后 **PASS** |
| 2. clone 找新脚本 | S-A3 | 修复前不存在；修复后存在 |
| 3. clone 查 hooksPath | S-A1 | 空（换机门禁缺席）→ guard-tools T1 可检测 |
| 4. clone `git add -f data/` | P1-A1 | 修复前 commit 成功（exit 0）；修复后 ❌ 拦截 |
| 5. 读 guard-cost 源码 | P1-A2 | 确认「同一天去重（覆盖）」→ 已改追加 |

## 四、已落地修复批（commit `072dcee`）

1. guard-meta M4 白名单 AGENTS.local.md（gitignore 快照非漂移）
2. guard-unfixed.sh / guard-roadmap.sh / guard-tools.sh 入库 + _index.md 补登记（guard-meta/review-perf 一并）
3. pre-commit 隐私闸门前移（堵类型绕过）+ 新增文件 gitignore 复核（堵 -f 强加）+ 删 --no-verify 提示
4. guard-cost --record 覆盖式改追加 + recorded_at + LOG 按日聚合
5. 新增 guard-tools.sh 接入自检（T1 hook / T2 快照 / T3 技能 / T4 注册 / T5 入口）——替代静态映射表（用户否决），机制替人记得
6. README 行业定位段修订：上下文资产 > 代码层 + .agents/ 非行业标准（仅 Qoder 专属约定）

## 五、未修项归口（详见 REVIEW.md）

- 战略：无新增（S-A1 部分残留并入 #179 登录体系依赖）
- P1：P1-3（audit 官表 +1 对抗官）、P1-5（脚手架标注）、P1-6（README 外引用）、P1-A3（cost 全量扫描）、P1-A4（smoke 鉴权依赖）、P1-A5（G 启发式）→ 入 REVIEW P1
- P2：P2-1（audit 内部矛盾）、P2-2（旧 IP 本机清理）、P2-3（cron 确认）、P2-4（9/10 字段）、P2-A2（method 漂移）、P2-A3（guard.sh 输出吞）→ 入 REVIEW P2
- P2-A1（快照 95% 预算）→ task-log 观察

> **追加方式**：后续 ai-engineering 体系改动按本报告清单复核，命中项标注「复发现场」。
