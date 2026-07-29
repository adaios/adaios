package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FeedAppService — 今日 Feed 流分页查询。
 * <p>
 * 只查询指定日期的数据（默认今天），通过 page/size 分页返回。
 * 历史数据走时间线入口，不通过 feed 加载。
 */
@Service
public class FeedAppService {

    private static final Logger log = LoggerFactory.getLogger(FeedAppService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final RecordRepository recordRepository;
    private final MemoryService memoryService;
    private final CardFileRepository cardRepository;

    public FeedAppService(RecordRepository recordRepository,
                          MemoryService memoryService,
                          BriefAppService briefAppService,
                          CardFileRepository cardRepository) {
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.cardRepository = cardRepository;
    }

    /**
     * 获取指定日期的 Feed，分页返回。
     *
     * @param date 日期，null 则默认今天
     * @param page 页码，从 0 开始
     * @param size 每页条数，默认 5
     */
    public FeedResponse getFeed(LocalDate date, int page, int size) {
        final LocalDate queryDate = date != null ? date : LocalDate.now();
        final int querySize = size <= 0 ? 5 : size;
        final int queryPage = Math.max(page, 0);

        List<ContentRecord> allRecords = recordRepository.findAll().stream()
                .filter(r -> r.createdAt().toLocalDate().equals(queryDate))
                .toList();
        List<Memory> allMemories = memoryService.findByDate(queryDate);

        List<CardRecord> todayCards = cardRepository.findTodayCards(queryDate);
        Map<String, CardRecord> turnToCard = buildTurnCardMap(todayCards);

        Set<String> skipRecordIds = new HashSet<>();
        for (ContentRecord r : allRecords) {
            if (r.content() == null || r.content().isBlank()) continue;
            String matchKey = r.content().strip();
            if (matchKey.length() > 60) matchKey = matchKey.substring(0, 60);
            if (turnToCard.containsKey(matchKey)) {
                skipRecordIds.add(r.id());
            }
        }

        List<FeedEntry> allEntries = new ArrayList<>();

        for (CardRecord card : todayCards) {
            // 只显示当天创建的卡片，跨日卡片跳过
            if (!card.createdAt().toLocalDate().equals(queryDate)) continue;
            allEntries.add(toCardFeedEntry(card));
        }

        for (ContentRecord r : allRecords) {
            if (skipRecordIds.contains(r.id())) continue;
            if ("conversation".equals(r.type()) || "ai_summary".equals(r.source())) continue;
            allEntries.add(toFeedEntry(r));
            memoriesFor(allMemories, r.id()).ifPresent(m -> allEntries.add(toAiEntry(m)));
        }

        allEntries.sort(Comparator.comparing(e -> e.time));
        int totalToday = allEntries.size();

        // 分页：从后往前翻，page 0 = 最新条目（tail），page 1 = 更早
        int totalPages = (totalToday + querySize - 1) / querySize;
        int idxFromEnd = totalPages - 1 - queryPage;
        List<FeedEntry> pageEntries;
        if (idxFromEnd < 0) {
            pageEntries = List.of();
        } else {
            int start = idxFromEnd * querySize;
            int end = Math.min(start + querySize, totalToday);
            pageEntries = allEntries.subList(start, end);
        }

        log.info("Feed 分页 | date={} | 总记录={} | 总条目={} | page={} | size={} | 返回={}条",
                queryDate, totalToday, allEntries.size(), queryPage, querySize, pageEntries.size());
        return new FeedResponse(pageEntries, totalToday);
    }

    public FeedResponse getFeed(LocalDate date) {
        return getFeed(date, 0, 5);
    }

    // ── 内部方法 ──

    private Map<String, CardRecord> buildTurnCardMap(List<CardRecord> cards) {
        Map<String, CardRecord> map = new HashMap<>();
        for (CardRecord card : cards) {
            if (card.turns() == null) continue;
            for (var turn : card.turns()) {
                if (!turn.isUser() || turn.text() == null || turn.text().isBlank()) continue;
                String key = turn.text().strip();
                if (key.length() > 60) key = key.substring(0, 60);
                map.put(key, card);
            }
        }
        return map;
    }

    private FeedEntry toFeedEntry(ContentRecord r) {
        String intent = "conversation".equals(r.type()) ? "question" : "log";
        return new FeedEntry(
                "record", r.id(), null,
                r.title(), r.content(), r.tags(),
                r.createdAt().toLocalTime().format(TIME_FMT),
                intent, r.summary(), null, r.domain()
        );
    }

    private FeedEntry toCardFeedEntry(CardRecord card) {
        List<TurnDto> turns = card.turns() != null
                ? card.turns().stream()
                    .map(t -> new TurnDto(t.isUser(), t.text(), t.time()))
                    .collect(Collectors.toList())
                : List.of();

        String timeStr = card.turns() != null
                ? card.turns().stream()
                    .filter(t -> t.isUser())
                    .findFirst()
                    .map(t -> t.time())
                    .orElse(card.createdAt().toLocalTime().format(TIME_FMT))
                : card.createdAt().toLocalTime().format(TIME_FMT);

        String firstUserMsg = card.turns() != null
                ? card.turns().stream()
                    .filter(t -> t.isUser())
                    .findFirst()
                    .map(t -> t.text())
                    .orElse("")
                : "";

        return new FeedEntry(
                "card", card.id(), null,
                firstUserMsg, firstUserMsg, card.tags(),
                timeStr, "question", card.summary(), turns, "life"
        );
    }

    private FeedEntry toAiEntry(Memory m) {
        return new FeedEntry(
                "ai_note", m.id(), m.recordId(),
                m.summary(), m.summary(), m.tags(),
                m.createdAt().toLocalTime().format(TIME_FMT),
                null, null, null, "life"
        );
    }

    private Optional<Memory> memoriesFor(List<Memory> memories, String recordId) {
        return memories.stream()
                .filter(m -> m.recordId().equals(recordId))
                .findFirst();
    }

    // ── DTO ──

    /** Feed 响应（分页版，不含 brief，brief 单独从 /api/v1/brief 获取） */
    public record FeedResponse(List<FeedEntry> entries, int totalToday) {}

    public record FeedEntry(
            String type, String id, String sourceRecordId,
            String title, String content, List<String> tags,
            String time, String intent, String summary,
            List<TurnDto> turns, String domain
    ) {}

    public record TurnDto(boolean isUser, String text, String time) {}
}
