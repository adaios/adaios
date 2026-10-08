import 'package:flutter/material.dart';

import '../services/api_service.dart';
import '../theme/app_colors.dart';
import '../widgets/push_settings.dart';

/// 推送设置页（2026-10-09 app 端体验重构批 4）。
///
/// **为什么要有这一页**：这套开关原本只在主页 Feed 里「对某条推送卡右滑」才能打开——
/// 路径隐蔽到用户找不到（`explore-app-trading-page-20261009.md` 的测试触点也印证：
/// 只有 main_page 那条右滑入口）。交易页「今天」区给一个直达入口，开关本身与 Feed 那套
/// **共用同一份清单与逻辑**（`widgets/push_settings.dart`），不存在第二份真相。
class PushSettingsPage extends StatefulWidget {
  final ApiService api;

  const PushSettingsPage({super.key, required this.api});

  @override
  State<PushSettingsPage> createState() => _PushSettingsPageState();
}

class _PushSettingsPageState extends State<PushSettingsPage> {
  Map<String, bool>? _settings;
  String? _error;
  final _messengerKey = GlobalKey<ScaffoldMessengerState>();

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final s = await widget.api.getPushSettings();
      if (!mounted) return;
      setState(() {
        _settings = s;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      // 取不到就如实说、给重试——不渲染一排默认「开」的假开关
      setState(() => _error = '推送设置没取到，稍后再试');
    }
  }

  @override
  Widget build(BuildContext context) {
    return ScaffoldMessenger(
      key: _messengerKey,
      child: Scaffold(
        backgroundColor: AppColors.darkBg,
        appBar: AppBar(
          backgroundColor: AppColors.darkBg,
          foregroundColor: AppColors.darkGrey1,
          title: const Text('推送设置', style: TextStyle(fontSize: 16)),
        ),
        body: _error != null
            ? Center(
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Text(_error!, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
                  const SizedBox(height: 12),
                  TextButton(onPressed: _load, child: const Text('重试')),
                ]),
              )
            : _settings == null
                ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
                : ListView(
                    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                    children: [
                      const Text('阿呆会按这里的开关决定要不要吵你——默认只在该说的时候说一句。',
                          style: TextStyle(fontSize: 12, color: AppColors.darkGrey5, height: 1.4)),
                      const SizedBox(height: 8),
                      PushSettingsView(
                        initialSettings: _settings!,
                        onToggle: (type, on) async {
                          try {
                            await widget.api.updatePushSetting(type, on);
                            _messengerKey.currentState?.showSnackBar(const SnackBar(
                              content: Text('改好了', style: TextStyle(fontSize: 13)),
                              backgroundColor: AppColors.darkSurface2,
                            ));
                            return null;
                          } catch (e) {
                            return '没改成，稍后再试';
                          }
                        },
                      ),
                    ],
                  ),
      ),
    );
  }
}
