/// Identity 相关 DTO。
///
/// 对应后端 GET/PUT /api/v1/identity 的 JSON 结构。
class IdentityResponse {
  final String name;
  final Map<String, String> preferences;
  final Map<String, String> rules;
  final List<String> tags;

  IdentityResponse({
    required this.name,
    required this.preferences,
    required this.rules,
    required this.tags,
  });

  factory IdentityResponse.fromJson(Map<String, dynamic> json) =>
      IdentityResponse(
        name: json['name'] as String? ?? '',
        preferences: (json['preferences'] as Map<String, dynamic>?)
                ?.map((k, v) => MapEntry(k, v as String)) ??
            {},
        rules: (json['rules'] as Map<String, dynamic>?)
                ?.map((k, v) => MapEntry(k, v as String)) ??
            {},
        tags: (json['tags'] as List?)?.cast<String>() ?? [],
      );
}

class IdentityRequest {
  final String name;
  final Map<String, String> preferences;
  final Map<String, String> rules;
  final List<String> tags;

  IdentityRequest({
    required this.name,
    required this.preferences,
    required this.rules,
    required this.tags,
  });

  Map<String, dynamic> toJson() => {
        'name': name,
        'preferences': preferences,
        'rules': rules,
        'tags': tags,
      };
}
