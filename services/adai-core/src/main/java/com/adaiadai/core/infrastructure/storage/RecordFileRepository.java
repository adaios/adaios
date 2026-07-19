package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
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

    private static final DateTimeFormatter ID_FORMATTER = DateTimeFormatter.ofPattern("'rec_'yyyyMMdd'_'HHmmss");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\n(.+?)\\n---\\n(.+)", Pattern.DOTALL);

    private final FileStorage fileStorage;

    public RecordFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public void save(ContentRecord record) {
        String path = filePath(record);
        String content = toMarkdown(record);
        fileStorage.write(path, content);
    }

    @Override
    public Optional<ContentRecord> findById(String id) {
        // 遍历所有 records 目录查找匹配的文件
        return findAll().stream()
                .filter(r -> r.id().equals(id))
                .findFirst();
    }

    @Override
    public List<ContentRecord> findAll() {
        List<String> files = fileStorage.listFiles("");
        return files.stream()
                .filter(f -> f.startsWith(RECORDS_DIR + "/") && f.endsWith(".md"))
                .map(this::parseFromFile)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ContentRecord::createdAt).reversed())
                .toList();
    }

    /**
     * 生成当前时间的最新 ID。
     */
    public static String generateId() {
        return LocalDateTime.now().format(ID_FORMATTER);
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
                ---
                %s

                """.formatted(
                record.id(),
                record.type(),
                record.source(),
                String.join(", ", record.tags()),
                record.createdAt().toString(),
                record.summary() != null ? record.summary() : "",
                record.content()
        );
    }

    private ContentRecord parseFromFile(String path) {
        String content = fileStorage.read(path);
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
        return new ContentRecord(id, type, source, extractTitle(body, id), body, tags, createdAt, null, summary);
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
