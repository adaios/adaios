# 20260802 多模态图片记录（GLM-VLM）— RFC

> status: **implemented**（2026-08-02：Phase 1 后端 + Phase 2 前端 app/web 全部落地；GLM-4.6V-Flash · 图片=记录 · v0.3.0）
> 落地记录：后端 `VisualAiClient`+`GlmVisualAiClient`+`POST/GET /records/media`（254 测试绿）· 前端 adai-app/adai-web 输入栏图片上传 · api-spec 同步
> 关联：`product-roadmap.md` §3.1（L4 多模态 ❌ 最大空白）｜`VISION.md` Layer 4 / Everything is Content

## 背景

L4 通用记录的最大空白：**多模态（图片）**。当前 `POST /api/v1/records` 只收纯文本，DeepSeek 是纯文本模型——图片理解必须引入第二家视觉模型供应商。

## 决策

1. **模型**：智谱 **GLM-4.6V-Flash**（图片理解**免费**、国内直连、持仓截图/白板/发票识别够用）。不够强可切 GLM-4.5V-Plus / Qwen3-VL-Plus（`VisualAiClient` 接口天然可切换，不碰业务层）。
2. **定位**：roadmap L4 多模态从 ❌ → 📋，目标 **v0.3.0**（先落后端闭环 + 测试，前端上传 Phase 2）。
3. **Everything is Content**：图片 → VLM 文本理解 → 进 `ContentRecord`，Timeline / Memory / Search **全部走现有文本闭环，流水线零改动**。

## 技术方案

```
前端选图 → POST /api/v1/records/media (multipart: image + 可选 caption)
  → 存原图 data/records/{userId}/YYYY/MM/media/{id}.png（File First，gitignore）
  → VisualAiClient.understand(base64, caption) → ImageUnderstanding
  → 组装 ContentRecord(type=image, content=理解文本, tags, domain, mediaPath)
  → recordRepository.save → TagIndex 自动索引 + Memory 沉淀（复用现有流）
  → 返回 {recordId, summary, tags}
```

- 新增 `infrastructure/ai/vision/`：
  - `VisualAiClient` 接口 — `ImageUnderstanding understand(ImageRequest)`（镜像 `AiClient` 端口模式）
  - `GlmVisualAiClient` — `@Component` + `@Value("${GLM_API_KEY:}")`，GLM OpenAI 兼容端点 `/api/paas/v4/chat/completions`，JSON 模式输出
- 配置：`adai.ai.vision.provider: glm` + `model: glm-4.6v-flash`（.env 配 `GLM_API_KEY`）
- 意图：图片记录固定为 **STATEMENT（记录）**，问图（"这张 K 线说明什么"）后续再说

## 接口设计（草案）

```jsonc
// POST /api/v1/records/media  (multipart)
//   file: 图片 (jpeg/png, ≤5MB)  X-User-Id header  caption?: 用户备注
// 返回 200:
{ "recordId": "rec_xxx", "intent": "log", "summary": "持仓截图：浦发银行…",
  "tags": ["交易","持仓"], "mediaPath": "2026/08/02/media/rec_xxx.png" }

// ImageUnderstanding 字段：summary / tags / category / extractedText(OCR)
```

## 范围与成本

| 项 | 内容 |
|:---|:-----|
| **Phase 1（本次）** | 后端：VisualAiClient + GLM 实现 + media 上传端点 + 测试（接口 + 解析） |
| **Phase 2** | 前端：adai-app（拍照/相册）+ adai-web 上传入口 + api-spec 同步 |
| **成本** | 0（GLM-4.6V-Flash 图片理解免费）；图片压缩限 5MB 控风险 |
| **风险** | GLM key 需申请；Flash 对复杂图表可能不准（升 Plus 兜底）|

## 待确认

1. 方向 OK？（默认：v0.3.0、记录为主、后端先行）
2. Phase 2 前端先做哪个端？（默认 **adai-app**——拍照/相册是移动端最自然的入口）
