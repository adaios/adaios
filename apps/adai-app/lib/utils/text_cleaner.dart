import 'dart:convert';

/// AI 回复文本清理工具：剥离尾部 JSON 残留 + 解码 \\uXXXX 转义（正确处理代理对 emoji）。
///
/// 两个聊天渲染路径（main_page / feed_card）曾各维护一份实现，
/// 且 main_page 版本不处理代理对导致 emoji 解码错误——统一到此处。
class TextCleaner {
  /// 去除 AI 回复末尾的 JSON 残留并解码 \\uXXXX 转义。
  static String stripDomainJson(String text) {
    final clean = _removeTrailingJson(text);
    return decodeUnicodeEscapes(clean);
  }

  /// 移除 AI 回复末尾可能附着的 JSON 块。
  /// 查找最后一个 "\n{"，校验其后内容是否为有效 JSON，是则剥离。
  static String _removeTrailingJson(String text) {
    final idx = text.lastIndexOf('\n{');
    if (idx < 0) {
      // 兼容旧格式 `{"domain"` 在行首的情况
      final oldIdx = text.indexOf('{"domain"');
      if (oldIdx < 0) return text;
      final end = text.indexOf('}', oldIdx);
      if (end < 0) return text;
      return text.substring(0, oldIdx).trim();
    }
    final candidate = text.substring(idx + 1);
    if (candidate.startsWith('{') && candidate.endsWith('}')) {
      try {
        // 校验是否为合法 JSON
        jsonDecode(candidate);
        return text.substring(0, idx).trim();
      } catch (_) {}
    }
    return text;
  }

  /// 解码 \\uXXXX 转义序列为实际字符（前端兜底）。
  /// 正确处理代理对（surrogate pair）：\\uD83C\\uDF3F → 🌿 (U+1F33F)。
  static String decodeUnicodeEscapes(String text) {
    if (text == null || text.isEmpty) return '';
    return text.replaceAllMapped(
      RegExp(r'\\u([0-9a-fA-F]{4})'),
      (match) {
        final code = int.parse(match.group(1)!, radix: 16);
        // 检测高代理（0xD800-0xDBFF），与后续的低代理（0xDC00-0xDFFF）合并
        if (code >= 0xD800 && code <= 0xDBFF) {
          final rest = match.input.substring(match.end);
          final lowMatch = RegExp(r'^\\u([0-9a-fA-F]{4})').firstMatch(rest);
          if (lowMatch != null) {
            final low = int.parse(lowMatch.group(1)!, radix: 16);
            if (low >= 0xDC00 && low <= 0xDFFF) {
              final codepoint = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
              return String.fromCharCode(codepoint);
            }
          }
          // 不是合法代理对，降级为单字符
        }
        return String.fromCharCode(code);
      },
    );
  }
}
