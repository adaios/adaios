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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FeedAppService {

    private static final Logger log = LoggerFactory.getLogger(FeedAppService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final RecordRepository recordRepository;
    private final MemoryService memoryService;
    private final BriefAppService briefAppService;
    private final CardFileRepository cardRepository;

    public FeedAppService(RecordRepository recordRepository,
                          MemoryService memoryService,
                          BriefAppService briefAppService,
                          CardFileRepository cardRepository) {
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.briefAppService = briefAppService;
        this.cardRepository = cardRepository;
    }

    public Feed getFeed(LocalDate date, LocalDateTime since) {
        String brief = briefAppService.getCachedBrief();

        // 计算更早天数的条目数
        long earlierCount = recordRepository.findAll().stream()
                .filter(r -> r.createdAt().toLocalDate().isBefore(date))
                .count();

        List<ContentRecord> allRecords = recordRepository.findAll().stream()
                .filter(r -> r.createdAt().toLocalDate().equals(date))
                .toList();
        List<Memory> allMemories = memoryService.findByDate(date);

        List<CardRecord> todayCards = cardRepository.findTodayCards(date);
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

        List<FeedEntry> currentEntries = new ArrayList<>();

        for (CardRecord card : todayCards) {
            currentEntries.add(toCardFeedEntry(card));
        }

        for (ContentRecord r : allRecords) {
            if (skipRecordIds.contains(r.id())) continue;
            if ("conversation".equals(r.type()) || "ai_summary".equals(r.source())) continue;
            currentEntries.add(toFeedEntry(r));
            memoriesFor(allMemories, r.id()).ifPresent(m -> currentEntries.add(toAiEntry(m)));
        }

        currentEntries.sort(Comparator.comparing(e -> e.time));

        log.info("Feed 组合 | date={} | 当前会话={}条 | 卡片={}张",
                date, currentEntries.size(), todayCards.size());
        return new Feed(brief, currentEntries, (int) earlierCount);
    }

    public Feed getFeed(LocalDate date) {
        return getFeed(date, null);
    }

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

    public record Feed(String brief, List<FeedEntry> entries, int earlierCount) {}

    public record FeedEntry(
            String type, String id, String sourceRecordId,
            String title, String content, List<String> tags,
            String time, String intent, String summary,
            List<TurnDto> turns, String domain
    ) {}

    public record TurnDto(boolean isUser, String text, String time) {}
}
