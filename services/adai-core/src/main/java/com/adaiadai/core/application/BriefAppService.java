package com.adaiadai.core.application;

import com.adaiadai.core.domain.project.Task;
import com.adaiadai.core.domain.project.TaskRepository;
import com.adaiadai.core.domain.project.TaskStatus;
import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
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
    private final DomainActivityService domainActivityService;
    private final TagRecommendationService tagRecommendationService;
    private final TaskRepository taskRepository;
    private final PluginService pluginService;

    // 多用户预留：Brief 缓存按 userId 隔离（2026-08-02）
    private final java.util.Map<String, String> cachedBriefByUser = new java.util.HashMap<>();
    private final java.util.Map<String, LocalDateTime> cachedBriefAtByUser = new java.util.HashMap<>();

    public BriefAppService(IdentityRepository identityRepository,
                           RecordRepository recordRepository,
                           MemoryService memoryService,
                           AiClient aiClient,
                           TradingReviewAppService tradingReviewAppService,
                           DomainActivityService domainActivityService,
                           TagRecommendationService tagRecommendationService,
                           TaskRepository taskRepository,
                           PluginService pluginService) {
        this.identityRepository = identityRepository;
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.aiClient = aiClient;
        this.tradingReviewAppService = tradingReviewAppService;
        this.domainActivityService = domainActivityService;
        this.tagRecommendationService = tagRecommendationService;
        this.taskRepository = taskRepository;
        this.pluginService = pluginService;
    }

    /**
     * 返回缓存的 Brief，不触发 AI 调用。
     * Feed 使用此方法避免阻塞主页加载。
     */
    public String getCachedBrief(String userId) {
        String cached = cachedBriefByUser.get(userId);
        LocalDateTime at = cachedBriefAtByUser.get(userId);
        if (cached != null && at != null
                && java.time.Duration.between(at, LocalDateTime.now()).toMinutes() < 5) {
            return cached;
        }
        return "";
    }

    public String generateBrief(String userId) {
        // 5 minutes cache（按 userId 隔离）
        String cached = cachedBriefByUser.get(userId);
        LocalDateTime cachedAt = cachedBriefAtByUser.get(userId);
        if (cached != null && cachedAt != null
                && java.time.Duration.between(cachedAt, LocalDateTime.now()).toMinutes() < 5) {
            return cached;
        }

        List<ContentRecord> todayRecords = recordRepository.findAll(userId).stream()
                .filter(r -> r.createdAt().toLocalDate().equals(LocalDate.now()))
                .toList();
        List<ContentRecord> recentRecords = recordRepository.findAll(userId).stream()
                .filter(r -> r.createdAt().toLocalDate().isAfter(LocalDate.now().minusDays(2)))
                .toList();
        List<Memory> recentMemories = memoryService.recent(userId, 7);
        String identityName = identityRepository.load(userId)
                .map(IdentityProfile::name)
                .orElse("user");

        int hour = java.time.LocalDateTime.now().getHour();
        boolean hasTodayRecords = !todayRecords.isEmpty();
        String prompt = buildBriefPrompt(userId, identityName, recentRecords, recentMemories, hour, hasTodayRecords);

        // R1 AI 交互日志：简报无 record，挂 userId + source 让日志正确落 data/{userId}/ai-logs
        AiTraceContext.set(userId, null, null, "brief");

        try {
            AiUnderstanding understanding = aiClient.understand(
                    new com.adaiadai.core.kernel.context.engine.ContextPackage(
                            "brief", identityName,
                            "brief", prompt, List.of(),
                            List.of(), prompt, java.time.LocalDateTime.now(),
                            List.of()
                    ));
            cachedBriefByUser.put(userId, truncateLines(understanding.summary(), 4)); // 1+3：首行问候 + 3 行内容（阿呆 08-13 层次反馈）
            cachedBriefAtByUser.put(userId, LocalDateTime.now());
            return cachedBriefByUser.get(userId);
        } catch (Exception e) {
            log.warn("Brief AI failed: {}", e.getMessage());
            String greeting = greetingForHour(hour);
            // 降级增强（阿呆 08-14 反馈「就两条」）：AI 失败时用本地数据拼内容，不再干巴巴 2 行
            StringBuilder fallback = new StringBuilder();
            fallback.append(emojiForHour(hour)).append(" ").append(identityName).append(" ").append(greeting).append("！");
            if (!todayRecords.isEmpty()) {
                fallback.append("\n📋 今日已有 ").append(todayRecords.size()).append(" 条记录");
            } else if (!recentRecords.isEmpty()) {
                fallback.append("\n📋 最近两天有 ").append(recentRecords.size()).append(" 条记录");
            } else {
                fallback.append("\n📋 今天还没有记录");
            }
            if (!recentMemories.isEmpty()) {
                String mem = recentMemories.get(0).summary();
                if (mem != null && !mem.isBlank()) {
                    if (mem.length() > 28) mem = mem.substring(0, 28) + "…";
                    fallback.append("\n🧠 ").append(mem.trim());
                }
            }
            fallback.append("\n☕ 慢慢来，一件件来");
            cachedBriefByUser.put(userId, truncateLines(fallback.toString(), 4));
            cachedBriefAtByUser.put(userId, LocalDateTime.now());
            return cachedBriefByUser.get(userId);
        }
    }

    /**
     * 中文时段问候。凌晨 0-5 → 深夜好；6-10 → 早上好；11-13 → 中午好；14-17 → 下午好；18-23 → 晚上好。
     * #14 修复（2026-08-12）：凌晨不再归入「早上好」。
     * #222（2026-08-12）：加中午段（11-13），12 点不再机械归「下午好」。
     */
    static String greetingForHour(int hour) {
        if (hour < 6) return "深夜好";
        if (hour < 11) return "早上好";
        if (hour < 14) return "中午好";
        if (hour < 18) return "下午好";
        return "晚上好";
    }

    /**
     * 英文时段问候（供 AI prompt 首行）。与 {@link #greetingForHour} 同步。
     */
    static String greetingEnForHour(int hour) {
        if (hour < 6) return "late night";
        if (hour < 11) return "morning";
        if (hour < 14) return "midday";
        if (hour < 18) return "afternoon";
        return "evening";
    }

    /**
     * 时段 emoji（#221：降级问候按时段，不再固定 ☀️——凌晨配 ☀️ 语义矛盾）。
     * 与 {@link #greetingForHour} 时段一致（#222 加中午 🌤️ / 下午 🌇）。
     */
    static String emojiForHour(int hour) {
        if (hour < 6) return "🌙";
        if (hour < 11) return "☀️";
        if (hour < 14) return "🌤️";
        if (hour < 18) return "🌇";
        return "✨";
    }

    /** Limit string to at most {@code maxLines} lines. */
    private String truncateLines(String text, int maxLines) {
        if (text == null || text.isBlank()) return text;
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private String buildBriefPrompt(String userId, String name, List<ContentRecord> records,
                                     List<Memory> memories, int hour,
                                     boolean hasTodayRecords) {
        StringBuilder sb = new StringBuilder();
        String greeting = greetingEnForHour(hour);

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

        // G-2（2026-08-16）：交易活动信号只注入 trading 插件用户——无插件用户不查交易、简报不出现交易提示
        boolean hasTrades = pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)
                && tradingReviewAppService.hasTradingActivity(userId, LocalDate.now());
        if (hasTrades) {
            sb.append("User had trading activity today. Suggest generating a review note.\n\n");
        }

        // ── Domain activity signals ──
        try {
            DomainActivityService.DomainBriefActivity activity = domainActivityService.getActivity(userId);
            sb.append("Domain activity (last 7 days):\n");
            for (var item : activity.domains()) {
                String note = switch (item.trend()) {
                    case "inactive" -> "no activity this week";
                    case "quiet" -> "activity dropped this week compared to last";
                    case "up" -> "activity increased this week";
                    case "stable" -> "activity level consistent";
                    default -> "activity level consistent";
                };
                sb.append("- ").append(item.domain()).append(": ")
                        .append(item.weekCount()).append(" records this week (")
                        .append(item.todayCount()).append(" today) — ").append(note).append("\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Domain activity signal skipped: {}", e.getMessage());
        }

        // ── Tag signals ──
        try {
            TagRecommendationService.TagRecommendations tags = tagRecommendationService.getRecommendations(userId);
            sb.append("Tag signals:\n");
            if (!tags.hot().isEmpty()) {
                sb.append("- Hot tags (recent 3 days): ").append(String.join(", ", tags.hot())).append("\n");
            }
            if (!tags.cold().isEmpty()) {
                sb.append("- Cold tags (not used >14 days, used to be frequent): ").append(String.join(", ", tags.cold())).append("\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Tag recommendation signal skipped: {}", e.getMessage());
        }

        // ── Task signals（08-14：概览卡主动提示待办，阿呆 10:25 反馈「重要信息不提示我」）──
        try {
            List<Task> openTasks = taskRepository.findAll(userId).stream()
                    .filter(t -> t.status() == TaskStatus.TODO || t.status() == TaskStatus.DOING)
                    .limit(3)
                    .toList();
            if (!openTasks.isEmpty()) {
                sb.append("Open tasks (not done, should be surfaced to user):\n");
                for (Task t : openTasks) {
                    sb.append("- ").append(t.title()).append(" (").append(t.status()).append(")\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Task signal skipped: {}", e.getMessage());
        }

        sb.append("Rules:\n");
        sb.append("1. First line: \"").append(name).append(" ").append(greeting).append("!\"\n");
        sb.append("2. Use emoji at the start of each line\n");
        sb.append("3. Warm, concise, Chinese\n");
        sb.append("4. Max 30 chars per line, 4 lines total: line 1 = greeting (concise overview), lines 2-4 = max 3 content items\n");
        sb.append("5. No JSON output\n");
        sb.append("6. Use actual emoji characters (NOT \\uXXXX escape codes)\n");
        sb.append("7. If there are open tasks, proactively remind 1-2 most important ones (e.g. \"你还有 N 件待办\")\n");

        return sb.toString();
    }

}
