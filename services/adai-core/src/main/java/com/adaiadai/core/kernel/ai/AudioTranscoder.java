package com.adaiadai.core.kernel.ai;

/**
 * AudioTranscoder — 音频转码端口（端口定义归 kernel，实现归 infrastructure）。
 * <p>
 * **为什么必须有这一步**（pitfall「B站 dash 音频直传 ASR 必失败」，2026-09-12 实测）：
 * B站 {@code playurl} 的 {@code dash.audio[].baseUrl} 下的是 **fMP4 分片容器**
 * （{@code ftyp}+{@code moov}+{@code moof}/{@code mdat}），ASR 解不了；只改后缀名也无效。
 * 必须先转码：{@code ffmpeg -i x.m4s -vn -ac 1 -ar 16000 -b:a 32k x.mp3}。
 * <p>
 * **部署前置**：生产服务器（Ubuntu 24.04）**未装 ffmpeg** → {@link #available()} 为 false 时
 * 转写链路应 fail-visible 提示，而不是产半成品。
 */
public interface AudioTranscoder {

    /** ffmpeg 是否可用（可执行文件存在）。 */
    boolean available();

    /**
     * 把任意容器音频转成 ASR 可解的 16k 单声道 mp3。
     *
     * @param input       源音频字节（B站为 fMP4 分片）
     * @param inputFormat 源容器后缀（如 {@code m4s}），仅用于临时文件命名
     * @return 转码后字节（mp3）
     * @throws IllegalStateException ffmpeg 不可用或转码失败（消息为人话）
     */
    byte[] toAsrCompatible(byte[] input, String inputFormat);
}
