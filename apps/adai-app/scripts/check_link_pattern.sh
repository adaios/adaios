#!/bin/sh
# check_link_pattern.sh — 「从分享文本里择出链接」的正则，双端必须逐字同口径。
#
# **为什么值得一个机械守卫**（2026-09-14 对抗审查 P1-3）：
# App 内整理走 Dart 侧 `_httpLinkRe`（`lib/main_page.dart`），分享扩展走 Swift 侧
# `linkPattern`（`ios/ShareExtension/ShareViewController.swift`）。两份正则只要差一个字符，
# 就会出现「**App 里能整理、分享进来却说找不到链接**」——而**没有任何编译或测试会报错**：
# 两边各自都"正常工作"，只是口径不同。
#
# 首版就真实漏了：Swift 少了全角 `）`（U+FF09）。实测
#   `看这个（https://www.bilibili.com/video/BV1xx411c7mD）讲得不错`
#   Swift → `https://www.bilibili.com/video/BV1xx411c7mD）讲得不错`（垃圾 URL → 后端抓取必失败）
#   Dart  → `https://www.bilibili.com/video/BV1xx411c7mD`（正确）
#
# 用法：sh scripts/check_link_pattern.sh   （改动任一侧正则后必跑；也可接进 CI/guard）
set -e
cd "$(dirname "$0")/.."

SWIFT_FILE="ios/ShareExtension/ShareViewController.swift"
DART_FILE="lib/main_page.dart"

SWIFT_PAT=$(sed -n 's/.*linkPattern = #"\(.*\)"#.*/\1/p' "$SWIFT_FILE" | head -1)
DART_PAT=$(sed -n "s/.*_httpLinkRe = RegExp(r'\(.*\)').*/\1/p" "$DART_FILE" | head -1)

if [ -z "$SWIFT_PAT" ] || [ -z "$DART_PAT" ]; then
  echo "FAIL: 没能从源码里提取到正则（变量被改名/挪走了？）"
  echo "  swift ($SWIFT_FILE): [$SWIFT_PAT]"
  echo "  dart  ($DART_FILE): [$DART_PAT]"
  exit 1
fi

if [ "$SWIFT_PAT" != "$DART_PAT" ]; then
  echo "FAIL: 双端择链接正则**不同口径** —— 会出现「App 里能整理、分享进来却说找不到链接」"
  echo "  swift: $SWIFT_PAT"
  echo "  dart : $DART_PAT"
  exit 1
fi

echo "PASS: 双端择链接正则同口径（${#SWIFT_PAT} 字符）"
