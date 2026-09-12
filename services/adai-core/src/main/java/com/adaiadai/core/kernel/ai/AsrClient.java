package com.adaiadai.core.kernel.ai;

/**
 * AsrClient — 云端语音转写客户端抽象（端口定义，RFC 20260912 §3.5/§3.8）。
 * <p>
 * 端口定义归 kernel（对齐 {@link AiClient} 的依赖倒置：端口在 kernel，实现在
 * {@code infrastructure/ai/asr}）。
 * <p>
 * **为什么必须对接云端 ASR**（2026-09-12 实测）：B站视频多数没有可获取的字幕，
 * 「无字幕 → 转写」是必经路径而非降级路径；服务器 2核4G 不适合本地跑 whisper
 * （37min 音频会打满 CPU）。
 * <p>
 * **B8 边界**：转写把音频发给第三方且产生费用 → 调用前必须经费用闸（配额 + 单次预估 +
 * 用户确认），见 {@code application.LearnTranscriptionService}。
 */
public interface AsrClient {

    /**
     * 转写是否可用（凭证/配置齐备）。
     * <p>
     * false 时调用方**不得**发起转写，应 fail-visible 提示人话而非静默失败。
     */
    boolean available();

    /**
     * 转写一段音频为文本。
     * <p>
     * 提交-轮询式（云端任务为异步）：实现内部完成「上传 → 提交 → 轮询 → 取成品」全流程，
     * 对调用方表现为同步阻塞调用（调用发生在 learn 提交式任务的后台线程池内，不阻塞 HTTP）。
     *
     * @param audio    音频字节（**必须是 ASR 可解的容器**：B站 dash 的 fMP4 分片须先经
     *                 {@link AudioTranscoder} 转码，直传必失败——pitfall「B站 dash 音频直传 ASR 必失败」）
     * @param fileName 上传文件名（带扩展名，云端按扩展名判容器）
     * @return 转写全文（纯文本）
     * @throws IllegalStateException 未配置凭证 / 任务失败 / 超时（消息为人话，由调用方转业务异常）
     */
    String transcribe(byte[] audio, String fileName);
}
