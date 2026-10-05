import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_web/utils/image_downscale.dart';

/// REVIEW `W-P3-9` + 前端审查 P1-1（2026-09-26）：Web 端选图后、上传前降采样。
///
/// 判据四条（对应实现的保守口径）：
/// ① 小图**不碰**（零重编码，`mime` 为空 = 沿用原类型）；
/// ② 长边超限才降采样，且**结果不会比原图大**（变大则回退原字节）；
/// ③ 非图片 / 损坏字节 → 原样返回（选图链路不该因为压缩失败而断）；
/// ④ **重编码后必须如实报告 `image/png`**（否则上层按原扩展名声明 jpeg → 后端落错扩展名、
///    VLM 收到 `data:image/jpeg` 包着 PNG 字节）。
Future<Uint8List> _png(int width, int height) async {
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  canvas.drawRect(
    Rect.fromLTWH(0, 0, width.toDouble(), height.toDouble()),
    Paint()..color = const Color(0xFF3366CC),
  );
  final picture = recorder.endRecording();
  final image = await picture.toImage(width, height);
  final data = await image.toByteData(format: ui.ImageByteFormat.png);
  image.dispose();
  picture.dispose();
  return data!.buffer.asUint8List();
}

Future<int> _longEdge(Uint8List bytes) async {
  final codec = await ui.instantiateImageCodec(bytes);
  final image = (await codec.getNextFrame()).image;
  final edge = math.max(image.width, image.height);
  image.dispose();
  codec.dispose();
  return edge;
}

/// 把 PNG 的 IHDR 之后（含 IDAT）的字节打乱——**图片头仍然合法**，像素数据已经解不出来。
Uint8List _corruptPixels(Uint8List png) {
  final copy = Uint8List.fromList(png);
  for (var i = 40; i < copy.length - 12; i++) {
    copy[i] = copy[i] ^ 0xFF;
  }
  return copy;
}

void main() {
  test('小图原样返回：字节不变且 mime 为空（沿用原类型）', () async {
    final small = await _png(800, 600);
    final out = await ImageDownscale.run(small);
    expect(identical(out.bytes, small), isTrue);
    expect(out.mime, isEmpty, reason: '没重编码就不该改类型声明');
  });

  test('长边超限 → 降采样到 1600，且如实报告 image/png（体积不增）', () async {
    final big = await _png(2400, 600);
    final out = await ImageDownscale.run(big);

    expect(identical(out.bytes, big), isFalse, reason: '超限图必须被处理');
    expect(await _longEdge(out.bytes), ImageDownscale.defaultMaxEdge, reason: '长边收到 1600');
    expect(out.bytes.length, lessThanOrEqualTo(big.length), reason: '结果不得比原图更大');
    expect(out.mime, ImageDownscale.pngMime,
        reason: '重编码后字节就是 PNG —— 必须如实报告，否则上层会按原扩展名声明 jpeg');
  });

  test('非图片 / 损坏字节 → 原样返回，不抛（选图链路不断）', () async {
    final junk = Uint8List.fromList(List<int>.generate(64, (i) => i));
    final out = await ImageDownscale.run(junk);
    expect(identical(out.bytes, junk), isTrue);
    expect(out.mime, isEmpty);
    expect((await ImageDownscale.run(Uint8List(0))).bytes, isEmpty);
  });

  test('asPngName：扩展名同步成 png（无扩展名/多点名都能处理）', () {
    expect(ImageDownscale.asPngName('IMG_1234.jpg'), 'IMG_1234.png');
    expect(ImageDownscale.asPngName('shot.HEIC'), 'shot.png');
    expect(ImageDownscale.asPngName('noext'), 'noext.png');
    expect(ImageDownscale.asPngName('a.b.jpeg'), 'a.b.png');
  });

  // ——— REVIEW P2-UI14（2026-10-05）：免全图解码，用调用路径（onStage 诊断钩子）做间接证据 ———
  //
  // `dart:ui` 在 Web 上没有「只读头部」通道（`ImageDescriptor.width` 在 web 直接抛 UnsupportedError、
  // `instantiateImageCodecWithSize` 内部仍是两次整图解码），所以这里用 `ImageHeader` 纯字节解析
  // 顶掉旧实现「先整图 decode 只为量宽高」的那一次解码。
  test('P2-UI14 小图：只走零解码的头部探测，绝不整图解码', () async {
    final small = await _png(800, 600);
    final stages = <String>[];
    final out = await ImageDownscale.run(small, onStage: stages.add);

    expect(identical(out.bytes, small), isTrue);
    expect(out.mime, isEmpty);
    expect(stages, <String>['header'],
        reason: '出现 decode 就说明又做了一次整图解码——这正是本条目要消灭的动作');
  });

  test('P2-UI14 大图：先读头 → 整图解码**只发生一次**（旧实现为两次）', () async {
    final big = await _png(2400, 600);
    final stages = <String>[];
    final out = await ImageDownscale.run(big, onStage: stages.add);

    expect(stages, <String>['header', 'decode'],
        reason: 'header 判定超限后只允许解码一次：那次解码是「拿真实尺寸 + 缩放」所必需，'
            '不再是「先整图解一遍量宽高、再整图解一遍缩放」');
    expect(await _longEdge(out.bytes), ImageDownscale.defaultMaxEdge);
    expect(out.mime, ImageDownscale.pngMime);
  });

  test('P2-UI14 体积已超 maxBytes：不必探测头部，直接解码（跳过 header 阶段）', () async {
    final png = await _png(400, 200);
    final stages = <String>[];
    final out = await ImageDownscale.run(png, maxEdge: 100, maxBytes: 1, onStage: stages.add);
    expect(stages, <String>['decode'], reason: '小图判定要求「长边不超限 **且** 字节不超限」，体积都超了就无须读头');
    expect(await _longEdge(out.bytes), 100, reason: '跳过读头也照样降采样');
  });

  test('P2-UI14 头部合法但像素损坏的小图：零解码直达，不抛、原样返回', () async {
    final broken = _corruptPixels(await _png(800, 600));
    final stages = <String>[];
    final out = await ImageDownscale.run(broken, onStage: stages.add);

    expect(stages, <String>['header'], reason: '头部已证明是小图 → 根本不碰解码器，损坏与否都无关');
    expect(identical(out.bytes, broken), isTrue);
    expect(out.mime, isEmpty);
  });

  test('P2-UI14 头部认不出来（非 PNG/JPEG/GIF/WebP）→ 老实降级解码，不猜尺寸', () async {
    // 破坏 PNG 签名：ImageHeader 认不出 → 只能走解码探测（旧路径）；而解码器同样认不出 → 原样返回
    final masked = Uint8List.fromList(await _png(800, 600))..[1] = 0x00;
    final stages = <String>[];
    final out = await ImageDownscale.run(masked, onStage: stages.add);

    expect(stages, <String>['decode'], reason: '认不出格式就解码，绝不凭猜测提前返回');
    expect(identical(out.bytes, masked), isTrue);
    expect(out.mime, isEmpty);
  });
}
