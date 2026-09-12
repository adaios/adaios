package com.adaiadai.core.kernel.knowledge;

import com.adaiadai.core.kernel.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LearnKnowledgeSource — 学习笔记知识源（RFC 20260829 learn 插件，L2 问答召回）。
 * <p>
 * 把用户消化过的学习卡片（{@code data/{userId}/learn/{type}/{date}_{title}.md}）注入问答上下文：
 * 你问「上次讲 RAG 那篇说了啥」，阿呆能引用你自己消化过的那篇作答，而不是泛泛而谈——
 * 这是「留存」的最终意义：存了能被想起来。
 * <p>
 * 设计（对齐 trading/life 知识源范式）：
 * <ul>
 *   <li>按用户读自己 learn/ 目录（多用户 File First），无文件/无卡片 → 返回空（不注入）</li>
 *   <li>门控：name()=learn → PluginRegistry.pluginForKnowledge 映射 learn 插件——
 *       ContextEngine 按 enabledPlugins 过滤，未启用 learn 插件的用户零注入</li>
 *   <li>learn 不进 domain 收敛（type 仅文件分类），因此场景判定只对 question/decision
 *       注入最近卡片（阅读/对话时被想起）；statement/log 不注入防上下文噪音</li>
 *   <li>轻量解析：只取 frontmatter title + ## 核心观点 首行（注入用摘要），
 *       不做完整 md 解析（浏览走 LearnController）；损坏文件跳过</li>
 *   <li>上限：最近 {@link #MAX_CARDS} 篇，单篇核心观点截断，token 克制</li>
 * </ul>
 */
@Component
public class LearnKnowledgeSource implements KnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(LearnKnowledgeSource.class);

    private static final String LEARN_DIR = "learn";
    /** 注入上限：最近 N 篇（token 克制，够"想起最近消化了什么"）。 */
    private static final int MAX_CARDS = 5;
    /** 单篇核心观点截断长度（注入是索引不是全文）。 */
    private static final int CORE_VIEW_MAX = 80;

    private static final Pattern TITLE_PATTERN = Pattern.compile("(?m)^title:\\s*(.+)$");
    private static final Pattern TYPE_PATTERN = Pattern.compile("(?m)^type:\\s*(.+)$");
    private static final Pattern CREATED_PATTERN = Pattern.compile("(?m)^created:\\s*(\\d{4}-\\d{2}-\\d{2})");
    /**
     * 核心观点段（**容错匹配**，2026-09-12 读侧对齐批）。
     * <p>
     * 同一个 learn 目录里有两个写入方：产品写 `## 核心观点`，Mac 上的 DSH 技能 A 写
     * `## 核心观点（一句话）`（段名带括号后缀、标题下还常空一行、正文首行是加粗）。
     * 原正则要求「段名后紧跟换行且下一行非空」——A 的卡全部匹配失败，召回到的只剩标题
     * （RFC 20260912 §9.1 记过这条）。这里放宽三处：允许段名前带序号（`二、核心观点`）、
     * 段名后带后缀（`核心观点（一句话）`）、标题与正文之间允许空行。
     */
    private static final Pattern CORE_VIEW_PATTERN = Pattern.compile(
            "(?m)^[ \\t]*##[ \\t]*(?:(?:[0-9０-９]+|[一二三四五六七八九十]+)[、.．)）][ \\t]*)?"
                    + "核心观点[^\\n]*\\n(?:[ \\t]*\\n)*[ \\t]*([^\\n#][^\\n]*)");

    private final FileStorage fileStorage;

    public LearnKnowledgeSource(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public String name() {
        return "learn";
    }

    @Override
    public String globalContext(String userId) {
        // 对齐 LifeKnowledgeSource 先例：始终注入（learn 插件用户才注入，ContextEngine 已按
        // pluginForKnowledge=learn 门控）。learn 卡片主题跨域（ai/trading/other），无法从
        // domainScene（life/trading/project）判定召回——用户问「上次讲 RAG 那篇说了啥」时
        // 该条记录的 domainScene 大概率是 life，场景化 enrich 会漏；始终注入最近 N 篇
        // （标题+核心观点）让问答能引用自己消化过的内容（RFC 3.7 ③ 价值呈现）。
        List<NoteSummary> notes = recentNotes(userId);
        if (notes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 你最近的学习笔记\n\n");
        for (NoteSummary n : notes) {
            sb.append("- ").append(n.title());
            if (!n.type().isBlank()) sb.append("（").append(n.type()).append("）");
            if (!n.coreView().isBlank()) sb.append("：").append(n.coreView());
            sb.append("\n");
        }
        log.info("LearnKnowledge 注入 | userId={} | 最近 {} 篇笔记", userId, notes.size());
        return sb.toString();
    }

    @Override
    public String enrich(String userId, String scene) {
        // 与 globalContext 重复（loadKnowledgeContext 会去重），返回空防双份注入
        return "";
    }

    /** 最近 N 篇卡片摘要（按 created 倒序；损坏/异型文件跳过）。 */
    List<NoteSummary> recentNotes(String userId) {
        List<NoteSummary> notes = new ArrayList<>();
        List<String> files = fileStorage.listFiles(userId, LEARN_DIR);
        for (String f : files) {
            if (!f.endsWith(".md") || f.contains("/_raw/")) continue;
            String content = fileStorage.read(userId, f);
            if (content == null || content.isBlank()) continue;
            NoteSummary n = parseSummary(content);
            if (n == null) continue;
            notes.add(n);
        }
        notes.sort(Comparator.comparing(NoteSummary::created).reversed());
        return notes.size() > MAX_CARDS ? notes.subList(0, MAX_CARDS) : notes;
    }

    /** 轻量 frontmatter 提取（title/type/created）+ 核心观点首行；核心字段缺失 → null。 */
    private NoteSummary parseSummary(String content) {
        String title = firstLine(TITLE_PATTERN, content);
        if (title == null || title.isBlank()) return null;
        String type = firstLine(TYPE_PATTERN, content);
        String created = firstLine(CREATED_PATTERN, content);
        String coreView = extractCoreView(content);
        return new NoteSummary(
                title.strip(),
                type == null ? "" : type.strip(),
                created == null ? "" : created.strip(),
                coreView);
    }

    private String extractCoreView(String content) {
        Matcher m = CORE_VIEW_PATTERN.matcher(content);
        if (!m.find()) return "";
        String view = m.group(1).strip();
        // 去列表/引用/加粗噪音（A 的卡正文首行常写成 **加粗一句话**）
        view = view.replaceAll("^[-*>]\\s+", "").strip();
        view = view.replaceAll("^\\*+", "").replaceAll("\\*+$", "").strip();
        return view.length() > CORE_VIEW_MAX ? view.substring(0, CORE_VIEW_MAX) + "…" : view;
    }

    private String firstLine(Pattern p, String content) {
        Matcher m = p.matcher(content);
        return m.find() ? m.group(1).strip() : null;
    }

    /** 注入用摘要（title + type + created + 核心观点）。 */
    record NoteSummary(String title, String type, String created, String coreView) {}
}
