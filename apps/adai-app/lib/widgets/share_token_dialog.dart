import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/api_service.dart';
import '../theme/app_colors.dart';

/// ShareTokenDialog — 「把分享接到阿呆」的引导（2026-09-13 外部入口批）。
///
/// **为什么需要这一页**：快捷指令是外部工具，它的凭据只能存在**我们控制不了的地方**
/// （明文写进 plist，且 `.shortcut` 文件本身会被分享出去）。所以它用的不是登录密码，
/// 而是一把**限权 + 可撤销**的钥匙（后端 `TokenScope`）。这一页负责三件事：
/// 让你拿到钥匙、把「钥匙能做什么」说清楚、以及随时能把它收回来。
///
/// 文案口径遵循第一原则：说「你」「我」，不出现系统视角的措辞。
class ShareTokenDialog extends StatefulWidget {
  const ShareTokenDialog({super.key, required this.api});

  final ApiService api;

  /// 打开引导弹窗。
  static Future<void> show(BuildContext context, ApiService api) {
    return showDialog<void>(
      context: context,
      builder: (_) => ShareTokenDialog(api: api),
    );
  }

  @override
  State<ShareTokenDialog> createState() => _ShareTokenDialogState();
}

class _ShareTokenDialogState extends State<ShareTokenDialog> {
  bool _loading = true;
  bool _busy = false;
  String? _error;

  /// 刚签发的明文——**只在这一次存在**（后端只存哈希，关了就得重发一把）。
  String? _freshPlain;
  String? _freshPrefix;

  List<Map<String, dynamic>> _tokens = const [];

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
      final data = await widget.api.listExternalTokens();
      if (!mounted) return;
      final list = (data['tokens'] as List?) ?? const [];
      setState(() {
        _tokens = list.whereType<Map<String, dynamic>>().toList();
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = _humanError(e);
      });
    }
  }

  Future<void> _issue() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final data = await widget.api.issueExternalToken(label: '快捷指令');
      if (!mounted) return;
      setState(() {
        _freshPlain = data['token']?.toString();
        _freshPrefix = data['prefix']?.toString();
        _busy = false;
      });
      await _load();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = _humanError(e);
      });
    }
  }

  Future<void> _revoke(String prefix) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await widget.api.revokeExternalToken(prefix);
      if (!mounted) return;
      if (_freshPrefix == prefix) {
        setState(() {
          _freshPlain = null;
          _freshPrefix = null;
        });
      }
      await _load();
      if (!mounted) return;
      _toast('收回来了，这把钥匙立刻失效。');
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      _toast(_humanError(e));
    }
  }

  Future<void> _copy(String text, String what) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    _toast('$what已复制。');
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  /// 后端给的多是人话（响应体 JSON 的 {@code error} 字段），这里提取它；
  /// 只有网络类/未知错误才落到兜底文案——**绝不把状态码甩给用户**（第一原则）。
  String _humanError(Object e) {
    if (e is ApiException) {
      final body = e.body;
      if (body != null && body.isNotEmpty) {
        try {
          final decoded = jsonDecode(body);
          if (decoded is Map && decoded['error'] is String) {
            final message = (decoded['error'] as String).trim();
            if (message.isNotEmpty) return message;
          }
        } catch (_) {
          // body 不是 JSON（网关错误页等）→ 落到下面的兜底
        }
      }
    }
    return '这次没接上，等会儿再试一次。';
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: AppColors.darkSurface,
      title: const Text('把分享接到阿呆',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 340,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: _buildBody(),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('知道了'),
        ),
      ],
    );
  }

  List<Widget> _buildBody() {
    final children = <Widget>[
      const Text(
        '配一次就行。以后在 B站、微博、公众号看到好东西，直接分享给我——我去读，整理成学习卡。',
        style: TextStyle(fontSize: 13, height: 1.5, color: AppColors.darkGrey3),
      ),
      const SizedBox(height: 6),
      const Text(
        '我要的是一把单独的小钥匙，不是你的登录密码——它只能用来「整理链接」，随时能收回来。',
        style: TextStyle(fontSize: 12, height: 1.5, color: AppColors.darkGrey4),
      ),
      const SizedBox(height: 14),
    ];

    if (_loading) {
      children.add(const Padding(
        padding: EdgeInsets.symmetric(vertical: 12),
        child: Center(child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))),
      ));
      return children;
    }

    if (_error != null) {
      children.add(Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Text(_error!, style: const TextStyle(fontSize: 12, color: AppColors.darkRed)),
      ));
    }

    // 刚签发：明文只此一次
    if (_freshPlain != null) {
      children.addAll(_buildFreshKey(_freshPlain!));
      children.add(const SizedBox(height: 14));
    }

    children.addAll(_buildHowTo());
    children.add(const SizedBox(height: 16));
    children.addAll(_buildExistingKeys());
    return children;
  }

  List<Widget> _buildFreshKey(String plain) {
    return [
      Container(
        width: double.infinity,
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.5)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('你的钥匙（只显示这一次）',
                style: TextStyle(fontSize: 12, color: AppColors.darkGreen, fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            SelectableText(plain,
                style: const TextStyle(fontSize: 11, fontFamily: 'monospace', color: AppColors.darkGrey2)),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: () => _copy(plain, '钥匙'),
                icon: const Icon(Icons.copy, size: 15),
                label: const Text('复制', style: TextStyle(fontSize: 12)),
              ),
            ),
            const Text('现在就复制走——关掉之后我这儿也只留指纹，还原不出来了。',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
          ],
        ),
      ),
    ];
  }

  List<Widget> _buildHowTo() {
    return [
      const Text('怎么用在快捷指令里',
          style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey2)),
      const SizedBox(height: 8),
      _step('1', '打开「快捷指令」App，新建一条'),
      _step('2', '加动作「获取 URL 内容」：\n'
          '　地址 https://api.adaiadai.com/api/v1/learn/digest\n'
          '　方法改成 POST\n'
          '　请求体选 JSON，加一项：键 url、值选「快捷指令输入」\n'
          '　请求头加 Authorization，值填 Bearer 加一个空格、再粘上钥匙'),
      _step('3', '点这条快捷指令的详情，打开「在共享表单中显示」，接收 URL'),
      _step('4', '以后在任意 App 点分享 → 选这条快捷指令，我就开始读了'),
    ];
  }

  Widget _step(String index, String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 18,
            height: 18,
            margin: const EdgeInsets.only(top: 1, right: 8),
            decoration: const BoxDecoration(color: AppColors.darkSurface2, shape: BoxShape.circle),
            child: Center(
              child: Text(index, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3)),
            ),
          ),
          Expanded(
            child: Text(text,
                style: const TextStyle(fontSize: 12, height: 1.5, color: AppColors.darkGrey3)),
          ),
        ],
      ),
    );
  }

  List<Widget> _buildExistingKeys() {
    const labelStyle = TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey2);
    if (_tokens.isEmpty) {
      return [
        const Text('已经发出去的钥匙', style: labelStyle),
        const SizedBox(height: 8),
        const Text('还没有。', style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            onPressed: _busy ? null : _issue,
            child: Text(_busy ? '正在生成…' : '给我一把钥匙'),
          ),
        ),
      ];
    }

    return [
      Row(children: [
        const Text('已经发出去的钥匙', style: labelStyle),
        const Spacer(),
        TextButton(
          onPressed: _busy ? null : _issue,
          child: const Text('再要一把', style: TextStyle(fontSize: 12)),
        ),
      ]),
      const SizedBox(height: 4),
      ..._tokens.map(_keyRow),
    ];
  }

  Widget _keyRow(Map<String, dynamic> token) {
    final prefix = token['prefix']?.toString() ?? '';
    final label = token['label']?.toString() ?? '未命名';
    final lastUsed = token['lastUsedAt']?.toString();

    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('$label · $prefix',
                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
                const SizedBox(height: 2),
                Text(lastUsed == null || lastUsed.isEmpty ? '还没用过' : '最近用过：${lastUsed.substring(0, 10)}',
                    style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
              ],
            ),
          ),
          TextButton(
            onPressed: _busy ? null : () => _revoke(prefix),
            child: const Text('收回', style: TextStyle(fontSize: 12, color: AppColors.darkRed)),
          ),
        ],
      ),
    );
  }
}
