package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnQuota;
import com.adaiadai.core.domain.learn.LearnQuotaRepository;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.kernel.ai.AsrClient;
import com.adaiadai.core.kernel.ai.AudioTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.YearMonth;

/**
 * LearnTranscriptionService — 云端转写 + **费用闸**（RFC 20260912 §3.8，learn 抓取批 2026-09-12）。
 * <p>
 * 「费用可控六条」在本类的落地：
 * <ol>
 *   <li><b>字幕优先</b>：抓取阶段已优先取字幕（{@code BilibiliFetcher}），只有无字幕才走到这里</li>
 *   <li><b>同一素材只转写一次</b>：转写稿落 {@code _raw/{platform}-{id}-transcript.txt}，
 *       重整理命中即复用（零费用）——源必留痕的附带收益</li>
 *   <li><b>只在你明确发话时花钱</b>：显式触发（用户提交链接），无任何批量/后台自动转写</li>
 *   <li><b>月度硬闸 + 记账</b>：超配额直接拒绝（人话说明剩余），用量落 {@code learn/_quota.json}</li>
 *   <li><b>单次可预期</b>：转写前给出「该视频 X 分钟，预计约 Y 元」由用户确认（流程见 AppService）</li>
 *   <li><b>备选更便宜通道</b>：{@link AsrClient} 为端口，换供应商只换实现（留口子不首期实现）</li>
 * </ol>
 * 单价默认 0.00008 元/秒 ≈ 0.288 元/小时（阿里云 ISI 计量计费公开价，与 fun-asr 同族模型），
 * 免费额度 36,000 秒/月（10 小时）即默认配额。
 */
@Service
public class LearnTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(LearnTranscriptionService.class);

    /** 时长未知时的保守估算口径（按 30 分钟计），用于配额判断与提示。 */
    static final int ASSUMED_SECONDS_WHEN_UNKNOWN = 1800;

    private final AsrClient asrClient;
    private final AudioTranscoder transcoder;
    private final LearnQuotaRepository quotaRepository;
    private final LearnCardRepository cardRepository;
    private final LearnFetchService fetchService;
    private final double yuanPerHour;

    public LearnTranscriptionService(AsrClient asrClient,
                                     AudioTranscoder transcoder,
                                     LearnQuotaRepository quotaRepository,
                                     LearnCardRepository cardRepository,
                                     LearnFetchService fetchService,
                                     @Value("${adai.learn.asr.yuan-per-hour:0.288}") double yuanPerHour) {
        this.asrClient = asrClient;
        this.transcoder = transcoder;
        this.quotaRepository = quotaRepository;
        this.cardRepository = cardRepository;
        this.fetchService = fetchService;
        this.yuanPerHour = yuanPerHour > 0 ? yuanPerHour : 0.288d;
    }

    /** 转写链路是否可用（凭证 + 转码工具齐备）。 */
    public boolean available() {
        return asrClient.available() && transcoder.available();
    }

    /** 不可用原因（人话，fail-visible 提示用；可用时返回 null）。 */
    public String unavailableReason() {
        if (!asrClient.available()) {
            return "云端转写还没配置好（缺凭证），无字幕的视频暂时整理不了；有字幕的视频和文章不受影响";
        }
        if (!transcoder.available()) {
            return "服务器上还没装转码工具（ffmpeg），无字幕的视频暂时整理不了；有字幕的视频和文章不受影响";
        }
        return null;
    }

    /** 转写稿留痕名（幂等键：同源同视频永远同一个文件 → 只烧一次钱）。 */
    static String transcriptRawName(LearnSource source) {
        return source.platform() + "-" + source.sourceId() + "-transcript.txt";
    }

    /** 已留痕的转写稿（无 → null）。 */
    public String cachedTranscript(String userId, LearnSource source) {
        String cached = cardRepository.readRaw(userId, transcriptRawName(source));
        return (cached == null || cached.isBlank()) ? null : cached;
    }

    /** 单次费用预估 + 额度视图（前端「是否花钱 + 花多少」提示的数据源）。 */
    public CostEstimate estimate(String userId, LearnSource source) {
        YearMonth month = YearMonth.now();
        LearnQuota quota = quotaRepository.view(userId, month);
        boolean durationKnown = source.durationSeconds() > 0;
        int seconds = durationKnown ? source.durationSeconds() : ASSUMED_SECONDS_WHEN_UNKNOWN;
        double yuan = round4(seconds / 3600.0d * yuanPerHour);
        boolean exceeds = quota.usedSeconds() + seconds > quota.quotaSeconds();
        return new CostEstimate(seconds, durationKnown, yuan,
                quota.usedSeconds(), quota.quotaSeconds(), quota.remainSeconds(), exceeds);
    }

    /**
     * 转写（含费用闸）。
     * <p>
     * <b>顺序（2026-09-12 对抗审查 P1-3/P1-4 修复后）</b>：
     * 命中留痕 → 复用（0 元）→ 校验可用性 → 校验配额硬闸 → **先预留记账**（写不进账就不花钱）
     * → 下载 → 转码 → 云端转写（失败则**退回预留**）→ **按实际音频时长结算差额** → 留痕。
     * <p>
     * 为什么改成「先预留再花钱」：原顺序是「先转写、再记账」，一旦记账写盘失败，钱花了却没入账，
     * 而重试会命中转写稿缓存直接返回 → **这笔费用永久不入账、剩余额度虚高**。留痕也移到记账成功
     * 之后（避免「有稿无账」）；留痕失败只告警（卡片已经该出就出，不影响账目）。
     *
     * @throws LearnException 转写不可用 / 超配额 / 下载或转写失败（人话）
     */
    public TranscriptionResult transcribe(String userId, LearnSource source) {
        String cached = cachedTranscript(userId, source);
        if (cached != null) {
            log.info("转写稿命中留痕，零费用复用 | userId={} | source={}", userId, transcriptRawName(source));
            return new TranscriptionResult(cached, true, 0, 0d, quotaRepository.view(userId, YearMonth.now()));
        }
        String reason = unavailableReason();
        if (reason != null) {
            throw new LearnException(reason);
        }
        CostEstimate estimate = estimate(userId, source);
        if (estimate.exceedsQuota()) {
            throw new LearnException("本月转写额度不够了（剩余 " + humanHours(estimate.remainSeconds())
                    + "），下月 1 日重置；有字幕的视频和文章不受影响，这篇的素材我已留存");
        }
        YearMonth month = YearMonth.now();

        // ① 先预留（记账在前，花钱在后）：账本写不进去就一分钱都不花
        try {
            quotaRepository.consume(userId, month, estimate.durationSeconds(), estimate.estimatedYuan());
        } catch (RuntimeException e) {
            log.error("转写预留记账失败，未开始转写 | userId={} | {}", userId, e.getMessage());
            throw new LearnException("额度账本暂时写不进去，我先不转写了（免得花了钱记不上账），稍后再试");
        }

        // ② 花钱：下载 → 转码 → 云端转写；任何失败都退回预留（不让用户为失败买单）
        byte[] transcoded;
        String text;
        try {
            byte[] audio = fetchService.downloadAudio(source.platform(), source.audioUrl());
            transcoded = transcoder.toAsrCompatible(audio, "m4s");
            text = asrClient.transcribe(transcoded, "learn-" + source.sourceId() + ".mp3");
        } catch (LearnException e) {
            refund(userId, month, estimate);
            throw e;
        } catch (RuntimeException e) {
            refund(userId, month, estimate);
            throw new LearnException(e.getMessage() == null ? "转写失败，已退回额度" : e.getMessage());
        }

        // ③ 按**实际音频时长**结算（对抗审查 P1-3）：转码产物是 32kbps CBR 单声道 mp3，
        //    字节数 / 4000 = 秒。时长未知时原先一律按 30 分钟记账 → 3 小时的视频只记 1800s，
        //    额度可以严重超用且账面上看不出来。
        int actualSeconds = actualSecondsOf(transcoded, estimate.durationSeconds());
        LearnQuota after = settle(userId, month, estimate, actualSeconds);

        // ④ 留痕（记账成功后才留痕；留痕失败只告警——不影响账目，卡片照出）
        try {
            cardRepository.saveRaw(userId, transcriptRawName(source), text);
        } catch (RuntimeException e) {
            log.warn("转写稿留痕失败（下次同源整理会重新计费）| userId={} | {}", userId, e.getMessage());
        }
        log.info("转写完成 | userId={} | {} 字 | 实际 {}s | 花费约 {} 元 | 本月累计 {}s",
                userId, text.length(), actualSeconds, yuanFor(actualSeconds), after.usedSeconds());
        return new TranscriptionResult(text, false, actualSeconds, yuanFor(actualSeconds), after);
    }

    /** 退回预留（转写失败不扣额度；退回本身失败只告警——宁可少扣，不可漏账）。 */
    private void refund(String userId, YearMonth month, CostEstimate estimate) {
        try {
            quotaRepository.consume(userId, month, -estimate.durationSeconds(), -estimate.estimatedYuan());
            log.info("转写失败，已退回预留额度 | userId={} | {}s", userId, estimate.durationSeconds());
        } catch (RuntimeException e) {
            log.error("退回预留失败（额度可能被多扣）| userId={} | {}", userId, e.getMessage());
        }
    }

    /** 按实际时长结算与预估的差额（正=补记，负=退回）。 */
    private LearnQuota settle(String userId, YearMonth month, CostEstimate estimate, int actualSeconds) {
        int deltaSeconds = actualSeconds - estimate.durationSeconds();
        double deltaYuan = round4(yuanFor(actualSeconds) - estimate.estimatedYuan());
        if (deltaSeconds == 0 && Math.abs(deltaYuan) < 1e-9) {
            return quotaRepository.view(userId, month);
        }
        try {
            return quotaRepository.consume(userId, month, deltaSeconds, deltaYuan);
        } catch (RuntimeException e) {
            log.error("按实际时长结算失败（账目可能停留在预估口径）| userId={} | {}", userId, e.getMessage());
            return quotaRepository.view(userId, month);
        }
    }

    /** 实际时长（秒）：32kbps CBR 单声道 mp3 → 4000 字节/秒；推算不出来就退回预估值。 */
    static int actualSecondsOf(byte[] transcodedMp3, int fallbackSeconds) {
        if (transcodedMp3 == null || transcodedMp3.length == 0) return fallbackSeconds;
        int seconds = (int) Math.round(transcodedMp3.length / 4000.0d);
        return seconds > 0 ? seconds : fallbackSeconds;
    }

    private double yuanFor(int seconds) {
        return round4(seconds / 3600.0d * yuanPerHour);
    }

    /** 单次预估结果（费用可控条 5：单次可预期）。 */
    public record CostEstimate(int durationSeconds, boolean durationKnown, double estimatedYuan,
                               int monthUsedSeconds, int quotaSeconds, int remainSeconds,
                               boolean exceedsQuota) {}

    /** 本月额度视图（GET /learn/digest/quota 响应，费用可控条 4：累计费用可查）。 */
    public record QuotaView(String month, int usedSeconds, double usedYuan, int quotaSeconds,
                            int remainSeconds, double yuanPerHour, boolean asrAvailable,
                            String unavailableReason) {}

    /** 本月转写用量与剩余额度（月初自动重置：新月份键不存在即归零）。 */
    public QuotaView quota(String userId) {
        LearnQuota quota = quotaRepository.view(userId, YearMonth.now());
        return new QuotaView(quota.month(), quota.usedSeconds(), quota.usedYuan(),
                quota.quotaSeconds(), quota.remainSeconds(), yuanPerHour,
                available(), unavailableReason());
    }

    /** 转写结果。 */
    public record TranscriptionResult(String text, boolean fromCache, int usedSeconds,
                                      double costYuan, LearnQuota quota) {}

    static String humanHours(int seconds) {
        if (seconds <= 0) return "0 分钟";
        int minutes = Math.round(seconds / 60.0f);
        if (minutes < 60) return minutes + " 分钟";
        double hours = Math.round(minutes / 6.0d) / 10.0d;
        if (hours == Math.floor(hours)) {
            return (long) hours + " 小时";
        }
        return hours + " 小时";
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
