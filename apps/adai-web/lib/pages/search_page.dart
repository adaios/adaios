import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../widgets/page_header.dart';

/// 搜索桌面形态 — 顶部全宽搜索栏 + 关键词高亮结果流。
class SearchPage extends StatefulWidget {
  final ApiService api;

  const SearchPage({super.key, required this.api});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  final _controller = TextEditingController();
  List<SearchResultItem> _results = [];
  int _total = 0;
  bool _searching = false;
  bool _hasSearched = false;
  String _lastQuery = '';

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _search([String? q]) async {
    final query = (q ?? _controller.text).trim();
    if (query.isEmpty) {
      setState(() {
        _hasSearched = false;
        _results = [];
      });
      return;
    }
    setState(() {
      _searching = true;
      _lastQuery = query;
    });
    try {
      final resp = await widget.api.search(query);
      if (!mounted) return;
      setState(() {
        _results = resp.results;
        _total = resp.total;
        _searching = false;
        _hasSearched = true;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _searching = false;
        _hasSearched = true;
        _results = [];
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      const PageHeader(title: '搜索', subtitle: '全文搜索记录与记忆'),
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
        child: TextField(
          controller: _controller,
          onSubmitted: _search,
          style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
          decoration: InputDecoration(
            hintText: '输入关键词搜索…',
            hintStyle: const TextStyle(fontSize: 13, color: AppColors.darkGrey5),
            prefixIcon: const Icon(Icons.search, size: 18, color: AppColors.darkGrey4),
            suffixIcon: _searching
                ? const Padding(
                    padding: EdgeInsets.all(12),
                    child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                  )
                : IconButton(
                    onPressed: () {
                      _controller.clear();
                      _search('');
                    },
                    icon: const Icon(Icons.close, size: 16),
                    color: AppColors.darkGrey5,
                  ),
            filled: true,
            fillColor: AppColors.darkSurface,
            isDense: true,
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(10),
              borderSide: const BorderSide(color: AppColors.darkBorder),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(10),
              borderSide: const BorderSide(color: AppColors.darkBorder),
            ),
          ),
        ),
      ),
      Expanded(child: _buildResults()),
    ]);
  }

  Widget _buildResults() {
    if (!_hasSearched) {
      return const Center(
        child: Text('输入关键词搜索记录', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
      );
    }
    if (_results.isEmpty) {
      return const Center(
        child: Text('未找到相关记录', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
          child: Text('共 $_total 条结果',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
            itemCount: _results.length,
            itemBuilder: (_, i) => _buildResultCard(_results[i]),
          ),
        ),
      ],
    );
  }

  Widget _buildResultCard(SearchResultItem r) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: AppColors.darkGreen.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(5),
              ),
              child: Text(r.type,
                  style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
            ),
            const Spacer(),
            Text(_timeOf(r.dateTime), style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          ]),
          const SizedBox(height: 8),
          Text.rich(
            TextSpan(children: _highlight(r.title)),
            style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1),
          ),
          if (r.content.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text.rich(
              TextSpan(children: _highlight(r.content)),
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 12, height: 1.5, color: AppColors.darkGrey3),
            ),
          ],
          if (r.tags.isNotEmpty) ...[
            const SizedBox(height: 6),
            Wrap(
              spacing: 4,
              runSpacing: 4,
              children: r.tags.map((t) => Text('#$t',
                  style: const TextStyle(fontSize: 10, color: AppColors.darkGrey5))).toList(),
            ),
          ],
        ],
      ),
    );
  }

  /// 关键词高亮（大小写不敏感）。
  List<TextSpan> _highlight(String text) {
    if (_lastQuery.isEmpty) return [TextSpan(text: text)];
    final lower = text.toLowerCase();
    final query = _lastQuery.toLowerCase();
    final spans = <TextSpan>[];
    var start = 0;
    while (true) {
      final idx = lower.indexOf(query, start);
      if (idx < 0) {
        if (start < text.length) spans.add(TextSpan(text: text.substring(start)));
        break;
      }
      if (idx > start) spans.add(TextSpan(text: text.substring(start, idx)));
      spans.add(TextSpan(
        text: text.substring(idx, idx + query.length),
        style: const TextStyle(color: AppColors.darkYellow, fontWeight: FontWeight.w700),
      ));
      start = idx + query.length;
    }
    return spans;
  }

  String _timeOf(String dateTime) {
    if (dateTime.length < 16) return dateTime;
    return dateTime.substring(0, 16);
  }
}
