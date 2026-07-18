# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ADAI — Personal Context Platform. A Flutter mobile / web app (Material 3, dark mode) that serves as a personal digital entry point. No backend, no API calls, no state management — all hardcoded mock data.

**Core philosophy:** 记录今天。理解过去。帮助未来。

**Single page product** — no BottomNavigation, no tabs, no multi-level pages. Just one scrollable feed.

## Build & Run

```bash
# Flutter SDK is at D:\Software\flutter\bin
export PATH="$PATH:/d/Software/flutter/bin"

# Run (need Chrome for web)
flutter run -d chrome

# Analyze
flutter analyze

# Build web release
flutter build web --release
python -m http.server 8080 --bind 127.0.0.1  # serve from build/web

# Build APK (requires Android SDK)
flutter build apk --debug
```

### 🚨 Build Troubleshooting

**Flutter SDK version:** 3.29.2 (stable), engine revision `18b71d647a292a980abb405ac7d16fe1f0b20434`

**Engine.version corruption** — If `flutter build web` fails with 404 on `flutter_gpu.zip`
or "Unable to determine engine version", check:

```bash
cat /d/Software/flutter/bin/internal/engine.version
```

If empty, restore the revision hash (confirm from `/d/Software/flutter/bin/cache/flutter.version.json`):

```bash
echo "18b71d647a292a980abb405ac7d16fe1f0b20434" > /d/Software/flutter/bin/internal/engine.version
```

**Git detection** — From Windows bash, Flutter's `flutter.bat` (cmd) and the `flutter` (bash script)
detect git differently. The bash script needs GIT_EXEC_PATH and GIT_TEMPLATE_DIR set.
When `flutter` wrapper fails with "Unable to find git in your PATH", bypass it:

```bash
export FLUTTER_ROOT="/d/Software/flutter"
export PATH="$FLUTTER_ROOT/bin/cache/dart-sdk/bin:$PATH"
dart $FLUTTER_ROOT/bin/cache/flutter_tools.snapshot build web --release
```

This avoids the wrappers entirely and calls the Flutter tool directly via the Dart snapshot.

**Force full rebuild** — If build/web has stale artifacts:
```bash
rm -rf build/web
flutter build web --release
```

## Project Structure

```
lib/
├── main.dart                 # RootApp — MaterialApp with dark/light themes
├── main_page.dart            # THE only page — TopBar + Feed + InputBar
├── theme/
│   ├── app_colors.dart       # 6-level warm grey palette (dark/light)
│   └── app_theme.dart        # Material 3 ThemeData
├── data/
│   └── mock_data.dart        # ALL mock data — FeedItem[], TimelineEntry[]
└── widgets/
    ├── feed_card.dart        # Unified card — left accent bar by role (user/AI/news)
    ├── input_bar.dart        # One-row input — voice/text toggle + attachment menu
    └── timeline_modal.dart   # BottomSheet timeline — date groups, rail on left
```

## Architecture Rules

- **No backend. No API. No database.**
- **No state management** — only `StatefulWidget` + `setState`
- **No BottomNavigationBar** — single page. Timeline is a modal, not a page.
- **Mock data only** — everything in `lib/data/mock_data.dart`
- **Dark mode first** — `ThemeMode.dark` by default

## Design Tokens (Dark Mode)

| Token | Value | Usage |
|-------|-------|-------|
| darkBg | `#0E0E0E` | Background |
| darkSurface | `#1A1A1A` | Cards |
| darkSurface2 | `#232326` | Buttons, input |
| darkBorder | `#2C2C2E` | Dividers |
| darkGrey1 | `#F0EDE9` | Primary text |
| darkGrey3 | `#B5B0AA` | Body text |
| darkGrey4 | `#908B85` | Secondary |
| darkGrey5 | `#66615C` | Tertiary |
| darkGrey6 | `#45423E` | Placeholder |

Accent: `darkGreen` (`#2BC457`, AI analysis), `darkBlue` (`#5299FF`, news).

## Three Card Roles

| Role | Visual Signature | Text Weight |
|------|-----------------|-------------|
| User entry | No decoration, lightest | 16px, w500 |
| AI analysis | Green 5px left bar + green dot + "分析" tag (9px, letter-spaced) | 15px, w400 |
| News | Blue 5px left bar + blue dot + "资讯" tag (9px, letter-spaced) | 15px, w400 |

All share same card container (`darkSurface`, 16px radius).

## Input Bar Layout

One row, three columns. WeChat-style:

```
[🎤/⌨]  [input area (text or hold-to-voice)]  [⊕/↑]
```

- **Left button** — toggles voice/keyboard mode
- **Middle** — text field (keyboard mode) or "按住 说话" (voice mode, long press to record)
- **Right** — always 40×40. ⊕ opens attachment menu when empty, ↑ sends when has text
- **Attachment menu** — ⊕ opens BottomSheet: image / voice / file / link

## Feed Layout

- Time + tags on one row, top-left
- Content below
- For AI/news: left accent bar (5px) + role indicator dot + role label
- For news: bottom source attribution with `↗` icon
- Cards spaced at 6px vertical margin (12px between adjacent)
- AI morning briefing entries are just the first items in the feed (07:00)

## Timeline

Not a page. Top-right icon → BottomSheet modal (75% height).
Date-anchored vertical rail. "时间线" header.

## Forbidden

- No BottomNavigation / tabs
- No chat bubbles (left/right)
- No "Brain", "AI", "Knowledge", "Capture" as UI labels
- No Dashboard KPI / statistics
- No send / save buttons — Enter to send, typing is recording
- No tech blue / gradients
- No state management libraries
