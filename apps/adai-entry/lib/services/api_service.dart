import 'dart:convert';

import 'package:http/http.dart' as http;

import 'api_config.dart';

/// 入口账号模型 — 与后端 `Account` record 同形状（只保留入口需要的字段）。
class Account {
  const Account({
    required this.userId,
    required this.role,
    required this.enabled,
  });

  factory Account.fromJson(Map<String, dynamic> json) => Account(
        userId: json['userId'] as String,
        role: json['role'] as String,
        enabled: json['enabled'] as bool,
      );

  final String userId;
  final String role;
  final bool enabled;

  bool get isAdmin => role == 'admin';
}

/// 入口 API 客户端 — 仅账号列表（构造器可注入 [http.Client] 与 [baseUrl] 供测试）。
class EntryApiService {
  EntryApiService({http.Client? client, String? baseUrl})
      : _client = client ?? http.Client(),
        baseUrl = baseUrl ?? ApiConfig.baseUrl;

  final http.Client _client;
  final String baseUrl;

  /// `GET /api/v1/accounts` → 全部账号（含禁用，过滤由页面负责）。
  Future<List<Account>> getAccounts() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/accounts'));
    if (resp.statusCode >= 400) {
      throw Exception('加载账号失败 HTTP ${resp.statusCode}: ${resp.body}');
    }
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List;
    return list
        .map((e) => Account.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  void close() => _client.close();
}
