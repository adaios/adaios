---
name: ship
description: 功能落地闭环 — 开发收尾：测试 → api-spec 同步 → 文档同步 → 规范提交
---

# /ship — 功能落地闭环

开发完一个功能后执行，把改动**完整收尾交付**。防止单人开发常犯的"改完代码忘同步文档/契约"——历史教训：api-spec 缺 7 端点、CLAUDE.md 描述过期、B Phase 4 文档滞后。

**与 `/review` 的关系**：ship 是上游（保证产出完整），review 是下游（检查遗漏）。ship 做得好，review 就少发现问题。

## 执行步骤

### 1. 摸清本次改动

```bash
git status --short
git diff --stat HEAD
```

确认改动归属：后端 / 前端 / 文档 / 知识。后续步骤只跑受影响的。

### 2. 测试验证（只跑受影响）

| 改动区域 | 命令 |
|:---------|:-----|
| `services/adai-core/**` | `cd services/adai-core && ./gradlew test` |
| `apps/adai-app/**` | `cd apps/adai-app && flutter test && flutter analyze` |

**测试失败 → 停下，修好再继续下一步。** 不在红着测试的前提下收尾。

### 3. API 契约同步

```bash
grep -rn "@GetMapping\|@PostMapping\|@PutMapping\|@PatchMapping\|@DeleteMapping" services/adai-core/src/main/java/com/adaiadai/core/interfaces
```

- 有**新增/修改端点** → 更新 `docs/architecture/api-spec.md`，保持契约与代码一致
- 无 API 改动 → 跳过

### 4. 文档同步

- 新功能/完成项 → 更新根 `CLAUDE.md`「当前焦点」状态表（方向进展/已完成/待做）
- 新文档、新 RFC → 登记 `docs/README.md` 索引
- 架构决策变化 → 确认 `docs/architecture/` 相关文档（如 `system-architecture.md`）未过期

### 5. 规范提交

```bash
git add -A
```

- 提交信息：`<前缀>: <中文描述>`，前缀用 kebab-case（`feat`/`fix`/`docs`/`refactor`/`test`）
- 末尾加 `Co-Authored-By: Claude <noreply@anthropic.com>`
- **不 push**（除非用户明确要求）
- 提交前再确认一遍：`git status` 无意外文件、测试已过、文档已同步

## 约定

- ship 是**执行型** skill：直接跑测试、改文档、提交。与 review（只报告不修复）相反。
- 改动若涉及隐私数据（`data/records`、`data/memory`、`data/trading` 等被 .gitignore 排除），确认不会误提交（参考 .gitignore 白名单机制）。
- ship 后如果用户想验证质量，可建议接着跑 `/review`（light 档，秒级）。
