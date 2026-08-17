---
title: 功能落地收尾流程（/ship）
description: 开发收尾闭环——测试 → 契约同步 → 文档登记 → 元治理校验（guard-meta）→ 规范提交；与 /review 配套
version: 1
created: 2026-08-15
updated: 2026-08-18
status: active
lines: 109
depends-on:
  - ../frontmatter-spec.md
  - ../guard-meta.sh
related:
  - review.md
  - audit.md
  - ../../docs/reference/status.md
tags: [ai, process, ship]
---

# 功能落地收尾流程（ship）

> 对应原 `/ship` skill，工具无关化。与 `/review` 配套：**ship 保证产出完整（上游），review 检查遗漏（下游）**。本流程是开发批次的收尾闭环——提交前必须全部通过。

## 完成标准（2026-08-16 反思沉淀，防"功能跑通就当完成"）

**一次功能改动 = 五件套同批完成，缺一不算 ship 完成：**

1. **功能代码** —— 改完只算"进行中"，不算完成
2. **测试同批** —— 服务层业务逻辑 + 端点测试与功能同批写（不后置）；解析/upsert/状态保留/写回等**关键分支必须测**
3. **契约同步** —— 新端点 → api-spec；新功能 → feature-reference；data 格式 → freeze
4. **门禁主动跑** —— 交付前主动跑三件套（guard-meta + guard-align + guard.sh），**不依赖 pre-commit 兜底**；`gradle test 绿 ≠ 项目绿`（guard-align 的 A1/A2/A4 对拍、guard.sh 的 G1-G7 防复发是 gradle 覆盖不到的）
5. **写代码前对照检查清单** —— 存储层 → 查 G2（不取 now()）；新端点 → 查 B43（api-spec/配置）；文档/目录迁移 → 查 D38（全库 grep）；新格式/正则 → 查 B44/K38（口径对拍）

**反模式（已踩，勿重演）**：功能跑通 + 部署成功 = 完成；测试后置；gradle 绿 = 安全；不翻检查清单凭感觉写。

## 步骤

### 1. 测试

- 后端：`cd services/adai-core && ./gradlew test`
- 前端：对应 Flutter 工程的 `flutter test`
- 更新 `docs/reference/status.md`（测试数/端点数唯一真相源，本处不复制数字）

### 2. 契约同步

- 新增/修改 API → 同步 `docs/architecture/api-spec.md`（含版本 + 变更记录）
- data/ 文件格式变更 → 同步 `docs/architecture/data-format-freeze.md`
- 新功能落地 → 同步 `docs/reference/feature-reference.md`（功能真相源）

### 3. 文档登记

- 新增/移动/删除文档 → 更新对应目录 `_index.md` 文件清单
- 新增目录 → 补 `_index.md`（职责 + 文件清单 + 过期判断）
- 子项目 CLAUDE.md「当前焦点」批次状态更新
- `docs/reference/change-log.md` 顶部追加一行（日期 | 批次 | 摘要 | 测试数变化）

### 4. 决策沉淀（RFC 验收核验 + 沉淀检查）

- 本批关联 RFC：对照其「验收标准」段**逐条 PASS/FAIL 核验并留痕**（写回 RFC 或 change-log）
- **ADR 三问判断**（2026-08-18 用户确立，防「每批必建」流水账）：**全中才建** `assets/adr/ADR-00N.md` 并登记 `assets/_index.md`；否则不建（在 change-log 批次摘要写清「为什么这么定」即可兜底）
  1. **推翻成本高**——将来反悔要动很多代码/数据？
  2. **有被否决的备选**——当时纠结过别的方案，未来的人可能再提？
  3. **影响未来方向**——新功能/新会话必须知道这个背景才能做对？
- 本批踩坑/取舍 → 入 checklists + `assets/pitfalls.md`（沉淀过滤器见 `workflow/discuss.md`）
- **沉淀检查**（进攻侧②③，软提示）：`bash ai-engineering/guard-sediment.sh`
  - S1 变更提示：本批代码文件 → 确认入 pitfalls/ADR（无则标注「无新增沉淀」）
  - S2 出表检查：REVIEW 未修项本批处理了 → 标 ✅ 出表
  - S3 登记检查：change-log 已登记本批（FAIL 级，未登记则补）

### 5. 元治理校验（提交前门禁）

```bash
bash ai-engineering/guard-meta.sh --fix    # 回写 lines（D34）+ 重新校验
bash ai-engineering/guard-meta.sh          # frontmatter 结构：必须 PASS
bash ai-engineering/guard-align.sh         # 代码↔文档内容对齐：必须 PASS（A1 端点/A2 测试数）
```

- `--fix` 自动回写 frontmatter `lines`（按 wc -l 校准）与 `updated`（今日日期）
- guard-meta 仍 FAIL 的项：M1 断链 / M2 lines / M3 孤儿 / M4 正文路径 → **人工处理后**，禁止带 FAIL 提交
- guard-align FAIL 的项：A1 端点未登记 api-spec / A2 测试数漂移 status.md → 先同步文档再提交
- **git pre-commit hook 自动触发**（`.githooks/pre-commit`，`core.hooksPath` 已配置）：任何代码/测试/契约文档变更，提交时自动跑 guard-align，FAIL 阻止提交——**无需人工提醒**
- 校验范围：AGENTS.md + docs/_index.md + 全部 docs/*/_index.md + ai-engineering/**（frontmatter-spec §四 强制区）

### 6. 部署（触发侧：deploy-gate 门禁 + smoke）

```bash
bash ai-engineering/deploy-gate.sh 49.235.37.220 build/libs/adai-core-0.0.1-SNAPSHOT.jar
```

- 部署前自动强制：guard-meta + guard-align + guard.sh（不过关拒绝部署）
- 部署后自动 smoke：feed/memory/advice/parse/timeline/tags 六端点验证
- 部署是用户确认的动作 → 最不可绕过的一道闸门

### 7. 规范提交

- 提交信息按批次主题（如 `feat:` / `fix:` / `docs:`），含批次要点
- 一个批次一个提交，不混合无关改动

## 与 /review 的分工

| | /ship（本流程） | /review |
|:--|:---------------|:--------|
| 时机 | 开发收尾 | 提交后/定期 |
| 目的 | 产出完整（上游）| 检查遗漏（下游）|
| 校验 | guard-meta 门禁 | 按 diff 派官 + 守护检查 |
| 输出 | 规范提交 | REVIEW.md 滚动更新 |

## 追加方式

新发现「收尾遗漏」类问题 → 在对应步骤补一行，注明日期。
