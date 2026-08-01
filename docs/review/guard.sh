#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 守护检查执行器 — /review 每次必跑，防 P0 复发（数据丢失/契约破坏）
#
# 用法:  bash docs/review/guard.sh
# 说明:  脚本内部自动 cd 到仓库根，免疫 cwd 漂移；
#         用引号规避 zsh glob 坑（--include=*.dart 之类）。
# 配套:  检查清单文档见 docs/review/checklists/guard.md
#         （清单说明"查什么/上次发现"，本脚本是执行器）
# 退出码: 0 = 全部 PASS；1 = 有 HIT（存在复发/契约破坏风险）
# ─────────────────────────────────────────────────────────────
set -u

cd "$(dirname "$0")/../.."   # docs/review → 仓库根
ROOT="$(pwd)"
SRC="$ROOT/services/adai-core/src/main/java/com/adaiadai/core"
APP="$ROOT/apps/adai-app/lib"

PASS=0; HIT=0; NOTE=0
ok()   { PASS=$((PASS+1)); printf "  \033[32m✅\033[0m [%s] PASS  %s\n" "$1" "$2"; }
hit()  { HIT=$((HIT+1));   printf "  \033[31m❌\033[0m [%s] HIT   %s\n" "$1" "$2"; }
note() { NOTE=$((NOTE+1)); printf "  \033[33m⚠️\033[0m [%s] NOTE  %s\n" "$1" "$2"; }

echo "== 守护检查（guard.sh）$(date '+%F %H:%M') =="

# ── 数据安全 ────────────────────────────────────────────────
echo "── 数据安全 ──"

# G1 所有 ID 生成必须含毫秒（SSS）。
# 只查 ID 生成点：① ID_FORMATTER 常量 ② generateId() 方法体内的 ofPattern。
# 排除显示格式化（HH:mm 等非 ID 用途）。
G1_BAD=$(awk '
  /ID_FORMATTER.*ofPattern/ { if ($0 !~ /SSS/) print FILENAME": 常量 ID_FORMATTER 缺毫秒" }
  /generateId/ { in_gen=1 }
  /^[[:space:]]*}/ { if (in_gen) in_gen=0 }
  in_gen && /ofPattern/ && $0 !~ /SSS/ { print FILENAME": generateId 内 ofPattern 缺毫秒" }
' $(grep -rl "ID_FORMATTER\|generateId" "$SRC" --include="*.java" 2>/dev/null))
if [ -z "$G1_BAD" ]; then
  ok G1 "所有 ID 生成含毫秒 SSS（常量 + generateId()）"
else
  hit G1 "$G1_BAD"
fi

# G2 持久化路径不得用 now()，必须从实体 createdAt 推导（跨日复制丢轮次）
G2=$(grep -rn "LocalDate.now()\|Instant.now()" "$SRC/infrastructure/storage" --include="*.java" 2>/dev/null)
if [ -n "$G2" ]; then
  hit G2 "storage 层用 now() 推路径：$(echo "$G2" | tr '\n' ';' | head -c 200)"
else
  ok G2 "storage 层无 LocalDate.now()/Instant.now()"
fi

# G3 降级路径不得删除刚保存的用户数据（AI 失败等场景）。
# 判定：删除调用发生在 catch 块内 → 危险（降级路径）；正常业务删除（REST API / 业务方法）豁免。
G3_DANGER=""
for f in $(grep -rl "deleteById\|\.delete(" "$SRC/interfaces" "$SRC/application" --include="*.java" 2>/dev/null); do
  DANGER=$(awk '
    /catch/ { in_catch=1 }
    /^[[:space:]]*}/ { if (in_catch) in_catch=0 }
    in_catch && /deleteById|\.delete\(/ { print FILENAME": " NR" catch 降级路径内删除" }
  ' "$f")
  [ -n "$DANGER" ] && G3_DANGER="$G3_DANGER $DANGER"
done
if [ -n "$G3_DANGER" ]; then
  hit G3 "catch 降级路径内存在删除调用：$G3_DANGER"
else
  ok G3 "删除调用均不在 catch 降级路径内（业务删除豁免）"
fi

# ── 正则健壮性 ─────────────────────────────────────────────
echo "── 正则健壮性 ──"

# G4 DOTALL 下"字段级捕获"必须用 [^\n]*，不能用贪婪 .+ / .*（跨行吞内容）。
# 检测形态：冒号字段 `field:\s*(` 紧跟贪婪点。frontmatter 的 `^---\n(.+?)\n---\n(.+)`
# 是抓整体正文的有意跨行（无冒号前缀），豁免。
G4_FILES=$(grep -rl "Pattern.DOTALL" "$SRC" --include="*.java" 2>/dev/null)
if [ -z "$G4_FILES" ]; then
  hit G4 "未找到任何 Pattern.DOTALL 使用（检查点无法验证）"
else
  G4_BAD=""
  for f in $G4_FILES; do
    if grep -E ':\\s*\(\.' "$f" >/dev/null 2>&1; then
      G4_BAD="$G4_BAD $(basename "$f")"
    fi
  done
  if [ -n "$G4_BAD" ]; then
    hit G4 "DOTALL 字段捕获存在贪婪 .+/.：$G4_BAD"
  else
    ok G4 "DOTALL 字段捕获均用 [^\n]*（$(echo "$G4_FILES" | wc -l | tr -d ' ') 文件）"
  fi
fi

# ── 契约一致性 ─────────────────────────────────────────────
echo "── 契约一致性 ──"

# G5 计算字段（PnL/市值等）必须序列化，前端 fromJson 才能读到
G5=$(grep -rn "@JsonGetter\|@JsonProperty" "$SRC/domain/trading" --include="*.java" 2>/dev/null | head -3)
if [ -z "$G5" ]; then
  hit G5 "domain/trading 无计算字段序列化注解（PnL 可能恒 0）"
else
  ok G5 "计算字段已序列化：$(echo "$G5" | tr '\n' ';' | head -c 120)"
fi

# G6 异步回调 setState 前必须有 mounted 守卫（UI 组件销毁后 setState 崩溃）
G6=$(grep -rn "if (!mounted) return\|if (mounted)" "$APP" --include="*.dart" 2>/dev/null | wc -l | tr -d ' ')
G6_SETSTATE=$(grep -rn "setState" "$APP" --include="*.dart" 2>/dev/null | wc -l | tr -d ' ')
if [ "$G6" -eq 0 ] && [ "$G6_SETSTATE" -gt 0 ]; then
  hit G6 "存在 setState（$G6_SETSTATE 处）但无任何 mounted 守卫"
else
  ok G6 "mounted 守卫 $G6 处 / setState $G6_SETSTATE 处"
fi

# ── 场景路由 ──────────────────────────────────────────────
echo "── 场景路由 ──"

# G7 compose 必须真实传入 scene，Contributor 的 supports() 才能分流。
# 允许固定字面量（如 retry 场景 compose("note", record)），但必须存在传变量的调用。
G7_CALLS=$(grep -rn "contextEngine.compose\|engine.compose" "$SRC/application" --include="*.java" 2>/dev/null)
G7_VAR=$(echo "$G7_CALLS" | grep -E "compose\([a-zA-Z_]" || true)
if [ -n "$G7_VAR" ]; then
  ok G7 "compose 传 scene 变量：$(echo "$G7_VAR" | tr '\n' ';' | head -c 120)"
  note G7 "固定字面量调用（合理场景）：$(echo "$G7_CALLS" | grep -E 'compose\("' | tr '\n' ';' || true)"
elif [ -n "$G7_CALLS" ]; then
  hit G7 "compose 全部传固定字符串，无 scene 变量：$(echo "$G7_CALLS" | tr '\n' ';' | head -c 200)"
else
  hit G7 "未找到任何 compose 调用"
fi

echo ""
echo "守护检查完成：$PASS PASS / $HIT HIT / $NOTE NOTE"
[ "$HIT" -gt 0 ] && exit 1
exit 0
