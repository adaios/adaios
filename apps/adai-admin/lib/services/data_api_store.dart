import '../models/api_dto.dart';
import '../models/data_models.dart';
import '../models/tree_node.dart';
import 'api_service.dart';

/// 数据管理模块存储接口 — 页面依赖抽象，测试注入 Fake。
///
/// 记录 / 记忆 / 档案 / 任务 / 持仓 / data/ 文件树均为 per-user（带 X-User-Id），
/// 文件树走系统级 `/api/v1/admin/files`。
/// 治理收敛（P-role-01~04）：admin 只读查看个人数据，个人数据写归用户端 app/web，
/// 因此本接口仅保留读方法。
abstract class DataStore {
  /// 加载记录列表（从 Feed 拉取 type=record 条目）。
  Future<List<ContentRecord>> loadRecords();

  /// 加载记忆列表。无 date 时自动选最近有记忆的日期。
  Future<List<MemoryItem>> loadMemories({String? date});

  /// 加载个人档案。
  Future<IdentityProfile> loadIdentity();

  /// 加载任务列表。
  Future<List<TaskItem>> loadTasks();

  /// 加载持仓。
  Future<List<Position>> loadPositions();

  /// 加载 data/ 目录条目（path 相对 data/ 根，空 = 根）。
  Future<List<TreeNode>> loadFiles(String path);

  /// 加载 data/ 文件内容。
  Future<TreeNode?> loadFileContent(String path);
}

/// 数据管理 — 真实后端实现。
class DataApiStore implements DataStore {
  DataApiStore({ApiService? api, required String userId})
      : _api = api ?? ApiService(userId: userId);

  final ApiService _api;

  @override
  Future<List<ContentRecord>> loadRecords() async {
    final dto = await _api.getFeed(page: 0, size: 50);
    final records = <ContentRecord>[];
    for (final e in dto.entries) {
      if (e.type != 'record' && e.type != 'card') continue;
      records.add(ContentRecord(
        id: e.id,
        type: e.intent == 'question' ? 'question' : 'statement',
        content: e.content.isNotEmpty ? e.content : e.title,
        tags: e.tags,
        createdAt: _todayWithTime(e.time),
      ));
    }
    // 最新在前
    records.sort((a, b) => b.createdAt.compareTo(a.createdAt));
    return records;
  }

  @override
  Future<List<MemoryItem>> loadMemories({String? date}) async {
    String? target = date;
    if (target == null) {
      final dates = await _api.getMemoryDates();
      if (dates.isEmpty) return const [];
      target = dates.reduce((a, b) => a.compareTo(b) >= 0 ? a : b);
    }
    final dtos = await _api.getMemory(date: target);
    return dtos
        .map((m) => MemoryItem(
              id: m.id,
              kind: m.kind,
              content: m.summary,
              superseded: m.superseded,
              createdAt: DateTime.tryParse(m.createdAt) ?? DateTime(1970),
            ))
        .toList();
  }

  @override
  Future<IdentityProfile> loadIdentity() async {
    final dto = await _api.getIdentity();
    return _toProfile(dto);
  }

  @override
  Future<List<TaskItem>> loadTasks() async {
    final dtos = await _api.getTasks();
    return dtos.map(_toTask).toList();
  }

  @override
  Future<List<Position>> loadPositions() async {
    final dtos = await _api.getPositions();
    return dtos
        .map((p) => Position(
              symbol: p.symbol,
              name: p.name,
              quantity: p.quantity.toDouble(),
              avgCost: p.avgCost,
              currentPrice: p.currentPrice,
            ))
        .toList();
  }

  @override
  Future<List<TreeNode>> loadFiles(String path) async {
    final dtos = await _api.listFiles(path: path);
    return dtos.map(_fileToNode).toList();
  }

  @override
  Future<TreeNode?> loadFileContent(String path) async {
    final dto = await _api.getFileContent(path);
    return TreeNode(
      name: _nameOf(path),
      path: dto.path.isNotEmpty ? dto.path : path,
      isDir: false,
      content: dto.content,
      meta: _sizeLabel(dto.size),
    );
  }

  // ── 映射辅助 ──

  IdentityProfile _toProfile(IdentityDto dto) => IdentityProfile(
        name: dto.name,
        preferences: dto.preferences,
        rules: dto.rules.values.toList(),
        tags: dto.tags,
      );

  TaskItem _toTask(TaskDto dto) => TaskItem(
        id: dto.id,
        title: dto.title,
        done: dto.isDone,
        priority: dto.priority, // 透传后端 P0-P3（#140）
        createdAt: DateTime.tryParse(dto.createdAt) ?? DateTime(1970),
      );

  TreeNode _fileToNode(AdminFileDto dto) => TreeNode(
        name: dto.name,
        path: dto.path,
        isDir: dto.isDir,
        children: const [],
        meta: dto.isDir ? null : _sizeLabel(dto.size),
      );

  static String _nameOf(String path) {
    final parts = path.split('/');
    return parts.isEmpty ? path : parts.last;
  }

  static String _sizeLabel(int? size) {
    if (size == null) return '';
    if (size < 1024) return '$size B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)} KB';
    return '${(size / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  /// Feed 只返回 "HH:mm"，与今天日期组合成完整时间。
  static DateTime _todayWithTime(String hhmm) {
    final now = DateTime.now();
    final parts = hhmm.split(':');
    final hour = parts.isNotEmpty ? int.tryParse(parts[0]) ?? 0 : 0;
    final minute = parts.length > 1 ? int.tryParse(parts[1]) ?? 0 : 0;
    return DateTime(now.year, now.month, now.day, hour, minute);
  }
}
