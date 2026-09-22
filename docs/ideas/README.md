# 想法/方案归档区

> 未定型但有价值的想法、方案、框架文档。**暂不参与项目主流程**，但值得保留——未来可能孵化成 RFC 或功能。

## 归档内容

| 目录 | 内容 | 说明 |
|:-----|:-----|:-----|
| [ai-terms/](ai-terms/) | AI 技术名词文稿 | 术语收集稿 |
| [domain-os-growth-model/](domain-os-growth-model/) | Domain OS 生长模型（草稿） | 通道协议 + 阶段机 + 压缩三形式 + OS/子模块判据，未定型 |
| [20260812-ai-interaction-log.md](20260812-ai-interaction-log.md) | AI 交互日志需求（R1） | 记录每次 AI 交互入参/响应，阿呆 08-12 反馈，✅ 已实现（后端一步到位，管理端查看页顺延） |
| [20260812-record-task-association.md](20260812-record-task-association.md) | 记录↔任务模块关联（R2） | ✅ 已实现（2026-08-13，RFC `20260813-record-task-and-sports-analysis`）：domain=project 记录自动转任务（方案 B：默认转 + AI actionable 挡 + #备忘/#想法 排除标签），任务带 sourceRecordId 溯源 |
| [20260812-camera-sports-analysis.md](20260812-camera-sports-analysis.md) | 相机拍照/视频 → 运动动作分析 | ⏸ 搁置（2026-08-13 阿呆决定不深入）：Phase 1「分析动作」按钮已撤掉，保留 L4 图片上传/追问基础通道；Phase 2 专用 type + 视频留 v2 |
| [20260916-plugin-and-cold-start-discussion.md](20260916-plugin-and-cold-start-discussion.md) | 插件、冷启动与知识传递——会话讨论沉淀 | 2026-09-16/17 五轮讨论：插件盘点（3+1）/「通用→基础能力·垂直→插件」判定尺 / 记忆出口 / AI「记忆主权」风口 / 新用户依赖（托付→履约→信任）/ learn 表征适配（编码-解码错配）；未定型，姊妹篇 `../review/audits/2026-09-17-wiring-audit.md` |
| [20260922-adai-contentflow-postmortem.md](20260922-adai-contentflow-postmortem.md) | 老项目「积录」（adai-contentflow）考古 | 2026-09-22 只读考古：三条活下来的基因（无感观察 / 共现关联 / 自动分段）+ 一条祖训（算不出来就说「未生成」）+ 六条判断 + 删除前保留结论；**项目服务器与数据均已不存在，本文件为唯一留存** |
| [20260922-jilu-docs-salvage.md](20260922-jilu-docs-salvage.md) | 积录**文档库**拾遗（代码之外的那一半） | 2026-09-22 只读通读 `adai-docs/docs/projects`（77 md / 3100 行）：Mx 词源、未实现清单（片段/四级坐标/订阅）、五年前的产品立场、可直接抄的工程规范、文档版「未修项」；**含同源明文凭据第二份拷贝的风险提示（P0）** |

> 原 `ai-native/` 框架系列已于 2026-08-15 移出仓库（公司/多项目侧研究归 `ai-context-research/`，adaios 同级独立目录；adaios git 历史可追溯）。

## 规则

- 有新想法/方案：放这里（自建子目录），并登记到本 README
- 本区是**正式位置**——`/ship`、`/review` 不把它当"未归位"内容
- 想法成熟决定立项时：升级为 `docs/rfc/` 并移出本区
