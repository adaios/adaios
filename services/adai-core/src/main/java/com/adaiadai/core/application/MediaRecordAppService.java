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
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
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

    private final VisualAiClient visualAiClient;
    private final RecordFileRepository recordFileRepository;
    private final MemoryService memoryService;
    private final FileStorage fileStorage;
    private final CardFileRepository cardRepository;

    public MediaRecordAppService(VisualAiClient visualAiClient,
                                 RecordFileRepository recordFileRepository,
                                 MemoryService memoryService,
                                 FileStorage fileStorage,
                                 CardFileRepository cardRepository) {
        this.visualAiClient = visualAiClient;
        this.recordFileRepository = recordFileRepository;
        this.memoryService = memoryService;
        this.fileStorage = fileStorage;
        this.cardRepository = cardRepository;
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

        ContentRecord record = new ContentRecord(
                id, "image", "user_input",
                // #166：按 code point 截断（substring 按 UTF-16 char 会拆断 emoji/surrogate pair）
                truncateByCodePoints(summary, 50),
                content, tags, now,
                "log", summary, ImageUnderstanding.domainOf(understanding.category())
        );
        recordFileRepository.save(userId, record);

        // Memory 沉淀（best-effort，失败不阻塞记录）
        try {
            memoryService.persist(userId, Memory.fromImageRecord(id, summary, tags));
        } catch (Exception e) {
            log.warn("图片记录记忆沉淀失败 | id={} | {}", id, e.getMessage());
        }

        log.info("图片记录完成 | id={} | summary={} | tags={} | domain={}",
                id, summary, tags, record.domain());
        return new MediaRecordResult(id, "log", summary, tags, mediaPath);
    }

    /**
     * 查找记录对应的媒体文件相对路径（供 GET 预览）。
     */
    public Optional<String> mediaPathFor(String userId, String id) {
        return recordFileRepository.findMediaPath(userId, id);
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
}
