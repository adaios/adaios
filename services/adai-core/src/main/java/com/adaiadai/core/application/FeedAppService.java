package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.kernel.market.MarketData;
import com.adaiadai.core.kernel.market.MarketDataSource;
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
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final RecordRepository recordRepository;
    private final MemoryService memoryService;
    private final CardFileRepository cardRepository;
    private final MarketDataSource marketDataSource;
    private final MarketPushRepository pushRepository;

    public FeedAppService(RecordRepository recordRepository,
                          MemoryService memoryService,
                          BriefAppService briefAppService,
                          CardFileRepository cardRepository,
                          MarketDataSource marketDataSource,
                          MarketPushRepository pushRepository) {
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.cardRepository = cardRepository;
        this.marketDataSource = marketDataSource;
        this.pushRepository = pushRepository;
    }

    /**
     * 获取指定日期的 Feed，分页返回。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param date   日期，null 则默认今天
     * @param page   页码，从 0 开始
     * @param size   每页条数，默认 5
     */
    public FeedResponse getFeed(String userId, LocalDate date, int page, int size) {
        final LocalDate queryDate = date != null ? date : LocalDate.now();
        final int querySize = size <= 0 ? 5 : size;
        final int queryPage = Math.max(page, 0);

        List<ContentRecord> allRecords = recordRepository.findAll(userId).stream()
                .filter(r -> r.createdAt().toLocalDate().equals(queryDate))
                .toList();
        List<Memory> allMemories = memoryService.findByDate(userId, queryDate);

        List<CardRecord> todayCards = cardRepository.findTodayCards(userId, queryDate);
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

        // 跨日记忆补齐（REVIEW #148）：重补/升级会把记忆沉淀到处理当天的文件（Memory.createdAt=now），
        // 同日 findByDate 查不到 → ai_note 归属错日/丢失。按记录补一次批量查询。
        Map<String, Memory> crossDayMemories = findCrossDayMemories(userId, allRecords, skipRecordIds, allMemories);

        List<FeedEntry> allEntries = new ArrayList<>();

        for (CardRecord card : todayCards) {
            // 只显示当天创建的卡片，跨日卡片跳过
            if (!card.createdAt().toLocalDate().equals(queryDate)) continue;
            allEntries.add(toCardFeedEntry(card));
        }

        for (ContentRecord r : allRecords) {
            if (skipRecordIds.contains(r.id())) continue;
            if ("conversation".equals(r.type()) || "ai_summary".equals(r.source())) continue;
            allEntries.add(toFeedEntry(userId, r));
            Memory memory = memoriesFor(allMemories, r.id())
                    .orElseGet(() -> crossDayMemories.get(r.id()));
            if (memory != null) {
                allEntries.add(toAiEntry(memory, r));
            }
        }

        // 记忆进化 Phase 3：未完成行动提醒（actionable 记忆）——按记忆创建时间参与排序
        for (Memory m : memoryService.findPendingActions(userId)) {
            allEntries.add(toActionEntry(m));
        }

        // v0.2.0 L5 行情嵌入：大盘指数行情条（MarketDataSource 60s 缓存，网络失败返回空）
        allEntries.addAll(buildMarketEntries());

        // Phase 2 主动推送：当日持仓异动推送（MarketAlertService 定时落盘，按日读取）
        allEntries.addAll(buildPushEntries(userId, queryDate));

        allEntries.sort(Comparator.comparing(e -> e.time));
        // 核心条目（record/card）分页；附加条目（ai_note/action/market）只在最新页返回。
        // totalToday = 核心输入数（前端过滤 aiNote 渲染，若含附加条目 → load more 永不收敛 + 空态误判，REVIEW #61）
        List<FeedEntry> coreEntries = allEntries.stream()
                .filter(e -> "record".equals(e.type()) || "card".equals(e.type()))
                .toList();
        List<FeedEntry> attachEntries = allEntries.stream()
                .filter(e -> !("record".equals(e.type()) || "card".equals(e.type())))
                .toList();
        int totalToday = coreEntries.size();

        // 分页：从后往前翻，page 0 = 最新条目（tail），page 1 = 更早；当天仅附加条目时也能翻到
        int totalPages = Math.max(1, (totalToday + querySize - 1) / querySize);
        int idxFromEnd = totalPages - 1 - queryPage;
        List<FeedEntry> pageEntries;
        if (idxFromEnd < 0) {
            pageEntries = List.of();
        } else {
            int start = idxFromEnd * querySize;
            int end = Math.min(start + querySize, totalToday);
            pageEntries = new ArrayList<>(coreEntries.subList(start, end));
            if (queryPage == 0) {
                pageEntries.addAll(attachEntries); // 附加条目（待办提醒/行情）只在最新页
            }
        }

        log.info("Feed 分页 | date={} | 总记录={} | 总条目={} | page={} | size={} | 返回={}条",
                queryDate, totalToday, allEntries.size(), queryPage, querySize, pageEntries.size());
        return new FeedResponse(pageEntries, totalToday);
    }

    public FeedResponse getFeed(String userId, LocalDate date) {
        return getFeed(userId, date, 0, 5);
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

    private FeedEntry toFeedEntry(String userId, ContentRecord r) {
        String intent = "conversation".equals(r.type()) ? "question" : "log";
        String mediaPath = "image".equals(r.type())
                ? recordRepository.findMediaPath(userId, r.id()).orElse(null)
                : null;
        return new FeedEntry(
                "record", r.id(), null,
                r.title(), r.content(), r.tags(),
                r.createdAt().toLocalTime().format(TIME_FMT),
                intent, r.summary(), null, r.domain(),
                r.createdAt().format(DATE_FMT), mediaPath
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
                timeStr, "question", card.summary(), turns, "life",
                card.createdAt().format(DATE_FMT), null
        );
    }

    private FeedEntry toAiEntry(Memory m, ContentRecord r) {
        // ai_note 归属记录本身日期/时间，而非记忆沉淀日期（REVIEW #148：
        // 重补/升级跨日后 createdAt=处理当天，按沉淀日期展示会错日/丢失）
        return new FeedEntry(
                "ai_note", m.id(), m.recordId(),
                m.summary(), m.summary(), m.tags(),
                r.createdAt().toLocalTime().format(TIME_FMT),
                null, null, null, "life",
                r.createdAt().format(DATE_FMT), null
        );
    }

    /**
     * 找出当天记录中缺同日记忆的 recordId，批量做一次跨日查询（REVIEW #148）。
     * 无缺漏时返回空 Map，不触发 365 天扫描。
     */
    private Map<String, Memory> findCrossDayMemories(String userId, List<ContentRecord> allRecords,
            Set<String> skipRecordIds, List<Memory> sameDayMemories) {
        Set<String> missing = new HashSet<>();
        for (ContentRecord r : allRecords) {
            if (skipRecordIds.contains(r.id())) continue;
            if ("conversation".equals(r.type()) || "ai_summary".equals(r.source())) continue;
            if (memoriesFor(sameDayMemories, r.id()).isEmpty()) {
                missing.add(r.id());
            }
        }
        if (missing.isEmpty()) return Map.of();
        Map<String, Memory> result = memoryService.findByRecordIds(userId, missing);
        return result != null ? result : Map.of();
    }

    private FeedEntry toActionEntry(Memory m) {
        String text = (m.suggestion() != null && !m.suggestion().isBlank())
                ? m.suggestion() : m.summary();
        return new FeedEntry(
                "action", m.id(), m.recordId(),
                text, text, m.tags(),
                m.createdAt().toLocalTime().format(TIME_FMT),
                null, null, null, "life",
                m.createdAt().format(DATE_FMT), null
        );
    }

    /**
     * 大盘指数行情条（v0.2.0 L5 行情嵌入）。按 code 排序稳定输出。
     */
    private List<FeedEntry> buildMarketEntries() {
        Map<String, MarketData> indices = marketDataSource.indices();
        if (indices.isEmpty()) return List.of();

        String content = indices.values().stream()
                .sorted(Comparator.comparing(MarketData::code))
                .map(m -> m.name() + " " + m.price()
                        + (m.changePercent() != null && m.changePercent().signum() >= 0 ? " +" : " ")
                        + m.changePercent() + "%")
                .collect(Collectors.joining(" · "));

        FeedEntry entry = new FeedEntry(
                "market", "market_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss")), null,
                "大盘行情", content, List.of("行情"),
                LocalDateTime.now().format(TIME_FMT), null, null, null, "trading",
                LocalDate.now().format(DATE_FMT), null
        );
        return List.of(entry);
    }

    /**
     * 当日行情异动推送（Phase 2 主动推送）。MarketAlertService 落盘到
     * {@code data/{userId}/trading/pushes/{date}.json}，这里按日读取注入 type=push 条目。
     */
    private List<FeedEntry> buildPushEntries(String userId, LocalDate date) {
        return pushRepository.findByDate(userId, date).stream()
                .map(p -> toPushEntry(p, date))
                .toList();
    }

    private FeedEntry toPushEntry(MarketPushEvent p, LocalDate date) {
        return new FeedEntry(
                "push", p.id(), null,
                "行情提醒", p.message(), List.of("行情"),
                p.time(), null, null, null, "trading",
                date.format(DATE_FMT), null
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
            List<TurnDto> turns, String domain,
            String date, String mediaPath
    ) {}

    public record TurnDto(boolean isUser, String text, String time) {}
}
