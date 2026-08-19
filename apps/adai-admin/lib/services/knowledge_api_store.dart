import '../models/api_dto.dart';
import '../models/knowledge_models.dart';
import '../models/tree_node.dart';
import 'api_service.dart';

/// 知识浏览模块存储接口 — 页面依赖抽象，测试注入 Fake。
///
/// os/ 资产浏览走系统级 `/api/v1/admin/knowledge`（无 X-User-Id）。
abstract class KnowledgeStore {
  // domain 与 os/ 目录名一致（后端白名单：trading-engine/life-os/project-os）。
  static const List<String> domains = ['trading-engine', 'life-os', 'project-os'];

  /// 加载 os/{domain}/ 目录条目（path 相对 os/ 根，如 `trading-engine/knowledge`）。
  Future<List<TreeNode>> loadOsDir({String domain = 'trading-engine', String path = ''});

  /// 加载 os/ 文件内容。
  Future<TreeNode?> loadOsFileContent(String path);

  /// 加载术语 / 规则列表。规则从 `knowledge/context/rules.md` 解析，术语用内置兜底。
  Future<List<TermRule>> loadTerms();
}

/// 知识浏览 — 真实后端实现。
class KnowledgeApiStore implements KnowledgeStore {
  KnowledgeApiStore({ApiService? api}) : _api = api ?? ApiService();

  final ApiService _api;

  /// 术语兜底（trading-engine 高频词），rules.md 解析失败时使用。
  static const List<TermRule> _fallbackTerms = [
    TermRule(
      name: '持仓',
      definition: '当前持有且未平仓的金融资产',
      category: '术语',
      source: 'trading-engine',
    ),
    TermRule(
      name: '回撤',
      definition: '从资产价格峰值到随后谷底的最大跌幅',
      category: '术语',
      source: 'trading-engine',
    ),
    TermRule(
      name: '复盘',
      definition: '交易结束后对决策过程与结果的系统性回顾',
      category: '术语',
      source: 'trading-engine',
    ),
    TermRule(
      name: '记忆合并',
      definition: '同主题记忆新版 superseded 旧版，保留元记忆回溯',
      category: '规则',
      source: '全局',
    ),
  ];

  @override
  Future<List<TreeNode>> loadOsDir({String domain = 'trading-engine', String path = ''}) async {
    final dtos = await _api.listKnowledge(domain: domain, path: path);
    return dtos.map(_fileToNode).toList();
  }

  @override
  Future<TreeNode?> loadOsFileContent(String path) async {
    final dto = await _api.getKnowledgeContent(path);
    return TreeNode(
      name: _nameOf(path),
      path: dto.path.isNotEmpty ? dto.path : path,
      isDir: false,
      content: dto.content,
      meta: _sizeLabel(dto.size),
    );
  }

  @override
  Future<List<TermRule>> loadTerms() async {
    final result = <TermRule>[..._fallbackTerms];
    try {
      final rules = await _parseRulesFromOs();
      // 规则（category=规则）优先用解析出的真实规则替换兜底中的规则项
      final rulesOnly = rules.where((r) => r.category == '规则').toList();
      if (rulesOnly.isNotEmpty) {
        result.removeWhere((t) => t.category == '规则');
        result.addAll(rulesOnly);
      }
    } catch (_) {
      // 读取失败保留兜底
    }
    return result;
  }

  Future<List<TermRule>> _parseRulesFromOs() async {
    const path = 'trading-engine/knowledge/context/rules.md';
    final dto = await _api.getKnowledgeContent(path);
    final rules = <TermRule>[];
    final pattern = RegExp(r'\*\*R(\d+)\s+([^*\n]+?)\s*\*\*(?:\n>\s*([^\n]+))?');
    for (final m in pattern.allMatches(dto.content)) {
      final num = m.group(1) ?? '';
      final title = m.group(2)?.trim() ?? '';
      final detail = m.group(3)?.trim();
      final definition = detail == null || detail.isEmpty
          ? title
          : '$title —— $detail';
      rules.add(TermRule(
        name: 'R$num',
        definition: definition,
        category: '规则',
        source: 'trading-engine',
      ));
    }
    return rules;
  }

  // ── 辅助 ──

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
}
