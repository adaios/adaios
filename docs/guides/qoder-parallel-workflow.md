---
title: Qoder CN 并行工作流实验手册（worktree 多任务）
description: 把「一条分支 = 一次只能干一件事」改成「多条任务线并行、人只在卡住处出场」的实操手册——Qoder CN CLI 环境配置、内网网络受限四档降级、手机/Web 远程看板（需许可）与 Hook+系统通知的纯本地看板（零云端）、IDEA 协同、验证清单、坑与回滚；跨项目通用，非 AdaiOS 功能模块
version: 1
created: 2026-09-17
updated: 2026-09-17
status: active
lines: 550
depends-on: []
related:
  - ./_index.md
tags: [guide, workflow, ai-tooling]
---

# Qoder CN 并行工作流实验手册（worktree 多任务）

> **这份文档是什么**：一份**跨项目通用**的实操手册，用于验证「一个人同时推进多条开发任务线」是否真的可行。
> 它不属于 AdaiOS 任何功能模块，也不含任何公司/项目具体信息（占位符 `<...>`），可安全放入版本库。
>
> **由来（2026-09-17 讨论）**：用户的工作模式是**任务按分支绑定**，因此「一个工作目录 = 并发度 1」，
> 多开会话也只能在同一分支允许的范围内活动。真正的瓶颈不是 AI 慢，而是**必须等它算完才能动弹**。
> 期望状态：人从「执行者」变成「卡住时的决策者」。

## 一、先明确要解决什么（和不解决什么）

**要解决**：会话能选工作目录 → 一任务一 worktree → N 条线并行 → 你只在卡住处出场。

**不解决**（别指望，否则会误判实验结论）：

| 约束 | 为什么工具解决不了 |
|:--|:--|
| 团队合并通道 | 你并行 5 条，review 通道还是那一个团队——**对你是并行，对团队是突发流量** |
| 合并冲突成本 | 串行时冲突由 AI 顺手解；并行后冲突变成**新的决策任务**，复杂度接近 N² |
| 上下文切换 | 从任务 1 被叫到任务 5 都要重新装载上下文——**这才是真正的天花板** |
| 共享运行环境 | 要起服务、连库、占端口的任务，多 worktree 会互相抢（见 §七 分级表） |

**成立的唯一前提**：任务之间**不共享运行环境、改动面不重叠**。

## 二、环境清单

| 项 | 要求 | 检查 |
|:--|:--|:--|
| 操作系统 | macOS / Linux / Windows Terminal（**Windows arm64 不支持**） | — |
| Git | 已装且在 PATH | `git --version` |
| Node.js | ≥ 20（**仅 npm 安装方式需要**，脚本安装不需要） | `node -v` |
| Qoder CN 账号 | 公司已开通、额度可用 | — |
| Qoder CN CLI | 命令是 `qoderclicn` | `qoderclicn --version` |
| **网络** | 能访问 `qoder.com.cn` 与阿里云 OSS；受限时见 **§3.4** | `curl -sS -o /dev/null -w '%{http_code}\n' https://qoder.com.cn/install` |

## 三、安装与登录（一次性）

### 3.1 安装

```bash
curl -fsSL https://qoder.com.cn/install | bash
qoderclicn --version        # 打印版本号即成功
```

npm 备用（需 Node ≥ 20）：

```bash
npm install -g @qodercn-ai/qoderclicn
```

> ⚠️ **CN 版命令是 `qoderclicn`，配置目录 `~/.qoder-cn/`**。
> 网上大量教程抄的是**国际版**（`qoder` / `~/.qoder/`），照抄会出现「命令找不到」或「配置改了不生效」。

### 3.2 登录

```bash
qoderclicn
/login
```

两种方式：**login with browser**（浏览器）/ **login with qoder personal access token**。
PAT 获取地址：<https://qoder.com.cn/account/integrations>

自动化/脚本场景用环境变量（**不要硬编码进脚本或仓库**）：

```bash
export QODERCN_PERSONAL_ACCESS_TOKEN="your_personal_access_token_here"
```

### 3.3 升级

自动升级默认开启。手动升级：

```bash
qoderclicn update
# 或重跑安装脚本
curl -fsSL https://qoder.com.cn/install | bash -s -- --force
```

### 3.4 内网网络受限怎么办（**先读这条**）

> **结论先行**：这套方案的生死线是 **`git worktree`**，不是 Qoder CLI。
> **CLI 装不上，目标照样能达成**——四档降级阶梯见下。

安装要过两道网：`qoder.com.cn`（拿安装脚本）和阿里云 OSS（`*.aliyuncs.com`，拿二进制包）。
但你**IDEA 里的 Qoder CN 插件既然能正常用**，就说明代理本来就是通的——
CLI 缺的只是「用同一个代理」，不是「没有网络」。

**第一步：先探测，别猜。**

```bash
# 1. 安装脚本域名
curl -sS -o /dev/null -w 'qoder.com.cn: %{http_code}\n' --max-time 8 https://qoder.com.cn/install
# 2. 二进制所在的阿里云 OSS 加速域名
curl -sS -o /dev/null -w 'oss: %{http_code}\n' --max-time 8 https://qoder-app.oss-accelerate.aliyuncs.com/
# 3. 当前代理环境变量
env | grep -i proxy
```

**第二步：把 IDEA 里那个代理抄给 CLI。**

官方明确支持代理（[网络代理配置](https://help.aliyun.com/zh/lingma/network-proxy-configuration)）。
插件侧在 **Settings → HTTP Proxy Settings → Manual proxy configuration → Proxy Configuration URL**
——**把那个 URL 抄下来**，然后：

```bash
export HTTPS_PROXY="http://<代理地址>:<端口>"
export HTTP_PROXY="$HTTPS_PROXY"
curl -fsSL https://qoder.com.cn/install | bash
```

要持久化就写进 `~/.zshrc`（官方也支持"使用系统全局环境变量中的网络代理"）。
脚本方式不通时**换 npm 方式再试一次**（`npm install -g @qodercn-ai/qoderclicn`）——两者走的网络路径不同。

**第三步：都装不上就降级——四档阶梯。**

| 档 | 做法 | 你能得到 | 你要接受 |
|:--:|:--|:--|:--|
| 1 | CLI 装得上 → `qoderclicn --worktree` | 真并行 + `/tasks` 统一面板 + Quest | — |
| 2 | 配好代理后 CLI 装上 | 同上 | 多一步代理配置 |
| 3 | **CLI 完全装不上** → `git worktree` + **IDEA 多开窗口** | **同样是一任务一 worktree、天然隔离** | 没有统一任务面板，窗口间自己切；每个 IDEA 实例约 1–2 GB 内存 |
| 4 | 连多开都不方便 → `git worktree` + 单窗口 | 目录隔离：**暂停 A 去做 B 不用 stash 半成品** | 不并行，但上下文不丢 |

**第 3 档是真正的底线，而它零网络依赖。** `git worktree` 是 git 自带的；
IDEA 多开窗口各自索引独立目录，插件在每个窗口里只作用于自己那个目录
——**等价于「一会话一分支」**。没有 `/tasks` 面板，用一张纸或一个 md 记任务状态就替代了。

> 换句话说：**网络权限决定你用不用得上 Qoder 的并行编排，不决定你能不能并行。**

## 四、一次性配置（仓库级）

### 4.1 `.gitignore` 加两行

worktree 目录和本机个人配置都不该进版本库：

```gitignore
.qoder/worktrees/
.qoder/settings.local.json
```

### 4.2 `.worktreeinclude`（可选，但建议先配上）

新 worktree 是**干净检出**：`.env` 这类被 gitignore 的本地文件**不会带过去**，
任务会因为读不到配置而失败。

在**仓库根**建 `.worktreeinclude`：

```text
.env
.env.local
config/secrets.json
```

规则：**只有「匹配且已被 git 忽略」的文件才会被复制**。
`.qoder/settings.local.json` 存在时会自动复制，无需列。

> ⚠️ **待实测**：CN 版文档未收录 `.worktreeinclude`（国际版有）。
> 先按此配置，若发现不生效，改用 §十二 坑清单第 3 条的手工复制。

### 4.3 权限：先把「信任目录」搞清楚（**最容易踩的坑**）

Qoder CN CLI 把**启动时的当前工作目录（CWD）**当作主信任目录，且明确规定：

> **非默认权限模式（accept_edits / auto / bypass 等）只在可信目录中生效；
> 如果当前目录不被信任，CLI 会强制回退到 `default` 模式。**

而**受保护路径**包括 `.git`、`.idea`、`.vscode`、**大多数 `.qoder` 配置文件**等
（常规模式下需明确批准，`auto` 模式下直接拒绝）。

于是两种起法的信任状况完全不同：

| 起法 | 信任状况 |
|:--|:--|
| `qoderclicn --worktree foo`（从主仓库起） | worktree 落在 `.qoder/worktrees/` 下，**贴近受保护路径**，需实测 |
| `git worktree add ../proj-foo` + `cd` 进去再起 | 新目录独立，**启动时它就是信任目录**，最干净 |

**推荐后者**（§五），理由：信任关系清晰、路径可控、且能直接用已有的工单分支。

补充手段：

```bash
qoderclicn --add-dir ../shared        # 增加额外可信目录
```

### 4.4 权限模式选择

```bash
qoderclicn --permission-mode accept_edits   # 日常编码：自动批准工作区内安全编辑
qoderclicn --permission-mode dont_ask       # headless：从不询问，原需询问的一律拒绝
qoderclicn --permission-mode auto           # 自主执行：零弹窗，风险动作被拒或交 AI 分类器
```

> 🚫 **不要用 `--yolo`**（等价 `bypass_permissions`，跳过所有批准）。
> 串行时你盯着，YOLO 尚可控；**并行时你不在场，YOLO × N 就是风险 × N**。
> 交互态下 `Shift+Tab` 循环切换权限模式，`Ctrl+Y` 直达 YOLO——**别按**。

### 4.5 「多下几份源码、开多个 IDEA」可行，但把 clone 换成 worktree

**先补一句 worktree 到底是什么**（没用过看这个就够）：

> 平时你只有一个仓库目录，切分支相当于**把桌面收干净、再摊开另一摊活**——同一时刻只能待在一个分支上。
> `git worktree` 让你**把同一个仓库同时摊成好几个目录**，每个目录待在不同分支上；
> 它们**共用同一份 `.git` 历史**，所以不像 clone 那样占 N 份磁盘。
> 在 A 目录提交，B 目录立刻能看到。

```bash
git worktree add ../proj-task-a feature-a   # 开一个新目录，检出 feature-a
git worktree list                           # 看现在摊开了哪些
git worktree remove ../proj-task-a          # 收掉一个
```

**日常就这三条，其余用不到。**

这个土办法**方向完全正确**——多目录 + 多 IDEA 就是并行，而且零审批、不依赖 CLI。
只建议把 `git clone` 换成 `git worktree`，因为**省磁盘 + 后面合并少受罪**：

| | `git clone` × N | `git worktree` × N |
|:--|:--|:--|
| 磁盘 | 每份一份完整 `.git` 历史 | **共享同一个 `.git`**，只多工作区文件 |
| 拉取 | 各自 fetch，容易漂 | fetch 一次，全体可见 |
| **把任务 A 的改动合进 B** | 得推远端，或 `git remote add` 互相拉 | **本地 `git merge` / `cherry-pick` 一条命令** |
| 分支归属 | 每份各自 checkout，互不知情 | 同一仓库的分支，全局可见 |
| 误操作风险 | **容易在两份里都留在 main 上改**，产生两个"不同"的 main | git 直接拒绝同一分支在多个 worktree 同时 checkout |

**最后两行才是关键**：clone 多份之后，它们对 git 来说是**两个陌生仓库**，
合并只能绕远路；而 worktree 是**同一个仓库**，合并全在本地完成，不经过远端。

```bash
git worktree add ../proj-task-a <工单分支A>
git worktree add ../proj-task-b <工单分支B>
```

比 clone 还快（不用重新下载），然后**每个目录开一个 IDEA**即可。

**但要留出三笔预算**（clone 和 worktree 都躲不掉）：

| 项 | 说明 |
|:--|:--|
| 内存 | 每个 IDEA 约 1–2 GB；开 3 个窗口就是 3–6 GB |
| 索引与构建 | 每个目录各自索引、各自 `target/`，CPU 和磁盘占用翻倍 |
| **依赖仓库锁** | `~/.m2` / `~/.gradle` 是共享的——**多个 IDEA 同时构建可能撞锁**，错峰构建 |

> 好消息：`~/.m2`、`~/.gradle` 共享是**对的**，不必准备 N 份依赖缓存；只需注意别同时构建。

## 五、实操 A：挂已有工单分支（推荐路径）

你的分支往往是**工单驱动、已经存在**的。而 `--worktree` 是针对**新建临时分支**设计的，
所以要挂已有分支，用 git 原生方式：

```bash
# 在主仓库里
git fetch origin
git worktree add ../<项目名>-<任务短名> <已有分支名>
cd ../<项目名>-<任务短名>
qoderclicn
```

三步的好处：worktree 独立在仓库外、当前目录天然是信任目录、用的就是工单分支本身。

## 六、实操 B：Qoder 托管 worktree（快速起量）

```bash
cd <仓库根>
git fetch origin                        # 必须先 fetch，否则可能基于旧提交
qoderclicn --worktree login-fix "修复登录失败问题"
```

行为要点：

- 名字可含字母、数字、点、下划线、连字符和 `/`（`/` 会转成 `+`），最长 256 字符
- **同名直接复用**（不重建）——相当于「回到那个任务」
- 不传名字会自动生成
- 基线是本地 `origin/HEAD` 指向的默认分支；未配置则尝试 `origin/main`，再退回当前 `HEAD`
- 结束时 CLI 打印 worktree 路径和恢复命令：`cd <worktree-path> && qoderclicn --resume <session-id>`

开第二条线（另一个终端）：

```bash
qoderclicn --worktree log-refactor "重构日志模块"
```

会话中途也能让 CLI 进入隔离 worktree 处理任务（子代理同样支持 `isolation: worktree`）。

> ⚠️ **待实测**：CN 版文档**未写明**落盘位置与临时分支命名
> （国际版是 `<repo>/.qoder/worktrees/<name>` + 分支 `worktree-<name>`）。
> 第一次跑完请记到 §十一 的记录表里。

## 七、实操 C：日常循环（你扮演的角色）

1. **派发**：从待办挑一个**能独立跑**的任务，起一个 worktree
2. **离开**：不要盯着——去干别的（这正是「不再等待」的意义）
3. **决策**：任务卡住会停下来等你；用 `/tasks` 或对应终端查看
4. **收敛**：完成后各自 `commit` → 合并到测试分支
5. **清理**：见 §九

### 并行度：从 2 条开始，不要一上来 5 条

加到 3 条之前，先看三个信号：

| 信号 | 健康值 | 不健康说明 |
|:--|:--|:--|
| 每天被打断次数 | ≤ 5 | 太多 → 并行度过高或任务切得太碎 |
| 合并时冲突量 | 可接受 | 冲突多 → 任务边界没切干净 |
| 有没有线一直挂着没人管 | 没有 | 有 → 你其实只需要 1–2 条 |

### 任务分级（决定能不能并行）

| 任务类型 | 能并行 | 原因 |
|:--|:--:|:--|
| 纯重构 / 写测试 / 改文档 / 加日志 | ✅ | 不依赖共享运行环境 |
| 改配置 / 小范围修 bug | ✅ | 同上 |
| 要起服务、连库、占端口验证 | ⚠️ | 多个 worktree 抢同一套环境，先解决环境隔离 |
| 改公共模块 / 被多处引用的依赖 | ⚠️ | 冲突面大，收敛成本高 |
| 需要人反复试错的 UI 调试 | ❌ | 你的注意力就是瓶颈，并行无收益 |

## 八、工具形态：IDEA 主刀台 + 手机 / Web 看板

### 8.1 IDEA 侧（主刀台）

**结论：日常编码留在 IDEA，并行任务交给 CLI。**

JetBrains 插件（截至官方 2026-09-11 更新日志）**没有 Quest、没有 worktree 隔离**——
它是 Chat/Agent + 多会话标签页，**始终作用于当前打开的项目目录**。
所以「插件里多开几个会话」并不产生并行，只会在同一份文件上互相干扰。

| 场景 | 怎么做 |
|:--|:--|
| 日常编码 / Java 重构 / 调试 / Database | **留在 IDEA**，不换工具 |
| 起并行任务 | 终端里 `qoderclicn` |
| 想看某条线的代码 | IDEA `File > Open` 那个 worktree 目录（识别为同一仓库的 worktree，共享 `.git`） |
| 从 IDE 拉起 CLI | 插件 **0.18.1 起内置 CLI 集成**（自动安装、版本检测、远端会话管理）；0.19.0 起还能把选中文本发给 CLI 会话 |

> **待你自查**：公司装的插件版本面板里有没有 Quest / 任务列表。
> 若有，说明插件已追平，就不必开 Qoder CN 原生 IDE；若没有，按上表分工。

### 8.2 官方看板：手机 / Web 远程控制（**需公司许可**）

TUI 的短板是「每终端一会话、没有全局视图」——而你真正需要的是
**「哪个任务卡住了在等我」**。官方给的答案不是桌面面板，而是**远程控制**。

两种模式（[官方文档](https://help.aliyun.com/zh/lingma/remote-control)）：

| 模式 | 启动方式 | 适合 |
|:--|:--|:--|
| **远程控制模式** | 已有会话里输入 `/remote-control` | 已经开了会话，临时离开但要盯着 |
| **守护进程模式**（Daemon） | 项目目录直接跑 `qoderclicn remote-control`（**不必先开会话**） | 电脑待命，随时从手机派新任务 |

两者启动后都显示**二维码 + URL**，手机扫码或浏览器打开即连上。连上后可以：

- 实时查看 CLI 运行状态
- **对需要审批的操作做交互** ← 这就是「卡住了你去决策」
- 从手机发新任务；**守护进程模式下不必等第一个任务完成就能发第二个，每个任务独立显示状态和结果**

| 用途 | 入口 |
|:--|:--|
| Web 端管理远程任务 | <https://qoder.com.cn/agents> |
| 查连接状态 | `/remote-control status` |
| 停止远程控制 | `/remote-control stop` |

**为什么这条比「找个好看的界面」更重要**：
它把「人从执行者变成决策者」这半截补完了——
你本来就必须离开电脑（那正是「等待时间」的由来），
现在**离开也能决策**，决策不再要求你回到那个终端前面。
**你想要的那个队列，官方叫它移动端任务列表。**

> ⚠️ **前提**：两种模式都要求**本地电脑保持活跃**（不能休眠）。
> macOS 上先把「系统设置 → 锁定屏幕」的休眠时间调长，或用 `caffeinate -s` 兜住——
> 否则人走了、Mac 睡了，任务和看板一起停。

> ⚠️ **这条需要公司许可**：远程控制属于企业版可管控项
> （**Organization / Mobile & Web**，Teams/Enterprise 计划下管理员可分别关掉 Mobile 与 Web。
> **两个开关默认是开的**，所以别凭感觉断定「不行」——去看一眼或问管理员，值一次确认）。
> 若确实被关，走 §8.3 的纯本地方案。

> ⚠️ **待实测**：Web 端 `qoder.com.cn/agents` 与移动端是否受公司内网/合规限制
> （CLI 走得通不代表 Web 端也走得通）；以及守护进程模式下「按顺序或并行处理」的实际并发度。

### 8.3 纯本地看板：Hook + 系统通知（**不依赖任何云端，一定可行**）

如果远程控制被管控关掉，还有一条**完全不出口**的路：
用 CLI 自带的 **Hook** + macOS 自带的 `osascript`，自己造出「卡住了叫我」。

Hook 覆盖完整会话生命周期，其中两个事件正好对应你要的状态：

| 事件 | matcher | 触发时机 |
|:--|:--|:--|
| `Notification` | `permission` | **权限请求——任务卡住了，等你决策** |
| `Notification` | `result` | Agent 产出结果——任务干完了 |
| `PermissionRequest` | 工具名 | 需要授权时（更细，可按工具过滤） |
| `Stop` | — | 主 Agent 停止响应（`exit 2` 可让它继续干） |

> 前提：需要 `jq`（解析 Hook 的 stdin JSON）。没有就先装。

**① 一行通知（最小实现）**

```bash
mkdir -p ~/.qoder-cn/hooks
cat > ~/.qoder-cn/hooks/notify.sh << 'EOF'
#!/bin/bash
input=$(cat)
cwd=$(echo "$input" | jq -r '.cwd')
title=$(echo "$input" | jq -r '.title // "Qoder CN CLI"')
message=$(echo "$input" | jq -r '.message')
task=$(basename "$cwd")                    # worktree 目录名 = 哪条任务线
osascript -e "display notification \"$message\" with title \"$task · $title\" sound name \"Ping\""
exit 0
EOF
chmod +x ~/.qoder-cn/hooks/notify.sh
```

写进 `~/.qoder-cn/settings.json`（三级配置会**合并**，用户级对所有项目生效）：

```json
{
  "hooks": {
    "Notification": [
      { "hooks": [ { "type": "command", "command": "~/.qoder-cn/hooks/notify.sh" } ] }
    ]
  }
}
```

**关键设计**：通知标题里带 **worktree 目录名**——你一眼就知道**是哪条任务线卡住了**。
这已经是最小可用的「待决策队列」。

**② 顺带落一个本地看板（可选）**

在上面脚本里追加一行，把事件写进日志：

```bash
printf '%s\t%s\t%s\t%s\n' "$(date +%H:%M:%S)" \
  "$(echo "$input" | jq -r '.notification_type')" "$task" "$message" >> ~/.qoder-cn/board.log
```

然后：

```bash
tail -20 ~/.qoder-cn/board.log                    # 任务流水
watch -n 5 'tail -20 ~/.qoder-cn/board.log'       # 挂一个实时面板
```

**为什么这条一定可行**：只用 ① CLI 自带 Hook、② 系统自带 `osascript`、③ 本地文件。
**零网络、零云端、零新增审批**——完全在内网边界之内。

> 注意：Hook 是**每次触发都跑一遍脚本**。
> 通知类只挂 `Notification`（频率低），不要为了看板去挂 `PreToolUse`/`PostToolUse`——那会拖慢每个任务。

## 九、收尾与清理

```bash
git worktree list                      # 看全部 worktree
git worktree remove <worktree-path>    # 删单个（有未提交改动会被拒绝）
git worktree prune                     # 清理已被手工删除目录的元数据
git branch -d worktree-<name>          # 删 Qoder 托管产生的临时分支
```

> ⚠️ **不要用 `rm -rf` 删 worktree 目录**——要用 `git worktree remove` / `prune`，
> 否则 `.git/worktrees/` 里会残留元数据。

## 十、验证清单（怎么知道这套真的成立）

第一次实验逐条打勾：

- [ ] `qoderclicn --version` 有输出
- [ ] `git worktree list` 能看到新建的 worktree
- [ ] **两条线的改动互不可见**：在 A 里改文件，B 里 `git status` 干净
- [ ] 每条线能独立 `git commit`
- [ ] 任务卡住时，你**确实**收到了能看懂的「要我决策什么」
- [ ] 合并到测试分支时，冲突量在可接受范围
- [ ] 一天的账：被打断次数 / 合并耗时 / 总产出 vs 串行

> **只要第 5 条不成立，并行就是负收益**——
> 你只是从「等 AI」变成「等自己理解 AI 卡在哪」。
> 这是目前所有工具都做得最糙的一环，也是最该盯的一条。

## 十一、待实测记录（第一次跑完填写）

| 待确认项 | 预期（国际版行为） | 实测结果 |
|:--|:--|:--|
| worktree 落盘位置 | `<repo>/.qoder/worktrees/<name>` | （待填） |
| 临时分支命名 | `worktree-<name>` | （待填） |
| `.worktreeinclude` 是否生效 | 生效（复制被忽略的 .env） | （待填） |
| 在 worktree 内权限模式是否生效 | 生效（CWD 为信任目录） | （待填） |
| JetBrains 插件是否有 Quest | 无（截至 2026-09-11） | （待填） |

## 十二、坑清单

| # | 坑 | 现象 | 处置 |
|:--:|:--|:--|:--|
| 1 | 命令名抄成国际版 | `qoder: command not found` | CN 版是 `qoderclicn` |
| 2 | 信任目录不对 | 权限模式不生效，一直弹确认 | 进到 worktree 目录里再启动，或 `--add-dir` |
| 3 | 缺 `.env` 等本地文件 | 任务跑不起来，报配置缺失 | 配 `.worktreeinclude`，或手工复制 |
| 4 | 基线落后 | 基于旧提交开分支 | 起之前先 `git fetch origin` |
| 5 | 依赖重复 | 每个 worktree 一份依赖/构建缓存 | 磁盘与时间成本，按需清理 |
| 6 | 合并冲突暴增 | 并行后冲突从顺手变成开会 | 任务边界切小；冲突交给 AI，**取舍由你定** |
| 7 | 一次开太多 | 你从决策者变成人肉调度器 | 回到 2 条 |
| 8 | 用了 YOLO | 无人值守下的破坏性操作 | **永不用 `--yolo`** |
| 9 | 越界用非批准能力 | 触碰公司合规口径 | 只用公司已开通的 Qoder CN 能力，不引入外部服务 |
| 10 | **内网无外网权限** | 安装脚本 / 二进制下不来 | 抄 IDEA 的代理地址 → `HTTPS_PROXY` 后重试；仍不行走 **§3.4 阶梯 3**（零网络依赖） |
| 11 | **Mac 休眠** | 人离开后任务与手机看板一起停 | 调长休眠时间，或用 `caffeinate -s`（见 §8.2） |

## 十三、速查

| 目标 | 命令 |
|:--|:--|
| 安装 | `curl -fsSL https://qoder.com.cn/install \| bash` |
| 配代理（抄 IDEA 的 HTTP Proxy Settings） | `export HTTPS_PROXY="http://<host>:<port>"` |
| 登录 | `qoderclicn` → `/login`（PAT: <https://qoder.com.cn/account/integrations>） |
| 起并行任务 | `qoderclicn --worktree <name> "<任务描述>"` |
| 挂已有分支 | `git worktree add ../x <branch> && cd ../x && qoderclicn` |
| 看后台任务 | `/tasks` |
| **手机/Web 看板** | 会话内 `/remote-control`；或直接 `qoderclicn remote-control`（守护进程）→ 扫码 |
| 远程任务管理（Web） | <https://qoder.com.cn/agents> |
| **本地通知看板**（零云端，§8.3） | 配 `Notification` Hook → `~/.qoder-cn/hooks/notify.sh` |
| Hook 配置位置 | `~/.qoder-cn/settings.json` / `<project>/.qoder/settings.json` / `.qoder/settings.local.json` |
| 恢复会话 | `qoderclicn --resume <session-id>` 或 `qoderclicn -c` |
| 指定工作目录 | `qoderclicn -w <dir>` |
| 看额度 | `/usage` |
| 看状态 | `/status` |
| 升级 | `qoderclicn update` |

### CN 版 vs 国际版（照抄教程翻车高发区）

| 项 | CN 版 | 国际版 |
|:--|:--|:--|
| 命令 | `qoderclicn` | `qoder` |
| 配置目录 | `~/.qoder-cn/` | `~/.qoder/` |
| 安装脚本 | `qoder.com.cn/install` | `qoder.com/install` |
| 官方文档 | `help.aliyun.com/zh/lingma` | `docs.qoder.com` |

### 官方文档（事实来源）

- 快速上手（安装 / 登录 / 升级）：<https://help.aliyun.com/zh/lingma/qoder-cli-cn-get-started-quickly>
- 使用 CLI（Worktree / 权限 / 记忆 / 子代理）：<https://help.aliyun.com/zh/lingma/using-the-cli>
- 国际版 Worktree 细节（更详细，可对照参考）：<https://docs.qoder.com/cli/run-tasks>
- JetBrains 插件更新日志（核实插件能力边界）：<https://docs.qoder.com/release-notes/jetbrains-plugin>
