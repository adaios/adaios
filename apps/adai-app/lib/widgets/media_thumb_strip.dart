import 'dart:typed_data';
import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import 'full_image_dialog.dart';

/// 多图缩略图条（2026-09-22 多图批）。
///
/// 一次投递（N 张图 + 可选一句话）= 一个回合 = 一张卡——本组件负责在**同一张卡内并列
/// 展示该回合的全部图**：96px 级缩略图横向并排（超出宽度可横滑）+「共 N 张」角标，
/// 点击任一图弹全图（沿用 [showFullImageDialog] 公共路径）。
///
/// 图片来源优先级：
/// - [bytesList] 非空 → 本地字节（上传占位卡，原图尚未落盘，用内存图做即时预览）；
/// - 否则用 [urls]（已落盘的 `GET /records/media/{id}`）。
///
/// FeedCard 与时间线共用同一实现，避免「卡片并列、时间线单图」两处形态漂移。
class MediaThumbStrip extends StatelessWidget {
  const MediaThumbStrip({
    super.key,
    this.urls = const [],
    this.bytesList = const [],
    this.headers,
    this.size = 96,
    this.radius = 8,
  });

  /// 原图 URL 列表（按上传顺序）。
  final List<String> urls;

  /// 本地字节列表（上传占位卡预览；非空时优先于 [urls]）。
  final List<Uint8List> bytesList;

  /// 媒体请求鉴权头（Image.network 需显式带 Bearer）。
  final Map<String, String>? headers;

  /// 单图边长（Feed 96，时间线可更小）。
  final double size;

  /// 缩略图圆角。
  final double radius;

  /// 实际要渲染的张数（本地字节优先）。
  int get count => bytesList.isNotEmpty ? bytesList.length : urls.length;

  @override
  Widget build(BuildContext context) {
    if (count == 0) return const SizedBox.shrink();
    return Stack(
      children: [
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (var i = 0; i < count; i++) ...[
                if (i > 0) const SizedBox(width: 6),
                _thumb(context, i),
              ],
            ],
          ),
        ),
        // 「共 N 张」角标（单图不显示——一个回合一张图没有并列语义）
        if (count > 1)
          Positioned(
            top: 4,
            right: 4,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: Colors.black.withAlpha(150),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text('共 $count 张',
                  style: const TextStyle(fontSize: 11, color: AppColors.darkGrey1)),
            ),
          ),
      ],
    );
  }

  Widget _thumb(BuildContext context, int i) {
    final bytes = i < bytesList.length ? bytesList[i] : null;
    if (bytes != null) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: Image.memory(
          bytes,
          width: size,
          height: size,
          fit: BoxFit.cover,
          cacheWidth: (size * 2).round(), // 降采样解码（Web 大图解码慢/易失败，与 input_bar 同模式）
          gaplessPlayback: true, // 解码期间保留旧帧，进度刷新 rebuild 不闪烁
          errorBuilder: (_, _, _) => _broken(),
        ),
      );
    }
    final url = i < urls.length ? urls[i] : '';
    if (url.isEmpty) return const SizedBox.shrink();
    return GestureDetector(
      onTap: () => showFullImageDialog(context, url: url, headers: headers),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: Image.network(
          url,
          headers: headers,
          width: size,
          height: size,
          cacheWidth: (size * 2).round(),
          fit: BoxFit.cover,
          errorBuilder: (_, _, _) => _broken(),
          loadingBuilder: (_, child, progress) => progress == null
              ? child
              : Container(
                  width: size,
                  height: size,
                  color: AppColors.darkSurface2,
                  child: const Center(
                    child: SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen),
                    ),
                  ),
                ),
        ),
      ),
    );
  }

  Widget _broken() => Container(
        width: size,
        height: size,
        color: AppColors.darkSurface2,
        child: const Icon(Icons.broken_image_outlined, size: 20, color: AppColors.darkGrey5),
      );
}
