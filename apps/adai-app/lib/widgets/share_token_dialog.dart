import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/api_service.dart';
import '../services/share_extension_service.dart';
import '../theme/app_colors.dart';

/// ShareTokenDialog — 「把分享接到阿呆」的引导（2026-09-13 外部入口批）。
///
/// **为什么需要这一页**：快捷指令是外部工具，它的凭据只能存在**我们控制不了的地方**
/// （明文写进 plist，且 `.shortcut` 文件本身会被分享出去）。所以它用的不是登录密码，
/// 而是一把**限权 + 可撤销**的钥匙（后端 `TokenScope`）。这一页负责三件事：
/// 让你拿到钥匙、把「钥匙能做什么」说清楚、以及随时能把它收回来。
///
/// 文案口径遵循第一原则：说「你」「我」，不出现系统视角的措辞。
///
/// 本页锁死的几条不变式（都是「丢了就真丢了」的路径）：
/// - **明文优先于 loading**：签发成功后 `_load()` 会把 `_loading` 置 true，
///   而明文只在这一次响应里存在——渲染顺序必须让明文先出来，不能被 spinner 顶掉。
/// - **关窗前二次确认**：明文没复制走就关窗（关闭按钮 / 返回键 / 遮罩）→ 先问一次。
/// - **失败不等于空**：列表读失败时说「没读到」，不能伪装成「还没有」。
/// - **收回后按钮必须复原**：`_busy` 两个分支都要复位，否则收回一把后按钮永久置灰。
class ShareTokenDialog extends StatefulWidget {
  const ShareTokenDialog({super.key, required this.api});

  final ApiService api;

  /// 打开引导弹窗。
  ///
  /// [barrierDismissible] 关闭：明文只在这一次出现，误点遮罩就关掉 = 真丢了；
  /// 需要关闭时走 [_ShareTokenDialogState._close]（内含二次确认）。
  static Future<void> show(BuildContext context, ApiService api) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => ShareTokenDialog(api: api),
    );
  }

  @override
  State<ShareTokenDialog> createState() => _ShareTokenDialogState();
}

class _ShareTokenDialogState extends State<ShareTokenDialog> {
  bool _loading = true;
  bool _busy = false;

  /// 列表读取失败（≠「没有令牌」：失败不能伪装成空态）。
  String? _listError;

  /// 动作失败（签发/收回失败等）。
  String? _actionError;

  /// 内联提示行——**不用 SnackBar**：它挂在页面 ScaffoldMessenger 上，会被 modal
  /// barrier 压住看不清（复制成功这种反馈必须看得见）。
  String? _hint;

  /// 刚签发的明文——**只在这一次存在**（后端只存哈希，关了就得重发一把）。
  String? _freshPlain;
  String? _freshPrefix;
  String? _freshId;

  /// 明文是否已复制走（决定关窗要不要二次确认）。
  bool _freshCopied = false;

  /// 关窗确认弹窗是否已弹出（防返回键连点弹出多个）。
  bool _confirmingClose = false;

  List<Map<String, dynamic>> _tokens = const [];

  /// 分享扩展的凭据状态（RFC 20260914）。**仅 iOS 有值**：Android/Web 上
  /// [ShareExtensionService.supported] 为 false，这一段 UI 整个不出现——
  /// 不能让用户去找一个在这台设备上根本不存在的东西。
  ShareExtensionStatus? _shareStatus;

  @override
  void initState() {
    super.initState();
    _load();
    unawaited(_loadShareStatus());
  }

  /// 查共享容器里有没有那把钥匙（只在 iOS 有意义）。
  Future<void> _loadShareStatus() async {
    if (!ShareExtensionService.supported) return;
    final status = await ShareExtensionService.status();
    if (!mounted) return;
    setState(() => _shareStatus = status);
  }

  /// 明文没复制走就要拦一下。
  bool get _needsDiscardConfirm => _freshPlain != null && !_freshCopied;

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _listError = null;
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
        _listError = _humanError(e);
      });
    }
  }

  Future<void> _issue() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _actionError = null;
      _hint = null;
    });
    try {
      final data = await widget.api.issueExternalToken(label: '系统分享与快捷指令');
      if (!mounted) return;
      final plain = data['token']?.toString();
      if (plain == null || plain.isEmpty) {
        // 后端 200 但没给明文 = 这次没发出去。明确说人话，不静默留个空。
        setState(() {
          _busy = false;
          _actionError = '这次没拿到钥匙的明文——没拿到就当没发出去，你再点一次。';
        });
        await _load();
        return;
      }
      final freshId = data['id']?.toString();
      setState(() {
        _freshPlain = plain;
        _freshPrefix = data['prefix']?.toString();
        _freshId = freshId;
        _freshCopied = false;
      });
      // 顺手把明文搬进 App Groups 共享容器：分享扩展是**独立进程**，读不到 App 的存储，
      // 而明文只在这一次响应里存在（后端只存哈希）——过了这一刻谁也还原不出来。
      // 用户拍板「签发时自动写入」，所以这里不额外加一步确认（2026-09-14 RFC）。
      if (ShareExtensionService.supported) {
        final connected =
            await ShareExtensionService.saveToken(token: plain, id: freshId);
        if (!mounted) return;
        if (!connected) {
          // 「拿到钥匙」与「系统分享接上了」是两件事，失败必须分开说——
          // 否则用户会以为分享面板已经能用了。
          setState(() => _actionError =
              '钥匙拿到了，但系统分享那边没接上。先把上面这串复制走，我回头修这里。');
        }
        await _loadShareStatus();
      }
      if (!mounted) return;
      // `_busy` 复位必须放在**所有 await 之后**：早复位会让等待期间按钮已可点，
      // 连点就签发两把——第一把明文被第二把顶掉，容器里留的是后写入的那把。
      setState(() => _busy = false);
      await _load();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _actionError = _humanError(e);
      });
    }
  }

  /// 收回一把钥匙。**优先用 `id` 撤销**（后端契约：`id` 唯一，旧前缀可能非唯一命中被拒）；
  /// 没有 `id` 时回退前缀（兼容旧后端）。
  Future<void> _revoke(Map<String, dynamic> token) async {
    if (_busy) return;
    final id = token['id']?.toString();
    final prefix = token['prefix']?.toString() ?? '';
    final target = (id != null && id.isNotEmpty) ? id : prefix;
    if (target.isEmpty) {
      setState(() => _hint = '这把钥匙没有可用的编号，先刷新一下列表再收回。');
      return;
    }
    setState(() {
      _busy = true;
      _actionError = null;
      _hint = null;
    });
    try {
      await widget.api.revokeExternalToken(target);
      if (!mounted) return;
      if ((id != null && id.isNotEmpty && _freshId == id) ||
          (prefix.isNotEmpty && _freshPrefix == prefix)) {
        setState(() {
          _freshPlain = null;
          _freshPrefix = null;
          _freshId = null;
          _freshCopied = false;
        });
      }
      // 成功分支也要复位 _busy——只复位失败分支的话，收回一把之后按钮就永久灰了。
      setState(() => _busy = false);
      _showHint('收回来了，这把钥匙立刻失效。');
      await _syncShareContainerAfterRevoke(revokedId: id);
      await _load();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _actionError = _humanError(e);
      });
    }
  }

  /// 撤销之后同步共享容器。**判不准就清**：容器里那把可能正是被撤的，
  /// 留着它只会让用户下次分享时收到一个说不清的 401；宁可让他重新点一次「发一把」，
  /// 也不要留一把可能已经失效的钥匙（fail-safe）。
  Future<void> _syncShareContainerAfterRevoke({String? revokedId}) async {
    if (!ShareExtensionService.supported) return;
    final status = _shareStatus;
    final sharedId = status?.id;
    final revoked = (revokedId != null && revokedId.isNotEmpty) ? revokedId : null;
    // 只有**确知容器里躺的是另一把**时才留着不动（状态可读 + 有令牌 + 两边 id 都明确且不同）。
    // 其余一律清 —— 包括 `_shareStatus == null`（通道报错或还没返回）：「不知道」时不清，
    // 就是留下一把可能已经失效的钥匙；判不准就清是这里唯一安全的默认（fail-safe）。
    final knownOtherKey = status != null &&
        status.hasToken &&
        sharedId != null &&
        revoked != null &&
        sharedId != revoked;
    if (knownOtherKey) return;
    final cleared = await ShareExtensionService.clearToken();
    await _loadShareStatus();
    if (!cleared && mounted) {
      // 清失败不能静默：界面若继续显示「已就绪」，用户会以为分享还能用（对抗审查 P2-6）。
      setState(() => _hint = '收回来了。系统分享那边那把我没清掉，回头我修这里。');
    }
  }

  /// 共享容器里那把钥匙**现在还有效吗**。
  ///
  /// 只看「字符串在不在」不够（对抗审查 P2-7）：令牌可能已经到期（90 天）或在别处被撤销，
  /// 那种情况下弹窗说「已就绪」，用户会去分享面板白找一趟。
  /// 判据复用**列表的真实数据**（容器里只存了副本的 id）：id 不在列表里 = 已被撤销/删号；
  /// 列表里那项 `expiresAt` 已过 = 已到期。**查不准时不误判**（列表没读到、旧版没记 id、
  /// 日期解析不了，一律当作有效）——「不知道」不能渲染成「失效」。
  bool get _sharedTokenAlive {
    final status = _shareStatus;
    if (status == null || !status.hasToken) return false;
    final id = status.id;
    if (id == null || id.isEmpty) return true;
    if (_listError != null) return true; // 列表没读到 ≠ 这把没了
    Map<String, dynamic>? match;
    for (final token in _tokens) {
      if (token['id']?.toString() == id) {
        match = token;
        break;
      }
    }
    if (match == null) return false;
    final expiresAt = match['expiresAt']?.toString();
    if (expiresAt == null || expiresAt.isEmpty) return true;
    final parsed = DateTime.tryParse(expiresAt);
    if (parsed == null) return true;
    return parsed.isAfter(DateTime.now());
  }

  Future<void> _copy(String text, String what, {bool freshKey = false}) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    if (freshKey) setState(() => _freshCopied = true);
    _showHint('$what已复制。');
  }

  void _showHint(String message) {
    if (!mounted) return;
    setState(() => _hint = message);
  }

  /// 关闭弹窗：明文没复制走 → 先二次确认（返回键与关闭按钮共用这一条路）。
  Future<void> _close() async {
    if (_needsDiscardConfirm) {
      final leave = await _confirmDiscard();
      if (!leave || !mounted) return;
    }
    if (mounted) Navigator.pop(context);
  }

  Future<bool> _confirmDiscard() async {
    if (_confirmingClose) return false;
    _confirmingClose = true;
    try {
      final leave = await showDialog<bool>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => AlertDialog(
          backgroundColor: AppColors.darkSurface,
          title: const Text('钥匙还没复制走',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          content: const Text('关掉这个窗口，明文就永远看不到了——我只能重新给你发一把。确定要关吗？',
              style: TextStyle(fontSize: 13, height: 1.5, color: AppColors.darkGrey3)),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('再看看'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('关掉', style: TextStyle(color: AppColors.darkRed)),
            ),
          ],
        ),
      );
      return leave ?? false;
    } finally {
      _confirmingClose = false;
    }
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

  /// 日期只取 `yyyy-MM-dd` 一段；**长度守卫**：短于 10 位就原样显示，
  /// 空/缺失返回 null（以前直接 `substring(0, 10)`，畸形值会把弹窗打崩）。
  String? _shortDate(Object? raw) {
    final value = raw?.toString();
    if (value == null || value.isEmpty) return null;
    return value.length >= 10 ? value.substring(0, 10) : value;
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_needsDiscardConfirm,
      onPopInvokedWithResult: (didPop, _) {
        // 返回键/手势：明文没复制走时 canPop=false，这里走同一条二次确认。
        if (didPop) return;
        unawaited(_close());
      },
      child: AlertDialog(
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
            onPressed: _close,
            child: const Text('知道了'),
          ),
        ],
      ),
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
      const SizedBox(height: 6),
      const Text(
        '两件事先说清：① 这把钥匙只能整理链接，但整理要花转写额度（也就是会花钱）；'
        '② 别把配好的快捷指令分享给别人——那等于把钥匙一起给了出去。',
        style: TextStyle(fontSize: 12, height: 1.5, color: AppColors.darkGrey4),
      ),
      const SizedBox(height: 14),
    ];

    // 明文必须排在 loading 之前：签发成功后 `_load()` 会把 `_loading` 置 true，
    // 而明文只出现这一次——被 spinner 顶掉就永远找不回来了。
    if (_freshPlain != null) {
      children.addAll(_buildFreshKey(_freshPlain!));
      children.add(const SizedBox(height: 14));
    }

    if (_hint != null) {
      children.add(Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.info_outline, size: 14, color: AppColors.darkGreen),
            const SizedBox(width: 6),
            Expanded(
              child: Text(_hint!, style: const TextStyle(fontSize: 12, color: AppColors.darkGreen)),
            ),
          ],
        ),
      ));
    }

    if (_actionError != null) {
      children.add(Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Text(_actionError!, style: const TextStyle(fontSize: 12, color: AppColors.darkRed)),
      ));
    }

    // 只有在没有明文要显示时才让 loading 独占内容区。
    if (_loading && _freshPlain == null) {
      children.add(const Padding(
        padding: EdgeInsets.symmetric(vertical: 12),
        child: Center(child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))),
      ));
      return children;
    }

    children.addAll(_buildShareSheetSection());
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
                onPressed: () => _copy(plain, '钥匙', freshKey: true),
                icon: const Icon(Icons.copy, size: 15),
                label: const Text('复制', style: TextStyle(fontSize: 12)),
              ),
            ),
            Text(
              _freshCopied
                  ? '复制好了。关掉窗口我也只留指纹，还原不出来。'
                  : '现在就复制走——关掉之后我这儿也只留指纹，还原不出来了。',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4),
            ),
          ],
        ),
      ),
    ];
  }

  /// iOS 上的「分享面板里的阿呆阿呆」（RFC 20260914）。
  ///
  /// **排在快捷指令引导之前**：iOS 上这才是主路径——点分享就进阿呆，不用配四条动作；
  /// 快捷指令引导保留给 Android/Web 与「不想用扩展」的场景（那两边没有这个扩展）。
  List<Widget> _buildShareSheetSection() {
    if (!ShareExtensionService.supported) return const <Widget>[];
    final status = _shareStatus;

    final String title;
    final String detail;
    Color color = AppColors.darkGrey3;
    if (status == null) {
      title = '正在确认系统分享接上没…';
      detail = '';
    } else if (!status.available) {
      title = '系统分享这条路还没接上';
      detail = '这台手机的共享容器没配好。先用下面的快捷指令，一样能分享。';
      color = AppColors.darkOrange;
    } else if (status.hasToken && _sharedTokenAlive) {
      title = '分享面板已就绪';
      detail = '在 B站/抖音点分享，在那一排里找「阿呆阿呆」。';
      color = AppColors.darkGreen;
    } else if (status.hasToken) {
      // 容器里有字符串 ≠ 那把钥匙还有效（90 天到期、或在别处被撤销）。
      title = '接过一次，但好像已经失效了';
      detail = '再点一次「给我一把钥匙」，我就把分享重新接上。';
      color = AppColors.darkOrange;
    } else {
      title = '还差一步';
      detail = '点下面的「给我一把钥匙」，我就把分享接上。';
      color = AppColors.darkOrange;
    }

    return [
      Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(
          status != null && status.hasToken && _sharedTokenAlive
              ? Icons.ios_share
              : Icons.info_outline,
          size: 15,
          color: color,
        ),
        const SizedBox(width: 6),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title,
                  style: TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w600, color: color)),
              if (detail.isNotEmpty) ...[
                const SizedBox(height: 3),
                Text(detail,
                    style: const TextStyle(
                        fontSize: 11, height: 1.4, color: AppColors.darkGrey4)),
              ],
            ],
          ),
        ),
      ]),
      const SizedBox(height: 14),
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

    // 读失败 ≠ 没有：失败时明确说「没读到」并给重试，绝不渲染「还没有。」伪装空态。
    if (_listError != null && _tokens.isEmpty) {
      return [
        const Text('已经发出去的钥匙', style: labelStyle),
        const SizedBox(height: 8),
        Text(_listError!, style: const TextStyle(fontSize: 12, color: AppColors.darkRed)),
        const SizedBox(height: 4),
        const Text('这不代表你一把都没发过，只是这次没读到。',
            style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton(
            onPressed: _loading ? null : _load,
            child: const Text('再试一次', style: TextStyle(fontSize: 12)),
          ),
        ),
        const SizedBox(height: 4),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            onPressed: _busy ? null : _issue,
            child: Text(_busy ? '正在生成…' : '给我一把钥匙'),
          ),
        ),
      ];
    }

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
    final lastUsed = _shortDate(token['lastUsedAt']);
    final expiresAt = _shortDate(token['expiresAt']);

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
                Text(lastUsed == null ? '还没用过' : '最近用过：$lastUsed',
                    style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
                const SizedBox(height: 2),
                Text(
                  // expiresAt 为 null = 不过期（后端契约）；如实说明，别让用户以为会自己失效。
                  expiresAt == null ? '长期有效' : '有效期至 $expiresAt',
                  style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4),
                ),
              ],
            ),
          ),
          TextButton(
            onPressed: _busy ? null : () => _revoke(token),
            child: const Text('收回', style: TextStyle(fontSize: 12, color: AppColors.darkRed)),
          ),
        ],
      ),
    );
  }
}
