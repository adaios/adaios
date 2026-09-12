package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardPatch;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * LearnDigestAppService — learn 消化用例编排（RFC 20260829 V1 流水线 + RFC 20260912 D 形态抓取批）。
 * <p>
 * <b>两条喂入路径</b>（2026-09-12 抓取批）：
 * <ul>
 *   <li><b>链接路径（D 形态核心）</b>：{@code url} → 服务端抓取（元数据/字幕/正文，见
 *       {@link LearnFetchService}）→ 无字幕则**费用闸 + 用户确认** → 云端转写
 *       （{@link LearnTranscriptionService}）→ LLM 六段结构化 → 落盘</li>
 *   <li><b>素材路径（兼容）</b>：{@code content} 直接结构化（B 形态原路径，保留给
 *       「抓不到就粘正文」的降级用法）</li>
 * </ul>
 * 卡片落 {@code data/{userId}/learn/{type}/{date}_{title}.md}；抓取与转写的原始素材落
 * {@code learn/_raw/}（**源必留痕铁律**：文章会失效、原音频丢了不可重建）。
 * <p>
 * <b>为什么提交式</b>（2026-09-10 起的既有形态，抓取批后更必要）：抓取 + 转写 + LLM 都是
 * 分钟级，远超客户端超时——POST 立即返回，前端轮询 {@code /learn/digest/status}。
 * 抓取批给轮询响应加了 {@code stage}（fetching/transcribing/structuring）与 {@code cost}
 * （单次预估 + 本月额度）两个字段，让「正在抓取/正在转写/要花多少钱」对用户可见。
 * <p>
 * <b>fail-visible</b>（不产半成品）：抓取失败/转写不可用/超配额/LLM 失败 → 任务态 failed +
 * 人话消息，已抓到的素材留在 {@code _raw/} 可重试。
 */
@Service
public class LearnDigestAppService {

    private static final Logger log = LoggerFactory.getLogger(LearnDigestAppService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CARD_SYSTEM_PROMPT = """
            你是 AdaiOS 的学习消化助手。用户给你一份外部学习素材（视频字幕/转写稿/文章原文），
            请把它整理成个人知识卡片。卡片正文用中文（术语保留英文原文），忠实原文、可提炼不编造。

            只输出 JSON，不要任何其他文本或代码块标记：
            {"title":"≤30字的卡片标题","type":"ai|trading|other","tags":["2-5个标签"],
            "core_view":"核心观点（一句话，用自己的话）",
            "key_points":["2-6个关键要点，可带原文时间戳如 02:31，保留关键数字"],
            "questions":["1-3个存疑点或可讨论处"],
            "trade_related":false,"trade_note":""}

            type 判定：技术/AI/编程类内容 → ai；交易理念/方法/规则类内容 → trading；
            科普/人文/其他 → other。
            trade_related 仅当 type=trading 且素材给出了**具体可执行的交易规则或信号**时为 true
            （理念/心态/方法论不算）；trade_note 简述与已有规则的关系（互补/冲突/重复），无则空串。

            格式要求（重要）：JSON 是严格格式，**字符串值内部禁止出现英文双引号**——
            需要引用术语或原话时，请用中文引号「」或单引号，否则整段 JSON 会解析失败。
            """;

    private final AiClient aiClient;
    private final LearnCardRepository repository;
    private final Executor learnSubmitExecutor;
    private final LearnFetchService fetchService;
    private final LearnTranscriptionService transcriptionService;

    /** 提交式消化任务态（key=userId）。 */
    private final Map<String, DigestJob> jobs = new ConcurrentHashMap<>();

    public LearnDigestAppService(AiClient aiClient,
                                 LearnCardRepository repository,
                                 @Qualifier("learnSubmitExecutor") Executor learnSubmitExecutor,
                                 LearnFetchService fetchService,
                                 LearnTranscriptionService transcriptionService) {
        this.aiClient = aiClient;
        this.repository = repository;
        this.learnSubmitExecutor = learnSubmitExecutor;
        this.fetchService = fetchService;
        this.transcriptionService = transcriptionService;
    }

    // ── 提交式消化（2026-09-10 喂入入口批；2026-09-12 抓取批扩展链接路径与阶段态）──

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_IDLE = "idle";
    /** 需用户确认才继续（无字幕视频要花钱转写，RFC 20260912 §3.8 条 5）。 */
    public static final String STATUS_NEEDS_CONFIRMATION = "needs_confirmation";
    /** 用户在确认环节取消（元数据已留痕，不产生费用）。 */
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String STAGE_FETCHING = "fetching";
    public static final String STAGE_TRANSCRIBING = "transcribing";
    public static final String STAGE_STRUCTURING = "structuring";

    /** done/failed/cancelled 结果保留时长：前端轮询消费后即不再查询，超时惰性清理防 jobs 泄漏。 */
    private static final long RESULT_TTL_MS = 60_000;
    /** needs_confirmation 保留更久（用户可能过一会儿才确认，不该被 60s 清掉）。 */
    private static final long CONFIRM_TTL_MS = 30 * 60_000L;

    /**
     * 消化请求（链接或素材二选一；链接优先）。
     *
     * @param url       内容页链接（D 形态：服务端自动抓取）
     * @param content   素材原文（降级路径：抓不到时用户粘正文）
     * @param type      显式类型（ai/trading/other，可空 = LLM 判）
     * @param platform  来源平台（仅素材路径有意义）
     * @param author    作者（仅素材路径有意义）
     * @param published 发布日期（仅素材路径有意义）
     */
    public record DigestRequest(String url, String content, String type,
                                String platform, String author, String published) {}

    /**
     * 提交消化（链接或素材）。
     * <p>
     * <b>输入归一</b>（2026-09-12）：用户「只粘了一个链接」是最常见的用法（且很容易粘进素材框），
     * 因此素材框里若是**裸链接**（单行 http(s)、无空格）→ 自动按链接走抓取，不再拿链接本身当素材
     * 去喂 LLM（否则白烧一次 AI 还产废卡）。链接与正文同时给出时：**正文为准，链接记为来源**。
     * <p>
     * 去重：同 user 已有任务在跑（含等待确认）→ 直接返回 running（连点/双端并发只烧一次 AI）。
     * 执行器拒绝（队列满）→ LearnException 400 人话。
     */
    public DigestSubmitResult submit(String userId, DigestRequest request) {
        if (request == null) {
            throw new LearnException("素材不能为空：请给我一个链接，或者把内容粘进来");
        }
        String typeHint = request.type();
        if (typeHint != null && !typeHint.isBlank() && !LearnCard.isValidType(typeHint)) {
            throw new LearnException("类型仅支持 ai/trading/other，请重试");
        }
        ResolvedInput input = resolve(request);
        if (input == null) {
            throw new LearnException("素材不能为空：请给我一个链接，或者把内容粘进来");
        }
        DigestJob cur = jobs.get(userId);
        if (cur != null && cur.isRunning()) {
            return new DigestSubmitResult(STATUS_RUNNING);
        }
        if (cur != null && cur.isAwaitingConfirm()) {
            // 等确认期间再次提交：不覆盖待确认的任务（丢了报价等于把用户的选择吞掉），
            // 如实回当前状态，前端据此重新弹费用提示
            return new DigestSubmitResult(STATUS_NEEDS_CONFIRMATION);
        }
        DigestJob job = new DigestJob();
        jobs.put(userId, job);
        try {
            learnSubmitExecutor.execute(() -> runJob(userId, job, input));
        } catch (RejectedExecutionException e) {
            jobs.remove(userId, job);
            throw new LearnException("消化任务繁忙，请稍后重试");
        }
        return new DigestSubmitResult(STATUS_PENDING);
    }

    /**
     * 输入归一：区分「要抓的链接」与「已给的正文」。两者皆无 → null。
     *
     * @param fetchUrl  需服务端抓取的链接（仅链接喂入时非空）
     * @param material  用户直接给的正文（非空时走素材路径）
     * @param sourceUrl 记入卡片 frontmatter 的来源链接
     */
    private record ResolvedInput(String fetchUrl, String material, String sourceUrl, String type,
                                 String platform, String author, String published) {}

    private ResolvedInput resolve(DigestRequest request) {
        String url = request.url() == null ? null : request.url().strip();
        String content = request.content() == null ? null : request.content().strip();
        if (url != null && url.isBlank()) url = null;
        if (content != null && content.isBlank()) content = null;

        String fetchUrl = null;
        String material = content;
        if (content != null && isBareUrl(content) && url == null) {
            fetchUrl = content;
            material = null;
        } else if (content == null && url != null) {
            fetchUrl = url;
        }
        if (fetchUrl == null && material == null) {
            return null;
        }
        return new ResolvedInput(fetchUrl, material, url, request.type(),
                request.platform(), request.author(), request.published());
    }

    /** 素材框里只放了一个链接（单行、http(s)、无空格）→ 按链接抓取。 */
    static boolean isBareUrl(String value) {
        if (value == null) return false;
        String t = value.strip();
        if (t.length() > 500 || t.contains("\n") || t.contains(" ") || t.contains("\t")) return false;
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /** 兼容旧调用（素材路径，B 形态先例）。 */
    public DigestSubmitResult submit(String userId, String content, String typeHint,
                                     String platform, String author, String url, String published) {
        return submit(userId, new DigestRequest(url, content, typeHint, platform, author, published));
    }

    /**
     * 费用确认（RFC 20260912 §3.8 条 5）：抓到的视频没有字幕时，先告诉用户「多长、要花多少」，
     * 用户确认后才真正调云端转写。
     *
     * @param confirm true = 继续转写；false = 取消（元数据已留痕，不产生费用）
     * @throws LearnException 当前没有等待确认的任务
     */
    public DigestJobStatus confirm(String userId, boolean confirm) {
        DigestJob job = jobs.get(userId);
        if (job == null || !job.isAwaitingConfirm()) {
            throw new LearnException("现在没有等待确认的整理任务");
        }
        if (!confirm) {
            job.cancel();
            log.info("learn 转写被用户取消（元数据已留存，未产生费用）| userId={}", userId);
            return job.statusView();
        }
        LearnSource source = job.pendingSource;
        ResolvedInput pendingInput = job.pendingInput;
        String typeHint = job.pendingTypeHint;
        job.resume();
        try {
            learnSubmitExecutor.execute(() -> {
                try {
                    job.stage = STAGE_TRANSCRIBING;
                    LearnTranscriptionService.TranscriptionResult tr =
                            transcriptionService.transcribe(userId, source);
                    finishJob(userId, job, tr.text(), typeHint, source, pendingInput);
                } catch (LearnException e) {
                    log.warn("learn 转写后消化失败 | userId={} | {}", userId, e.getMessage());
                    job.fail(e.getMessage());
                } catch (Exception e) {
                    log.error("learn 转写后消化异常 | userId={}", userId, e);
                    job.fail("AI 消化失败，原始素材已留存，可稍后重试");
                }
            });
        } catch (RejectedExecutionException e) {
            job.awaitConfirm(job.pendingCost);
            throw new LearnException("消化任务繁忙，请稍后重试");
        }
        return job.statusView();
    }

    /** 当前消化任务状态（GET /learn/digest/status 响应）。 */
    public DigestJobStatus digestJobStatus(String userId) {
        DigestJob job = jobs.get(userId);
        if (job == null) return DigestJobStatus.idle();
        if (!job.isRunning()) {
            long ttl = job.isAwaitingConfirm() ? CONFIRM_TTL_MS : RESULT_TTL_MS;
            if (job.elapsedSinceSettled() > ttl) {
                jobs.remove(userId, job);
                return DigestJobStatus.idle();
            }
        }
        return job.statusView();
    }

    // ── 流水线 ──

    private void runJob(String userId, DigestJob job, ResolvedInput input) {
        try {
            if (input.fetchUrl() != null) {
                runFetchPath(userId, job, input);
            } else {
                finishJob(userId, job, input.material(), input.type(), null, input);
            }
        } catch (LearnException e) {
            log.warn("learn 后台消化失败 | userId={} | {}", userId, e.getMessage());
            job.fail(e.getMessage());
        } catch (Exception e) {
            log.error("learn 后台消化异常 | userId={}", userId, e);
            job.fail("AI 消化失败，原始素材已留存（learn/_raw/），可稍后重试");
        }
    }

    /** 链接路径：抓 →（无字幕则费用闸 + 等确认）→ 否则直接结构化。 */
    private void runFetchPath(String userId, DigestJob job, ResolvedInput input) {
        job.stage = STAGE_FETCHING;
        LearnSource source = fetchService.fetchAndArchive(userId, input.fetchUrl());
        job.source = source;
        job.pendingTypeHint = input.type();
        job.pendingInput = input;

        if (source.hasText()) {
            finishJob(userId, job, source.text(), input.type(), source, input);
            return;
        }

        String reason = transcriptionService.unavailableReason();
        if (reason != null) {
            throw new LearnException(reason);
        }
        LearnTranscriptionService.CostEstimate estimate = transcriptionService.estimate(userId, source);
        if (estimate.exceedsQuota()) {
            throw new LearnException("本月转写额度不够了（剩余 "
                    + LearnTranscriptionService.humanHours(estimate.remainSeconds())
                    + "），下月 1 日重置；抓到的元数据我已留存");
        }
        job.awaitConfirm(estimate);
        log.info("learn 等待转写确认 | userId={} | {} | 约 {} 分钟 | 预计 {} 元",
                userId, source.title(), Math.round(estimate.durationSeconds() / 60.0), estimate.estimatedYuan());
    }

    private void finishJob(String userId, DigestJob job, String text, String typeHint,
                           LearnSource source, ResolvedInput input) {
        job.stage = STAGE_STRUCTURING;
        String platform = source != null ? displayPlatform(source) : input.platform();
        String author = source != null ? source.author() : input.author();
        String url = source != null ? source.url() : input.sourceUrl();
        String published = source != null ? source.published() : input.published();
        LearnCard card = digest(userId, text, typeHint, platform, author, url, published);
        job.done(card.type(), card.title());
    }

    /** 卡片「平台」展示值：文章用它自己的域名（bilibili 保持原样），比裸 "article" 有信息量。 */
    private static String displayPlatform(LearnSource source) {
        if (source == null) return null;
        if (!"article".equals(source.platform())) return source.platform();
        String host = LearnFetchService.hostOf(source.url());
        return host == null ? source.platform() : host;
    }

    // ── 任务态 ──

    /** 消化提交结果（POST /learn/digest 响应：status）。 */
    public record DigestSubmitResult(String status) {}

    /**
     * 消化任务状态（GET /learn/digest/status 响应）。
     *
     * @param status   idle / pending / running / needs_confirmation / done / failed / cancelled
     * @param type     done 时的卡片类型
     * @param title    done 时的卡片标题
     * @param message  failed / needs_confirmation / cancelled 时的人话
     * @param stage    进行中阶段：fetching / transcribing / structuring（可空）
     * @param source   抓到的源信息（抓取成功后可回显，可空）
     * @param cost     转写费用预估与本月额度（needs_confirmation 时必填，可空）
     */
    public record DigestJobStatus(String status, String type, String title, String message,
                                  String stage, SourceView source, CostView cost) {

        /** 兼容旧调用（2026-09-10 先例：四字段）。 */
        public DigestJobStatus(String status, String type, String title, String message) {
            this(status, type, title, message, null, null, null);
        }

        static DigestJobStatus idle() {
            return new DigestJobStatus(STATUS_IDLE, null, null, null, null, null, null);
        }

        /** 抓到的源信息（状态轮询期间回显，让用户看到「阿呆在抓什么」）。 */
        public record SourceView(String platform, String title, String author, Integer durationSeconds) {}

        /** 转写费用视图（费用可控条 4/5：可查、可预期）。 */
        public record CostView(Integer durationSeconds, Boolean durationKnown, Double estimatedYuan,
                               Integer monthUsedSeconds, Integer quotaSeconds, Integer remainSeconds) {}
    }

    /** 单用户消化任务态（内存态，重启丢失 → 轮询回 idle）。 */
    private static final class DigestJob {
        private volatile String status = STATUS_RUNNING;
        private volatile String stage;
        private volatile String type;
        private volatile String title;
        private volatile String message;
        private volatile LearnSource source;
        private volatile String pendingTypeHint;
        private volatile LearnSource pendingSource;
        private volatile ResolvedInput pendingInput;
        private volatile LearnTranscriptionService.CostEstimate pendingCost;
        private final long createdAt = System.currentTimeMillis();
        private volatile long settledAt = 0L;

        boolean isRunning() {
            return STATUS_RUNNING.equals(status);
        }

        boolean isAwaitingConfirm() {
            return STATUS_NEEDS_CONFIRMATION.equals(status);
        }

        long elapsedSinceSettled() {
            return settledAt == 0L ? 0L : System.currentTimeMillis() - settledAt;
        }

        void awaitConfirm(LearnTranscriptionService.CostEstimate estimate) {
            this.pendingSource = source;
            this.pendingCost = estimate;
            this.stage = STAGE_TRANSCRIBING;
            this.status = STATUS_NEEDS_CONFIRMATION;
            this.settledAt = System.currentTimeMillis();
            this.message = "这个视频没有字幕，需要转写：" + humanDuration(estimate)
                    + "，预计约 " + String.format("%.2f", estimate.estimatedYuan())
                    + " 元（本月剩余额度 " + LearnTranscriptionService.humanHours(estimate.remainSeconds()) + "）";
        }

        void resume() {
            this.status = STATUS_RUNNING;
            this.settledAt = 0L;
            this.message = null;
        }

        void cancel() {
            this.status = STATUS_CANCELLED;
            this.settledAt = System.currentTimeMillis();
            this.message = "已取消转写（没花钱），抓到的元数据我留着了，回头想整理再说一声";
        }

        void done(String type, String title) {
            this.status = STATUS_DONE;
            this.type = type;
            this.title = title;
            this.stage = null;
            this.settledAt = System.currentTimeMillis();
        }

        void fail(String message) {
            this.status = STATUS_FAILED;
            this.message = message;
            this.stage = null;
            this.settledAt = System.currentTimeMillis();
        }

        DigestJobStatus statusView() {
            DigestJobStatus.SourceView sourceView = source == null ? null
                    : new DigestJobStatus.SourceView(source.platform(), source.title(), source.author(),
                    source.durationSeconds() > 0 ? source.durationSeconds() : null);
            DigestJobStatus.CostView costView = pendingCost == null ? null
                    : new DigestJobStatus.CostView(pendingCost.durationSeconds(), pendingCost.durationKnown(),
                    pendingCost.estimatedYuan(), pendingCost.monthUsedSeconds(), pendingCost.quotaSeconds(),
                    pendingCost.remainSeconds());
            return new DigestJobStatus(status, type, title, message, stage, sourceView, costView);
        }

        private static String humanDuration(LearnTranscriptionService.CostEstimate estimate) {
            if (!estimate.durationKnown()) {
                return "时长未知（按 30 分钟估算）";
            }
            int minutes = Math.max(1, Math.round(estimate.durationSeconds() / 60.0f));
            return minutes + " 分钟";
        }
    }

    // ── 卡片化 ──

    /**
     * 消化素材为学习卡片并落盘。
     *
     * @param userId    用户
     * @param content   素材原文（字幕/转写稿/文章正文）
     * @param typeHint  显式类型（ai/trading/other，可空 = LLM 判定）
     * @param platform  来源平台（可空）
     * @param author    作者/UP 主（可空）
     * @param url       原文链接（可空）
     * @param published 原文发布日期（可空）
     * @return 落盘的卡片
     * @throws LearnException 卡片化失败（400 + 人话；素材已留存 _raw/）
     */
    public LearnCard digest(String userId, String content, String typeHint,
                            String platform, String author, String url, String published) {
        if (content == null || content.isBlank()) {
            throw new LearnException("素材内容不能为空");
        }
        if (typeHint != null && !typeHint.isBlank() && !LearnCard.isValidType(typeHint)) {
            throw new LearnException("类型仅支持 ai/trading/other，请重试");
        }
        String userPrompt = buildDigestPrompt(content, platform, author, url, published, typeHint);
        ContextPackage ctx = ContextPackage.simple(
                "learn", null, "学习消化", userPrompt, List.of(), userPrompt);
        AiTraceContext.set(userId, null, null, "learn_digest");

        String raw;
        try {
            raw = aiClient.generate(ctx, CARD_SYSTEM_PROMPT);
        } catch (Exception e) {
            log.warn("learn 卡片化 LLM 失败 | userId={} | {}", userId, e.getMessage());
            repository.saveRawSource(userId, content);
            throw new LearnException("AI 消化失败，原始素材已留存（learn/_raw/），可稍后重试：" + e.getMessage());
        }

        DigestResult parsed;
        try {
            parsed = parseDigest(raw);
        } catch (Exception e) {
            log.warn("learn 卡片化输出不可解析 | userId={} | {}", userId, e.getMessage());
            repository.saveRawSource(userId, content);
            throw new LearnException("AI 消化输出无法识别，原始素材已留存（learn/_raw/），可稍后重试");
        }
        if (parsed.title() == null || parsed.title().isBlank()) {
            repository.saveRawSource(userId, content);
            throw new LearnException("AI 消化未给出标题，原始素材已留存（learn/_raw/），可稍后重试");
        }

        // type：显式 hint > LLM 判 > other 兜底（越界值不落盘）
        String type = typeHint != null && !typeHint.isBlank()
                ? typeHint
                : (LearnCard.isValidType(parsed.type()) ? parsed.type() : LearnCard.TYPE_OTHER);
        if (!typeHintValid(typeHint) && !LearnCard.isValidType(parsed.type())) {
            log.warn("learn type 越界回落 other | userId={} | llmType={}", userId, parsed.type());
        }

        LearnCard card = new LearnCard(
                type, parsed.title(),
                platform, author, url, published,
                LocalDate.now(), LearnCard.STATUS_NEW,
                LearnCard.TYPE_TRADING.equals(type) && parsed.tradeRelated(),
                LearnCard.TYPE_TRADING.equals(type) ? parsed.tradeNote() : null,
                parsed.tags(),
                parsed.coreView(), parsed.keyPoints(), parsed.questions(),
                "");
        repository.save(userId, card);
        log.info("learn 卡片化完成 | userId={} | type={} | title={} | 要点 {} 条 | 疑问 {} 条",
                userId, card.type(), card.title(),
                card.keyPoints().size(), card.questions().size());
        return card;
    }

    private boolean typeHintValid(String typeHint) {
        return typeHint != null && !typeHint.isBlank();
    }

    /** 指定类型卡片列表（created 倒序）。 */
    public List<LearnCard> list(String userId, String type) {
        if (!LearnCard.isValidType(type)) return List.of();
        return repository.list(userId, type);
    }

    /** 单篇卡片（按 type + title）；不存在 → 业务异常（404 语义由调用方映射 400 人话）。 */
    public LearnCard detail(String userId, String type, String title) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) {
            throw new LearnException("卡片不存在：" + safeLabel(type, title));
        }
        return repository.find(userId, type, title)
                .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, title)));
    }

    /**
     * 复习状态流转（V2 2026-09-07 P2-learn8 + S-learn1 修复）：
     * 只允许 new→review→done 与回退 review→new / done→review（跳变 new→done、done→new 拒绝，
     * isValidTransition 在仓储执行）；进入 review（含 done→review 重进）由仓储写 review_at=today
     * （复习提醒按进入复习之日计时）。仅改 frontmatter，正文与手写「复述」段落原样保留（File First）。
     *
     * @throws LearnException 类型/状态非法（400 人话）或卡片不存在
     */
    public LearnCard changeStatus(String userId, String type, String title, String status) {
        if (!LearnCard.isValidType(type)) {
            throw new LearnException("类型仅支持 ai/trading/other，请重试");
        }
        if (title == null || title.isBlank()) {
            throw new LearnException("卡片标题不能为空");
        }
        if (!LearnCard.isValidStatus(status)) {
            throw new LearnException("复习状态仅支持 new/review/done");
        }
        return repository.updateStatus(userId, type, title, status, LocalDate.now());
    }

    /**
     * 编辑卡片正文（V2 对话流让阿呆改的后端支撑）：按 type+title 定位，patch 字段
     * null = 保留原值，非 null = 覆盖（含清空）。merge 与写盘在仓储锁内原子完成
     * （P2-learn6 并发 PATCH 不丢更新），手工未知 frontmatter 键/正文段保留（P2-learn7）。
     * type/title/created 由原卡继承不可改。返回更新后卡片。
     *
     * @throws LearnException 定位/入参非法或卡片不存在
     */
    public LearnCard edit(String userId, String type, String title, LearnCardPatch patch) {
        if (!LearnCard.isValidType(type)) {
            throw new LearnException("类型仅支持 ai/trading/other，请重试");
        }
        if (title == null || title.isBlank()) {
            throw new LearnException("卡片标题不能为空");
        }
        return repository.applyEdit(userId, type, title, patch == null ? new LearnCardPatch(null, null, null, null, null, null, null) : patch);
    }

    /** 资产树：learn 按 type 分组（只含已落盘卡片）。 */
    public Map<String, List<LearnCard>> tree(String userId) {
        return repository.tree(userId);
    }

    private String safeLabel(String type, String title) {
        String t = type == null || type.isBlank() ? "?" : type;
        String ti = title == null || title.isBlank() ? "(空标题)" : title;
        return t + "/" + ti;
    }

    /** 组装用户指令：素材 + 来源 + type 提示。 */
    private String buildDigestPrompt(String content, String platform, String author,
                                     String url, String published, String typeHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("请消化以下素材为学习卡片：\n\n");
        if (platform != null && !platform.isBlank()) {
            sb.append("来源平台：").append(platform).append("\n");
        }
        if (author != null && !author.isBlank()) {
            sb.append("作者/UP主：").append(author).append("\n");
        }
        if (url != null && !url.isBlank()) {
            sb.append("链接：").append(url).append("\n");
        }
        if (published != null && !published.isBlank()) {
            sb.append("发布日期：").append(published).append("\n");
        }
        if (typeHint != null && !typeHint.isBlank()) {
            sb.append("内容类型（用户已指定，据此组织卡片）：").append(typeHint).append("\n");
        }
        sb.append("\n素材正文：\n").append(content);
        return sb.toString();
    }

    // ── LLM 输出解析 ──

    private record DigestResult(String title, String type, List<String> tags,
                                String coreView, List<String> keyPoints,
                                List<String> questions, boolean tradeRelated, String tradeNote) {}

    private DigestResult parseDigest(String raw) throws Exception {
        String json = extractJson(raw);
        if (json == null) throw new IllegalStateException("AI 输出未包含 JSON");
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception strict) {
            // pitfall「LLM 输出 JSON 夹未转义引号」：同一素材重试即成功（概率性）——先做宽松修复再放弃
            node = MAPPER.readTree(repairJson(json));
        }
        String title = node.path("title").asText("").strip();
        String type = node.path("type").asText("").strip().toLowerCase();
        List<String> tags = stringArray(node, "tags");
        String coreView = node.path("core_view").asText("").strip();
        List<String> keyPoints = stringArray(node, "key_points");
        List<String> questions = stringArray(node, "questions");
        boolean tradeRelated = node.path("trade_related").asBoolean(false);
        String tradeNote = node.path("trade_note").asText("").strip();
        return new DigestResult(title, type, tags, coreView, keyPoints, questions, tradeRelated, tradeNote);
    }

    private List<String> stringArray(JsonNode node, String field) {
        List<String> list = new ArrayList<>();
        JsonNode arr = node.path(field);
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isTextual() && !n.asText().isBlank()) list.add(n.asText().strip());
            }
        }
        return list;
    }

    /** 抽取 JSON：先去代码块围栏，再取最外层花括号范围。 */
    private String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1).strip();
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence).strip();
        }
        if (t.startsWith("{")) return t;
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) return t.substring(start, end + 1);
        return null;
    }

    /**
     * 宽松修复 LLM 输出的 JSON（pitfall「LLM 输出 JSON 夹未转义引号」的解析层兜底，
     * prompt 层同时要求值内用中文引号——**双做**）：
     * <ol>
     *   <li>字符串值内部未转义的英文双引号 → 转义（按「引号后是否紧跟 : , } ]」判断是值内引号还是收尾引号）</li>
     *   <li>对象/数组尾随逗号删除</li>
     * </ol>
     * 仍是启发式：修不好就照旧 fail-visible（素材留 {@code _raw/}，不产半成品）。
     */
    static String repairJson(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                sb.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (!inString) {
                    inString = true;
                    sb.append(c);
                    continue;
                }
                int j = i + 1;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                char next = j < json.length() ? json.charAt(j) : '\0';
                boolean looksLikeEnd = next == ':' || next == ',' || next == '}' || next == ']' || next == '\0';
                if (looksLikeEnd) {
                    inString = false;
                    sb.append(c);
                } else {
                    sb.append("\\\"");
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString().replaceAll(",\\s*([}\\]])", "$1");
    }
}
