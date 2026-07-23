import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// 搜索页 — 全文搜索，显示带高亮的匹配结果。
class SearchPage extends StatefulWidget {
  final ApiService api;
  final String initialQuery;

  const SearchPage({super.key, required this.api, this.initialQuery = ''});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  final TextEditingController _searchCtrl = TextEditingController();
  final FocusNode _focusNode = FocusNode();

  List<SearchResultItem> _results = [];
  bool _loading = false;
  bool _hasSearched = false;
  String _query = '';

  @override
  void initState() {
    super.initState();
    if (widget.initialQuery.isNotEmpty) {
      _searchCtrl.text = widget.initialQuery;
      _search();
    } else {
      _focusNode.requestFocus();
    }
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final q = _searchCtrl.text.trim();
    if (q.isEmpty) return;
    setState(() {
      _loading = true;
      _hasSearched = true;
      _query = q;
    });
    try {
      final resp = await widget.api.search(q);
      if (!mounted) return;
      setState(() {
        _results = resp.results;
        _loading = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  List<InlineSpan> _buildHighlightedText(String text, String query) {
    if (query.isEmpty) return [TextSpan(text: text)];
    final lower = text.toLowerCase();
    final qLower = query.toLowerCase();
    final spans = <InlineSpan>[];
    int start = 0;
    int idx = lower.indexOf(qLower, start);
    while (idx >= 0) {
      if (idx > start) {
        spans.add(TextSpan(text: text.substring(start, idx)));
      }
      spans.add(TextSpan(
        text: text.substring(idx, idx + query.length),
        style: TextStyle(color: AppColors.darkGreen, fontWeight: FontWeight.w600),
      ));
      start = idx + query.length;
      idx = lower.indexOf(qLower, start);
    }
    if (start < text.length) {
      spans.add(TextSpan(text: text.substring(start)));
    }
    return spans;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Column(
          children: [
            _buildHeader(),
            const SizedBox(height: 8),
            if (_loading)
              const Expanded(child: Center(child: CircularProgressIndicator()))
            else if (!_hasSearched)
              const Expanded(
                child: Center(child: Text('输入关键词搜索记录', style: TextStyle(fontSize: 14, color: AppColors.darkGrey6))),
              )
            else if (_results.isEmpty)
              Expanded(
                child: Center(child: Text('未找到相关记录', style: TextStyle(fontSize: 14, color: AppColors.darkGrey6))),
              )
            else
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      child: Text('共 ${_results.length} 条结果', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
                    ),
                    const SizedBox(height: 8),
                    Expanded(
                      child: ListView.builder(
                        padding: const EdgeInsets.symmetric(horizontal: 20),
                        itemCount: _results.length,
                        itemBuilder: (_, i) => _buildResultCard(_results[i]),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      child: Row(
        children: [
          GestureDetector(
            onTap: () => Navigator.pop(context),
            child: Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Icon(Icons.arrow_back, size: 20, color: AppColors.darkGrey4),
            ),
          ),
          Expanded(
            child: Container(
              height: 40,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(14),
              ),
              child: TextField(
                controller: _searchCtrl,
                focusNode: _focusNode,
                style: TextStyle(fontSize: 15, color: AppColors.darkGrey1),
                decoration: InputDecoration(
                  hintText: '搜索…',
                  hintStyle: TextStyle(fontSize: 15, color: AppColors.darkGrey6),
                  border: InputBorder.none,
                  contentPadding: EdgeInsets.zero,
                  isDense: true,
                ),
                onSubmitted: (_) => _search(),
              ),
            ),
          ),
          const SizedBox(width: 8),
          GestureDetector(
            onTap: _search,
            child: Container(
              width: 40, height: 40,
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(Icons.arrow_forward_rounded, size: 18, color: AppColors.darkGrey5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildResultCard(SearchResultItem item) {
    final time = item.dateTime.length >= 16 ? item.dateTime.substring(11, 16) : '';

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface.withAlpha(200),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkBorder.withAlpha(100)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(item.title, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: AppColors.darkGrey1)),
          const SizedBox(height: 4),
          RichText(
            text: TextSpan(
              style: TextStyle(fontSize: 13, color: AppColors.darkGrey5, height: 1.4),
              children: _buildHighlightedText(item.content, _query),
            ),
          ),
          if (item.tags.isNotEmpty) ...[
            const SizedBox(height: 6),
            Wrap(
              spacing: 4, runSpacing: 4,
              children: item.tags.map((t) => Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(t, style: TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
              )).toList(),
            ),
          ],
          const SizedBox(height: 4),
          Text(time, style: TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
        ],
      ),
    );
  }
}
