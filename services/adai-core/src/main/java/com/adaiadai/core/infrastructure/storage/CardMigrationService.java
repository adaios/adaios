package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.CardRecord.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CardMigrationService — 卡片数据迁移服务。
 * <p>
 * 负责将旧版存储在 records/ 根目录下的卡片文件（纯数字 ID），
 * 迁移到 records/cards/YYYY/MM/DD/card_{id}.md 格式。
 * <p>
 * 调用方式：POST /api/v1/cards/migrate
 */
@Service
public class CardMigrationService {

    private static final Logger log = LoggerFactory.getLogger(CardMigrationService.class);

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\n(.+?)\\n---\\n(.+)", Pattern.DOTALL);
    private static final Pattern CARD_ID_PATTERN = Pattern.compile("^(\\d+)\\.md$");

    private final FileStorage fileStorage;
    private final CardFileRepository cardRepository;

    public CardMigrationService(FileStorage fileStorage, CardFileRepository cardRepository) {
        this.fileStorage = fileStorage;
        this.cardRepository = cardRepository;
    }

    /**
     * 执行迁移。
     *
     * @return 迁移结果统计
     */
    public MigrationResult migrate() {
        List<String> allFiles = fileStorage.listFiles("records");
        List<String> cardFiles = new ArrayList<>();
        List<String> migrated = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        // Step 1: 找出 records/ 下所有非 rec_ 前缀、非 cards/ 子目录的 .md 文件
        for (String f : allFiles) {
            if (f.startsWith("records/cards/")) continue;
            if (!f.endsWith(".md")) continue;
            String fileName = f.substring(f.lastIndexOf('/') + 1);
            if (fileName.startsWith("rec_")) continue;

            cardFiles.add(f);
        }

        log.info("迁移扫描完成 | 待迁移文件数={}", cardFiles.size());

        // Step 2: 逐个解析并迁移
        for (String oldPath : cardFiles) {
            try {
                String content = fileStorage.read(oldPath);
                if (content == null || content.isBlank()) {
                    failed.add(oldPath + " (empty)");
                    continue;
                }

                CardRecord card = parseAsCard(content, oldPath);
                if (card == null) {
                    failed.add(oldPath + " (not a card file)");
                    continue;
                }

                // 写入新位置
                fileStorage.write(pathInCardsDir(card), content);
                migrated.add(oldPath + " → " + pathInCardsDir(card));
                log.info("卡片迁移成功 | old={} | new={}", oldPath, pathInCardsDir(card));
            } catch (Exception e) {
                failed.add(oldPath + " (" + e.getMessage() + ")");
                log.warn("卡片迁移失败 | old={} | error={}", oldPath, e.getMessage());
            }
        }

        return new MigrationResult(cardFiles.size(), migrated.size(), failed.size(), migrated, failed);
    }

    /**
     * 将旧文件解析为 CardRecord（验证它确实是卡片文件）。
     */
    private CardRecord parseAsCard(String content, String oldPath) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            // 没有 frontmatter 的跳过
            return null;
        }

        String frontmatter = matcher.group(1);
        String body = matcher.group(2).strip();
        Map<String, String> fields = parseFrontmatter(frontmatter);

        String type = fields.getOrDefault("type", "");

        // 只有 conversation 类型或 body 含 ## 时间标记的才视为卡片
        if (!"conversation".equals(type)) {
            // 尝试从 body 判断：包含 ## HH:mm 格式的视为卡片
            if (!body.contains("\n## ") && !body.startsWith("## ")) {
                return null;
            }
        }

        // 生成新 ID：从文件名提取旧 ID，加上 card_ 前缀
        String oldId = fields.getOrDefault("id", "unknown");
        String cardId = oldId.startsWith("card_") ? oldId : "card_" + oldId;

        String status = fields.getOrDefault("status", "idle");
        List<String> tags = parseTags(fields.getOrDefault("tags", ""));
        LocalDateTime createdAt = parseDateTime(fields.get("createdAt"));
        LocalDateTime updatedAt = parseDateTime(fields.get("updatedAt"));
        String summary = fields.getOrDefault("summary", null);

        List<Turn> turns = parseTurns(body);

        return new CardRecord(cardId, "conversation", status, tags, turns, summary, createdAt, updatedAt);
    }

    private String pathInCardsDir(CardRecord card) {
        String datePath = card.createdAt().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return "records/cards/" + datePath + "/" + card.id() + ".md";
    }

    private List<Turn> parseTurns(String body) {
        List<Turn> turns = new ArrayList<>();
        String[] lines = body.split("\n");
        String currentTime = "";
        for (String line : lines) {
            line = line.trim();
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
                fields.put(line.substring(0, colonIdx).trim(),
                        line.substring(colonIdx + 1).trim());
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

    public record MigrationResult(
            int totalScanned,
            int migrated,
            int failed,
            List<String> migratedFiles,
            List<String> failedFiles
    ) {}
}
