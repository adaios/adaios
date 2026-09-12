package com.adaiadai.core.infrastructure.ai.asr;

import com.adaiadai.core.kernel.ai.AudioTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * FfmpegAudioTranscoder — ffmpeg 转码（端口实现，RFC 20260912 §3.5）。
 * <p>
 * **为什么必须转码**（pitfall「B站 dash 音频直传 ASR 必失败」，2026-09-12 实测）：B站
 * {@code dash.audio[].baseUrl} 下的是 fMP4 分片容器，ASR 解不了；只改后缀名无效。须先
 * {@code ffmpeg -i x.m4s -vn -ac 1 -ar 16000 -b:a 32k x.mp3}（28min → 6.7MB）。
 * <p>
 * **部署前置（重要，2026-09-12 核实）**：生产服务器（Ubuntu 24.04）**未安装 ffmpeg** ——
 * 上一轮实测是「经 SSH 拉到本地转码再回传」完成的。因此 {@link #available()} 在生产为
 * false 时，转写链路必须 fail-visible 提示（人话），**不得**产半成品卡片。装 ffmpeg 属
 * 生产变更（B8），须用户确认后再做。
 * <p>
 * 路径由 {@code adai.learn.ffmpeg-path} 配置（默认 {@code ffmpeg}，即走 PATH）。
 */
@Component
public class FfmpegAudioTranscoder implements AudioTranscoder {

    private static final Logger log = LoggerFactory.getLogger(FfmpegAudioTranscoder.class);
    private static final int TIMEOUT_SECONDS = 600;

    private final String ffmpegPath;
    private volatile Boolean cachedAvailable;

    public FfmpegAudioTranscoder(@Value("${adai.learn.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath.strip();
    }

    @Override
    public boolean available() {
        Boolean cached = cachedAvailable;
        if (cached != null) return cached;
        boolean ok;
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            ok = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("ffmpeg 不可用（{}）：{}", ffmpegPath, e.getMessage());
            ok = false;
        }
        cachedAvailable = ok;
        if (!ok) {
            log.warn("ffmpeg 不可用 —— 无字幕视频的转写路径将 fail-visible 提示（生产服务器需安装 ffmpeg）");
        }
        return ok;
    }

    @Override
    public byte[] toAsrCompatible(byte[] input, String inputFormat) {
        if (input == null || input.length == 0) {
            throw new IllegalStateException("音频内容为空，没法转码");
        }
        if (!available()) {
            throw new IllegalStateException("服务器上的转码工具（ffmpeg）不可用，暂时没法处理无字幕视频");
        }
        String suffix = (inputFormat == null || inputFormat.isBlank()) ? "m4s" : inputFormat.replaceAll("[^A-Za-z0-9]", "");
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("learn-asr-", "." + suffix);
            out = Files.createTempFile("learn-asr-", ".mp3");
            Files.write(in, input);

            Process p = new ProcessBuilder(ffmpegPath, "-y", "-i", in.toString(),
                    "-vn", "-ac", "1", "-ar", "16000", "-b:a", "32k", out.toString())
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("音频转码超时（文件过大？），可换个短一点的视频");
            }
            if (p.exitValue() != 0) {
                log.warn("ffmpeg 转码失败 | exit={} | {}", p.exitValue(),
                        output.length() > 500 ? output.substring(output.length() - 500) : output);
                throw new IllegalStateException("音频转码失败，暂时没法处理这个视频");
            }
            byte[] result = Files.readAllBytes(out);
            if (result.length == 0) {
                throw new IllegalStateException("音频转码结果为空，暂时没法处理这个视频");
            }
            log.info("音频转码完成 | {} → {} 字节（16k 单声道 mp3）", input.length, result.length);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("音频转码失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("音频转码被中断，请重试");
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }
}
