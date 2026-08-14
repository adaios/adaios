/// 账号 — AdaiOS 管理端账号模型（与后端 Account 字段完全一致）。
class Account {
  Account({
    required this.userId,
    required this.role,
    required this.enabled,
    required this.createdAt,
    this.plugins = const [],
  });

  /// 账号唯一标识（登录名）。
  final String userId;

  /// 角色：admin（管理员）/ user（普通用户）。
  final String role;

  /// 启用状态：true=启用，false=禁用。
  bool enabled;

  /// 创建时间。
  final DateTime createdAt;

  /// 启用的插件名列表（RFC 20260814 Domain=插件模型；空 = 只有 Kernel 基础服务）。
  List<String> plugins;

  bool get isAdmin => role == 'admin';

  String get roleLabel => isAdmin ? '管理员' : '普通用户';

  String get statusLabel => enabled ? '启用' : '禁用';

  /// 从后端 JSON（`{userId, role, enabled, createdAt, plugins}`）解析。
  /// [createdAt] 为 ISO 日期字符串（如 "2026-08-02"）；老账号无 plugins → 空列表。
  factory Account.fromJson(Map<String, dynamic> json) => Account(
        userId: json['userId'] as String? ?? '',
        role: json['role'] as String? ?? 'user',
        enabled: json['enabled'] as bool? ?? false,
        createdAt: DateTime.tryParse(json['createdAt'] as String? ?? '') ??
            DateTime(1970),
        plugins: (json['plugins'] as List?)?.map((e) => e.toString()).toList() ??
            const [],
      );

  /// 序列化为建号/更新请求所需字段（日期不下发）。
  Map<String, dynamic> toJson() => {
        'userId': userId,
        'role': role,
        'enabled': enabled,
        'plugins': plugins,
      };
}
