import 'dart:convert';
import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../theme/app_colors.dart';

/// 修改密码弹窗（移动端，暗色主题，与 App 既有弹窗风格一致）。
///
/// 内嵌校验：新密码 ≥8 位、两次输入一致、原密码非空；
/// 提交调 [onSubmit]（原密码 + 新密码 → 被踢会话数），成功后以 [Navigator.pop] 返回该数；
/// 失败（原密码错误 / 会话失效 / 网络）→ 弹窗内红字人话展示，不崩溃、不清空输入。
class ChangePasswordDialog extends StatefulWidget {
  const ChangePasswordDialog({super.key, required this.onSubmit});

  /// 提交回调：成功返回被踢会话数；失败抛异常（ApiException 带 statusCode + 后端 body）。
  final Future<int> Function(String oldPassword, String newPassword) onSubmit;

  @override
  State<ChangePasswordDialog> createState() => _ChangePasswordDialogState();
}

class _ChangePasswordDialogState extends State<ChangePasswordDialog> {
  final _oldCtrl = TextEditingController();
  final _newCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();
  String? _error;
  bool _submitting = false;

  @override
  void dispose() {
    _oldCtrl.dispose();
    _newCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  InputDecoration _decoration(String label) {
    return InputDecoration(
      labelText: label,
      labelStyle: const TextStyle(fontSize: 13, color: AppColors.darkGrey4),
      isDense: true,
      filled: true,
      fillColor: AppColors.darkBg,
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: const BorderSide(color: AppColors.darkBorder, width: 0.5),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: const BorderSide(color: AppColors.darkBlue, width: 1),
      ),
    );
  }

  Future<void> _submit() async {
    final oldPwd = _oldCtrl.text;
    final newPwd = _newCtrl.text;
    if (oldPwd.isEmpty) {
      setState(() => _error = '请输入原密码');
      return;
    }
    if (newPwd.length < 8) {
      setState(() => _error = '新密码长度至少 8 位');
      return;
    }
    if (newPwd != _confirmCtrl.text) {
      setState(() => _error = '两次输入的新密码不一致');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final kicked = await widget.onSubmit(oldPwd, newPwd);
      if (!mounted) return;
      Navigator.pop(context, kicked);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _error = _friendlyError(e);
      });
    }
  }

  /// 从 ApiException 提取后端人话（原密码错误 / 会话已失效等）；网络/超时单独兜底。
  String _friendlyError(Object e) {
    if (e is ApiException && e.body != null) {
      try {
        final decoded = jsonDecode(e.body!);
        if (decoded is Map && decoded['error'] != null) {
          final msg = decoded['error'].toString().trim();
          if (msg.isNotEmpty) return msg;
        }
        if (decoded is Map && decoded['message'] != null) {
          final msg = decoded['message'].toString().trim();
          if (msg.isNotEmpty) return msg;
        }
      } catch (_) {}
    }
    final str = e.toString();
    if (str.contains('TimeoutException') || str.contains('timed out')) {
      return '请求超时，请检查网络';
    }
    if (str.contains('Connection refused') || str.contains('SocketException')) {
      return '无法连接服务器，请确认网络';
    }
    return '修改失败，请重试';
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: AppColors.darkSurface,
      title: const Text('修改密码',
          style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 340,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('新密码至少 8 位；提交后其他登录会退出，当前保持在线。',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
            const SizedBox(height: 14),
            TextField(
              key: const Key('change-password-old'),
              controller: _oldCtrl,
              obscureText: true,
              enabled: !_submitting,
              style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
              decoration: _decoration('原密码'),
            ),
            const SizedBox(height: 10),
            TextField(
              key: const Key('change-password-new'),
              controller: _newCtrl,
              obscureText: true,
              enabled: !_submitting,
              style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
              decoration: _decoration('新密码（至少 8 位）'),
            ),
            const SizedBox(height: 10),
            TextField(
              key: const Key('change-password-confirm'),
              controller: _confirmCtrl,
              obscureText: true,
              enabled: !_submitting,
              style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
              onSubmitted: (_) => _submit(),
              decoration: _decoration('确认新密码'),
            ),
            if (_error != null) ...[
              const SizedBox(height: 10),
              Text(_error!,
                  style: const TextStyle(fontSize: 12, color: AppColors.darkRed)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _submitting ? null : () => Navigator.pop(context),
          child: const Text('取消', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
        ),
        FilledButton(
          onPressed: _submitting ? null : _submit,
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.darkBlue,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
          ),
          child: Text(_submitting ? '提交中…' : '确认修改',
              style: const TextStyle(fontSize: 13)),
        ),
      ],
    );
  }
}
