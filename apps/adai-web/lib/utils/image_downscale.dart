import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'image_header.dart';

/// 图片降采样（REVIEW `W-P3-9`，2026-09-26 夜间批；按前端审查 P1-1 修正类型声明；
/// 按 REVIEW `P2-UI14`（2026-10-05）改为「免全图解码」路径）。
///
/// **问题**：Web 端 `FilePicker.pickFiles(withData: true)` 会把**整份原始字节**读进内存再上传——
/// 手机原图常在 5~10MB / 4000px 级别，一次投递最多 3 张，内存峰值与上传耗时都被原图放大。
///
/// **治法（保守：绝不让结果变差）**：
/// - 先做**零解码**的头部探测（[ImageHeader.size]，纯 Dart 读字节）：
///   长边 ≤ [defaultMaxEdge] **且** 字节 ≤ [defaultMaxBytes] → **原样返回**（零解码、零重编码）；
/// - 需要真处理时**只解码一次**：拿到解码后的真实尺寸后，在**同一张已解码图**上用 `Canvas`
///   缩放到长边 [defaultMaxEdge]，再做一次 PNG 编码（旧的 probe + 目标解码是**两次整图解码**）；
/// - 降采样结果**比原字节还大**时仍返回原字节（PNG 对照片可能不划算——宁可不缩，也不让上传变大）；
/// - 任何解码/编码失败（不是图片 / 字节损坏）→ 返回原字节，**不阻断**用户选图。
///
/// **平台核实（REVIEW `P2-UI14` 的两个诉求在 Flutter Web 上都做不到「教科书答案」）**：
/// 本工程**只有 Web 一个目标平台**（`apps/adai-web/` 下只有 `web/`，无 android/ios/macos/linux/windows；
/// 全仓 `lib/` 无任何 `kIsWeb` 分支、无 `dart:isolate` 用法），而：
/// 1. **编码无法移出主 isolate**：`dart:isolate` 在 dart2js/dart2wasm 下
///    `Isolate.spawn`/`ReceivePort`/`Isolate.run` 全部 `throw UnsupportedError`
///    （`dart-sdk/lib/_internal/js_runtime/lib/isolate_patch.dart:54,191`）；
///    `compute()` 的 web 实现是同线程直调 `return callback(message)`，只让出一帧
///    （`flutter/lib/src/foundation/_isolates_web.dart`）——**并行度为 0**。
///    故 `toByteData(png)` 仍在主线程，本轮**不写假的 isolate 分支、不吞异常**。
/// 2. **「按目标尺寸直接解码」在 dart:ui 层面不存在**：web 的
///    `ImageDescriptor.encoded().width/height` 直接抛 `UnsupportedError`
///    （`flutter_web_sdk/lib/ui/painting.dart:1080`）；`instantiateImageCodecWithSize()` 内部仍是
///    「整图解码一次拿尺寸 + 按目标再解一次」（同文件 :757）；带 `targetWidth/targetHeight` 的
///    `instantiateImageCodec()` 在 CanvasKit 下是**先全解、再用 DOM canvas 缩放**
///    （`_engine/engine/image.dart:39` `CkResizingCodec`）。所以能做到的「免全图解码」=
///    **小图零解码（头部探测）+ 大图从「整图解码两次」降到「解码一次」**；真正的
///    「解码期降采样」需要绕开 dart:ui 走 WebCodecs/DOM（Safari 无 `ImageDecoder`），超出本批保守口径。
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
  ///
  /// [onStage] 是可选的**诊断钩子**，按发生顺序回调内部阶段名：
  /// `'header'`（零解码的头部探测成功）· `'decode'`（发生了一次整图解码）。
  /// 它只用于观测调用路径（测试断言「小图不做整图解码」/ 性能诊断），**不传则零开销、不改变行为**。
  static Future<({Uint8List bytes, String mime})> run(
    Uint8List bytes, {
    int maxEdge = defaultMaxEdge,
    int maxBytes = defaultMaxBytes,
    void Function(String stage)? onStage,
  }) async {
    if (bytes.isEmpty) return (bytes: bytes, mime: '');

    // ① 零解码探测：体积不超限时先读图片头——小图到此为止，一次像素解码都不做。
    if (bytes.length <= maxBytes) {
      final header = ImageHeader.size(bytes);
      if (header != null) {
        onStage?.call('header');
        if (math.max(header.width, header.height) <= maxEdge) {
          return (bytes: bytes, mime: '');
        }
      }
    }

    ui.Codec? codec;
    ui.Image? decoded;
    ui.Picture? picture;
    ui.Image? scaled;
    try {
      // ② 只解码一次：尺寸取自解码结果本身（EXIF 方向、格式差异都由引擎兜底）。
      onStage?.call('decode');
      codec = await ui.instantiateImageCodec(bytes);
      decoded = (await codec.getNextFrame()).image;

      final longEdge = math.max(decoded.width, decoded.height);
      if (longEdge <= maxEdge && bytes.length <= maxBytes) return (bytes: bytes, mime: '');

      final ratio = maxEdge / longEdge;
      final targetWidth = math.max(1, (decoded.width * ratio).round());
      final targetHeight = math.max(1, (decoded.height * ratio).round());

      // ③ 在**已解码的这张图**上直接缩放（不再整图解码第二次）。
      final recorder = ui.PictureRecorder();
      ui.Canvas(recorder).drawImageRect(
        decoded,
        ui.Rect.fromLTWH(0, 0, decoded.width.toDouble(), decoded.height.toDouble()),
        ui.Rect.fromLTWH(0, 0, targetWidth.toDouble(), targetHeight.toDouble()),
        ui.Paint()..filterQuality = ui.FilterQuality.medium,
      );
      picture = recorder.endRecording();
      scaled = await picture.toImage(targetWidth, targetHeight);

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
        picture?.dispose();
        scaled?.dispose();
        decoded?.dispose();
        codec?.dispose();
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
