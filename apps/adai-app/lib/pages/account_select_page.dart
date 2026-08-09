import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// 账号选择页 — v1.0.0 多账号前端选号（首屏无记录时 + 切换账号时复用）。
/// 账号由 adai-admin 后台创建，产品端只选号不建号。
class AccountSelectPage extends StatefulWidget {
  final ApiService api;
  final String? currentUserId;
  final ValueChanged<String> onSelect;

  const AccountSelectPage({
    super.key,
    required this.api,
    this.currentUserId,
    required this.onSelect,
  });

  @override
  State<AccountSelectPage> createState() => _AccountSelectPageState();
}

class _AccountSelectPageState extends State<AccountSelectPage> {
  List<AccountModel>? _accounts;
  bool _loading = true;
  String? _error;

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
      final accounts = await widget.api.getAvailableAccounts();
      if (!mounted) return;
      setState(() {
        _accounts = accounts;
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

  String _errText(dynamic e) {
    final str = e.toString();
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器，请确认后端已启动';
    return '加载失败，请重试';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      appBar: AppBar(
        backgroundColor: AppColors.darkBg,
        elevation: 0,
        // 非首屏（切换场景）提供关闭；首屏（home）无可关闭层级
        leading: widget.currentUserId != null
            ? IconButton(
                icon: Icon(Icons.close, size: 20, color: AppColors.darkGrey4),
                onPressed: () => Navigator.pop(context),
              )
            : null,
        title: Text('选择账号',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: Text('加载中…', style: TextStyle(color: AppColors.darkGrey5)));
    }
    if (_error != null) {
      return Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.error_outline, size: 28, color: AppColors.darkOrange),
          const SizedBox(height: 10),
          Text(_error ?? '加载失败', style: const TextStyle(fontSize: 15, color: AppColors.darkGrey4)),
          const SizedBox(height: 14),
          GestureDetector(
            onTap: _load,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.3)),
              ),
              child: const Text('重试', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
            ),
          ),
        ]),
      );
    }
    final accounts = _accounts ?? [];
    if (accounts.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(32),
          child: Text('暂无可用账号\n请在管理后台（adai-admin）创建账号后刷新',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 14, color: AppColors.darkGrey5, height: 1.6)),
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
      itemCount: accounts.length,
      separatorBuilder: (_, _) => const SizedBox(height: 8),
      itemBuilder: (_, i) => _buildRow(accounts[i]),
    );
  }

  Widget _buildRow(AccountModel a) {
    final isCurrent = a.userId == widget.currentUserId;
    return GestureDetector(
      onTap: () => widget.onSelect(a.userId),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isCurrent
                ? AppColors.darkGreen.withValues(alpha: 0.5)
                : AppColors.darkBorder.withValues(alpha: 100),
          ),
        ),
        child: Row(children: [
          Container(
            width: 34,
            height: 34,
            decoration: BoxDecoration(
              color: (a.isAdmin ? AppColors.darkGreen : AppColors.darkBlue).withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Icon(a.isAdmin ? Icons.admin_panel_settings : Icons.person,
                size: 18, color: a.isAdmin ? AppColors.darkGreen : AppColors.darkBlue),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(a.userId,
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
              const SizedBox(height: 2),
              Text(a.isAdmin ? '管理员' : '普通用户',
                  style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            ]),
          ),
          if (isCurrent)
            Text('当前', style: TextStyle(fontSize: 11, color: AppColors.darkGreen)),
        ]),
      ),
    );
  }
}
