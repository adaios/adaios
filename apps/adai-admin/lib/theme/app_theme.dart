import 'package:flutter/material.dart';
import 'app_colors.dart';

/// AdaiOS Admin 主题 — Material 3 深色，与 adai-app 视觉一致。
class AppTheme {
  AppTheme._();

  static ThemeData get dark => ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: AppColors.darkBg,
        colorScheme: const ColorScheme.dark(
          surface: AppColors.darkBg,
          primary: AppColors.darkGrey1,
          secondary: AppColors.darkGrey4,
          onSurface: AppColors.darkGrey1,
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: Colors.transparent,
          elevation: 0,
          centerTitle: false,
          titleTextStyle: TextStyle(
            color: AppColors.darkGrey1,
            fontSize: 28,
            fontWeight: FontWeight.w600,
            letterSpacing: -0.5,
          ),
        ),
        navigationRailTheme: NavigationRailThemeData(
          backgroundColor: AppColors.darkSurface,
          // P3-25（2026-09-06）：统一 15% 写法（原 hex 0x263AB75A vs navigationBar withValues 双轨）
          indicatorColor: AppColors.darkGreen.withValues(alpha: 0.15),
          selectedIconTheme: const IconThemeData(color: AppColors.darkGreen),
          unselectedIconTheme: const IconThemeData(color: AppColors.darkGrey5),
          selectedLabelTextStyle: const TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: AppColors.darkGrey1,
          ),
          unselectedLabelTextStyle: const TextStyle(
            fontSize: 12,
            color: AppColors.darkGrey5,
          ),
        ),
        navigationBarTheme: NavigationBarThemeData(
          backgroundColor: AppColors.darkSurface,
          indicatorColor: AppColors.darkGreen.withValues(alpha: 0.15),
          elevation: 0,
          iconTheme: WidgetStateProperty.resolveWith((states) {
            if (states.contains(WidgetState.selected)) {
              return const IconThemeData(color: AppColors.darkGreen);
            }
            return const IconThemeData(color: AppColors.darkGrey5);
          }),
          labelTextStyle: WidgetStateProperty.resolveWith((states) {
            if (states.contains(WidgetState.selected)) {
              return const TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: AppColors.darkGrey1,
              );
            }
            return const TextStyle(fontSize: 11, color: AppColors.darkGrey5);
          }),
        ),
        // P3-18（2026-09-06）：删除死 cardTheme——全库无一处用 Material Card
        // （面板统一走 AppCard：darkSurface2 / radius10），cardTheme(darkSurface/radius14)
        // 是无人消费的「第二真相」，留着会导致后续以为面板由主题 Card 控制
        dividerTheme: const DividerThemeData(
          color: AppColors.darkBorder,
          thickness: 0.5,
          space: 0,
        ),
        floatingActionButtonTheme: const FloatingActionButtonThemeData(
          backgroundColor: AppColors.darkSurface2,
          foregroundColor: AppColors.darkGrey1,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(16)),
            side: BorderSide(color: AppColors.darkBorder, width: 0.5),
          ),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: AppColors.darkSurface2,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide.none,
          ),
          hintStyle: const TextStyle(
            color: AppColors.darkGrey5,
            fontSize: 16,
          ),
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 14,
          ),
        ),
        snackBarTheme: const SnackBarThemeData(
          backgroundColor: AppColors.darkSurface2,
          contentTextStyle: TextStyle(color: AppColors.darkGrey1),
          behavior: SnackBarBehavior.floating,
        ),
        textTheme: const TextTheme(
          displayLarge: TextStyle(
            fontSize: 32,
            fontWeight: FontWeight.w600,
            letterSpacing: -0.5,
            color: AppColors.darkGrey1,
          ),
          headlineLarge: TextStyle(
            fontSize: 28,
            fontWeight: FontWeight.w600,
            letterSpacing: -0.3,
            color: AppColors.darkGrey1,
          ),
          titleLarge: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w600,
            letterSpacing: -0.3,
            color: AppColors.darkGrey1,
          ),
          titleMedium: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w500,
            color: AppColors.darkGrey1,
          ),
          bodyLarge: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w400,
            color: AppColors.darkGrey1,
            height: 1.5,
          ),
          bodyMedium: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w400,
            color: AppColors.darkGrey4,
            height: 1.4,
          ),
          labelSmall: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w400,
            color: AppColors.darkGrey5,
          ),
        ),
      );
}
