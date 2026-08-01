package com.adaiadai.core.kernel.memory;

import com.adaiadai.core.infrastructure.storage.FileStorage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MemoryService — 个人记忆管理。
 * <p>
 * 负责将 AI 理解结果沉淀为长期记忆，并提供查询能力。
 * 采用 File First：记忆按月组织到 {@code data/memory/YYYY/MM.md}。
 * <p>
 * Phase 1：支持 pattern（行为模式）和 preference（用户偏好）的类型化读写。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MEMORY_DIR = "memory";
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // 解析条目：--- 分隔的前置元信息 + 正文
    private static final Pattern ENTRY_SPLIT = Pattern.compile(
            "---\\n(.+?)\\n---\\n(.+?)(?=\\n---|\\z)",
            Pattern.DOTALL);

    private final FileStorage fileStorage;

    public MemoryService(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 沉淀一条记忆（去重：同 recordId 同日期不重复写入）。
     */
    public void persist(Memory memory) {
        // 去重 + 升级：同 recordId 已有记忆时——AI 洞察覆盖降级条目；其余重复跳过
        LocalDate memDate = memory.createdAt().toLocalDate();
        List<Memory> existingByDate = findByDate(memDate);
        boolean isDegraded = Memory.isDegraded(memory);

        for (Memory existing : existingByDate) {
            if (!existing.recordId().equals(memory.recordId())) continue;
            if (!isDegraded && Memory.isDegraded(existing)) {
                // AI 洞察升级降级记忆：移除降级条目后写入洞察
                log.info("记忆升级：降级原文 → AI 洞察 | recordId={}", memory.recordId());
                removeFromFile(memDate, memory.recordId());
                break;
            }
            log.debug("Memory skipped (duplicate recordId): {}", memory.recordId());
            return;
        }

        String path = memoryFilePath(memory);
        String entry = formatMemoryEntry(memory);

        String existing = fileStorage.read(path);
        String content;
        if (existing != null && !existing.isBlank()) {
            content = existing + "\n" + entry;
        } else {
            content = """
                    # 记忆 - %s

                    %s
                    """.formatted(memory.createdAt().toLocalDate().toString(), entry);
        }
        fileStorage.write(path, content);
        if (memory.patterns() != null && !memory.patterns().isEmpty()) {
            log.info("记忆已沉淀 | recordId={} | summary={} | patterns={}",
                    memory.recordId(), truncate(memory.summary(), 40), memory.patterns().size());
        } else {
            log.info("记忆已沉淀 | recordId={} | summary={}", memory.recordId(), truncate(memory.summary(), 40));
        }
    }

    /**
     * 按日期查询记忆条目。
     */
    public List<Memory> findByDate(LocalDate date) {
        String path = MEMORY_DIR + "/" + date.format(DateTimeFormatter.ofPattern("yyyy/MM")) + ".md";
        String content = fileStorage.read(path);
        if (content == null || content.isBlank()) return List.of();

        return parseEntries(content).stream()
                .filter(m -> m.createdAt().toLocalDate().equals(date))
                .collect(Collectors.toList());
    }

    /**
     * 获取最近指定天数的记忆条目。
     */
    public List<Memory> recent(int days) {
        List<Memory> all = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            all.addAll(findByDate(date));
        }
        return all;
    }

    /**
     * 获取某条记录对应的 AI 理解。
     */
    public Optional<Memory> findByRecordId(String recordId) {
        // 遍历最近 30 天的记忆文件查找
        for (int i = 0; i < 30; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            List<Memory> dayMemories = findByDate(date);
            for (Memory m : dayMemories) {
                if (m.recordId().equals(recordId)) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 按 recordId 删除记忆条目。
     * 遍历所有记忆文件，找到匹配的记录并移除该条目。
     *
     * @param recordId 要删除的记录 ID
     * @return 是否找到并删除了记忆
     */
    public boolean deleteByRecordId(String recordId) {
        if (recordId == null || recordId.isBlank()) return false;
        // 搜索最近 365 天的记忆文件
        for (int i = 0; i < 365; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            String ym = date.format(DateTimeFormatter.ofPattern("yyyy/MM"));
            String path = MEMORY_DIR + "/" + ym + ".md";
            String content = fileStorage.read(path);
            if (content == null || content.isBlank()) continue;

            // 查找并移除匹配的记录
            String recordIdMarker = "recordId: " + recordId;
            if (!content.contains(recordIdMarker)) continue;

            StringBuilder sb = new StringBuilder();
            Matcher matcher = ENTRY_SPLIT.matcher(content);
            boolean removed = false;
            while (matcher.find()) {
                String entry = matcher.group();
                if (entry.contains(recordIdMarker)) {
                    removed = true;
                } else {
                    sb.append(entry).append("\n");
                }
            }
            if (removed) {
                String result = sb.toString().strip();
                if (result.isBlank()) {
                    // 文件清空则删除文件
                    fileStorage.delete(path);
                } else {
                    fileStorage.write(path, result);
                }
                log.info("Memory deleted | recordId={}", recordId);
                return true;
            }
        }
        log.warn("Memory not found for deletion | recordId={}", recordId);
        return false;
    }

    /**
     * 返回所有有记忆数据的日期列表（从新到旧）。
     */
    public List<LocalDate> findAllDates() {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 365; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            if (!findByDate(date).isEmpty()) {
                dates.add(date);
            }
        }
        return dates;
    }

    /**
     * 返回记忆总条数。
     */
    public long count() {
        long total = 0;
        for (int i = 0; i < 365; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            total += findByDate(date).size();
        }
        return total;
    }

    /**
     * 查询所有 memory 中出现的 patterns（去重，按置信度降序）。
     */
    public List<MemoryPattern> findAllPatterns() {
        Map<String, MemoryPattern> merged = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            for (Memory m : findByDate(date)) {
                if (m.patterns() != null) {
                    for (MemoryPattern p : m.patterns()) {
                        // 同内容取最高置信度
                        merged.merge(p.content(), p,
                                (a, b) -> a.confidence() >= b.confidence() ? a : b);
                    }
                }
            }
        }
        return merged.values().stream()
                .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
                .collect(Collectors.toList());
    }

    /**
     * 查询所有 memory 中出现的 preferences（去重，按置信度降序）。
     */
    public List<MemoryPreference> findAllPreferences() {
        Map<String, MemoryPreference> merged = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            for (Memory m : findByDate(date)) {
                if (m.preferences() != null) {
                    for (MemoryPreference p : m.preferences()) {
                        merged.merge(p.content(), p,
                                (a, b) -> a.confidence() >= b.confidence() ? a : b);
                    }
                }
            }
        }
        return merged.values().stream()
                .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
                .collect(Collectors.toList());
    }

    /**
     * 是否已有 AI 洞察记忆（非降级原文）。重补逻辑用它判断是否需重新理解。
     */
    public boolean hasRealMemory(String recordId) {
        Optional<Memory> memory = findByRecordId(recordId);
        return memory.isPresent() && !Memory.isDegraded(memory.get());
    }

    // ── 内部方法 ──

    private String memoryFilePath(Memory memory) {
        String ym = memory.createdAt().format(MONTH_FORMATTER);
        return MEMORY_DIR + "/" + ym + ".md";
    }

    /**
     * 移除某天文件中指定 recordId 的记忆条目（用于降级记忆被 AI 洞察升级时清理）。
     */
    private void removeFromFile(LocalDate date, String recordId) {
        String path = MEMORY_DIR + "/" + date.format(DateTimeFormatter.ofPattern("yyyy/MM")) + ".md";
        String content = fileStorage.read(path);
        if (content == null || content.isBlank()) return;

        StringBuilder sb = new StringBuilder();
        Matcher matcher = ENTRY_SPLIT.matcher(content);
        boolean removed = false;
        while (matcher.find()) {
            String entry = matcher.group();
            if (entry.contains("recordId: " + recordId)) {
                removed = true;
            } else {
                sb.append(entry).append("\n");
            }
        }
        if (removed) {
            String result = sb.toString().strip();
            if (result.isBlank()) {
                fileStorage.delete(path);
            } else {
                fileStorage.write(path, result);
            }
        }
    }

    private String formatMemoryEntry(Memory memory) {
        String patternsJson = "[]";
        String preferencesJson = "[]";
        try {
            if (memory.patterns() != null && !memory.patterns().isEmpty()) {
                patternsJson = MAPPER.writeValueAsString(memory.patterns());
            }
            if (memory.preferences() != null && !memory.preferences().isEmpty()) {
                preferencesJson = MAPPER.writeValueAsString(memory.preferences());
            }
        } catch (Exception e) {
            log.warn("序列化 patterns/preferences 失败: {}", e.getMessage());
        }

        String suggestion = memory.suggestion() != null ? memory.suggestion() : "";
        return """
                ---
                id: %s
                recordId: %s
                tags: [%s]
                sentiment: %s
                actionable: %b
                patterns: %s
                preferences: %s
                suggestion: %s
                createdAt: %s
                ---
                %s
                """.strip().formatted(
                memory.id(),
                memory.recordId(),
                String.join(", ", memory.tags()),
                memory.sentiment(),
                memory.actionable(),
                patternsJson,
                preferencesJson,
                suggestion,
                memory.createdAt().toString(),
                memory.summary()
        );
    }

    private List<Memory> parseEntries(String content) {
        List<Memory> result = new ArrayList<>();
        Matcher matcher = ENTRY_SPLIT.matcher(content);
        while (matcher.find()) {
            try {
                String frontmatter = matcher.group(1);
                String body = matcher.group(2).strip();

                // 逐行解析 frontmatter
                Map<String, String> fields = new LinkedHashMap<>();
                for (String line : frontmatter.split("\n")) {
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String key = line.substring(0, colonIdx).strip();
                        String value = line.substring(colonIdx + 1).strip();
                        fields.put(key, value);
                    }
                }

                String id = fields.getOrDefault("id", "");
                String recordId = fields.getOrDefault("recordId", "");
                String sentiment = fields.getOrDefault("sentiment", "neutral");
                boolean actionable = Boolean.parseBoolean(fields.getOrDefault("actionable", "false"));
                String suggestion = fields.getOrDefault("suggestion", null);
                if ("null".equals(suggestion)) suggestion = null;
                String createdAtStr = fields.getOrDefault("createdAt", "");
                LocalDateTime createdAt = createdAtStr.isBlank()
                        ? LocalDateTime.now()
                        : LocalDateTime.parse(createdAtStr);

                List<String> tags = parseTags(fields.getOrDefault("tags", "[]"));
                List<MemoryPattern> patterns = parsePatterns(fields.getOrDefault("patterns", "[]"));
                List<MemoryPreference> preferences = parsePreferences(fields.getOrDefault("preferences", "[]"));

                result.add(new Memory(id, recordId, body, patterns, preferences,
                        tags, sentiment, actionable, suggestion, createdAt));
            } catch (Exception e) {
                log.warn("解析记忆条目失败: {}", e.getMessage());
            }
        }
        return result;
    }

    private List<String> parseTags(String tagsStr) {
        String cleaned = tagsStr.replaceAll("[\\[\\]\"'\\s]", "");
        if (cleaned.isBlank()) return List.of();
        return Arrays.stream(cleaned.split(","))
                .filter(s -> !s.isBlank())
                .map(String::strip)
                .toList();
    }

    private List<MemoryPattern> parsePatterns(String json) {
        try {
            if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
            return MAPPER.readValue(json, new TypeReference<List<MemoryPattern>>() {});
        } catch (Exception e) {
            log.warn("解析 patterns JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<MemoryPreference> parsePreferences(String json) {
        try {
            if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
            return MAPPER.readValue(json, new TypeReference<List<MemoryPreference>>() {});
        } catch (Exception e) {
            log.warn("解析 preferences JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
