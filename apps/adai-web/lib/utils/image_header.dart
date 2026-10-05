import 'dart:typed_data';

/// 图片头解析：**不解码像素**，只用纯 Dart 读字节拿到声明尺寸。
///
/// **为什么需要它（REVIEW `P2-UI14`，2026-10-05 在本机 Flutter 3.47.4 上核实）**：
/// `dart:ui` 官方那两条「只读头部」通道在 **Flutter Web 上都不成立**——
/// - `ui.ImageDescriptor.encoded()`：web 实现只保存字节，`width`/`height` 直接
///   `throw UnsupportedError('ImageDescriptor.width is not supported on web.')`
///   （`flutter_web_sdk/lib/ui/painting.dart:1064-1081`，注释也写明 “On the Web, this is only
///   supported for [raw] images”）——照抄这条会在 web 上**每次都抛异常并静默退化成「永不降采样」**；
/// - `ui.instantiateImageCodecWithSize()`：web 实现内部就是「先整图 `instantiateImageCodec` +
///   `getNextFrame()` 拿尺寸，再按目标尺寸解第二次」（同文件 :757-784），一次都没省。
///
/// 所以这里按容器格式自己读头部——只覆盖主流格式，**任何不确定都返回 null**（绝不猜），
/// 由调用方降级回「解码一次」的老路径，行为不会比改动前差。
///
/// 覆盖：PNG（IHDR）· JPEG（SOF0/1/2/3/5/6/7/9/10/11/13/14/15）· GIF（Logical Screen
/// Descriptor）· WebP（VP8X / VP8L / VP8 ）。
class ImageHeader {
  const ImageHeader._();

  /// 返回图片头声明的像素尺寸；格式不在覆盖范围或结构不合法 → `null`。
  ///
  /// 注意语义：这是**编码数据声明的存储尺寸**（与 EXIF 方向无关，长边不受旋转影响）。
  static ({int width, int height})? size(Uint8List bytes) {
    return _pngSize(bytes) ?? _jpegSize(bytes) ?? _gifSize(bytes) ?? _webpSize(bytes);
  }

  /// PNG：8 字节签名 + IHDR（长度 4 + 'IHDR' 4 + width 4 + height 4，大端）。
  static ({int width, int height})? _pngSize(Uint8List b) {
    if (b.length < 24) return null;
    const signature = <int>[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
    for (var i = 0; i < 8; i++) {
      if (b[i] != signature[i]) return null;
    }
    // b[8..11] 是 IHDR 的长度（恒为 13），b[12..15] 必须是 'IHDR'
    if (b[12] != 0x49 || b[13] != 0x48 || b[14] != 0x44 || b[15] != 0x52) return null;
    return _ok(_be32(b, 16), _be32(b, 20));
  }

  /// GIF：`GIF87a`/`GIF89a` + Logical Screen Descriptor 的 2 字节小端宽、高。
  static ({int width, int height})? _gifSize(Uint8List b) {
    if (b.length < 10) return null;
    if (b[0] != 0x47 || b[1] != 0x49 || b[2] != 0x46 || b[3] != 0x38) return null;
    if (b[4] != 0x37 && b[4] != 0x39) return null;
    if (b[5] != 0x61) return null;
    return _ok(b[6] | (b[7] << 8), b[8] | (b[9] << 8));
  }

  /// JPEG：扫顶层 marker 找 SOF（`0xFFC0`–`0xFFCF`，排除 DHT/JPG/DAC），
  /// 段内 `precision(1) height(2, 大端) width(2, 大端)`。APP1/EXIF 缩略图在段内被整段跳过。
  static ({int width, int height})? _jpegSize(Uint8List b) {
    if (b.length < 4 || b[0] != 0xFF || b[1] != 0xD8) return null;
    var offset = 2;
    while (offset + 4 <= b.length) {
      if (b[offset] != 0xFF) return null;
      final marker = b[offset + 1];
      if (marker == 0xFF) {
        // 段间填充字节
        offset++;
        continue;
      }
      // 无长度字段的 marker：TEM(0x01)、RSTn(0xD0-0xD7)、SOI(0xD8)、EOI(0xD9)
      if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
        offset += 2;
        continue;
      }
      if (marker == 0xD9 || marker == 0xDA) return null; // EOI / 熵编码起点前没找到 SOF
      final length = (b[offset + 2] << 8) | b[offset + 3];
      if (length < 2) return null;
      final isSof = marker >= 0xC0 &&
          marker <= 0xCF &&
          marker != 0xC4 && // DHT
          marker != 0xC8 && // JPG
          marker != 0xCC; // DAC
      if (isSof) {
        if (offset + 9 > b.length) return null;
        final height = (b[offset + 5] << 8) | b[offset + 6];
        final width = (b[offset + 7] << 8) | b[offset + 8];
        return _ok(width, height);
      }
      offset += 2 + length;
    }
    return null;
  }

  /// WebP：`RIFF....WEBP` + 首个 chunk（VP8X 扩展 / VP8L 无损 / VP8 有损）。
  static ({int width, int height})? _webpSize(Uint8List b) {
    if (b.length < 20) return null;
    if (b[0] != 0x52 || b[1] != 0x49 || b[2] != 0x46 || b[3] != 0x46) return null;
    if (b[8] != 0x57 || b[9] != 0x45 || b[10] != 0x42 || b[11] != 0x50) return null;
    if (b[12] != 0x56 || b[13] != 0x50 || b[14] != 0x38) return null;
    switch (b[15]) {
      case 0x58: // 'X' → VP8X：24..26 画布宽-1，27..29 画布高-1（3 字节小端）
        if (b.length < 30) return null;
        return _ok(
          (b[24] | (b[25] << 8) | (b[26] << 16)) + 1,
          (b[27] | (b[28] << 8) | (b[29] << 16)) + 1,
        );
      case 0x4C: // 'L' → VP8L：20 是签名 0x2F，21..24 位打包（宽-1 低 14 位，高-1 次 14 位）
        if (b.length < 25 || b[20] != 0x2F) return null;
        final bits = b[21] | (b[22] << 8) | (b[23] << 16) | (b[24] << 24);
        return _ok((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1);
      case 0x20: // ' ' → VP8（有损）：23..25 起始码，26..27 宽、28..29 高（各 14 位有效）
        if (b.length < 30) return null;
        if (b[23] != 0x9D || b[24] != 0x01 || b[25] != 0x2A) return null;
        return _ok((b[26] | (b[27] << 8)) & 0x3FFF, (b[28] | (b[29] << 8)) & 0x3FFF);
      default:
        return null;
    }
  }

  static int _be32(Uint8List b, int offset) =>
      (b[offset] << 24) | (b[offset + 1] << 16) | (b[offset + 2] << 8) | b[offset + 3];

  static ({int width, int height})? _ok(int width, int height) =>
      width > 0 && height > 0 ? (width: width, height: height) : null;
}
