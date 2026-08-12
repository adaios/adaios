package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.IdGenerator;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.CardRecord.Turn;
import com.adaiadai.core.kernel.record.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class CardFileRepository implements CardRepository {

    private static final Logger log = LoggerFactory.getLogger(CardFileRepository.class);
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
        return IdGenerator.monotonic("card_");
    }

    /**
     * 保存卡片。
     */
    public void save(String userId, CardRecord card) {
        String path = filePath(card);
        String content = toMarkdown(card);
        fileStorage.write(userId, path, content);
    }

    /**
     * 根据 ID 查找卡片。
     * 兼容旧版数字 ID：如 "1784872873886" 也会匹配 "card_1784872873886"。
     */
    public Optional<CardRecord> findById(String userId, String id) {
        // 精确匹配
        Optional<CardRecord> exact = findAll(userId).stream()
                .filter(c -> c.id().equals(id))
                .findFirst();
        if (exact.isPresent()) return exact;

        // 兼容旧版数字 ID：尝试补 card_ 前缀
        if (!id.startsWith("card_")) {
            String withPrefix = "card_" + id;
            return findAll(userId).stream()
                    .filter(c -> c.id().equals(withPrefix))
                    .findFirst();
        }
        return Optional.empty();
    }

    /**
     * 查找最近的活跃卡片（status=active）。
     */
    public Optional<CardRecord> findActiveCard(String userId) {
        return findAll(userId).stream()
                .filter(c -> "active".equals(c.status()))
                .findFirst();
    }

    /**
     * 获取指定日期"最后活跃"的卡片列表（REVIEW updatedAt 时间基准）。
     * <p>
     * 卡片目录按 createdAt 组织，但对话可能跨日续接（updatedAt 落到最后活跃日），
     * 因此按 updatedAt 过滤而非目录。文件量小（单用户会话级），全量扫成本可接受
     * （全量遍历为已知待办 REVIEW #19）。
     */
    public List<CardRecord> findTodayCards(String userId, LocalDate date) {
        return findAll(userId).stream()
                // #206：按最后活跃日归属；缺 updatedAt（旧版卡片）回退 createdAt，不再误归"今天"
                .filter(c -> {
                    LocalDateTime active = c.updatedAt() != null ? c.updatedAt() : c.createdAt();
                    return active.toLocalDate().equals(date);
                })
                .sorted(Comparator.comparing(CardRecord::createdAt))
                .collect(Collectors.toList());
    }

    public List<CardRecord> findAll(String userId) {
        List<String> files = fileStorage.listFiles(userId, CARDS_DIR);
        // 按 id 去重：卡片迁移（CardMigrationService）曾复制出"同 id 多文件"，
        // 且新文件 frontmatter id 不带 card_ 前缀，导致 findAll 返回重复卡片。
        // 重复卡片会让 retryCards 永远选中"空摘要副本"并写回另一文件 → 死循环。
        // 这里保留信息最完整的一份（有 summary > 有 tags > updatedAt 最新）。
        Map<String, CardRecord> byId = new LinkedHashMap<>();
        for (String f : files) {
            if (!f.endsWith(".md")) continue;
            CardRecord card = parseFromFile(userId, f);
            if (card == null) continue;
            byId.merge(card.id(), card, this::keepMoreComplete);
        }
        return byId.values().stream()
                .sorted(Comparator.comparing(CardRecord::createdAt).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 同 id 多文件时保留信息更完整者：有 summary &gt; 有 tags &gt; updatedAt 最新。
     */
    private CardRecord keepMoreComplete(CardRecord a, CardRecord b) {
        int scoreA = completenessScore(a);
        int scoreB = completenessScore(b);
        if (scoreA != scoreB) return scoreA > scoreB ? a : b;
        if (a.updatedAt() != null && b.updatedAt() != null) {
            return a.updatedAt().isAfter(b.updatedAt()) ? a : b;
        }
        return a;
    }

    private int completenessScore(CardRecord c) {
        int score = 0;
        if (c.summary() != null && !c.summary().isBlank()) score += 2;
        if (c.tags() != null && !c.tags().isEmpty()) score += 1;
        return score;
    }

    /**
     * 按 ID 删除卡片文件。
     */
    public void deleteById(String userId, String id) {
        List<String> files = fileStorage.listFiles(userId, CARDS_DIR);
        boolean found = false;
        for (String file : files) {
            if (file.contains(id)) {
                fileStorage.delete(userId, file);
                log.info("Card deleted | id={} | path={}", id, file);
                found = true;
                // 继续遍历，同一个 ID 可能在不同日期目录下都有文件
            }
        }
        if (!found) {
            log.warn("Card not found for deletion | id={}", id);
        }
    }

    // ── 内部方法 ──

    private String filePath(CardRecord card) {
        // 按卡片创建日期推导目录，跨日续接对话时写回原文件，避免重复副本
        LocalDate date = card.createdAt().toLocalDate();
        return CARDS_DIR + "/" + date.format(DIR_DATE_FORMAT) + "/" + card.id() + ".md";
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

    private CardRecord parseFromFile(String userId, String path) {
        String content = fileStorage.read(userId, path);
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
        // #206：createdAt 缺失/损坏 = 数据损坏卡，跳过不进内存（避免 null 参与排序/日期过滤）
        if (createdAt == null) return null;
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

    /**
     * 解析 frontmatter 时间字段。#206：缺失/非法值返回 null（不再回退 now()）——
     * 否则缺 updatedAt 的旧卡会被解析成"最后活跃=今天"，永久出现在今日 Feed。
     * createdAt 缺失属数据损坏，调用方（parseFromFile）据此跳过整卡。
     */
    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
