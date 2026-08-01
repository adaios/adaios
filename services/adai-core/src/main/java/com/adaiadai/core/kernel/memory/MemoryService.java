package com.adaiadai.core.kernel.memory;

import com.adaiadai.core.infrastructure.storage.FileStorage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

        // Phase 2：主题合并——新记忆与近 30 天记忆 tags 重叠，旧版本标 superseded 建立演变链
        Memory toPersist = memory;
        if (!isDegraded) {
            Optional<Memory> match = findTopicMatch(memory);
            if (match.isPresent()) {
                Memory prev = match.get();
                String topicId = (prev.topic() != null && !prev.topic().isBlank()) ? prev.topic() : prev.id();
                markSuperseded(prev, memory.id());
                toPersist = withTopic(memory, topicId);
                log.info("记忆主题进化：{} superseded → {} | topic={}", prev.id(), memory.id(), topicId);
            }
        }

        String path = memoryFilePath(toPersist);
        String entry = formatMemoryEntry(toPersist);

        String existing = fileStorage.read(path);
        String content;
        if (existing != null && !existing.isBlank()) {
            content = existing + "\n" + entry;
        } else {
            content = """
                    # 记忆 - %s

                    %s
                    """.formatted(toPersist.createdAt().toLocalDate().toString(), entry);
        }
        fileStorage.write(path, content);
        if (toPersist.patterns() != null && !toPersist.patterns().isEmpty()) {
            log.info("记忆已沉淀 | recordId={} | summary={} | patterns={}",
                    toPersist.recordId(), truncate(toPersist.summary(), 40), toPersist.patterns().size());
        } else {
            log.info("记忆已沉淀 | recordId={} | summary={}", toPersist.recordId(), truncate(toPersist.summary(), 40));
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
        Map<String, Double> bestScore = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            for (Memory m : findByDate(date)) {
                double decay = timeDecay(m);
                if (m.patterns() != null) {
                    for (MemoryPattern p : m.patterns()) {
                        // 时效衰减：置信度 × 时间衰减，旧记忆不再平权参与
                        double score = p.confidence() * decay;
                        if (score > bestScore.getOrDefault(p.content(), 0.0)) {
                            bestScore.put(p.content(), score);
                            merged.put(p.content(), p);
                        }
                    }
                }
            }
        }
        return merged.values().stream()
                .sorted((a, b) -> Double.compare(
                        bestScore.getOrDefault(b.content(), 0.0),
                        bestScore.getOrDefault(a.content(), 0.0)))
                .collect(Collectors.toList());
    }

    /**
     * 查询所有 memory 中出现的 preferences（去重，按置信度降序）。
     */
    public List<MemoryPreference> findAllPreferences() {
        Map<String, MemoryPreference> merged = new LinkedHashMap<>();
        Map<String, Double> bestScore = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            for (Memory m : findByDate(date)) {
                double decay = timeDecay(m);
                if (m.preferences() != null) {
                    for (MemoryPreference p : m.preferences()) {
                        // 时效衰减：置信度 × 时间衰减，旧偏好不再平权误导当前决策
                        double score = p.confidence() * decay;
                        if (score > bestScore.getOrDefault(p.content(), 0.0)) {
                            bestScore.put(p.content(), score);
                            merged.put(p.content(), p);
                        }
                    }
                }
            }
        }
        return merged.values().stream()
                .sorted((a, b) -> Double.compare(
                        bestScore.getOrDefault(b.content(), 0.0),
                        bestScore.getOrDefault(a.content(), 0.0)))
                .collect(Collectors.toList());
    }

    /**
     * 是否已有 AI 洞察记忆（非降级原文）。重补逻辑用它判断是否需重新理解。
     */
    public boolean hasRealMemory(String recordId) {
        Optional<Memory> memory = findByRecordId(recordId);
        return memory.isPresent() && !Memory.isDegraded(memory.get());
    }

    /**
     * 按记忆类型查询（记忆进化 Phase 1）。
     * <p>
     * 遍历最近 30 天记忆，过滤 kind 匹配的条目。
     * 供分类召回使用（如偏好类偏好、模式类模式）。
     */
    public List<Memory> findByKind(String kind) {
        List<Memory> result = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            for (Memory m : findByDate(date)) {
                if (kind.equals(m.kind())) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    /**
     * 获取最近指定天数的活跃记忆（排除 superseded，记忆进化 Phase 2）。
     * <p>
     * Context Engine 回读用它，只取各主题最新未取代版本（演变链保留在文件中）。
     */
    public List<Memory> recentActive(int days) {
        return recent(days).stream()
                .filter(m -> !m.superseded())
                .collect(Collectors.toList());
    }

    // ── 记忆进化 Phase 2：主题级合并 ──

    /**
     * 主题匹配（MVP）：新记忆与近 30 天记忆做 tags 重叠匹配（≥1 重叠标签 → 候选），
     * 取创建时间最新的未 superseded 候选作为当前主题版本。
     */
    private Optional<Memory> findTopicMatch(Memory memory) {
        if (memory.tags() == null || memory.tags().isEmpty()) return Optional.empty();
        Memory latest = null;
        for (int i = 0; i < 30; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            for (Memory m : findByDate(date)) {
                // 新记忆尚未写入文件；用 recordId 区分同记录（id 毫秒精度可能碰撞，不作为匹配依据）
                if (m.superseded()) continue;
                if (m.recordId().equals(memory.recordId())) continue;
                if (m.tags() != null && hasOverlap(m.tags(), memory.tags())) {
                    if (latest == null || m.createdAt().isAfter(latest.createdAt())) {
                        latest = m;
                    }
                }
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * 标记旧版本记忆为 superseded，evolvedTo 指向新版本（建立演变链）。
     */
    private void markSuperseded(Memory prev, String evolvedTo) {
        Memory updated = new Memory(prev.id(), prev.recordId(), prev.kind(), prev.summary(),
                prev.patterns(), prev.preferences(), prev.tags(), prev.sentiment(),
                prev.actionable(), prev.suggestion(), prev.createdAt(),
                prev.topic(), true, evolvedTo, prev.doneAt(), prev.lastConfirmed());
        replaceEntry(prev.createdAt().toLocalDate(), prev.recordId(), updated);
    }

    private Memory withTopic(Memory memory, String topicId) {
        return new Memory(memory.id(), memory.recordId(), memory.kind(), memory.summary(),
                memory.patterns(), memory.preferences(), memory.tags(), memory.sentiment(),
                memory.actionable(), memory.suggestion(), memory.createdAt(),
                topicId, false, null, memory.doneAt(), memory.lastConfirmed());
    }

    /**
     * 重写指定 recordId 的记忆条目（用于 superseded 标记 / 行动完成标记）。
     */
    private void replaceEntry(LocalDate date, String recordId, Memory updated) {
        String path = MEMORY_DIR + "/" + date.format(DateTimeFormatter.ofPattern("yyyy/MM")) + ".md";
        String content = fileStorage.read(path);
        if (content == null || content.isBlank()) return;

        String newEntry = formatMemoryEntry(updated);
        Matcher matcher = ENTRY_SPLIT.matcher(content);
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        while (matcher.find()) {
            String entry = matcher.group();
            if (entry.contains("recordId: " + recordId)) {
                sb.append(newEntry).append("\n");
                replaced = true;
            } else {
                sb.append(entry).append("\n");
            }
        }
        if (replaced) {
            fileStorage.write(path, sb.toString().strip());
        }
    }

    /**
     * 标记行动类记忆为已完成（记忆进化 Phase 3）。
     * <p>
     * actionable=false + doneAt=now，保留 suggestion（行动记录可追溯）。
     * 完成后的记忆不再出现在"待行动事项"/Feed 待办提醒。
     */
    public boolean markDone(String memoryId) {
        for (int i = 0; i < 30; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            for (Memory m : findByDate(date)) {
                if (m.id().equals(memoryId)) {
                    Memory updated = new Memory(m.id(), m.recordId(), m.kind(), m.summary(),
                            m.patterns(), m.preferences(), m.tags(), m.sentiment(),
                            false, m.suggestion(), m.createdAt(),
                            m.topic(), m.superseded(), m.evolvedTo(), LocalDateTime.now(), m.lastConfirmed());
                    replaceEntry(date, m.recordId(), updated);
                    log.info("行动标记完成 | memoryId={} | summary={}", memoryId, truncate(m.summary(), 40));
                    return true;
                }
            }
        }
        log.warn("行动记忆未找到 | memoryId={}", memoryId);
        return false;
    }

    /**
     * 查询未完成的行动记忆（actionable=true 且 doneAt=null，近 30 天）。
     * <p>
     * Feed 待办提醒 + Context 待行动事项共用（记忆进化 Phase 3）。
     */
    public List<Memory> findPendingActions() {
        return recentActive(30).stream()
                .filter(m -> m.actionable() && m.doneAt() == null)
                .collect(Collectors.toList());
    }

    // ── 记忆进化 Phase 4：时效与淘汰 ──

    /**
     * 回读确认：更新近期记忆的 lastConfirmed 为当前时间（每天每记忆最多 touch 一次）。
     * <p>
     * Context Engine 回读时调用，让"经常回读的记忆"保持时效权重。
     */
    public void touchActive() {
        LocalDateTime now = LocalDateTime.now();
        for (Memory m : recentActive(30)) {
            LocalDateTime ref = m.lastConfirmed() != null ? m.lastConfirmed() : m.createdAt();
            if (Duration.between(ref, now).toDays() < 1) continue;
            Memory updated = new Memory(m.id(), m.recordId(), m.kind(), m.summary(),
                    m.patterns(), m.preferences(), m.tags(), m.sentiment(),
                    m.actionable(), m.suggestion(), m.createdAt(),
                    m.topic(), m.superseded(), m.evolvedTo(), m.doneAt(), now);
            replaceEntry(m.createdAt().toLocalDate(), m.recordId(), updated);
        }
    }

    /**
     * 清理过期条目（随 rebuild 触发）：superseded 超 60 天、actionable 完成超 30 天。
     */
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        int removed = 0;
        for (int i = 0; i < 365; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            for (Memory m : findByDate(date)) {
                boolean supersededOld = m.superseded()
                        && Duration.between(m.createdAt(), now).toDays() > 60;
                boolean doneOld = m.doneAt() != null
                        && Duration.between(m.doneAt(), now).toDays() > 30;
                if (supersededOld || doneOld) {
                    removeFromFile(date, m.recordId());
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("记忆清理完成 | 移除过期条目 {} 条", removed);
        }
    }

    /**
     * 时效衰减因子：0.95^天数（基于 lastConfirmed，未确认时回退 createdAt）。
     */
    private double timeDecay(Memory m) {
        LocalDateTime ref = m.lastConfirmed() != null ? m.lastConfirmed() : m.createdAt();
        long days = Math.max(0, Duration.between(ref, LocalDateTime.now()).toDays());
        return Math.pow(0.95, days);
    }

    private boolean hasOverlap(List<String> a, List<String> b) {
        for (String s : a) {
            if (b.contains(s)) return true;
        }
        return false;
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
                kind: %s
                topic: %s
                superseded: %b
                evolvedTo: %s
                doneAt: %s
                lastConfirmed: %s
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
                memory.kind(),
                memory.topic() != null ? memory.topic() : "",
                memory.superseded(),
                memory.evolvedTo() != null ? memory.evolvedTo() : "",
                memory.doneAt() != null ? memory.doneAt().toString() : "",
                memory.lastConfirmed() != null ? memory.lastConfirmed().toString() : "",
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
                // 旧条目无 kind 字段 → 默认 insight（记忆进化 Phase 1 向后兼容）
                String kind = fields.getOrDefault("kind", Memory.KIND_INSIGHT);
                String sentiment = fields.getOrDefault("sentiment", "neutral");
                // Phase 2：topic/superseded/evolvedTo（旧条目默认无主题、未取代）
                String topic = fields.getOrDefault("topic", null);
                if ("".equals(topic) || "null".equals(topic)) topic = null;
                boolean superseded = Boolean.parseBoolean(fields.getOrDefault("superseded", "false"));
                String evolvedTo = fields.getOrDefault("evolvedTo", null);
                if ("".equals(evolvedTo) || "null".equals(evolvedTo)) evolvedTo = null;
                // Phase 3：doneAt（行动完成时间，默认 null）
                LocalDateTime doneAt = null;
                String doneAtStr = fields.getOrDefault("doneAt", null);
                if (doneAtStr != null && !doneAtStr.isBlank() && !"null".equals(doneAtStr)) {
                    try {
                        doneAt = LocalDateTime.parse(doneAtStr);
                    } catch (Exception e) {
                        doneAt = null;
                    }
                }
                // Phase 4：lastConfirmed（最近回读/确认时间，默认 null → 衰减回退 createdAt）
                LocalDateTime lastConfirmed = null;
                String lcStr = fields.getOrDefault("lastConfirmed", null);
                if (lcStr != null && !lcStr.isBlank() && !"null".equals(lcStr)) {
                    try {
                        lastConfirmed = LocalDateTime.parse(lcStr);
                    } catch (Exception e) {
                        lastConfirmed = null;
                    }
                }
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

                result.add(new Memory(id, recordId, kind, body, patterns, preferences,
                        tags, sentiment, actionable, suggestion, createdAt, topic, superseded, evolvedTo, doneAt, lastConfirmed));
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
