---
title: 已知坑归集（Pitfalls）
description: 跨 checklists 归集的「踩过的坑」索引——症状/根因/修复/复发信号，按域分组；完整逐条在 checklists 活文档
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 65
depends-on:
  - ../checklists/guard.md
related:
  - ../checklists/review-backend.md
  - ../checklists/review-frontend.md
  - ../checklists/review-docs.md
  - ../checklists/review-knowledge.md
tags: [ai, assets, pitfalls]
---

# 已知坑归集

> **定位**：checklists 是「逐条检查方法 + 上次发现」的活文档；本文件把**已发生过的坑**按域归集为索引——AI 打开一眼看到「这个项目踩过哪些坑、复发信号是什么」。新坑由 AI 主动发现（沉淀过滤器），修复即入对应 checklists，汇总在此。

## 一、存储与数据安全（G1-G3 / B 系列）

| 坑 | 症状 | 根因 | 修复 | 🔴 待修 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| ID 秒级精度 | 同秒写入互相覆盖 | ID 生成 `yyyyMMdd_HHmmss` 无毫秒 | 加 `SSS`（G1） | ✅ 已修 | 新 ID 生成不含毫秒 |
| now() 推导路径 | 跨日复制丢轮次/旧卡归"今天" | storage 用 `LocalDate.now()` 推 filePath / parseDateTime 回退 now() | 从实体 `createdAt` 推导；缺失返回 null（G2/B29/B37） | ✅ 已修 | 新增 `now()` 回退代码 |
| 删除在降级路径 | AI 失败时删用户记录 | catch 降级路径内调用删除 | 正常业务删除豁免，降级路径禁止（G3） | ✅ 已修 | catch 块内出现 delete |
| 整文件重写并发 | 并发 RMW 静默丢更新 | Memory/TagIndex/Position 整文件重写无锁 | synchronized / per-user 锁（B14） | 🔴 待修 | save 无锁 |

## 二、AI 集成健壮性（B11-B12/B24/B26）

| 坑 | 症状 | 根因 | 修复 | 🔴 待修 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| emoji 代理对 | AI 回复 emoji 抛异常丢字段 | 解析器未处理 surrogate pair | `LlmResponseParser` 按 matcher region 推进（B11） | ✅ 已修 | 新解析器不用 region |
| AI 失败删记录 | 调用失败数据丢失 | 失败路径直接删 | 失败有降级路径（B12） | ✅ 已修 | 失败→删除逻辑 |
| 哨兵复用 | summary="recorded" 被三处消费，记录无限重补 | 内容文本兼任处理标记 | 显式处理标记，禁止内容哨兵（B26） | ✅ 已修 | 新标记复用内容文本 |

## 三、前端状态与生命周期（F 系列）

| 坑 | 症状 | 根因 | 修复 | 🔴 待修 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| await 后空值 | 对话态崩溃 | await 后解引用共享单例 `_activeCardId!` | 重新判空 + build 侧 null 兜底（F10/F29） | 🔴 待修 | `!` 解引用无判空 |
| 列表重建挤掉活动卡 | 发媒体后崩溃 | `_loadFeed` 覆盖 `_cards` 但活动卡被挤出 | 校验 `_activeCardId` 仍在新列表（F29） | ✅ 已修 | 重建路径不校验 |
| 守卫只包异步 | 双击弹掉 home | onConfirm 守卫只包 async 不包 `nav.pop()` | 守卫包住闭包整体（F22） | ✅ 已修 | 副作用在守卫外 |
| 保活页陈旧 | 数据可变页面保活即陈旧 | IndexedStack initState-only 加载 | 刷新路径（F11/F41） | 🔴 待修 | 保活页无刷新 |

## 四、文档与契约（D 系列）

| 坑 | 症状 | 根因 | 修复 | 🔴 待修 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| 数字散落漂移 | 测试数/端点数多处不一致 | 数字快照散在多处 | status.md 单一真相源（D20/D32） | ✅ 已修 | 新文档写数字快照 |
| frontmatter 断链 | 图谱边解析失败 | depends-on/related 相对路径错 | guard-meta M1 检测（D30） | ✅ 已修 | 新增边不校验 |
| lines 漂移 | 声明行数 ≠ 实际 | 手写 lines | guard-meta --fix 回写（D34） | ✅ 已修 | 手改 lines |

## 五、插件门控与隐私（B31-B33/B36/B40/K 系列）

| 坑 | 症状 | 根因 | 修复 | 🔴 待修 | 复发信号 |
|:---|:-----|:-----|:-----|:----:|:---------|
| 门控旁路 | 无插件用户访问 trading/project | 只门控主入口漏写路径 | 端点清单枚举（B36/B40） | ✅ 已修 | 新端点不查 plugins |
| 隐私进 git | 真实持仓/记录进 git 历史 | 新落盘目录漏 .gitignore | `git check-ignore` 验证（B25/K8） | ✅ 已修 | 新 data/ 子目录未 ignore |

---
**追加方式**：AI 在开发/审核中发现新坑 → ①入对应 checklists（活文档）②本文件按域补一行（索引）。两条都要，防止只入一处。
