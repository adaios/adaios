import 'package:flutter/material.dart';

/// AdaiOS Admin 调色板 — 与 adai-app 保持一致（暖灰深色主题，6 级深度）。
/// 独立工程复制值，不跨工程 import adai-app 的代码。
class AppColors {
  AppColors._();

  // -- Dark mode 背景/表面 --
  static const Color darkBg = Color(0xFF131211);
  static const Color darkSurface = Color(0xFF1D1B1A);
  static const Color darkSurface2 = Color(0xFF252220);
  static const Color darkBorder = Color(0xFF2D2926);

  // 6-level warm grey scale
  static const Color darkGrey1 = Color(0xFFF0EDE9); // highest emphasis
  static const Color darkGrey2 = Color(0xFFD4D0CB); // high emphasis
  static const Color darkGrey3 = Color(0xFFB5B0AA); // body
  static const Color darkGrey4 = Color(0xFF908B85); // secondary
  // 2026-10-08（独立审核 `review-uiux-visual` P3-4）：tertiary 由 `#66615C` 提亮为 `#8D8882`——
  // 前者在 darkSurface 上仅 **2.80:1**、darkSurface2 上 **2.58:1**（低于 WCAG AA 4.5:1；且
  // checklist `V5-2` 本就规定「小字号元信息不用 darkGrey5」，属欠账）。提亮后
  // **4.88:1 / 4.50:1**，两种表面均达标（值取「刚好过线」，下探一档 #8A857F 会在 surface2 上掉回 4.32）。
  // 已知代价：与 darkGrey4（`#908B85`，5.08:1 / 4.68:1）的视觉差被压到约 4%。
  // 若观感上需要恢复三级层次 → 走「整体重排」（同时提亮 darkGrey4），**不要**回退此值。
  static const Color darkGrey5 = Color(0xFF8D8882); // tertiary（AA-compliant on dark surfaces）
  static const Color darkGrey6 = Color(0xFF45423E); // placeholder

  // Accents — muted
  static const Color darkGreen = Color(0xFF3AB75A);
  static const Color darkOrange = Color(0xFFE8963A);
  static const Color darkBlue = Color(0xFF5299FF);
  static const Color darkPurple = Color(0xFF9B7FD4);
  static const Color darkYellow = Color(0xFFD4A043);
  static const Color darkRed = Color(0xFFD95757);
}
