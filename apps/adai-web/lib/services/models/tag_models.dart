/// 标签统计 DTO，对应后端 GET /api/v1/tags 响应。
class TagSummary {
  final String name;
  final int count;
  final String lastAt;

  TagSummary({required this.name, required this.count, required this.lastAt});

  factory TagSummary.fromJson(Map<String, dynamic> json) => TagSummary(
        name: json['name'] as String? ?? '',
        count: json['count'] as int? ?? 0,
        lastAt: json['lastAt'] as String? ?? '',
      );
}

class TagsResponse {
  final List<TagSummary> tags;
  final int total;
  final String updatedAt;

  TagsResponse({required this.tags, required this.total, required this.updatedAt});

  factory TagsResponse.fromJson(Map<String, dynamic> json) => TagsResponse(
        tags: (json['tags'] as List?)?.map((e) => TagSummary.fromJson(e)).toList() ?? [],
        total: json['total'] as int? ?? 0,
        updatedAt: json['updatedAt'] as String? ?? '',
      );
}
