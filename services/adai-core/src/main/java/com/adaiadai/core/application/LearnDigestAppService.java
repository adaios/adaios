package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardPatch;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LearnDigestAppService — learn 消化用例编排（RFC 20260829 learn 插件，V1 后端流水线）。
 * <p>
 * 喂入素材（字幕/链接原文）→ LLM 按 RFC 3.4 渐进式摘要模板结构化 → 卡片落
 * {@code data/{userId}/learn/{type}/{date}_{title}.md}。独立端点喂入（2026-09-06 用户拍板，
 * 仿截图入账先例）——不建记录、不沉淀记忆，是消化动作不是记录动作，不污染 Feed/时间线。
 * <p>
 * 降级（fail-visible）：LLM 失败/输出不可解析/缺标题 → 原始素材留存 learn/_raw/ + 抛
 * LearnException（400 人话），不产半成品卡片（对齐交易规则层 P0 教训）。
 * type 判定：请求显式 type 优先，否则 LLM 判；非法/缺失 → 回落 other（防越界值进持久化，
 * 卡片不丢；trade_related 由 LearnCard 构造器对非 trading 强制收敛 false）。
 */
@Service
public class LearnDigestAppService {

    private static final Logger log = LoggerFactory.getLogger(LearnDigestAppService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CARD_SYSTEM_PROMPT = """
            你是 AdaiOS 的学习消化助手。用户给你一份外部学习素材（视频字幕/文章原文/链接内容），
            请把它整理成个人知识卡片。卡片正文用中文（术语保留英文原文），忠实原文、可提炼不编造。

            只输出 JSON，不要任何其他文本或代码块标记：
            {"title":"≤30字的卡片标题","type":"ai|trading|other","tags":["2-5个标签"],
            "core_view":"核心观点（一句话，用自己的话）",
            "key_points":["2-6个关键要点，可带原文时间戳如 02:31，保留关键数字"],
            "questions":["1-3个存疑点或可讨论处"],
            "trade_related":false,"trade_note":""}

            type 判定：技术/AI/编程类内容 → ai；交易理念/方法/规则类内容 → trading；
            科普/人文/其他 → other。
            trade_related 仅当 type=trading 且素材给出了**具体可执行的交易规则或信号**时为 true
            （理念/心态/方法论不算）；trade_note 简述与已有规则的关系（互补/冲突/重复），无则空串。
            """;

    private final AiClient aiClient;
    private final LearnCardRepository repository;

    public LearnDigestAppService(AiClient aiClient, LearnCardRepository repository) {
        this.aiClient = aiClient;
        this.repository = repository;
    }

    /**
     * 消化素材为学习卡片并落盘。
     *
     * @param userId    用户
     * @param content   素材原文（字幕/文章/链接内容）
     * @param typeHint  显式类型（ai/trading/other，可空 = LLM 判定）
     * @param platform  来源平台（可空）
     * @param author    作者/UP 主（可空）
     * @param url       原文链接（可空）
     * @param published 原文发布日期（可空）
     * @return 落盘的卡片
     * @throws LearnException 卡片化失败（400 + 人话；素材已留存 _raw/）
     */
    public LearnCard digest(String userId, String content, String typeHint,
                            String platform, String author, String url, String published) {
        if (content == null || content.isBlank()) {
            throw new LearnException("素材内容不能为空");
        }
        if (typeHint != null && !typeHint.isBlank() && !LearnCard.isValidType(typeHint)) {
            throw new LearnException("类型仅支持 ai/trading/other，请重试");
        }
        String userPrompt = buildDigestPrompt(content, platform, author, url, published, typeHint);
        ContextPackage ctx = ContextPackage.simple(
                "learn", null, "学习消化", userPrompt, List.of(), userPrompt);
        AiTraceContext.set(userId, null, null, "learn_digest");

        String raw;
        try {
            raw = aiClient.generate(ctx, CARD_SYSTEM_PROMPT);
        } catch (Exception e) {
            log.warn("learn 卡片化 LLM 失败 | userId={} | {}", userId, e.getMessage());
            repository.saveRawSource(userId, content);
            throw new LearnException("AI 消化失败，原始素材已留存（learn/_raw/），可稍后重试：" + e.getMessage());
        }

        DigestResult parsed;
        try {
            parsed = parseDigest(raw);
        } catch (Exception e) {
            log.warn("learn 卡片化输出不可解析 | userId={} | {}", userId, e.getMessage());
            repository.saveRawSource(userId, content);
            throw new LearnException("AI 消化输出无法识别，原始素材已留存（learn/_raw/），可稍后重试");
        }
        if (parsed.title() == null || parsed.title().isBlank()) {
            repository.saveRawSource(userId, content);
            throw new LearnException("AI 消化未给出标题，原始素材已留存（learn/_raw/），可稍后重试");
        }

        // type：显式 hint > LLM 判 > other 兜底（越界值不落盘）
        String type = typeHint != null && !typeHint.isBlank()
                ? typeHint
                : (LearnCard.isValidType(parsed.type()) ? parsed.type() : LearnCard.TYPE_OTHER);
        if (!typeHintValid(typeHint) && !LearnCard.isValidType(parsed.type())) {
            log.warn("learn type 越界回落 other | userId={} | llmType={}", userId, parsed.type());
        }

        LearnCard card = new LearnCard(
                type, parsed.title(),
                platform, author, url, published,
                LocalDate.now(), LearnCard.STATUS_NEW,
                LearnCard.TYPE_TRADING.equals(type) && parsed.tradeRelated(),
                LearnCard.TYPE_TRADING.equals(type) ? parsed.tradeNote() : null,
                parsed.tags(),
                parsed.coreView(), parsed.keyPoints(), parsed.questions(),
                "");
        repository.save(userId, card);
        log.info("learn 卡片化完成 | userId={} | type={} | title={} | 要点 {} 条 | 疑问 {} 条",
                userId, card.type(), card.title(),
                card.keyPoints().size(), card.questions().size());
        return card;
    }

    private boolean typeHintValid(String typeHint) {
        return typeHint != null && !typeHint.isBlank();
    }

    /** 指定类型卡片列表（created 倒序）。 */
    public List<LearnCard> list(String userId, String type) {
        if (!LearnCard.isValidType(type)) return List.of();
        return repository.list(userId, type);
    }

    /** 单篇卡片（按 type + title）；不存在 → 业务异常（404 语义由调用方映射 400 人话）。 */
    public LearnCard detail(String userId, String type, String title) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) {
            throw new LearnException("卡片不存在：" + safeLabel(type, title));
        }
        return repository.find(userId, type, title)
                .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, title)));
    }

    /**
     * 复习状态流转（V2 2026-09-07 P2-learn8 + S-learn1 修复）：
     * 只允许 new→review→done 与回退 review→new / done→review（跳变 new→done、done→new 拒绝，
     * isValidTransition 在仓储执行）；进入 review（含 done→review 重进）由仓储写 review_at=today
     * （复习提醒按进入复习之日计时）。仅改 frontmatter，正文与手写「复述」段落原样保留（File First）。
     *
     * @throws LearnException 类型/状态非法（400 人话）或卡片不存在
     */
    public LearnCard changeStatus(String userId, String type, String title, String status) {
        if (!LearnCard.isValidType(type)) {
            throw new LearnException("类型仅支持 ai/trading/other，请重试");
        }
        if (title == null || title.isBlank()) {
            throw new LearnException("卡片标题不能为空");
        }
        if (!LearnCard.isValidStatus(status)) {
            throw new LearnException("复习状态仅支持 new/review/done");
        }
        return repository.updateStatus(userId, type, title, status, LocalDate.now());
    }

    /**
     * 编辑卡片正文（V2 对话流让阿呆改的后端支撑）：按 type+title 定位，patch 字段
     * null = 保留原值，非 null = 覆盖（含清空）。merge 与写盘在仓储锁内原子完成
     * （P2-learn6 并发 PATCH 不丢更新），手工未知 frontmatter 键/正文段保留（P2-learn7）。
     * type/title/created 由原卡继承不可改。返回更新后卡片。
     *
     * @throws LearnException 定位/入参非法或卡片不存在
     */
    public LearnCard edit(String userId, String type, String title, LearnCardPatch patch) {
        if (!LearnCard.isValidType(type)) {
            throw new LearnException("类型仅支持 ai/trading/other，请重试");
        }
        if (title == null || title.isBlank()) {
            throw new LearnException("卡片标题不能为空");
        }
        return repository.applyEdit(userId, type, title, patch == null ? new LearnCardPatch(null, null, null, null, null, null, null) : patch);
    }

    /** 资产树：learn 按 type 分组（只含已落盘卡片）。 */
    public Map<String, List<LearnCard>> tree(String userId) {
        return repository.tree(userId);
    }

    private String safeLabel(String type, String title) {
        String t = type == null || type.isBlank() ? "?" : type;
        String ti = title == null || title.isBlank() ? "(空标题)" : title;
        return t + "/" + ti;
    }

    /** 组装用户指令：素材 + 来源 + type 提示。 */
    private String buildDigestPrompt(String content, String platform, String author,
                                     String url, String published, String typeHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("请消化以下素材为学习卡片：\n\n");
        if (platform != null && !platform.isBlank()) {
            sb.append("来源平台：").append(platform).append("\n");
        }
        if (author != null && !author.isBlank()) {
            sb.append("作者/UP主：").append(author).append("\n");
        }
        if (url != null && !url.isBlank()) {
            sb.append("链接：").append(url).append("\n");
        }
        if (published != null && !published.isBlank()) {
            sb.append("发布日期：").append(published).append("\n");
        }
        if (typeHint != null && !typeHint.isBlank()) {
            sb.append("内容类型（用户已指定，据此组织卡片）：").append(typeHint).append("\n");
        }
        sb.append("\n素材正文：\n").append(content);
        return sb.toString();
    }

    // ── LLM 输出解析 ──

    private record DigestResult(String title, String type, List<String> tags,
                                String coreView, List<String> keyPoints,
                                List<String> questions, boolean tradeRelated, String tradeNote) {}

    private DigestResult parseDigest(String raw) throws Exception {
        String json = extractJson(raw);
        if (json == null) throw new IllegalStateException("AI 输出未包含 JSON");
        JsonNode node = MAPPER.readTree(json);
        String title = node.path("title").asText("").strip();
        String type = node.path("type").asText("").strip().toLowerCase();
        List<String> tags = stringArray(node, "tags");
        String coreView = node.path("core_view").asText("").strip();
        List<String> keyPoints = stringArray(node, "key_points");
        List<String> questions = stringArray(node, "questions");
        boolean tradeRelated = node.path("trade_related").asBoolean(false);
        String tradeNote = node.path("trade_note").asText("").strip();
        return new DigestResult(title, type, tags, coreView, keyPoints, questions, tradeRelated, tradeNote);
    }

    private List<String> stringArray(JsonNode node, String field) {
        List<String> list = new ArrayList<>();
        JsonNode arr = node.path(field);
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isTextual() && !n.asText().isBlank()) list.add(n.asText().strip());
            }
        }
        return list;
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        if (trimmed.startsWith("{")) return trimmed;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }
}
