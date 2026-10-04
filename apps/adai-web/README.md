# adai-web — AdaiOS 桌面端（Flutter Web）

AdaiOS 的**桌面端产品入口**（参考元宝电脑端：左侧常驻导航 + 主内容区）。与 `apps/adai-app`（移动端）
**各做各的 UI**，只共享 API 契约与状态机模型（值复制，不跨工程 import）。

- **定位与边界**：`AGENTS.md`（本目录）
- **功能与界面参考**：`../../.agents/knowledge/reference/designs/frontend-reference.md`
- **API 契约（唯一真相源）**：`../../.agents/knowledge/reference/contracts/api-spec.md`

## 构建与运行

```bash
# 构建 + 本地服务（:8082，含 CanvasKit + 字体本地补丁）
sh scripts/serve_web.sh

# 分析 / 测试
flutter analyze
flutter test
```

> ⚠️ **不要用裸 `flutter build web` 部署**：漏补丁会让 CanvasKit 从 gstatic 拉取（被墙）→ 白屏、
> 中文全框；必须走 `scripts/serve_web.sh`。字体与补丁细节见本目录 `AGENTS.md`。
> 后端需先起：`cd ../../services/adai-core && ./gradlew bootRun`（:8080）。

## 发布

桌面端产物随后端批次一起部署（发版判定见 `../../.agents/mechanism/guards/guard-release.sh`：改
`apps/adai-web/**` 会推出 web 端）。**AI 不主动部署**——判定后须人点头才走 `deploy-gate.sh`。
