package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.rhythm.Rhythm;
import com.adaiadai.core.kernel.rhythm.RhythmRepository;
import com.adaiadai.core.kernel.rhythm.RhythmStatus;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RhythmFileRepository — 节律文件仓储（File First，RFC 20260923 B 批）。
 * <p>
 * 存储形态与 {@code TodoFileRepository} 同构：{@code data/{userId}/rhythm/YYYY/MM.md}，
 * 每条一个 {@code ---} 围栏块 + 一行正文（正文即 title，便于人手读）。
 * <pre>
 * # 节律 - 2026/09
 *
 * ---
 * id: rhy_20260923_231500123
 * title: 周四固定发版加班
 * recurrence: FREQ=WEEKLY;BYDAY=TH
 * status: ACTIVE
 * sourceRecordId: rec_20260917_193216462
 * validFrom: 2026-09-23
 * validUntil:
 * createdAt: 2026-09-23
 * updatedAt: 2026-09-23
 * ---
 * 周四固定发版加班
 * </pre>
 * 兼容性：{@code sourceRecordId} / {@code validUntil} 为可选行（手写或历史产物缺行不炸）；
 * 未知 status 值读作 {@link RhythmStatus#ACTIVE} 之外的保守值（见 {@code parseStatus}）。
 */
@Repository
public class RhythmFileRepository implements RhythmRepository {

    private static final Logger log = LoggerFactory.getLogger(RhythmFileRepository.class);

    private static final String RHYTHM_DIR = "rhythm";
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "---\\n" +
                    // 冒号后空白用 [ \t]*（\s 含换行会贪婪吞掉下一行，见 TodoFileRepository 同款注释）
                    "id:[ \\t]*(\\S+)\\n" +
                    "title:[ \\t]*([^\\n]*)\\n" +
                    "recurrence:[ \\t]*([^\\n]*)\\n" +
                    "status:[ \\t]*(\\S+)\\n" +
                    "(?:sourceRecordId:[ \\t]*([^\\n]*)\\n)?" +
                    "validFrom:[ \\t]*(\\S+)\\n" +
                    "(?:validUntil:[ \\t]*([^\\n]*)\\n)?" +
                    "createdAt:[ \\t]*(\\S+)\\n" +
                    "updatedAt:[ \\t]*(\\S+)\\n" +
                    "---\\n" +
                    ".+?(?=\\n---|\\z)",
            Pattern.DOTALL);

    private final FileStorage fileStorage;

    public RhythmFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public List<Rhythm> findAll(RhythmStatus status, String userId) {
        return findAll(userId).stream()
                .filter(r -> status == null || r.status() == status)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<Rhythm> findAll(String userId) {
        List<Rhythm> all = new ArrayList<>();
        for (String path : fileStorage.listFiles(userId, RHYTHM_DIR)) {
            if (!path.endsWith(".md")) continue;
            String content = fileStorage.read(userId, path);
            if (content != null && !content.isBlank()) {
                all.addAll(parseEntries(content));
            }
        }
        return all;
    }

    @Override
    public Optional<Rhythm> findById(String userId, String id) {
        return findAll(userId).stream()
                .filter(r -> r.id().equals(id))
                .findFirst();
    }

    @Override
    public synchronized void save(String userId, Rhythm rhythm) {
        String path = rhythmFilePath(rhythm.createdAt());
        String entry = formatEntry(rhythm);

        String existing = fileStorage.read(userId, path);
        if (existing != null && !existing.isBlank()) {
            List<Rhythm> rhythms = parseEntries(existing);
            boolean replaced = false;
            StringBuilder sb = new StringBuilder();
            sb.append(extractHeader(existing)).append("\n\n");
            for (Rhythm r : rhythms) {
                if (r.id().equals(rhythm.id())) {
                    sb.append(entry).append("\n");
                    replaced = true;
                } else {
                    sb.append(formatEntry(r)).append("\n");
                }
            }
            if (!replaced) {
                sb.append(entry).append("\n");
            }
            fileStorage.write(userId, path, sb.toString());
        } else {
            fileStorage.write(userId, path, """
                    # 节律 - %s

                    %s
                    """.formatted(monthLabel(rhythm.createdAt()), entry));
        }
        log.info("节律已保存 | id={} | title=\"{}\" | recurrence={} | status={}",
                rhythm.id(), rhythm.title(), rhythm.recurrence(), rhythm.status());
    }

    @Override
    public synchronized void delete(String userId, String id) { // 与 save 同锁，防并发 save+delete 互覆
        Rhythm rhythm = findById(userId, id).orElse(null);
        if (rhythm == null) {
            log.warn("节律删除失败：未找到 | id={}", id);
            return;
        }
        String path = rhythmFilePath(rhythm.createdAt());
        String existing = fileStorage.read(userId, path);
        if (existing == null || existing.isBlank()) return;

        StringBuilder sb = new StringBuilder();
        sb.append(extractHeader(existing)).append("\n\n");
        for (Rhythm r : parseEntries(existing)) {
            if (!r.id().equals(id)) {
                sb.append(formatEntry(r)).append("\n");
            }
        }
        fileStorage.write(userId, path, sb.toString());
        log.info("节律已删除 | id={}", id);
    }

    // ── 内部方法 ──

    private String rhythmFilePath(LocalDate date) {
        return RHYTHM_DIR + "/" + date.format(MONTH_FORMATTER) + ".md";
    }

    private String monthLabel(LocalDate date) {
        return date.format(MONTH_FORMATTER);
    }

    private String formatEntry(Rhythm rhythm) {
        String title = singleLine(rhythm.title());
        return """
                ---
                id: %s
                title: %s
                recurrence: %s
                status: %s
                sourceRecordId: %s
                validFrom: %s
                validUntil: %s
                createdAt: %s
                updatedAt: %s
                ---
                %s
                """.strip().formatted(
                rhythm.id(),
                title,
                rhythm.recurrence() != null ? singleLine(rhythm.recurrence()) : "",
                rhythm.status() != null ? rhythm.status().name() : RhythmStatus.ACTIVE.name(),
                rhythm.sourceRecordId() != null ? rhythm.sourceRecordId() : "",
                rhythm.validFrom() != null ? rhythm.validFrom().toString() : "",
                rhythm.validUntil() != null ? rhythm.validUntil().toString() : "",
                rhythm.createdAt() != null ? rhythm.createdAt().toString() : "",
                rhythm.updatedAt() != null ? rhythm.updatedAt().toString() : "",
                title
        );
    }

    /** 字段值单行化：换行/回车替换为空格，连续空格压缩（防多行 title 写坏文件）。 */
    private String singleLine(String s) {
        if (s == null || s.isBlank()) return "";
        return s.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").strip();
    }

    /**
     * 提取文件头：第一行 # 标题 + 其后到第一个条目（---）之间的手写注释。
     * 防止 save/delete 重建文件时丢弃用户手动添加的说明文字。
     */
    private String extractHeader(String content) {
        int idx = content.indexOf("\n---");
        if (idx <= 0) {
            return content.lines().findFirst().orElse("");
        }
        return content.substring(0, idx).strip();
    }

    private List<Rhythm> parseEntries(String content) {
        List<Rhythm> result = new ArrayList<>();
        Matcher matcher = ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            try {
                String id = matcher.group(1);
                String title = matcher.group(2).strip();
                String recurrence = matcher.group(3).strip();
                RhythmStatus status = parseStatus(matcher.group(4));
                String sourceRecordId = matcher.group(5);
                LocalDate validFrom = LocalDate.parse(matcher.group(6));
                LocalDate validUntil = parseDate(matcher.group(7));
                LocalDate createdAt = LocalDate.parse(matcher.group(8));
                LocalDate updatedAt = LocalDate.parse(matcher.group(9));

                result.add(new Rhythm(id, title, recurrence, status,
                        sourceRecordId != null && !sourceRecordId.isBlank() ? sourceRecordId.strip() : null,
                        validFrom, validUntil, createdAt, updatedAt));
            } catch (Exception e) {
                log.warn("解析节律条目失败: {}", e.getMessage());
            }
        }
        return result;
    }

    /** 未知状态值保守读作 PAUSED（比 ACTIVE 安全：宁可少注入，也不凭空多提醒）。 */
    private RhythmStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return RhythmStatus.ACTIVE;
        try {
            return RhythmStatus.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("节律状态值不认识，保守读作 PAUSED | status={}", raw);
            return RhythmStatus.PAUSED;
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.strip());
        } catch (Exception e) {
            log.warn("节律日期解析失败，按空处理 | value={}", raw);
            return null;
        }
    }
}
