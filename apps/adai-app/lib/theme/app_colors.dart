import 'package:flutter/material.dart';

/// ADAI color palette — warm grey tones, 6 levels of depth.
/// Like warm paper under soft light.
class AppColors {
  AppColors._();

  // -- Dark mode --
  static const Color darkBg = Color(0xFF0E0E0E);
  static const Color darkSurface = Color(0xFF1A1A1A);
  static const Color darkSurface2 = Color(0xFF232326);
  static const Color darkBorder = Color(0xFF2C2C2E);

  // 6-level warm grey scale (was: darkTextPrimary/Secondary/Tertiary)
  static const Color darkGrey1 = Color(0xFFF0EDE9); // highest emphasis
  static const Color darkGrey2 = Color(0xFFD4D0CB); // high emphasis
  static const Color darkGrey3 = Color(0xFFB5B0AA); // body
  static const Color darkGrey4 = Color(0xFF908B85); // secondary
  static const Color darkGrey5 = Color(0xFF66615C); // tertiary
  static const Color darkGrey6 = Color(0xFF45423E); // placeholder

  // Backwards-compat aliases
  static Color get darkTextPrimary => darkGrey1;
  static Color get darkTextSecondary => darkGrey4;
  static Color get darkTextTertiary => darkGrey5;

  // Accents — muted
  static const Color darkGreen = Color(0xFF2BC457);
  static const Color darkOrange = Color(0xFFE8963A);
  static const Color darkBlue = Color(0xFF5299FF);

  // -- Light mode --
  static const Color lightBg = Color(0xFFF5F4F2);
  static const Color lightSurface = Color(0xFFFFFFFF);
  static const Color lightSurface2 = Color(0xFFF2F1EF);
  static const Color lightBorder = Color(0xFFDFDDDA);

  static const Color lightGrey1 = Color(0xFF1C1A18);
  static const Color lightGrey2 = Color(0xFF3E3B38);
  static const Color lightGrey3 = Color(0xFF5E5A56);
  static const Color lightGrey4 = Color(0xFF8B8782);
  static const Color lightGrey5 = Color(0xFFAEA9A4);
  static const Color lightGrey6 = Color(0xFFC8C4BF);

  static Color get lightTextPrimary => lightGrey1;
  static Color get lightTextSecondary => lightGrey4;
  static Color get lightTextTertiary => lightGrey5;

  static const Color lightGreen = Color(0xFF34C759);
  static const Color lightOrange = Color(0xFFFF9500);
  static const Color lightBlue = Color(0xFF007AFF);
}
