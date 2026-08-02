/// 账号 — AdaiOS 管理端账号模型。
/// 当前为 mock 阶段；后端接口（MD11）补充后接入真实 API。
class Account {
  Account({
    required this.userId,
    required this.role,
    required this.enabled,
    required this.createdAt,
  });

  /// 账号唯一标识（登录名）。
  final String userId;

  /// 角色：admin（管理员）/ user（普通用户）。
  final String role;

  /// 启用状态：true=启用，false=禁用。
  bool enabled;

  /// 创建时间。
  final DateTime createdAt;

  bool get isAdmin => role == 'admin';

  String get roleLabel => isAdmin ? '管理员' : '普通用户';

  String get statusLabel => enabled ? '启用' : '禁用';
}
