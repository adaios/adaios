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
            {"title":"≤30字的卡片标题","type":"ai|trading|other","topic":"≤12字的主题目录名","tags":["2-5个标签"],
            "core_view":"核心观点（一句话，用自己的话）",
            "key_points":["2-6个关键要点，可带原文时间戳如 02:31，保留关键数字"],
            "questions":["1-3个存疑点或可讨论处"],
            "trade_related":false,"trade_note":""}

            type 判定：技术/AI/编程类内容 → ai；交易理念/方法/规则类内容 → trading；
            科普/人文/其他 → other。
            topic 判定（卡片归档目录，**同一主题的多个素材必须用同一个 topic 名**）：
            用简短的主题名（如「harness-engineering」「量价关系」「睡眠科学」），
            不要用日期、不要用「学习」「笔记」这类泛词、不要每次换名字；
            如果用户消息里列了我已有的主题目录且这份素材属于其中之一，**必须用完全同名**的那个。
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
    private final com.adaiadai.core.infrastructure.ai.vision.VisualAiClient visualAiClient;

    /** 提交式消化任务态（key=userId）。 */
    private final Map<String, DigestJob> jobs = new ConcurrentHashMap<>();

    /**
     * 生产装配（含视觉模型：图片源要读图）。
     * <p>
     * 2026-09-12 完整升级批：图片源（书页/PPT/截图）复用既有 GLM 视觉通道——图 → 忠实提取文本
     * → 与链接/素材同一条消化流水线。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LearnDigestAppService(AiClient aiClient,
                                 LearnCardRepository repository,
                                 @Qualifier("learnSubmitExecutor") Executor learnSubmitExecutor,
                                 LearnFetchService fetchService,
                                 LearnTranscriptionService transcriptionService,
                                 com.adaiadai.core.infrastructure.ai.vision.VisualAiClient visualAiClient) {
        this.aiClient = aiClient;
        this.repository = repository;
        this.learnSubmitExecutor = learnSubmitExecutor;
        this.fetchService = fetchService;
        this.transcriptionService = transcriptionService;
        this.visualAiClient = visualAiClient;
    }

    /** 测试/无视觉模型装配（图片源不可用 → submitImages 人话拒绝，其余路径不受影响）。 */
    public LearnDigestAppService(AiClient aiClient,
                                 LearnCardRepository repository,
                                 Executor learnSubmitExecutor,
                                 LearnFetchService fetchService,
                                 LearnTranscriptionService transcriptionService) {
        this(aiClient, repository, learnSubmitExecutor, fetchService, transcriptionService, null);
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
    /** 图片源阶段（2026-09-12）：正在读图（视觉模型提取文字与图意）。 */
    public static final String STAGE_READING = "reading";

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
        // 「检查在跑 + 占位」必须原子完成（2026-09-12 自查）：原先是 get→判断→put 三步，
        // 两端同时提交会各建一个 job 各跑一遍 → 重复抓取 + 重复烧 AI（确认路径下还会重复花钱）。
        // ConcurrentHashMap.compute 对同一 key 原子：抢占失败方直接复用当前任务，不再入队。
        DigestJob fresh = new DigestJob();
        DigestJob current = jobs.compute(userId,
                (key, cur) -> (cur == null || cur.isTerminal()) ? fresh : cur);
        if (current != fresh) {
            // 已有任务在跑 → running；等确认期间再次提交 → 如实回待确认（不吞掉用户的选择）
            return new DigestSubmitResult(current.isAwaitingConfirm() ? STATUS_NEEDS_CONFIRMATION : STATUS_RUNNING);
        }
        try {
            learnSubmitExecutor.execute(() -> runJob(userId, current, input));
        } catch (RejectedExecutionException e) {
            jobs.remove(userId, current);
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

    // ── 图片源（2026-09-12 完整升级批：书页/PPT/截图 → 忠实提取 → 同一条消化流水线）──

    /** 一张待消化的图（原始字节 + MIME + 原文件名）。 */
    public record ImageInput(byte[] bytes, String contentType, String filename) {}

    /** 单张图上限（与上传通道一致，防 2核4G 服务器被大图打爆）。 */
    private static final long IMAGE_MAX_BYTES = 5 * 1024 * 1024L;
    private static final int IMAGE_MAX_COUNT = 3;

    /** 读图提示词：**忠实提取**（不是概括）——结构化交给文本模型，这里只负责「把图变成可信素材」。 */
    private static final String IMAGE_READ_PROMPT = """
            这是一张学习资料图片（书页/PPT/讲义/截图/图表）。请把图中的学习内容**完整提取**出来：
            1) 逐字抄录图中的文字，保留标题层级、编号、公式、代码与专有名词（不要改写、不要总结成一句话）；
            2) 若有图表，说明它表达了什么（坐标轴/趋势/结论）；
            3) 手写体或模糊处把握不准的，标「（此处不清晰）」。
            4) **若内容超出你的输出长度上限**，在结尾另起一行写「（余下内容未能提取）」——
               宁可我事后补，也不要静默省略。
            只输出提取结果本身，不要客套话，不要「以下是」这类开场。
            不要总结成一段话，也不要为了简短而省略条目。
            """;

    /**
     * 提交图片消化（1~3 张）：原图先留痕（源必留痕：原图丢了不可重建）→ 后台读图 → 结构化落卡。
     * <p>
     * 与链接路径共用同一套任务态/轮询/去重（{@code stage=reading}）；同 user 有任务在跑 →
     * 直接回 running（连点/双端不重复烧模型）。
     */
    public DigestSubmitResult submitImages(String userId, List<ImageInput> images, String typeHint, String note) {
        if (visualAiClient == null) {
            throw new LearnException("这台服务器还没接视觉模型，图片暂时读不了；把图里的文字粘进来我照样能整理");
        }
        if (images == null || images.isEmpty()) {
            throw new LearnException("请给我至少一张图片");
        }
        if (images.size() > IMAGE_MAX_COUNT) {
            throw new LearnException("一次最多 " + IMAGE_MAX_COUNT + " 张图，多了我看不过来，分两次发吧");
        }
        if (typeHint != null && !typeHint.isBlank() && !LearnCard.isValidType(typeHint)) {
            throw new LearnException("类型仅支持 ai/trading/other，请重试");
        }
        // 先校验全部图片（空/超限/非图 → 400，不占任务位），再抢任务位，最后才留痕：
        // 抢不到任务位（已有消化在跑/执行器满）时不写暂存区，免得留下永远不会归位的孤儿素材
        List<String> rawNames = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            ImageInput img = images.get(i);
            if (img == null || img.bytes() == null || img.bytes().length == 0) {
                throw new LearnException("第 " + (i + 1) + " 张图是空的，重新发一次");
            }
            if (img.bytes().length > IMAGE_MAX_BYTES) {
                throw new LearnException("第 " + (i + 1) + " 张图太大了（超过 5MB），压一下再发");
            }
            rawNames.add("image-" + (i + 1) + "-" + shortHash(new String(img.bytes(),
                    java.nio.charset.StandardCharsets.ISO_8859_1)) + "." + imageExt(img));
        }

        DigestJob fresh = new DigestJob();
        DigestJob current = jobs.compute(userId,
                (key, cur) -> (cur == null || cur.isTerminal()) ? fresh : cur);
        if (current != fresh) {
            return new DigestSubmitResult(current.isAwaitingConfirm() ? STATUS_NEEDS_CONFIRMATION : STATUS_RUNNING);
        }
        current.pendingRawNames = List.copyOf(rawNames);
        List<ImageInput> copy = List.copyOf(images);
        try {
            // 对抗审查 P1-B（2026-09-12）修复：占位成功后**任何**失败都必须回收 job——
            // 原先只处理执行器拒绝，而「写暂存区」抛错（磁盘满/权限）会把 job 留在 RUNNING：
            // 该用户之后所有喂入都被当「有任务在跑」挡死、状态永远 running，只有重启才能恢复。
            for (int i = 0; i < images.size(); i++) {
                repository.saveRawBytes(userId, rawNames.get(i), images.get(i).bytes());
            }
            learnSubmitExecutor.execute(() -> runImageJob(userId, current, copy, typeHint, note));
        } catch (RejectedExecutionException e) {
            cleanupStaged(userId, rawNames);   // 任务没跑起来 → 清掉刚写的暂存素材（不留孤儿）
            jobs.remove(userId, current);
            throw new LearnException("消化任务繁忙，请稍后重试");
        } catch (LearnException e) {
            cleanupStaged(userId, rawNames);
            jobs.remove(userId, current);
            throw e;
        } catch (Exception e) {
            cleanupStaged(userId, rawNames);
            jobs.remove(userId, current);
            log.warn("learn 图片受理失败（占位已回收）| userId={} | {}", userId, e.getMessage());
            throw new LearnException("这次的图片没受理上（服务器存不下或权限不对），稍后再试一次");
        }
        return new DigestSubmitResult(STATUS_PENDING);
    }

    /** 受理失败时清掉刚落的暂存素材（素材还没被处理，留着只会变孤儿）。 */
    private void cleanupStaged(String userId, List<String> names) {
        for (String name : names) {
            try {
                if (repository.readRawBytes(userId, name) != null) {
                    repository.deleteRaw(userId, name);
                }
            } catch (Exception e) {
                log.warn("learn 暂存素材清理失败 | userId={} | name={} | {}", userId, name, e.getMessage());
            }
        }
    }

    /** 图片后缀（按 MIME 判定，不认识就按文件名，再不行当 png）。 */
    private static String imageExt(ImageInput img) {
        String ct = img.contentType() == null ? "" : img.contentType().toLowerCase();
        if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg";
        if (ct.contains("webp")) return "webp";
        if (ct.contains("png")) return "png";
        String name = img.filename() == null ? "" : img.filename().toLowerCase();
        for (String ext : List.of("png", "jpg", "jpeg", "webp")) {
            if (name.endsWith("." + ext)) return "jpeg".equals(ext) ? "jpg" : ext;
        }
        return "png";
    }

    /** 图片后台任务：读图（视觉模型）→ 拼接素材 → 与文本素材同一条结构化路径。 */
    private void runImageJob(String userId, DigestJob job, List<ImageInput> images, String typeHint, String note) {
        try {
            // 对抗审查 P2-4（2026-09-12）：读图是新的付费调用，必须挂上 trace，
            // 否则 AI 日志与成本会记到 default 或**上一个任务**的用户（线程复用 + digest 不 clear）
            AiTraceContext.set(userId, null, null, "learn_image");
            job.stage = STAGE_READING;
            StringBuilder material = new StringBuilder();
            for (int i = 0; i < images.size(); i++) {
                ImageInput img = images.get(i);
                String text;
                try {
                    // 用户备注要**并进问题文本**：视觉客户端的 ask 只发问题，ImageRequest.caption 会被忽略
                    // （2026-09-12 对抗审查指出「note 参数实际无效」）——所以在这里显式拼进去。
                    String question = (note == null || note.isBlank())
                            ? IMAGE_READ_PROMPT
                            : "用户补充说明：" + note.strip() + "\n\n" + IMAGE_READ_PROMPT;
                    text = visualAiClient.ask(
                            new com.adaiadai.core.infrastructure.ai.vision.ImageRequest(
                                    java.util.Base64.getEncoder().encodeToString(img.bytes()),
                                    img.contentType(), note),
                            question);
                } catch (Exception e) {
                    log.warn("learn 读图失败 | userId={} | 第 {} 张 | {}", userId, i + 1, e.getMessage());
                    throw new LearnException("第 " + (i + 1) + " 张图我没读出来，原图已留存，稍后再试一次");
                }
                if (text == null || text.isBlank()) {
                    throw new LearnException("第 " + (i + 1) + " 张图里没读出内容——换一张清楚的，或者把文字粘进来");
                }
                material.append("【第 ").append(i + 1).append(" 张图】\n").append(text.strip()).append("\n\n");
            }
            ResolvedInput input = new ResolvedInput(null, material.toString(), null, typeHint,
                    images.size() > 1 ? "图片（" + images.size() + " 张）" : "图片", null, null);
            finishJob(userId, job, material.toString(), typeHint, null, input, job.pendingRawNames);
        } catch (LearnException e) {
            job.fail(e.getMessage());
        } catch (Exception e) {
            log.error("learn 图片消化异常 | userId={}", userId, e);
            job.fail("图片消化失败，原图已留存（learn/_raw/），可稍后重试");
        }
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
        // 原子「取待确认任务 + 转移状态」（2026-09-12 自查）：原先是 get→isAwaitingConfirm→resume 三步，
        // 两端（web + app）同时点「继续转写」可双双通过检查 → 两个执行任务 → **重复转写 = 重复花钱**。
        // 用 compute 对同一 key 原子转移：只有第一个调用者能把它从 needs_confirmation 挪走。
        boolean[] claimed = {false};
        DigestJob job = jobs.compute(userId, (key, cur) -> {
            if (cur == null || !cur.isAwaitingConfirm()) {
                return cur;
            }
            claimed[0] = true;
            if (confirm) {
                cur.resume();
            } else {
                cur.cancel();
            }
            return cur;
        });
        if (job == null || !claimed[0]) {
            throw new LearnException("现在没有等待确认的整理任务");
        }
        if (!confirm) {
            log.info("learn 转写被用户取消（元数据已留存，未产生费用）| userId={}", userId);
            return job.statusView();
        }
        LearnSource source = job.pendingSource;
        ResolvedInput pendingInput = job.pendingInput;
        String typeHint = job.pendingTypeHint;
        try {
            learnSubmitExecutor.execute(() -> {
                try {
                    job.stage = STAGE_TRANSCRIBING;
                    LearnTranscriptionService.TranscriptionResult tr =
                            transcriptionService.transcribe(userId, source);
                    finishJob(userId, job, tr.text(), typeHint, source, pendingInput, job.pendingRawNames);
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
                finishJob(userId, job, input.material(), input.type(), null, input, List.of());
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
            finishJob(userId, job, source.text(), input.type(), source, input, job.pendingRawNames);
            return;
        }

        // 对抗审查 P1-2①：「接口报错/限流」是可重试的错误，**不是**「确实没字幕」。
        // 把它降级成付费转写 = 让用户为本来能省的钱买单，且违反 fail-visible → 直接人话失败。
        if (source.textBlockedByError()) {
            throw new LearnException(source.textUnavailableReason() + "；抓到的信息我已留存，稍后再试一次");
        }

        String reason = transcriptionService.unavailableReason();
        if (reason != null) {
            throw new LearnException(reason);
        }

        // 对抗审查 P1-2②：先确认音频真的拿得到，再让用户点头——
        // 否则用户为一件做不到的事点了确认（点了才报「拿不到音频地址」）。
        if (source.audioUrl() == null || source.audioUrl().isBlank()) {
            throw new LearnException("这个视频的音频我拿不到（可能要登录或被限制），"
                    + "把字幕或正文粘进来我照样能整理");
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
                           LearnSource source, ResolvedInput input, List<String> extraRawNames) {
        job.stage = STAGE_STRUCTURING;
        String platform = source != null ? displayPlatform(source) : input.platform();
        String author = source != null ? source.author() : input.author();
        String url = source != null ? source.url() : input.sourceUrl();
        String published = source != null ? source.published() : input.published();
        // 粘贴素材（降级路径）也要留痕：源必留痕是铁律，粘贴的正文同样会「过期」不可重建
        String pastedRaw = null;
        if (source == null && text != null && !text.isBlank()) {
            pastedRaw = "pasted-" + shortHash(text) + ".txt";
            try {
                repository.saveRaw(userId, pastedRaw, text);
            } catch (Exception e) {
                log.warn("learn 粘贴素材留痕失败 | userId={} | {}", userId, e.getMessage());
                pastedRaw = null;
            }
        }
        LearnCard card = digest(userId, text, typeHint, platform, author, url, published);
        promoteRawAssets(userId, card, source, pastedRaw, extraRawNames);
        job.done(card.type(), card.title(), card.topic());
    }

    /**
     * 消化成功后把素材从暂存区**归位**到主题目录（2026-09-12 结构统一批）：
     * 与 Mac 侧技能「{@code _raw/} 在主题目录内」同契约——源与卡放在一起才可复原。
     */
    private void promoteRawAssets(String userId, LearnCard card, LearnSource source,
                                  String pastedRaw, List<String> extraRawNames) {
        List<String> names = new ArrayList<>();
        if (source != null) {
            for (LearnSource.RawAsset a : source.rawAssets()) names.add(a.name());
            names.add(LearnTranscriptionService.transcriptRawName(source)); // 未转写则暂存区没有 → 跳过
        }
        if (pastedRaw != null) names.add(pastedRaw);
        if (extraRawNames != null) names.addAll(extraRawNames);   // 图片源：原图
        if (names.isEmpty()) return;
        try {
            repository.promoteRaw(userId, card.type(), card.topic(), names);
        } catch (Exception e) {
            log.warn("learn 素材归位失败 | userId={} | {}", userId, e.getMessage());
        }
    }

    /** 内容短哈希（粘贴素材留痕文件名，幂等：同正文重复整理命中同一份留痕）。 */
    static String shortHash(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
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
    public record DigestJobStatus(String status, String type, String title, String topic, String message,
                                  String stage, SourceView source, CostView cost) {

        /** 兼容旧调用（2026-09-10 先例：四字段）。 */
        public DigestJobStatus(String status, String type, String title, String message) {
            this(status, type, title, null, message, null, null, null);
        }

        /** 兼容调用（v3.57 先例：含 stage/source/cost）。 */
        public DigestJobStatus(String status, String type, String title, String message,
                               String stage, SourceView source, CostView cost) {
            this(status, type, title, null, message, stage, source, cost);
        }

        static DigestJobStatus idle() {
            return new DigestJobStatus(STATUS_IDLE, null, null, null, null, null, null, null);
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
        private volatile String topic;
        private volatile String message;
        private volatile LearnSource source;
        private volatile String pendingTypeHint;
        private volatile LearnSource pendingSource;
        private volatile ResolvedInput pendingInput;
        private volatile LearnTranscriptionService.CostEstimate pendingCost;
        /** 提交时已留痕的素材名（图片源：原图先落暂存区，消化成功后与卡一起归位到主题目录）。 */
        private volatile List<String> pendingRawNames = List.of();
        private final long createdAt = System.currentTimeMillis();
        private volatile long settledAt = 0L;

        boolean isRunning() {
            return STATUS_RUNNING.equals(status);
        }

        boolean isAwaitingConfirm() {
            return STATUS_NEEDS_CONFIRMATION.equals(status);
        }

        /** 终态（done/failed/cancelled）：可被新提交替换；非终态（running/needs_confirmation）在跑/待回话。 */
        boolean isTerminal() {
            return STATUS_DONE.equals(status) || STATUS_FAILED.equals(status)
                    || STATUS_CANCELLED.equals(status);
        }

        long elapsedSinceSettled() {
            return settledAt == 0L ? 0L : System.currentTimeMillis() - settledAt;
        }

        void awaitConfirm(LearnTranscriptionService.CostEstimate estimate) {
            this.pendingSource = source;
            this.pendingCost = estimate;
            // 对抗审查 P2-3：等确认时**不能**留 stage=transcribing —— 状态与阶段自相矛盾，
            // 前端按 stage 渲染会显示「正在转写」，而实际在等你拍板、一分钱没花。置空交由状态渲染。
            this.stage = null;
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

        void done(String type, String title, String topic) {
            this.status = STATUS_DONE;
            this.type = type;
            this.title = title;
            this.topic = topic;
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
            return new DigestJobStatus(status, type, title, topic, message, stage, sourceView, costView);
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
        String userPrompt = buildDigestPrompt(content, platform, author, url, published, typeHint,
                existingTopics(userId));
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
        // 对抗审查 P2-2：原先只卡「标题非空」→ 核心观点/要点全空也会落一张只有标题的空卡，
        // 而 AI 已经花了钱。要求「核心观点或要点至少有一个」，否则 fail-visible（素材已留存）。
        if ((parsed.coreView() == null || parsed.coreView().isBlank()) && parsed.keyPoints().isEmpty()) {
            repository.saveRawSource(userId, content);
            throw new LearnException("AI 这次没给出可用的要点，原始素材已留存（learn/_raw/），可稍后重试");
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
                "", LearnCard.topicDir(parsed.topic()));
        repository.save(userId, card);
        log.info("learn 卡片化完成 | userId={} | type={} | topic={} | title={} | 要点 {} 条 | 疑问 {} 条",
                userId, card.type(), card.topic(), card.title(),
                card.keyPoints().size(), card.questions().size());
        return card;
    }

    /** 已有主题目录（按类型列出，供 LLM 归并到同一主题——2026-09-12 结构统一批）。 */
    private String existingTopics(String userId) {
        StringBuilder sb = new StringBuilder();
        for (String t : List.of(LearnCard.TYPE_AI, LearnCard.TYPE_TRADING, LearnCard.TYPE_OTHER)) {
            List<String> topics = repository.topics(userId, t);
            if (!topics.isEmpty()) {
                if (sb.length() > 0) sb.append("；");
                sb.append(t).append("：").append(String.join("、", topics));
            }
        }
        return sb.toString();
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

    /**
     * 老式扁平卡一次性迁移到主题目录（2026-09-12 完整升级批；**幂等**）。
     * <p>
     * 为什么需要：V1/V2 的产品卡落在 `{type}/{date}_{title}.md`，与 Mac 侧技能的主题目录契约不同构；
     * 迁到主题目录后两个写入方才真正同构（RFC §七 验收 4）。老卡不迁也能用（原地可读可写），
     * 所以这是**用户可选的一次动作**，不是启动时的强制改造。
     *
     * @return 本次迁移明细（已迁过 → 空列表）
     */
    public List<com.adaiadai.core.domain.learn.LearnCardRepository.MigrationItem> migrateLegacy(String userId) {
        return repository.migrateLegacy(userId);
    }

    /**
     * 卡片全文（md 原文，两种来源都完整可读）+ 元信息（2026-09-12 完整升级批）。
     *
     * @param content md 原文（含 frontmatter，供需要完整文件的消费方）
     * @param body    **去掉 frontmatter 的正文**（展示用；对抗审查 P2-7：直接把原文摊给用户会把
     *               {@code origin}/{@code type}/{@code created} 这些内部字段当成阿呆的话，违反第一原则）
     */
    public record CardContent(String type, String title, String topic, boolean writable,
                              String content, String body) {}

    /** 读卡片全文（?type=&title= 定位）；不存在 → 人话 400。 */
    public CardContent content(String userId, String type, String title) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) {
            throw new LearnException("卡片不存在：" + safeLabel(type, title));
        }
        LearnCard card = repository.find(userId, type, title)
                .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, title)));
        String md = repository.readCard(userId, type, title);
        if (md == null) throw new LearnException("卡片不存在：" + safeLabel(type, title));
        return new CardContent(card.type(), card.title(), card.topic(), card.writable(), md, stripFrontmatter(md));
    }

    /** 去掉 md 的 frontmatter 块（展示用；无前言块则原样返回）。 */
    static String stripFrontmatter(String md) {
        if (md == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(---\\r?\\n)(.*?)(\\r?\\n---\\r?\\n)", java.util.regex.Pattern.DOTALL).matcher(md);
        return m.find() ? md.substring(m.end()).strip() : md.strip();
    }

    /**
     * 找卡片（对话里「打开那篇」与学习页搜索）。**纯规则打分，不烧 AI**：
     * 标题命中权重最高，其次主题/标签，最后正文/要点；命中多个按分数 + 创建日期倒序。
     *
     * @param q     关键词（自然语言整句也可以——按子串匹配）
     * @param limit 返回上限（默认 5，最大 20）
     */
    public List<LearnCard> find(String userId, String q, Integer limit) {
        String query = q == null ? "" : q.strip();
        if (query.isEmpty()) return List.of();
        int max = limit == null || limit <= 0 ? 5 : Math.min(limit, 20);
        List<String> tokens = java.util.Arrays.stream(query.split("[\\s,，。！？、；;]+"))
                .map(String::strip).filter(t -> t.length() >= 2).toList();
        java.util.List<java.util.Map.Entry<LearnCard, Integer>> scored = new ArrayList<>();
        for (String type : List.of(LearnCard.TYPE_AI, LearnCard.TYPE_TRADING, LearnCard.TYPE_OTHER)) {
            for (LearnCard c : repository.list(userId, type)) {
                int score = scoreOf(c, query, tokens);
                if (score > 0) scored.add(java.util.Map.entry(c, score));
            }
        }
        scored.sort((a, b) -> a.getValue().equals(b.getValue())
                ? b.getKey().created().compareTo(a.getKey().created())
                : b.getValue() - a.getValue());
        return scored.stream().limit(max).map(java.util.Map.Entry::getKey).toList();
    }

    /** 打分：整句命中标题 10 / 主题 6 / 标签 4 / 单关键词命中各字段 2~1。 */
    private static int scoreOf(LearnCard card, String query, List<String> tokens) {
        int score = 0;
        String title = card.title() == null ? "" : card.title();
        if (!query.isEmpty() && title.contains(query)) score += 10;
        if (!query.isEmpty() && card.topic() != null && card.topic().contains(query)) score += 6;
        for (String token : tokens) {
            if (title.contains(token)) score += 3;
            if (card.topic() != null && card.topic().contains(token)) score += 2;
            if (card.tags() != null && card.tags().stream().anyMatch(t -> t.contains(token))) score += 2;
            if (card.coreView() != null && card.coreView().contains(token)) score += 1;
            if (card.keyPoints() != null && card.keyPoints().stream().anyMatch(k -> k.contains(token))) score += 1;
        }
        return score;
    }

    private String safeLabel(String type, String title) {
        String t = type == null || type.isBlank() ? "?" : type;
        String ti = title == null || title.isBlank() ? "(空标题)" : title;
        return t + "/" + ti;
    }

    /** 组装用户指令：素材 + 来源 + type 提示 + 已有主题（提示归并，避免一篇一个主题目录）。 */
    private String buildDigestPrompt(String content, String platform, String author,
                                     String url, String published, String typeHint, String existingTopics) {
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
        if (existingTopics != null && !existingTopics.isBlank()) {
            sb.append("我已有的主题目录（若这份素材属于其中之一，topic 就用**完全同名**的那个，"
                    + "便于归到同一主题；确实无关再新建）：").append(existingTopics).append("\n");
        }
        sb.append("\n素材正文：\n").append(content);
        return sb.toString();
    }

    // ── LLM 输出解析 ──

    private record DigestResult(String title, String type, String topic, List<String> tags,
                                String coreView, List<String> keyPoints,
                                List<String> questions, boolean tradeRelated, String tradeNote) {}

    private DigestResult parseDigest(String raw) throws Exception {
        String json = extractJson(raw);
        if (json == null) throw new IllegalStateException("AI 输出未包含 JSON");
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception strict) {
            // pitfall「LLM 输出 JSON 夹未转义引号」：同一素材重试即成功（概率性）——先做宽松修复再放弃。
            // 修复是启发式，可能改动文本，故留日志便于事后核对（对抗审查 P1-5）。
            String repaired = repairJson(json);
            log.warn("learn LLM 输出严格解析失败，走宽松修复 | {} | 原文 {} 字 → 修复后 {} 字",
                    strict.getMessage(), json.length(), repaired.length());
            node = MAPPER.readTree(repaired);
        }
        String title = node.path("title").asText("").strip();
        String type = node.path("type").asText("").strip().toLowerCase();
        String topic = node.path("topic").asText("").strip();
        List<String> tags = stringArray(node, "tags");
        String coreView = node.path("core_view").asText("").strip();
        List<String> keyPoints = stringArray(node, "key_points");
        List<String> questions = stringArray(node, "questions");
        boolean tradeRelated = node.path("trade_related").asBoolean(false);
        String tradeNote = node.path("trade_note").asText("").strip();
        return new DigestResult(title, type, topic, tags, coreView, keyPoints, questions, tradeRelated, tradeNote);
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
            if (c == ',' && !inString) {
                // 尾随逗号：**只处理字符串外的逗号**（对抗审查 P1-5）。原先用全局正则
                // replaceAll(",\s*([}\]])")，对转义内容一视同仁——LLM 只要在别处犯了尾随逗号，
                // 正文里本来就正确的 `, }` / `, ]` 也会被吞掉，且**解析成功、无声改坏卡片**。
                int j = i + 1;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                if (j < json.length() && (json.charAt(j) == '}' || json.charAt(j) == ']')) {
                    continue;   // 丢弃这个尾随逗号
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
