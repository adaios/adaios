#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 发版单元映射（唯一真相源）：「哪些路径被改了」→「要发布哪几端」
#
# 为什么单抽成一个文件（2026-09-24 用户「接下来需要你判定前后端是否发布，
# 我建议整理个机制，知道哪些需要生产发布」）：
#   这条映射原先散在三处——deploy-gate.sh 算 artifacts、guard-prod.sh 读 artifacts 判落后、
#   deploy.sh 注释里说明。要加 iOS 端时无处可加，于是出现「app 改了 7 个文件，
#   却不在任何发布清单里，只能靠人记」。
#   现在统一从这里取；将来新增发布单元（如 Android 包）只改本文件。
#
# 用法（都是纯函数：stdin 读路径列表，stdout 出结果；不 ssh、不碰 git、不打印）：
#   . ai-engineering/lib/release-units.sh
#   printf '%s\n' "$CHANGED" | ru_units_from_paths             # → backend / web / admin / app（每行一个）
#   printf '%s\n' "$CHANGED" | ru_deploy_artifacts_from_paths  # → backend,web（DEPLOYED 的 artifacts= 用）
#
# 约定：不用 `cmd && var=x` 这种写法——set -e 下条件为假会让整条语句非零、误杀脚本。
# ─────────────────────────────────────────────────────────────

# 全部发布单元（顺序固定：先后端，再服务器静态端，最后 iOS 包）
RU_ALL_UNITS=(backend web admin app)

# 单元 → 仓库路径前缀
ru_path_prefix() {
    case "${1:-}" in
        backend) printf 'services/adai-core\n' ;;
        web)     printf 'apps/adai-web\n' ;;
        admin)   printf 'apps/adai-admin\n' ;;
        app)     printf 'apps/adai-app\n' ;;
        *)       return 1 ;;
    esac
}

# 单元 → 人话名（输出层用）
ru_unit_label() {
    case "${1:-}" in
        backend) printf '后端\n' ;;
        web)     printf 'Web 桌面端\n' ;;
        admin)   printf '管理后台\n' ;;
        app)     printf 'iOS App\n' ;;
        *)       printf '%s\n' "${1:-}" ;;
    esac
}

# 单元 → 发布方式（人话，输出层用）
ru_unit_how() {
    case "${1:-}" in
        backend) printf 'jar → deploy-gate.sh\n' ;;
        web)     printf 'flutter build web → tar 原子替换 /opt/adaios/web\n' ;;
        admin)   printf 'flutter build web → tar 原子替换 /opt/adaios/admin\n' ;;
        app)     printf 'TestFlight 构建（不落服务器）\n' ;;
        *)       printf '?\n' ;;
    esac
}

# stdin=改动路径 → stdout=涉及的发布单元（每行一个，仅在确实改到该端时出现）
ru_units_from_paths() {
    local paths u prefix
    paths="$(cat)"
    for u in "${RU_ALL_UNITS[@]}"; do
        prefix="$(ru_path_prefix "$u")"
        if printf '%s\n' "$paths" | grep -q "^${prefix}/"; then
            printf '%s\n' "$u"
        fi
    done
}

# stdin=改动路径 → stdout=服务器端清单（逗号分隔，供 DEPLOYED 的 artifacts= 用）
# `backend` 恒在清单内：部署动作本身就是替换后端 jar（哪怕本批没改后端代码，
# 生产的 jar 时刻也已经动了）——这与 guard-release 的「后端要不要发」判定不是一回事。
# 不含 app：iOS 包走 TestFlight、不落生产目录，巡检不该拿它判「产物落后」。
ru_deploy_artifacts_from_paths() {
    local units u out="backend"
    units="$(ru_units_from_paths)"
    for u in web admin; do
        if printf '%s\n' "$units" | grep -qx "$u"; then
            out="${out},${u}"
        fi
    done
    printf '%s\n' "$out"
}
