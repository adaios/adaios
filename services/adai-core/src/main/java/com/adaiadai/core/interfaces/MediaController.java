package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.MediaRecordAppService;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MediaController — 图片记录 API（多模态，L4）。
 * <p>
 * POST /api/v1/records/media — 上传图片，VLM 理解后沉淀为记录 + 记忆。
 * GET  /api/v1/records/media/{id} — 取回原图（前端预览）。
 * POST /api/v1/records/media/{id}/ask — 图片追问（L4 图片问答），VLM 回答并沉淀记录。
 */
@RestController
@RequestMapping("/api/v1/records")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final MediaRecordAppService mediaRecordAppService;
    private final FileStorage fileStorage;
    private final IntentRecognizer intentRecognizer;

    public MediaController(MediaRecordAppService mediaRecordAppService,
                           FileStorage fileStorage,
                           IntentRecognizer intentRecognizer) {
        this.mediaRecordAppService = mediaRecordAppService;
        this.fileStorage = fileStorage;
        this.intentRecognizer = intentRecognizer;
    }

    /**
     * 上传图片记录（multipart：file + 可选 caption）。
     */
    @PostMapping("/media")
    public ResponseEntity<?> uploadImage(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption) {
        try {
            MediaRecordAppService.MediaRecordResult result = mediaRecordAppService.recordImage(
                    userId, file.getBytes(), file.getContentType(), caption);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("图片记录失败 | userId={} | {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "图片记录失败: " + e.getMessage()));
        }
    }

    /**
     * 图片追问（L4 图片问答）：就一张已记录的图片提问，返回 VLM 自然语言回答。
     */
    @PostMapping("/media/{id}/ask")
    public ResponseEntity<?> askImage(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        try {
            MediaRecordAppService.AskResult result = mediaRecordAppService.askImage(
                    userId, id, body != null ? body.get("question") : null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("图片追问失败 | userId={} | id={} | {}", userId, id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "图片追问失败: " + e.getMessage()));
        }
    }

    /**
     * 多图问答（Phase 1 带图 ask）：对已上传的 1-3 张图片一次提问，VLM 综合看图回答。
     * <p>
     * 与文本记录「入口统一，后台分流」一致：Controller 用 {@link IntentRecognizer} 判定
     * 附带的文本是问句（question → VLM 多图回答）还是陈述（log → 图片已逐张记录，无需问答）。
     * AI 判定失败降级问号启发式（文本以 ？/? 结尾）。
     * <p>
     * body: {@code {"imageRecordIds": ["rec_..", "rec_.."], "question": "..."}}
     */
    @PostMapping("/media/ask-batch")
    public ResponseEntity<?> askBatchImages(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null) {
                throw new IllegalArgumentException("请求体不能为空");
            }
            List<String> recordIds = body.get("imageRecordIds") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : null;
            String question = body.get("question") instanceof String s ? s : null;
            if (recordIds == null || recordIds.isEmpty()) {
                throw new IllegalArgumentException("图片不能为空");
            }
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }

            // intent 分流：与 RecordController 同构（AI 判定，失败降级问号启发式）
            boolean isQuestion;
            try {
                isQuestion = intentRecognizer.recognizeWithAi(question)
                        == IntentRecognizer.Intent.QUESTION;
            } catch (Exception e) {
                log.warn("多图问答 intent 判定失败，降级问号启发式 | {}", e.getMessage());
                String q = question.strip();
                isQuestion = q.endsWith("？") || q.endsWith("?");
            }
            if (!isQuestion) {
                // 陈述文本：图片已在逐张上传时以 caption 记录，无需多图问答
                return ResponseEntity.ok(Map.of("intent", "log", "imageRecordIds", recordIds));
            }

            MediaRecordAppService.AskBatchResult result =
                    mediaRecordAppService.askImages(userId, recordIds, question);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("多图问答失败 | userId={} | {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "多图问答失败: " + e.getMessage()));
        }
    }

    /**
     * 取回原图（预览 / 下载）。
     */
    @GetMapping("/media/{id}")
    public ResponseEntity<byte[]> getMedia(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id) {
        Optional<String> mediaPath = mediaRecordAppService.mediaPathFor(userId, id);
        if (mediaPath.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = fileStorage.readBytes(userId, mediaPath.get());
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(mediaTypeOf(mediaPath.get())).body(bytes);
    }

    private MediaType mediaTypeOf(String path) {
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (path.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (path.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (path.endsWith(".png")) return MediaType.IMAGE_PNG;
        // 未知 image/ 类型（heic/heif 等）：按实际扩展名映射，不假装 png（#146）
        if (path.endsWith(".heic")) return MediaType.parseMediaType("image/heic");
        if (path.endsWith(".heif")) return MediaType.parseMediaType("image/heif");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
