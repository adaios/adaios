#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 午间谷时任务壳（工作日 12:01 触发）— 2026-09-16 建
#
# 为什么卡 12:01：
#   DeepSeek 峰谷定价（api-docs.deepseek.com/zh-cn/quick_start/pricing）
#   高峰 = 北京时间周一至周五 9:00-12:00、14:00-18:00；其余为空闲时段（半价）。
#   12:00-14:00 是工作日唯一的日间谷时窗口，全长 2 小时。
#   用 12:01 而非 12:00 整，避开峰谷切换那一秒的边界。
#
# ⚠️ 窗口 14:00 就结束：跨过 14:00 的请求按高峰价（2 倍）计费。
#     长任务请改用整晚谷（工作日 18:00-次日 9:00）或周末全天，别硬塞进午间。
#
# 触发方式（2026-09-16 起）：
#   LaunchAgent `com.adai.adaios-noon-task`（周一至周五 12:01）
#   日志 ai-engineering/state/noon-task.log
#   重装：bash scripts/setup-launchd.sh
#   自检：bash scripts/setup-launchd.sh --check
#
# 用法:  bash ai-engineering/noon-task.sh [--force]
#        --force = 跳过空闲时段闸门（手工补跑时用，会按高峰价计费）
#
# 加任务：把可执行 .sh 丢进 ai-engineering/noon-task.d/（按文件名顺序执行）。
#         壳只负责「到点 + 闸门 + 留痕」，任务内容与壳解耦——加任务不用动壳。
# ─────────────────────────────────────────────────────────────
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
HOOK_DIR="${ROOT}/ai-engineering/noon-task.d"
FORCE="${1:-}"

# 时钟来源：默认读本机钟；NOON_FAKE_* 可注入，供闸门矩阵测试（launchd 到点才跑，无法单测）
HOUR="${NOON_FAKE_HOUR:-$(date +%H)}"
MIN="${NOON_FAKE_MIN:-$(date +%M)}"
DOW="${NOON_FAKE_DOW:-$(date +%u)}"        # 1=周一 … 7=周日
HHMM=$(( 10#${HOUR}${MIN} ))
OFFSET="$(date +%z)"
MIN_LEFT=$(( 14 * 60 - (10#${HOUR} * 60 + 10#${MIN}) ))

echo "═══ 午间谷时任务（$(date '+%Y-%m-%d %H:%M:%S')）═══"

# 峰谷口径是北京时间；本机若不在 +0800，下面的闸门判定就不可信了
if [ "${OFFSET}" != "+0800" ]; then
    echo "⚠️ 本机时区偏移 ${OFFSET} ≠ +0800（北京时间）：峰谷判定按本机钟走，请核对"
fi

if [ "${MIN_LEFT}" -lt 0 ]; then
    echo "▸ 窗口：工作日 12:00-14:00 谷时（半价）· 当前已在窗口外"
else
    echo "▸ 窗口：工作日 12:00-14:00 谷时（半价）· 本次剩余约 ${MIN_LEFT} 分钟"
fi

# ── 空闲时段闸门（安全阀）────────────────────────────────────
# 目的：宁可漏跑，也不在高峰时段（2 倍价）偷偷花钱。
# 工作日高峰 = 9:00-12:00 与 14:00-18:00；周末全天为谷时。
in_peak=0
if [ "${DOW}" -le 5 ]; then
    if [ "${HHMM}" -ge 900 ] && [ "${HHMM}" -lt 1200 ]; then in_peak=1; fi
    if [ "${HHMM}" -ge 1400 ] && [ "${HHMM}" -lt 1800 ]; then in_peak=1; fi
fi

if [ "${in_peak}" -eq 1 ] && [ "${FORCE}" != "--force" ]; then
    echo "⛔ 当前是高峰时段（${HOUR}:${MIN}），按纪律跳过本次执行"
    echo "   手工补跑：bash ai-engineering/noon-task.sh --force"
    exit 0
fi

if [ "${in_peak}" -eq 1 ]; then
    echo "⚠️ --force：在高峰时段强制执行（将按 2 倍价计费）"
fi

# ── 任务体：钩子目录（任务与壳解耦）──────────────────────────
START_TS="$(date +%s)"
ran=0

# 注意：macOS /bin/bash 是 3.2，空数组 + set -u 会报 unbound variable，
#       所以这里用 glob 直接循环 + [ -e ] 兜底，不落数组。
for hook in "${HOOK_DIR}"/*.sh; do
    [ -e "${hook}" ] || continue
    if [ ! -x "${hook}" ]; then
        echo "   ⏭ 跳过（不可执行）: $(basename "${hook}")"
        continue
    fi
    echo "   ▸ 执行钩子: $(basename "${hook}")"
    if bash "${hook}"; then
        echo "     ✅ 成功"
    else
        rc=$?
        echo "     ❌ 失败（退出码 ${rc}，不中断后续钩子）"
    fi
    ran=$(( ran + 1 ))
done

if [ "${ran}" -eq 0 ]; then
    echo "▸ 无任务钩子（noon-task.d/ 为空）—— 壳已通，任务待填"
    echo "   加任务：把可执行 .sh 放进 ai-engineering/noon-task.d/，无需改壳"
fi

echo "▸ 完成，耗时 $(( $(date +%s) - ${START_TS} ))s"
