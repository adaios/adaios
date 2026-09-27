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
}
