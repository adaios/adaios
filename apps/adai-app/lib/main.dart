import 'package:flutter/material.dart';
import 'theme/app_theme.dart';
import 'theme/app_colors.dart';
import 'services/api_service.dart';
import 'main_page.dart';
import 'pages/launcher_page.dart';
import 'pages/profile_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const RootApp());
}

class RootApp extends StatelessWidget {
  const RootApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '阿呆阿呆',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.dark,
      home: const DualWorldShell(),
    );
  }
}

/// 双主页壳 — World A (Feed) 与 World B (Launcher) 无缝切换。
class DualWorldShell extends StatefulWidget {
  const DualWorldShell({super.key});

  @override
  State<DualWorldShell> createState() => _DualWorldShellState();
}

class _DualWorldShellState extends State<DualWorldShell> {
  final ApiService _api = ApiService();
  bool _showWorldB = false;
  String? _filterTag;

  void _toggleWorld() {
    setState(() => _showWorldB = !_showWorldB);
  }

  void _onTagTap(String tag) {
    setState(() {
      _filterTag = tag;
      _showWorldB = false;
    });
  }

  void _clearFilter() {
    setState(() => _filterTag = null);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: GestureDetector(
        onVerticalDragEnd: (d) {
          // 全局空白快速拖拽：高速度阈值避免干扰正常滚动
          if (d.primaryVelocity != null) {
            if (d.primaryVelocity! < -400 && !_showWorldB) {
              _toggleWorld();
            } else if (d.primaryVelocity! > 400 && _showWorldB) {
              _toggleWorld();
            }
          }
        },
        behavior: HitTestBehavior.translucent,
        child: AnimatedSwitcher(
        duration: const Duration(milliseconds: 250),
        switchInCurve: Curves.easeOut,
        switchOutCurve: Curves.easeIn,
        child: _showWorldB
            ? LauncherPage(
                key: const ValueKey('worldB'),
                api: _api,
                onNavigateBack: _toggleWorld,
                onTagTap: _onTagTap,
              )
            : MainPage(
                key: ValueKey('worldA-${_filterTag ?? ''}'),
                onPullUp: _toggleWorld,
                filterTag: _filterTag,
                onClearFilter: _clearFilter,
                onProfileTap: () {
                  Navigator.push(context, MaterialPageRoute(
                    builder: (_) => Scaffold(
                      backgroundColor: AppColors.darkBg,
                      body: ProfilePage(api: _api),
                    ),
                  ));
                },
              ),
        ),
      ),
    );
  }
}
