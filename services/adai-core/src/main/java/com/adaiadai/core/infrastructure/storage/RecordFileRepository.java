package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.IdGenerator;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RecordFileRepository — 基于文件系统的 Record 存储实现。
 * <p>
 * 每个 Record 以 Markdown 文件存储，格式：
 * {@code data/records/YYYY/MM/rec_yyyyMMdd_HHmmss.md}
 * <p>
 * 文件包含 YAML frontmatter（id, type, source, tags, createdAt）和正文内容。
 */
@Repository
public class RecordFileRepository implements RecordRepository {

    private static final String RECORDS_DIR = "records";
    private static final String MEDIA_DIR = "media";

    private static final DateTimeFormatter ID_FORMATTER = DateTimeFormatter.ofPattern("'rec_'yyyyMMdd'_'HHmmssSSS");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\n(.+?)\\n---\\n(.+)", Pattern.DOTALL);

    private static final Logger log = LoggerFactory.getLogger(RecordFileRepository.class);

    private final FileStorage fileStorage;
    private TagIndexService tagIndexService;

    public RecordFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        this.tagIndexService = null;
    }

    /**
     * 设置 TagIndexService（用于避免循环依赖，启动时手动设置）。
     */
    public void setTagIndexService(TagIndexService tagIndexService) {
        this.tagIndexService = tagIndexService;
    }

    @Override
    public void save(String userId, ContentRecord record) {
        String path = filePath(record);
        String content = toMarkdown(record);
        fileStorage.write(userId, path, content);
        if (tagIndexService != null) {
            tagIndexService.onRecordSaved(userId, record);
        }
    }

    @Override
    public Optional<ContentRecord> findById(String userId, String id) {
        // 遍历该用户所有 records 目录查找匹配的文件
        return findAll(userId).stream()
                .filter(r -> r.id().equals(id))
                .findFirst();
    }

    @Override
    public List<ContentRecord> findAll(String userId) {
        List<String> files = fileStorage.listFiles(userId, "");
        return files.stream()
                .filter(f -> f.startsWith(RECORDS_DIR + "/") && f.endsWith(".md"))
                .filter(f -> !f.startsWith(RECORDS_DIR + "/cards/"))
                .filter(f -> {
                    String fileName = f.substring(f.lastIndexOf('/') + 1);
                    return fileName.startsWith("rec_");
                })
                .map(f -> parseFromFile(userId, f))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ContentRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public void deleteById(String userId, String id) {
        // 以持久化 createdAt 推导路径（与 save 一致，防月边界 ID 月份 ≠ createdAt 月份静默删不掉，P0 #136）
        Optional<ContentRecord> existing = findById(userId, id);
        if (existing.isEmpty()) {
            log.warn("Record not found for deletion | id={}", id);
            return;
        }
        ContentRecord r = existing.get();
        String filePath = RECORDS_DIR + "/" + r.yearMonth() + "/" + id + ".md";
        fileStorage.delete(userId, filePath);
        // 多模态：同步清理关联媒体文件（records/{yyyy}/{MM}/media/{id}.*）
        deleteMediaFiles(userId, id, r.createdAt());
        // 标签索引清理（#137：删除不清理 → 幽灵计数/已删 ID 关联）
        if (tagIndexService != null && r.tags() != null && !r.tags().isEmpty()) {
            tagIndexService.onRecordDeleted(userId, r.tags(), id);
        }
        log.info("Record deleted | id={} | path={}", id, filePath);
    }

    /**
     * 保存图片二进制文件：{@code records/{yyyy}/{MM}/media/{id}.{ext}}（多模态记录资产）。
     *
     * @return 相对用户层的媒体文件路径
     */
    public String saveMedia(String userId, String id, byte[] bytes, String ext, LocalDateTime createdAt) {
        String mediaPath = mediaPath(id, ext, createdAt);
        fileStorage.writeBytes(userId, mediaPath, bytes);
        log.info("Media saved | id={} | path={}", id, mediaPath);
        return mediaPath;
    }

    /**
     * 查找记录对应的媒体文件相对路径（records/{yyyy}/{MM}/media/{id}.*）。
     */
    public Optional<String> findMediaPath(String userId, String id) {
        if (id == null || !id.startsWith("rec_")) {
            return Optional.empty();
        }
        // 以持久化 createdAt 推导目录（与 save 一致，防 ID 月份 ≠ createdAt 月份）
        return findById(userId, id)
                .map(r -> RECORDS_DIR + "/" + r.yearMonth() + "/" + MEDIA_DIR)
                .flatMap(dir -> fileStorage.listFiles(userId, dir).stream()
                        .filter(p -> fileNameOf(p).startsWith(id + "."))
                        .findFirst());
    }

    private void deleteMediaFiles(String userId, String id, LocalDateTime createdAt) {
        String dir = RECORDS_DIR + "/" + createdAt.format(DateTimeFormatter.ofPattern("yyyy/MM")) + "/" + MEDIA_DIR;
        fileStorage.listFiles(userId, dir).stream()
                .filter(p -> fileNameOf(p).startsWith(id + "."))
                .forEach(p -> fileStorage.delete(userId, p));
    }

    private String fileNameOf(String path) {
        int idx = path.lastIndexOf('/');
        return path.substring(idx + 1);
    }

    private String mediaPath(String id, String ext, LocalDateTime createdAt) {
        return RECORDS_DIR + "/" + createdAt.format(DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + MEDIA_DIR + "/" + id + "." + ext;
    }

    /**
     * 生成当前时间的最新 ID。
     */
    public static String generateId() {
        return IdGenerator.monotonic("rec_");
    }

    @Override
    public void updateDomain(String userId, String id, String domain) {
        Optional<ContentRecord> existing = findById(userId, id);
        if (existing.isEmpty()) {
            log.warn("Cannot update domain: record not found | id={}", id);
            return;
        }
        ContentRecord r = existing.get();
        ContentRecord updated = new ContentRecord(
                r.id(), r.type(), r.source(), r.title(), r.content(),
                r.tags(), r.createdAt(), r.intent(), r.summary(), domain
        );
        save(userId, updated);
        log.info("Record domain updated | id={} | domain={}", id, domain);
    }

    // ── 内部方法 ──

    private String filePath(ContentRecord record) {
        String ym = record.yearMonth();
        return RECORDS_DIR + "/" + ym + "/" + record.id() + ".md";
    }

    private String toMarkdown(ContentRecord record) {
        return """
                ---
                id: %s
                type: %s
                source: %s
                tags: [%s]
                createdAt: %s
                summary: %s
                domain: %s
                ---
                %s

                """.formatted(
                record.id(),
                record.type(),
                record.source(),
                String.join(", ", record.tags()),
                record.createdAt().toString(),
                record.summary() != null ? record.summary() : "",
                record.domain() != null ? record.domain() : "life",
                record.content()
        );
    }

    private ContentRecord parseFromFile(String userId, String path) {
        String content = fileStorage.read(userId, path);
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        String frontmatter = matcher.group(1);
        String body = matcher.group(2).strip();

        Map<String, String> fields = parseFrontmatter(frontmatter);

        String id = fields.getOrDefault("id", "unknown");
        String type = fields.getOrDefault("type", "note");
        String source = fields.getOrDefault("source", "user_input");
        List<String> tags = parseTags(fields.getOrDefault("tags", ""));
        LocalDateTime createdAt = parseDateTime(fields.getOrDefault("createdAt", null));

        String summary = fields.getOrDefault("summary", null);
        if (summary != null && summary.isBlank()) summary = null;
        String domain = fields.getOrDefault("domain", "life");
        if (domain != null && domain.isBlank()) domain = "life";
        return new ContentRecord(id, type, source, extractTitle(body, id), body, tags, createdAt, null, summary, domain);
    }

    private Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : frontmatter.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                fields.put(key, value);
            }
        }
        return fields;
    }

    private List<String> parseTags(String tagsStr) {
        String cleaned = tagsStr.replaceAll("[\\[\\]\"'\\s]", "");
        if (cleaned.isBlank()) return List.of();
        return Arrays.stream(cleaned.split(","))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private String extractTitle(String body, String fallbackId) {
        String firstLine = body.lines().findFirst().orElse("").strip();
        if (!firstLine.isEmpty() && firstLine.length() < 100) {
            return firstLine.replaceAll("^#+\\s*", "");
        }
        return fallbackId;
    }
}
