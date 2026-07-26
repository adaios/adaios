package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class BriefAppService {

    private static final Logger log = LoggerFactory.getLogger(BriefAppService.class);

    private final IdentityRepository identityRepository;
    private final RecordRepository recordRepository;
    private final MemoryService memoryService;
    private final AiClient aiClient;
    private final TradingReviewAppService tradingReviewAppService;

    private String cachedBrief;
    private LocalDateTime cachedBriefAt;

    public BriefAppService(IdentityRepository identityRepository,
                           RecordRepository recordRepository,
                           MemoryService memoryService,
                           AiClient aiClient,
                           TradingReviewAppService tradingReviewAppService) {
        this.identityRepository = identityRepository;
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.aiClient = aiClient;
        this.tradingReviewAppService = tradingReviewAppService;
    }

    public String generateBrief() {
        // 2 minutes cache
        if (cachedBrief != null && cachedBriefAt != null
                && java.time.Duration.between(cachedBriefAt, LocalDateTime.now()).toMinutes() < 2) {
            return cachedBrief;
        }

        List<ContentRecord> todayRecords = recordRepository.findAll().stream()
                .filter(r -> r.createdAt().toLocalDate().equals(LocalDate.now()))
                .toList();
        List<ContentRecord> recentRecords = recordRepository.findAll().stream()
                .filter(r -> r.createdAt().toLocalDate().isAfter(LocalDate.now().minusDays(2)))
                .toList();
        List<Memory> recentMemories = memoryService.recent(7);
        String identityName = identityRepository.load()
                .map(IdentityProfile::name)
                .orElse("user");

        int hour = java.time.LocalDateTime.now().getHour();
        boolean hasTodayRecords = !todayRecords.isEmpty();
        String prompt = buildBriefPrompt(identityName, recentRecords, recentMemories, hour, hasTodayRecords);

        try {
            AiUnderstanding understanding = aiClient.understand(
                    new com.adaiadai.core.kernel.context.engine.ContextPackage(
                            "brief", "",
                            "brief", prompt, List.of(),
                            List.of(), prompt, java.time.LocalDateTime.now(),
                            List.of()
                    ));
            cachedBrief = understanding.summary();
            cachedBriefAt = LocalDateTime.now();
            return cachedBrief;
        } catch (Exception e) {
            log.warn("Brief AI failed, using default: {}", e.getMessage());
            cachedBrief = defaultBrief(identityName, todayRecords, recentRecords);
            cachedBriefAt = LocalDateTime.now();
            return cachedBrief;
        }
    }

    private String buildBriefPrompt(String name, List<ContentRecord> records,
                                     List<Memory> memories, int hour,
                                     boolean hasTodayRecords) {
        StringBuilder sb = new StringBuilder();
        String greeting = hour < 12 ? "morning" : hour < 18 ? "afternoon" : "evening";

        LocalDate today = LocalDate.now();
        DayOfWeek dow = today.getDayOfWeek();
        String todayInfo = today + " " + dow.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINESE);

        sb.append("You are a personal AI assistant. Generate a warm, concise greeting.\n\n");
        sb.append("Date: ").append(todayInfo).append("\n");
        sb.append("Day of week: ").append(dow.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)).append("\n");
        sb.append("User: ").append(name).append("\n\n");

        if (!records.isEmpty()) {
            sb.append("Recent records:\n");
            for (ContentRecord r : records) {
                String time = r.createdAt().toLocalTime()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                String dateLabel = r.createdAt().toLocalDate().equals(today) ? "today" : "yesterday";
                sb.append("- [").append(dateLabel).append(" ").append(time).append("] ")
                        .append(r.content()).append("\n");
            }
            sb.append("\n");
        }

        if (!memories.isEmpty()) {
            sb.append("What AI understands about this user:\n");
            for (Memory m : memories) {
                sb.append("- ").append(m.summary()).append("\n");
            }
            sb.append("\n");
        }

        // Timeliness hint
        if (!hasTodayRecords) {
            sb.append("NOTE: No new records today. The data above is from previous days.\n");
            sb.append("Keep it simple. Just say hello and mention 1 thing from recent history.\n");
            sb.append("Do NOT suggest the user just did something today.\n\n");
        }

        // Habit injection from memories
        if (!memories.isEmpty()) {
            sb.append("If you notice a pattern or habit from the user's history (e.g. they exercise on certain days, they often talk about certain topics), mention it naturally.\n\n");
        }

        boolean hasTrades = tradingReviewAppService.hasTradingActivity(LocalDate.now());
        if (hasTrades) {
            sb.append("User had trading activity today. Suggest generating a review note.\n\n");
        }

        sb.append("Rules:\n");
        sb.append("1. First line: \"").append(name).append(" ").append(greeting).append("!\"\n");
        sb.append("2. Use emoji at the start of each line\n");
        sb.append("3. Warm, concise, Chinese\n");
        sb.append("4. Max 30 chars per line\n");
        sb.append("5. No JSON output\n");

        return sb.toString();
    }

    private String defaultBrief(String name, List<ContentRecord> todayRecords, List<ContentRecord> allRecent) {
        int todayCount = todayRecords.size();
        int hour = java.time.LocalDateTime.now().getHour();
        String greeting = hour < 12 ? "morning" : hour < 18 ? "afternoon" : "evening";
        return "Hello " + greeting + ", " + name + "!\n"
                + todayCount + " records today\n"
                + "Stay hydrated!";
    }
}
