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
 * {type}/{topic}/NN-{slug}.md      # 卡片（frontmatter + 正文段）——与 Mac 侧技能产物同契约
 * {type}/{topic}/README.md         # 主题索引（本实现只**追加**「阿呆整理记录」段，不重写他人内容）
 * {type}/{topic}/_raw/{name}       # 该主题的原始素材（源必留痕，与卡放在一起）
 * _raw/{name}                      # 素材暂存区（消化成功前主题未知；成功后 promoteRaw 归位）
 * </pre>
 * frontmatter 扁平 key: value（写读对称，不做嵌套——source 信息平铺为 platform/author/url/published）。
 * 并发：per-user 条带锁（固定 16 条带，P2-交易28 锁池模式）串行读-改-写。
 * 写失败抛 StorageException（fail-visible，P0-1 原则）。
 * <p>
 * 2026-09-12 结构统一批（本文件最主要的改造）：
 * <ul>
 *   <li><b>主题目录契约</b>：新卡落 {@code {type}/{topic}/NN-{slug}.md}（NN 主题内递增 + 主题 README
 *       索引），与 Mac 上 DSH 技能 `learn-digest` 的产物**同一个结构**——两个写入方不再各写一套</li>
 *   <li><b>来源判定（origin）</b>：本实现写出的卡带 {@code origin: product}；别处整理的卡没有该键
 *       → 判定为**只读**（读写路径守卫从「路径算得对不对」改为「这张卡是不是我写的」，手工卡与
 *       产品卡同在一个主题目录里也能各自区分）</li>
 *   <li><b>标题歧义消解</b>：同名时**本产品卡优先**；别处手工卡同名不再拦截新建（P2-learn20），
 *       多个本产品同名卡仍 400（P1-learn2 不变）</li>
 *   <li><b>兼容读**：老式扁平 {@code {type}/{date}_{title}.md} 与主题目录两种布局都能读/能改
 *       （老卡原地不动，不强制迁移）</li>
 *   <li><b>README.md 不当卡片</b>：主题索引的 frontmatter 也长得像卡（有 title/type/created），
 *       列表/定位一律跳过</li>
 * </ul>
 * <p>
 * V2 learn 审查修复（2026-09-07）：
 * <ul>
 *   <li><b>P1-learn2</b>：标题寻址歧义根治——save 拒绝「同 type+同 title」已存在
 *       （跨日同名是旧卡无法寻址/改错卡的源头）</li>
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
    /** 素材暂存区（消化成功前主题未知；成功后归位到主题目录，见 {@link #promoteRaw}）。 */
    private static final String RAW_STAGING_DIR = "learn/_raw/";
    private static final String RAW_SUBDIR = "_raw";
    /** 软删除目录（删卡不真删：知识是资产，误删要能捡回来）。 */
    private static final String TRASH_SUBDIR = "_trash";
    private static final String README_NAME = "README.md";
    /** 本实现写出的卡带此标记；别处（Mac 上技能）整理的卡没有 → 只读。 */
    private static final String ORIGIN_KEY = "origin";
    private static final String ORIGIN_PRODUCT = "product";
    /** README 自动段标记（只追加、不重写他人已写内容）。 */
    private static final String README_MARKER = "## 阿呆整理记录（自动维护）";
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

    /** 定位结果：卡片**实际所在文件** + 该文件的主题/可写性（由路径与 origin 判定）。 */
    private record Located(String path, LearnCard card) {}

    /**
     * 扫某 type 下的全部卡片文件（递归，含主题子目录里的手工卡）。
     * <p>
     * 主题由**文件所在目录**决定（路径是位置真相），可写性由 {@code origin: product} 决定
     * （是不是我写的）——两者都从盘上算，不从 frontmatter 里猜。
     */
    private List<Located> locateAll(String userId, String type) {
        List<Located> out = new ArrayList<>();
        for (String f : fileStorage.listFiles(userId, LEARN_DIR + type)) {
            if (!f.endsWith(".md") || isReadme(f)) continue;
            if (f.contains("/" + RAW_SUBDIR + "/")) continue;   // _raw/ 里的素材不是卡片
            String content = fileStorage.read(userId, f);
            if (content == null || content.isBlank()) continue;
            LearnCard parsed = parse(content);
            if (parsed == null || !type.equals(parsed.type())) continue;
            out.add(new Located(f, decorate(parsed, f, type, content)));
        }
        return out;
    }

    /** 读盘后补上「在哪（主题）」与「能不能改（是否本实现产出）」。 */
    private static LearnCard decorate(LearnCard parsed, String path, String type, String content) {
        return parsed.withTopic(topicOfPath(path, type)).withWritable(isOwn(path, type, content));
    }

    /** 文件所在主题目录名（扁平老布局/直接放在 type 下 → 缺省主题）。 */
    private static String topicOfPath(String path, String type) {
        String prefix = LEARN_DIR + type + "/";
        if (path == null || !path.startsWith(prefix)) return LearnCard.DEFAULT_TOPIC;
        String rel = path.substring(prefix.length());
        int slash = rel.indexOf('/');
        if (slash <= 0) return LearnCard.DEFAULT_TOPIC;
        String dir = rel.substring(0, slash);
        return RAW_SUBDIR.equals(dir) ? LearnCard.DEFAULT_TOPIC : dir;
    }

    /**
     * 是否**本实现产出的卡**（可写）。
     * <p>
     * 两条判据：① 老式扁平布局 {@code {type}/{date}_{title}.md}（V1/V2 产品卡，原地保留不迁移）；
     * ② 主题布局里带 {@code origin: product} 的（本批起新产品卡）。Mac 上技能写的卡两条都不满足
     * → 只读（列表/全文照常可见）。
     */
    private static boolean isOwn(String path, String type, String content) {
        String prefix = LEARN_DIR + type + "/";
        if (path == null || !path.startsWith(prefix)) return false;
        String rel = path.substring(prefix.length());
        int slash = rel.indexOf('/');
        if (slash < 0) {
            return rel.matches("\\d{4}-\\d{2}-\\d{2}_.+\\.md");   // 老式扁平产品卡
        }
        String dir = rel.substring(0, slash);
        if (RAW_SUBDIR.equals(dir)) return false;
        String file = rel.substring(slash + 1);
        return file.matches("\\d+-.+\\.md") && hasProductOrigin(content);
    }

    /**
     * 是不是本实现写的卡（看 frontmatter 的 {@code origin: product}）。
     * <p>
     * 对抗审查 P1-A（2026-09-12）修复：**只在前言块里找**——原先扫全文，外部卡正文/代码块里
     * 只要出现一行 {@code origin: product}（例如一张讲解本契约的笔记），就会被误判成「产品卡」
     * 进而被产品改写（正是本批要防的「把产品模板段注入别人的文件」）。正文一概不算。
     */
    private static boolean hasProductOrigin(String content) {
        if (content == null) return false;
        java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
                "^(---\\n)(.*?)(\\n---\\n)", java.util.regex.Pattern.DOTALL).matcher(content);
        if (!fm.find()) return false;
        return java.util.regex.Pattern
                .compile("(?m)^" + ORIGIN_KEY + ":\\s*" + ORIGIN_PRODUCT + "\\s*$")
                .matcher(fm.group(2)).find();
    }

    /** 主题索引文件（README.md / index.md）不是卡片。 */
    private static boolean isReadme(String path) {
        if (path == null) return false;
        String base = baseName(path).toLowerCase();
        return base.equals("readme.md") || base.equals("index.md");
    }

    /**
     * 按 type + 标题定位（**本产品卡优先**，P2-learn20 修复）。
     * <p>
     * 同名可能来自两处：本实现写的卡、Mac 上技能写的手工卡。产品侧的操作（编辑/流转/反哺）
     * 只应落在自己的卡上；全是手工卡时返回它（只读，供查看）。**多个本产品卡同名**仍 400
     * （P1-learn2：禁止静默改错卡）。
     */
    private Optional<Located> locate(String userId, String type, String title) {
        List<Located> named = locateAll(userId, type).stream()
                .filter(l -> title.equals(l.card().title()))
                .toList();
        if (named.isEmpty()) return Optional.empty();
        List<Located> own = named.stream().filter(l -> l.card().writable()).toList();
        if (own.size() > 1) {
            String dates = own.stream().map(l -> l.card().created().toString()).sorted()
                    .reduce((a, b) -> a + " / " + b).orElse("");
            throw new LearnException("《" + title + "》存在 " + own.size() + " 张同名卡片（创建于 "
                    + dates + "），标题无法唯一寻址——请人工合并文件后再操作");
        }
        if (own.size() == 1) return Optional.of(own.get(0));
        if (named.size() > 1) {
            log.warn("learn 同名手工卡多张，取第一张（只读）| userId={} | type={} | title={} | 命中 {} 张",
                    userId, type, title, named.size());
        }
        return Optional.of(named.get(0));
    }

    /**
     * 可写路径守卫（2026-09-12 读侧对齐批 → 结构统一批改为 origin 判据）：只允许改
     * **本实现产出的卡片**。别处整理的卡一律只读——既避免把产品模板段注入别人的文件，
     * 也把原先那句莫名的「卡片不存在」换成说得通的人话。
     *
     * @throws LearnException 卡不存在 / 不是本实现产出的卡（消息为人话）
     */
    private Located requireWritable(String userId, String type, LearnCard card) {
        Located located = locate(userId, type, card.title())
                .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, card.title())));
        if (!located.card().writable()) {
            throw new LearnException(readOnlyMessage(located.card().title()));
        }
        return located;
    }

    /**
     * 别处整理的手工卡：只读人话（含可行路径，别让用户撞墙）。
     * <p>
     * 对抗审查 P2-1（2026-09-12）：产品卡的判据是 frontmatter 的 {@code origin: product}，
     * 若该行被手工/别处工具改写掉，产品卡会退化成只读——所以话术要**如实**（不说死「一定是在
     * Mac 上整理的」）并给两条可行动路径。
     */
    private static String readOnlyMessage(String title) {
        return "这张《" + title + "》不是我在产品里写的（一般是在 Mac 上整理的，也可能它的来源标记被改过），"
                + "我只当资料看、不改动它；想改的话，我可以照它的内容另存一张能编辑的给你";
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
            // P1-learn2：同 type + 同 title 已存在 → 拒绝，防跨日同名歧义。
            // 2026-09-12：只挡**本产品产出**的同名卡——别处整理的手工卡同名不该拦住新建
            // （P2-learn20 修复：两者是不同文件，寻址时本产品卡优先）。
            Optional<Located> dup = locate(userId, card.type(), card.title())
                    .filter(l -> l.card().writable());
            if (dup.isPresent()) {
                throw new LearnException("已有同名卡片《" + card.title() + "》（创建于 "
                        + dup.get().card().created() + "），同标题内容请先查看/编辑已有卡片，避免重复消化");
            }
            String topic = LearnCard.topicDir(card.topic());
            String dir = LEARN_DIR + card.type() + "/" + topic + "/";
            String file = fileName(nextSeq(userId, dir), LearnCard.fileStem(card.title()));
            String path = dir + file;
            fileStorage.write(userId, path, toMarkdown(card));
            updateTopicReadme(userId, card.type(), topic, card, file);
            log.info("learn 卡片已落盘 | userId={} | type={} | topic={} | file={}", userId, card.type(), topic, file);
        }
    }

    @Override
    public Optional<LearnCard> find(String userId, String type, String title) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) return Optional.empty();
        return locate(userId, type, title).map(Located::card);
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
            String path = requireWritable(userId, type, card).path();
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
            // 返回**补全 topic/writable 的卡**（2026-09-12）：写响应缺这两个字段时，前端就地写回
            // 会把卡片从它的主题组跳到「未归类」（web 侧自查 P1，根治放在后端）
            return decorate(parse(updated), path, type, updated);
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
            String path = requireWritable(userId, type, card).path();
            String content = fileStorage.read(userId, path);
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(type, title));
            }
            String updated = replaceFrontmatterKey(content, "reminded_at",
                    today == null ? "" : today.toString());
            fileStorage.write(userId, path, updated);
            log.info("learn 复习提醒已标记 | userId={} | type={} | title={} | remindedAt={}",
                    userId, type, title, today);
            return decorate(parse(updated), path, type, updated);
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
            String path = requireWritable(userId, type, cur).path();
            String content = fileStorage.read(userId, path);
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(type, title));
            }
            String rewritten = rewriteManaged(content, updated);
            fileStorage.write(userId, path, rewritten);
            log.info("learn 卡片已编辑 | userId={} | type={} | title={}", userId, type, title);
            return decorate(parse(rewritten), path, type, rewritten);
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
            String path = requireWritable(userId, card.type(), existing).path();
            String content = fileStorage.read(userId, path);
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(card.type(), card.title()));
            }
            // 主题以**文件所在目录**为准（位置真相）：调用方传进来的卡若没带主题（缺省「未归类」），
            // 不能把 frontmatter 的 topic 覆盖成与目录不一致的值
            String rewritten = rewriteManaged(content, card.withTopic(existing.topic()));
            fileStorage.write(userId, path, rewritten);
            log.info("learn 卡片已更新 | userId={} | type={} | title={}", userId, card.type(), card.title());
            return decorate(parse(rewritten), path, card.type(), rewritten);
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
                cur.reviewAt(), cur.remindedAt(), cur.topic(), cur.writable());
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
        List<String> managedKeys = List.of("title", "type", "topic", "platform", "author", "url", "published",
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
            case "topic" -> LearnCard.topicDir(card.topic());
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
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) return false;
        // 兼容两种布局（老扁平 + 主题目录）：同名即视为已存在（防重复消化覆盖）
        return locate(userId, type, title).isPresent();
    }

    @Override
    public List<LearnCard> list(String userId, String type) {
        List<LearnCard> result = new ArrayList<>();
        for (Located l : locateAll(userId, type)) {
            result.add(l.card());
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
    public List<String> topics(String userId, String type) {
        if (!LearnCard.isValidType(type)) return List.of();
        java.util.LinkedHashSet<String> topics = new java.util.LinkedHashSet<>();
        for (Located l : locateAll(userId, type)) {
            if (!LearnCard.DEFAULT_TOPIC.equals(l.card().topic())) topics.add(l.card().topic());
        }
        return List.copyOf(topics);
    }

    @Override
    public String readCard(String userId, String type, String title) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) return null;
        return locate(userId, type, title)
                .map(l -> fileStorage.read(userId, l.path()))
                .orElse(null);
    }

    @Override
    public String cardPath(String userId, String type, String title) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) return null;
        return locate(userId, type, title).map(Located::path).orElse(null);
    }

    @Override
    public String deleteCard(String userId, String type, String title) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) {
            throw new LearnException("卡片不存在：" + safeLabel(type, title));
        }
        synchronized (lockFor(userId)) {
            LearnCard card = find(userId, type, title)
                    .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, title)));
            Located located = requireWritable(userId, type, card);
            String content = fileStorage.read(userId, located.path());
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(type, title));
            }
            // 软删除：先进 _trash（保留原主题信息，便于人工捡回），再摘掉老位置与索引行
            String trashName = com.adaiadai.core.kernel.IdGenerator.monotonic("learn_del_") + "-"
                    + baseName(located.path());
            fileStorage.write(userId, LEARN_DIR + TRASH_SUBDIR + "/" + trashName, content);
            fileStorage.delete(userId, located.path());
            removeTopicReadmeEntry(userId, type, located.card().topic(), baseName(located.path()));
            log.info("learn 卡片已删除（软删除到 _trash）| userId={} | {} → {}/{}",
                    userId, located.path(), TRASH_SUBDIR, trashName);
            return located.path();
        }
    }

    @Override
    public LearnCard moveToTopic(String userId, String type, String title, String newTopic) {
        if (!LearnCard.isValidType(type) || title == null || title.isBlank()) {
            throw new LearnException("卡片不存在：" + safeLabel(type, title));
        }
        synchronized (lockFor(userId)) {
            LearnCard card = find(userId, type, title)
                    .orElseThrow(() -> new LearnException("卡片不存在：" + safeLabel(type, title)));
            Located located = requireWritable(userId, type, card);
            String target = LearnCard.topicDir(newTopic);
            String oldTopic = located.card().topic();
            if (target.equals(oldTopic)) {
                return located.card();   // 同主题：幂等
            }
            String content = fileStorage.read(userId, located.path());
            if (content == null || content.isBlank()) {
                throw new LearnException("卡片不存在：" + safeLabel(type, title));
            }
            String dir = LEARN_DIR + type + "/" + target + "/";
            String file = fileName(nextSeq(userId, dir), LearnCard.fileStem(card.title()));
            String newPath = dir + file;
            String updated = replaceFrontmatterKey(content, "topic", target);
            fileStorage.write(userId, newPath, updated);
            try {
                fileStorage.delete(userId, located.path());
            } catch (Exception e) {
                // 老文件删不掉 → 回滚新文件，避免同题两张同名卡（locate 会 400，卡就废了）
                log.warn("learn 改主题删旧文件失败，已回滚 | userId={} | {} | {}", userId, located.path(), e.getMessage());
                try {
                    fileStorage.delete(userId, newPath);
                } catch (Exception rollback) {
                    log.error("learn 改主题回滚失败（两处都有副本，需人工合并）| userId={} | {}", userId, newPath);
                }
                throw new LearnException("这次没挪动（老位置删不掉），稍后再试一次");
            }
            removeTopicReadmeEntry(userId, type, oldTopic, baseName(located.path()));
            updateTopicReadme(userId, type, target, card.withTopic(target), file);
            // 老主题若已没有别的卡，把它的 _raw/ 素材一起搬过去——否则素材会留在空目录里成孤儿
            // （2026-09-13 生产实测发现：改主题只挪卡不挪素材，老主题只剩 README + _raw/）
            moveRawIfTopicEmptied(userId, type, oldTopic, target);
            log.info("learn 卡片已改主题 | userId={} | {} → {}", userId, located.path(), newPath);
            return decorate(parse(updated), newPath, type, updated);
        }
    }

    @Override
    public List<MigrationItem> migrateLegacy(String userId) {
        List<MigrationItem> items = new ArrayList<>();
        for (String type : List.of(LearnCard.TYPE_AI, LearnCard.TYPE_TRADING, LearnCard.TYPE_OTHER)) {
            for (String f : fileStorage.listFiles(userId, LEARN_DIR + type)) {
                if (!isLegacyFlat(f, type)) continue;
                synchronized (lockFor(userId)) {
                    MigrationItem item = migrateOne(userId, type, f);
                    if (item != null) items.add(item);
                }
            }
        }
        if (!items.isEmpty()) {
            log.info("learn 老式扁平卡迁移完成 | userId={} | {} 张", userId, items.size());
        }
        return items;
    }

    /** 老式扁平产品卡：直接放在 type 目录下、以日期开头。 */
    private static boolean isLegacyFlat(String path, String type) {
        String prefix = LEARN_DIR + type + "/";
        if (path == null || !path.startsWith(prefix)) return false;
        String rel = path.substring(prefix.length());
        return !rel.contains("/") && rel.endsWith(".md") && rel.matches("\\d{4}-\\d{2}-\\d{2}_.+\\.md");
    }

    /**
     * 迁移单张老扁平卡：补 origin/topic 键 → 落主题目录（续号）→ 维护 README → 删老文件。
     * 删不掉则回滚目标（不留两张同名可写卡）。内容不可解析（不是卡片）→ 原地不动（返回 null）。
     */
    private MigrationItem migrateOne(String userId, String type, String sourcePath) {
        String content = fileStorage.read(userId, sourcePath);
        if (content == null || content.isBlank()) return null;
        LearnCard card = parse(content);
        if (card == null || !type.equals(card.type())) return null;
        try {
            String topic = LearnCard.topicDir(frontmatterValue(content, "topic"));
            String updated = replaceFrontmatterKey(content, ORIGIN_KEY, ORIGIN_PRODUCT);
            updated = replaceFrontmatterKey(updated, "topic", topic);
            String dir = LEARN_DIR + type + "/" + topic + "/";
            String file = fileName(nextSeq(userId, dir), LearnCard.fileStem(card.title()));
            String target = dir + file;
            fileStorage.write(userId, target, updated);
            try {
                fileStorage.delete(userId, sourcePath);
            } catch (Exception deleteFailure) {
                // 宁可不迁：回滚目标，避免留下两张同名可写卡（locate 会 400，卡就废了）
                log.warn("learn 老卡迁移删源失败，已回滚 | userId={} | {} | {}",
                        userId, sourcePath, deleteFailure.getMessage());
                try {
                    fileStorage.delete(userId, target);
                } catch (Exception rollbackFailure) {
                    log.error("learn 迁移回滚失败（目标已留副本，需人工合并）| userId={} | {} | {}",
                            userId, target, rollbackFailure.getMessage());
                }
                return null;
            }
            updateTopicReadme(userId, type, topic, card.withTopic(topic), file);
            log.info("learn 老卡已迁移 | userId={} | {} → {}", userId, sourcePath, target);
            return new MigrationItem(type, sourcePath, target);
        } catch (Exception e) {
            log.warn("learn 老卡迁移失败（原地保留，不影响使用）| userId={} | {} | {}",
                    userId, sourcePath, e.getMessage());
            return null;
        }
    }

    /** frontmatter 单键取值（迁移用：老卡可能有手工写的 topic）。只在 frontmatter 区内找，不误取正文。 */
    private static String frontmatterValue(String content, String key) {
        java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
                "^(---\\n)(.*?)(\\n---\\n)", java.util.regex.Pattern.DOTALL).matcher(content);
        String block = fm.find() ? fm.group(2) : "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?m)^" + java.util.regex.Pattern.quote(key) + ":\\s*(.*)$").matcher(block);
        return m.find() ? m.group(1).strip() : null;
    }

    // ── 主题 README 索引（追加式，不重写他人内容）──

    /**
     * 维护主题目录的 README 索引。
     * <p>
     * Mac 上技能整理的 README 是用户资产（有手写目录/frontmatter）——本实现**只追加**一个
     * 明确标记的自动段，绝不重写既有内容；产品自建的主题目录则直接建 README。
     */
    private void updateTopicReadme(String userId, String type, String topic, LearnCard card, String fileName) {
        String path = LEARN_DIR + type + "/" + topic + "/" + README_NAME;
        // 标题里的 []() 会破坏 markdown 链接（对抗审查 P3 2026-09-12）→ 归一为全角括号
        String label = singleLine(card.title()).replace('[', '（').replace(']', '）');
        String entry = "- [" + fileName + "](" + fileName + ") — " + label
                + "（" + card.sourceLabel() + "，" + card.created() + "）";
        try {
            String existing = fileStorage.read(userId, path);
            if (existing == null || existing.isBlank()) {
                fileStorage.write(userId, path, "# " + topic + " 学习资料\n\n" + README_MARKER + "\n\n" + entry + "\n");
            } else if (existing.contains(README_MARKER)) {
                if (!existing.contains("(" + fileName + ")")) {
                    fileStorage.append(userId, path, entry + "\n");
                }
            } else {
                fileStorage.append(userId, path, "\n" + README_MARKER + "\n\n" + entry + "\n");
            }
        } catch (Exception e) {
            // 索引是附带收益：写不进去不该让已花钱的消化失败（与留痕同口径）
            log.warn("learn 主题 README 更新失败 | userId={} | {}/{} | {}", userId, type, topic, e.getMessage());
        }
    }

    /**
     * 老主题搬空后把主题级 {@code _raw/} 素材跟着搬到新主题（2026-09-13 生产实测补）。
     * <p>
     * 契约里 {@code _raw/} 是**主题级**的（一个主题的多源素材放一起）。当一张卡被挪走、老主题里
     * 已经没有任何卡片时，那些素材其实都是这张卡的来源——留在空目录里就是孤儿，所以一起搬。
     * 老主题还有别的卡 → 不动（素材仍属于那个主题）。
     * <p>
     * 目标同名不覆盖（与 promoteRaw 同口径）：改名并存，绝不丢素材。
     */
    private void moveRawIfTopicEmptied(String userId, String type, String oldTopic, String newTopic) {
        String oldDir = LEARN_DIR + type + "/" + LearnCard.topicDir(oldTopic) + "/";
        if (!locateAll(userId, type).stream()
                .anyMatch(l -> LearnCard.topicDir(l.card().topic()).equals(LearnCard.topicDir(oldTopic)))) {
            String targetDir = LEARN_DIR + type + "/" + LearnCard.topicDir(newTopic) + "/" + RAW_SUBDIR + "/";
            for (String f : fileStorage.listFiles(userId, oldDir)) {
                if (!f.contains("/" + RAW_SUBDIR + "/")) continue;
                try {
                    String name = baseName(f);
                    byte[] content = fileStorage.readBytes(userId, f);
                    if (content == null) continue;
                    String target = targetDir + name;
                    if (fileStorage.readBytes(userId, target) != null) {
                        target = targetDir + versionedName(name);
                    }
                    fileStorage.writeBytes(userId, target, content);
                    fileStorage.delete(userId, f);
                    log.info("learn 素材随卡迁移 | userId={} | {} → {}", userId, f, target);
                } catch (Exception e) {
                    log.warn("learn 素材随卡迁移失败（原处保留）| userId={} | {} | {}", userId, f, e.getMessage());
                }
            }
        }
    }

    /** 从主题 README 的自动段里摘掉某张卡的行（删卡/改主题时调用；只动带标记的自动段）。 */
    private void removeTopicReadmeEntry(String userId, String type, String topic, String fileName) {
        String path = LEARN_DIR + type + "/" + LearnCard.topicDir(topic) + "/" + README_NAME;
        try {
            String existing = fileStorage.read(userId, path);
            if (existing == null || existing.isBlank() || !existing.contains(README_MARKER)) return;
            String marker = "(" + fileName + ")";
            StringBuilder kept = new StringBuilder();
            boolean removed = false;
            for (String line : existing.split("\n", -1)) {
                if (line.startsWith("- ") && line.contains(marker)) {
                    removed = true;
                    continue;
                }
                kept.append(line).append('\n');
            }
            if (removed) {
                fileStorage.write(userId, path, kept.toString());
                log.info("learn 主题 README 已摘除索引行 | userId={} | {}/{} | {}", userId, type, topic, fileName);
            }
        } catch (Exception e) {
            log.warn("learn 主题 README 摘行失败 | userId={} | {}/{} | {}", userId, type, topic, e.getMessage());
        }
    }

    @Override
    public void saveRawSource(String userId, String content) {
        String path = RAW_STAGING_DIR + com.adaiadai.core.kernel.IdGenerator.monotonic("learn_raw_") + ".txt";
        fileStorage.write(userId, path, content == null ? "" : content);
        log.info("learn 原始素材已留存 | userId={} | path={}", userId, path);
    }

    @Override
    public void saveRaw(String userId, String name, String content) {
        String path = RAW_STAGING_DIR + safeRawName(name);
        fileStorage.write(userId, path, content == null ? "" : content);
        log.info("learn 原始素材已留存（暂存）| userId={} | path={} | {} 字", userId, path,
                content == null ? 0 : content.length());
    }

    @Override
    public String readRaw(String userId, String name) {
        if (name == null || name.isBlank()) return null;
        String safe = safeRawName(name);
        String staged = fileStorage.read(userId, RAW_STAGING_DIR + safe);
        if (staged != null) return staged;
        // 归位后暂存区已删：按已落盘的主题目录回读（幂等复用转写稿的前提）
        for (String f : fileStorage.listFiles(userId, LEARN_DIR)) {
            if (f.endsWith("/" + RAW_SUBDIR + "/" + safe)) return fileStorage.read(userId, f);
        }
        return null;
    }

    @Override
    public void saveRawBytes(String userId, String name, byte[] bytes) {
        String path = RAW_STAGING_DIR + safeRawName(name);
        fileStorage.writeBytes(userId, path, bytes == null ? new byte[0] : bytes);
        log.info("learn 原始素材已留存（暂存/二进制）| userId={} | path={} | {} 字节", userId, path,
                bytes == null ? 0 : bytes.length);
    }

    @Override
    public byte[] readRawBytes(String userId, String name) {
        if (name == null || name.isBlank()) return null;
        String safe = safeRawName(name);
        byte[] staged = fileStorage.readBytes(userId, RAW_STAGING_DIR + safe);
        if (staged != null) return staged;
        for (String f : fileStorage.listFiles(userId, LEARN_DIR)) {
            if (f.endsWith("/" + RAW_SUBDIR + "/" + safe)) return fileStorage.readBytes(userId, f);
        }
        return null;
    }

    @Override
    public void deleteRaw(String userId, String name) {
        if (name == null || name.isBlank()) return;
        String safe = safeRawName(name);
        String staged = RAW_STAGING_DIR + safe;
        if (fileStorage.exists(userId, staged)) fileStorage.delete(userId, staged);
        for (String f : fileStorage.listFiles(userId, LEARN_DIR)) {
            if (f.endsWith("/" + RAW_SUBDIR + "/" + safe)) fileStorage.delete(userId, f);
        }
    }

    @Override
    public List<String> promoteRaw(String userId, String type, String topic, List<String> names) {
        if (!LearnCard.isValidType(type) || names == null || names.isEmpty()) return List.of();
        String dir = LEARN_DIR + type + "/" + LearnCard.topicDir(topic) + "/" + RAW_SUBDIR + "/";
        List<String> promoted = new ArrayList<>();
        for (String rawName : names) {
            try {
                String safe = safeRawName(rawName);
                String stagedPath = RAW_STAGING_DIR + safe;
                // 字节通道取内容（文本与二进制都不改写），再按「能不能当文本读」决定落盘通道：
                // 文本素材保持文本（readRaw 可读）；二进制（原图）走 writeBytes
                byte[] content = fileStorage.readBytes(userId, stagedPath);
                if (content == null) continue;               // 未产生该素材（如未转写）→ 跳过
                String target = dir + safe;
                byte[] existing = fileStorage.readBytes(userId, target);
                if (existing != null) {
                    if (java.util.Arrays.equals(existing, content)) {
                        fileStorage.delete(userId, stagedPath);   // 同一份素材已归位过 → 幂等收尾
                        promoted.add(safe);
                        continue;
                    }
                    // 对抗审查 P2-2（2026-09-12）修复：目标已存在且内容不同（同一源更新后重跑）
                    // → **不覆盖**（源必留痕：旧留痕也是证据），改名并存 + WARN
                    target = dir + versionedName(safe);
                    log.warn("learn 素材归位遇同名不同内容，改用新名并存 | userId={} | {} → {}",
                            userId, safe, target);
                }
                String text = tryReadText(userId, stagedPath);
                if (text != null) {
                    fileStorage.write(userId, target, text);
                } else {
                    fileStorage.writeBytes(userId, target, content);
                }
                fileStorage.delete(userId, stagedPath);
                promoted.add(baseName(target));
            } catch (Exception e) {
                log.warn("learn 素材归位失败 | userId={} | name={} | {}", userId, rawName, e.getMessage());
            }
        }
        if (!promoted.isEmpty()) {
            log.info("learn 素材已归位到主题目录 | userId={} | {}/{} | {} 个", userId, type, topic, promoted.size());
        }
        return promoted;
    }

    /** 同名素材的版本化文件名（{@code a.txt} → {@code a-2.txt}、{@code a-3.txt}…）。 */
    private static String versionedName(String safe) {
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        for (int v = 2; v < 100; v++) {
            String candidate = stem + "-" + v + ext;
            if (!candidate.equals(safe)) return candidate;
        }
        return stem + "-dup" + ext;
    }

    /** 尝试按文本读（二进制素材读不出文本 → null，交由字节通道处理）。 */
    private String tryReadText(String userId, String path) {
        try {
            return fileStorage.read(userId, path);
        } catch (Exception e) {
            return null;
        }
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

    /** 主题内下一个编号（NN 递增；扫该主题目录下已存在**卡片**文件的数字前缀）。 */
    private int nextSeq(String userId, String dir) {
        int max = 0;
        for (String f : fileStorage.listFiles(userId, dir)) {
            if (f.contains("/" + RAW_SUBDIR + "/") || isReadme(f)) continue;   // 素材/索引不占编号
            String base = baseName(f);
            int dash = base.indexOf('-');
            if (dash <= 0 || dash > 3) continue;   // 编号是 1~3 位（`01-`/`12-`/`100-`）；`2026-09-12_x.md` 不当编号
            try {
                max = Math.max(max, Integer.parseInt(base.substring(0, dash)));
            } catch (NumberFormatException ignored) {
                // 非编号文件跳过
            }
        }
        return max + 1;
    }

    private static String fileName(int seq, String stem) {
        return String.format("%02d-%s.md", seq, stem);
    }

    private static String baseName(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String singleLine(String text) {
        if (text == null) return "";
        return text.replace("\n", " ").replace("\r", " ").replaceAll(" +", " ").strip();
    }

    /** 渲染 md（新建卡）：frontmatter 扁平字段 + 正文四段（V1 模板四段空段补齐）。 */
    static String toMarkdown(LearnCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(singleLine(card.title())).append("\n");
        sb.append("type: ").append(card.type()).append("\n");
        // origin：本实现写出的卡带此标记（判定可写）；Mac 上技能整理的卡没有该键 → 只读
        sb.append("origin: ").append(ORIGIN_PRODUCT).append("\n");
        sb.append("topic: ").append(LearnCard.topicDir(card.topic())).append("\n");
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
