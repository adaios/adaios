package com.adaiadai.core.application;

import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoRepository;
import com.adaiadai.core.kernel.todo.TodoStatus;
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

    /** 简报注入的待办条数硬上限（RFC 20260923 A 批·闸 3）。 */
    private static final int MAX_BRIEF_TODOS = 3;

    /**
     * 周期性表述识别（RFC 20260923 A 批·闸 2）。
     * <p>
     * 「周期习惯」不是待办——它没有终点，催它等于每天复读。2026-09-23 用户反馈的生产实据：
     * 一句「今天周四 固定发版日 在加班」被转成 OPEN 待办后，概览卡天天提醒他。
     * A 批先用本判据把这**一类**条目挡在提醒段之外（数据不动、待办页照常可见）；
     * B 批上线 rhythm 通道后，改由 RRULE 命中日决定是否作背景注入。
     * <p>
     * 判据刻意保守：必须出现明确的重复词（每周/每月/每天/例会/定期…）或「周X + 固定」组合。
     * 反例（**不得命中**）："周四要交周报"（一次性任务）、"给妈打个电话"、"整理上周复盘"。
     */
    private static final java.util.regex.Pattern RHYTHM_LIKE = java.util.regex.Pattern.compile(
            "每周|每星期|每月|每天|每日|每季度|每年|例行|定期"
                    + "|(周|星期|礼拜)[一二三四五六日天]\\s*固定"
                    + "|固定\\s*(的)?\\s*(周|星期|礼拜|每周|发版|例会|值班)");

    /** 是否周期性习惯表述（= 不该被当待办催）。包级可见：供单测直接覆盖判据正反例。 */
    static boolean isRhythmLike(String title) {
        return title != null && RHYTHM_LIKE.matcher(title).find();
    }

    private final IdentityRepository identityRepository;
    private final RecordRepository recordRepository;
    private final MemoryService memoryService;
    private final AiClient aiClient;
    private final TradingReviewAppService tradingReviewAppService;
    private final DomainActivityService domainActivityService;
    private final TagRecommendationService tagRecommendationService;
    private final TodoRepository todoRepository;
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
                           TodoRepository todoRepository,
                           PluginService pluginService) {
        this.identityRepository = identityRepository;
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.aiClient = aiClient;
        this.tradingReviewAppService = tradingReviewAppService;
        this.domainActivityService = domainActivityService;
        this.tagRecommendationService = tagRecommendationService;
        this.todoRepository = todoRepository;
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

        // 图文一体（2026-09-22）：薄附件（被主记录 mediaIds 引用）不进简报——它们只是主记录的图，
        // 不是独立事件；否则简报会读到 N 条「图片附件」这种系统视角内容（违反第一原则）
        List<ContentRecord> allForBrief = recordRepository.findAll(userId);
        java.util.Set<String> attachmentIds =
                com.adaiadai.core.kernel.record.MediaAttachments.referencedIds(allForBrief);
        List<ContentRecord> todayRecords = allForBrief.stream()
                .filter(r -> !attachmentIds.contains(r.id()))
                .filter(r -> r.createdAt().toLocalDate().equals(LocalDate.now()))
                .toList();
        List<ContentRecord> recentRecords = allForBrief.stream()
                .filter(r -> !attachmentIds.contains(r.id()))
                .filter(r -> r.createdAt().toLocalDate().isAfter(LocalDate.now().minusDays(2)))
                .toList();
        // RFC 20260923 A 批：统一走 recentActive（过滤 superseded）——原来用 recent()，
        // 已被取代/作废的记忆照样注入，是「天天提醒」的第二条通路（与 ContextEngine 口径对齐）。
        List<Memory> recentMemories = memoryService.recentActive(userId, 7);
        // 2026-09-16「第一次见面」批：name 现在允许为空（新用户还没填昵称）。
        // 分开两用——给 AI 的空值兜底成 "the user"，给用户看的问候语则在空时整段省掉称呼，
        // 避免拼出「☀️  早上好！」这种双空格或把英文塞进中文问候。
        String userName = identityRepository.load(userId)
                .map(IdentityProfile::name)
                .map(String::trim)
                .orElse("");
        String identityName = userName.isEmpty() ? "the user" : userName;

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
            fallback.append(emojiForHour(hour)).append(" ");
            if (!userName.isEmpty()) fallback.append(userName).append(" ");
            fallback.append(greeting).append("！");
            if (!todayRecords.isEmpty()) {
                fallback.append("\n📋 今日已有 ").append(todayRecords.size()).append(" 条记录");
            } else if (!recentRecords.isEmpty()) {
                fallback.append("\n📋 最近两天有 ").append(recentRecords.size()).append(" 条记录");
            } else {
                fallback.append("\n📋 今天还没有记录");
            }
            if (!recentMemories.isEmpty()) {
                // P1-4（2026-08-17 走查）：不再直贴 AI 记忆摘要（第三人称原文如「延续此前对面壁者计划的讨论」
                // 是内部理解，贴进问候语=第三视角泄漏）→ 中性引导，让用户自己开口
                fallback.append("\n🧠 你之前聊过些话题，想接着聊随时说");
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

        // RFC 20260923 A 批：删除原「发现习惯就自然提及」指令——
        // 它是「天天提醒」最直接的正面成因（要求模型把习惯说出来，而节律又被当成了待办）。
        // 记忆继续注入作背景，但不再主动要求模型点名习惯。

        // G-2（2026-08-16）：交易活动信号只注入 trading 插件用户——无插件用户不查交易、简报不出现交易提示
        boolean hasTrades = pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)
                && tradingReviewAppService.hasTradingActivity(userId, LocalDate.now());
        if (hasTrades) {
            sb.append("User had trading activity today. Suggest generating a review note.\n\n");
        }

        // 2026-08-17：注入真实持仓快照——防止 LLM 拿历史买入记录自行算盈亏（曾产出「京东方浮盈11.73%」而实际亏 3.8%）
        if (pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            try {
                List<String> posLines = tradingReviewAppService.positionSummaryLines(userId);
                if (!posLines.isEmpty()) {
                    sb.append("Current positions (authoritative, use these for P&L — never compute from old records):\n");
                    for (String line : posLines) {
                        sb.append("- ").append(line).append("\n");
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                log.debug("Brief 持仓快照注入失败: {}", e.getMessage());
            }
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

        // ── Todo signals（08-14：概览卡主动提示待办，阿呆 10:25 反馈「重要信息不提示我」）──
        // RFC 20260917：待办归 Kernel builtin（旧 Task 看板已撤），口径 = OPEN 未完成
        // RFC 20260923 A 批·闸 2/闸 3：
        //   ① 周期性习惯（"周四固定发版加班"）**不进提醒段**——节律是概率不是承诺（用户：
        //      「我可能需要加班，也可能这周四就不需要了」），催它＝每天复读；
        //   ② 条数硬上限显式化（原来裸写 limit(3)）。
        // 被挡下的条目**数据不动**（待办页照常可见）；B 批建 rhythm 通道后按 RRULE 命中日作背景注入。
        try {
            List<Todo> openTodos = todoRepository.findAll(TodoStatus.OPEN, userId);
            List<Todo> remindable = openTodos.stream()
                    .filter(t -> !isRhythmLike(t.title()))
                    .limit(MAX_BRIEF_TODOS)
                    .toList();
            if (!remindable.isEmpty()) {
                sb.append("Open todos (not done, should be surfaced to user):\n");
                for (Todo t : remindable) {
                    sb.append("- ").append(t.title());
                    if (t.due() != null) sb.append(" (due ").append(t.due()).append(")");
                    sb.append("\n");
                }
                sb.append("\n");
            }
            int heldBack = openTodos.size() - remindable.size();
            if (heldBack > 0) {
                log.debug("Brief 待办注入闸门：{}/{} 条未注入（周期习惯优先挡下 + 超上限） | userId={}",
                        heldBack, openTodos.size(), userId);
            }
        } catch (Exception e) {
            log.debug("Todo signal skipped: {}", e.getMessage());
        }

        sb.append("Rules:\n");
        sb.append("1. First line: \"").append(name).append(" ").append(greeting).append("!\"\n");
        sb.append("2. Use emoji at the start of each line\n");
        sb.append("3. Warm, concise, Chinese\n");
        sb.append("4. Max 30 chars per line, 4 lines total: line 1 = greeting (concise overview), lines 2-4 = max 3 content items\n");
        sb.append("5. No JSON output\n");
        sb.append("6. Use actual emoji characters (NOT \\uXXXX escape codes)\n");
        // RFC 20260923 A 批·闸 2：提醒口径收紧——只提醒列在上面的待办；
        // 并明令禁止把习惯/惯例/周期性事件当"要做的事"提出来（那是催，不是提醒）。
        sb.append("7. Only if the \"Open todos\" section above is non-empty, mention 1-2 of them.\n");
        sb.append("8. Never invent reminders. Do NOT bring up habits, routines or recurring events (e.g. \"you usually work late on Thursdays\") as things to do or to prepare for.\n");

        return sb.toString();
    }

}
