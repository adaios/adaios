package com.adaiadai.core.domain.learn;

/**
 * LearnCard — 学习卡片（RFC 20260829 learn 插件）。
 * <p>
 * File First：每张卡片 = {@code data/{userId}/learn/{type}/{topic}/NN-{slug}.md}
 * （frontmatter 元数据 + 正文段），与 Mac 侧 DSH 技能 `learn-digest` 的产物**同一契约**
 * （2026-09-12 结构统一批：主题目录归档，两个写入方不再各写一套结构）。
 * 多用户按 userId 分目录（对齐 trading）。
 * <p>
 * type：ai（技术）| trading（交易）| other（科普/人文）——卡片分类，非插件 domain 收敛对象
 * （learn 不进 life/trading/project 收敛，2026-09-06 拍板）。trade_related 仅 type=trading
 * 内容有意义（是否含可执行交易规则/信号）；V1 只记录不联动规则库。
 *
 * @param type         卡片分类（ai/trading/other）
 * @param title        标题（LLM 从素材提炼，同日同标题幂等键）
 * @param platform     来源平台（bilibili/youtube/web…，可空）
 * @param author       来源作者/UP 主（可空）
 * @param url          原文链接（可空）
 * @param published    原文发布日期 yyyy-MM-dd（可空）
 * @param created      消化日期（服务器日期）
 * @param status       复习状态（new/review/done，V2 复习流转）
 * @param tradeRelated 是否涉及可执行交易规则（仅 type=trading 有意义）
 * @param tradeNote    交易备注：与 R 规则的关系（V1 仅记录，可空）
 * @param tags         标签列表
 * @param coreView     核心观点（一句话）
 * @param keyPoints    关键要点列表（可带时间戳，如 "02:31 要点"）
 * @param questions    我的疑问列表（存疑点，可触发后续讨论）
 * @param retell       复述段（24h 内自己写 100-200 字——消化关键；AI 不代写，
 *                     V2 编辑/对话流让阿呆改 后可填充）
 * @param reviewAt     进入 review 队列的日期（V2 S-learn1 修复 2026-09-07：复习提醒按它计时，
 *                     非消化日 created；review 卡才非空）
 * @param remindedAt   最近一次复习提醒推送日期（V2 S-learn1：节流——同卡 7 天内不重复推，
 *                     防「搁置卡每晚 nag」）
 * @param topic        主题目录名（2026-09-12 结构统一批）：卡片落
 *                     {@code learn/{type}/{topic}/NN-{slug}.md}——与 Mac 侧技能产物**同一契约**
 *                     （主题维度归档，多源同主题归并）；空 → {@link #DEFAULT_TOPIC}
 * @param writable     是否本产品产出的卡（可读写）。false = 别处（Mac 上技能）整理的手工卡，
 *                     只读：列表/全文照常看到，写入口统一人话拒绝
 */
public record LearnCard(
        String type,
        String title,
        String platform,
        String author,
        String url,
        String published,
        java.time.LocalDate created,
        String status,
        boolean tradeRelated,
        String tradeNote,
        java.util.List<String> tags,
        String coreView,
        java.util.List<String> keyPoints,
        java.util.List<String> questions,
        String retell,
        java.time.LocalDate reviewAt,
        java.time.LocalDate remindedAt,
        String topic,
        boolean writable) {

    public static final String TYPE_AI = "ai";
    public static final String TYPE_TRADING = "trading";
    public static final String TYPE_OTHER = "other";
    public static final String STATUS_NEW = "new";
    public static final String STATUS_REVIEW = "review";
    public static final String STATUS_DONE = "done";
    /** 主题目录缺省名（LLM 未给出主题时归到这里，界面上看得见、可再编辑归类）。 */
    public static final String DEFAULT_TOPIC = "未归类";

    /** 15 参便捷构造（reviewAt/remindedAt/topic 缺省）——兼容 V1 构造点/测试，不破坏调用。 */
    public LearnCard(String type, String title, String platform, String author, String url, String published,
                     java.time.LocalDate created, String status, boolean tradeRelated, String tradeNote,
                     java.util.List<String> tags, String coreView, java.util.List<String> keyPoints,
                     java.util.List<String> questions, String retell) {
        this(type, title, platform, author, url, published, created, status, tradeRelated, tradeNote,
                tags, coreView, keyPoints, questions, retell, null, null, null, true);
    }

    /** 17 参便捷构造（V2 复习计时字段）——topic 缺省、writable=true。 */
    public LearnCard(String type, String title, String platform, String author, String url, String published,
                     java.time.LocalDate created, String status, boolean tradeRelated, String tradeNote,
                     java.util.List<String> tags, String coreView, java.util.List<String> keyPoints,
                     java.util.List<String> questions, String retell,
                     java.time.LocalDate reviewAt, java.time.LocalDate remindedAt) {
        this(type, title, platform, author, url, published, created, status, tradeRelated, tradeNote,
                tags, coreView, keyPoints, questions, retell, reviewAt, remindedAt, null, true);
    }

    /** 18 参便捷构造（含 topic，writable=true）。 */
    public LearnCard(String type, String title, String platform, String author, String url, String published,
                     java.time.LocalDate created, String status, boolean tradeRelated, String tradeNote,
                     java.util.List<String> tags, String coreView, java.util.List<String> keyPoints,
                     java.util.List<String> questions, String retell, String topic) {
        this(type, title, platform, author, url, published, created, status, tradeRelated, tradeNote,
                tags, coreView, keyPoints, questions, retell, null, null, topic, true);
    }

    public LearnCard {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("卡片标题不能为空");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("卡片类型不能为空");
        }
        if (created == null) {
            throw new IllegalArgumentException("消化日期不能为空");
        }
        tags = tags == null ? java.util.List.of() : tags;
        keyPoints = keyPoints == null ? java.util.List.of() : keyPoints;
        questions = questions == null ? java.util.List.of() : questions;
        status = status == null ? STATUS_NEW : status;
        retell = retell == null ? "" : retell.strip();
        // 语义收敛：非交易内容不标 trade_related（防 LLM 幻觉把理念类标成规则）
        if (!TYPE_TRADING.equals(type)) {
            tradeRelated = false;
            tradeNote = null;
        }
        topic = (topic == null || topic.isBlank()) ? DEFAULT_TOPIC : topic.strip();
    }

    /** 主题目录名（文件安全：去路径分隔符/控制符，防逃逸；空 → {@link #DEFAULT_TOPIC}）。 */
    public static String topicDir(String topic) {
        if (topic == null || topic.isBlank()) return DEFAULT_TOPIC;
        String cleaned = topic.strip()
                // 控制符（含 \u0000）会让路径解析直接抛异常（对抗审查 P3 2026-09-12 实测 500）
                .replaceAll("[\\p{Cntrl}]", "")
                .replace("\n", " ").replace("\r", " ")
                .replaceAll("[\\\\/:*?\"<>|#]", "-")
                .replaceAll("\\s+", "-");
        cleaned = cleaned.replaceAll("-{2,}", "-").replaceAll("^-+", "").replaceAll("^[.]+", "").strip();
        if (cleaned.isBlank() || cleaned.matches("[-.]+")) return DEFAULT_TOPIC;
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    /** 返回 topic/writable 被替换的新卡（仓储读盘时标注来源与可写性）。 */
    public LearnCard withTopic(String newTopic) {
        return new LearnCard(type, title, platform, author, url, published, created, status, tradeRelated,
                tradeNote, tags, coreView, keyPoints, questions, retell, reviewAt, remindedAt, newTopic, writable);
    }

    /** 返回 writable 被替换的新卡。 */
    public LearnCard withWritable(boolean newWritable) {
        return new LearnCard(type, title, platform, author, url, published, created, status, tradeRelated,
                tradeNote, tags, coreView, keyPoints, questions, retell, reviewAt, remindedAt, topic, newWritable);
    }

    /** 来源展示标签（主题 README 索引用）：平台 · 作者，都没有 → 本地素材。 */
    public String sourceLabel() {
        String p = platform == null ? "" : platform.strip();
        String a = author == null ? "" : author.strip();
        if (!p.isEmpty() && !a.isEmpty()) return p + " · " + a;
        if (!p.isEmpty()) return p;
        if (!a.isEmpty()) return a;
        return "本地素材";
    }

    /** 合法类型。 */
    public static boolean isValidType(String type) {
        return TYPE_AI.equals(type) || TYPE_TRADING.equals(type) || TYPE_OTHER.equals(type);
    }

    /** 合法复习状态（new → review → done）。 */
    public static boolean isValidStatus(String status) {
        return STATUS_NEW.equals(status) || STATUS_REVIEW.equals(status) || STATUS_DONE.equals(status);
    }

    /**
     * 状态流转合法性（V2 P2-learn8 修复 2026-09-07）：只允许相邻流转
     * new→review、review→done；允许回退 review→new（误标纠偏）、done→review（想再看一遍）；
     * 禁止跳变 new→done（跳过复习队列）、done→new（跳过消化确认）。幂等（from==to）放行。
     */
    public static boolean isValidTransition(String from, String to) {
        if (from == null || to == null) return false;
        if (from.equals(to)) return true;
        return (STATUS_NEW.equals(from) && STATUS_REVIEW.equals(to))
                || (STATUS_REVIEW.equals(from) && STATUS_DONE.equals(to))
                || (STATUS_REVIEW.equals(from) && STATUS_NEW.equals(to))
                || (STATUS_DONE.equals(from) && STATUS_REVIEW.equals(to));
    }

    /** 标题 → 文件安全片段（learn 文件名 date_{stem}.md；learn_card_id 回链同口径复用，P1-learn1 修复）。 */
    public static String fileStem(String title) {
        if (title == null || title.isBlank()) return "untitled";
        String cleaned = title
                .replaceAll("[\\p{Cntrl}]", "")   // 控制符（含 \u0000）不进文件名
                .replace("\n", " ").replace("\r", " ")
                .replaceAll("[\\\\/:*?\"<>|#]", "-")
                .replaceAll("\\s+", " ").strip();
        // 防路径逃逸：. 与 - 打头、连续横线收敛、纯横线/纯点归一 untitled
        cleaned = cleaned.replaceAll("^-+", "").replaceAll("^[.]+", "")
                .replaceAll("-{2,}", "-").strip();
        if (cleaned.isBlank() || cleaned.matches("[-.]+")) return "untitled";
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }
}
