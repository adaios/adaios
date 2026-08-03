package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.vision.ImageRequest;
import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    private final VisualAiClient visualAiClient;
    private final RecordFileRepository recordFileRepository;
    private final MemoryService memoryService;

    public MediaRecordAppService(VisualAiClient visualAiClient,
                                 RecordFileRepository recordFileRepository,
                                 MemoryService memoryService) {
        this.visualAiClient = visualAiClient;
        this.recordFileRepository = recordFileRepository;
        this.memoryService = memoryService;
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
                summary.length() > 50 ? summary.substring(0, 50) : summary,
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
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    public record MediaRecordResult(
            String recordId, String intent, String summary, List<String> tags, String mediaPath) {}
}
