import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

/// 图片降采样（REVIEW `W-P3-9`，2026-09-26 夜间批；随后按前端审查 P1-1 修正类型声明）。
///
/// **问题**：Web 端 `FilePicker.pickFiles(withData: true)` 会把**整份原始字节**读进内存再上传——
/// 手机原图常在 5~10MB / 4000px 级别，一次投递最多 3 张，内存峰值与上传耗时都被原图放大。
///
/// **治法（保守：绝不让结果变差）**：
/// - 长边 ≤ [defaultMaxEdge] **且** 字节 ≤ [defaultMaxBytes] → **原样返回**（零重编码）；
/// - 否则用 `ui.instantiateImageCodec` 在**解码阶段**就降采样到长边 [defaultMaxEdge]，再编码回 PNG；
/// - 降采样结果**比原字节还大**时仍返回原字节（PNG 对照片可能不划算——宁可不缩，也不让上传变大）；
/// - 任何解码/编码失败（不是图片 / 字节损坏）→ 返回原字节，**不阻断**用户选图。
///
/// **返回值带 mime（前端审查 P1-1，2026-09-26）**：Flutter 只能编码 PNG，重编码后**字节类型已经变了**，
/// 上层若仍按原扩展名声明 `image/jpeg`，就会出现「PNG 字节 + jpeg 声明」——后端按声明落盘成 `.jpg`
/// （错误扩展名永久留在盘上），并把 `data:image/jpeg;base64,<PNG>` 交给视觉模型（可能直接报格式错）。
/// 所以这里如实告诉调用方输出类型：`mime` 非空表示**字节已按该类型重编码**，调用方必须同步改
/// filename 扩展名与 mimeType；`mime` 为空表示原字节原样返回（沿用原类型）。
class ImageDownscale {
  const ImageDownscale._();

  /// 降采样后的长边上限（够 VLM 看清截图与书页，又不至于把原图整份传上去）。
  static const int defaultMaxEdge = 1600;

  /// 低于这个体积且长边不超限就不动它（避免为小图做无谓的往返编码）。
  static const int defaultMaxBytes = 3 * 1024 * 1024;

  /// 输出类型（重编码后恒为 PNG）。
  static const String pngMime = 'image/png';

  /// 见类注释。任何异常都吞掉并返回原字节（选图链路不该因为压缩失败而断）。
  static Future<({Uint8List bytes, String mime})> run(
    Uint8List bytes, {
    int maxEdge = defaultMaxEdge,
    int maxBytes = defaultMaxBytes,
  }) async {
    if (bytes.isEmpty) return (bytes: bytes, mime: '');
    ui.Codec? probe;
    ui.Image? probeImage;
    ui.Codec? codec;
    ui.Image? scaled;
    try {
      probe = await ui.instantiateImageCodec(bytes);
      probeImage = (await probe.getNextFrame()).image;
      final srcWidth = probeImage.width;
      final srcHeight = probeImage.height;
      probeImage.dispose();
      probeImage = null;
      probe.dispose();
      probe = null;

      final longEdge = math.max(srcWidth, srcHeight);
      if (longEdge <= maxEdge && bytes.length <= maxBytes) return (bytes: bytes, mime: '');

      final ratio = maxEdge / longEdge;
      final targetWidth = math.max(1, (srcWidth * ratio).round());
      final targetHeight = math.max(1, (srcHeight * ratio).round());

      codec = await ui.instantiateImageCodec(
        bytes,
        targetWidth: targetWidth,
        targetHeight: targetHeight,
      );
      scaled = (await codec.getNextFrame()).image;
      final data = await scaled.toByteData(format: ui.ImageByteFormat.png);
      if (data == null) return (bytes: bytes, mime: '');

      final out = data.buffer.asUint8List();
      // 变大则回退原字节（PNG 对照片可能不划算）——此时类型未变，mime 留空
      if (out.length >= bytes.length) return (bytes: bytes, mime: '');
      return (bytes: out, mime: pngMime);
    } catch (_) {
      return (bytes: bytes, mime: '');
    } finally {
      // 异常路径也要释放（前端审查 P3-2）
      try {
        scaled?.dispose();
        codec?.dispose();
        probeImage?.dispose();
        probe?.dispose();
      } catch (_) {
        // 释放失败不影响结果
      }
    }
  }

  /// 把文件名扩展名改成 `png`（重编码后必须同步，否则盘上类型与内容不符）。
  static String asPngName(String name) {
    final dot = name.lastIndexOf('.');
    // 边界（对抗复核 P3-6）：`.jpg`（dot==0 的隐藏文件名）不能拼成 `.jpg.png`，空前缀也不能拼成 `.png`
    var base = dot > 0 ? name.substring(0, dot) : name;
    if (base.startsWith('.')) base = base.substring(1);
    if (base.isEmpty) base = 'image';
    return '$base.png';
  }
}
