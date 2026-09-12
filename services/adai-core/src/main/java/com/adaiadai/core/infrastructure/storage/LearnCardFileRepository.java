package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardPatch;
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
 * <p>
 * V2 learn 审查修复（2026-09-07）：
 * <ul>
 *   <li><b>P1-learn2</b>：标题寻址歧义根治——save 拒绝「同 type+同 title 任意日期」已存在
 *       （跨日同名是旧卡无法寻址/改错卡的源头）；find 遇残留多张同名抛 400（列出 created）</li>
 *   <li><b>S-learn1</b>：frontmatter 增可选 {@code review_at}/{@code reminded_at}——复习提醒按
 *       进入 review 之日计时 + 提醒节流（仓储只做机械落盘，today 由 application 传入，storage
 *       不取系统时间，G2）</li>
 *   <li><b>P2-learn6/7</b>：update/applyEdit 读-改-写在同一把锁内原子完成；写盘基于原文件做
 *       受管键/受管正文段手术替换——手工未知 frontmatter 键与未知正文段原样保留（File First）</li>
 * </ul>
 */
@Repository
public class LearnCardFileRepository implements LearnCardRepository {

    private static final Logger log = LoggerFactory.getLogger(LearnCardFileRepository.class);
    private static final String LEARN_DIR = "learn/";
    private static final String RAW_DIR = "learn/_raw/";
    private static final DateTimeFormatter DIR_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int LOCK_STRIPES = 16;
    /** V1 模板正文四段（编辑手术替换时受管段），其余「## 标题」段视为用户手工段保留。 */
    private static final List<String> MANAGED_SECTIONS =
            List.of("核心观点", "关键要点", "我的疑问", "复述");

    private final Object[] locks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final FileStorage fileStorage;

    public LearnCardFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 定位卡片**实际所在文件**（递归扫该 type 目录，含主题子目录）。
     * <p>
     * 为什么需要：`listFiles` 是递归的，所以 Mac 上 A 技能写在 `{type}/{topic}/NN-*.md` 的卡
     * 也会被本仓储读到（列表/资产树里看得见）。但本实现的读写路径是按 `{type}/{date}_{title}.md`
     * **算出来的**——对这些「别处整理的卡」读写会算出另一个不存在的路径。
     */
    private String locatedPath(String userId, String type, LearnCard card) {
        for (String f : fileStorage.listFiles(userId, LEARN_DIR + type)) {
            if (!f.endsWith(".md")) continue;
            String content = fileStorage.read(userId, f);
            if (content == null || content.isBlank()) continue;
            LearnCard parsed = parse(content);
            if (parsed == null || !type.equals(parsed.type())) continue;
            if (card.title().equals(parsed.title())) return f;
        }
        return null;
    }

    /**
     * 可写路径守卫（2026-09-12 读侧对齐批）：只允许改**本实现产出的卡片**。
     * <p>
     * 别处整理的卡（Mac 上 A 技能写在主题目录里的手工卡）一律**只读**——既避免把产品模板段
     * 注入别人的文件，也把原先那句莫名的「卡片不存在」换成说得通的人话。
     *
     * @throws LearnException 卡不存在 / 不是本实现产出的卡（消息为人话）
     */
    private String writablePath(String userId, String type, LearnCard card) {
        String located = locatedPath(userId, type, card);
        if (located == null) {
            throw new LearnException("卡片不存在：" + safeLabel(type, card.title()));
        }
        if (!located.equals(filePath(card))) {
            throw new LearnException("这张《" + card.title() + "》是在 Mac 上整理的原始卡，"
                    + "我在这里只当资料看、不改动它；想改的话我可以照它的内容另存一张能编辑的给你");
        }
        return located;
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
        if (card == null) throw new LearnException("卡片不能为空");
        synchronized (lockFor(userId)) {
            // P1-learn2：同 type + 同 title 已存在（任意日期）→ 拒绝，防跨日同名歧义。
            // 卡片少、单用户，扫描可接受；不覆盖、不静默（重复消化请先编辑已有卡片）。
            Optional<LearnCard> dup = findByTitleQuiet(userId, card.type(), card.title());
            if (dup.isPresent()) {
                throw new LearnException("已有同名卡片《" + card.title() + "》（创建于 "
                        + dup.get().created() + "），同标题内容请先查看/编辑已有卡片，避免重复消化");
            }
            String path = filePath(card);
            fileStorage.write(userId, path, toMarkdown(card));
            log.info("learn 卡片已落盘 | userId={} | type={} | title={}", userId, card.type(), card.title());
        }
    }

    @Override
    public Optional<LearnCard> find(String userId, String type, String title) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) return Optional.empty();
        List<LearnCard> matched = list(userId, type).stream()
                .filter(c -> title.equals(c.title()))
                .toList();
        if (matched.size() > 1) {
            // P1-learn2：残留多张同名（历史/手工）→ 显式 400，禁止静默取最新改错卡
            String dates = matched.stream()
                    .map(c -> c.created().toString()).sorted()
                    .reduce((a, b) -> a + " / " + b).orElse("");
            throw new LearnException("《" + title + "》存在 " + matched.size() + " 张同名卡片（创建于 "
                    + dates + "），标题无法唯一寻址——请人工合并文件后再操作");
        }
        return matched.isEmpty() ? Optional.empty() : Optional.of(matched.get(0));
    }

    /** 静默版标题查找（save 重复检查用；多张同名取最新 created，不抛——save 只关心「已存在」）。 */
    private Optional<LearnCard> findByTitleQuiet(String userId, String type, String title) {
        return list(userId, type).stream()
                .filter(c -> title.equals(c.title()))
                .max(Comparator.comparing(LearnCard::created));
    }

    @Override
    public LearnCard updateStatus(String userId, String type, String title, String toStatus, LocalDate today) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) {
            throw new LearnException("卡片不存在");
        }
        synchronized (lockFor(userId)) {
            LearnCard card = find(userId, type, title)
                    .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, title)));
            String from = card.status();
            if (!LearnCard.isValidTransition(from, toStatus)) {
                throw new LearnException("状态流转 " + from + "→" + toStatus + " 不被允许（new→review→done，"
                        + "可回退 review→new / done→review）");
            }
            String path = writablePath(userId, type, card);
            String content = fileStorage.read(userId, path);
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(type, title));
            }
            // S-learn1 计时语义：进入 review（含 done→review 重进）→ review_at=today、清 reminded_at；
            // 离开 review（→new/done）→ 清计时（不再提醒）。
            boolean enteringReview = LearnCard.STATUS_REVIEW.equals(toStatus)
                    && !LearnCard.STATUS_REVIEW.equals(from);
            boolean leavingReview = !LearnCard.STATUS_REVIEW.equals(toStatus)
                    && LearnCard.STATUS_REVIEW.equals(from);
            String updated = replaceFrontmatterKey(content, "status", toStatus);
            if (enteringReview) {
                updated = replaceFrontmatterKey(updated, "review_at", today == null ? "" : today.toString());
                updated = replaceFrontmatterKey(updated, "reminded_at", "");
            } else if (leavingReview) {
                updated = replaceFrontmatterKey(updated, "review_at", "");
                updated = replaceFrontmatterKey(updated, "reminded_at", "");
            }
            fileStorage.write(userId, path, updated);
            log.info("learn 复习状态流转 | userId={} | type={} | title={} | {}→{}", userId, type, title, from, toStatus);
            return parse(updated);
        }
    }

    @Override
    public LearnCard markReminded(String userId, String type, String title, LocalDate today) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) {
            throw new LearnException("卡片不存在");
        }
        synchronized (lockFor(userId)) {
            LearnCard card = find(userId, type, title)
                    .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, title)));
            if (!LearnCard.STATUS_REVIEW.equals(card.status())) {
                return card; // 非 review 卡不参与提醒节流
            }
            String path = writablePath(userId, type, card);
            String content = fileStorage.read(userId, path);
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(type, title));
            }
            String updated = replaceFrontmatterKey(content, "reminded_at",
                    today == null ? "" : today.toString());
            fileStorage.write(userId, path, updated);
            log.info("learn 复习提醒已标记 | userId={} | type={} | title={} | remindedAt={}",
                    userId, type, title, today);
            return parse(updated);
        }
    }

    @Override
    public LearnCard applyEdit(String userId, String type, String title, LearnCardPatch patch) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) {
            throw new LearnException("卡片不存在");
        }
        synchronized (lockFor(userId)) {
            LearnCard cur = find(userId, type, title)
                    .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, title)));
            // P2-learn6：锁内基于最新快照 merge，并发 PATCH 不再丢更新
            LearnCard updated = mergePatch(cur, patch);
            String path = writablePath(userId, type, cur);
            String content = fileStorage.read(userId, path);
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(type, title));
            }
            String rewritten = rewriteManaged(content, updated);
            fileStorage.write(userId, path, rewritten);
            log.info("learn 卡片已编辑 | userId={} | type={} | title={}", userId, type, title);
            return parse(rewritten);
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
            String path = writablePath(userId, card.type(), existing);
            String content = fileStorage.read(userId, path);
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(card.type(), card.title()));
            }
            String rewritten = rewriteManaged(content, card);
            fileStorage.write(userId, path, rewritten);
            log.info("learn 卡片已更新 | userId={} | type={} | title={}", userId, card.type(), card.title());
            return parse(rewritten);
        }
    }

    // ── 补丁/手术写 ──────────────────────────────────────────────

    /** 补丁 merge（锁内基于最新卡；null = 保留原值；非 trading 收敛由 LearnCard 构造器保证）。 */
    private static LearnCard mergePatch(LearnCard cur, LearnCardPatch p) {
        boolean tradeRelated = p != null && p.tradeRelated() != null ? p.tradeRelated() : cur.tradeRelated();
        List<String> tags = p != null && p.tags() != null ? cleanList(p.tags()) : cur.tags();
        List<String> keyPoints = p != null && p.keyPoints() != null ? cleanList(p.keyPoints()) : cur.keyPoints();
        List<String> questions = p != null && p.questions() != null ? cleanList(p.questions()) : cur.questions();
        String tradeNote = p != null && p.tradeNote() != null ? p.tradeNote().strip() : cur.tradeNote();
        return new LearnCard(
                cur.type(), cur.title(), cur.platform(), cur.author(), cur.url(), cur.published(),
                cur.created(), cur.status(), tradeRelated, tradeNote, tags,
                p != null && p.coreView() != null ? p.coreView().strip() : cur.coreView(),
                keyPoints, questions,
                p != null && p.retell() != null ? p.retell().strip() : cur.retell(),
                cur.reviewAt(), cur.remindedAt());
    }

    private static List<String> cleanList(List<String> list) {
        if (list == null) return List.of();
        return list.stream().map(String::strip).filter(s -> !s.isBlank()).toList();
    }

    /**
     * P2-learn7：基于原文件做「受管键 + 受管正文段」手术替换，未知 frontmatter 键与未知
     * 「## 标题」段原样保留（File First：md 即真相源，编辑不抹手工内容）。
     */
    private static String rewriteManaged(String original, LearnCard card) {
        java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
                "^(---\\n)(.*?)(\\n---\\n)", java.util.regex.Pattern.DOTALL).matcher(original);
        if (!fm.find()) throw new LearnException("卡片文件格式异常，无法更新");
        String fmBlock = fm.group(2);
        String body = original.substring(fm.end());

        // 1) frontmatter：受管键行替换为新卡值；未受管键行原样保留
        Map<String, String> fmLines = parseFrontmatterLines(fmBlock);
        StringBuilder newFm = new StringBuilder();
        newFm.append(fm.group(1));
        List<String> managedKeys = List.of("title", "type", "platform", "author", "url", "published",
                "created", "status", "trade_related", "trade_note", "tags", "review_at", "reminded_at");
        for (Map.Entry<String, String> e : fmLines.entrySet()) {
            String key = e.getKey();
            if (managedKeys.contains(key)) {
                String v = managedValue(card, key);
                if (v == null) continue; // 空值键不保留（如 review_at 无值）
                newFm.append(key).append(": ").append(v).append("\n");
            } else {
                newFm.append(key).append(": ").append(e.getValue()).append("\n");
            }
        }
        newFm.append(fm.group(3));

        // 2) 正文：按「## 标题」切段——受管段重建，未知段原样保留（顺序不变）
        return newFm + rewriteBodySections(body, card);
    }

    /** 受管 frontmatter 键的序列化值（空串 → null = 省略该键行）。 */
    private static String managedValue(LearnCard card, String key) {
        return switch (key) {
            case "title" -> singleLine(card.title());
            case "type" -> card.type();
            case "platform" -> singleLine(card.platform());
            case "author" -> singleLine(card.author());
            case "url" -> singleLine(card.url());
            case "published" -> singleLine(card.published());
            case "created" -> String.valueOf(card.created());
            case "status" -> card.status();
            case "trade_related" -> String.valueOf(card.tradeRelated());
            case "trade_note" -> card.tradeNote() == null || card.tradeNote().isBlank() ? null : singleLine(card.tradeNote());
            case "tags" -> "[" + String.join(", ", card.tags()) + "]";
            case "review_at" -> card.reviewAt() == null ? null : card.reviewAt().toString();
            case "reminded_at" -> card.remindedAt() == null ? null : card.remindedAt().toString();
            default -> null;
        };
    }

    /** 正文切段重建：原顺序遍历，「## 标题」段内受管标题重建、未知标题原样。 */
    static String rewriteBodySections(String body, LearnCard card) {
        List<BodyPart> parts = splitBodyParts(body);
        StringBuilder sb = new StringBuilder();
        boolean[] seen = new boolean[MANAGED_SECTIONS.size()];
        for (BodyPart part : parts) {
            if (part.header() != null) {
                // 与读侧同口径归一：手工把段名写成「核心观点（一句话）」也不当成未知段重复补一份
                int idx = MANAGED_SECTIONS.indexOf(normalizeSectionName(part.header()));
                if (idx >= 0) {
                    seen[idx] = true;
                    sb.append("## ").append(part.header()).append("\n")
                            .append(managedSectionBody(idx, card));
                } else {
                    sb.append("## ").append(part.header()).append("\n").append(part.content());
                }
            } else {
                sb.append(part.content()); // 首段前导文本（模板无，保留以防手工）
            }
        }
        // 模板缺段补回（保持四段齐全语义）
        for (int i = 0; i < MANAGED_SECTIONS.size(); i++) {
            if (!seen[i]) {
                sb.append("## ").append(MANAGED_SECTIONS.get(i)).append("\n")
                        .append(managedSectionBody(i, card));
            }
        }
        return sb.toString();
    }

    private static String managedSectionBody(int idx, LearnCard card) {
        return switch (idx) {
            case 0 -> (card.coreView() == null || card.coreView().isBlank() ? "" : card.coreView()) + "\n\n";
            case 1 -> {
                StringBuilder sb = new StringBuilder();
                if (card.keyPoints() != null) {
                    for (String kp : card.keyPoints()) sb.append("- ").append(singleLine(kp)).append("\n");
                }
                yield sb.append("\n").toString();
            }
            case 2 -> {
                StringBuilder sb = new StringBuilder();
                if (card.questions() != null) {
                    for (String q : card.questions()) sb.append("- ").append(singleLine(q)).append("\n");
                }
                yield sb.append("\n").toString();
            }
            default -> (card.retell() == null || card.retell().isBlank() ? "" : card.retell()) + "\n\n";
        };
    }

    private record BodyPart(String header, String content) {}

    /** 按「## 标题」切正文（含段前文本）。段内保持原样（含换行）；strip 尾空白由写入方控制。 */
    static List<BodyPart> splitBodyParts(String body) {
        List<BodyPart> parts = new ArrayList<>();
        if (body == null) return parts;
        String[] lines = body.split("\n", -1);
        StringBuilder pre = new StringBuilder();
        String curHeader = null;
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (curHeader != null || cur.length() > 0) {
                    parts.add(new BodyPart(curHeader, cur.toString()));
                } else if (pre.length() > 0) {
                    parts.add(new BodyPart(null, pre.toString()));
                }
                curHeader = line.substring(3).strip();
                cur = new StringBuilder();
            } else if (curHeader == null) {
                pre.append(line).append("\n");
            } else {
                cur.append(line).append("\n");
            }
        }
        if (pre.length() > 0 && curHeader == null) {
            parts.add(new BodyPart(null, pre.toString()));
        } else if (curHeader != null || cur.length() > 0) {
            parts.add(new BodyPart(curHeader, cur.toString()));
        }
        return parts;
    }

    /** frontmatter 键值解析（含行序）；非受管键写回用。 */
    private static Map<String, String> parseFrontmatterLines(String frontmatter) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : frontmatter.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                fields.put(line.substring(0, colonIdx).trim(), line.substring(colonIdx + 1).trim());
            }
        }
        return fields;
    }

    /** frontmatter 区内单键 upsert（有则替换、无则追加行；value 空串 = 删除键行）。 */
    private static String replaceFrontmatterKey(String content, String key, String value) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(---\\n)(.*?)(\\n---\\n)", java.util.regex.Pattern.DOTALL).matcher(content);
        if (!m.find()) throw new LearnException("卡片文件格式异常，无法更新复习状态");
        String fm = m.group(2);
        String linePattern = "(?m)^" + java.util.regex.Pattern.quote(key) + ":.*$";
        boolean exists = java.util.regex.Pattern.compile(linePattern).matcher(fm).find();
        String replaced;
        if (value == null || value.isEmpty()) {
            replaced = exists ? fm.replaceAll(linePattern, "") : fm;
        } else if (exists) {
            replaced = fm.replaceAll(linePattern, key + ": " + value);
        } else {
            // frontmatter 末尾追加新键行（fm 不尾随换行，需补 \n 防粘行）
            replaced = fm.endsWith("\n") ? fm + key + ": " + value : fm + "\n" + key + ": " + value;
        }
        return m.group(1) + replaced + m.group(3) + content.substring(m.end());
    }

    @Override
    public boolean existsOn(String userId, String type, LocalDate created, String title) {
        String path = LEARN_DIR + type + "/" + created.format(DIR_DATE) + "_" + LearnCard.fileStem(title) + ".md";
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

    @Override
    public void saveRaw(String userId, String name, String content) {
        String path = RAW_DIR + safeRawName(name);
        fileStorage.write(userId, path, content == null ? "" : content);
        log.info("learn 原始素材已留存 | userId={} | path={} | {} 字", userId, path,
                content == null ? 0 : content.length());
    }

    @Override
    public String readRaw(String userId, String name) {
        if (name == null || name.isBlank()) return null;
        return fileStorage.read(userId, RAW_DIR + safeRawName(name));
    }

    /** 具名素材文件名清洗（防路径逃逸：只允许 [A-Za-z0-9._-]，拒绝 .. 与分隔符）。 */
    static String safeRawName(String name) {
        if (name == null || name.isBlank()) throw new LearnException("素材文件名不能为空");
        String cleaned = name.strip().replaceAll("[^A-Za-z0-9._-]", "_");
        while (cleaned.contains("..")) cleaned = cleaned.replace("..", "_");
        if (cleaned.isBlank()) throw new LearnException("素材文件名不合法");
        return cleaned;
    }

    // ── md 渲染/解析（与 CardFileRepository 单行化口径一致，保证写读对称）──

    /** 文件名：标题清洗为文件安全片段（实现上移 domain LearnCard.fileStem，P1-learn1 复用同口径）。 */
    private String filePath(LearnCard card) {
        String date = card.created().format(DIR_DATE);
        return LEARN_DIR + card.type() + "/" + date + "_" + LearnCard.fileStem(card.title()) + ".md";
    }

    private static String singleLine(String text) {
        if (text == null) return "";
        return text.replace("\n", " ").replace("\r", " ").replaceAll(" +", " ").strip();
    }

    /** 渲染 md（新建卡）：frontmatter 扁平字段 + RFC 3.4 正文四段（V1 模板四段空段补齐）。 */
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
        if (card.tradeNote() != null && !card.tradeNote().isBlank()) {
            sb.append("trade_note: ").append(singleLine(card.tradeNote())).append("\n");
        }
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
                sections.getOrDefault("复述", "").strip(),
                parseDate(fields.get("review_at")),
                parseDate(fields.get("reminded_at")));
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

    /**
     * 段名归一（**容错读**，2026-09-12 读侧对齐批）。
     * <p>
     * 同一个 learn 目录里有两个写入方：产品（本实现，段名 `## 核心观点`）与 Mac 上的 DSH 技能 A
     * （段名 `## 核心观点（一句话）`、`## 二、核心观点` 这类带后缀/序号的写法）。原先按**精确段名**
     * 取值，A 的卡被读出来就是「核心观点/要点全空」——看得见、读不全。
     * 归一只做**同一概念的写法差异**（去编号前缀、去括号后缀），**不做语义改名**：
     * A 的 `## 内容脉络` 保持原样（它是另一个名字的段，不冒充「关键要点」），未知段照旧原样保留。
     */
    static String normalizeSectionName(String header) {
        if (header == null) return "";
        String h = header.strip();
        h = h.replaceAll("^(?:[0-9０-９]+|[一二三四五六七八九十]+)\\s*[、.．)）]\\s*", "");
        h = h.replaceAll("[（(][^（()）]*[)）]\\s*$", "");
        return h.strip();
    }

    /** 按 "## 标题" 切正文段（段名经 {@link #normalizeSectionName} 归一）。 */
    private static Map<String, String> parseSections(String body) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return sections;
        String current = null;
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("## ")) {
                if (current != null) sections.put(current, sb.toString().strip());
                current = normalizeSectionName(line.substring(3));
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
