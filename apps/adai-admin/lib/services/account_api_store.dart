import '../models/account.dart';
import 'api_exception.dart';
import 'api_service.dart';

/// 账号存储接口 — 页面依赖抽象，测试注入 Fake。
abstract class AccountStore {
  /// 内置管理员账号：受保护，不可删除 / 禁用（防止锁死系统）。
  static const String protectedAdminId = 'adai';

  /// 加载账号列表。
  Future<List<Account>> loadAccounts();

  /// 新建账号（userId + role + 可选初始密码 ≥8 位；不设密码 = 登录前需先重置）。
  /// 返回 null 表示成功；返回字符串为失败原因（如账号已存在）。
  Future<String?> create({required String userId, required String role, String? password});

  /// 启用 / 禁用切换。内置管理员不可禁用。
  /// 返回 null 表示成功；返回字符串为失败原因。
  Future<String?> setEnabled(String userId, bool enabled);

  /// 合并插件（REVIEW S-R2）：服务端原子 add/remove——根治全量 PATCH read-modify-write 并发互覆。
  Future<String?> mergePlugins(String userId, {required List<String> add, required List<String> remove});

  /// 重置密码（REVIEW #178：admin 为他人/自己设新密码，≥8 位；后端踢除该账号全部会话）。
  /// 返回 null 表示成功；返回字符串为失败原因。
  Future<String?> resetPassword(String userId, String newPassword);

  /// 删除账号。内置管理员不可删除。
  /// 返回 null 表示成功；返回字符串为失败原因。
  Future<String?> delete(String userId);
}

/// 账号管理 — 真实后端实现（`/api/v1/accounts`，系统级，无 X-User-Id；需登录 + role=admin，#178）。
class AccountApiStore implements AccountStore {
  AccountApiStore({ApiService? api}) : _api = api ?? ApiService();

  final ApiService _api;

  @override
  Future<List<Account>> loadAccounts() async {
    try {
      return await _api.getAccounts();
    } on ApiException {
      rethrow;
    }
  }

  @override
  Future<String?> create(
      {required String userId, required String role, String? password}) async {
    try {
      await _api.createAccount(userId: userId, role: role, password: password);
      return null;
    } on ApiException catch (e) {
      return e.message;
    }
  }

  @override
  Future<String?> setEnabled(String userId, bool enabled) async {
    try {
      await _api.updateAccount(userId, enabled: enabled);
      return null;
    } on ApiException catch (e) {
      return e.message;
    }
  }

  @override
  Future<String?> mergePlugins(String userId,
      {required List<String> add, required List<String> remove}) async {
    try {
      await _api.mergeAccountPlugins(userId, add: add, remove: remove);
      return null;
    } on ApiException catch (e) {
      return e.message;
    }
  }

  @override
  Future<String?> resetPassword(String userId, String newPassword) async {
    try {
      await _api.updateAccount(userId, password: newPassword);
      return null;
    } on ApiException catch (e) {
      return e.message;
    }
  }

  @override
  Future<String?> delete(String userId) async {
    try {
      await _api.deleteAccount(userId);
      return null;
    } on ApiException catch (e) {
      return e.message;
    }
  }
}
