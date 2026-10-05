import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_web/utils/image_header.dart';

/// REVIEW `P2-UI14`（2026-10-05）：`ImageHeader` 是「不解码就拿到尺寸」的那一步。
///
/// 判据：
/// ① 四种主流容器的头部都能读出**声明尺寸**（PNG 真图 + JPEG/GIF/WebP 手工头）；
/// ② 不认识的格式 / 结构不合法 / 字节被截断 → **一律 null**（绝不猜，交给调用方降级解码）；
/// ③ 读头不碰像素：解析一张只有头、没有有效像素数据的图也不抛。
Future<Uint8List> _realPng(int width, int height) async {
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

/// 最小合法 JPEG 段结构：SOI + APP0(JFIF) + SOF0 + EOI（不含任何熵编码数据）。
Uint8List _jpegHeader(int width, int height) => Uint8List.fromList(<int>[
      0xFF, 0xD8, // SOI
      0xFF, 0xE0, 0x00, 0x10, // APP0，长度 16
      0x4A, 0x46, 0x49, 0x46, 0x00, // 'JFIF\0'
      0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // 版本/密度
      0xFF, 0xC0, 0x00, 0x11, 0x08, // SOF0，长度 17，精度 8
      (height >> 8) & 0xFF, height & 0xFF, // 高（大端）
      (width >> 8) & 0xFF, width & 0xFF, // 宽（大端）
      0x03, // 3 个分量
      0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
      0xFF, 0xD9, // EOI
    ]);

Uint8List _gifHeader(int width, int height) => Uint8List.fromList(<int>[
      0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
      width & 0xFF, (width >> 8) & 0xFF, // 逻辑屏宽（小端）
      height & 0xFF, (height >> 8) & 0xFF, // 逻辑屏高（小端）
      0x00, 0x00, 0x00,
    ]);

Uint8List _webpVp8x(int width, int height) {
  final b = Uint8List(30);
  b.setAll(0, <int>[0x52, 0x49, 0x46, 0x46]); // RIFF
  b.setAll(8, <int>[0x57, 0x45, 0x42, 0x50]); // WEBP
  b.setAll(12, <int>[0x56, 0x50, 0x38, 0x58]); // VP8X
  b.setAll(16, <int>[0x0A, 0x00, 0x00, 0x00]); // chunk size
  final w = width - 1;
  final h = height - 1;
  b[24] = w & 0xFF;
  b[25] = (w >> 8) & 0xFF;
  b[26] = (w >> 16) & 0xFF;
  b[27] = h & 0xFF;
  b[28] = (h >> 8) & 0xFF;
  b[29] = (h >> 16) & 0xFF;
  return b;
}

Uint8List _webpVp8l(int width, int height) {
  final b = Uint8List(25);
  b.setAll(0, <int>[0x52, 0x49, 0x46, 0x46]);
  b.setAll(8, <int>[0x57, 0x45, 0x42, 0x50]);
  b.setAll(12, <int>[0x56, 0x50, 0x38, 0x4C]); // VP8L
  b[20] = 0x2F; // 签名
  final bits = (width - 1) | ((height - 1) << 14);
  b[21] = bits & 0xFF;
  b[22] = (bits >> 8) & 0xFF;
  b[23] = (bits >> 16) & 0xFF;
  b[24] = (bits >> 24) & 0xFF;
  return b;
}

Uint8List _webpVp8(int width, int height) {
  final b = Uint8List(30);
  b.setAll(0, <int>[0x52, 0x49, 0x46, 0x46]);
  b.setAll(8, <int>[0x57, 0x45, 0x42, 0x50]);
  b.setAll(12, <int>[0x56, 0x50, 0x38, 0x20]); // 'VP8 '
  b[23] = 0x9D;
  b[24] = 0x01;
  b[25] = 0x2A;
  b[26] = width & 0xFF;
  b[27] = (width >> 8) & 0xFF;
  b[28] = height & 0xFF;
  b[29] = (height >> 8) & 0xFF;
  return b;
}

void main() {
  test('PNG：从 IHDR 读出真实声明尺寸（不经过解码器）', () async {
    expect(ImageHeader.size(await _realPng(2400, 600)), (width: 2400, height: 600));
    expect(ImageHeader.size(await _realPng(37, 91)), (width: 37, height: 91));
  });

  test('JPEG：扫到 SOF0 读宽高（APP0 等前置段被整段跳过）', () {
    expect(ImageHeader.size(_jpegHeader(4032, 3024)), (width: 4032, height: 3024));
    expect(ImageHeader.size(_jpegHeader(1, 1)), (width: 1, height: 1));
  });

  test('GIF：逻辑屏宽高（小端）', () {
    expect(ImageHeader.size(_gifHeader(320, 240)), (width: 320, height: 240));
  });

  test('WebP：VP8X / VP8L / VP8 三种头都能读', () {
    expect(ImageHeader.size(_webpVp8x(1920, 1080)), (width: 1920, height: 1080));
    expect(ImageHeader.size(_webpVp8l(800, 1600)), (width: 800, height: 1600));
    expect(ImageHeader.size(_webpVp8(640, 480)), (width: 640, height: 480));
  });

  test('不认识 / 不合法 / 截断 → null（绝不猜，交给调用方降级解码）', () {
    expect(ImageHeader.size(Uint8List(0)), isNull);
    expect(ImageHeader.size(Uint8List.fromList(List<int>.generate(64, (i) => i))), isNull);
    // 截断的 PNG：签名对但不足 24 字节
    expect(ImageHeader.size(Uint8List.fromList(<int>[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])), isNull);
    // 签名对、但 IHDR 位置不是 'IHDR'
    final fake = Uint8List(24);
    fake.setAll(0, <int>[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
    expect(ImageHeader.size(fake), isNull);
    // JPEG 只有 SOI、没有 SOF
    expect(ImageHeader.size(Uint8List.fromList(<int>[0xFF, 0xD8, 0xFF, 0xD9])), isNull);
    // 零宽高的 GIF 头
    expect(ImageHeader.size(_gifHeader(0, 0)), isNull);
  });
}
