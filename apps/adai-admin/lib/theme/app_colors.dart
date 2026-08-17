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
  static const Color darkGrey5 = Color(0xFF66615C); // tertiary
  static const Color darkGrey6 = Color(0xFF45423E); // placeholder

  // Accents — muted
  static const Color darkGreen = Color(0xFF3AB75A);
  static const Color darkOrange = Color(0xFFE8963A);
  static const Color darkBlue = Color(0xFF5299FF);
  static const Color darkPurple = Color(0xFF9B7FD4);
  static const Color darkYellow = Color(0xFFD4A043);
  /// 红涨绿亏（A股，2026-08-17 走查）：涨/盈=红，跌/亏=绿（对齐 adai-web darkRed）。
  static const Color darkRed = Color(0xFFD95757);
}
