---
title: 多账号架构预留 — 数据路径 userId 分层（v1.0.0 前置）
date: 2026-08-02
status: implemented
implementedIn: 2026-08-02（全链路 userId 透传 + 数据迁移 + 隔离测试）
---

# 多账号架构预留（Multi-Account Prep）

## 一、背景

多账号是 v1.0.0 目标之一（简单登录，不做注册，账号由后台管理系统维护）。数据格式在 v1.0.0 冻结前，必须把 **userId 维度**定稿——否则上线后再改数据路径，迁移成本剧增。

**本期只做架构预留，不做功能层**（登录/注册/账号管理在 v1.0.0）。

## 二、决策

### 1. 统一加层 + 数据迁移

所有数据路径 `data/records/...` → `data/{userId}/records/...`。现有单用户数据迁移进 `data/default/`（幂等脚本 + 自动备份 tar.gz）。

### 2. 全链路一次到位

`Controller @RequestHeader("X-User-Id")` → AppService → ContextEngine/Repository → FileStorage 全链路显式透传。

### 3. 显式传参，不用 ThreadLocal/拦截器

- 符合项目"禁止隐式状态"风格（无 @Autowired 字段注入）
- 线程安全：AI 场景有 @Async/CompletableFuture，ThreadLocal 会丢失
- 测试直接：传不同 userId 即验证隔离

### 4. FileStorage 是唯一强制隔离点

5 方法全部加 `String userId`，`resolve(userId, path)` = `basePath/{userId}/{path}`。userId 校验仅 `[a-zA-Z0-9_-]+`（防路径注入），不合法返回 400。

## 三、改动面（~47 文件，5 阶段）

| 阶段 | 内容 | 状态 |
|:-----|:-----|:----:|
| A | 数据层：FileStorage 5 方法加 userId + 8 存储实现透传 | ✅ |
| B | 服务层：ContextEngine / 5 个 Contributor / 8 AppService 透传 | ✅ |
| C | 接口层：12 Controller 加 `@RequestHeader` 透传 | ✅ |
| D | 数据迁移：`scripts/migrate-data-to-user-layer.sh` + .gitignore 通配 | ✅ |
| E | 隔离测试：MultiUserIsolationTest（FileStorage/Record/TagIndex/Memory 四维） | ✅ |
| F | 收尾：api-spec + 本文档 + CLAUDE.md | ✅ |

**例外（不加 userId）**：`ProjectStatusAppService.getStatus()`（读 docs/rfc + git，全局）；`TradingController` promote/conflicts（读写 os/trading-os 知识库，全局共享）。

## 四、单用户兼容

- Controller 用 `@RequestHeader(value = "X-User-Id", defaultValue = "default")`，不带头 = 默认 `default`，行为与之前完全一致
- 现有 169 测试全绿（多数不带头验证默认行为）

## 五、多用户隔离验证

`MultiUserIsolationTest` 核心价值：alice 写入的 record/tags/memory，bob 全链路读不到（FileStorage 路径 → RecordRepository → TagIndex → MemoryService 四层）。

## 六、后续（v1.0.0 功能层，不在本期）

- 登录/账号体系（后台管理系统管理账号）
- 前端登录态 → 携带 `X-User-Id`
- 多用户下 Brief/TagIndex 缓存已按用户键隔离（本期已做）
- `RecordRetryService` 定时任务默认扫 `default` 用户，手动触发可指定
