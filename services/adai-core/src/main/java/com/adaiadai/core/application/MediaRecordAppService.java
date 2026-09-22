package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.infrastructure.ai.vision.ImageRequest;
import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.ImageQaFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * MediaRecordAppService — 图片记录用例编排（多模态，L4）。
 * <p>
 * 图片 → File First 存储（records/yyyy/MM/media/）→ VLM 理解 → ContentRecord 沉淀 → Memory 沉淀。
 * Everything is Content：图片理解文本化后，Timeline / Memory / Search 全走现有文本闭环。
 * <p>
 * VLM 失败不丢数据：降级用备注/占位 summary 保存记录（与文本记录 AI 失败降级同原则）。
 */
@Service
public class MediaRecordAppService {

    private static final Logger log = LoggerFactory.getLogger(MediaRecordAppService.class);
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    /** #214：图片追问 question 上界（超长会原样进 image_qa 记录 + ai-log prompt）。 */
    private static final int MAX_QUESTION_LENGTH = 500;
    /** Phase 1 带图 ask：多图问答一次上限（用户 08-14 拍板：数量限制 3 张）。 */
    private static final int MAX_BATCH_IMAGES = 3;
    /**
     * 薄 image 记录的 summary 哨兵（图文一体，RFC 20260815-media-event-unification）。
     * <p>
     * 一次投递的 N 张图各落一条薄记录，**只作原图索引**（不做 VLM、不沉淀记忆）。哨兵取非空值
     * 使 {@code RecordRetryService.alreadyProcessed} 判为「已处理」，避免每张附件被单独重跑 VLM
     * 并重复沉淀记忆（那就退回了「N 条记录各写各的总结」的旧问题）。
     */
    static final String ATTACHMENT_SUMMARY = "图片附件";

    private final VisualAiClient visualAiClient;
    private final RecordFileRepository recordFileRepository;
    private final MemoryService memoryService;
    private final FileStorage fileStorage;
    private final CardFileRepository cardRepository;
    private final PluginService pluginService;
    /** RFC 20260817：交易日志自动归集（截图识别为当日成交 → 候选，待确认）。 */
    private final TradeLogCollectService tradeLogCollectService;

    /** 投递级幂等索引路径（REVIEW P1-多图2：客户端超时重试不得重复入库）。 */
    private static final String DELIVERY_INDEX_PATH = "index/media-deliveries.json";
    /** 幂等索引保留条数（短窗即可——覆盖「超时后用户手动重试 / 网络抖动重发」的时间范围）。 */
    private static final int DELIVERY_INDEX_MAX = 200;
    /** per-user RMW 锁（与 TagIndexService / MemoryService 同口径：索引是 load→改→save）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> deliveryLocks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper deliveryMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public MediaRecordAppService(VisualAiClient visualAiClient,
                                 RecordFileRepository recordFileRepository,
                                 MemoryService memoryService,
                                 FileStorage fileStorage,
                                 CardFileRepository cardRepository,
                                 PluginService pluginService,
                                 TradeLogCollectService tradeLogCollectService) {
        this.visualAiClient = visualAiClient;
        this.recordFileRepository = recordFileRepository;
        this.memoryService = memoryService;
        this.fileStorage = fileStorage;
        this.cardRepository = cardRepository;
        this.pluginService = pluginService;
        this.tradeLogCollectService = tradeLogCollectService;
    }

    /**
     * 记录一张图片：保存原图 → VLM 理解 → 沉淀记录 + 记忆。
     *
     * @throws IllegalArgumentException 非图片或超 5MB
     */
    public MediaRecordResult recordImage(String userId, byte[] imageBytes, String contentType, String caption) {
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("仅支持图片文件");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片不能超过 5MB");
        }

        String id = RecordFileRepository.generateId();
        LocalDateTime now = LocalDateTime.now();
        String mediaPath = recordFileRepository.saveMedia(userId, id, imageBytes, extensionOf(contentType), now);

        // R1 AI 交互日志：挂载图片记录锚点，LoggingVisualAiClient 装饰器读取
        AiTraceContext.set(userId, id, null, "media");

        // VLM 理解（失败不丢数据：降级用备注/占位）
        ImageUnderstanding understanding;
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            understanding = visualAiClient.understand(new ImageRequest(base64, contentType, caption));
        } catch (Exception e) {
            log.warn("VLM 理解失败，图片记录降级保存 | id={} | {}", id, e.getMessage());
            String fallback = caption != null && !caption.isBlank() ? caption : "图片记录";
            understanding = new ImageUnderstanding(fallback, "photo", "", List.of());
        }

        String summary = understanding.summary() != null ? understanding.summary() : "图片记录";
        List<String> tags = understanding.tags() != null ? understanding.tags() : List.of();
        String content = buildContent(understanding, caption);
        // REVIEW P1-B4：图片记录 domain 与文本路径同口径走 gateDomain（D5 不变量——
        // VLM 判 category=trading 时，无 trading 插件用户不得落盘 trading 标注）

        ContentRecord record = new ContentRecord(
                id, "image", "user_input",
                // #166：按 code point 截断（substring 按 UTF-16 char 会拆断 emoji/surrogate pair）
                truncateByCodePoints(summary, 50),
                content, tags, now,
                "log", summary, pluginService.gateDomain(userId, ImageUnderstanding.domainOf(understanding.category()))
        );
        recordFileRepository.save(userId, record);

        // Memory 沉淀（best-effort，失败不阻塞记录）
        try {
            memoryService.persist(userId, Memory.fromImageRecord(id, summary, tags));
        } catch (Exception e) {
            log.warn("图片记录记忆沉淀失败 | id={} | {}", id, e.getMessage());
        }

        // RFC 20260817：交易日志自动归集（与多图路径同一编排；含 P2-多图3 的前置判别）
        collectTradingCandidates(userId, id, understanding, summary);

        log.info("图片记录完成 | id={} | summary={} | tags={} | domain={}",
                id, summary, tags, record.domain());
        return new MediaRecordResult(id, "log", summary, tags, mediaPath);
    }

    /**
     * 一次投递的多张图片（图文一体，RFC 20260815-media-event-unification + 20260815-image-chat-interaction）：
     * **N 张图 + 可选一句话 = 一个回合 = 一条主记录**。
     * <p>
     * 与 {@link #recordImage}（单图，逐张各成一条）的区别：
     * <ul>
     *   <li>N 张原图各落一条**薄 image 记录**（仅作原图索引，不做 VLM、不沉淀记忆）——原图访问
     *       {@code GET /records/media/{attachmentId}} 与追问链路因此**零改动**（RFC 决策 1/2）；</li>
     *   <li>主记录用 {@code mediaIds} 引用这 N 条附件（freeze §2.1 MINOR），是 Feed 里唯一的卡；</li>
     *   <li>**一次**多图视觉调用：无提问 → 一段综合总结（type=image）；有提问 → 据图作答（type=image_qa）。</li>
     * </ul>
     * 并发/重复：幂等由调用方（{@code MediaDeliveryRegistry} + {@code Idempotency-Key}）保证，
     * 命中即返回首次结果、不重跑 AI 也不重复落盘。
     *
     * @param asQuestion 用户那句话是否为问句（Controller 用 {@code IntentRecognizer} 判定后传入；
     *                   text 为空时必须为 false）
     */
    public MediaBatchResult recordImages(String userId, List<byte[]> images, List<String> contentTypes,
                                         String text, boolean asQuestion) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("图片不能为空");
        }
        if (images.size() > MAX_BATCH_IMAGES) {
            throw new IllegalArgumentException("一次最多 " + MAX_BATCH_IMAGES + " 张图片");
        }
        if (contentTypes == null || contentTypes.size() != images.size()) {
            throw new IllegalArgumentException("图片类型与图片数量不一致");
        }
        for (int i = 0; i < images.size(); i++) {
            if (contentTypes.get(i) == null || !contentTypes.get(i).startsWith("image/")) {
                throw new IllegalArgumentException("仅支持图片文件");
            }
            if (images.get(i) == null || images.get(i).length == 0) {
                throw new IllegalArgumentException("图片内容为空");
            }
            if (images.get(i).length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("图片不能超过 5MB");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<String> mediaIds = new ArrayList<>(images.size());
        List<ImageRequest> requests = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            mediaIds.add(saveAttachment(userId, images.get(i), contentTypes.get(i), now));
            requests.add(new ImageRequest(
                    Base64.getEncoder().encodeToString(images.get(i)), contentTypes.get(i), null));
        }

        // R1 AI 交互日志：挂载首图附件锚点（与单图/多图问答同口径，可溯源）
        AiTraceContext.set(userId, mediaIds.get(0), null, "media");

        if (asQuestion) {
            return recordImagesAsQuestion(userId, mediaIds, requests, text, now);
        }
        return recordImagesAsLog(userId, mediaIds, requests, text, now);
    }

    /** 无提问分支：一次多图识别 → 一段综合总结 → 1 条图文记录（type=image）。 */
    private MediaBatchResult recordImagesAsLog(String userId, List<String> mediaIds,
                                               List<ImageRequest> requests, String text, LocalDateTime now) {
        ImageUnderstanding understanding;
        try {
            understanding = visualAiClient.understandMulti(requests, text);
            if (understanding == null) {
                throw new IllegalStateException("多图理解返回空结果");
            }
        } catch (Exception e) {
            log.warn("多图理解失败，图文记录降级保存 | images={} | {}", mediaIds.size(), e.getMessage());
            String fallback = text != null && !text.isBlank() ? text : "图片记录";
            understanding = new ImageUnderstanding(fallback, "photo", "", List.of());
        }
        String summary = understanding.summary() != null && !understanding.summary().isBlank()
                ? understanding.summary() : "图片记录";
        List<String> tags = understanding.tags() != null ? understanding.tags() : List.of();
        String content = buildMultiContent(understanding, text);
        // REVIEW P1-B4：domain 与文本路径同口径走 gateDomain（无 trading 插件不得落 trading 标注）
        String domain = pluginService.gateDomain(userId, ImageUnderstanding.domainOf(understanding.category()));

        String recordId = RecordFileRepository.generateId();
        ContentRecord record = new ContentRecord(
                recordId, "image", "user_input",
                truncateByCodePoints(summary, 50),
                content, tags, now, "log", summary, domain, List.copyOf(mediaIds));
        recordFileRepository.save(userId, record);

        // 记忆沉淀：**一条**（旧路径是 N 张图 N 条记忆，图文一体后收敛）
        try {
            memoryService.persist(userId, Memory.fromImageRecord(recordId, summary, tags));
        } catch (Exception e) {
            log.warn("图文记录记忆沉淀失败 | id={} | {}", recordId, e.getMessage());
        }

        // 交易归集：与单图路径同口径，保功能不回退（非交易图的成本优化见 REVIEW P2-多图3，另批）
        collectTradingCandidates(userId, recordId, understanding, summary);

        log.info("图文记录完成（多图）| id={} | images={} | summary={} | tags={} | domain={}",
                recordId, mediaIds.size(), summary, tags, domain);
        return new MediaBatchResult(recordId, List.copyOf(mediaIds), "image", "log",
                summary, null, tags, domain, false);
    }

    /** 有提问分支：一次多图作答 → 1 条 image_qa 记录（引用全部图）+ Q/A 追加首图卡（复用 #209）。 */
    private MediaBatchResult recordImagesAsQuestion(String userId, List<String> mediaIds,
                                                    List<ImageRequest> requests, String text, LocalDateTime now) {
        String question = text.strip();
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("问题过长（最多 " + MAX_QUESTION_LENGTH + " 字符）");
        }
        String answer = visualAiClient.askMulti(requests, question);
        String recordId = RecordFileRepository.generateId();
        String content = """
                【多图问答】
                图片记录：%s
                问：%s
                答：%s
                """.formatted(String.join(", ", mediaIds), question, answer == null ? "" : answer.strip());
        ContentRecord record = new ContentRecord(
                recordId, "image_qa", "user_input",
                truncate(answer == null ? "" : answer, 50), content, List.of(), now,
                "question", answer, "life", List.copyOf(mediaIds));
        recordFileRepository.save(userId, record);
        appendQaToImageCard(userId, mediaIds.get(0), question, answer, now);

        log.info("图文问答完成（多图）| images={} | qaId={} | question=\"{}\"",
                mediaIds.size(), recordId, truncate(question, 40));
        return new MediaBatchResult(recordId, List.copyOf(mediaIds), "image_qa", "question",
                answer, answer, List.of(), "life", false);
    }

    /**
     * 薄 image 记录：只作原图索引（RFC 决策 1）——落原图 + 写一条不跑 AI 的记录。
     * summary 用 {@link #ATTACHMENT_SUMMARY} 哨兵，防 {@code RecordRetryService} 单独重识别。
     */
    private String saveAttachment(String userId, byte[] bytes, String contentType, LocalDateTime now) {
        String id = RecordFileRepository.generateId();
        recordFileRepository.saveMedia(userId, id, bytes, extensionOf(contentType), now);
        ContentRecord thin = new ContentRecord(
                id, "image", "user_input",
                ATTACHMENT_SUMMARY, "", List.of(), now, "log", ATTACHMENT_SUMMARY, "life");
        recordFileRepository.save(userId, thin);
        return id;
    }

    /** 多图正文：按序保留 VLM 的合并 OCR + 用户那句话（两者都空时退回 summary）。 */
    private String buildMultiContent(ImageUnderstanding u, String text) {
        StringBuilder sb = new StringBuilder();
        if (u.extractedText() != null && !u.extractedText().isBlank()) {
            sb.append("【图片文字】").append(u.extractedText().strip());
        }
        if (text != null && !text.isBlank()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("【备注】").append(text.strip());
        }
        if (sb.length() == 0) {
            return u.summary() != null ? u.summary() : "";
        }
        return sb.toString();
    }

    /**
     * 交易日志自动归集（RFC 20260817）——单图与多图两条入账路径共用，口径完全一致。
     * <p>
     * 归集原料用 {@code extractedText}（OCR 全文）优先——summary 只是概括（flash 只给 6 字，表格行拆不出）。
     * <p>
     * <b>P2-多图3（2026-09-22）前置判别</b>：此前**任何**图片（含篮球群聊截图这类生活图）都会白跑一次
     * 交易表格解析（含一次 LLM 调用）。判据刻意保守——**任一命中即归集**：
     * VLM 判 {@code category=trading}，或 OCR/摘要里出现交易特征词（成交/委托/买入/卖出/证券/6 位代码…）。
     * 宁可多跑一次，也不让「截图入账」这条核心工作流漏掉一笔。
     */
    private void collectTradingCandidates(String userId, String recordId,
                                          ImageUnderstanding understanding, String summary) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return;
        }
        if (!looksLikeTrading(understanding, summary)) {
            log.debug("图片不像交易素材，跳过交易归集 | id={}", recordId);
            return;
        }
        try {
            // 归集原料用 extractedText（OCR 全文）优先——summary 只是概括（表格行拆不出）
            String ocr = (understanding.extractedText() != null && !understanding.extractedText().isBlank())
                    ? understanding.extractedText() : summary;
            // P2-交易44（2026-09-14）：被表格规则丢弃的行必须可见——此处是通用图片记录入口，
            // 响应契约不在本批改动面内，用 WARN 如实记录（原来只在解析器里 log.debug 静默吞）
            java.util.List<TradingImportParser.UnparsedLine> dropped =
                    tradeLogCollectService.collectDetailed(userId, ocr, "image").dropped();
            if (!dropped.isEmpty()) {
                log.warn("图文记录归集：有 {} 行没能归集 | id={} | {}", dropped.size(), recordId,
                        dropped.stream().map(TradingImportParser.UnparsedLine::describe).toList());
            }
        } catch (Exception e) {
            log.warn("交易日志归集失败（不影响记录）| id={} | {}", recordId, e.getMessage());
        }
    }

    /** 交易特征词（P2-多图3 前置判别）：任一命中即认为值得跑一次表格解析（保守优先）。 */
    private static final java.util.regex.Pattern TRADING_HINT = java.util.regex.Pattern.compile(
            "成交|委托|买入|卖出|证券|股票|持仓|股份|资金|券商|通达信|开盘|收盘|涨停|跌停|成本价|盈亏|\\d{6}");

    /**
     * 这张图/这段识别结果是否像交易素材（决定要不要花一次表格解析）。
     * 判据是「或」：宁可对生活图多跑一次，也不让交易截图漏归集。
     */
    private boolean looksLikeTrading(ImageUnderstanding understanding, String summary) {
        if (understanding == null) {
            return false;
        }
        if ("trading".equals(ImageUnderstanding.domainOf(understanding.category()))) {
            return true;
        }
        String text = (understanding.extractedText() == null ? "" : understanding.extractedText())
                + " " + (summary == null ? "" : summary);
        return TRADING_HINT.matcher(text).find();
    }

    /**
     * 查找记录对应的媒体文件相对路径（供 GET 预览）。
     * <p>
     * S-2 聚合卡身份断裂修复：入参为 {@code image_qa} 聚合记录 id 时，该记录本身没有媒体文件
     * （{@code {id}.{ext}} 不存在）——回退解析 content 引用的首张图片记录（freeze §2.1：
     * {@code 图片记录：{id1}, {id2}}）→ 返回首图 mediaPath。普通 image 记录原语义不变。
     */
    public Optional<String> mediaPathFor(String userId, String id) {
        Optional<String> direct = recordFileRepository.findMediaPath(userId, id);
        if (direct.isPresent()) {
            return direct;
        }
        return referencedImageIdsOf(userId, id).stream()
                .findFirst()
                .flatMap(firstId -> recordFileRepository.findMediaPath(userId, firstId));
    }

    /**
     * 解析 image_qa 聚合记录引用的图片记录 id 列表（可能是多张）。
     * <p>
     * 供 MediaController 追问路由与 {@link #mediaPathFor} 缩略图回退共用：
     * 非 image_qa 记录 / 未知 id / 无引用 → 返回空列表（调用方走原单图路径）。
     */
    public List<String> referencedImageIdsOf(String userId, String id) {
        Optional<ContentRecord> record = recordFileRepository.findById(userId, id);
        if (record.isEmpty() || !"image_qa".equals(record.get().type())) {
            return List.of();
        }
        return ImageQaFormatter.imageRecordIds(record.get().content());
    }

    /**
     * 图片追问（L4 图片问答）：重新取原图 → VLM 回答 → 沉淀问答记录。
     * <p>
     * 每次追问独立沉淀为 {@code image_qa} 记录（File First，时间线/搜索可见），
     * content 中保留图片记录 ID 用于溯源。回答本身即信息，进个人资产闭环。
     *
     * @param userId   用户
     * @param recordId 图片记录 ID（rec_xxx）
     * @param question 用户对图片的追问
     * @return 回答 + 问答记录 ID
     */
    public AskResult askImage(String userId, String recordId, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        // #214：question 无上界会原样进 image_qa 记录 content 与 ai-log prompt（超大 prompt/文件/日志行）
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("问题过长（最多 " + MAX_QUESTION_LENGTH + " 字符）");
        }
        Optional<String> mediaPathOpt = recordFileRepository.findMediaPath(userId, recordId);
        if (mediaPathOpt.isEmpty()) {
            throw new IllegalArgumentException("未找到图片记录: " + recordId);
        }
        String mediaPath = mediaPathOpt.get();
        byte[] bytes = fileStorage.readBytes(userId, mediaPath);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片文件缺失: " + recordId);
        }

        String base64 = Base64.getEncoder().encodeToString(bytes);
        // R1 AI 交互日志：挂载图片记录锚点（追问同样可溯源）
        AiTraceContext.set(userId, recordId, null, "media");
        String answer = visualAiClient.ask(
                new ImageRequest(base64, contentTypeOf(mediaPath), null), question);

        String qaId = RecordFileRepository.generateId();
        LocalDateTime now = LocalDateTime.now();
        String content = """
                【图片问答】
                图片记录：%s
                问：%s
                答：%s
                """.formatted(recordId, question.strip(), answer == null ? "" : answer.strip());
        ContentRecord record = new ContentRecord(
                qaId, "image_qa", "ai_answer",
                truncate(answer, 50),
                content, List.of(), now,
                "question", answer, "life"
        );
        recordFileRepository.save(userId, record);

        // #209：图片追问气泡持久化——Q/A 追加进图片卡关联的 card 文件（id=图片记录 id），
        // 刷新后追问历史仍挂在图片卡下。image_qa 独立记录保留（时间线/搜索资产沉淀），两者不冲突。
        appendQaToImageCard(userId, recordId, question, answer, now);

        log.info("图片追问完成 | imageId={} | qaId={} | question=\"{}\" | answer=\"{}\"",
                recordId, qaId, truncate(question, 40), truncate(answer, 60));
        return new AskResult(qaId, answer, recordId);
    }

    /**
     * 多图问答（Phase 1 带图 ask）：对已上传的 1-3 张图片一次提问，VLM 综合看图回答。
     * <p>
     * 与单图追问同链：沉淀 {@code image_qa} 记录（content 引用全部图片 id）+ Q/A 追加到首图卡
     * （Feed 刷新后首图卡显示问答气泡，复用 {@link #appendQaToImageCard} 与 FeedAppService 的 turns 合并）。
     * intent 分流由 Controller 判定（与文本记录「入口统一，后台分流」一致），本方法只执行 QUESTION 分支。
     *
     * @param userId    用户
     * @param recordIds 已上传的图片记录 ID（1..MAX_BATCH_IMAGES）
     * @param question  用户对多图的提问
     * @return 回答 + 问答记录 ID + 涉及图片
     */
    public AskBatchResult askImages(String userId, List<String> recordIds, String question) {
        if (recordIds == null || recordIds.isEmpty()) {
            throw new IllegalArgumentException("图片不能为空");
        }
        if (recordIds.size() > MAX_BATCH_IMAGES) {
            throw new IllegalArgumentException("一次最多 " + MAX_BATCH_IMAGES + " 张图片");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        // #214：question 无上界会原样进 image_qa 记录 content 与 ai-log prompt（与单图 ask 同口径）
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("问题过长（最多 " + MAX_QUESTION_LENGTH + " 字符）");
        }

        // 取 N 张原图（任一张缺失 → 400，不落半截问答记录）
        List<ImageRequest> requests = new ArrayList<>(recordIds.size());
        for (String recordId : recordIds) {
            Optional<String> mediaPathOpt = recordFileRepository.findMediaPath(userId, recordId);
            if (mediaPathOpt.isEmpty()) {
                throw new IllegalArgumentException("未找到图片记录: " + recordId);
            }
            byte[] bytes = fileStorage.readBytes(userId, mediaPathOpt.get());
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("图片文件缺失: " + recordId);
            }
            requests.add(new ImageRequest(
                    Base64.getEncoder().encodeToString(bytes),
                    contentTypeOf(mediaPathOpt.get()), null));
        }

        // R1 AI 交互日志：挂载首个图片记录锚点（多图问答同样可溯源）
        AiTraceContext.set(userId, recordIds.get(0), null, "media");

        String answer = visualAiClient.askMulti(requests, question);

        String qaId = RecordFileRepository.generateId();
        LocalDateTime now = LocalDateTime.now();
        String content = """
                【多图问答】
                图片记录：%s
                问：%s
                答：%s
                """.formatted(String.join(", ", recordIds), question.strip(), answer == null ? "" : answer.strip());
        ContentRecord record = new ContentRecord(
                qaId, "image_qa", "ai_answer",
                truncate(answer, 50),
                content, List.of(), now,
                "question", answer, "life"
        );
        recordFileRepository.save(userId, record);

        // Q/A 持久化到首图卡 card 文件（刷新后首图卡显示多图问答气泡，复用 #209 合并链路）
        appendQaToImageCard(userId, recordIds.get(0), question, answer, now);

        log.info("多图问答完成 | images={} | qaId={} | question=\"{}\" | answer=\"{}\"",
                recordIds.size(), qaId, truncate(question, 40), truncate(answer, 60));
        return new AskBatchResult("question", answer, qaId, List.copyOf(recordIds));
    }

    /**
     * 图片追问持久化：把本轮 Q/A 追加进图片卡 card 文件。
     * <p>
     * 图片卡在前端是 {@code ContentRecord(type=image)}，追问历史此前只存前端内存（刷新即丢）。
     * 这里用图片记录 id 作为 card id，追加 turns——FeedAppService 读取时把该 card 的 turns
     * 合并进图片记录 entry，刷新后图片卡下对话历史完整可见。
     */
    private void appendQaToImageCard(String userId, String imageRecordId, String question, String answer, LocalDateTime now) {
        String time = now.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        String cleanAnswer = answer == null ? "" : answer.strip();
        CardRecord existing = cardRepository.findById(userId, imageRecordId).orElse(null);
        CardRecord updated;
        if (existing != null) {
            // 已有追问历史 → 追加本轮的 Q + A（withTurn 会刷新 updatedAt）
            updated = existing.withTurn(true, question.strip(), time)
                    .withTurn(false, cleanAnswer, time);
        } else {
            updated = new CardRecord(
                    imageRecordId, "conversation", "active",
                    List.of(),
                    List.of(
                            new CardRecord.Turn(true, question.strip(), time),
                            new CardRecord.Turn(false, cleanAnswer, time)
                    ),
                    null, now, now
            );
        }
        cardRepository.save(userId, updated);
        log.info("图片追问已持久化到卡片 | imageId={} | turns={}", imageRecordId,
                updated.turns() != null ? updated.turns().size() : 0);
    }

    /**
     * 查一次投递的首次结果（幂等）：同一 {@code Idempotency-Key} 的重发/重试直接复用，
     * **不重跑 AI、不重复落盘**——这是 REVIEW P1-多图2（客户端超时→重试→同一张图存两份，md5 铁证）的治法。
     */
    public Optional<MediaBatchResult> findDelivery(String userId, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Object lock = deliveryLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            StoredDelivery stored = loadDeliveries(userId).get(key);
            return stored == null ? Optional.empty() : Optional.of(stored.toResult());
        }
    }

    /** 记录一次投递结果（best-effort：索引写失败不影响本次结果，只是下次重发会重新执行）。 */
    public void rememberDelivery(String userId, String key, MediaBatchResult result) {
        if (key == null || key.isBlank() || result == null) {
            return;
        }
        Object lock = deliveryLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            java.util.LinkedHashMap<String, StoredDelivery> all = loadDeliveries(userId);
            all.put(key, StoredDelivery.of(result));
            while (all.size() > DELIVERY_INDEX_MAX) {
                java.util.Iterator<String> it = all.keySet().iterator();
                it.next();
                it.remove();
            }
            try {
                fileStorage.write(userId, DELIVERY_INDEX_PATH,
                        deliveryMapper.writerWithDefaultPrettyPrinter().writeValueAsString(all));
            } catch (Exception e) {
                log.warn("投递幂等索引写入失败（不影响本次结果）| key={} | {}", key, e.getMessage());
            }
        }
    }

    private java.util.LinkedHashMap<String, StoredDelivery> loadDeliveries(String userId) {
        try {
            String json = fileStorage.read(userId, DELIVERY_INDEX_PATH);
            if (json == null || json.isBlank()) {
                return new java.util.LinkedHashMap<>();
            }
            return deliveryMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, StoredDelivery>>() {});
        } catch (Exception e) {
            // 损坏按空处理（与 TagIndexService 同口径）：宁可重新执行一次投递，也不要卡住用户
            log.warn("投递幂等索引读取失败，按空处理 | {}", e.getMessage());
            return new java.util.LinkedHashMap<>();
        }
    }

    /** 幂等索引的持久化形态（只存重建响应所需的字段）。 */
    private record StoredDelivery(String recordId, List<String> mediaIds, String type, String intent,
                                  String summary, String answer, List<String> tags, String domain) {
        static StoredDelivery of(MediaBatchResult r) {
            return new StoredDelivery(r.recordId(), r.mediaIds(), r.type(), r.intent(),
                    r.summary(), r.answer(), r.tags(), r.domain());
        }

        MediaBatchResult toResult() {
            return new MediaBatchResult(recordId, mediaIds, type, intent, summary, answer, tags, domain, false);
        }
    }

    private String contentTypeOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";
        return "image/jpeg";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return truncateByCodePoints(s, maxLen);
    }

    /**
     * 按 code point 截断到 maxLen 个字符（#166）——UTF-16 {@code substring} 按 char 截断
     * 会拆断 emoji/surrogate pair（如 title 中间出现半个 emoji）。
     */
    private static String truncateByCodePoints(String s, int maxLen) {
        if (s == null) return null;
        if (s.codePointCount(0, s.length()) <= maxLen) return s;
        int end = s.offsetByCodePoints(0, maxLen);
        return s.substring(0, end) + "…";
    }

    // ── 辅助 ──

    private String buildContent(ImageUnderstanding u, String caption) {
        StringBuilder sb = new StringBuilder();
        if (u.extractedText() != null && !u.extractedText().isBlank()) {
            sb.append("【图片文字】").append(u.extractedText());
        }
        if (caption != null && !caption.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("【备注】").append(caption);
        }
        if (sb.isEmpty()) sb.append(u.summary());
        return sb.toString();
    }

    private String extensionOf(String contentType) {
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("仅支持图片文件: " + contentType);
        }
        String subtype = contentType.substring("image/".length()).toLowerCase();
        if (!subtype.matches("[a-z0-9]+")) {
            throw new IllegalArgumentException("不支持的图片格式: " + contentType);
        }
        // 已知类型映射规范扩展名；未知 image/ 类型（如 heic/heif）按 subtype 原样落盘，
        // 避免字节是原格式却落 .png → GET 返回错误 MIME 预览坏 + VLM 收到错误 content-type（#146）
        return switch (subtype) {
            case "jpeg" -> "jpg";
            default -> subtype;
        };
    }

    public record MediaRecordResult(
            String recordId, String intent, String summary, List<String> tags, String mediaPath) {}

    /** 图片追问结果。 */
    public record AskResult(String recordId, String answer, String imageRecordId) {}

    /** 多图问答结果。 */
    public record AskBatchResult(String intent, String answer, String recordId, List<String> imageRecordIds) {}

    /**
     * 一次投递多图的结果（图文一体）。{@code recordId} = 主记录 id，同时也是卡片 id；
     * {@code mediaIds} = 附件（薄 image 记录）id，按上传顺序，原图走 {@code GET /records/media/{id}}。
     *
     * @param duplicated 幂等命中：本次请求重复了同一 {@code Idempotency-Key}，返回的是首次结果
     *                   （**没有**重新识别、**没有**重复落盘）
     */
    public record MediaBatchResult(
            String recordId,
            List<String> mediaIds,
            String type,
            String intent,
            String summary,
            String answer,
            List<String> tags,
            String domain,
            boolean duplicated) {

        /** 幂等命中时对外返回的形态（duplicated=true）。 */
        public MediaBatchResult asDuplicated() {
            return new MediaBatchResult(recordId, mediaIds, type, intent, summary, answer, tags, domain, true);
        }
    }
}
