package com.adaiadai.core.domain.learn;

/**
 * LearnSourceFetcher — 外部内容源抓取端口（RFC 20260912 D 形态，learn 抓取批 2026-09-12）。
 * <p>
 * domain 端口（对齐 {@link LearnCardRepository} 的依赖倒置）：每种内容源一个实现，
 * 实现归 {@code infrastructure/fetch/}。
 * <p>
 * B8 授权边界：抓取仅限**内容页**（域名白名单见各实现 {@link #supports}），不触登录态接口、
 * 不绕付费墙与登录墙；抓取属读取动作（技能文档口径），转写属外向付费动作（另经费用闸）。
 */
public interface LearnSourceFetcher {

    /** 平台标识（{@code bilibili} / {@code article}），用于分流与日志。 */
    String platform();

    /** 是否由本实现处理该链接（域名白名单判定，不做网络请求）。 */
    boolean supports(String url);

    /**
     * 抓取内容源。
     *
     * @param url 内容页链接
     * @return 抓取结果（元数据 + 正文 或 待转写线索）
     * @throws LearnException 抓取失败（反爬/不存在/超时）——消息为**人话**，fail-visible
     */
    LearnSource fetch(String url);

    /**
     * 下载音频字节（仅视频类源支持；B站需带 Referer 头，否则 403）。
     * <p>
     * 默认不支持：非视频源调用即抛 {@link LearnException}。
     *
     * @param audioUrl {@link LearnSource#audioUrl()}
     * @return 音频字节（B站为 fMP4 分片，须 ffmpeg 转码后才可送 ASR）
     */
    default byte[] downloadAudio(String audioUrl) {
        throw new LearnException("该来源不支持音频下载");
    }
}
