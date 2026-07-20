package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.record.ContentRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TagIndexService — 标签索引服务。
 * <p>
 * 维护 {@code data/index/tags.json}，按标签索引所有记录。
 * Context Engine 通过标签检索关联记录，替代时间窗口切分。
 * <p>
 * 索引文件可以从 records/ 目录全量重建，不是 Source of Truth。
 * <p>
 * 只依赖 FileStorage，不依赖 RecordRepository（避免循环依赖）。
 */
@Service
public class TagIndexService {

    private static final Logger log = LoggerFactory.getLogger(TagIndexService.class);

    private static final String INDEX_PATH = "index/tags.json";
    private static final int MAX_RECORDS_PER_TAG = 50;

    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    private TagIndex cachedIndex;

    public TagIndexService(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        this.objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.cachedIndex = null;
    }

    /**
     * 记录保存后调用：更新标签索引。
     */
    public void onRecordSaved(ContentRecord record) {
        if (record.tags() == null || record.tags().isEmpty()) {
            return;
        }
        TagIndex index = load();
        boolean changed = false;

        for (String tag : record.tags()) {
            TagEntry entry = index.tags().get(tag);
            if (entry == null) {
                entry = new TagEntry(0, new ArrayList<>(), record.createdAt(), record.createdAt());
                index.tags().put(tag, entry);
            }

            // 去重添加
            if (!entry.recordIds().contains(record.id())) {
                List<String> ids = new ArrayList<>(entry.recordIds());
                ids.add(record.id());
                // 保持最新的在前
                ids.sort(Collections.reverseOrder());

                // 限制单标签最大记录数
                if (ids.size() > MAX_RECORDS_PER_TAG) {
                    ids = ids.subList(0, MAX_RECORDS_PER_TAG);
                }

                entry = new TagEntry(
                        entry.count() + 1,
                        ids,
                        entry.firstAt(),
                        record.createdAt()
                );
                index.tags().put(tag, entry);
                changed = true;
            }
        }

        if (changed) {
            save(new TagIndex(index.tags(), LocalDateTime.now()));
            cachedIndex = null;
            log.debug("标签索引已更新 | tags={}", record.tags());
        }
    }

    /**
     * 根据标签列表查找关联记录 ID（并集，保持标签索引中的顺序）。
     */
    public List<String> findRelatedIds(List<String> tags, int maxResults) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        TagIndex index = load();

        // 取所有匹配标签的 recordId 并集
        Set<String> matchedIds = new LinkedHashSet<>();
        for (String tag : tags) {
            TagEntry entry = index.tags().get(tag);
            if (entry != null) {
                matchedIds.addAll(entry.recordIds());
            }
        }

        if (matchedIds.isEmpty()) {
            return List.of();
        }

        return matchedIds.stream()
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 从所有记录 ID 列表重建索引（在应用启动时调用）。
     */
    public void rebuild(List<ContentRecord> allRecords) {
        log.info("开始全量重建标签索引...");

        Map<String, TagEntry> tags = new LinkedHashMap<>();
        for (ContentRecord record : allRecords) {
            if (record.tags() == null) continue;
            for (String tag : record.tags()) {
                TagEntry existing = tags.get(tag);
                List<String> ids = existing != null
                        ? new ArrayList<>(existing.recordIds())
                        : new ArrayList<>();
                if (!ids.contains(record.id())) {
                    ids.add(record.id());
                }
                tags.put(tag, new TagEntry(
                        existing != null ? existing.count() + 1 : 1,
                        ids,
                        existing != null ? existing.firstAt() : record.createdAt(),
                        record.createdAt()
                ));
            }
        }

        save(new TagIndex(tags, LocalDateTime.now()));
        cachedIndex = null;
        log.info("标签索引重建完成 | tags={}", tags.size());
    }

    // ── 内部 ──

    private TagIndex load() {
        if (cachedIndex != null) {
            return cachedIndex;
        }

        String content = fileStorage.read(INDEX_PATH);
        if (content == null || content.isBlank()) {
            cachedIndex = new TagIndex(new LinkedHashMap<>(), LocalDateTime.now());
            return cachedIndex;
        }

        try {
            cachedIndex = objectMapper.readValue(content, TagIndex.class);
            return cachedIndex;
        } catch (IOException e) {
            log.warn("标签索引文件解析失败: {}", e.getMessage());
            cachedIndex = new TagIndex(new LinkedHashMap<>(), LocalDateTime.now());
            return cachedIndex;
        }
    }

    private void save(TagIndex index) {
        try {
            String content = objectMapper.writeValueAsString(index);
            fileStorage.write(INDEX_PATH, content);
        } catch (IOException e) {
            throw new UncheckedIOException("标签索引写入失败", e);
        }
    }

    // ── 内部数据模型 ──

    public record TagEntry(
            int count,
            List<String> recordIds,
            LocalDateTime firstAt,
            LocalDateTime lastAt
    ) {}

    public record TagIndex(
            Map<String, TagEntry> tags,
            LocalDateTime updatedAt
    ) {}
}
