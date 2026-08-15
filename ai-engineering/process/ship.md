---
title: 功能落地收尾流程（/ship）
description: 开发收尾闭环——测试 → 契约同步 → 文档登记 → 元治理校验（guard-meta）→ 规范提交；与 /review 配套
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 70
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

### 4. 元治理校验（提交前门禁）

```bash
bash ai-engineering/guard-meta.sh --fix    # 回写 lines（D34）+ 重新校验
bash ai-engineering/guard-meta.sh          # 必须 PASS 才可提交
```

- `--fix` 自动回写 frontmatter `lines`（按 wc -l 校准）与 `updated`（今日日期）
- 仍 FAIL 的项：M1 断链 / M2 lines / M3 孤儿 → **人工处理后再提交**，禁止带着 FAIL 提交
- 校验范围：AGENTS.md + docs/_index.md + 全部 docs/*/_index.md + ai-engineering/**（frontmatter-spec §四 强制区）

### 5. 规范提交

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
