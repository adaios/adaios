# 文档索引

> AdaiOS 项目文档。按"必读 → 架构 → 功能 → API → 决策 → 部署"分层组织。

---

## ⚡ 必读

| 文档 | 说明 |
|:-----|:------|
| [VISION.md](VISION.md) | 项目愿景、五层产品架构、工程原则。**每个 AI 会话首先阅读。** |
| [adaios-personal-os.md](architecture/adaios-personal-os.md) | 核心理念完整阐述：Everything is Content、Knowledge Evolves、Reasoning is Service |

## 🏗 架构

| 文档 | 说明 |
|:-----|:------|
| [product-architecture.md](architecture/product-architecture.md) | 五层产品架构详解（Layer 1-6） |
| [system-architecture.md](architecture/system-architecture.md) | 系统架构、Kernel/Domain 分层、Context Engine |
| [data-flow.md](architecture/data-flow.md) | 当前数据流图（代码实现对应） |
| [ai-context-design.md](architecture/ai-context-design.md) | AI Context 分层设计（Profile/Records/Chat/Memory/Pattern） |

## 🔧 功能手册

| 文档 | 说明 |
|:-----|:------|
| [backend-capabilities.md](architecture/backend-capabilities.md) | 后端功能产品说明书：输入、对话、Feed、记忆、搜索、交易 |

## 📋 API 契约

| 文档 | 说明 |
|:-----|:------|
| [api-spec.md](architecture/api-spec.md) | 前后端接口契约。**所有 API 的定义、请求/响应结构在此。** |

## 🎯 决策记录 (RFC)

| 文档 | 日期 | 说明 |
|:-----|:-----|:------|
| [20260718-context-memory-knowledge-loop.md](rfc/20260718-context-memory-knowledge-loop.md) | 2026-07-18 | Context 闭环：记忆回读 + 知识召回 |
| [20260720-context-architecture.md](rfc/20260720-context-architecture.md) | 2026-07-20 | Context 架构设计 |
| [20260721-ai-chat-quality.md](rfc/20260721-ai-chat-quality.md) | 2026-07-21 | AI 对话质量修复：从分析模式到对话模式 |
| [20260722-dual-world.md](rfc/20260722-dual-world.md) | 2026-07-22 | 双主页（Dual World）设计 |
| [20260722-features-memory-timeline-search.md](rfc/20260722-features-memory-timeline-search.md) | 2026-07-22 | 记忆页 + 时间线页 + 搜索设计 |
| [20260722-identity-page.md](rfc/20260722-identity-page.md) | 2026-07-22 | 身份页设计 |
| [20260723-launcher-polish.md](rfc/20260723-launcher-polish.md) | 2026-07-23 | 启动器打磨 |
| [20260723-tagcloud-gesture.md](rfc/20260723-tagcloud-gesture.md) | 2026-07-23 | 标签云手势交互 |

## 🚀 部署

| 文档 | 说明 |
|:-----|:------|
| [backend-deployment.md](deployment/backend-deployment.md) | 后端部署指南 |

---

**文档版本：v1.0 | 最后更新：2026-07-22**
