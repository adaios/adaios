package com.adaiadai.core.domain.learn;

import java.util.List;

/**
 * LearnSource — 外部内容源抓取结果（RFC 20260912 D 形态，learn 抓取批 2026-09-12）。
 * <p>
 * 服务端抓取一个内容页（B站视频 / 文章）后得到的中间产物：元数据 + 正文（或「需要转写」的
 * 音频线索）+ 待留痕的原始素材。**这是 D 形态与 B 形态的分水岭**——B 把「拿到素材」留给用户，
 * D 由服务端完成（RFC 20260829 §9.2 死因 3）。
 * <p>
 * 落 {@code _raw/} 的素材由 {@link RawAsset} 承载（元数据 json / 字幕 / 文章全文），
 * 转写稿由转写步骤另行留痕（源必留痕铁律：抓取产物一旦丢失不可重建）。
 *
 * @param platform          来源平台标识（{@code bilibili} / {@code article}）
 * @param sourceId          源稳定标识（BV 号 / 文章 URL 短哈希）——用于 {@code _raw/} 命名与
 *                          「同一素材只转写一次」幂等判定
 * @param url               原始链接
 * @param title             标题（可空）
 * @param author            作者/UP 主（可空）
 * @param published         发布日期 yyyy-MM-dd（可空）
 * @param text              正文/字幕文本；**空 = 需要转写**
 * @param needsTranscription 是否需要云端转写（无字幕视频 → true）
 * @param audioUrl          需转写时的音频地址（可空）
 * @param durationSeconds   内容时长（秒）；0 = 未知（费用预估用）
 * @param rawAssets         待写入 {@code _raw/} 的文本类原始素材
 * @param textUnavailableReason 正文「取不到」的原因（**对抗审查 P1-2 修复新增**）：仅当
 *                          **接口报错/限流**这类**可重试**原因导致拿不到正文时非空。
 *                          与「确实没有字幕」（空列表，属正常情况→走转写）严格区分——
 *                          否则会把可重试的报错降级成「花钱转写」：既多花钱，又不算 fail-visible。
 */
public record LearnSource(
        String platform,
        String sourceId,
        String url,
        String title,
        String author,
        String published,
        String text,
        boolean needsTranscription,
        String audioUrl,
        int durationSeconds,
        List<RawAsset> rawAssets,
        String textUnavailableReason) {

    public LearnSource {
        rawAssets = rawAssets == null ? List.of() : List.copyOf(rawAssets);
    }

    /** 兼容构造器（不含「正文取不到原因」= 无异常，语义同旧调用）。 */
    public LearnSource(String platform, String sourceId, String url, String title, String author,
                       String published, String text, boolean needsTranscription, String audioUrl,
                       int durationSeconds, List<RawAsset> rawAssets) {
        this(platform, sourceId, url, title, author, published, text, needsTranscription, audioUrl,
                durationSeconds, rawAssets, null);
    }

    /** 是否已拿到可结构化的正文（有字幕/文章全文）。 */
    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /** 正文是否因**可重试原因**（接口报错/限流）缺失——此时不应进入付费转写分支。 */
    public boolean textBlockedByError() {
        return textUnavailableReason != null && !textUnavailableReason.isBlank();
    }

    /** 待留痕的原始素材（文件名 + 文本内容）；文件名落 {@code learn/_raw/}。 */
    public record RawAsset(String name, String content) {}
}
