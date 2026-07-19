package com.adaiadai.core.application;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * FeedAppService — Feed 流组合服务。
 * <p>
 * 支持"每次打开 App 是一个会话"模式。
 * 调用时传入 {@code since} 时间戳，只返回该时间之后的条目，
 * 之前的条目计数为 {@code earlierCount}，App 可展示"展开查看更早记录"。
 */
@Service
public class FeedAppService {

    private static final Logger log = LoggerFactory.getLogger(FeedAppService.class);

    private final RecordRepository recordRepository;
    private final MemoryService memoryService;
    private final BriefAppService briefAppService;

    public FeedAppService(RecordRepository recordRepository,
                          MemoryService memoryService,
                          BriefAppService briefAppService) {
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.briefAppService = briefAppService;
    }

    /**
     * 获取 Feed，支持会话隔离。
     *
     * @param date      日期
     * @param since     可选，此时间之后的条目作为"当前会话"内容
     * @return Feed
     */
    public Feed getFeed(LocalDate date, LocalDateTime since) {
        String brief = briefAppService.generateBrief();
        List<ContentRecord> allRecords = recordRepository.findAll().stream()
                .filter(r -> r.createdAt().toLocalDate().equals(date))
                .toList();
        List<Memory> allMemories = memoryService.findByDate(date);

        List<FeedEntry> currentEntries = new ArrayList<>();
        int earlierCount = 0;

        for (ContentRecord r : allRecords) {
            boolean isCurrent = since == null || r.createdAt().isAfter(since);

            if (isCurrent) {
                currentEntries.add(toFeedEntry(r, null));
                memoriesFor(allMemories, r.id()).ifPresent(m ->
                        currentEntries.add(toAiEntry(m)));
            } else {
                earlierCount++;
                // AI note 也计入 earlier（如果有的话）
                if (memoriesFor(allMemories, r.id()).isPresent()) {
                    earlierCount++;
                }
            }
        }

        currentEntries.sort(Comparator.comparing(FeedEntry::time));

        log.info("Feed 组合 | date={} | 当前会话={}条 | 已收起={}条",
                date, currentEntries.size(), earlierCount);

        return new Feed(brief, currentEntries, earlierCount);
    }

    public Feed getFeed(LocalDate date) {
        return getFeed(date, null);
    }

    // ── 内部方法 ──

    private FeedEntry toFeedEntry(ContentRecord r, Memory m) {
        String intent = "conversation".equals(r.type()) ? "question" : "log";
        return new FeedEntry(
                "record",
                r.id(), null,
                r.title(), r.content(), r.tags(),
                r.createdAt().toLocalTime(),
                intent,
                r.summary()
        );
    }

    private FeedEntry toAiEntry(Memory m) {
        return new FeedEntry(
                "ai_note",
                m.id(), m.recordId(),
                m.summary(), m.summary(), m.tags(),
                m.createdAt().toLocalTime(),
                null,
                null
        );
    }

    private java.util.Optional<Memory> memoriesFor(List<Memory> memories, String recordId) {
        return memories.stream()
                .filter(m -> m.recordId().equals(recordId))
                .findFirst();
    }

    // ── DTO ──

    public record Feed(
            String brief,
            List<FeedEntry> entries,
            int earlierCount
    ) {}

    public record FeedEntry(
            String type,        // record | ai_note | push
            String id,
            String sourceRecordId,
            String title,
            String content,
            List<String> tags,
            LocalTime time,
            String intent,       // "question" | "log" | null
            String summary      // AI-generated summary
    ) {
        public String timeString() {
            return time.format(DateTimeFormatter.ofPattern("HH:mm"));
        }
    }
}
