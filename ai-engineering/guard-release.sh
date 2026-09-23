#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 发版体检（发布前随时问一句：现在欠着什么没发）
#
# 用法:
#   bash ai-engineering/guard-release.sh          # 人话三段
#   bash ai-engineering/guard-release.sh --json   # 原始 JSON（喂 AI / 二次处理）
#
# 为什么有这个脚本（2026-09-24 用户「接下来需要你判定前后端是否发布，
# 我建议整理个机制，知道哪些需要生产发布」）:
#   2026-09-23 的发版清单（REVIEW P2-工程9）只在**部署那一刻**算一次、写进生产 DEPLOYED，
#   事后由 guard-prod 巡检核对——于是「现在欠着什么没发」在部署之前根本答不出来：
#   要么先跑一次部署，要么去几百行巡检输出里翻红字。
#   而且那份清单只有 backend/web/admin，**iOS 包完全不在机制内**（app 改了要出新
#   TestFlight 构建，只写在 status.md 散文里）。
#
# 与既有机制的分工（共用同一份路径映射 ai-engineering/lib/release-units.sh）:
#   guard-release  发布前：现在欠什么（本脚本，**只读**，不碰生产）
#   deploy-gate    发布时：算 artifacts 落 DEPLOYED + 门禁 + smoke
#   guard-prod     发布后：巡检核对「声明要发的端，产物是否真的更新了」
#
# 判定基线：生产 DEPLOYED 里的 commit（本地查不到该 commit 时退回「部署时刻」，
# 两者都拿不到才保守按全部文件判定——宁可多报一端，不静默漏发）。
#
# 依赖: ssh 免密到生产（ubuntu@ + ~/.ssh/id_ed25519）、生产侧免密 sudo
# ─────────────────────────────────────────────────────────────
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}/.."
. "${SCRIPT_DIR}/lib/release-units.sh"

HOST="${ADAI_PROD_HOST:-ubuntu@82.156.111.146}"
PROD_IP="${HOST##*@}"
SSH_KEY="${ADAI_PROD_KEY:-$HOME/.ssh/id_ed25519}"
JSON_ONLY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --json)    JSON_ONLY=1; shift ;;
        -h|--help) sed -n '3,23p' "$0"; exit 0 ;;
        *) echo "未知参数: ${1}（--help 看用法）" >&2; exit 2 ;;
    esac
done

# 生产是公网 IP + 域名，本机代理会拦，显式绕过
export no_proxy="82.156.111.146,adaiadai.com,api.adaiadai.com,${no_proxy:-localhost}"

# ── ① 生产现状（只读）──
DEPLOYED_RAW="$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o BatchMode=yes \
    "$HOST" "sudo cat /opt/adaios/backend/DEPLOYED 2>/dev/null" 2>/dev/null)"

dep_field() {
    printf '%s\n' "$DEPLOYED_RAW" | grep "^${1}=" | head -1 | cut -d= -f2-
}
PROD_SHA="$(dep_field commit)"
PROD_AT="$(dep_field deployedAt)"
PROD_SUBJECT="$(dep_field commitSubject)"
PROD_ARTIFACTS="$(dep_field artifacts)"
PROD_DIRTY="$(dep_field dirtyFiles)"
PROD_REACHABLE=1
if [ -z "$PROD_SHA" ]; then PROD_REACHABLE=0; fi

HEAD_SHA="$(git rev-parse HEAD 2>/dev/null)"
HEAD_SHORT="$(git rev-parse --short HEAD 2>/dev/null)"
HEAD_AT="$(git log -1 --date=format:'%Y-%m-%d %H:%M' --format='%ad' 2>/dev/null)"
UNPUSHED="$(git rev-list --count origin/main..HEAD 2>/dev/null | tr -d ' \n')"
if [ -z "$UNPUSHED" ]; then UNPUSHED="?"; fi

# UTC → 北京时间（脚本里只有这一处需要日期运算，直接借 python3）
PROD_AT_CN="$PROD_AT"
if [ -n "$PROD_AT" ]; then
    PROD_AT_CN="$(python3 -c 'import datetime, sys
try:
    t = datetime.datetime.strptime(sys.argv[1], "%Y-%m-%dT%H:%M:%SZ")
    print((t + datetime.timedelta(hours=8)).strftime("%Y-%m-%d %H:%M"))
except Exception:
    print(sys.argv[1])' "$PROD_AT" 2>/dev/null)"
fi

# ── ② 判定基线 → 本批改动 ──
BASE_KIND="none"
BASE_SHA=""
BASE_LABEL="（拿不到生产记录 → 保守按仓库全部文件判定）"
CHANGED=""
COMMITS="?"
if [ -n "$PROD_SHA" ] && git cat-file -e "${PROD_SHA}^{commit}" 2>/dev/null; then
    BASE_KIND="commit"
    BASE_SHA="$PROD_SHA"
    CHANGED="$(git diff --name-only "${BASE_SHA}..HEAD")"
    COMMITS="$(git rev-list --count "${BASE_SHA}..HEAD")"
    BASE_LABEL="$(printf '%s' "${BASE_SHA}" | cut -c1-7)..HEAD"
elif [ -n "$PROD_AT" ]; then
    BASE_KIND="time"
    CHANGED="$(git log --since="$PROD_AT" --name-only --format= | grep -v '^$' | sort -u)"
    COMMITS="$(git log --since="$PROD_AT" --oneline | wc -l | tr -d ' ')"
    BASE_LABEL="自生产部署时刻 ${PROD_AT_CN} 起的提交（该 commit 本地不存在）"
else
    CHANGED="$(git ls-files)"
fi
FILE_COUNT="$(printf '%s\n' "$CHANGED" | grep -c .)"

# ── ③ 逐端统计（映射规则来自 lib/release-units.sh，不在这里重写）──
U_NAMES=()
U_FILES=()
U_COMMITS=()
U_SUBS=()
UNITS_TSV=""
idx=0
for u in "${RU_ALL_UNITS[@]}"; do
    prefix="$(ru_path_prefix "$u")"
    n="$(printf '%s\n' "$CHANGED" | awk -v p="${prefix}/" 'index($0,p)==1{c++} END{print c+0}')"
    tot="0"
    subs=""
    if [ "$n" -gt 0 ]; then
        if [ "$BASE_KIND" = "commit" ]; then
            subs="$(git log --oneline "${BASE_SHA}..HEAD" -- "${prefix}" | head -3)"
            tot="$(git log --oneline "${BASE_SHA}..HEAD" -- "${prefix}" | wc -l | tr -d ' ')"
        elif [ "$BASE_KIND" = "time" ]; then
            subs="$(git log --oneline --since="$PROD_AT" -- "${prefix}" | head -3)"
            tot="$(git log --oneline --since="$PROD_AT" -- "${prefix}" | wc -l | tr -d ' ')"
        fi
    fi
    U_NAMES[$idx]="$u"
    U_FILES[$idx]="$n"
    U_COMMITS[$idx]="$tot"
    U_SUBS[$idx]="$subs"
    UNITS_TSV="${UNITS_TSV}${u}|$(ru_unit_label "$u")|${n}|${tot}"$'\n'
    idx=$((idx + 1))
done

# 不属于任何发布单元的文件（文档 / 工程规范 / 知识资产）
UNIT_PREFIXES=""
for u in "${RU_ALL_UNITS[@]}"; do
    UNIT_PREFIXES="${UNIT_PREFIXES}$(ru_path_prefix "$u")/ "
done
OTHER_FILES="$(printf '%s\n' "$CHANGED" | awk -v ps="$UNIT_PREFIXES" '
    BEGIN { n = split(ps, arr, " ") }
    { for (k = 1; k <= n; k++) if (index($0, arr[k]) == 1) next }
    NF { c++ }
    END { print c+0 }')"

# iOS 构建号（Apple 拒重复构建号）
APP_BUILD="$(grep -m1 '^version:' apps/adai-app/pubspec.yaml 2>/dev/null | sed 's/.*+//' | tr -d ' \n')"
NEXT_BUILD="?"
if [ -n "$APP_BUILD" ]; then NEXT_BUILD=$((APP_BUILD + 1)); fi

# ── ④ JSON 模式（喂 AI / 二次处理）──
if [ "$JSON_ONLY" = "1" ]; then
    ADAI_REL_UNITS="$UNITS_TSV" \
    ADAI_REL_BASE_KIND="$BASE_KIND" ADAI_REL_BASE_LABEL="$BASE_LABEL" \
    ADAI_REL_COMMITS="$COMMITS" ADAI_REL_FILES="$FILE_COUNT" \
    ADAI_REL_PROD_SHA="$PROD_SHA" ADAI_REL_PROD_AT="$PROD_AT" \
    ADAI_REL_PROD_ARTIFACTS="$PROD_ARTIFACTS" ADAI_REL_PROD_DIRTY="$PROD_DIRTY" \
    ADAI_REL_PROD_REACHABLE="$PROD_REACHABLE" \
    ADAI_REL_HEAD="$HEAD_SHA" ADAI_REL_HEAD_SHORT="$HEAD_SHORT" \
    ADAI_REL_UNPUSHED="$UNPUSHED" ADAI_REL_OTHER="$OTHER_FILES" \
    ADAI_REL_APP_BUILD="$APP_BUILD" ADAI_REL_NEXT_BUILD="$NEXT_BUILD" \
    python3 - <<'JSON_EOF'
import json, os

units = []
for line in os.environ.get('ADAI_REL_UNITS', '').splitlines():
    p = line.split('|')
    if len(p) < 4:
        continue
    units.append({
        'unit': p[0],
        'label': p[1],
        'files': int(p[2]),
        'commits': (None if p[3] == '?' else int(p[3])),
        'needRelease': int(p[2]) > 0,
    })

def _int(name):
    v = os.environ.get(name, '')
    return int(v) if v.isdigit() else None

out = {
    'prod': {
        'reachable': os.environ.get('ADAI_REL_PROD_REACHABLE') == '1',
        'commit': os.environ.get('ADAI_REL_PROD_SHA') or None,
        'deployedAt': os.environ.get('ADAI_REL_PROD_AT') or None,
        'artifacts': os.environ.get('ADAI_REL_PROD_ARTIFACTS') or None,
        'dirtyFiles': _int('ADAI_REL_PROD_DIRTY'),
    },
    'head': {
        'commit': os.environ.get('ADAI_REL_HEAD') or None,
        'short': os.environ.get('ADAI_REL_HEAD_SHORT') or None,
        'unpushed': _int('ADAI_REL_UNPUSHED'),
    },
    'baseline': {
        'kind': os.environ.get('ADAI_REL_BASE_KIND'),
        'label': os.environ.get('ADAI_REL_BASE_LABEL'),
        'commits': _int('ADAI_REL_COMMITS'),
        'files': _int('ADAI_REL_FILES'),
    },
    'units': units,
    'otherFiles': _int('ADAI_REL_OTHER'),
    'appBuildNumber': {'current': _int('ADAI_REL_APP_BUILD'), 'next': _int('ADAI_REL_NEXT_BUILD')},
    'needRelease': [u['unit'] for u in units if u['needRelease']],
}
print(json.dumps(out, ensure_ascii=False, indent=2))
JSON_EOF
    exit 0
fi

# ── ⑤ 人话三段 ──
echo ""
printf '\033[1m═══ 发版体检 %s ═══\033[0m\n' "$(date '+%Y-%m-%d %H:%M')"

printf '\n\033[1m── ① 生产现状 ──\033[0m\n'
if [ "$PROD_REACHABLE" = "1" ]; then
    printf '  生产代码 %s · 部署于 %s（北京）· 当时工作区脏文件 %s\n' \
        "$(printf '%s' "$PROD_SHA" | cut -c1-7)" "$PROD_AT_CN" "${PROD_DIRTY:-?}"
    if [ -n "$PROD_SUBJECT" ]; then
        printf '    %s\n' "$(printf '%s' "$PROD_SUBJECT" | cut -c1-70)"
    fi
    printf '  上批发版清单 %s · 本地 HEAD %s（%s）· 待 push %s 个提交\n' \
        "${PROD_ARTIFACTS:-未记录}" "$HEAD_SHORT" "$HEAD_AT" "$UNPUSHED"
else
    printf '  \033[33m⚠ 取不到生产 DEPLOYED（SSH 不通 / 生产不可达 / sudo 受限）\033[0m\n'
    printf '  本地 HEAD %s（%s）· 待 push %s 个提交\n' "$HEAD_SHORT" "$HEAD_AT" "$UNPUSHED"
    printf '    手工确认: ssh -i %s %s "sudo cat /opt/adaios/backend/DEPLOYED"\n' "$SSH_KEY" "$HOST"
fi

printf '\n\033[1m── ② 判定（基线 %s：%s 个提交 / %s 个文件）──\033[0m\n' \
    "$BASE_LABEL" "$COMMITS" "$FILE_COUNT"
PENDING=""
idx=0
for u in "${RU_ALL_UNITS[@]}"; do
    label="$(ru_unit_label "$u")"
    n="${U_FILES[$idx]}"
    tot="${U_COMMITS[$idx]}"
    if [ "$n" -gt 0 ]; then
        printf '  \033[32m● %s %s\033[0m —— 要发（%s 个文件 · %s 个提交）\n' "$label" "$u" "$n" "$tot"
        printf '      方式：%s\n' "$(ru_unit_how "$u")"
        if [ -n "${U_SUBS[$idx]}" ]; then
            printf '%s\n' "${U_SUBS[$idx]}" | sed 's/^/      /'
        fi
        PENDING="${PENDING}${u} "
    else
        printf '  \033[2m○ %s %s —— 不用发（本批 0 个文件）\033[0m\n' "$label" "$u"
    fi
    idx=$((idx + 1))
done
if [ "$OTHER_FILES" -gt 0 ]; then
    printf '  \033[2m· 其他 %s 个文件（文档 / 工程规范 / 知识资产）——不影响发布\033[0m\n' "$OTHER_FILES"
fi

printf '\n\033[1m── ③ 下一步 ──\033[0m\n'
if [ -z "$PENDING" ]; then
    printf '  \033[32m✅ 没有待发布的端——生产已是最新\033[0m\n'
else
    idx=0
    for u in "${RU_ALL_UNITS[@]}"; do
        n="${U_FILES[$idx]}"
        idx=$((idx + 1))
        if [ "$n" -le 0 ]; then continue; fi
        case "$u" in
            backend)
                printf '  【后端】\n'
                printf '    cd services/adai-core && ./gradlew bootJar\n'
                printf '    bash ai-engineering/deploy-gate.sh %s services/adai-core/build/libs/adai-core-0.0.1-SNAPSHOT.jar\n' "$PROD_IP"
                printf '    （deploy-gate 会跑三门门禁 + 部署后 smoke，并自动把发版清单写进生产 DEPLOYED）\n'
                ;;
            web)
                printf '  【Web 桌面端】\n'
                printf '    cd apps/adai-web && sh scripts/serve_web.sh https://api.adaiadai.com --build-only\n'
                printf '    cd build/web && tar -cf - . | ssh %s "sudo rm -rf /opt/adaios/web.new && sudo mkdir -p /opt/adaios/web.new && sudo tar -xf - -C /opt/adaios/web.new && sudo chown -R adaios:adaios /opt/adaios/web.new && sudo rm -rf /opt/adaios/web && sudo mv /opt/adaios/web.new /opt/adaios/web && sudo systemctl restart adaios-web"\n' "$HOST"
                printf '    （完整步骤见 docs/deployment/backend-deployment.md §8）\n'
                ;;
            admin)
                printf '  【管理后台】\n'
                printf '    cd apps/adai-admin && sh scripts/serve_web.sh https://api.adaiadai.com --build-only\n'
                printf '    cd build/web && tar -cf - . | ssh %s "sudo rm -rf /opt/adaios/admin.new && sudo mkdir -p /opt/adaios/admin.new && sudo tar -xf - -C /opt/adaios/admin.new && sudo chown -R adaios:adaios /opt/adaios/admin.new && sudo rm -rf /opt/adaios/admin && sudo mv /opt/adaios/admin.new /opt/adaios/admin && sudo systemctl restart adaios-admin"\n' "$HOST"
                printf '    （base-href=/admin/ 已内置在 serve_web.sh，漏了会显示成 Web 桌面壳）\n'
                ;;
            app)
                printf '  【iOS App】\n'
                printf '    sh apps/adai-app/scripts/release_testflight.sh --build-number %s\n' "$NEXT_BUILD"
                printf '    （构建号 %s → %s：Apple 拒重复构建号；不带 --build-number 时脚本自动 +1 并写回 pubspec.yaml）\n' \
                    "$APP_BUILD" "$NEXT_BUILD"
                printf '    （上传后外测组需过一次 Beta App Review ≈21 小时；攒批可省一次，见 docs/deployment/ios-release.md）\n'
                ;;
        esac
    done
    printf '\n  提示：以上均为用户确认后才执行的动作（B8）；push 与部署等你说。\n'
fi
echo
