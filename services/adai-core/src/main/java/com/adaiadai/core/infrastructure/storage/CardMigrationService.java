package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.storage.FileStorage;
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
import java.util.stream.Collectors;

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
    public MigrationResult migrate(String userId) {
        List<String> allFiles = fileStorage.listFiles(userId, "records");
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
                String content = fileStorage.read(userId, oldPath);
                if (content == null || content.isBlank()) {
                    failed.add(oldPath + " (empty)");
                    continue;
                }

                CardRecord card = parseAsCard(content, oldPath);
                if (card == null) {
                    failed.add(oldPath + " (not a card file)");
                    continue;
                }

                // 写入新位置（frontmatter id 同步改写为 card_ 前缀，避免与新文件名不一致
                // → 否则 findAll 解析出的 id 无前缀，save() 按 id 写回时落到旧路径，产生同 id 双文件）
                String migratedContent = rewriteIdInFrontmatter(content, card.id());
                String newPath = pathInCardsDir(card);
                fileStorage.write(userId, newPath, migratedContent);
                // 迁移即移动：成功后删除旧文件，防止同 id 双文件（findAll 去重的根源数据）
                if (!oldPath.equals(newPath)) {
                    fileStorage.delete(userId, oldPath);
                }
                migrated.add(oldPath + " → " + newPath);
                log.info("卡片迁移成功 | old={} | new={}", oldPath, newPath);
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

        // #216 判定收紧：conversation 类型，或 body 含「用户：」对话标记（卡片必有对话轮）才视为卡片。
        // 原逻辑「body 含 ## 时间标记即视为卡片」太宽——普通带 markdown 标题的笔记会被误当卡片
        // 迁移并删原文件（误判即删）。「用户：」是卡片 turns 的必要结构，比 ## 更可靠。
        if (!"conversation".equals(type)) {
            if (!body.contains("用户：")) {
                return null;
            }
        }

        // #216：无 id 字段的文件跳过（原并入 card_unknown → findAll 去重合并为一条，数据淹没）。
        // 缺 id 说明文件格式不完整，不迁移（保留原文件，人工处理）。
        String oldId = fields.get("id");
        if (oldId == null || oldId.isBlank()) {
            log.warn("卡片迁移跳过：缺 id 字段 | file={}", oldPath);
            return null;
        }
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

    /**
     * 将 frontmatter 中的 id 字段改写为新 id（card_ 前缀），保持内容与新文件名一致。
     * 旧代码迁移时原样复制内容（id 仍无前缀），导致 findAll 解析出的 id 与文件名不符，
     * save() 按 id 写回时落到旧路径 → 同 id 双文件（死循环数据根源）。
     * <p>#217：只在 frontmatter 段（首对 {@code ---} 之间）替换 id 行，不再用全文件
     * {@code ^id:} 正则——body 中出现 {@code id:} 行会被误改（frontmatter 保留旧 id → 双文件复发）。
     */
    private String rewriteIdInFrontmatter(String content, String newId) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        String frontmatter = matcher.group(1);
        String newFrontmatter = frontmatter.replaceAll("(?m)^id:\\s*.*$", "id: " + newId);
        if (newFrontmatter.equals(frontmatter)) {
            // frontmatter 无 id 行：追加（与 parseAsCard 的 id 兜底保持一致）
            newFrontmatter = frontmatter + "\nid: " + newId;
        }
        // 重拼：---\n{frontmatter}\n---\n{body}（body 从 group(2) 取，避免 end(1) 起点的分隔符错位）
        return "---\n" + newFrontmatter + "\n---\n" + matcher.group(2);
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

    /**
     * 清理旧的数据：找到卡片对话对应的重复 ContentRecord 并删除。
     * 卡片 turns 里的每一轮对话，之前在旧代码中都被存成了独立的 rec_*.md 文件。
     * 卡片迁移到 records/cards/ 后，这些独立的记录就是冗余的，应该被清理。
     *
     * @return 清理结果统计
     */
    public CleanupResult cleanupDuplicateRecords(String userId) {
        List<CardRecord> allCards = cardRepository.findAll(userId);
        List<String> deleted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int totalRecords = 0;

        for (CardRecord card : allCards) {
            if (card.turns() == null || card.turns().isEmpty()) continue;

            // 收集卡片中所有 user turn 的文本（去重）
            Set<String> turnTexts = card.turns().stream()
                    .filter(t -> t.isUser())
                    .map(t -> t.text().strip())
                    .filter(t -> !t.isBlank())
                    .collect(Collectors.toSet());

            if (turnTexts.isEmpty()) continue;

            String cardDate = card.createdAt().toLocalDate().toString();

            // 扫描 records/ 下匹配的内容
            List<String> allFiles = fileStorage.listFiles(userId, "records");
            for (String f : allFiles) {
                if (!f.startsWith("records/") || f.startsWith("records/cards/")) continue;
                if (!f.endsWith(".md")) continue;
                String fileName = f.substring(f.lastIndexOf('/') + 1);
                if (!fileName.startsWith("rec_")) continue;

                String content = fileStorage.read(userId, f);
                if (content == null || content.isBlank()) continue;

                // 解析 frontmatter 获取 brief content
                String briefContent = extractBriefContent(content);
                if (briefContent == null) continue;

                // 匹配卡片中的用户消息文本
                if (turnTexts.contains(briefContent)) {
                    fileStorage.delete(userId, f);
                    deleted.add(f);
                    totalRecords++;
                }
            }
        }

        log.info("清理完成 | 删除冗余记录={}条", deleted.size());
        return new CleanupResult(deleted.size(), deleted, skipped);
    }

    /**
     * 从文件内容中提取简短的正文（用于匹配卡片 turn）。
     */
    private String extractBriefContent(String fileContent) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(fileContent);
        if (!matcher.find()) return null;
        String body = matcher.group(2).strip();
        // 取第一行作为简略内容
        String firstLine = body.lines().findFirst().orElse("").strip();
        // 去掉 markdown 标题标记
        firstLine = firstLine.replaceAll("^#+\\s*", "").strip();
        if (firstLine.length() > 100) firstLine = firstLine.substring(0, 100);
        return firstLine;
    }

    public record CleanupResult(
            int deleted,
            List<String> deletedFiles,
            List<String> skippedFiles
    ) {}

    public record MigrationResult(
            int totalScanned,
            int migrated,
            int failed,
            List<String> migratedFiles,
            List<String> failedFiles
    ) {}
}
