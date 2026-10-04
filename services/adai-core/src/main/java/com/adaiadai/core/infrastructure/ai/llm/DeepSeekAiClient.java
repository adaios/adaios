package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.kernel.context.engine.ContextPackage.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * DeepSeekAiClient — DeepSeek API 实现的 AI 客户端。
 * <p>
 * 双模式：
 * <ul>
 *   <li>STATEMENT（conversationHistory 为空）: 分析模式，0.3 temp，JSON 输出</li>
 *   <li>QUESTION（conversationHistory 非空）: 对话模式，0.7 temp，多轮 messages</li>
 * </ul>
 */
@Component
public class DeepSeekAiClient implements AiClient, com.adaiadai.core.kernel.ai.StreamingAiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * 单次调用超时（2026-08-26 对齐前端 AI 超时 120s：单次 45s × 2 次尝试 + 0.6s 重试间隔 = 90.6s
     * < 120s，消除「前端先超时断开 → 后端仍在跑 → 用户重发 → 卡片重复」链条，REVIEW S-9）。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final int MAX_ATTEMPTS = 2;    // DeepSeek 偶发返回空内容/超时，重试 1 次（生产 08-14 反馈 brief 降级 2 行）
    private static final long RETRY_DELAY_MS = 600;

    /**
     * 意图分类的 system（2026-09-26，task-log「202 剩余」）：此前 {@code recognizeIntent} 走
     * {@link #buildSimpleBody(String, int, double)} 的**默认 system**——那是「分析一条个人记录、
     * 输出 JSON（summary/insight/patterns）」的分析指令，与「只判 ask / log」的分类任务语义相悖
     * （模型可能回一段 JSON，而调用方只做 {@code result.contains("ask")} → 意图识别失准）。
     */
    static final String INTENT_SYSTEM = """
            你是意图分类器。判断用户这句话是在向助手提问（ask），还是在陈述一件要记下来的事（log）。
            只回答一个词：ask 或 log。不要输出 JSON、不要解释、不要标点。""".strip();

    /**
     * 「偏向需要回复」版的分类器 system（D4，2026-09-30 用户拍板「宁可它多说一句」）。
     * <p>
     * ⚠️ **只配 {@link #recognizeIntentLeanAsk}（文本入口）使用**——媒体入口走 {@link #recognizeIntent}
     * 与上面的旧口径（对抗审查 P1-1：倾向会改变图片资产形态、并让超长配文从 log 变 400）。
     * <p>
     * 措辞要点（回应审查 Q1「放大点」）：① ask 侧给**可列举的信号**（提问/求助/征求看法/想接话），
     * 不写成「任何意味」；② 兜底保留「信号不足 → ask」，但**给 log 正例**把「明确记录」的窄口撑开
     * （否则模型会把日常记录也判 ask，费用与体感双升）；③ 例子里刻意包含一句**没有记录指令的情绪短句**
     * （判 ask）与一句**带记录指令的同类**（判 log），把边界钉在「有没有让我记/答的索取」上。
     */
    static final String INTENT_SYSTEM_LEAN_ASK = """
            你是意图分类器。判断用户这句话是在向助手提问（ask），还是在陈述一件要记下来的事（log）。
            判据（宁可选 ask，但别把明确的记录也判成 ask）：
            - 有索取 → ask：提问、求助、征求看法、要我评价/建议，或明显想让我接话；
            - 无索取 → log：第一人称、已发生、只是记下来（含明确的记录指令）。
            - 信号不足、看不出意图 → ask。
            参考例：
            - 「今天天气不错」「中午吃了碗面」 → log（明确的日常记录，无索取）
            - 「今天很开心帮我记一下」 → log（带明确的记录指令）
            - 「今天很开心」 → ask（无记录指令、信号不足）
            - 「我听了两只股票」 → ask（像是想接着说下去）
            只回答一个词：ask 或 log。不要输出 JSON、不要解释、不要标点。""".strip();

    /**
     * 生成正文的默认 system（同批）：{@code generate(ctx, null)} 此前落到分析指令（要求输出 JSON），
     * 与「生成正文」语义矛盾。调用方给了自定义 system 时以调用方为准。
     */
    static final String GENERATE_DEFAULT_SYSTEM = """
            你是阿呆的个人 AI 助手。用中文自然地写出用户要的正文；不要输出 JSON、
            不要写解释性的元信息（如「以下是…」「JSON 如下」）。""".strip();

    private final HttpClient httpClient;
    private final String apiKey;
    private final String apiUrl;
    /** 旗舰推理模型（复盘等深度场景，慢但推理强）。 */
    private final String modelPro;
    /** 轻量快模型（问答/记录/意图/简报等高频交互，无长思维链，快且不吃满 max_tokens）。 */
    private final String modelFlash;

    public DeepSeekAiClient(
            @Value("${DEEPSEEK_API_KEY:}") String apiKey,
            @Value("${DEEPSEEK_BASE_URL:https://api.deepseek.com}") String baseUrl,
            @Value("${adai.ai.model:deepseek-v4-pro}") String modelPro,
            @Value("${adai.ai.model-flash:deepseek-v4-flash}") String modelFlash
    ) {
        this.httpClient = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))  // 不走系统代理（Privoxy）
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.apiKey = apiKey;
        this.apiUrl = baseUrl + "/v1/chat/completions";
        this.modelPro = modelPro;
        this.modelFlash = modelFlash;

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DEEPSEEK_API_KEY 未设置，DeepSeekAiClient 将无法工作");
        } else {
            log.info("DeepSeekAiClient 初始化 | url={} | pro={} | flash={}",
                    this.apiUrl, this.modelPro, this.modelFlash);
        }
    }

    /**
     * 按调用来源路由模型（2026-08-26 模型分层，REVIEW S-10：scene() 全是 trading 不可用，
     * 用 AiTraceContext.source 区分）：复盘（trading_review）要推理质量走 pro，
     * 其余高频交互（brief / conversation / intent / media / question / record / retry /
     * learn_digest / learn_image / learn_repages / trading_advice / trading_parse /
     * trading_screenshot / trading_case_insight）走 flash。
     * <p>
     * 包级可见：供 DeepSeekAiClientTest 直接测路由。
     */
    String modelFor() {
        String source = AiTraceContext.source();
        if ("trading_review".equals(source)) {
            return modelPro;
        }
        return modelFlash;
    }

    @Override
    public AiUnderstanding understand(ContextPackage contextPackage) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("DEEPSEEK_API_KEY 未配置，无法调用 DeepSeek API");
            throw new RuntimeException("AI 未配置：缺少 API Key");
        }

        List<ChatMessage> history = contextPackage.conversationHistory();
        boolean isChat = history != null && !history.isEmpty();

        try {
            String requestBody = isChat
                    ? buildChatRequestBody(contextPackage)
                    : buildAnalysisRequestBody(contextPackage);

            log.info("[DeepSeek] 请求 model={} | 模式={} | tokens 预估={}",
                    modelFor(), isChat ? "CHAT" : "ANALYSIS", contextPackage.estimateTokens());

            String rawResponse = sendAndParse(requestBody, TIMEOUT);
            return LlmResponseParser.parse(rawResponse);

        } catch (java.net.http.HttpConnectTimeoutException e) {
            log.error("DeepSeek API 连接超时", e);
            throw new RuntimeException("AI 连接超时，请稍后重试", e);
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("DeepSeek API 请求超时", e);
            throw new RuntimeException("AI 请求超时，请稍后重试", e);
        } catch (Exception e) {
            log.error("DeepSeek API 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String generate(ContextPackage contextPackage, String systemPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("DEEPSEEK_API_KEY 未配置，无法调用 DeepSeek API");
            throw new RuntimeException("AI 未配置：缺少 API Key");
        }
        try {
            // 生成语义：自定义 system 引导正文格式，无 JSON 摘要指令；0.7 temp + 2048 tokens 适合结构化正文
            // generate 输出正文非 JSON，不开 json_mode
            String body = buildSimpleBody(contextPackage.prompt(), 8192, 0.7,
                    (systemPrompt != null && !systemPrompt.isBlank()) ? systemPrompt : GENERATE_DEFAULT_SYSTEM,
                    false);
            String content = sendAndParse(body, TIMEOUT);
            log.info("[DeepSeek] generate 响应 | model={} | 长度={}", modelFor(), content.length());
            return content;
        } catch (java.net.http.HttpConnectTimeoutException e) {
            log.error("DeepSeek API 连接超时", e);
            throw new RuntimeException("AI 连接超时，请稍后重试", e);
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("DeepSeek API 请求超时", e);
            throw new RuntimeException("AI 请求超时，请稍后重试", e);
        } catch (Exception e) {
            log.error("DeepSeek API 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式生成正文（REVIEW P2-用户2，2026-08-29：ai-calling-governance 批 2 聊天流式）。
     * <p>
     * body 复用 {@link #buildChatRequestBody}（完整上下文：身份/背景/记忆/多轮历史），
     * 加 {@code stream:true}；响应按 SSE（{@code data: {...}} 行）解析 delta.content 逐块回调。
     * 完整文本累计返回。失败抛 RuntimeException（调用方降级非流式重试一次）。
     * 聊天输出含 JSON 回执尾巴（buildChatRequestBody 的 system 指令），由前端流式渲染时剥离。
     */
    @Override
    public String streamGenerate(ContextPackage contextPackage, String systemPrompt,
                                 java.util.function.Consumer<String> onDelta) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("DEEPSEEK_API_KEY 未配置，无法流式调用 DeepSeek API");
            throw new RuntimeException("AI 未配置：缺少 API Key");
        }
        java.util.function.Consumer<String> sink = onDelta != null ? onDelta : s -> { };
        try {
            String requestBody = buildChatRequestBody(contextPackage);
            var node = MAPPER.readTree(requestBody);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("stream", true);
            // RFC 20260929 批 1 ⑤：显式索取 usage。DeepSeek 官方口径：不设置时 `[DONE]` 前最后一块
            // 也会带 usage；设置 include_usage 后所有块都带该字段（非最后一块为 null）。此处显式声明，
            // 是为了把 prompt_cache_hit_tokens / prompt_cache_miss_tokens 落进日志——此前**零解析**，
            // 「真实输入 token」与「缓存命中」在生产上完全不可见（F7）。
            var streamOptions = MAPPER.createObjectNode();
            streamOptions.put("include_usage", true);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("stream_options", streamOptions);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(node)))
                    // 流式长超时：生成期无新块即断（首块后 60s 无增量判超时——SseEmitter 侧同配）
                    .timeout(Duration.ofSeconds(120))
                    .build();
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("AI 流式调用失败 status=" + response.statusCode() + " " + err);
            }
            StringBuilder full = new StringBuilder();
            int[] usage = {-1, -1, -1, -1};   // prompt / cacheHit / cacheMiss / completion
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).strip();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    try {
                        var chunkNode = MAPPER.readTree(data);
                        var delta = chunkNode.path("choices").path(0).path("delta").path("content");
                        if (delta.isTextual()) {
                            String chunk = delta.asText();
                            full.append(chunk);
                            sink.accept(chunk);
                        }
                        // 批 1 ⑤：最后一块的 usage（include_usage 下其余块为 null）
                        var u = chunkNode.path("usage");
                        if (!u.isMissingNode() && !u.isNull()) {
                            usage[0] = u.path("prompt_tokens").asInt(-1);
                            usage[1] = u.path("prompt_cache_hit_tokens").asInt(-1);
                            usage[2] = u.path("prompt_cache_miss_tokens").asInt(-1);
                            usage[3] = u.path("completion_tokens").asInt(-1);
                        }
                    } catch (Exception ignored) {
                        // 跳过非 JSON 的 data 行（SSE 注释/心跳等）
                    }
                }
            }
            String result = full.toString();
            if (result.isBlank()) throw new RuntimeException("AI 流式返回空内容");
            log.info("[DeepSeek] 流式响应 | model={} | 长度={}", modelFor(), result.length());
            if (usage[0] >= 0) {
                log.info("[DeepSeek] usage | 模式=STREAM | prompt={} (缓存命中={} 未命中={}) | completion={}",
                        usage[0], usage[1], usage[2], usage[3]);
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("DeepSeek API 流式调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI 流式调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String recognizeIntent(String content) {
        return classifyIntent(content, false);
    }

    /**
     * D4（2026-09-30 用户拍板「宁可它多说一句」）：**文本入口专用**的偏向版。
     * 媒体入口不调用它——见 {@link AiClient#recognizeIntentLeanAsk} 的说明。
     */
    @Override
    public String recognizeIntentLeanAsk(String content) {
        return classifyIntent(content, true);
    }

    private String classifyIntent(String content, boolean leanAsk) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("AI 未配置：缺少 API Key");
        }
        try {
            String prompt = leanAsk ? intentPromptLeanAsk(content) : intentPrompt(content);
            String system = leanAsk ? INTENT_SYSTEM_LEAN_ASK : INTENT_SYSTEM;
            // v4-pro 推理模型：50 tokens 会被思维链吃满→content 空（08-14 连调实锤）→ 提到 512
            // 意图识别返回裸词 ask/log（非 JSON），不开 json_mode（json_object 会强制输出 JSON 结构破坏裸词）
            String body = buildSimpleBody(prompt, 512, leanAsk ? 0.1 : 0.3, system, false);
            String result = sendAndParse(body, Duration.ofSeconds(15)).strip().toLowerCase();
            if (result.contains("ask")) return "ask";
            return "log";
        } catch (Exception e) {
            throw new RuntimeException("AI 意图识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 意图分类的 user prompt（包级可见：供 {@code DeepSeekAiClientTest} 直接断言判据倾向，
     * 与 {@link #INTENT_SYSTEM} 一起钉住 D4「宁可它多说一句」的口径）。
     * <p>
     * 2026-09-30（用户拍板 D4 = A「说出来」＋ 宁可多说一句）：把「模棱两可」显式划到 ask 一侧。
     * 动因——带卡片的请求里 **47%** 被判 log，用户「说了话，它一个字都不回」，
     * 体感就是「对话模式丢了上下文」（REVIEW P2-对话3）。
     */
    static String intentPrompt(String content) {
        return """
                判断以下用户输入是否需要 AI 回复。
                需要回复（提问、命令、要求等） → 返回 ask
                不需要回复（纯记录、日记、随想） → 返回 log
                只需返回一个词：ask 或 log。
                输入：%s
                结果：""".formatted(content);
    }

    /**
     * 「偏向需要回复」版的 user prompt——**只配 {@link #recognizeIntentLeanAsk}（文本入口）**。
     * 与 {@link #INTENT_SYSTEM_LEAN_ASK} 一起构成 D4 口径；媒体入口仍走 {@link #intentPrompt}。
     */
    static String intentPromptLeanAsk(String content) {
        return """
                判断以下用户输入是否需要 AI 回复。
                有索取（提问、求助、征求看法、想让我接话） → 返回 ask
                无索取、只是记下来（含「帮我记一下」这类记录指令） → 返回 log
                信号不足、拿不准 → 返回 ask。
                只需返回一个词：ask 或 log。
                输入：%s
                结果：""".formatted(content);
    }

    /**
     * 发送请求 + 解析内容，对可重试错误（空内容/连接超时/请求超时）自动重试一次。
     * 根因：DeepSeek 偶发返回空内容（生产 08-14 反馈，brief 直接降级成 2 行），
     * 瞬时问题重试即可恢复；非瞬时错误（业务/格式）不重试直接抛。
     */
    private String sendAndParse(String requestBody, Duration timeout) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(timeout)
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String raw = parseChatCompletion(response.body());
                // 批 1 ⑤：真实 token 用量（含缓存命中）——此前零解析，生产上看不到输入花了多少
                logUsage(response.body());
                log.info("[DeepSeek] 响应 received | status={} | 长度={}", response.statusCode(), raw.length());
                return raw;
            } catch (Exception e) {
                boolean retryable = e instanceof java.net.http.HttpConnectTimeoutException
                        || e instanceof java.net.http.HttpTimeoutException
                        || (e.getMessage() != null && e.getMessage().contains("返回空内容"));
                if (attempt < MAX_ATTEMPTS && retryable) {
                    log.warn("[DeepSeek] 第 {} 次调用失败（{}），{}ms 后重试", attempt, e.getMessage(), RETRY_DELAY_MS);
                    Thread.sleep(RETRY_DELAY_MS);
                    continue;
                }
                last = e;
                if (!retryable) break;
            }
        }
        if (last instanceof RuntimeException re) throw re;
        throw new RuntimeException("AI 调用失败: " + (last != null ? last.getMessage() : "unknown"), last);
    }

    // ── Body 构建 ──

    /**
     * ANALYSIS 模式（STATEMENT 场景）：
     * 单条 user message + JSON 输出指令，0.3 temperature。
     * <p>
     * brief 场景例外：使用中文温暖问候的系统 prompt，0.7 temperature。
     */
    private String buildAnalysisRequestBody(ContextPackage ctx) throws Exception {
        if ("brief".equals(ctx.scene())) {
            // brief 是问候语正文非 JSON，不开 json_mode
            return buildSimpleBody(ctx.prompt(), 4096, 0.7,
                    "你是阿呆的个人 AI 助手。用中文回复，语气温暖。生成温暖的问候语。不要输出 JSON。不要使用 emoji 和 unicode 转义码。", false);
        }
        // STATEMENT 分析要结构化 JSON（summary/tags/domain）→ json_object 硬约束
        // v4-pro 推理模型思维链长：1024 会吃满致 content 空（08-15 连调实锤理解降级）→ 8192
        return buildSimpleBody(ctx.prompt(), 8192, 0.3, null, true);
    }

    /**
     * CHAT 模式（QUESTION 场景）：多轮 messages，0.7 temperature。
     * <p>
     * 两种装配（RFC 20260929 批 1 ④ 引入分叉，由 {@link ContextPackage#stableSystem()} 是否为 null 判定）：
     * <ul>
     *   <li><b>v1</b>（{@code stableSystem != null}）：**唯一一条 system** = 稳定前缀 + 输出契约；
     *       对话历史居中；**每轮变化的参考（记忆/相关记录/检索）拼到最后一条 user**——
     *       于是前缀逐轮稳定（DeepSeek「缓存前缀单元」可命中），也规避多条 system 的兼容性差异
     *       （Gemini 只取最后一条、Qwen/Mistral 直接报错）。</li>
     *   <li><b>legacy</b>（null）：3 条 system（角色 / 背景 / 上下文）+ 历史——与批 1 之前逐字一致，
     *       保留用于灰度对比与回滚。</li>
     * </ul>
     * 对话历史已由调用方 append 本轮用户输入（{@code ensureCardWithUserTurn}），故末条即当前问句。
     * <p>
     * 包级可见：供 {@code DeepSeekAiClientTest} 直接断言 messages 结构（system 条数与动态内容位置），
     * 与 {@link #parseChatCompletion} 同一惯例。
     */
    String buildChatRequestBody(ContextPackage ctx) throws Exception {
        var root = MAPPER.createObjectNode();
        root.put("model", modelFor());
        root.put("max_tokens", 8192);
        root.put("temperature", 0.7);

        var messages = MAPPER.createArrayNode();
        List<ChatMessage> history = ctx.conversationHistory();

        if (ctx.stableSystem() != null) {
            // ── v1 装配（批 1 ④）──
            String systemContent = ctx.stableSystem() + "\n\n" + chatOutputContract(ctx.domainEnum());
            var systemMsg = MAPPER.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemContent);
            messages.add(systemMsg);

            String dynamicRefs = buildDynamicRefs(ctx);
            appendHistoryWithDynamicTail(messages, history, dynamicRefs, ctx.recordContent());

            // V5（REVIEW P2-对话2）：把**实际装配摘要**落日志。ai-log 记的是 `ctx.prompt()`，
            // 而 v1 下 prompt 并不是发送内容（检索/知识改由 relatedRefs 下发）——
            // 只看 ai-log 会得出「阿呆看到了交易知识」的假象，排查必须能对上真正发出的结构。
            log.info("[DeepSeek] v1 装配 | system=1条({}字符) | 历史={}条 | 动态参考={}字符 | 当前问句已并入末条 user",
                    systemContent.length(), history.size(), dynamicRefs.length());
        } else {
            // ── legacy 装配（批 1 之前的行为，逐字保留）──
            var systemMsg = MAPPER.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content",
                    "你是阿呆的个人 AI 助手。用中文回复，语气温暖。" + chatOutputContract(ctx.domainEnum()));
            messages.add(systemMsg);

            // 背景知识：作为单独的 system 消息（model 在 system prompt 之后读取，
            // 但不会把背景知识当成"自己要说的内容"）
            String background = buildBackground(ctx);
            if (background != null) {
                var bgMsg = MAPPER.createObjectNode();
                bgMsg.put("role", "system");
                bgMsg.put("content", background);
                messages.add(bgMsg);
            }

            // 组装上下文（全局领域、知识源、记忆等）：从 prompt 中提取不包含当前记录的上下文部分
            String context = buildContextFromPrompt(ctx);
            if (context != null) {
                var ctxMsg = MAPPER.createObjectNode();
                ctxMsg.put("role", "system");
                ctxMsg.put("content", context);
                messages.add(ctxMsg);
            }

            if (history.isEmpty()) {
                log.warn("chat 模式但没有历史记录，回退到普通 prompt");
                var fallbackMsg = MAPPER.createObjectNode();
                fallbackMsg.put("role", "user");
                fallbackMsg.put("content", ctx.recordContent());
                messages.add(fallbackMsg);
            } else {
                for (ChatMessage msg : history) {
                    var histMsg = MAPPER.createObjectNode();
                    histMsg.put("role", msg.role());
                    histMsg.put("content", msg.content());
                    messages.add(histMsg);
                }
            }
        }

        root.set("messages", messages);
        return MAPPER.writeValueAsString(root);
    }

    /** CHAT 模式的输出契约（JSON 回执指令）——v1 与 legacy 共用同一段文本，避免两处漂移。 */
    private static String chatOutputContract(String domainEnum) {
        return "回复结束后在末尾另起一行输出 JSON（不要包裹 markdown 代码块）：\n"
            + "{\n"
            + "  \"summary\": \"3-5个词概括本次问答主题，避免人称代词，像标签一样简洁\",\n"
            + "  \"tags\": [\"标签1\", \"标签2\"],\n"
            + "  \"sentiment\": \"positive 或 negative 或 neutral\",\n"
            + "  \"domain\": \"" + domainEnum + "\",\n"
            + "  \"actionable\": true 或 false,\n"
            + "  \"actionSuggestion\": \"需要后续操作写建议，否则写 null\"\n"
            + "}\n"
            + "不要使用 emoji 和 unicode 转义码。";
    }

    /** v1 的动态参考（记忆 + 相关历史 + 检索）：relatedRefs 中非空的部分（v1 下不含对话历史文本）。 */
    private String buildDynamicRefs(ContextPackage ctx) {
        if (ctx.relatedRefs() == null || ctx.relatedRefs().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String ref : ctx.relatedRefs()) {
            if (ref == null || ref.isBlank()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(ref.strip());
        }
        return sb.toString();
    }

    /**
     * v1 的 messages 组装：历史照旧，**动态参考拼到最后一条 user（当前问句）之前**。
     * <p>
     * 排在最后是有意的——它是每轮都变的内容，放前面会破坏缓存前缀；同时明确标注
     * 「与问题冲突时以问题为准」，把「背景 vs 当前」的优先级说清。
     */
    private void appendHistoryWithDynamicTail(com.fasterxml.jackson.databind.node.ArrayNode messages,
                                              List<ChatMessage> history,
                                              String dynamicRefs, String recordContent) {
        if (history.isEmpty()) {
            log.warn("chat 模式但没有历史记录，回退到普通 prompt");
            var fallbackMsg = MAPPER.createObjectNode();
            fallbackMsg.put("role", "user");
            fallbackMsg.put("content", dynamicRefs.isBlank() ? recordContent
                    : dynamicRefs + "\n\n" + recordContent);
            messages.add(fallbackMsg);
            return;
        }

        int lastIdx = history.size() - 1;
        for (int i = 0; i < lastIdx; i++) {
            var histMsg = MAPPER.createObjectNode();
            histMsg.put("role", history.get(i).role());
            histMsg.put("content", history.get(i).content());
            messages.add(histMsg);
        }

        ChatMessage last = history.get(lastIdx);
        if (!"user".equals(last.role())) {
            // 兜底：末条不是用户输入（异常装配）→ 原样保留，参考另起一条 user
            var lastMsg = MAPPER.createObjectNode();
            lastMsg.put("role", last.role());
            lastMsg.put("content", last.content());
            messages.add(lastMsg);
            if (!dynamicRefs.isBlank()) {
                var refMsg = MAPPER.createObjectNode();
                refMsg.put("role", "user");
                refMsg.put("content", "（本次参考）\n" + dynamicRefs);
                messages.add(refMsg);
            }
            return;
        }

        var userMsg = MAPPER.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", dynamicRefs.isBlank() ? last.content()
                : "（本次参考，供你判断用；与下面的问题冲突时以问题为准）\n" + dynamicRefs
                  + "\n\n" + last.content());
        messages.add(userMsg);
    }

    /**
     * 构建背景知识文本（单独作为一条 system 消息，不含 AI 角色指令）。
     */
    private String buildBackground(ContextPackage ctx) {
        StringBuilder sb = new StringBuilder();

        // 相关历史记录 + 记忆回读
        if (ctx.relatedRefs() != null && !ctx.relatedRefs().isEmpty()) {
            for (String ref : ctx.relatedRefs()) {
                if (ref != null && !ref.isBlank()) {
                    sb.append(ref.strip()).append("\n");
                }
            }
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 从 prompt 中提取上下文部分（身份、日期、全局领域、知识、记忆等），
     * 排除"当前记录"和最终指令，作为 system 消息供 CHAT 模式使用。
     */
    private String buildContextFromPrompt(ContextPackage ctx) {
        if (ctx.prompt() == null || ctx.prompt().isBlank()) return null;

        String prompt = ctx.prompt();
        // 截取到"当前记录："之前的部分，因为后面是本次用户输入和指令
        int cutoff = prompt.indexOf("\n当前记录：");
        if (cutoff < 0) return null;

        String contextPart = prompt.substring(0, cutoff).strip();
        return contextPart.isEmpty() ? null : contextPart;
    }

    /**
     * 从 identityRef 文本中提取用户称呼。
     */
    private String extractName(String identityRef) {
        if (identityRef == null || identityRef.isBlank()) return null;
        // 匹配 "- 称呼：xxx" 或 "用户身份摘要：xxx" 后面的名字
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("称呼[：:]\\s*(\\S+)").matcher(identityRef);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 简单的单条 prompt 请求体（用于 STATEMENT 分析和意图识别）。
     */
    private String buildSimpleBody(String prompt, int maxTokens, double temperature) throws Exception {
        return buildSimpleBody(prompt, maxTokens, temperature, null, false);
    }

    /**
     * 带自定义 system prompt 的单条请求体。
     *
     * @param jsonMode 是否开启 DeepSeek 原生 response_format=json_object（2026-08-26：
     *                 模型分层后 flash 也吃思维链，prompt 仅说「只输出JSON」不足以保证格式，
     *                 json_object 硬约束消灭「未找到 JSON 降级」；实测 v4-flash 支持）
     */
    private String buildSimpleBody(String prompt, int maxTokens, double temperature,
                                   String systemContent, boolean jsonMode) throws Exception {
        var root = MAPPER.createObjectNode();
        root.put("model", modelFor());
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);
        if (jsonMode) {
            var rf = MAPPER.createObjectNode();
            rf.put("type", "json_object");
            root.set("response_format", rf);
        }

        var messages = MAPPER.createArrayNode();

        var systemMsg = MAPPER.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContent != null ? systemContent : """
                分析一条个人记录，输出JSON。summary用3-5个词简短概括，不要完整句子；insight用一句话客观概括，有信息增量，不要复述原文，避免人称代词。用tags数组标注关键词标签；用domain字段判定所属领域(life/trading之一，以记录内容为准，越界值会被系统修正)。
                如果记录揭示了用户的长期行为模式或明确偏好，请在patterns/preferences数组中输出，每项包含content和confidence(0-1)。
                只输出JSON，不要包裹markdown。
                """.strip());
        messages.add(systemMsg);

        var userMsg = MAPPER.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);

        root.set("messages", messages);
        return MAPPER.writeValueAsString(root);
    }

    /** 包级可见：供 DeepSeekAiClientTest 直接测解析（reasoning_content 兜底回归）。 */
    String parseChatCompletion(String responseBody) throws Exception {
        JsonNode root = MAPPER.readTree(responseBody);

        // 检查 API 错误
        if (root.has("error")) {
            String errorMsg = root.get("error").path("message").asText("未知错误");
            log.error("DeepSeek API 返回错误: {}", errorMsg);
            throw new RuntimeException("DeepSeek API 错误: " + errorMsg);
        }

        // 提取 content
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException("DeepSeek API 返回异常: 无 choices");
        }

        String content = choices.get(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            // deepseek-v4-pro 是推理模型：先写 reasoning_content（思维链）再写 content（最终答案）。
            // max_tokens 被思维链吃满时 content 空、finish_reason=length（08-14/15 连调实锤）。
            // 注意：reasoning_content 是思考过程不是答案，不能回退当结果——喂给 JSON 解析器会污染。
            // 正解是提高各路径 max_tokens 让思维链 + 答案都落盘；此处仍报"空内容"走 sendAndParse 重试。
            String reasoning = choices.get(0).path("message").path("reasoning_content").asText("");
            log.warn("[DeepSeek] content 为空（reasoning={} 字符）——疑似 max_tokens 被思维链吃满", reasoning.length());
            throw new RuntimeException("DeepSeek API 返回空内容");
        }

        return content;
    }

    /**
     * 解析并记录 token 用量（RFC 20260929 批 1 ⑤）。
     * <p>
     * 此前**完全没有解析 usage** —— 所以「真实输入 token」与「上下文缓存命中」在生产上不可见（F7）。
     * DeepSeek 的 usage：{@code prompt_tokens}（= hit + miss）、{@code prompt_cache_hit_tokens}、
     * {@code prompt_cache_miss_tokens}、{@code completion_tokens}、{@code total_tokens}。
     * 缓存命中与未命中的**价差约 50 倍**（flash 命中 0.04 元/百万 vs 未命中 2 元/百万），
     * 这也是「顺序摆对（稳定前缀在前）」的直接收益指标。
     * <p>
     * 解析失败只记 debug——**绝不让观测逻辑影响主流程**。
     */
    private void logUsage(String responseBody) {
        try {
            var usage = MAPPER.readTree(responseBody).path("usage");
            if (usage.isMissingNode() || usage.isNull()) return;
            log.info("[DeepSeek] usage | 模式=SYNC | prompt={} (缓存命中={} 未命中={}) | completion={} | total={}",
                    usage.path("prompt_tokens").asInt(-1),
                    usage.path("prompt_cache_hit_tokens").asInt(-1),
                    usage.path("prompt_cache_miss_tokens").asInt(-1),
                    usage.path("completion_tokens").asInt(-1),
                    usage.path("total_tokens").asInt(-1));
        } catch (Exception e) {
            log.debug("usage 解析跳过: {}", e.getMessage());
        }
    }
}
