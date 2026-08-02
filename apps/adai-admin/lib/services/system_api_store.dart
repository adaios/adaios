import '../models/api_dto.dart';
import '../models/system_models.dart';
import 'api_exception.dart';
import 'api_service.dart';

/// 系统操作台模块存储接口 — 页面依赖抽象，测试注入 Fake。
///
/// Feed / 行情（持仓实时价）/ 复盘 / 知识反哺 / 维护操作均为 per-user（带 X-User-Id）。
abstract class SystemStore {
  /// 加载 Feed 条目。
  Future<List<FeedItem>> loadFeed();

  /// 加载持仓实时价（作为行情快照）。
  Future<List<PositionQuote>> loadQuotes();

  /// 加载复盘列表（含是否已生成）。
  Future<List<TradingReview>> loadReviews();

  /// 生成复盘（id 形如 `review-2026-08-01`）。返回是否成功。
  Future<bool> generateReview(String id);

  /// 取复盘内容（未生成返回 null）。
  Future<String?> reviewContent(String id);

  /// 记忆重建。
  Future<MaintenanceResult> rebuildMemory();

  /// 记忆重补（records/retry）。
  Future<MaintenanceResult> refillMemory();

  /// 数据清理（cards/cleanup）。
  Future<MaintenanceResult> cleanData();

  /// 加载知识反哺冲突项。
  Future<List<ConflictItem>> loadConflicts();

  /// 将复盘提升为入库候选。
  Future<PromoteResultDto> promoteReview(String date, {String? note});
}

/// 系统操作台 — 真实后端实现。
class SystemApiStore implements SystemStore {
  SystemApiStore({ApiService? api, required String userId})
      : _api = api ?? ApiService(userId: userId);

  final ApiService _api;

  @override
  Future<List<FeedItem>> loadFeed() async {
    final dto = await _api.getFeed(page: 0, size: 50);
    return dto.entries
        .map((e) => FeedItem(
              id: e.id,
              type: e.type,
              title: e.title.isNotEmpty ? e.title : e.content,
              subtitle: e.content.isNotEmpty ? e.content : e.summary ?? '',
              time: _todayWithTime(e.time),
            ))
        .toList();
  }

  @override
  Future<List<PositionQuote>> loadQuotes() async {
    final dtos = await _api.getPositions();
    return dtos
        .map((p) => PositionQuote(
              symbol: p.symbol,
              name: p.name,
              price: p.currentPrice,
              changePercent: p.pnlPercent,
            ))
        .toList();
  }

  @override
  Future<List<TradingReview>> loadReviews() async {
    final dates = await _api.getReviewDates();
    final reviews = <TradingReview>[];
    for (final date in dates) {
      final content = await _api.getReview(date);
      reviews.add(TradingReview(
        id: 'review-$date',
        date: DateTime.tryParse(date) ?? DateTime(1970),
        title: '$date 复盘',
        generated: content != null && content.isNotEmpty,
      ));
    }
    reviews.sort((a, b) => b.date.compareTo(a.date));
    return reviews;
  }

  @override
  Future<bool> generateReview(String id) async {
    try {
      await _api.generateReview(_dateOf(id));
      return true;
    } on ApiException {
      return false;
    }
  }

  @override
  Future<String?> reviewContent(String id) async {
    try {
      return await _api.getReview(_dateOf(id));
    } on ApiException {
      return null;
    }
  }

  @override
  Future<MaintenanceResult> rebuildMemory() async {
    try {
      final r = await _api.rebuildMemory();
      final ok = r.failed == 0;
      return MaintenanceResult(
        success: ok,
        message: '记忆重建完成：成功 ${r.success} / 失败 ${r.failed}（共 ${r.total}）',
      );
    } on ApiException catch (e) {
      return MaintenanceResult(success: false, message: '记忆重建失败：${e.message}');
    }
  }

  @override
  Future<MaintenanceResult> refillMemory() async {
    try {
      final r = await _api.triggerRecordRetry();
      return MaintenanceResult(
        success: r.status == 'ok',
        message: '重补完成：记忆 ${r.memoriesBefore} → ${r.memoriesAfter}（新增 ${r.newMemories}）',
      );
    } on ApiException catch (e) {
      return MaintenanceResult(success: false, message: '重补失败：${e.message}');
    }
  }

  @override
  Future<MaintenanceResult> cleanData() async {
    try {
      final r = await _api.cleanupCards();
      return MaintenanceResult(
        success: true,
        message: '清理完成：删除 ${r['deleted'] ?? 0} 条重复记录',
      );
    } on ApiException catch (e) {
      return MaintenanceResult(success: false, message: '清理失败：${e.message}');
    }
  }

  @override
  Future<List<ConflictItem>> loadConflicts() async {
    final dto = await _api.getConflicts();
    return dto.conflicts
        .map((c) => ConflictItem(
              id: c.rule,
              sideA: c.rule,
              sideB: c.description,
              handled: false,
            ))
        .toList();
  }

  @override
  Future<PromoteResultDto> promoteReview(String date, {String? note}) async {
    return await _api.promoteReview(date, note: note);
  }

  // ── 辅助 ──

  static String _dateOf(String reviewId) => reviewId.replaceFirst('review-', '');

  static DateTime _todayWithTime(String hhmm) {
    final now = DateTime.now();
    final parts = hhmm.split(':');
    final hour = parts.isNotEmpty ? int.tryParse(parts[0]) ?? 0 : 0;
    final minute = parts.length > 1 ? int.tryParse(parts[1]) ?? 0 : 0;
    return DateTime(now.year, now.month, now.day, hour, minute);
  }
}
