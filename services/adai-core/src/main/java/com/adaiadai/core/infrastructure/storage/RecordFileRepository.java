package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.IdGenerator;
import com.adaiadai.core.kernel.storage.FileStorage;
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
    // #19 优化：rec_ 前缀 + 标准格式 id 可直接推导年月路径直读，避免全量扫。
    // REVIEW #249：预编译复用，避免每次 findById 调用重编译正则（理解/重补/删除/Feed 热路径）。
    private static final Pattern REC_ID_PATTERN = Pattern.compile("rec_\\d{8}_\\d{9}");

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
        // #19 优化：rec_ 前缀 + 标准格式的 id 可直接从 id 推导年月路径直读，避免全量扫。
        if (id != null && REC_ID_PATTERN.matcher(id).matches()) {
            String path = RECORDS_DIR + "/" + id.substring(4, 8) + "/" + id.substring(8, 10) + "/" + id + ".md";
            // 2026-08-27：引用悬空（image_qa 引用的图片记录已不存在）→ 静默返回 empty，
            // 不经过 parseFromFile 的 WARN——该 WARN（REVIEW #248）只针对「文件存在但空/损坏」，
            // 不存在是合法降级（Feed 缩略图缺失、引用解析跳过），不该刷生产日志。
            if (fileStorage.exists(userId, path)) {
                ContentRecord record = parseFromFile(userId, path);
                if (record != null && id.equals(record.id())) {
                    return Optional.of(record);
                }
            }
        }
        // 兼容回退：id 月份 ≠ createdAt 月份（月边界迁移）/ 不规则历史 id → 全量扫兜底
        return findAll(userId).stream()
                .filter(r -> r.id().equals(id))
                .findFirst();
    }

    @Override
    public List<ContentRecord> findAll(String userId) {
        // #19 优化：限定 records/ 目录扫描（原 listFiles("") 全盘扫用户目录，
        // 会遍历 memory/index/ai-logs 等无关目录；records/ 下还有 cards/、media/ 靠 filter 排除）
        List<String> files = fileStorage.listFiles(userId, RECORDS_DIR);
        return files.stream()
                .filter(f -> f.endsWith(".md"))
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
    @Override
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
                intent: %s
                ---
                %s

                """.formatted(
                record.id(),
                record.type(),
                record.source(),
                String.join(", ", record.tags()),
                record.createdAt().toString(),
                singleLine(record.summary()),
                record.domain() != null ? record.domain() : "life",
                record.intent() != null ? record.intent() : "",
                record.content()
        );
    }

    /**
     * frontmatter 值单行化：换行/回车压成空格，防多行 AI 内容泄漏破坏行式解析（#135）。
     */
    private static String singleLine(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private ContentRecord parseFromFile(String userId, String path) {
        String content = fileStorage.read(userId, path);
        if (content == null || content.isBlank()) {
            // REVIEW #248：损坏/空文件不再静默——#19 直读路径下单个文件 frontmatter 损坏
            // 会让该记录在 Feed/时间线/搜索无声消失（磁盘文件仍在），打日志便于定位。
            log.warn("Record 文件为空或不可读，userId={} path={}", userId, path);
            return null;
        }
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            log.warn("Record 文件缺少 frontmatter（损坏），userId={} path={}", userId, path);
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
        // P1-W12：createdAt 缺失/损坏 = 数据损坏记录，跳过不进内存（防 null 参与排序/日期过滤/误删）
        if (createdAt == null) return null;

        String summary = fields.getOrDefault("summary", null);
        if (summary != null && summary.isBlank()) summary = null;
        String domain = fields.getOrDefault("domain", "life");
        if (domain != null && domain.isBlank()) domain = "life";
        // #144：intent 落盘——rebuild 借此区分 question 记录，避免重跑烧 AI
        String intent = fields.getOrDefault("intent", null);
        if (intent != null && intent.isBlank()) intent = null;
        return new ContentRecord(id, type, source, extractTitle(body, id), body, tags, createdAt, intent, summary, domain);
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

    /**
     * 解析 createdAt（REVIEW P1-W12 / B37）：缺失/损坏 → 返回 null，调用方跳过/拒删——
     * 禁止回退 now()（会把脏记录误归"今天"，导致月边界删除失败 + Feed 错计）。
     */
    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
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
