package com.adaiadai.core.kernel.memory;

import com.adaiadai.core.infrastructure.storage.FileStorage;
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
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final String MEMORY_DIR = "memory";
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "---\\n" +
                    "id:\\s*(\\S+)\\n" +
                    "recordId:\\s*(\\S+)\\n" +
                    "tags:\\s*\\[([^\\]]*)\\]\\n" +
                    "sentiment:\\s*(\\S+)\\n" +
                    "actionable:\\s*(true|false)\\n" +
                    "createdAt:\\s*(\\S+?)\\n" +
                    "---\\n" +
                    "(.+?)(?=\\n---|\\z)",
            Pattern.DOTALL);

    private final FileStorage fileStorage;

    public MemoryService(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 沉淀一条记忆。
     */
    public void persist(Memory memory) {
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
        log.info("记忆已沉淀 | recordId={} | summary={}", memory.recordId(), memory.summary());
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

    // ── 内部方法 ──

    private String memoryFilePath(Memory memory) {
        String ym = memory.createdAt().format(MONTH_FORMATTER);
        return MEMORY_DIR + "/" + ym + ".md";
    }

    private String formatMemoryEntry(Memory memory) {
        return """
                ---
                id: %s
                recordId: %s
                tags: [%s]
                sentiment: %s
                actionable: %b
                createdAt: %s
                ---
                %s
                """.strip().formatted(
                memory.id(),
                memory.recordId(),
                String.join(", ", memory.tags()),
                memory.sentiment(),
                memory.actionable(),
                memory.createdAt().toString(),
                memory.summary()
        );
    }

    private List<Memory> parseEntries(String content) {
        List<Memory> result = new ArrayList<>();
        Matcher matcher = ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            try {
                String id = matcher.group(1);
                String recordId = matcher.group(2);
                List<String> tags = parseTags(matcher.group(3));
                String sentiment = matcher.group(4);
                boolean actionable = Boolean.parseBoolean(matcher.group(5));
                LocalDateTime createdAt = LocalDateTime.parse(matcher.group(6));
                String summary = matcher.group(7).strip();

                result.add(new Memory(id, recordId, summary, tags, sentiment, actionable, null, createdAt));
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
}
