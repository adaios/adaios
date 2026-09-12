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
     * 顺序：命中留痕 → 复用（0 元）→ 否则校验可用性 → 校验配额硬闸（超限直接拒绝）
     * → 下载音频 → ffmpeg 转码 → 云端转写 → 留痕 → 记账。
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

        byte[] audio = fetchService.downloadAudio(source.platform(), source.audioUrl());
        byte[] transcoded = transcoder.toAsrCompatible(audio, "m4s");
        String text;
        try {
            text = asrClient.transcribe(transcoded, "learn-" + source.sourceId() + ".mp3");
        } catch (IllegalStateException e) {
            throw new LearnException(e.getMessage());
        }
        cardRepository.saveRaw(userId, transcriptRawName(source), text);
        LearnQuota after;
        try {
            after = quotaRepository.consume(userId, month, estimate.durationSeconds(), estimate.estimatedYuan());
        } catch (RuntimeException e) {
            // 记账写盘失败（StorageException）：**中止消化**而不是带着「花过钱但没账」的隐患继续。
            // 转写稿已留痕 → 稍后重试命中缓存，不再重复花钱（fail-visible，不静默）。
            log.error("转写记账失败，已中止消化 | userId={} | {}", userId, e.getMessage());
            throw new LearnException("转写好了，但记账没写成，我先把这一步停住了（避免产生查不到的费用）。"
                    + "转写稿已留存，稍后重试不会重复花钱");
        }
        log.info("转写完成 | userId={} | {} 字 | 用时 {}s | 花费约 {} 元 | 本月累计 {}s",
                userId, text.length(), estimate.durationSeconds(), estimate.estimatedYuan(), after.usedSeconds());
        return new TranscriptionResult(text, false, estimate.durationSeconds(), estimate.estimatedYuan(), after);
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
