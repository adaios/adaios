import '../models/account.dart';

/// 内存 Mock 账号存储 — 管理端 UI 的数据源。
/// 后端接口（MD11）补充后，替换为真实 API 调用即可，页面无需改动。
class MockAccountStore {
  MockAccountStore() {
    _accounts = [
      Account(
        userId: 'adai',
        role: 'admin',
        enabled: true,
        createdAt: DateTime(2026, 7, 1),
      ),
      Account(
        userId: 'alice',
        role: 'user',
        enabled: true,
        createdAt: DateTime(2026, 7, 12),
      ),
      Account(
        userId: 'bob',
        role: 'user',
        enabled: false,
        createdAt: DateTime(2026, 7, 20),
      ),
    ];
  }

  /// 内置管理员账号：受保护，不可删除 / 禁用（防止锁死系统）。
  static const String protectedAdminId = 'adai';

  late final List<Account> _accounts;

  /// 只读快照，供列表渲染。
  List<Account> get accounts => List.unmodifiable(_accounts);

  /// 新建账号（无口令，仅 userId + role）。
  /// 返回 null 表示成功；返回字符串为失败原因（如账号已存在）。
  String? create({required String userId, required String role}) {
    final id = userId.trim();
    if (id.isEmpty) return '账号 ID 不能为空';
    if (_accounts.any((a) => a.userId == id)) return '账号已存在：$id';

    _accounts.add(
      Account(userId: id, role: role, enabled: true, createdAt: DateTime.now()),
    );
    return null;
  }

  /// 启用 / 禁用切换。内置管理员不可禁用。
  bool setEnabled(String userId, bool enabled) {
    if (userId == protectedAdminId && !enabled) return false;
    final idx = _accounts.indexWhere((a) => a.userId == userId);
    if (idx < 0) return false;
    _accounts[idx].enabled = enabled;
    return true;
  }

  /// 删除账号。内置管理员不可删除。
  bool delete(String userId) {
    if (userId == protectedAdminId) return false;
    final before = _accounts.length;
    _accounts.removeWhere((a) => a.userId == userId);
    return _accounts.length < before;
  }
}
