import 'package:flutter/material.dart';

import '../services/push_service.dart';
import '../theme/app_colors.dart';

/// 推送设置 —— **一处真相源**（2026-10-09 app 端体验重构批 4 抽出）。
///
/// 抽出来的原因：这套开关原本只活在主页 Feed 的右滑对话框里（`main_page.dart` 私有类），
/// 交易页想给一个直达入口就得再抄一遍 12 条清单——两份清单必然漂移。
/// 现在清单与开关逻辑都在这里：**对话框**与**设置页**共用同一个 [PushSettingsView]。
///
/// 口径不变（D2 2026-09-13 外部视角审查）：非 iOS 端**没有任何推送渠道**——
/// 开关照常可点、服务端照常存，但必须**如实告知「这台收不到」**，不承诺收不到的东西。
class PushSettingsView extends StatefulWidget {
  final Map<String, bool> initialSettings;

  /// 切换回调：返回 null = 成功；返回字符串 = 失败原因（B11-2，P2-推送5——失败不再假阳性）。
  final Future<String?> Function(String type, bool on) onToggle;

  /// 失败提示（由外层弹，避免在无 Scaffold 的 dialog 里取不到 context）。
  final void Function(String message)? onToggleFailed;

  const PushSettingsView({
    super.key,
    required this.initialSettings,
    required this.onToggle,
    this.onToggleFailed,
  });

  @override
  State<PushSettingsView> createState() => _PushSettingsViewState();
}

/// 推送类型清单（**唯一一份**）：key 与后端 `/push-settings` 的键一致。
const List<(String, String)> kPushSettingItems = [
  ('session', '时段节奏（早盘/午间/尾盘/收盘确认）'), // B11-3：注明含 15:15 收盘操作确认
  ('buy-point', '买点提醒'),
  ('close-summary', '收盘小结（当日成交+破止损+待确认）'), // P2-用户3 2026-08-29
  ('plan', '次日计划提醒（20:30 提醒写下个交易日的计划）'), // RFC 20261003 §三 2026-10-03
  ('learn-review', '学习复习提醒（每日复习到期卡片）'), // learn V2 批 4 2026-09-07
  ('todo-due', '待办到期提醒'), // RFC 20260917：待办到期日当天提醒（默认开、可关）
  ('stop-loss', '止损预警'),
  ('near-stop-loss', '接近止损'),
  ('loss', '单日大跌提醒'),
  ('gain', '放飞提示'),
  ('break-cost', '跌破成本线'),
  ('market', '大盘行情条'),
];

class _PushSettingsViewState extends State<PushSettingsView> {
  late Map<String, bool> _settings = Map.of(widget.initialSettings);

  /// D2：本机到底能不能收到推送（安卓与网页没有任何渠道）。
  bool get _canReceivePush => PushService.supported;

  Widget _notSupportedNotice() {
    return const Padding(
      padding: EdgeInsets.only(bottom: 10),
      child: Text(
        '这台收不到通知——阿呆现在只能推到 iPhone。\n'
        '这些开关先留着，以后换到 iPhone 就按这个来。',
        style: TextStyle(fontSize: 12, color: AppColors.darkGrey4, height: 1.4),
      ),
    );
  }

  Future<void> _toggle(String type, bool on) async {
    final err = await widget.onToggle(type, on);
    if (!mounted) return;
    if (err == null) {
      setState(() => _settings[type] = on);
    } else {
      widget.onToggleFailed?.call(err);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (!_canReceivePush) _notSupportedNotice(),
        for (final (type, label) in kPushSettingItems)
          SwitchListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: Text(label, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2)),
            value: _settings[type] ?? true,
            activeTrackColor: AppColors.darkGreen,
            onChanged: _canReceivePush ? (on) => _toggle(type, on) : null,
          ),
      ],
    );
  }
}

/// Feed 右滑进来的对话框形态（原 `_PushSettingsDialog`，就地搬过来）。
class PushSettingsDialog extends StatelessWidget {
  final Map<String, bool> settings;
  final Future<String?> Function(String type, bool on) onToggle;
  final void Function(String message)? onToggleFailed;

  const PushSettingsDialog({
    super.key,
    required this.settings,
    required this.onToggle,
    this.onToggleFailed,
  });

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      // D2（2026-09-13）：加了「这台收不到通知」说明条后内容变高，小屏会溢出 → 可滚动
      scrollable: true,
      backgroundColor: AppColors.darkSurface2,
      title: const Text('推送设置', style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 300,
        child: PushSettingsView(
          initialSettings: settings,
          onToggle: onToggle,
          onToggleFailed: onToggleFailed,
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text('完成', style: TextStyle(color: AppColors.darkGrey3)),
        ),
      ],
    );
  }
}
