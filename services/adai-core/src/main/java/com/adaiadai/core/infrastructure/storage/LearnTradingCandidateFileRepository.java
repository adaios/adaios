package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnTradingCandidate;
import com.adaiadai.core.domain.learn.LearnTradingCandidateRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LearnTradingCandidateFileRepository — learn → trading 反哺候选的 md 文件存储（RFC 20260829 V2 批 3）。
 * <p>
 * 文件布局 {@code data/{userId}/trading/candidates/}（RFC 3.3：V2 加 candidates/ 候选目录）：
 * <pre>
 * {yyyy-MM-dd}_{title}.md   # 建议卡（frontmatter + 提炼建议）
 * </pre>
 * frontmatter 扁平 key: value（写读对称，对齐 LearnCardFileRepository）。文件名 = 生成日期 + 标题，
 * 同日同名防覆盖（一卡一反哺，重复生成提示先看已有候选）。
 * 并发：per-user 条带锁（16 条带，P2-交易28 锁池模式）；写失败抛 StorageException（fail-visible）。
 */
@Repository
public class LearnTradingCandidateFileRepository implements LearnTradingCandidateRepository {

    private static final Logger log = LoggerFactory.getLogger(LearnTradingCandidateFileRepository.class);
    private static final String CANDIDATE_DIR = "trading/candidates/";
    private static final DateTimeFormatter DIR_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int LOCK_STRIPES = 16;

    private final Object[] locks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final FileStorage fileStorage;

    public LearnTradingCandidateFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    private Object lockFor(String key) {
        int h = (key != null ? key : "default").hashCode();
        return locks[(h ^ (h >>> 16)) & (locks.length - 1)];
    }

    @Override
    public void save(String userId, LearnTradingCandidate candidate) {
        synchronized (lockFor(userId)) {
            String path = filePath(candidate);
            if (fileStorage.exists(userId, path)) {
                throw new LearnException("已有同名的反哺候选《" + candidate.title() + "》，请先查看/删除已有候选");
            }
            Optional<LearnTradingCandidate> dup = findByTitleQuiet(userId, candidate.title());
            if (dup.isPresent()) {
                throw new LearnException("已有同名的反哺候选《" + candidate.title() + "》（创建于 "
                        + dup.get().created() + "），同标题请先查看/删除已有候选");
            }
            fileStorage.write(userId, path, toMarkdown(candidate));
            log.info("learn → trading 反哺候选已落盘 | userId={} | title={} | learnCard={}",
                    userId, candidate.title(), candidate.learnCardId());
        }
    }

    @Override
    public Optional<LearnTradingCandidate> find(String userId, String title) {
        if (title == null || title.isBlank()) return Optional.empty();
        List<LearnTradingCandidate> matched = list(userId).stream()
                .filter(c -> title.equals(c.title()))
                .toList();
        if (matched.size() > 1) {
            String dates = matched.stream()
                    .map(c -> c.created().toString()).sorted()
                    .reduce((a, b) -> a + " / " + b).orElse("");
            throw new LearnException("候选《" + title + "》存在 " + matched.size() + " 条同名（创建于 "
                    + dates + "），标题无法唯一寻址——请人工合并文件后再操作");
        }
        return matched.isEmpty() ? Optional.empty() : Optional.of(matched.get(0));
    }

    private Optional<LearnTradingCandidate> findByTitleQuiet(String userId, String title) {
        return list(userId).stream()
                .filter(c -> title.equals(c.title()))
                .max(Comparator.comparing(LearnTradingCandidate::created));
    }

    @Override
    public List<LearnTradingCandidate> list(String userId) {
        List<LearnTradingCandidate> result = new ArrayList<>();
        List<String> files = fileStorage.listFiles(userId, CANDIDATE_DIR);
        for (String f : files) {
            if (!f.endsWith(".md")) continue;
            String content = fileStorage.read(userId, f);
            if (content == null || content.isBlank()) continue;
            LearnTradingCandidate c = parse(content);
            if (c == null) continue;  // 损坏/异型文件跳过
            result.add(c);
        }
        result.sort(Comparator.comparing(LearnTradingCandidate::created).reversed());
        return result;
    }

    @Override
    public void delete(String userId, String title) {
        if (title == null || title.isBlank()) return;
        synchronized (lockFor(userId)) {
            // 唯一语义：同名多张 → 歧义 400（禁止删错对象，P1-learn4 修复 2026-09-07）
            List<LearnTradingCandidate> matched = list(userId).stream()
                    .filter(c -> title.equals(c.title()))
                    .toList();
            if (matched.isEmpty()) return; // 幂等
            if (matched.size() > 1) {
                String dates = matched.stream()
                        .map(c -> c.created().toString()).sorted()
                        .reduce((a, b) -> a + " / " + b).orElse("");
                throw new LearnException("候选《" + title + "》存在 " + matched.size() + " 条同名（创建于 "
                        + dates + "），无法确定删除对象——请人工合并文件后再删除");
            }
            LearnTradingCandidate target = matched.get(0);
            fileStorage.delete(userId, CANDIDATE_DIR
                    + target.created().format(DIR_DATE) + "_" + LearnCard.fileStem(target.title()) + ".md");
            log.info("learn → trading 反哺候选已删除 | userId={} | title={} | created={}",
                    userId, title, target.created());
        }
    }

    private String filePath(LearnTradingCandidate candidate) {
        return CANDIDATE_DIR + candidate.created().format(DIR_DATE) + "_" + LearnCard.fileStem(candidate.title()) + ".md";
    }

    private static String singleLine(String text) {
        if (text == null) return "";
        return text.replace("\n", " ").replace("\r", " ").replaceAll(" +", " ").strip();
    }

    /** 渲染 md：frontmatter 扁平字段 + 建议正文（提炼，不复制整卡）。 */
    static String toMarkdown(LearnTradingCandidate c) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(singleLine(c.title())).append("\n");
        sb.append("learn_card_id: ").append(singleLine(c.learnCardId())).append("\n");
        sb.append("source_type: ").append(c.sourceType()).append("\n");
        sb.append("created: ").append(c.created()).append("\n");
        sb.append("trade_note: ").append(singleLine(c.tradeNote())).append("\n");
        sb.append("tags: [").append(String.join(", ", c.tags())).append("]\n");
        sb.append("---\n\n");
        sb.append("> 本条候选由 learn 卡片反哺生成（learn_card_id 回链）。请在交易知识库工作流审核后\n");
        sb.append("> 融合归正式规则目录，并在收敛时重建 knowledge/context——不会自动进入 AI 上下文。\n\n");
        sb.append("## 建议核心\n").append(c.coreView() == null || c.coreView().isBlank() ? "" : c.coreView()).append("\n\n");
        sb.append("## 建议要点\n");
        if (c.keyPoints() != null && !c.keyPoints().isEmpty()) {
            for (String kp : c.keyPoints()) sb.append("- ").append(singleLine(kp)).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /** 解析 md → LearnTradingCandidate；frontmatter 损坏/缺关键字段 → null（跳过不中断）。 */
    static LearnTradingCandidate parse(String content) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^---\\n(.+?)\\n---\\n(.+)", java.util.regex.Pattern.DOTALL
        ).matcher(content);
        if (!matcher.find()) return null;
        Map<String, String> fields = parseFrontmatter(matcher.group(1));
        String title = fields.getOrDefault("title", "").strip();
        String learnCardId = fields.getOrDefault("learn_card_id", "").strip();
        String sourceType = fields.getOrDefault("source_type", "").strip();
        if (title.isBlank() || learnCardId.isBlank()) return null;

        LocalDate created;
        try {
            created = LocalDate.parse(fields.getOrDefault("created", ""));
        } catch (Exception e) {
            return null;
        }
        if (created == null) return null;

        String body = matcher.group(2);
        Map<String, String> sections = parseSections(body);
        return new LearnTradingCandidate(
                title, learnCardId,
                sourceType.isBlank() ? "trading" : sourceType,
                created,
                sections.getOrDefault("建议核心", "").strip(),
                parseList(sections.get("建议要点")),
                fields.getOrDefault("trade_note", "").strip(),
                parseTags(fields.getOrDefault("tags", "")));
    }

    private static Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : frontmatter.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                fields.put(line.substring(0, colonIdx).trim(), line.substring(colonIdx + 1).trim());
            }
        }
        return fields;
    }

    /** 按 "## 标题" 切正文段。 */
    private static Map<String, String> parseSections(String body) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return sections;
        String current = null;
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("## ")) {
                if (current != null) sections.put(current, sb.toString().strip());
                current = line.substring(3).strip();
                sb = new StringBuilder();
            } else {
                sb.append(line).append("\n");
            }
        }
        if (current != null) sections.put(current, sb.toString().strip());
        return sections;
    }

    private static List<String> parseList(String section) {
        if (section == null || section.isBlank()) return List.of();
        List<String> list = new ArrayList<>();
        for (String line : section.split("\n")) {
            String l = line.strip();
            if (l.startsWith("- ")) list.add(l.substring(2).strip());
        }
        return list;
    }

    private static List<String> parseTags(String tagsStr) {
        String cleaned = tagsStr.replaceAll("[\\[\\]\"'\\s]", "");
        if (cleaned.isBlank()) return List.of();
        return java.util.Arrays.stream(cleaned.split(","))
                .filter(s -> !s.isBlank())
                .toList();
    }
}
