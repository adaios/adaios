# 状态快照（Status）

> **测试数/端点数/运行环境的唯一事实源**（RFC `20260815-docs-governance` 单一事实源化）。
> **更新规则：每批 /ship 时更新本文件**——CLAUDE.md 及子项目 CLAUDE.md 一律不复制数字，只指回本文件。

---

## 测试状态

| 端 | 测试数 | 失败 | 备注 |
|:---|:------:|:----:|:-----|
| 后端 adai-core | **440** | 0 | 15 Controller 接口测试全覆盖 + 多模态 18 + 鉴权 4 + 行情推送 15 + AI 日志 20 + 插件门控/隔离 + 展示层聚合（S-2 图文一体 + 多轮 chat 单条，+4）|
| 前端 adai-app | **94** | 0 | Feed 状态机 12 + 6 页面 + 选号/切换链路 + 插件门控 |
| 前端 adai-web | **47** | 0 | 桌面壳（导航/懒加载/保活）+ P1-5 插件加载 label 重解析回归 |
| 前端 adai-admin | **33** | 0 | 账号/数据/系统/知识页 + 插件开关 |

## 端点/控制器计数（Gradle `endpoints.txt` 单一口径，REVIEW #228）

- Controller：**16**（`interfaces/*Controller.java`，含 MeController；GlobalExceptionHandler 非 Controller）
- 端点：**51**（`build/resources/main/META-INF/endpoints.txt` 实测）
- 端点计数规则：以 Gradle 生成的 `endpoints.txt` 为准，禁止扫源码回退（REVIEW #228/#187）

## 运行环境

- 后端：`localhost:8080`（DeepSeek 模式 + GLM 视觉——`.env` 需配 `GLM_API_KEY` 才有真 VLM 理解，无 key 时上传降级不丢数据）
- 前端：adai-app `localhost:8081`（移动端入口，Web 形态）· adai-web `localhost:8082`（桌面端入口）· adai-admin `localhost:8083`（产品后台）（均 Flutter Web + CanvasKit 补丁）
- 生产服务器：49.235.37.220
- 数据路径：`data/{userId}/...`（本机账号 = `data/adai/`，default 已迁移移除；测试可用 `data/default/`）

## 发布状态

- **v1.0.0**：Release Notes + 数据冻结 + 插件门控就绪；tag + 部署待用户确认触发（2026-08-15 用户决策：**先治理流程，发布顺延**）
- 后续候选：多账号前端选号（v1.0.1）、登录体系（#179，v1.0.1 立项）

## 未修项

- 见 `docs/review/REVIEW.md`（战略 + P0-P2 唯一滚动区）
- 待办/观察项：见 `docs/reference/task-log.md`
