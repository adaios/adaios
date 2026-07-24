package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.CardRecord.Turn;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CardFileRepository — 会话卡片的文件存储。
 * <p>
 * 文件位置：data/records/YYYY/MM/DD/card_{id}.md
 * <p>
 * 文件格式：
 * <pre>
 * ---
 * id: card_a
 * type: conversation
 * status: active
 * tags: [标签1, 标签2]
 * createdAt: 2026-07-19T14:00:00
 * updatedAt: 2026-07-19T14:05:00
 * summary: 用户探讨了... (ended 后才有)
 * ---
 * ## 14:00
 * 用户：男人本色？
 * AI：这是一个复杂的文化概念...
 *
 * ## 14:02
 * 用户：如何戒掉？
 * AI：可以从这几个方面入手...
 * </pre>
 */
@Repository
public class CardFileRepository {

    private static final String CARDS_DIR = "records/cards";
    private static final DateTimeFormatter ID_FORMATTER = DateTimeFormatter.ofPattern("'card_'HHmmssSSS");
    private static final DateTimeFormatter DIR_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final FileStorage fileStorage;

    public CardFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 生成卡片 ID。
     */
    public String generateId() {
        return LocalDateTime.now().format(ID_FORMATTER);
    }

    /**
     * 保存卡片。
     */
    public void save(CardRecord card) {
        String path = filePath(card.id());
        String content = toMarkdown(card);
        fileStorage.write(path, content);
    }

    /**
     * 根据 ID 查找卡片。
     */
    public Optional<CardRecord> findById(String id) {
        // 需要遍历所有日期的目录查找
        return findAll().stream()
                .filter(c -> c.id().equals(id))
                .findFirst();
    }

    /**
     * 查找最近的活跃卡片（status=active）。
     */
    public Optional<CardRecord> findActiveCard() {
        return findAll().stream()
                .filter(c -> "active".equals(c.status()))
                .findFirst();
    }

    /**
     * 获取今天的卡片列表。
     */
    public List<CardRecord> findTodayCards(LocalDate date) {
        String prefix = CARDS_DIR + "/" + date.format(DIR_DATE_FORMAT);
        List<String> files = fileStorage.listFiles(prefix);
        return files.stream()
                .filter(f -> f.startsWith(prefix) && f.endsWith(".md"))
                .map(this::parseFromFile)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CardRecord::createdAt))
                .collect(Collectors.toList());
    }

    public List<CardRecord> findAll() {
        List<String> files = fileStorage.listFiles(CARDS_DIR);
        return files.stream()
                .filter(f -> f.endsWith(".md"))
                .map(this::parseFromFile)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CardRecord::createdAt).reversed())
                .collect(Collectors.toList());
    }

    // ── 内部方法 ──

    private String filePath(String cardId) {
        LocalDate today = LocalDate.now();
        return CARDS_DIR + "/" + today.format(DIR_DATE_FORMAT) + "/" + cardId + ".md";
    }

    private String toMarkdown(CardRecord card) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("id: ").append(card.id()).append("\n");
        sb.append("type: ").append(card.type()).append("\n");
        sb.append("status: ").append(card.status()).append("\n");
        sb.append("tags: [").append(String.join(", ", card.tags())).append("]\n");
        sb.append("createdAt: ").append(card.createdAt().toString()).append("\n");
        sb.append("updatedAt: ").append(card.updatedAt().toString()).append("\n");
        if (card.summary() != null && !card.summary().isBlank()) {
            sb.append("summary: ").append(card.summary()).append("\n");
        }
        sb.append("---\n\n");

        for (Turn turn : card.turns()) {
            sb.append("## ").append(turn.time()).append("\n");
            sb.append(turn.isUser() ? "用户：" : "AI：");
            sb.append(turn.text()).append("\n\n");
        }

        return sb.toString();
    }

    private CardRecord parseFromFile(String path) {
        String content = fileStorage.read(path);
        if (content == null || content.isBlank()) return null;

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^---\\n(.+?)\\n---\\n(.+)", java.util.regex.Pattern.DOTALL
        ).matcher(content);
        if (!matcher.find()) return null;

        String frontmatter = matcher.group(1);
        String body = matcher.group(2).strip();

        Map<String, String> fields = parseFrontmatter(frontmatter);

        String id = fields.getOrDefault("id", "unknown");
        String type = fields.getOrDefault("type", "log");
        String status = fields.getOrDefault("status", "idle");
        List<String> tags = parseTags(fields.getOrDefault("tags", ""));
        LocalDateTime createdAt = parseDateTime(fields.get("createdAt"));
        LocalDateTime updatedAt = parseDateTime(fields.get("updatedAt"));
        String summary = fields.getOrDefault("summary", null);

        // Parse turns from body
        List<Turn> turns = parseTurns(body);

        return new CardRecord(id, type, status, tags, turns, summary, createdAt, updatedAt);
    }

    private List<Turn> parseTurns(String body) {
        List<Turn> turns = new ArrayList<>();
        String[] lines = body.split("\n");
        String currentTime = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("## ")) {
                currentTime = line.substring(3).trim();
            } else if (line.startsWith("用户：")) {
                turns.add(new Turn(true, line.substring(3).trim(), currentTime));
            } else if (line.startsWith("AI：")) {
                turns.add(new Turn(false, line.substring(3).trim(), currentTime));
            }
        }
        return turns;
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
}
