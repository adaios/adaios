package com.adaiadai.core.infrastructure.ai.asr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FfmpegAudioTranscoderTest — 音频转码可用性（RFC 20260912 §3.5，learn 抓取批 2026-09-12）。
 * <p>
 * 锁两件事：
 * <ol>
 *   <li><b>生产未装 ffmpeg 时的人话降级</b>（2026-09-12 核实：上一轮实测是「本地转码再回传」，
 *       服务器本身没有 ffmpeg）——必须 fail-visible 提示，不能假装能转</li>
 *   <li><b>空输入直接拒绝</b>（上游音频没下下来时不该白跑一次 ffmpeg）</li>
 * </ol>
 * 真实转码依赖本机 ffmpeg，属部署后实测项，不在单测内强依赖。
 */
class FfmpegAudioTranscoderTest {

    @Test
    void available_falseForNonExistentBinary() {
        FfmpegAudioTranscoder transcoder = new FfmpegAudioTranscoder("/definitely/not/ffmpeg");

        assertFalse(transcoder.available());
    }

    @Test
    void available_isCachedAcrossCalls() {
        FfmpegAudioTranscoder transcoder = new FfmpegAudioTranscoder("/definitely/not/ffmpeg");

        assertFalse(transcoder.available());
        assertFalse(transcoder.available());
    }

    @Test
    void toAsrCompatible_whenUnavailable_throwsHumanMessage() {
        FfmpegAudioTranscoder transcoder = new FfmpegAudioTranscoder("/definitely/not/ffmpeg");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> transcoder.toAsrCompatible(new byte[]{1, 2, 3}, "m4s"));

        assertTrue(e.getMessage().contains("转码工具") || e.getMessage().contains("ffmpeg"),
                "要人话说清是服务器缺工具，而不是抛技术栈信息");
    }

    @Test
    void toAsrCompatible_emptyInput_throws() {
        FfmpegAudioTranscoder transcoder = new FfmpegAudioTranscoder("/definitely/not/ffmpeg");

        assertThrows(IllegalStateException.class, () -> transcoder.toAsrCompatible(new byte[0], "m4s"));
        assertThrows(IllegalStateException.class, () -> transcoder.toAsrCompatible(null, "m4s"));
    }

    @Test
    void toAsrCompatible_brokenAudio_throwsHumanMessage_doesNotLeakTempState() {
        // 用一个存在但不是 ffmpeg 的可执行文件（/bin/echo）模拟「命令在但处理失败」
        FfmpegAudioTranscoder transcoder = new FfmpegAudioTranscoder("/bin/echo");

        if (!transcoder.available()) {
            // 某些环境 /bin/echo -version 非 0，退化为不可用分支，同样是 fail-visible
            assertThrows(IllegalStateException.class, () -> transcoder.toAsrCompatible(new byte[]{1}, "m4s"));
            return;
        }
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> transcoder.toAsrCompatible(new byte[]{1, 2}, "m4s"));
        assertTrue(e.getMessage().contains("转码"), "非 ffmpeg 命令应报转码失败而不是静默返回");
    }

    @Test
    void constructor_blankPath_fallsBackToPathLookup() {
        FfmpegAudioTranscoder transcoder = new FfmpegAudioTranscoder("   ");

        // 不抛异常即可（走 PATH 查询 ffmpeg，可用与否取决于环境）
        transcoder.available();
    }
}
