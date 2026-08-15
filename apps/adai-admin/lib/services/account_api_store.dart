import '../models/account.dart';
import 'api_exception.dart';
import 'api_service.dart';

/// 账号存储接口 — 页面依赖抽象，测试注入 Fake。
abstract class AccountStore {
  /// 内置管理员账号：受保护，不可删除 / 禁用（防止锁死系统）。
  static const String protectedAdminId = 'adai';

  /// 加载账号列表。
  Future<List<Account>> loadAccounts();

  /// 新建账号（无口令，仅 userId + role）。
  /// 返回 null 表示成功；返回字符串为失败原因（如账号已存在）。
  Future<String?> create({required String userId, required String role});

  /// 启用 / 禁用切换。内置管理员不可禁用。
  /// 返回 null 表示成功；返回字符串为失败原因。
  Future<String?> setEnabled(String userId, bool enabled);

  /// 设置账号插件（RFC 20260814：trading/project 开关）。
  /// 返回 null 表示成功；返回字符串为失败原因。
  Future<String?> setPlugins(String userId, List<String> plugins);

  /// 合并插件（REVIEW S-R2）：服务端原子 add/remove——根治全量 PATCH read-modify-write 并发互覆。
  Future<String?> mergePlugins(String userId, {required List<String> add, required List<String> remove});

  /// 删除账号。内置管理员不可删除。
  /// 返回 null 表示成功；返回字符串为失败原因。
  Future<String?> delete(String userId);
}

/// 账号管理 — 真实后端实现（`/api/v1/accounts`，系统级，无 X-User-Id）。
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
  Future<String?> create({required String userId, required String role}) async {
    try {
      await _api.createAccount(userId: userId, role: role);
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
  Future<String?> setPlugins(String userId, List<String> plugins) async {
    try {
      await _api.updateAccount(userId, plugins: plugins);
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
  Future<String?> delete(String userId) async {
    try {
      await _api.deleteAccount(userId);
      return null;
    } on ApiException catch (e) {
      return e.message;
    }
  }
}
