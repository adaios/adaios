package com.adaiadai.core.kernel.context.engine;

import com.adaiadai.core.kernel.context.engine.ContextPackage.ChatMessage;
import com.adaiadai.core.kernel.context.policy.ContextAssemblyPolicy;
import com.adaiadai.core.kernel.search.SearchResult;
import com.adaiadai.core.kernel.search.SearchService;
import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import com.adaiadai.core.kernel.knowledge.KnowledgeSource;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.CardRepository;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ContextEngine — 上下文引擎，Kernel 核心能力。
 * <p>
 * Phase 2 重构：
 * <ul>
 *   <li>标签索引关联记录（TagIndexService），替代时间窗口</li>
 *   <li>Memory 回读（按标签聚合）</li>
 *   <li>卡片对话上下文（cardId 传入时加载全部对话轮次）</li>
 *   <li>全局领域上下文（所有 Domain OS 的 globalContext()）</li>
 *   <li>当前日期/星期注入</li>
 * </ul>
 */
@Component
public class ContextEngine {

    private static final Logger log = LoggerFactory.getLogger(ContextEngine.class);

    private static final int MAX_RELATED_RECORDS = 20;
    private static final int MEMORY_DAYS = 7;

    /** 短档「极少核心」记忆的回看窗口（天）——独立于 {@link #MEMORY_DAYS}，因为它只取偏好类。 */
    private static final int CORE_MEMORY_LOOKBACK_DAYS = 30;

    private static final List<String> TRADING_KEYWORDS =
            List.of("指标", "K线", "持仓", "走势", "复盘", "买入", "卖出", "仓位", "股票", "大盘", "行情", "买卖");

    private final IdentityRepository identityRepository;
    private final RecordRepository recordRepository;
    private final TagIndexReader tagIndexReader;
    private final MemoryService memoryService;
    private final CardRepository cardRepository;
    private final List<ContextContributor> contributors;
    private final List<KnowledgeSource> knowledgeSources;
    private final SearchService searchService;

    /** 插件门控（RFC 20260814 第二步）：按账号 enabledPlugins 过滤知识源/贡献者 + D5 domain 判定。 */
    private final PluginService pluginService;

    /**
     * 装配策略（RFC 20260929 批 1）：分档、配额与灰度开关。
     * 关闭分档时行为与批 1 之前**逐字一致**（legacy）。
     */
    private final ContextAssemblyPolicy policy;

    public ContextEngine(IdentityRepository identityRepository,
                         RecordRepository recordRepository,
                         TagIndexReader tagIndexReader,
                         MemoryService memoryService,
                         CardRepository cardRepository,
                         List<ContextContributor> contributors,
                         List<KnowledgeSource> knowledgeSources,
                         SearchService searchService,
                         PluginService pluginService,
                         ContextAssemblyPolicy policy) {
        this.identityRepository = identityRepository;
        this.recordRepository = recordRepository;
        this.tagIndexReader = tagIndexReader;
        this.memoryService = memoryService;
        this.cardRepository = cardRepository;
        this.contributors = contributors;
        this.knowledgeSources = knowledgeSources;
        this.searchService = searchService;
        this.pluginService = pluginService;
        this.policy = policy;
    }

    /**
     * 为指定记录组装上下文包（无卡片上下文）。
     */
    public ContextPackage compose(String userId, String scene, ContentRecord record) {
        return compose(userId, scene, record, null);
    }

    /**
     * 为指定的 Record 组装上下文包。
     * <p>
     * 组装流程：
     * <ol>
     *   <li>Identity — 你是谁</li>
     *   <li>卡片上下文 — 当前会话的对话轮次</li>
     *   <li>标签关联记录 — 与当前记录同标签的历史记录</li>
     *   <li>记忆摘要 — AI 对你的近期理解（按标签聚合）</li>
     *   <li>领域上下文 — 场景特定 + 所有 Domain 全局摘要</li>
     * </ol>
     *
     * @param userId 用户 ID（多用户架构预留，单用户传 "default"）
     */
    public ContextPackage compose(String userId, String scene, ContentRecord record, String cardId) {
        // RFC 20260814 第二步：按账号 enabledPlugins 门控知识/贡献者注入 + D5 domain 判定
        Set<String> enabledPlugins = pluginService.enabledPlugins(userId);

        // RFC 20260929 批 1 ①：**一次读卡**。原先 loadCardContext 与 buildConversationHistory
        // 各调一次 cardRepository.findById，而它的实现是 findAll 全量遍历 + 逐个解析卡片文件
        // （CardFileRepository:77-137）——单次对话把整个卡片目录解析 2~3 遍（REVIEW #19 的已知待办）。
        CardRecord card = cardId != null ? cardRepository.findById(userId, cardId).orElse(null) : null;
        List<CardRecord.Turn> turns = (card != null && card.turns() != null) ? card.turns() : List.of();

        // 批 1 ②：按轮次分档（关闭分档时恒为 LONG = 现状全量）。
        // ⚠️ 对抗审查 P1-2：**无卡片场景必须视为 LONG**——随手记（STATEMENT）/ 重补 / 复盘走的是
        // 三参 compose（cardId=null）→ turns=0 → 会被误判成短档，把相关历史与近期记忆一起砍掉；
        // 更要命的是 `loadMemorySummary` 不再被调用 → `touchActive`（记忆进化 Phase 4 的回读确认，
        // 即 `lastConfirmed` 的累积）对新记录**彻底停止**。无卡就没有「对话前文」，分档本就无意义。
        ContextAssemblyPolicy.Tier tier = (cardId == null)
                ? ContextAssemblyPolicy.Tier.LONG
                : policy.tierFor(turns.size());

        String identityRef = loadIdentitySummary(userId);

        // 批 1 ③：CHAT 模式的前文由 messages（conversationHistory）承载，**prompt 里不再重复一份**
        // ——同一段对话以「原始轮次」与「摘要文本」两种形式并存会让模型无法判断哪份权威（context clash）。
        // legacy 保持原样（cardContext 仍进 prompt），便于灰度对比。
        String cardContext = policy.tieringEnabled() ? "" : loadCardContext(card);
        List<ChatMessage> chatHistory = "question".equals(scene) && cardId != null
                ? toChatMessages(turns)
                : List.of();

        // 批 1 ②：L3 相关历史/检索——短档整层不注入；中档不检索（仅长档允许 L3）
        String relatedRecords = tier == ContextAssemblyPolicy.Tier.SHORT
                ? ""
                : loadRelatedRecords(userId, record);
        String searchResults = tier == ContextAssemblyPolicy.Tier.LONG
                ? loadSearchResults(userId, record)
                : "";

        // 批 1 ①/D1：L2 记忆——短档只给「极少核心」（偏好类，≤ 配额）；中/长档给完整近期记忆
        String memorySummary = tier == ContextAssemblyPolicy.Tier.SHORT
                ? loadCoreMemory(userId)
                : loadMemorySummary(userId);

        // 领域场景按记录内容推导（trading/life），只在启用插件间判定（D5）
        String domainScene = detectDomainScene(record, enabledPlugins);
        boolean tradingHit = "trading".equals(domainScene);

        // 批 1 ④：L4 领域知识**只在命中该领域时**注入——生产实测（2026-09-29）生活/学习话题
        // 也背着 512 字符「交易哲学」+ 501 字符「交易系统状态」，知识源整体占 prompt 的 46%（P90 87%）。
        // 只收紧 trading（证据最充分）；learn 保持原行为——不擅自扩大范围。
        boolean injectTrading = !policy.tieringEnabled() || tradingHit;
        String knowledgeContext = loadKnowledgeContext(userId, domainScene, enabledPlugins, injectTrading);
        String domainContext = enrichFromContributors(userId, domainScene, identityRef, record, enabledPlugins);
        String globalContext = loadGlobalContext(userId, enabledPlugins, injectTrading);

        // ANALYSIS 模式：仍然使用合成 Prompt
        String prompt = buildPrompt(scene, identityRef, record,
                cardContext, relatedRecords, searchResults, memorySummary,
                knowledgeContext, domainContext, globalContext, enabledPlugins);

        // 批 1 ⑤：分层用量可观测——此前只有「标签关联/搜索」两个计数，且 token 估算漏算
        // history 与 relatedRefs（F5）。有了分段字数，才知道「这次到底注入了什么、各占多少」。
        int l0 = identityRef.length();
        int l1 = chatHistory.stream().mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
        int l2 = memorySummary.length();
        int l3 = relatedRecords.length() + searchResults.length();
        int l4 = knowledgeContext.length() + domainContext.length() + globalContext.length();
        int cur = record.content() == null ? 0 : record.content().length();

        // 注入合计（字符）：v1 下 prompt **不再是发送内容**（CHAT 走 messages），因此不再打印
        // `prompt.length()/2` 冒充 token 估计——对抗审查 P2-1 指出它会把「根本没发送的 prompt」算进去，
        // 与本类真正分段对不上。要精确 token 看 DeepSeek 返回的 usage（本批已解析并落日志）。
        int injectedChars = l0 + l1 + l2 + l3 + l4 + cur;

        log.info("ContextPackage 组装完成 | scene={} | record={} | 模式={} | 档位={} | 轮次={} "
                        + "| 分段字数 L0={} L1={} L2={} L3={} L4={} 当前记录={} | 注入合计={}字符 "
                        + "| 标签关联={}条 | 搜索={}条",
                scene, record.id(),
                chatHistory.isEmpty() ? "ANALYSIS" : "CHAT(" + chatHistory.size() + "轮)",
                tier, turns.size(),
                l0, l1, l2, l3, l4, cur, injectedChars,
                relatedRecords.isBlank() ? 0 : relatedRecords.split("\n").length,
                searchResults.isBlank() ? 0 : searchResults.split("\n").length);

        // 批 1 ④：稳定前缀（v1）——CHAT 模式用作**唯一一条 system**；变化内容（记忆/相关记录/检索/领域知识）
        // 由消费方拼到最后一条 user 消息，于是「system + 对话历史」构成逐轮稳定的可缓存前缀。
        String stableSystem = policy.tieringEnabled()
                ? buildStableSystem(identityRef, enabledPlugins)
                : null;

        // ⚠️ 对抗审查 P1-1（真缺陷）：v1 **不再走 `buildContextFromPrompt`**（那是 legacy「从 prompt 里
        // 截取上下文塞进 system」的做法），所以检索结果、知识源、领域/全局上下文必须**显式随包下发**——
        // 否则就是「算完即丢」：对话里交易哲学 / 学习卡 / 生活知识 **0 注入**，新加的 L4 门控也白做
        // （只白算了 prompt 字符串，还会留在 ai-log 里造成「阿呆看到了交易知识」的假象）。
        // legacy 仍是三元素（由 `buildBackground` 消费），逐字不变。
        List<String> relatedRefs = policy.tieringEnabled()
                ? List.of(cardContext, relatedRecords, memorySummary, searchResults,
                           joinSections(knowledgeContext, domainContext, globalContext))
                : List.of(cardContext, relatedRecords, memorySummary);

        return new ContextPackage(
                scene, identityRef,
                record.title(), record.content(), record.tags(),
                relatedRefs,
                prompt,
                LocalDateTime.now(),
                chatHistory,
                // REVIEW P2-4：插件收敛后的 domain 枚举随包下发，CHAT 模式 system prompt 不再硬编码全量
                buildDomainEnum(enabledPlugins),
                stableSystem);
    }

    /** 按序拼接若干上下文块（空块跳过）——v1 的动态参考用；legacy 不经过它。 */
    private static String joinSections(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(p.strip());
        }
        return sb.toString();
    }

    /**
     * 稳定前缀（批 1 ④）：CHAT 模式下的**唯一一条 system** 内容——只放"逐轮不变"的部分。
     * <p>
     * 与 {@link #buildPrompt} 的区别：prompt 是**一次性合成长文**（含当前记录、相关历史、记忆、检索，
     * 给 ANALYSIS 模式用），而这里只取其中稳定的骨架；变化的部分（记忆/相关记录/检索）由
     * {@code DeepSeekAiClient} 拼到最后一条 user 消息里。
     * <p>
     * 为什么必须拆：现状把每轮都变的内容放进第 ②③ 条 system，而 system 排在 messages 最前面
     * → 前缀第二段就变了，DeepSeek 的「缓存前缀单元」从那里起全部失效；且**多条 system 的兼容性
     * 各家不一**（Gemini 只取最后一条、Qwen/Mistral 直接报错）。
     * <p>
     * 日期是这里唯一"每天变一次"的内容（天级变化可接受；**不得**放进秒级时间戳或随机 id）。
     */
    private String buildStableSystem(String identityRef, Set<String> enabledPlugins) {
        String todayInfo = "%s %s".formatted(
                LocalDate.now().toString(),
                LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE)
        );
        return """
                处理一条新记录。

                %s
                当前日期：%s
                场景：question

                %s
                %s""".formatted(identityRef, todayInfo,
                buildCapabilityContext(enabledPlugins), buildDomainRules(enabledPlugins));
    }

    // ── 内部方法 ──

    /**
     * 根据记录内容关键词推导领域场景（trading / project / life）。
     * <p>
     * 调用方传给 compose() 的 scene 是模式场景（"note"/"question"），
     * 领域知识源和场景贡献者需要内容驱动的领域场景才能被触发
     * （规则与 api-spec「domain 判定规则」一致）。
     * <p>
     * D5（RFC 20260814）：只在用户启用的插件间判定——无 trading 插件 → 交易词不判 trading；
     * 无 project 插件 → 项目词不判 project；无插件用户一律 life（单一 domain，无需判定）。
     */
    private String detectDomainScene(ContentRecord record, Set<String> enabledPlugins) {
        String content = record.content() == null ? "" : record.content();
        if (content.isBlank()) return "life";

        if (enabledPlugins.contains(PluginRegistry.PLUGIN_TRADING)
                && TRADING_KEYWORDS.stream().anyMatch(content::contains)) {
            return "trading";
        }
        return "life";
    }

    private String loadIdentitySummary(String userId) {
        Optional<IdentityProfile> profile = identityRepository.load(userId);
        return profile.map(p -> {
            // 2026-09-16「第一次见面」批：新用户还没填昵称（name 为空）时不注入空称呼行——
            // 既避免 prompt 出现「- 称呼：」这种空壳，也不给模型编造名字的机会。
            String name = p.name() == null ? "" : p.name().trim();
            String callLine = name.isEmpty() ? "- 称呼：（还没告诉我，先别称呼，或自然称呼「你」）\n" : "- 称呼：" + name + "\n";
            String prefs = String.join("; ", p.preferences().values());
            String rules = String.join("; ", p.rules().values());
            return """
                    用户身份摘要：
                    %s- 偏好：%s
                    - 协作规则：%s
                    """.formatted(callLine, prefs, rules);
        }).orElse("用户身份：未配置");
    }

    /**
     * 加载当前卡片对话上下文（全部轮次）——**legacy 口径**：把前文以文本块放进 prompt。
     * <p>
     * 批 1 起 {@code v1} 模式不再调用它：前文改由 messages（conversationHistory）承载，
     * 避免同一段对话以「原始轮次 + 摘要文本」两种形式并存（context clash）。
     * 入参改为**已读到的卡片**——调用方一次读卡（见 {@code compose}），不再各自 findById。
     */
    private String loadCardContext(CardRecord card) {
        if (card == null) return "";

        List<CardRecord.Turn> turns = card.turns();
        if (turns == null || turns.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## 当前会话对话历史\n\n");
        for (CardRecord.Turn turn : turns) {
            String prefix = turn.isUser() ? "用户" : "AI";
            sb.append("- [").append(turn.time()).append("] ")
                    .append(prefix).append("：").append(turn.text()).append("\n");
        }

        log.info("卡片上下文已加载 | cardId={} | turns={}", card.id(), turns.size());
        return sb.toString();
    }

    /**
     * 把卡片轮次转成多轮对话消息（供 DeepSeekAiClient chat 模式使用）。
     * <p>
     * isUser=true → "user"，isUser=false → "assistant"。入参为**已读到的 turns**
     * （调用方一次读卡，见 {@code compose}——批 1 起不再各自 findById 全量扫卡片目录）。
     */
    private List<ChatMessage> toChatMessages(List<CardRecord.Turn> turns) {
        if (turns == null || turns.isEmpty()) return List.of();

        List<ChatMessage> messages = new ArrayList<>();
        for (CardRecord.Turn turn : turns) {
            String role = turn.isUser() ? "user" : "assistant";
            messages.add(new ChatMessage(role, turn.text()));
        }

        log.info("对话历史已构建 | messages={}", messages.size());
        return messages;
    }

    /**
     * 加载与当前记录同标签的历史记录（通过 TagIndexService，不限时间）。
     * <p>
     * 如果当前记录还没有标签（新记录，AI 尚未处理），回退到最近记录。
     */
    private String loadRelatedRecords(String userId, ContentRecord currentRecord) {
        List<String> tags = currentRecord.tags();
        if (tags == null || tags.isEmpty()) {
            // 回退：没有标签时取最近记录
            List<ContentRecord> allRecords = recordRepository.findAll(userId);
            // 图文一体（2026-09-22）：薄附件不进 AI 上下文——否则阿呆会读到 N 条「图片附件」噪音
            java.util.Set<String> attachmentIds =
                    com.adaiadai.core.kernel.record.MediaAttachments.referencedIds(allRecords);
            // 批 1 ②：「最近 N 条」≠「相关 N 条」——生产实测 72%（108/151）的对话模式请求走的
            // 就是这个回退分支、且**满额注入 20 条**无关记录摘要（F2）。批 1 先收到 policy 配额
            // （v1 默认 2 条）；真正的相关性打分/两臂召回属批 2。
            List<ContentRecord> recent = allRecords.stream()
                    .filter(r -> !attachmentIds.contains(r.id()))
                    .filter(r -> !r.id().equals(currentRecord.id()))
                    .limit(policy.fallbackRecentMax())
                    .collect(Collectors.toList());
            if (recent.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("## 相关历史记录\n\n");
            for (ContentRecord r : recent) {
                String time = r.createdAt().toLocalTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm"));
                String date = r.createdAt().toLocalDate().toString();
                String summary = r.summary() != null && !r.summary().isBlank()
                        ? r.summary()
                        : r.title();
                sb.append("- [").append(date).append(" ").append(time).append("] ")
                        .append("(").append(r.type()).append(") ")
                        .append(summary).append("\n");
            }
            return sb.toString();
        }

        List<String> relatedIds = tagIndexReader.findRelatedIds(userId, tags, MAX_RELATED_RECORDS);

        // 加载实际记录
        List<ContentRecord> related = relatedIds.stream()
                .map(id -> recordRepository.findById(userId, id).orElse(null))
                .filter(Objects::nonNull)
                .filter(r -> !r.id().equals(currentRecord.id()))
                .collect(Collectors.toList());

        if (related.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("## 相关历史记录\n\n");
        sb.append("（基于标签：").append(String.join("、", tags)).append("）\n\n");
        for (ContentRecord r : related) {
            String time = r.createdAt().toLocalTime()
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
            String date = r.createdAt().toLocalDate().toString();
            String summary = r.summary() != null && !r.summary().isBlank()
                    ? r.summary()
                    : r.title();
            sb.append("- [").append(date).append(" ").append(time).append("] ")
                    .append("(").append(r.type()).append(") ")
                    .append(summary).append("\n");
        }
        return sb.toString();
    }

    /**
     * 通过全文搜索查找与当前记录内容相关的历史记录。
     * <p>
     * 对用户输入做关键词搜索，找到标题/正文/标签/摘要中匹配的历史记录。
     * 与 loadRelatedRecords() 互补：tag 关联找到"同类"，搜索找到"同主题"。
     */
    private String loadSearchResults(String userId, ContentRecord currentRecord) {
        String query = currentRecord.content();
        if (query == null || query.isBlank()) return "";

        // 取关键词前 50 字作为搜索查询
        if (query.length() > 50) query = query.substring(0, 50);

        List<SearchResult> results = searchService.search(userId, query);
        if (results.isEmpty()) return "";

        // 排除当前记录本身，最多取 10 条
        List<SearchResult> filtered = results.stream()
                .filter(r -> !r.id().equals(currentRecord.id()))
                .limit(10)
                .toList();

        if (filtered.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## 搜索相关历史记录\n\n");
        sb.append("（基于内容关键词匹配）\n\n");
        for (SearchResult r : filtered) {
            String time = r.dateTime().toLocalTime()
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
            String date = r.dateTime().toLocalDate().toString();
            String summary = r.title() != null && !r.title().isBlank()
                    ? r.title()
                    : r.content().length() > 60 ? r.content().substring(0, 60) + "..." : r.content();
            sb.append("- [").append(date).append(" ").append(time).append("] ")
                    .append("(").append(r.type()).append(") ")
                    .append(summary).append("\n");
        }
        return sb.toString();
    }

    /**
     * 加载近期的记忆摘要（按标签聚合）。
     */
    private String loadMemorySummary(String userId) {
        // 记忆进化 Phase 4：回读确认——更新近期记忆 lastConfirmed，让常回读的记忆保持时效权重
        memoryService.touchActive(userId);
        List<Memory> recentMemories = memoryService.recentActive(userId, MEMORY_DAYS);
        if (recentMemories.isEmpty()) {
            return "";
        }

        // 按标签聚合记忆摘要
        Map<String, List<String>> byTag = new LinkedHashMap<>();
        for (Memory m : recentMemories) {
            for (String tag : m.tags()) {
                byTag.computeIfAbsent(tag, k -> new ArrayList<>())
                        .add(m.summary());
            }
        }

        StringBuilder sb = new StringBuilder("## AI 对你的近期理解\n\n");
        for (Map.Entry<String, List<String>> entry : byTag.entrySet()) {
            // 取每个标签下最新的 2 条摘要
            List<String> summaries = entry.getValue().stream()
                    .distinct()
                    .limit(2)
                    .collect(Collectors.toList());
            if (!summaries.isEmpty()) {
                sb.append("【").append(entry.getKey()).append("】")
                        .append(String.join("；", summaries))
                        .append("\n");
            }
        }

        // 待行动事项：actionable 且未完成的记忆单独注入（记忆进化 Phase 3）
        List<Memory> pendingActions = recentMemories.stream()
                .filter(m -> m.actionable() && m.doneAt() == null)
                .toList();
        if (!pendingActions.isEmpty()) {
            sb.append("\n## 待行动事项\n");
            for (Memory m : pendingActions) {
                String s = (m.suggestion() != null && !m.suggestion().isBlank())
                        ? m.suggestion() : m.summary();
                sb.append("- ").append(s).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 短档「极少核心」记忆（D1 拍板：短对话仍保留身份 + 高置信偏好，≤ 配额）。
     * <p>
     * 为什么不干脆不注入：用户多次强调「你要记住我」，而 identityRef 里的偏好是**手填的**；
     * preference 类记忆是阿呆自己观察到的偏好，短对话给 2 条能保住「它记得我」的体感。
     * <p>
     * 对抗审查 P2-3 修正两处：
     * <ul>
     *   <li><b>成本</b>：原用 {@code findByKind}（只扫近 30 天，但**逐日**调 {@code findByDate} 30 次）
     *       比中档的 {@code recentActive(7)} <b>更贵</b>，与「短档省开销」的初衷相反 →
     *       改用 {@code recentActive(30)} 一次取、再按 kind 过滤（与中档同一条取数路径）；</li>
     *   <li><b>语义</b>：既然是近 30 天窗口，文案就不能叫「长期偏好」→ 改「近期偏好」（名副其实）。
     *       V3 实测：25 条 preference 里混有「卖出后小幅回补」这类**会过期的操作事实**，
     *       所以每条带日期，并写明「若与你刚说的不一致，以你刚说的为准」。</li>
     * </ul>
     */
    private String loadCoreMemory(String userId) {
        int limit = policy.coreMemoryMax();
        if (limit <= 0) return "";

        List<Memory> prefs = memoryService.recentActive(userId, CORE_MEMORY_LOOKBACK_DAYS).stream()
                .filter(m -> "preference".equals(m.kind()))
                .filter(m -> m.createdAt() != null)
                .filter(m -> m.summary() != null && !m.summary().isBlank())
                .sorted(Comparator.comparing(Memory::createdAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
        if (prefs.isEmpty()) {
            log.info("核心记忆：近 {} 天内没有可用偏好", CORE_MEMORY_LOOKBACK_DAYS);
            return "";
        }

        StringBuilder body = new StringBuilder();
        int budget = policy.coreMemoryMaxTokens() * 2;   // 与 estimateTokens 同口径：1 token ≈ 2 字符
        for (Memory m : prefs) {
            String line = "- " + m.createdAt().toLocalDate() + " "
                    + m.summary().replace("\n", " ").replace("\r", " ") + "\n";
            if (body.length() + line.length() > budget) break;
            body.append(line);
        }
        // 对抗审查 P3-2：预算被第一条就撑爆时，宁可不注入，也不要只留一个光秃秃的标题
        if (body.isEmpty()) {
            log.info("核心记忆：配额 {} 字符装不下任何一条，跳过注入", budget);
            return "";
        }
        log.info("核心记忆已加载 | 条数={} | 字数={}", prefs.size(), body.length());
        return "## 我对你的近期偏好（近 " + CORE_MEMORY_LOOKBACK_DAYS
                + " 天记下的；若与你刚说的不一致，以你刚说的为准）\n\n" + body;
    }

    /**
     * 收集场景特定贡献 + 所有全局上下文。
     */
    private String enrichFromContributors(String userId, String scene, String identityRef,
                                          ContentRecord record, Set<String> enabledPlugins) {
        StringBuilder sceneContext = new StringBuilder();

        // 1. 找场景特定贡献者（插件贡献者按 enabledPlugins 门控）
        for (ContextContributor contributor : contributors) {
            if (!contributorAllowed(userId, contributor, enabledPlugins)) continue;
            if (!contributor.isDefault() && contributor.supports(scene)) {
                String context = contributor.enrich(userId, identityRef, record);
                if (context != null && !context.isBlank()) {
                    sceneContext.append(context).append("\n\n");
                    log.info("使用场景贡献者: {} | scene={}", contributor.getClass().getSimpleName(), scene);
                }
            }
        }

        return sceneContext.toString().strip();
    }

    /**
     * 加载所有 Knowledge 源的知识上下文。
     * <p>
     * 调用每个 {@link KnowledgeSource} 的 globalContext() + enrich(scene)。
     * 位置在 memory 和 domain context 之间。
     */
    private String loadKnowledgeContext(String userId, String scene, Set<String> enabledPlugins,
                                        boolean injectTrading) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeSource source : knowledgeSources) {
            // 插件知识源（trading/learn）只注入启用该插件的用户（RFC 20260814 第二步门控）
            String plugin = pluginService.pluginForKnowledge(source.name());
            if (plugin != null && !enabledPlugins.contains(plugin)) {
                log.debug("Knowledge 跳过（插件未启用）: {} | userId={}", source.name(), userId);
                continue;
            }
            // 批 1 ④：v1 下**交易知识只在本次内容命中交易时注入**——生产实测生活/学习话题也背着
            // 512 字符「交易哲学」+ 501 字符「交易系统状态」，知识源整体占 prompt 46%（P90 87%）。
            // 只收紧 trading（证据最充分）；learn 保持原行为，不擅自扩大范围。
            if (!injectTrading && PluginRegistry.PLUGIN_TRADING.equals(plugin)) {
                log.debug("Knowledge 跳过（本次未命中交易领域）: {} | userId={}", source.name(), userId);
                continue;
            }
            try {
                String global = source.globalContext(userId);
                if (global != null && !global.isBlank()) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(global);
                    log.debug("Knowledge 全局上下文已加载: {}", source.name());
                }
                String enriched = source.enrich(userId, scene);
                if (enriched != null && !enriched.isBlank() && !enriched.equals(global)) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(enriched);
                    log.debug("Knowledge 场景上下文已加载: {} | scene={}", source.name(), scene);
                }
            } catch (Exception e) {
                log.warn("Knowledge 上下文加载失败: {} | {}", source.name(), e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * 贡献者门控（RFC 20260814 第二步）：插件贡献者只注入启用该插件的用户；基础服务（life/默认）放行。
     */
    private boolean contributorAllowed(String userId, ContextContributor contributor, Set<String> enabledPlugins) {
        String plugin = pluginService.pluginForContributor(contributor);
        return plugin == null || enabledPlugins.contains(plugin);
    }

    /**
     * 加载所有 Domain OS 的全局上下文（GlobalContext）。
     */
    private String loadGlobalContext(String userId, Set<String> enabledPlugins, boolean injectTrading) {
        StringBuilder sb = new StringBuilder();
        for (ContextContributor contributor : contributors) {
            if (contributor.isDefault()) continue;
            if (!contributorAllowed(userId, contributor, enabledPlugins)) continue;
            // 批 1 ④：v1 下交易域贡献者的全局上下文（如「交易系统状态」）同样只在命中交易时注入。
            // 生活话题不再无故背上 501 字符的交易状态（F5 分块实测）。
            if (!injectTrading
                    && PluginRegistry.PLUGIN_TRADING.equals(pluginService.pluginForContributor(contributor))) {
                log.debug("全局上下文跳过（本次未命中交易领域）: {} | userId={}",
                        contributor.getClass().getSimpleName(), userId);
                continue;
            }
            try {
                String globalCtx = contributor.globalContext(userId);
                if (globalCtx != null && !globalCtx.isBlank()) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(globalCtx);
                    log.debug("全局上下文已加载: {}", contributor.getClass().getSimpleName());
                }
            } catch (Exception e) {
                log.warn("全局上下文加载失败: {} | {}", contributor.getClass().getSimpleName(), e.getMessage());
            }
        }
        return sb.toString();
    }

    private String buildPrompt(String scene, String identityRef, ContentRecord record,
                               String cardContext, String relatedRecords,
                               String searchResults, String memorySummary,
                               String knowledgeContext, String domainContext, String globalContext,
                               Set<String> enabledPlugins) {
        String todayInfo = "%s %s".formatted(
                LocalDate.now().toString(),
                LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE)
        );

        StringBuilder prompt = new StringBuilder();
        prompt.append("处理一条新记录。\n\n");
        prompt.append(identityRef).append("\n");
        prompt.append("当前日期：").append(todayInfo).append("\n");
        prompt.append("场景：").append(scene).append("\n");

        // 卡片对话历史
        if (!cardContext.isBlank()) {
            prompt.append("\n").append(cardContext).append("\n");
        }

        // 标签关联历史记录
        if (!relatedRecords.isBlank()) {
            prompt.append("\n").append(relatedRecords).append("\n");
        }

        // 全文搜索结果（内容匹配的相关历史）
        if (!searchResults.isBlank()) {
            prompt.append("\n").append(searchResults).append("\n");
        }

        // 记忆摘要
        if (!memorySummary.isBlank()) {
            prompt.append("\n").append(memorySummary).append("\n");
        }

        // Knowledge 知识源（结构化知识：规则、战法、知识体系）
        if (knowledgeContext != null && !knowledgeContext.isBlank()) {
            prompt.append("\n").append(knowledgeContext).append("\n");
        }

        // 全局领域上下文（所有 Domain）
        if (!globalContext.isBlank()) {
            prompt.append("\n").append(globalContext).append("\n");
        }

        // 当前记录
        prompt.append("当前记录：\n")
                .append("---\n")
                .append("标题：").append(record.title()).append("\n")
                .append("标签：").append(String.join(", ", record.tags())).append("\n")
                .append("内容：\n").append(record.content()).append("\n")
                .append("---\n");

        // 场景特定领域上下文
        if (domainContext != null && !domainContext.isBlank()) {
            prompt.append("\n当前领域上下文：\n").append(domainContext).append("\n");
        }

        // 场景感知 Prompt
        if ("question".equals(scene)) {
            prompt.append("""

                    请你直接回答用户的问题，用自然、简洁的中文。

回答结束后，在末尾另起一行输出 JSON（不要包裹 markdown 代码块）：
{
  "summary": "3-5个词概括本次问答主题，避免人称代词，像标签一样简洁",
  "tags": ["标签1", "标签2"],
  "sentiment": "positive 或 negative 或 neutral",
  "domain": "%s",
  "actionable": true 或 false,
  "actionSuggestion": "需要后续操作时，用第二人称直接面向用户写建议（如「该休息了」）；否则写 null"
}

%s
%s
""".formatted(buildDomainEnum(enabledPlugins), buildDomainRules(enabledPlugins),
                    buildCapabilityContext(enabledPlugins)));
        } else {
            prompt.append("""

                    请分析这条记录，输出 JSON 格式（不要包裹 markdown 代码块）：
                    {
                      "summary": "3-5个词客观概括，不要人称代词（不用你/我/用户），像标签一样简洁",
                      "insight": "一句话客观理解，不要复述原文，避免人称代词",
                      "patterns": "（可选）如果这条记录揭示了用户的长期行为模式，输出数组，每项包含 content(模式描述) 和 confidence(0-1置信度)；否则不输出此字段",
                      "preferences": "（可选）如果这条记录揭示了用户的明确偏好，输出数组，每项包含 content(偏好描述) 和 confidence(0-1置信度)；否则不输出此字段",
                      "tags": ["标签1", "标签2", "标签3"],
                      "sentiment": "positive 或 negative 或 neutral",
                      "domain": "%s",
                      "actionable": true 或 false,
                      "actionSuggestion": "需要后续操作时，用第二人称直接面向用户写建议（如「该休息了」）；否则写 null",

%s
                    }
                    """.formatted(buildDomainEnum(enabledPlugins), buildDomainRules(enabledPlugins)));
        }

        return prompt.toString();
    }

    /**
     * D5：按启用插件收敛 domain 枚举（无插件用户 → 只 life）。
     * <p>
     * REVIEW P1-B1：返回**不带引号**的纯枚举（`life(生活)/trading(交易)`）——
     * 消费方各自显式包引号（buildPrompt 的 `"domain": "%s"`、DeepSeekAiClient 手拼），
     * 避免双重引号产生非法 JSON 模板。
     */
    private String buildDomainEnum(Set<String> enabledPlugins) {
        StringBuilder sb = new StringBuilder("life(生活)");
        if (enabledPlugins.contains(PluginRegistry.PLUGIN_TRADING)) sb.append("/trading(交易)");
        return sb.toString();
    }

    /**
     * D5：domain 判定规则由关键词常量拼接（REVIEW P2-2：单一真相源——
     * 与 detectDomainScene 的 TRADING_KEYWORDS 同源，杜绝确定性路由与 AI 判定矛盾）。
     */
    private String buildDomainRules(Set<String> enabledPlugins) {
        StringBuilder sb = new StringBuilder("domain判定规则（按优先级，只在本用户启用的插件间判定）：\n");
        if (enabledPlugins.contains(PluginRegistry.PLUGIN_TRADING)) {
            sb.append("- 内容涉及 ").append(String.join("、", TRADING_KEYWORDS)).append(" → trading\n");
        }
        sb.append("- 其他日常、想法、记录、心情、问题 → life\n");
        return sb.toString();
    }

    /**
     * 能力边界（2026-09-16「第一次见面」批）。
     * <p>
     * 新用户第一眼问的就是「你能干什么 / 你有什么特别的能力」。此时插件默认全关，
     * 模型若顺口承诺「我帮你盯持仓 / 我帮你把 B站 视频整理成卡片」，用户点下去只会拿到 403，
     * 第一印象当场崩掉。这里把「已开启 / 未开启」如实写进 prompt，并约束口径：
     * 已开启的才允许说「我可以帮你」，未开启的只能如实说「要单独开启」，
     * 且不得编造清单之外的能力（不吹牛 = 第一原则之外的诚信底线）。
     */
    private String buildCapabilityContext(Set<String> enabledPlugins) {
        List<String> on = new ArrayList<>(List.of(
                "记录（随手记，文字或图片，自动归档打标签）",
                "问答（基于你自己的记录、记忆作答）",
                "记忆（长期记住你的偏好和习惯）",
                "时间线 / 搜索 / 待办 / 今日简报"));
        List<String> off = new ArrayList<>();
        (enabledPlugins.contains(PluginRegistry.PLUGIN_TRADING) ? on : off)
                .add("交易（持仓、复盘、买点）");
        (enabledPlugins.contains(PluginRegistry.PLUGIN_LEARN) ? on : off)
                .add("学习（把链接、视频整理成能复习的卡片）");

        StringBuilder sb = new StringBuilder();
        sb.append("能力边界（回答「你能干什么」这类问题时必须严格遵守）：\n");
        sb.append("- 已经能用的：").append(String.join("、", on)).append("\n");
        if (!off.isEmpty()) {
            sb.append("- 还没开、现在用不了的：").append(String.join("、", off)).append("\n");
            sb.append("- 两类必须分清：能用的才可以说「我可以帮你」；用不了的只能如实说「这个要单独开启」，")
                    .append("不许说「我现在就能」，也不许编造上面清单之外的能力。\n");
        }
        return sb.toString();
    }
}
