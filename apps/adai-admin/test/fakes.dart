import 'dart:async';

// 测试 Fake 存储 — 实现各 store 抽象接口，返回固定数据。
// 供 widget 测试注入页面，避免测试依赖真实后端。

import 'package:adai_admin/models/account.dart';
import 'package:adai_admin/models/api_dto.dart';
import 'package:adai_admin/models/data_models.dart';
import 'package:adai_admin/models/knowledge_models.dart';
import 'package:adai_admin/models/system_models.dart';
import 'package:adai_admin/models/tree_node.dart';
import 'package:adai_admin/services/account_api_store.dart';
import 'package:adai_admin/services/data_api_store.dart';
import 'package:adai_admin/services/knowledge_api_store.dart';
import 'package:adai_admin/services/system_api_store.dart';

// ── 账号 ──

class FakeAccountStore implements AccountStore {
  FakeAccountStore([List<Account>? seed]) {
    _accounts = seed ??
        [
          Account(
            userId: 'adai',
            role: 'admin',
            enabled: true,
            createdAt: DateTime(2026, 7, 1),
          ),
          Account(
            userId: 'alice',
            role: 'user',
            enabled: true,
            createdAt: DateTime(2026, 7, 12),
          ),
          Account(
            userId: 'bob',
            role: 'user',
            enabled: false,
            createdAt: DateTime(2026, 7, 20),
          ),
        ];
  }

  late final List<Account> _accounts;

  @override
  Future<List<Account>> loadAccounts() async => List.of(_accounts);

  @override
  Future<String?> create({required String userId, required String role}) async {
    final id = userId.trim();
    if (id.isEmpty) return '账号 ID 不能为空';
    if (_accounts.any((a) => a.userId == id)) return '账号已存在：$id';
    _accounts.add(
      Account(userId: id, role: role, enabled: true, createdAt: DateTime.now()),
    );
    return null;
  }

  @override
  Future<String?> setEnabled(String userId, bool enabled) async {
    if (userId == AccountStore.protectedAdminId && !enabled) {
      return '内置管理员不可禁用';
    }
    final idx = _accounts.indexWhere((a) => a.userId == userId);
    if (idx < 0) return '账号不存在：$userId';
    _accounts[idx].enabled = enabled;
    return null;
  }

  @override
  Future<String?> setPlugins(String userId, List<String> plugins) async {
    final idx = _accounts.indexWhere((a) => a.userId == userId);
    if (idx < 0) return '账号不存在：$userId';
    _accounts[idx].plugins = List.of(plugins);
    return null;
  }

  @override
  Future<String?> mergePlugins(String userId,
      {required List<String> add, required List<String> remove}) async {
    final idx = _accounts.indexWhere((a) => a.userId == userId);
    if (idx < 0) return '账号不存在：$userId';
    final merged = [..._accounts[idx].plugins];
    for (final p in add) {
      if (!merged.contains(p)) merged.add(p);
    }
    merged.removeWhere(remove.contains);
    _accounts[idx].plugins = merged;
    return null;
  }

  @override
  Future<String?> delete(String userId) async {
    if (userId == AccountStore.protectedAdminId) return '内置管理员不可删除';
    _accounts.removeWhere((a) => a.userId == userId);
    return null;
  }
}

// ── 数据管理 ──

class FakeDataStore implements DataStore {
  FakeDataStore() {
    _records = [
      ContentRecord(
        id: 'r-20260801-001',
        type: 'statement',
        content: '完成 v0.2.0 前端 actionable 闭环验收，核心链路全部打通',
        tags: const ['ship', '前端'],
        createdAt: DateTime(2026, 8, 1, 21, 30),
      ),
      ContentRecord(
        id: 'r-20260801-002',
        type: 'question',
        content: '上证指数这周会突破 3400 吗？需要拉取近五日成交量对比',
        tags: const ['行情', '大盘'],
        createdAt: DateTime(2026, 8, 1, 19, 5),
      ),
    ];
    _memories = [
      MemoryItem(
        id: 'm-01',
        kind: 'insight',
        content: 'Feed 中 type=market 的行情条需要独立渲染逻辑，与普通记录区分',
        superseded: false,
        createdAt: DateTime(2026, 8, 1, 20, 0),
      ),
      MemoryItem(
        id: 'm-03',
        kind: 'actionable',
        content: '给 admin 端补三个模块的 mock 页面框架',
        superseded: true,
        createdAt: DateTime(2026, 7, 30, 12, 0),
      ),
    ];
    _tasks = [
      TaskItem(
        id: 't-01',
        title: '为 adai-admin 补数据/系统/知识三个模块的页面框架',
        done: false,
        priority: 'high',
        createdAt: DateTime(2026, 8, 1, 9, 0),
      ),
      TaskItem(
        id: 't-04',
        title: 'Life OS 等数据积累到可用阈值',
        done: true,
        priority: 'low',
        createdAt: DateTime(2026, 7, 20, 11, 0),
      ),
    ];
    _positions = [
      Position(
        symbol: '510300',
        name: '沪深300ETF',
        quantity: 10000,
        avgCost: 3.85,
        currentPrice: 4.02,
      ),
      Position(
        symbol: '600519',
        name: '贵州茅台',
        quantity: 200,
        avgCost: 1450.0,
        currentPrice: 1421.5,
      ),
    ];
    _files = [
      TreeNode(name: 'records', path: 'records', isDir: true, children: const []),
      TreeNode(
        name: 'profile.md',
        path: 'identity/profile.md',
        isDir: false,
        content: '# 个人档案\n\n姓名：adai',
        meta: '1.2 KB',
      ),
    ];
  }

  late final List<ContentRecord> _records;
  late final List<MemoryItem> _memories;
  late final List<TaskItem> _tasks;
  late final List<Position> _positions;
  late final List<TreeNode> _files;

  IdentityProfile _identity = IdentityProfile(
    name: 'adai',
    preferences: const {'语言': '中文', '复盘粒度': '周度'},
    rules: const ['新功能先写 RFC 确认方向', '修改 API 后同步 api-spec.md'],
    tags: const ['单人开发', 'File First'],
  );

  @override
  Future<List<ContentRecord>> loadRecords() async => List.of(_records);

  @override
  Future<bool> deleteRecord(String id) async {
    final before = _records.length;
    _records.removeWhere((r) => r.id == id);
    return _records.length < before;
  }

  @override
  Future<List<MemoryItem>> loadMemories({String? date}) async =>
      List.of(_memories);

  @override
  Future<bool> updateMemory(String id, String content) async {
    final idx = _memories.indexWhere((m) => m.id == id);
    if (idx < 0) return false;
    _memories[idx] = MemoryItem(
      id: _memories[idx].id,
      kind: _memories[idx].kind,
      content: content.trim(),
      superseded: _memories[idx].superseded,
      createdAt: _memories[idx].createdAt,
    );
    return true;
  }

  @override
  Future<IdentityProfile> loadIdentity() async => _identity;

  @override
  Future<void> saveIdentity(IdentityProfile profile) async {
    _identity = profile;
  }

  @override
  Future<List<TaskItem>> loadTasks() async => List.of(_tasks);

  @override
  Future<TaskItem> addTask(String title, {String priority = 'medium'}) async {
    final task = TaskItem(
      id: 't-${DateTime.now().millisecondsSinceEpoch}',
      title: title.trim(),
      done: false,
      priority: priority,
      createdAt: DateTime.now(),
    );
    _tasks.insert(0, task);
    return task;
  }

  @override
  Future<bool> toggleTask(String id, bool currentDone) async {
    final idx = _tasks.indexWhere((t) => t.id == id);
    if (idx < 0) return false;
    _tasks[idx] = TaskItem(
      id: _tasks[idx].id,
      title: _tasks[idx].title,
      done: !currentDone,
      priority: _tasks[idx].priority,
      createdAt: _tasks[idx].createdAt,
    );
    return true;
  }

  @override
  Future<bool> deleteTask(String id) async {
    final before = _tasks.length;
    _tasks.removeWhere((t) => t.id == id);
    return _tasks.length < before;
  }

  @override
  Future<List<Position>> loadPositions() async => List.of(_positions);

  @override
  Future<List<TreeNode>> loadFiles(String path) async => List.of(_files);

  @override
  Future<TreeNode?> loadFileContent(String path) async {
    for (final f in _files) {
      if (f.path == path) return f;
    }
    return TreeNode(name: path.split('/').last, path: path, isDir: false, content: '文件内容');
  }
}

// ── 系统操作台 ──

class FakeSystemStore implements SystemStore {
  FakeSystemStore() {
    _feed = [
      FeedItem(
        id: 'f-01',
        type: 'market',
        title: '行情 · 沪深300 指数',
        subtitle: '沪深300 +0.85% 报 4021.34',
        time: DateTime(2026, 8, 1, 10, 30),
      ),
      FeedItem(
        id: 'f-02',
        type: 'record',
        title: '记录 · 今日复盘',
        subtitle: 'v0.2.0 验收完成，核心闭环全部打通',
        time: DateTime(2026, 8, 1, 21, 35),
      ),
    ];
    _quotes = [
      PositionQuote(symbol: '510300', name: '沪深300ETF', price: 4.02, changePercent: 1.01),
      PositionQuote(symbol: '600519', name: '贵州茅台', price: 1421.5, changePercent: -0.35),
    ];
    _reviews = [
      TradingReview(
        id: 'review-2026-08-01',
        date: DateTime(2026, 8, 1),
        title: '2026-08-01 复盘',
        generated: false,
      ),
      TradingReview(
        id: 'review-2026-07-31',
        date: DateTime(2026, 7, 31),
        title: '2026-07-31 复盘',
        generated: true,
      ),
    ];
    _conflicts = [
      ConflictItem(
        id: 'R96 四不原则',
        sideA: 'R96 四不原则',
        sideB: '当前仅持有 1 个标的，检查是否违反四不原则',
        handled: false,
      ),
    ];
  }

  late final List<FeedItem> _feed;
  late final List<PositionQuote> _quotes;
  late final List<TradingReview> _reviews;
  late final List<ConflictItem> _conflicts;

  @override
  Future<List<FeedItem>> loadFeed() async => List.of(_feed);

  @override
  Future<List<PositionQuote>> loadQuotes() async => List.of(_quotes);

  @override
  Future<List<TradingReview>> loadReviews() async => List.of(_reviews);

  @override
  Future<bool> generateReview(String id) async {
    final idx = _reviews.indexWhere((r) => r.id == id);
    if (idx < 0) return false;
    _reviews[idx] = TradingReview(
      id: _reviews[idx].id,
      date: _reviews[idx].date,
      title: _reviews[idx].title,
      generated: true,
    );
    return true;
  }

  @override
  Future<String?> reviewContent(String id) async {
    final idx = _reviews.indexWhere((r) => r.id == id);
    if (idx < 0 || !_reviews[idx].generated) return null;
    return '# ${_reviews[idx].title}\n\n## 持仓表现\n- 沪深300ETF +4.4%（达标）';
  }

  @override
  Future<MaintenanceResult> rebuildMemory() async =>
      const MaintenanceResult(success: true, message: '记忆重建完成：成功 1 / 失败 0（共 1）');

  @override
  Future<MaintenanceResult> refillMemory() async =>
      const MaintenanceResult(success: true, message: '重补完成：记忆 5 → 10（新增 5）');

  @override
  Future<MaintenanceResult> cleanData() async =>
      const MaintenanceResult(success: true, message: '清理完成：删除 3 条重复记录');

  @override
  Future<List<ConflictItem>> loadConflicts() async => List.of(_conflicts);

  @override
  Future<PromoteResultDto> promoteReview(String date, {String? note}) async =>
      const PromoteResultDto(
          status: 'ok', path: 'os/trading-os/99-inbox/review-2026-07-31.md');
}

// ── 知识浏览 ──

class FakeKnowledgeStore implements KnowledgeStore {
  @override
  Future<List<TreeNode>> loadOsDir({String domain = 'trading-os', String path = ''}) async {
    return [
      TreeNode(name: '11-context', path: 'trading-os/11-context', isDir: true, children: const []),
      TreeNode(
        name: 'rules.md',
        path: 'trading-os/11-context/rules.md',
        isDir: false,
        content: '**R1 活跃市值4%启动信号**\n> 活跃市值单日涨幅≥4%',
        meta: '0.3 KB',
      ),
    ];
  }

  @override
  Future<TreeNode?> loadOsFileContent(String path) async {
    return TreeNode(
      name: path.split('/').last,
      path: path,
      isDir: false,
      content: '**R1 活跃市值4%启动信号**\n> 活跃市值单日涨幅≥4%',
    );
  }

  @override
  Future<List<TermRule>> loadTerms() async => const [
        TermRule(
            name: '持仓',
            definition: '当前持有且未平仓的金融资产',
            category: '术语',
            source: 'trading-os'),
        TermRule(
            name: 'R096',
            definition: '交易复盘必须走 ContextEngine',
            category: '规则',
            source: 'trading-os'),
      ];
}


/// 可控延迟账号 store（REVIEW P2-R1 竞态测试）：setPlugins 挂起直到 gate 放行，
/// 记录每次调用参数；loadAccounts 返回当前列表快照。
class GatedAccountStore implements AccountStore {
  GatedAccountStore({required this.gate}) {
    _accounts = [
      Account(userId: 'alice', role: 'user', enabled: true, createdAt: DateTime(2026, 8, 2)),
    ];
  }

  final Completer<void> gate;
  final List<List<String>> setPluginsCalls = [];
  late final List<Account> _accounts;

  @override
  Future<List<Account>> loadAccounts() async => List.of(_accounts);

  @override
  Future<String?> create({required String userId, required String role}) async => null;

  @override
  Future<String?> setEnabled(String userId, bool enabled) async => null;

  @override
  Future<String?> setPlugins(String userId, List<String> plugins) async {
    setPluginsCalls.add(List.of(plugins));
    await gate.future; // 挂起直到测试放行
    final idx = _accounts.indexWhere((a) => a.userId == userId);
    if (idx >= 0) _accounts[idx].plugins = List.of(plugins);
    return null;
  }

  @override
  Future<String?> mergePlugins(String userId,
      {required List<String> add, required List<String> remove}) async {
    setPluginsCalls.add([...add, ...remove]);
    await gate.future;
    final idx = _accounts.indexWhere((a) => a.userId == userId);
    if (idx >= 0) {
      final merged = [..._accounts[idx].plugins];
      for (final p in add) {
        if (!merged.contains(p)) merged.add(p);
      }
      merged.removeWhere(remove.contains);
      _accounts[idx].plugins = merged;
    }
    return null;
  }

  @override
  Future<String?> delete(String userId) async => null;
}
