package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
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
 * LearnCardFileRepository — 学习卡片的 md 文件存储（RFC 20260829）。
 * <p>
 * 文件布局 {@code data/{userId}/learn/}（File First，md 即卡片真相源，可 diff/可渲染）：
 * <pre>
 * {type}/{yyyy-MM-dd}_{title}.md      # 卡片（frontmatter + RFC 3.4 正文四段）
 * _raw/{time}.txt                     # 原始素材（LLM 失败时素材不丢，fail-visible）
 * </pre>
 * frontmatter 扁平 key: value（写读对称，不做嵌套——source 信息平铺为 platform/author/url/published）。
 * 并发：per-user 条带锁（固定 16 条带，P2-交易28 锁池模式）串行读-改-写。
 * 写失败抛 StorageException（fail-visible，P0-1 原则）。
 */
@Repository
public class LearnCardFileRepository implements LearnCardRepository {

    private static final Logger log = LoggerFactory.getLogger(LearnCardFileRepository.class);
    private static final String LEARN_DIR = "learn/";
    private static final String RAW_DIR = "learn/_raw/";
    private static final DateTimeFormatter DIR_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int LOCK_STRIPES = 16;
    private static final int MAX_TITLE_FILE_LEN = 60;

    private final Object[] locks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final FileStorage fileStorage;

    public LearnCardFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    private Object lockFor(String key) {
        int h = (key != null ? key : "default").hashCode();
        return locks[(h ^ (h >>> 16)) & (locks.length - 1)];
    }

    private static String safeLabel(String type, String title) {
        String t = type == null || type.isBlank() ? "?" : type;
        String ti = title == null || title.isBlank() ? "(空标题)" : title;
        return t + "/" + ti;
    }

    @Override
    public void save(String userId, LearnCard card) {
        synchronized (lockFor(userId)) {
            String path = filePath(card);
            if (fileStorage.exists(userId, path)) {
                throw new LearnException("已有同日同名卡片《" + card.title() + "》，避免覆盖，请先查看已有卡片");
            }
            fileStorage.write(userId, path, toMarkdown(card));
            log.info("learn 卡片已落盘 | userId={} | type={} | title={}", userId, card.type(), card.title());
        }
    }

    @Override
    public Optional<LearnCard> find(String userId, String type, String title) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) return Optional.empty();
        // 文件名含日期前缀（yyyy-MM-dd_{stem}.md），无法按纯标题拼路径——扫描匹配（卡片量小，
        // 单用户会话级，与 CardFileRepository.findAll 全量扫同思路）。
        return list(userId, type).stream()
                .filter(c -> title.equals(c.title()))
                .findFirst();
    }

    @Override
    public LearnCard updateStatus(String userId, String type, String title, String status) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) {
            throw new LearnException("卡片不存在");
        }
        synchronized (lockFor(userId)) {
            LearnCard card = find(userId, type, title)
                    .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, title)));
            String path = filePath(card);
            String content = fileStorage.read(userId, path);
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(type, title));
            }
            String updated = replaceStatusLine(content, status);
            fileStorage.write(userId, path, updated);
            log.info("learn 复习状态流转 | userId={} | type={} | title={} | status={}", userId, type, title, status);
            return parse(updated);
        }
    }

    @Override
    public LearnCard update(String userId, LearnCard card) {
        if (card == null) throw new LearnException("卡片不能为空");
        synchronized (lockFor(userId)) {
            // 定位原卡片（按 type+title）；路径稳定守卫：created/type/title 与现有一致才允许
            // 覆盖更新（title 变 → find 不到 = 卡片不存在；created 变 → 路径变 = 拒绝移动）
            LearnCard existing = find(userId, card.type(), card.title())
                    .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(card.type(), card.title())));
            if (!card.created().equals(existing.created())) {
                throw new LearnException("标题/类型/日期不可修改（会移动文件），如需改名请新建卡片");
            }
            fileStorage.write(userId, filePath(existing), toMarkdown(card));
            log.info("learn 卡片已更新 | userId={} | type={} | title={}", userId, card.type(), card.title());
            return card;
        }
    }

    /** 只替换 frontmatter 区内的 status 行（正文其余段落原样保留——md 即真相源）。 */
    private static String replaceStatusLine(String content, String status) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(---\\n)(.*?)(\\n---\\n)", java.util.regex.Pattern.DOTALL).matcher(content);
        if (!m.find()) {
            throw new LearnException("卡片文件格式异常，无法更新复习状态");
        }
        String fm = m.group(2);
        String replaced = fm.replaceAll("(?m)^status:.*$", "status: " + status);
        if (replaced.equals(fm)) {
            replaced = fm + "\nstatus: " + status;
        }
        return m.group(1) + replaced + m.group(3)
                + content.substring(m.end());
    }

    @Override
    public boolean existsOn(String userId, String type, LocalDate created, String title) {
        String path = LEARN_DIR + type + "/" + created.format(DIR_DATE) + "_" + fileStem(title) + ".md";
        return fileStorage.exists(userId, path);
    }

    @Override
    public List<LearnCard> list(String userId, String type) {
        List<LearnCard> result = new ArrayList<>();
        List<String> files = fileStorage.listFiles(userId, LEARN_DIR + type);
        for (String f : files) {
            if (!f.endsWith(".md")) continue;
            String content = fileStorage.read(userId, f);
            if (content == null || content.isBlank()) continue;
            LearnCard card = parse(content);
            if (card == null || !type.equals(card.type())) continue;  // 损坏/异型文件跳过
            result.add(card);
        }
        result.sort(Comparator.comparing(LearnCard::created).reversed());
        return result;
    }

    @Override
    public Map<String, List<LearnCard>> tree(String userId) {
        Map<String, List<LearnCard>> tree = new LinkedHashMap<>();
        for (String type : List.of(LearnCard.TYPE_AI, LearnCard.TYPE_TRADING, LearnCard.TYPE_OTHER)) {
            List<LearnCard> cards = list(userId, type);
            if (!cards.isEmpty()) tree.put(type, cards);
        }
        return tree;
    }

    @Override
    public void saveRawSource(String userId, String content) {
        String path = RAW_DIR + com.adaiadai.core.kernel.IdGenerator.monotonic("learn_raw_") + ".txt";
        fileStorage.write(userId, path, content == null ? "" : content);
        log.info("learn 原始素材已留存 | userId={} | path={}", userId, path);
    }

    // ── md 渲染/解析（与 CardFileRepository 单行化口径一致，保证写读对称）──

    /** 文件名：标题清洗为文件安全片段，同日同标题即同文件（幂等冲突检查）。 */
    static String fileStem(String title) {
        if (title == null || title.isBlank()) return "untitled";
        String cleaned = title
                .replace("\n", " ").replace("\r", " ")
                .replaceAll("[\\\\/:*?\"<>|#]", "-")
                .replaceAll("\\s+", " ").strip();
        // 防路径逃逸：. 与 - 打头、连续横线收敛、纯横线/纯点归一 untitled
        cleaned = cleaned.replaceAll("^-+", "").replaceAll("^[.]+", "")
                .replaceAll("-{2,}", "-").strip();
        if (cleaned.isBlank() || cleaned.matches("[-.]+")) return "untitled";
        return cleaned.length() > MAX_TITLE_FILE_LEN
                ? cleaned.substring(0, MAX_TITLE_FILE_LEN) : cleaned;
    }

    private String filePath(LearnCard card) {
        String date = card.created().format(DIR_DATE);
        return LEARN_DIR + card.type() + "/" + date + "_" + fileStem(card.title()) + ".md";
    }

    private static String singleLine(String text) {
        if (text == null) return "";
        return text.replace("\n", " ").replace("\r", " ").replaceAll(" +", " ").strip();
    }

    /** 渲染 md：frontmatter 扁平字段 + RFC 3.4 正文四段。 */
    static String toMarkdown(LearnCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(singleLine(card.title())).append("\n");
        sb.append("type: ").append(card.type()).append("\n");
        sb.append("platform: ").append(singleLine(card.platform())).append("\n");
        sb.append("author: ").append(singleLine(card.author())).append("\n");
        sb.append("url: ").append(singleLine(card.url())).append("\n");
        sb.append("published: ").append(singleLine(card.published())).append("\n");
        sb.append("created: ").append(card.created()).append("\n");
        sb.append("status: ").append(card.status()).append("\n");
        sb.append("trade_related: ").append(card.tradeRelated()).append("\n");
        sb.append("trade_note: ").append(singleLine(card.tradeNote())).append("\n");
        sb.append("tags: [").append(String.join(", ", card.tags())).append("]\n");
        sb.append("---\n\n");
        sb.append("## 核心观点\n").append(card.coreView() == null || card.coreView().isBlank() ? "" : card.coreView()).append("\n\n");
        sb.append("## 关键要点\n");
        if (card.keyPoints() != null && !card.keyPoints().isEmpty()) {
            for (String kp : card.keyPoints()) sb.append("- ").append(singleLine(kp)).append("\n");
        }
        sb.append("\n## 我的疑问\n");
        if (card.questions() != null && !card.questions().isEmpty()) {
            for (String q : card.questions()) sb.append("- ").append(singleLine(q)).append("\n");
        }
        sb.append("\n## 复述\n");
        if (card.retell() != null && !card.retell().isBlank()) {
            sb.append(card.retell()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /** 解析 md → LearnCard；frontmatter 损坏/缺核心字段 → null（跳过不中断）。 */
    static LearnCard parse(String content) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^---\\n(.+?)\\n---\\n(.+)", java.util.regex.Pattern.DOTALL
        ).matcher(content);
        if (!matcher.find()) return null;
        Map<String, String> fields = parseFrontmatter(matcher.group(1));
        String title = fields.getOrDefault("title", "").strip();
        String type = fields.getOrDefault("type", "").strip();
        if (title.isBlank() || !LearnCard.isValidType(type)) return null;

        LocalDate created = parseDate(fields.get("created"));
        if (created == null) return null;

        String body = matcher.group(2);
        Map<String, String> sections = parseSections(body);
        List<String> keyPoints = parseList(sections.get("关键要点"));
        List<String> questions = parseList(sections.get("我的疑问"));

        boolean tradeRelated = Boolean.parseBoolean(fields.getOrDefault("trade_related", "false").strip());
        return new LearnCard(
                type, title,
                fields.getOrDefault("platform", "").strip(),
                fields.getOrDefault("author", "").strip(),
                fields.getOrDefault("url", "").strip(),
                fields.getOrDefault("published", "").strip(),
                created,
                fields.getOrDefault("status", LearnCard.STATUS_NEW).strip(),
                tradeRelated,
                fields.getOrDefault("trade_note", "").strip(),
                parseTags(fields.getOrDefault("tags", "")),
                sections.getOrDefault("核心观点", "").strip(),
                keyPoints, questions,
                sections.getOrDefault("复述", "").strip());
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

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.strip());
        } catch (Exception e) {
            return null;
        }
    }
}
