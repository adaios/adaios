import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../theme/app_colors.dart';

/// 登录设备（RFC 20260914 L2）——「哪些设备登录着」，以及**单独撤销一台**。
///
/// 为什么需要它：会话是 30 天滑动续期（活跃即不过期），一旦在别的设备上登录过，
/// 它就长期有效；此前唯一的补救手段是改密码（把自己也踢下线、还分不清是哪台）。
/// 这里补上可辨认、可撤销的出口——与外部工具令牌的撤销思路一致。
class LoginDevicesPage extends StatefulWidget {
  final ApiService api;

  const LoginDevicesPage({super.key, required this.api});

  @override
  State<LoginDevicesPage> createState() => _LoginDevicesPageState();
}

class _LoginDevicesPageState extends State<LoginDevicesPage> {
  List<Map<String, dynamic>>? _sessions;
  String? _error;
  bool _loading = true;

  /// 正在撤销的会话 id（按钮显示 loading，防连点）。
  String? _revoking;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final list = await widget.api.listSessions();
      if (!mounted) return;
      setState(() {
        _sessions = list;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = _errText(e);
        _loading = false;
      });
    }
  }

  Future<void> _revoke(Map<String, dynamic> session) async {
    final id = (session['id'] as String?) ?? '';
    if (id.isEmpty) return;
    final label = _deviceLabel(session);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: const Text('撤销这台设备？', style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
        content: Text('「$label」会立刻退出登录，需要在它上面重新输密码。其它设备不受影响。',
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消', style: TextStyle(color: AppColors.darkGrey4)),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('撤销', style: TextStyle(color: AppColors.darkRed)),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _revoking = id);
    try {
      await widget.api.revokeSession(id);
      if (!mounted) return;
      setState(() => _revoking = null);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已撤销「$label」，它需要重新登录')),
      );
      await _load();
    } catch (e) {
      if (!mounted) return;
      setState(() => _revoking = null);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(_errText(e))));
    }
  }

  /// 设备显示名：客户端上报的 name 优先，其次平台中文，最后「未知设备」。
  String _deviceLabel(Map<String, dynamic> session) {
    final device = session['device'];
    if (device is Map) {
      final name = (device['name'] as String?)?.trim();
      if (name != null && name.isNotEmpty) return name;
      final platform = (device['platform'] as String?)?.trim();
      if (platform != null && platform.isNotEmpty) return '${_platformLabel(platform)}设备';
    }
    return '未知设备';
  }

  String _platformLabel(String p) => switch (p.toLowerCase()) {
        'ios' => 'iOS ',
        'android' => 'Android ',
        'web' => '网页',
        'macos' => 'macOS ',
        _ => '$p ',
      };

  /// 最近活跃的相对时间（够用即可，不引时间库）。
  String _relative(String? iso) {
    if (iso == null) return '—';
    final t = DateTime.tryParse(iso);
    if (t == null) return '—';
    final diff = DateTime.now().toUtc().difference(t.toUtc());
    if (diff.inMinutes < 1) return '刚刚';
    if (diff.inMinutes < 60) return '${diff.inMinutes} 分钟前';
    if (diff.inHours < 24) return '${diff.inHours} 小时前';
    return '${diff.inDays} 天前';
  }

  /// 从 ApiException 里取出后端人话（`{"error": "..."}`）。
  String _errText(dynamic e) {
    final body = e is ApiException ? e.body : null;
    if (body != null && body.contains('"error"')) {
      final start = body.indexOf('"error"') + 8;
      final end = body.indexOf('"', start + 1);
      if (end > start) return body.substring(start + 1, end);
    }
    final str = e.toString();
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('SocketException') || str.contains('Connection refused')) return '无法连接服务器，请确认网络';
    if (str.contains('401')) return '会话已失效，请重新登录';
    return '操作失败，请重试';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      appBar: AppBar(
        backgroundColor: AppColors.darkBg,
        elevation: 0,
        title: const Text('登录设备', style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
        iconTheme: const IconThemeData(color: AppColors.darkGrey3),
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        color: AppColors.darkBlue,
        backgroundColor: AppColors.darkSurface,
        child: _buildBody(),
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(
        child: SizedBox(
            width: 22, height: 22,
            child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkBlue)),
      );
    }
    if (_error != null) {
      return ListView(
        children: [
          const SizedBox(height: 80),
          Center(
            child: Text(_error!,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, color: AppColors.darkRed)),
          ),
          const SizedBox(height: 12),
          Center(
            child: TextButton(onPressed: _load, child: const Text('重试')),
          ),
        ],
      );
    }
    final sessions = _sessions ?? const [];
    if (sessions.isEmpty) {
      return ListView(
        children: const [
          SizedBox(height: 80),
          Center(child: Text('没有其它登录中的设备', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5))),
        ],
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: sessions.length + 1,
      separatorBuilder: (_, __) => const SizedBox(height: 10),
      itemBuilder: (context, index) {
        if (index == 0) {
          return const Padding(
            padding: EdgeInsets.only(bottom: 6),
            child: Text(
              '这些设备现在都能打开阿呆阿呆。不认识的就把「撤销」点掉——它下次请求会被拒绝，需要重新登录。',
              style: TextStyle(fontSize: 12, color: AppColors.darkGrey5, height: 1.5),
            ),
          );
        }
        return _sessionCard(sessions[index - 1]);
      },
    );
  }

  Widget _sessionCard(Map<String, dynamic> session) {
    final id = (session['id'] as String?) ?? '';
    final current = session['current'] == true;
    final busy = _revoking == id;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: current ? AppColors.darkGreen.withAlpha(90) : AppColors.darkBorder),
      ),
      child: Row(
        children: [
          Icon(_deviceIcon(session), size: 18, color: AppColors.darkGrey4),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(_deviceLabel(session),
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1)),
                    ),
                    if (current) ...[
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                        decoration: BoxDecoration(
                          color: AppColors.darkGreen.withAlpha(30),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: const Text('当前设备',
                            style: TextStyle(fontSize: 10, color: AppColors.darkGreen)),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 4),
                Text('最近活跃 ${_relative(session['lastSeenAt'] as String?)}',
                    style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              ],
            ),
          ),
          if (busy)
            const SizedBox(
                width: 16, height: 16,
                child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGrey4))
          else if (!current)
            TextButton(
              onPressed: () => _revoke(session),
              style: TextButton.styleFrom(foregroundColor: AppColors.darkRed),
              child: const Text('撤销', style: TextStyle(fontSize: 13)),
            ),
        ],
      ),
    );
  }

  IconData _deviceIcon(Map<String, dynamic> session) {
    final device = session['device'];
    final platform = device is Map ? (device['platform'] as String? ?? '') : '';
    return switch (platform.toLowerCase()) {
      'ios' || 'android' => Icons.smartphone_outlined,
      'web' || 'macos' => Icons.laptop_mac_outlined,
      _ => Icons.devices_other_outlined,
    };
  }
}
