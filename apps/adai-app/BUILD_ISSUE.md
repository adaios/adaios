# Gradle 构建问题记录

## 错误信息

```
Error resolving plugin [id: 'dev.flutter.flutter-plugin-loader', version: '1.0.0']
> Build was configured to prefer settings repositories over project repositories
> but repository 'maven' was added by settings file 'settings.gradle.kts'
```

## 环境

| 项目 | 版本 |
|---|---|
| Flutter | 3.44.6 (stable) |
| Dart | 3.12.2 |
| AGP | 9.0.1 |
| Kotlin | 2.3.20 |
| Gradle | 尝试过 9.3.0 / 9.1.0 / 8.13，均失败 |

## 问题现象

执行 `flutter build apk --debug` 时，Gradle 在 settings 阶段解析 `dev.flutter.flutter-plugin-loader` 插件失败，报错仓库验证冲突。`settings.gradle.kts` 已恢复为 Flutter SDK 官方模板，无额外修改。

## 尝试过的方案

| # | 操作 | 结果 |
|---|---|---|
| 1 | `FAIL_ON_PROJECT_REPOS` → `PREFER_SETTINGS` | ❌ |
| 2 | 移除整个 `dependencyResolutionManagement` 块 | ❌ |
| 3 | 移除阿里云自定义 Maven 镜像 | ❌ |
| 4 | 移除 `gradlePluginPortal()` | ❌ |
| 5 | 移除 `pluginManagement.repositories`，仅保留 `includeBuild` | ❌ |
| 6 | Gradle 9.3.0 → 9.1.0 → 8.13（依次降级） | ❌ 全部同错 |

## 错误堆栈（关键部分）

```
DefaultArtifactRepositoryContainer.configure()
  → DefaultRepositoryHandler.maven()
    → addRepository()
      → addWithUniqueName()
        → InvalidUserCodeException: "prefer settings repositories"
```

## 根因分析

Flutter SDK 的 included build（`$FLUTTER_ROOT/packages/flutter_tools/gradle/settings.gradle.kts`）内部声明了：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
```

主项目的 `settings.gradle.kts` 通过 `includeBuild(...)` 引入该构建。当 Gradle 处理主项目的 `plugins` 块、解析 `dev.flutter.flutter-plugin-loader`（版本 1.0.0）时，该插件虽由 included build 提供，但 Gradle 在解析过程中触发了 included build 内部的仓库验证逻辑，导致冲突。

核心矛盾：
- **Flutter SDK 不能被修改**（属于外部依赖）
- **主项目的 settings 已对齐官方模板**，问题依然存在
- **Gradle 多个版本（8.13 ~ 9.3）均表现一致**，非版本特定 bug

## 当前状态

- `settings.gradle.kts`：Flutter SDK 官方模板（无自定义内容）
- `gradle-wrapper.properties`：Gradle 8.13
- 构建无法通过，阻塞 Android 端开发

## 待尝试方向

- 使用 `pluginManagement.resolutionStrategy` 绕过仓库冲突
- 直接修改 Flutter SDK 内的 `settings.gradle.kts`（需谨慎评估）
- 回退 AGP / Kotlin / Gradle 到更早的兼容组合
- 创建全新 Flutter 项目对比构建配置
