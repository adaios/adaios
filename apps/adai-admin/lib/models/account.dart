/// 账号 — AdaiOS 管理端账号模型（与后端 Account 字段完全一致）。
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

  /// 从后端 JSON（`{userId, role, enabled, createdAt}`）解析。
  /// [createdAt] 为 ISO 日期字符串（如 "2026-08-02"）。
  factory Account.fromJson(Map<String, dynamic> json) => Account(
        userId: json['userId'] as String? ?? '',
        role: json['role'] as String? ?? 'user',
        enabled: json['enabled'] as bool? ?? false,
        createdAt: DateTime.tryParse(json['createdAt'] as String? ?? '') ??
            DateTime(1970),
      );

  /// 序列化为建号/更新请求所需字段（日期不下发）。
  Map<String, dynamic> toJson() => {
        'userId': userId,
        'role': role,
        'enabled': enabled,
      };
}
